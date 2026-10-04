package com.mhtextractor.gui.controller;

import com.mhtextractor.app.WorkspaceExtractionService;
import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.cache.PreviewCacheStore;
import com.mhtextractor.processor.BatchProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B —— 取消统计回写竞态：{@link ExtractionTaskCoordinator.RunChannel} 的每 run 结果通道
 * + 完成信号 + 陈旧守卫（<b>零 JavaFX、不启动 toolkit</b>，与
 * {@link ImportSessionCoordinatorQuotaTest} 同思路的无头驱动）。
 *
 * <h2>三个时点怎么表达</h2>
 * <ol>
 *   <li>① 请求取消 —— {@code onCancelled} 已触发 = 本测试先调 {@code deliverAfterWorkerFinished}；</li>
 *   <li>② worker 停止并产出 partial —— 本测试后调 {@code channel.result.complete(stats)}；</li>
 *   <li>③ UI 收到终态并回写 —— 注入的派发器把回写动作放进队列，测试线程按序 drain。</li>
 * </ol>
 * "先注册、后完成"的顺序关系由<b>调用顺序</b>直接表达（不是 sleep 抢时序），旧实现的缺陷
 * （onCancelled 直接读 {@code lastExtractStats} → null / 上一次运行的残留）由各用例的断言
 * 反向钉死：<b>worker 未完成时不得有任何回写、完成后恰好一次、载荷 == worker 返回的统计</b>。
 *
 * <p>⑦ 一节补上「三终态 UI 落地」（步骤 7 修复）：成功 / 失败 / 取消三个终态各自把
 * 运行标记复位、开始按钮按<b>注入的派生函数</b>重算 —— 经
 * {@link ExtractionTaskCoordinator#landTerminalState} 驱动（零 JavaFX，与①②③同口径）。
 * 停止按钮禁用（E1.1 起经 host）由 {@code ExtractionTaskCoordinatorCancelRaceTest} /
 * {@code ExtractionTaskCoordinatorGateTest} ④ 以真实控件断言。</p>
 */
class ExtractionTaskCoordinatorRunChannelTest {

    /** 等待队列里的投递动作（比"队列非空"宽裕，防 CI 抖动） */
    private static final long DELIVERY_TIMEOUT_SECONDS = 5;

    /** "worker 仍未完成"的观察窗口：窗口内队列必须保持为空 */
    private static final long QUIET_PROBE_MILLIS = 300;

    private static BatchProcessor.ProcessStats stats(int success, int fail, int skip, int warn) {
        BatchProcessor.ProcessStats s = new BatchProcessor.ProcessStats();
        s.setSuccessCount(success);
        s.setFailCount(fail);
        s.setSkipCount(skip);
        s.setWarnCount(warn);
        return s;
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

    // ==================== ②③ 取消回写：worker 完成后恰好一次 ====================

    /** 复现核心：onCancelled 先跑、worker 后完成 —— 回写必须等 worker，且恰好一次（旧实现读到 null 从不回写）。 */
    @Test
    void cancelWriteBackWaitsForWorkerAndAppliesExactlyOnce() throws Exception {
        AtomicLong generation = new AtomicLong(1);
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        List<BatchProcessor.ProcessStats> applied = new ArrayList<>();
        ExtractionTaskCoordinator.RunChannel channel =
                new ExtractionTaskCoordinator.RunChannel(1, q::add);

        // 时点①：onCancelled 已触发（取消请求 ≠ worker 已退出）
        channel.deliverAfterWorkerFinished(generation::get, applied::add);

        // worker 尚未完成：不得有任何回写（旧实现此刻读 lastExtractStats 只会拿到 null）
        assertNull(q.poll(QUIET_PROBE_MILLIS, TimeUnit.MILLISECONDS),
                "worker 未完成时不得提前回写");

        // 时点②：worker 停止并产出 partial
        BatchProcessor.ProcessStats partial = stats(2, 1, 0, 0);
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
        List<BatchProcessor.ProcessStats> applied = new ArrayList<>();
        ExtractionTaskCoordinator.RunChannel channel =
                new ExtractionTaskCoordinator.RunChannel(1, q::add);

        channel.deliverAfterWorkerFinished(generation::get, applied::add);
        BatchProcessor.ProcessStats empty = stats(0, 0, 0, 0);
        channel.result.complete(empty);

        assertEquals(1, drain(q), "应恰好投递一次");
        assertEquals(1, applied.size());
        assertSame(empty, applied.get(0), "空统计也要原样回写");
        assertEquals(0, applied.get(0).getSuccessCount());
        assertEquals(0, applied.get(0).getFailCount());
    }

    /** ② 处理若干单元后取消：partial 恰回写一次，各计数与 worker 返回值逐字段一致；重复 complete 不再投递。 */
    @Test
    void cancelAfterSeveralUnitsWritesBackPartialExactlyOnceWithExactCounts() throws Exception {
        AtomicLong generation = new AtomicLong(1);
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        List<BatchProcessor.ProcessStats> applied = new ArrayList<>();
        ExtractionTaskCoordinator.RunChannel channel =
                new ExtractionTaskCoordinator.RunChannel(1, q::add);

        channel.deliverAfterWorkerFinished(generation::get, applied::add);
        BatchProcessor.ProcessStats partial = stats(2, 1, 3, 1);
        channel.result.complete(partial);

        assertEquals(1, drain(q), "应恰好投递一次");
        assertEquals(1, applied.size(), "partial 只回写一次");
        BatchProcessor.ProcessStats got = applied.get(0);
        assertSame(partial, got, "回写载荷必须是 worker 返回的同一份统计");
        assertEquals(2, got.getSuccessCount(), "success 计数逐字段一致");
        assertEquals(1, got.getFailCount(), "fail 计数逐字段一致");
        assertEquals(3, got.getSkipCount(), "skip 计数逐字段一致");
        assertEquals(1, got.getWarnCount(), "warn 计数逐字段一致");

        // CompletableFuture 语义：第二次 complete 被忽略，不得再投递
        channel.result.complete(stats(9, 9, 9, 9));
        assertNull(q.poll(QUIET_PROBE_MILLIS, TimeUnit.MILLISECONDS), "重复 complete 不得再投递");
    }

    /** ③ 取消与自然完成同时（worker 先完成、onCancelled 迟到注册）：已完成 future 上的回调仍恰好执行一次。 */
    @Test
    void cancelRacingWithNaturalCompletionStillDeliversOnce() throws Exception {
        AtomicLong generation = new AtomicLong(1);
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        List<BatchProcessor.ProcessStats> applied = new ArrayList<>();
        ExtractionTaskCoordinator.RunChannel channel =
                new ExtractionTaskCoordinator.RunChannel(1, q::add);

        // 时点②先发生：worker 已完成
        BatchProcessor.ProcessStats partial = stats(5, 0, 0, 0);
        channel.result.complete(partial);
        // onCancelled 迟到注册（取消请求到达时 worker 刚好已停）
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
        List<BatchProcessor.ProcessStats> applied = new ArrayList<>();
        ExtractionTaskCoordinator.RunChannel run1 =
                new ExtractionTaskCoordinator.RunChannel(1, q::add);

        run1.deliverAfterWorkerFinished(generation::get, applied::add);
        // 新 run 已开始：runGeneration 递增
        generation.incrementAndGet();
        assertFalse(run1.isCurrent(generation.get()), "新 run 开始后旧 run 不再 current");
        assertTrue(run1.isCurrent(1), "isCurrent 与 runId 的纯比较");

        // 旧 worker 迟到完成：回写动作被投递，但在派发目标线程上的守卫处 no-op（不调用 applyResults）
        run1.result.complete(stats(1, 0, 0, 0));
        assertEquals(1, drain(q), "旧 run 的迟到回写动作应被投递一次");
        assertTrue(applied.isEmpty(), "新 run 期间 UI 不得收到旧 run 的统计（守卫处 no-op）");

        // 新 run 自己的通道照常工作
        ExtractionTaskCoordinator.RunChannel run2 =
                new ExtractionTaskCoordinator.RunChannel(2, q::add);
        assertTrue(run2.isCurrent(generation.get()));
        run2.deliverAfterWorkerFinished(generation::get, applied::add);
        run2.result.complete(stats(3, 0, 0, 0));
        assertEquals(1, drain(q), "新 run 应恰好回写一次");
        assertEquals(1, applied.size());
        assertEquals(3, applied.get(0).getSuccessCount());
    }

    // ==================== ⑤⑥ 中断响应慢 / 失败路径 ====================

    /** ⑤ 中断响应慢：worker 无视中断继续处理，回写只认"处理完成"（闸门不可被中断打破）。 */
    @Test
    void slowInterruptWorkerDeliversOnlyAfterItActuallyFinishes() throws Exception {
        AtomicLong generation = new AtomicLong(1);
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        List<BatchProcessor.ProcessStats> applied = new ArrayList<>();
        ExtractionTaskCoordinator.RunChannel channel =
                new ExtractionTaskCoordinator.RunChannel(1, q::add);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interruptSwallowed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            entered.countDown();
            try {
                release.await();                       // 闸门：可被 interrupt 打断
            } catch (InterruptedException e) {
                interruptSwallowed.countDown();        // 吞掉 Task.cancel(true) 的中断：取消请求 ≠ worker 已退出
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // 只会再来一次中断，不会再被吞
                }
            }
            channel.result.complete(stats(4, 0, 0, 0));
        }, "run-channel-slow-worker");
        worker.setDaemon(true);

        // 时点①：onCancelled 已注册
        channel.deliverAfterWorkerFinished(generation::get, applied::add);
        worker.start();
        assertTrue(entered.await(DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS), "worker 应已进入闸门");
        worker.interrupt();                            // 模拟 cancel(true) 的中断
        assertTrue(interruptSwallowed.await(DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "中断应已被 worker 吞掉（worker 仍在处理中）");
        assertNull(q.poll(QUIET_PROBE_MILLIS, TimeUnit.MILLISECONDS),
                "中断后 worker 尚未完成：不得回写");

        release.countDown();                           // 放行：worker 终于完成处理
        worker.join(10000);
        assertFalse(worker.isAlive(), "worker 应在放行后退出");
        assertEquals(1, drain(q), "worker 完成后恰好投递一次");
        assertEquals(1, applied.size());
        assertEquals(4, applied.get(0).getSuccessCount());
    }

    /** ⑥ 失败路径：worker 异常 → 通道完成 null → 回写动作投递但不调用 applyResults（无统计可回写）。 */
    @Test
    void failedWorkerCompletesNullAndAppliesNothing() throws Exception {
        AtomicLong generation = new AtomicLong(1);
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        List<BatchProcessor.ProcessStats> applied = new ArrayList<>();
        ExtractionTaskCoordinator.RunChannel channel =
                new ExtractionTaskCoordinator.RunChannel(1, q::add);

        channel.deliverAfterWorkerFinished(generation::get, applied::add);
        channel.result.complete(null);                 // call() 异常路径

        assertEquals(1, drain(q), "通道应投递一次收尾动作");
        assertTrue(applied.isEmpty(), "null 统计不得触发 applyResults");
    }

    // ==================== ⑦ 三终态 UI 落地（成功 / 失败 / 取消各一例） ====================

    @TempDir
    Path tmp;

    /** 记录终态落地动作的桩 UI（零 JavaFX）：E1.1 起停止按钮禁用走 host（本测试 host 为 null，
     *  跳过），落地契约的观测点是运行标记 + 注入派生函数 + 状态栏文案零写入。 */
    private static final class LandingUi implements ExtractionTaskCoordinator.ExtractionUi {
        final List<BatchProcessor.ProcessStats> applied = new ArrayList<>();
        int statusTextCalls;

        @Override public File sessionOutputFolder() { return new File("."); }
        @Override public boolean openFolderAfterComplete() { return false; }
        @Override public void setProgress(double value) { }
        @Override public void setProgressBarOutcome(ExtractionTaskCoordinator.ExtractOutcomeStyle style) { }
        @Override public void extractionStarted() { }
        @Override public void applyResults(BatchProcessor.ProcessStats stats) { applied.add(stats); }
        @Override public void setStatusText(String text) { statusTextCalls++; }
    }

    /** 3 参构造 + 注入派生函数计数（host 为 null：不注册顺序门，与 1/2 参构造同口径）。 */
    private ExtractionTaskCoordinator coordinator(AtomicInteger derivedCalls) {
        return new ExtractionTaskCoordinator(new WorkspaceExtractionService(
                new PreviewCacheLifecycle(new PreviewCacheStore(tmp.resolve("cache")))), null,
                derivedCalls::incrementAndGet);
    }

    /** ⑦-成功：onSucceeded 的落地 —— 运行标记复位、开始按派生函数重算恰一次（落地本身不回写统计、不改文案）。
     *  停止按钮禁用走 host（E1.1），其"恰一次"由 {@code ExtractionTaskCoordinatorCancelRaceTest}（真实控件）与
     *  {@code ExtractionTaskCoordinatorGateTest} ④ 断言。 */
    @Test
    void successTerminalStateLandsRunningOffStopDisabledAndStartRecomputed() {
        AtomicInteger derivedCalls = new AtomicInteger();
        ExtractionTaskCoordinator c = coordinator(derivedCalls);
        LandingUi ui = new LandingUi();
        c.running.set(true);

        c.landTerminalState();

        assertFalse(c.running.get(), "成功终态必须复位运行标记");
        assertEquals(1, derivedCalls.get(), "成功终态必须按派生函数重算开始按钮恰一次");
        assertEquals(0, ui.statusTextCalls, "落地不得改状态栏文案");
        assertTrue(ui.applied.isEmpty(), "落地本身不得回写统计（回写是成功回调自身的动作）");
    }

    /** ⑦-失败：onFailed 的落地 —— 与成功同一落地函数；即使失败路径无统计可回写，运行标记照样复位。 */
    @Test
    void failedTerminalStateLandsRunningOffStopDisabledAndStartRecomputed() {
        AtomicInteger derivedCalls = new AtomicInteger();
        ExtractionTaskCoordinator c = coordinator(derivedCalls);
        LandingUi ui = new LandingUi();
        c.running.set(true);

        c.landTerminalState();

        assertFalse(c.running.get(), "失败终态必须复位运行标记");
        assertEquals(1, derivedCalls.get(), "失败终态必须按派生函数重算开始按钮恰一次");
        assertEquals(0, ui.statusTextCalls, "落地不得改状态栏文案");
        assertTrue(ui.applied.isEmpty(), "失败路径没有统计可回写（落地不依赖统计）");
    }

    /** ⑦-取消：onCancelled 的落地 + B 阶段回写不回归 —— 落地在时点①立即发生（不等到 worker 退出）；
     *  partial 回写仍恰一次、且只在 worker 完成（时点②）之后经派发器送达（时点③）。 */
    @Test
    void cancelTerminalStateLandsAndPartialWriteBackStillDeliveredOnceAfterWorker() throws Exception {
        AtomicInteger derivedCalls = new AtomicInteger();
        ExtractionTaskCoordinator c = coordinator(derivedCalls);
        LandingUi ui = new LandingUi();
        AtomicLong generation = new AtomicLong(1);
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        ExtractionTaskCoordinator.RunChannel channel =
                new ExtractionTaskCoordinator.RunChannel(1, q::add);
        c.running.set(true);

        // 时点①：onCancelled —— 落地立即发生（运行标记复位、开始重算）
        c.landTerminalState();
        assertFalse(c.running.get(), "取消终态必须复位运行标记（不等到 worker 退出）");
        assertEquals(1, derivedCalls.get(), "取消终态必须按派生函数重算开始按钮恰一次");

        // 取消回写仍在结果通道上挂"worker 完成后投递一次"（B 阶段口径不回退）
        channel.deliverAfterWorkerFinished(generation::get, ui::applyResults);
        assertNull(q.poll(QUIET_PROBE_MILLIS, TimeUnit.MILLISECONDS),
                "worker 未完成时不得提前回写（落地与回写解耦）");

        // 时点②：worker 停止并产出 partial → 时点③：派发器上恰好一次
        BatchProcessor.ProcessStats partial = stats(2, 1, 0, 0);
        channel.result.complete(partial);
        assertEquals(1, drain(q), "取消回写应恰好投递一次");
        assertEquals(List.of(partial), ui.applied, "回写载荷 == worker 返回的 partial");
        assertEquals(1, derivedCalls.get(), "派生函数不得随回写重复调用");
        assertFalse(c.running.get(), "回写不得复活运行标记");
    }

    /**
     * ⑦-幂等（E1.1）：{@code MainController.onStopProcessing → cancelAndReset} 已同步落地一次之后，
     * 任务线程迟到的 {@code onCancelled} 仍会在陈旧守卫通过时再落地一次 —— 两次落地结果必须相同、
     * 不得抛异常、不得改状态栏文案（"extract.stopped" 只由 onStopProcessing 写一次）。
     */
    @Test
    void repeatedTerminalLandingIsIdempotentAndWritesNoStatusText() {
        AtomicInteger derivedCalls = new AtomicInteger();
        ExtractionTaskCoordinator c = coordinator(derivedCalls);
        LandingUi ui = new LandingUi();
        c.running.set(true);

        c.landTerminalState();   // 第一次落地：cancelAndReset 的同步复位
        c.landTerminalState();   // 第二次落地：任务线程迟到的 onCancelled（陈旧守卫通过时）

        assertFalse(c.running.get(), "两次落地后运行标记仍为复位（复位动作幂等）");
        assertEquals(2, derivedCalls.get(), "派生函数每落地重算一次（纯派生，两次结果相同）");
        assertEquals(0, ui.statusTextCalls, "落地不得改状态栏文案（stop 文案由 onStopProcessing 恰一次写入）");
        assertTrue(ui.applied.isEmpty(), "落地本身不得回写统计");
    }
}
