package com.mhtextractor.engine;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.ExtractionStats;
import com.mhtextractor.processor.BatchProcessor;
import com.mhtextractor.uiport.PortEvent;
import com.mhtextractor.util.OutputUnitPlanner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B —— 取消统计回写竞态：{@link WorkspaceExtractionCoordinator.RunChannel} 的每 run 结果通道
 * + 完成信号 + 陈旧守卫（原 {@code ExtractionTaskCoordinatorRunChannelTest}，P3-B6 随通道下沉
 * 搬到 app 包 —— <b>零 JavaFX、不启动 toolkit</b>，与 {@code WorkspaceImportCoordinatorTest}
 * 同思路的无头驱动）。断言面逐条平移，语义不得放宽。
 *
 * <h2>三个时点怎么表达</h2>
 * <ol>
 *   <li>① 请求取消 —— 取消终态已交付 = 本测试先调 {@code deliverAfterWorkerFinished}；</li>
 *   <li>② worker 停止并产出 partial —— 本测试后调 {@code channel.result.complete(stats)}；</li>
 *   <li>③ 宿主线程收到回写并投递 —— 注入的派发器把回写动作放进队列，测试线程按序 drain。</li>
 * </ol>
 * "先注册、后完成"的顺序关系由<b>调用顺序</b>直接表达（不是 sleep 抢时序），旧实现的缺陷
 * （onCancelled 直接读 {@code lastExtractStats} → null / 上一次运行的残留）由各用例的断言
 * 反向钉死：<b>worker 未完成时不得有任何回写、完成后恰好一次、载荷 == worker 返回的统计</b>。
 *
 * <p>⑧ 一节补上「协调器级终态契约」（P3-B6 下沉新增）：成功 / 失败 / 取消三个终态各自
 * <b>恰好交付一次</b> {@link PortEvent.ExtractionTerminal}、运行标记复位；取消终态在
 * 取消请求时刻（不等到 worker 退出），partial 回写经 {@link PortEvent.ExtractionResult}
 * 在 worker 收尾后恰好一次；旧任务迟到的终态/回写被代际守卫拦下。</p>
 */
class WorkspaceExtractionCoordinatorRunChannelTest {

    /** 等待队列里的投递动作（比"队列非空"宽裕，防 CI 抖动） */
    private static final long DELIVERY_TIMEOUT_SECONDS = 5;

    /** "worker 仍未完成"的观察窗口：窗口内队列必须保持为空 */
    private static final long QUIET_PROBE_MILLIS = 300;

    /** 构造 worker 统计（与生产同径：先建 {@code ProcessStats} 再经 {@link WorkspaceExtractionService#statsFrom} 搬运） */
    private static ExtractionStats stats(int success, int fail, int skip, int warn) {
        BatchProcessor.ProcessStats s = new BatchProcessor.ProcessStats();
        s.setSuccessCount(success);
        s.setFailCount(fail);
        s.setSkipCount(skip);
        s.setWarnCount(warn);
        return WorkspaceExtractionService.statsFrom(s);
    }

    /** 把队列里已投递的回写动作全部跑掉，返回执行过的动作数 */
    private static int drain(BlockingQueue<Runnable> q) throws InterruptedException {
        int ran = 0;
        Runnable task;
        while ((task = q.poll(200, TimeUnit.MILLISECONDS)) != null) {
            task.run();
            ran++;
        }
        return ran;
    }

    /** 把队列里已投递的动作全部跑掉（不数个数 —— 投递动作数与断言无关，事件流才是契约面）。 */
    private static int drainAll(BlockingQueue<Runnable> q) throws InterruptedException {
        int ran = 0;
        Runnable task;
        while ((task = q.poll(500, TimeUnit.MILLISECONDS)) != null) {
            task.run();
            ran++;
        }
        return ran;
    }

    // ==================== ②③ 取消回写：worker 完成后恰好一次 ====================

    /** 复现核心：取消终态先交付、worker 后完成 —— 回写必须等 worker，且恰好一次（旧实现读到 null 从不回写）。 */
    @Test
    void cancelWriteBackWaitsForWorkerAndAppliesExactlyOnce() throws Exception {
        AtomicLong generation = new AtomicLong(1);
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        List<ExtractionStats> applied = new ArrayList<>();
        WorkspaceExtractionCoordinator.RunChannel channel =
                new WorkspaceExtractionCoordinator.RunChannel(1, q::add);

        // 时点①：取消终态已交付（取消请求 ≠ worker 已退出）
        channel.deliverAfterWorkerFinished(generation::get, applied::add);

        // worker 尚未完成：不得有任何回写（旧实现此刻读 lastExtractStats 只会拿到 null）
        assertNull(q.poll(QUIET_PROBE_MILLIS, TimeUnit.MILLISECONDS),
                "worker 未完成时不得提前回写");

        // 时点②：worker 停止并产出 partial
        ExtractionStats partial = stats(2, 1, 0, 0);
        channel.result.complete(partial);

        // 时点③：派发器上恰好投递一次
        Runnable delivered = q.poll(DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(delivered, "worker 完成后应投递一次回写");
        delivered.run();
        assertEquals(List.of(partial), applied, "回写载荷必须 == worker 返回的统计");
        assertNull(q.poll(QUIET_PROBE_MILLIS, TimeUnit.MILLISECONDS), "不得有第二次回写");
    }

    /** ① 未处理任何单元时取消：worker 返回空统计，仍回写一次（载荷 = 空统计，未处理条目保持原状态）。 */
    @Test
    void cancelBeforeAnyUnitStillWritesBackEmptyStatsOnce() throws Exception {
        AtomicLong generation = new AtomicLong(1);
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        List<ExtractionStats> applied = new ArrayList<>();
        WorkspaceExtractionCoordinator.RunChannel channel =
                new WorkspaceExtractionCoordinator.RunChannel(1, q::add);

        channel.deliverAfterWorkerFinished(generation::get, applied::add);
        ExtractionStats empty = stats(0, 0, 0, 0);
        channel.result.complete(empty);

        assertEquals(1, drain(q), "应恰好投递一次");
        assertEquals(1, applied.size());
        assertSame(empty, applied.get(0), "空统计也要原样回写");
        assertEquals(0, applied.get(0).failCount());
        assertEquals(0, applied.get(0).skipCount());
        assertEquals(0, applied.get(0).warnCount());
    }

    /** ② 处理若干单元后取消：partial 恰回写一次，各计数与 worker 返回值逐字段一致；重复 complete 不再投递。 */
    @Test
    void cancelAfterSeveralUnitsWritesBackPartialExactlyOnceWithExactCounts() throws Exception {
        AtomicLong generation = new AtomicLong(1);
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        List<ExtractionStats> applied = new ArrayList<>();
        WorkspaceExtractionCoordinator.RunChannel channel =
                new WorkspaceExtractionCoordinator.RunChannel(1, q::add);

        channel.deliverAfterWorkerFinished(generation::get, applied::add);
        ExtractionStats partial = stats(2, 1, 3, 1);
        channel.result.complete(partial);

        assertEquals(1, drain(q), "应恰好投递一次");
        assertEquals(1, applied.size(), "partial 只回写一次");
        ExtractionStats got = applied.get(0);
        assertSame(partial, got, "回写载荷必须是 worker 返回的同一份统计");
        assertEquals(1, got.failCount(), "fail 计数逐字段一致");
        assertEquals(3, got.skipCount(), "skip 计数逐字段一致");
        assertEquals(1, got.warnCount(), "warn 计数逐字段一致");

        // CompletableFuture 语义：第二次 complete 被忽略，不得再投递
        channel.result.complete(stats(9, 9, 9, 9));
        assertNull(q.poll(QUIET_PROBE_MILLIS, TimeUnit.MILLISECONDS), "重复 complete 不得再投递");
    }

    /** ③ 取消与自然完成同时（worker 先完成、取消回写迟到注册）：已完成 future 上的回调仍恰好执行一次。 */
    @Test
    void cancelRacingWithNaturalCompletionStillDeliversOnce() throws Exception {
        AtomicLong generation = new AtomicLong(1);
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        List<ExtractionStats> applied = new ArrayList<>();
        WorkspaceExtractionCoordinator.RunChannel channel =
                new WorkspaceExtractionCoordinator.RunChannel(1, q::add);

        // 时点②先发生：worker 已完成
        ExtractionStats partial = stats(5, 0, 0, 0);
        channel.result.complete(partial);
        // 取消回写迟到注册（取消请求到达时 worker 刚好已停）
        channel.deliverAfterWorkerFinished(generation::get, applied::add);

        assertEquals(1, drain(q), "对已完成 future 注册的回调仍应恰好投递一次");
        assertEquals(List.of(partial), applied);
    }

    // ==================== ④ 陈旧守卫：旧 run 的迟到回调不得污染新 run ====================

    /** ④ 取消后立即再开新任务：旧 run 的回写 no-op，新 run 自己正常回写一次。 */
    @Test
    void lateWriteBackOfStaleRunIsNoOpAndNewRunWritesOnce() throws Exception {
        AtomicLong generation = new AtomicLong(1);
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        List<ExtractionStats> applied = new ArrayList<>();
        WorkspaceExtractionCoordinator.RunChannel run1 =
                new WorkspaceExtractionCoordinator.RunChannel(1, q::add);

        run1.deliverAfterWorkerFinished(generation::get, applied::add);
        // 新 run 已开始：runGeneration 递增
        generation.incrementAndGet();
        assertFalse(run1.isCurrent(generation.get()), "新 run 开始后旧 run 不再 current");
        assertTrue(run1.isCurrent(1), "isCurrent 与 runId 的纯比较");

        // 旧 worker 迟到完成：回写动作被投递，但在派发目标线程上的守卫处 no-op（不调用 applyResults）
        run1.result.complete(stats(1, 0, 0, 0));
        assertEquals(1, drain(q), "旧 run 的迟到回写动作应被投递一次");
        assertTrue(applied.isEmpty(), "新 run 期间宿主不得收到旧 run 的统计（守卫处 no-op）");

        // 新 run 自己的通道照常工作
        WorkspaceExtractionCoordinator.RunChannel run2 =
                new WorkspaceExtractionCoordinator.RunChannel(2, q::add);
        assertTrue(run2.isCurrent(generation.get()));
        run2.deliverAfterWorkerFinished(generation::get, applied::add);
        ExtractionStats run2Stats = stats(3, 0, 0, 0);
        run2.result.complete(run2Stats);
        assertEquals(1, drain(q), "新 run 应恰好回写一次");
        assertEquals(1, applied.size());
        assertSame(run2Stats, applied.get(0), "新 run 回写载荷 == worker 返回的统计");
    }

    // ==================== ⑤⑥ 中断响应慢 / 失败路径 ====================

    /** ⑤ 中断响应慢：worker 无视中断继续处理，回写只认"处理完成"（闸门不可被中断打破）。 */
    @Test
    void slowInterruptWorkerDeliversOnlyAfterItActuallyFinishes() throws Exception {
        AtomicLong generation = new AtomicLong(1);
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        List<ExtractionStats> applied = new ArrayList<>();
        WorkspaceExtractionCoordinator.RunChannel channel =
                new WorkspaceExtractionCoordinator.RunChannel(1, q::add);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interruptSwallowed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExtractionStats workerStats = stats(4, 0, 0, 0);
        Thread worker = new Thread(() -> {
            entered.countDown();
            try {
                release.await();                       // 闸门：可被 interrupt 打断
            } catch (InterruptedException e) {
                interruptSwallowed.countDown();        // 吞掉取消请求的中断：取消请求 ≠ worker 已退出
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // 只会再来一次中断，不会再被吞
                }
            }
            channel.result.complete(workerStats);
        }, "run-channel-slow-worker");
        worker.setDaemon(true);

        // 时点①：取消回写已注册
        channel.deliverAfterWorkerFinished(generation::get, applied::add);
        worker.start();
        assertTrue(entered.await(DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS), "worker 应已进入闸门");
        worker.interrupt();                            // 模拟取消请求的中断
        assertTrue(interruptSwallowed.await(DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "中断应已被 worker 吞掉（worker 仍在处理中）");
        assertNull(q.poll(QUIET_PROBE_MILLIS, TimeUnit.MILLISECONDS),
                "中断后 worker 尚未完成：不得回写");

        release.countDown();                           // 放行：worker 终于完成处理
        worker.join(10000);
        assertFalse(worker.isAlive(), "worker 应在放行后退出");
        assertEquals(1, drain(q), "worker 完成后恰好投递一次");
        assertEquals(1, applied.size());
        assertSame(workerStats, applied.get(0), "回写载荷 == worker 返回的统计");
    }

    /** ⑥ 失败路径：worker 异常 → 通道完成 null → 回写动作投递但不调用 applyResults（无统计可回写）。 */
    @Test
    void failedWorkerCompletesNullAndAppliesNothing() throws Exception {
        AtomicLong generation = new AtomicLong(1);
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        List<ExtractionStats> applied = new ArrayList<>();
        WorkspaceExtractionCoordinator.RunChannel channel =
                new WorkspaceExtractionCoordinator.RunChannel(1, q::add);

        channel.deliverAfterWorkerFinished(generation::get, applied::add);
        channel.result.complete(null);                 // worker 异常路径

        assertEquals(1, drain(q), "通道应投递一次收尾动作");
        assertTrue(applied.isEmpty(), "null 统计不得触发 applyResults");
    }

    // ==================== ⑧ 协调器级终态契约（成功 / 失败 / 取消各恰好一次） ====================

    /** 事件订阅器：把协调器的事件流抄进列表（事件在派发器投递线程同步送达）。 */
    private static final class EventLog {
        final List<PortEvent> events = new ArrayList<>();
        final AutoCloseable subscription;

        EventLog(WorkspaceExtractionCoordinator coordinator) {
            subscription = coordinator.subscribe(events::add);
        }
    }

    /** 单单元请求（空文件列表也足以驱动一次单元调用：单元执行器是接缝，不真解析）。 */
    private static WorkspaceExtractionService.ExtractionRequest request(String session) {
        List<OutputUnitPlanner.OutputUnit> units = List.of(
                new OutputUnitPlanner.OutputUnit(null, false, new AppConfig(), List.of("p"), session,
                        new com.mhtextractor.model.OutputName(null, "txt"), null));
        return new WorkspaceExtractionService.ExtractionRequest(session, units, Map.of(), false, () -> true);
    }

    /** 注入桩单元执行器的协调器：worker 行为由桩决定，派发器 = 测试线程按序 drain 的队列。 */
    private WorkspaceExtractionCoordinator coordinator(WorkspaceExtractionService.UnitProcessor unit,
                                                       BlockingQueue<Runnable> q) {
        return new WorkspaceExtractionCoordinator(new WorkspaceExtractionService(unit),
                checkedOnly -> request("session-a"), q::add);
    }

    /** ⑧-成功：自然完成 —— SUCCEEDED 终态恰好一次（载荷 = worker 统计）、运行标记复位、无额外回写。 */
    @Test
    void successTerminalLandsRunningOffAndDeliversStatsExactlyOnce() throws Exception {
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        ExtractionStats workerStats = stats(2, 0, 0, 0);
        WorkspaceExtractionCoordinator c = coordinator(
                (unit, session, overrides, cancel, recursion, cb) -> {
                    BatchProcessor.ProcessStats s = new BatchProcessor.ProcessStats();
                    s.setSuccessCount(2);
                    return s;
                }, q);
        EventLog log = new EventLog(c);

        c.startExtraction(false);
        assertTrue(c.running.get(), "启动路径必须置运行标记");
        drainAll(q);
        assertEquals(1, log.events.size(), "成功终态必须恰好交付一次");
        PortEvent.ExtractionTerminal terminal = (PortEvent.ExtractionTerminal) log.events.get(0);
        assertEquals(PortEvent.ExtractionEnd.SUCCEEDED, terminal.end());
        assertEquals("session-a", terminal.sessionOutputFolder(), "终态载荷必须带会话目录");
        assertEquals(0, terminal.stats().failCount(), "载荷计数来自 worker 统计（无失败）");
        assertSame(c.lastExtractStats.get(), terminal.stats(), "终态载荷 == holder 里 worker 返回的统计");
        assertFalse(c.running.get(), "成功终态必须复位运行标记");
        assertNull(q.poll(QUIET_PROBE_MILLIS, TimeUnit.MILLISECONDS), "不得有第二次终态");
    }

    /** ⑧-取消：取消请求时刻（时点①）交付 CANCELLED 终态恰好一次（不等到 worker 退出）、运行标记立即复位；
     *  worker 收尾（时点②/③）后 partial 经 ExtractionResult 恰好一次、且不得有第二次终态。 */
    @Test
    void cancelTerminalLandsAtRequestTimeAndPartialDeliveredOnceAfterWorker() throws Exception {
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WorkspaceExtractionCoordinator c = coordinator(
                (unit, session, overrides, cancel, recursion, cb) -> {
                    entered.countDown();
                    try {
                        release.await();               // 闸门吞中断：取消请求 ≠ worker 已退出
                    } catch (InterruptedException e) {
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException ignored) {
                            // 至多再来一次中断
                        }
                    }
                    BatchProcessor.ProcessStats s = new BatchProcessor.ProcessStats();
                    s.setSuccessCount(2);
                    s.setFailCount(1);
                    return s;
                }, q);
        EventLog log = new EventLog(c);

        c.startExtraction(false);
        assertTrue(entered.await(DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS), "worker 应已进入闸门");

        // 时点①：取消请求 —— 终态此刻交付（不等到 worker 退出），partial 尚不存在
        c.cancelExtraction();
        assertFalse(c.running.get(), "取消终态必须立即复位运行标记（不等到 worker 退出）");
        assertEquals(1, log.events.size(), "取消终态必须此刻交付恰一次");
        PortEvent.ExtractionTerminal terminal = (PortEvent.ExtractionTerminal) log.events.get(0);
        assertEquals(PortEvent.ExtractionEnd.CANCELLED, terminal.end());
        assertNull(terminal.stats(), "取消终态不得携带统计（partial 尚不存在）");
        assertEquals(0, drain(q), "worker 未完成时不得投递任何回写");

        // 时点②/③：放行 worker → partial 经结果通道恰好一次（收尾守卫 + 回写两个动作都被投递）
        release.countDown();
        drainAll(q);
        assertEquals(2, log.events.size(), "取消回写必须恰好一次");
        PortEvent.ExtractionResult result = (PortEvent.ExtractionResult) log.events.get(1);
        assertEquals(1, result.stats().failCount(), "partial 计数来自 worker 统计");
        assertSame(c.lastExtractStats.get(), result.stats(), "回写载荷 == worker 返回的统计（同一对象）");
        assertEquals(1, log.events.stream().filter(e -> e instanceof PortEvent.ExtractionTerminal).count(),
                "取消路径不得有第二次终态");
        assertFalse(c.running.get(), "回写不得复活运行标记");
    }

    /** ⑧-裁决：worker 已收尾后取消（{@code result.isDone()}）→ 不得交付第二次终态
     *  （等价 {@code FutureTask.cancel} 对已完成任务 no-op：成功终态已在途/已交付）。 */
    @Test
    void cancelAfterWorkerFinishedDoesNotDeliverSecondTerminal() throws Exception {
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        WorkspaceExtractionCoordinator c = coordinator(
                (unit, session, overrides, cancel, recursion, cb) -> {
                    BatchProcessor.ProcessStats s = new BatchProcessor.ProcessStats();
                    s.setSuccessCount(2);
                    return s;
                }, q);
        EventLog log = new EventLog(c);

        c.startExtraction(false);
        drainAll(q);                                   // 成功终态已交付
        assertEquals(1, log.events.size(), "成功终态必须恰好交付一次");
        c.cancelExtraction();                          // worker 已收尾：no-op（不得第二次终态）
        assertEquals(1, log.events.size(), "不得交付第二次终态");
        assertFalse(c.running.get(), "运行标记保持复位");
    }

    /** ⑧-失败：worker 抛 Error（非 Exception；生产 Task 框架把 Error 一视同仁路由到 onFailed）——
     *  FAILED 终态恰好一次（stats = null）、运行标记复位、无额外投递。
     *  只捕 Exception 的旧写法会把通道晾死（result 永不完成、无终态、运行标记卡在 true），
     *  本用例反向钉死该语义不得放窄（与迁移前 onFailed 同口径）。 */
    @Test
    void failedWorkerLandsFailedTerminalExactlyOnceWithNullStats() throws Exception {
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        WorkspaceExtractionCoordinator c = coordinator(
                (unit, session, overrides, cancel, recursion, cb) -> {
                    throw new AssertionError("worker boom");   // 服务内层只捕 Exception，Error 直达 worker 收尾
                }, q);
        EventLog log = new EventLog(c);

        c.startExtraction(false);
        assertTrue(c.running.get(), "启动路径必须置运行标记");
        drainAll(q);
        assertEquals(1, log.events.size(), "失败终态必须恰好交付一次");
        PortEvent.ExtractionTerminal terminal = (PortEvent.ExtractionTerminal) log.events.get(0);
        assertEquals(PortEvent.ExtractionEnd.FAILED, terminal.end());
        assertNull(terminal.stats(), "失败终态不得携带统计");
        assertFalse(c.running.get(), "失败终态必须复位运行标记");
        assertNull(q.poll(QUIET_PROBE_MILLIS, TimeUnit.MILLISECONDS), "不得有第二次终态");
    }

    /** ⑧-陈旧：取消后立即再开新任务 —— 旧 run 迟到的回写与收尾被代际守卫拦下，新 run 自己的终态照常交付。 */
    @Test
    void cancelThenImmediatelyStartNewRunKeepsLateDeliveriesFromPolluting() throws Exception {
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 用可变工厂表达两次启动的不同会话：run 1 钉在闸门上，run 2 立即完成
        java.util.concurrent.atomic.AtomicReference<String> session =
                new java.util.concurrent.atomic.AtomicReference<>("session-old");
        WorkspaceExtractionCoordinator c = new WorkspaceExtractionCoordinator(
                new WorkspaceExtractionService((unit, sessionF, overrides, cancel, recursion, cb) -> {
                    if ("session-old".equals(sessionF)) {
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            try {
                                release.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException ignored) {
                                // 至多再来一次中断
                            }
                        }
                    }
                    BatchProcessor.ProcessStats s = new BatchProcessor.ProcessStats();
                    s.setSuccessCount(3);
                    return s;
                }), checkedOnly -> request(session.get()), q::add);
        EventLog log = new EventLog(c);

        c.startExtraction(false);                      // run 1：钉在闸门
        assertTrue(entered.await(DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS), "run 1 worker 应已进入闸门");
        c.cancelExtraction();                          // 时点①：run 1 取消终态 + 回写注册
        session.set("session-new");
        c.startExtraction(false);                      // run 2：立即再开（代际递增）
        drainAll(q);                                   // run 2 worker 收尾投递

        release.countDown();                           // run 1 worker 迟到完成
        drainAll(q);                                   // 迟到的收尾 + 回写动作仍被投递（在守卫处 no-op）

        List<PortEvent.ExtractionTerminal> terminals = log.events.stream()
                .filter(e -> e instanceof PortEvent.ExtractionTerminal)
                .map(e -> (PortEvent.ExtractionTerminal) e).toList();
        assertEquals(2, terminals.size(), "每 run 各一个终态：run 1 取消 + run 2 成功");
        assertEquals(PortEvent.ExtractionEnd.CANCELLED, terminals.get(0).end());
        assertEquals(1L, terminals.get(0).generation(), "run 1 终态带 run 1 代际");
        assertEquals(PortEvent.ExtractionEnd.SUCCEEDED, terminals.get(1).end());
        assertEquals(2L, terminals.get(1).generation(), "run 2 终态带 run 2 代际");
        assertEquals(0, log.events.stream().filter(e -> e instanceof PortEvent.ExtractionResult).count(),
                "旧 run 的迟到回写被代际守卫拦下：不得向新 run 期间投递任何回写");
        assertFalse(c.running.get(), "run 2 终态必须复位运行标记");
    }
}
