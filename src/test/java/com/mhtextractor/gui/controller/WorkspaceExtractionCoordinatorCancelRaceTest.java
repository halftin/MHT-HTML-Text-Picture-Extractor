package com.mhtextractor.gui.controller;

import com.mhtextractor.app.WorkspaceExtractionCoordinator;
import com.mhtextractor.app.WorkspaceExtractionService;
import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.cache.PreviewCacheStore;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uiport.PortEvent;
import com.mhtextractor.util.OutputUnitPlanner;
import javafx.application.Platform;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * B —— 端到端复现「取消统计回写竞态」（原 {@code ExtractionTaskCoordinatorCancelRaceTest}，
 * P3-B6 随任务通道下沉改驱动 {@link WorkspaceExtractionCoordinator}：真实提取 worker + 真实 FX
 * 事件循环 + 真实 {@link WorkspaceExtractionService}（公开构造：{@code PreviewCacheLifecycle} →
 * 生产 dispatch）。断言面逐条平移（FakeUi 的 applied 观测改为终态/回写事件流），语义不得放宽。
 *
 * <h2>确定性复现手法（不用 sleep 抢时序）</h2>
 * <ol>
 *   <li><b>闸门</b>：worker 解析 300 层嵌套的 {@code deep-nesting.mht} 时同步调用
 *       {@code ExtractionRequest.recursionCallback()}（公开 seam）——回调阻塞在闸门上，
 *       worker 被钉在单元处理中途；</li>
 *   <li><b>闸门吞中断</b>：取消请求的中断被吞掉（取消请求 ≠ worker 已退出，场景⑤）；</li>
 *   <li>FX 线程上 {@code cancelExtraction()}，随后排空一次 FX 队列：取消终态已先交付（时点①），
 *       worker 仍未返回；</li>
 *   <li>放开闸门：worker 返回 partial（时点②）。断言回写事件 {@link PortEvent.ExtractionResult}
 *       在 worker 完成后<b>恰好一次</b>、载荷 == worker 返回的统计
 *       （{@code coordinator.lastExtractStats} 同一对象）。</li>
 * </ol>
 * 旧实现：{@code onCancelled} 直接读 {@code lastExtractStats} → null（首次运行，从不回写）
 * 或上一次运行的残留（回写错误统计）→ 各用例断言失败。
 *
 * <h2>运行环境</h2>
 * <p>本测试需要真实 FX toolkit：单独 {@code -Dtest=WorkspaceExtractionCoordinatorCancelRaceTest}
 * 运行时完整执行；全量 {@code mvn test} 中若 toolkit 已被同 JVM 的其它用例 {@code Platform.exit()}
 * （{@code WorkspaceFxAdapterTest} 末尾如此），则按假设跳过 —— 该逻辑的常开回归由无头测试
 * {@link com.mhtextractor.app.WorkspaceExtractionCoordinatorRunChannelTest} 承担。
 * headless-layer-b 执行（JavaFX 已物理剔除）同样按假设跳过。</p>
 */
class WorkspaceExtractionCoordinatorCancelRaceTest {

    private static final long WAIT_SECONDS = 20;

    @TempDir
    Path tmp;

    // ==================== 事件观测（替代原 FakeUi：只记录事件流，同步） ====================

    /** 终态与回写事件的观测列表（事件在 FX 线程上经派发器送达）。 */
    private static final class EventLog {
        final List<PortEvent> events = Collections.synchronizedList(new ArrayList<>());

        List<PortEvent.ExtractionTerminal> terminals() {
            synchronized (events) {
                return events.stream()
                        .filter(e -> e instanceof PortEvent.ExtractionTerminal)
                        .map(e -> (PortEvent.ExtractionTerminal) e).toList();
            }
        }

        List<PortEvent.ExtractionResult> results() {
            synchronized (events) {
                return events.stream()
                        .filter(e -> e instanceof PortEvent.ExtractionResult)
                        .map(e -> (PortEvent.ExtractionResult) e).toList();
            }
        }
    }

    // ==================== 闸门（递归深度回调 seam） ====================

    /** 阻塞 worker 的递归回调：吞掉取消请求的中断（取消请求 ≠ worker 已退出）。 */
    private static final class Gate implements Supplier<Boolean> {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Boolean get() {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                // 中断被吞：worker 必须完成处理并产出 partial 才算停（场景⑤）
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // 至多再来一次中断
                }
            }
            return true;
        }

        void open() {
            release.countDown();
        }
    }

    // ==================== FX 工具 ====================

    /** 启动或复用 toolkit；不可用（已被 exit / 无 JavaFX）则按假设跳过整个用例。 */
    private static void requireToolkit() {
        Assumptions.assumeFalse("1".equals(System.getProperty("mht.headless.execution")),
                "headless-layer-b 执行（JavaFX 已物理剔除）：本测试需要真实 FX 事件循环");
        try {
            Platform.startup(() -> { });
            Platform.setImplicitExit(false);
        } catch (IllegalStateException e) {
            if (!toolkitResponds()) {
                Assumptions.assumeTrue(false,
                        "FX toolkit 已被同 JVM 的其它用例 Platform.exit()：本端到端用例跳过；"
                                + "单独 -Dtest=WorkspaceExtractionCoordinatorCancelRaceTest 可完整执行");
            }
            // 已初始化：复用现有 toolkit
        }
    }

    private static boolean toolkitResponds() {
        try {
            CountDownLatch probe = new CountDownLatch(1);
            Platform.runLater(probe::countDown);
            return probe.await(5, TimeUnit.SECONDS);
        } catch (IllegalStateException | InterruptedException e) {
            return false;
        }
    }

    /** 在 FX 线程执行动作并等待其完成（动作内异常原样上抛为 AssertionError）。 */
    private static void runOnFx(Runnable action) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "FX 动作应在超时前执行");
        if (failure.get() != null) {
            throw new AssertionError("FX 动作失败：" + failure.get(), failure.get());
        }
    }

    /** 排空 FX 事件队列：此调用之前入队的事件（含终态/回写投递）必已处理。 */
    private static void drainFx() throws InterruptedException {
        CountDownLatch drain = new CountDownLatch(1);
        Platform.runLater(drain::countDown);
        assertTrue(drain.await(15, TimeUnit.SECONDS), "FX 队列应在超时前排空");
    }

    /** 测试侧等待外部线程（FX / worker）的条件成立；超时给出可读失败信息。 */
    private static void waitUntil(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_SECONDS * 1000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() >= deadline) {
                fail("等待超时：" + what);
            }
            Thread.sleep(50);
        }
    }

    // ==================== 夹具 ====================

    private Path fixture(String resource, String name) throws Exception {
        Path dest = tmp.resolve(name);
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, "夹具缺失：" + resource);
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return dest;
    }

    private WorkspaceExtractionService.ExtractionRequest request(List<Path> files,
                                                                 Supplier<Boolean> recursion,
                                                                 String session) {
        List<Object> data = files.stream()
                .map(p -> new TItem(p.toString(), p.getParent().toString()))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        List<OutputUnitPlanner.OutputUnit> units =
                OutputUnitPlanner.plan(data, VIEW, false, new AppConfig(), null, session);
        assertEquals(1, units.size(), "单文件应恰好规划出 1 个输出单元");
        return new WorkspaceExtractionService.ExtractionRequest(session, units, Map.of(), false, recursion);
    }

    /** 桩条目视图（与 {@code WorkspaceExtractionServiceTest} 同形）。 */
    private record TItem(String path, String source) {
    }

    private static final OutputUnitPlanner.ItemView<TItem> VIEW = new OutputUnitPlanner.ItemView<>() {
        @Override
        public String path(TItem item) {
            return item.path();
        }

        @Override
        public String sourceFolder(TItem item) {
            return item.source();
        }

        @Override
        public boolean removed(TItem item) {
            return false;
        }

        @Override
        public AppConfig textItemParams(TItem item) {
            return null;
        }

        @Override
        public AppConfig imageItemParams(TItem item) {
            return null;
        }
    };

    /** 协调器：真实服务 + 注入请求工厂（每次启动取当前请求快照；闸门经 recursionCallback 直入请求）+ FX 派发器。 */
    private WorkspaceExtractionCoordinator coordinator(
            AtomicReference<WorkspaceExtractionService.ExtractionRequest> request) {
        return new WorkspaceExtractionCoordinator(new WorkspaceExtractionService(
                new PreviewCacheLifecycle(new PreviewCacheStore(tmp.resolve("cache")))),
                checkedOnly -> request.get(), Platform::runLater);
    }

    // ==================== 用例 ====================

    /**
     * 复现核心（首次运行）：取消终态先交付、worker 后完成 ——
     * 旧实现读到 null 从不回写；新实现 worker 完成后恰好回写一次 partial。
     */
    @Test
    void cancelWhileWorkerBlockedInUnitWritesBackPartialExactlyOnce() throws Exception {
        requireToolkit();
        EventLog log = new EventLog();
        Gate gate = new Gate();
        Path deep = fixture("/fixtures/deep-nesting.mht", "deep.mht");
        Path session = Files.createDirectories(tmp.resolve("session-a"));
        java.util.concurrent.atomic.AtomicReference<WorkspaceExtractionService.ExtractionRequest> req =
                new java.util.concurrent.atomic.AtomicReference<>(request(List.of(deep), gate, session.toString()));
        WorkspaceExtractionCoordinator coordinator = coordinator(req);
        coordinator.subscribe(log.events::add);

        try {
            runOnFx(() -> coordinator.startExtraction(false));
            assertTrue(gate.entered.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "worker 应已进入递归深度回调（闸门）");

            runOnFx(coordinator::cancelExtraction);
            drainFx();                       // 时点①：取消终态已先交付，worker 仍阻塞在闸门
            assertTrue(log.results().isEmpty(),
                    "取消终态已交付但 worker 未完成：不得回写（旧实现此刻读 holder 会拿到 null/残留）");
            assertFalse(coordinator.running.get(),
                    "取消终态必须立即复位运行标记（不等到 worker 退出）");
            assertEquals(1, log.terminals().size(), "取消终态必须此刻交付恰一次");
            assertEquals(PortEvent.ExtractionEnd.CANCELLED, log.terminals().get(0).end());

            gate.open();                     // 时点②：放行 worker → 返回 partial
            waitUntil("取消后 worker 完成 → partial 回写", () -> !log.results().isEmpty());
            drainFx();
            assertEquals(1, log.results().size(), "partial 必须恰好回写一次（worker 完成后）");
            assertSame(coordinator.lastExtractStats.get(), log.results().get(0).stats(),
                    "回写载荷 == worker 返回的统计（同一对象）");
            assertEquals(1, log.terminals().size(), "回写不得重复交付终态（仍恰一次）");
        } finally {
            gate.open();                     // 任何失败路径都放行 worker，防非 daemon 线程残留
        }
    }

    /** 第二次运行取消：旧实现回写上一次运行的残留统计；新实现回写本次 partial。 */
    @Test
    void cancelOnSecondRunWritesBackThisRunsPartialNotStaleHolder() throws Exception {
        requireToolkit();
        EventLog log = new EventLog();
        Path simple = fixture("/fixtures/simple-text.mht", "simple-b.mht");
        Path deep = fixture("/fixtures/deep-nesting.mht", "deep-b.mht");
        Path session1 = Files.createDirectories(tmp.resolve("session-b1"));
        Path session2 = Files.createDirectories(tmp.resolve("session-b2"));

        // run 1：自然完成（成功路径统计随终态交付一次）
        java.util.concurrent.atomic.AtomicReference<WorkspaceExtractionService.ExtractionRequest> req =
                new java.util.concurrent.atomic.AtomicReference<>(
                        request(List.of(simple), () -> true, session1.toString()));
        WorkspaceExtractionCoordinator coordinator = coordinator(req);
        coordinator.subscribe(log.events::add);
        runOnFx(() -> coordinator.startExtraction(false));
        waitUntil("run 1 自然完成并回写", () -> !log.terminals().isEmpty());
        drainFx();
        assertEquals(1, log.terminals().size(), "run 1 成功终态恰一次");
        com.mhtextractor.model.ExtractionStats run1Stats = log.terminals().get(0).stats();
        assertNotNull(run1Stats);

        // run 2：deep 文件在闸门上取消
        Gate gate = new Gate();
        try {
            req.set(request(List.of(deep), gate, session2.toString()));
            runOnFx(() -> coordinator.startExtraction(false));
            assertTrue(gate.entered.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "run 2 的 worker 应已进入闸门");

            runOnFx(coordinator::cancelExtraction);
            drainFx();                       // 取消终态(run 2)已先交付（旧实现此刻已误回写 run 1 残留）
            gate.open();                     // 放行 worker → run 2 partial
            // 以 holder 变化为"worker 已停"的信号（回写计数在旧实现下会提前到位，不能当完成信号用）
            waitUntil("run 2 worker 完成", () -> coordinator.lastExtractStats.get() != run1Stats);
            drainFx();
            assertEquals(2, log.terminals().size(), "两个 run 各一个终态（run 1 成功 + run 2 取消）");
            assertEquals(1, log.results().size(), "取消回写恰好一次：只有 run 2 的 partial");
            assertSame(coordinator.lastExtractStats.get(), log.results().get(0).stats(),
                    "回写必须是 run 2 的 partial，不得是 run 1 的残留统计");
            assertSame(run1Stats, log.terminals().get(0).stats(), "run 1 的成功统计不受影响");
            assertFalse(coordinator.running.get(), "取消终态必须复位运行标记");
        } finally {
            gate.open();
        }
    }

    /** 取消后立即再开新任务：旧 run 的迟到回调不得污染新 run（宿主只收到 run 0 与 run 2 的统计各一次）。 */
    @Test
    void cancelThenImmediatelyStartNewRunKeepsLateCallbacksFromPolluting() throws Exception {
        requireToolkit();
        EventLog log = new EventLog();
        Path simple0 = fixture("/fixtures/simple-text.mht", "simple-c0.mht");
        Path deep = fixture("/fixtures/deep-nesting.mht", "deep-c.mht");
        Path simple2 = fixture("/fixtures/simple-text.mht", "simple-c2.mht");
        Path session0 = Files.createDirectories(tmp.resolve("session-c0"));
        Path session1 = Files.createDirectories(tmp.resolve("session-c1"));
        Path session2 = Files.createDirectories(tmp.resolve("session-c2"));

        // run 0：自然完成（统计随成功终态交付一次）
        java.util.concurrent.atomic.AtomicReference<WorkspaceExtractionService.ExtractionRequest> req =
                new java.util.concurrent.atomic.AtomicReference<>(
                        request(List.of(simple0), () -> true, session0.toString()));
        WorkspaceExtractionCoordinator coordinator = coordinator(req);
        coordinator.subscribe(log.events::add);
        runOnFx(() -> coordinator.startExtraction(false));
        waitUntil("run 0 自然完成并回写", () -> !log.terminals().isEmpty());
        drainFx();
        com.mhtextractor.model.ExtractionStats run0Stats = log.terminals().get(0).stats();

        // run 1：闸门上取消，随后不排空 FX 立即开 run 2 —— 旧 run 的迟到投递可能落在 run 2 前后
        Gate gate = new Gate();
        try {
            req.set(request(List.of(deep), gate, session1.toString()));
            runOnFx(() -> coordinator.startExtraction(false));
            assertTrue(gate.entered.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "run 1 的 worker 应已进入闸门");
            runOnFx(coordinator::cancelExtraction);
            req.set(request(List.of(simple2), () -> true, session2.toString()));
            runOnFx(() -> coordinator.startExtraction(false));

            waitUntil("run 2 自然完成并回写", () -> log.terminals().size() >= 3);
            drainFx();
            // 终态顺序 = [run 0 成功, run 1 取消, run 2 成功]：run 2 的统计在索引 2（索引 1 是取消终态，stats 恒 null）
            com.mhtextractor.model.ExtractionStats run2Stats = log.terminals().get(2).stats();

            gate.open();                     // run 1 worker 迟到完成 → 其回写必须 no-op
            waitUntil("run 1 worker 迟到完成", () -> coordinator.lastExtractStats.get() != run2Stats);
            drainFx();
            assertEquals(3, log.terminals().size(),
                    "三个 run 各一个终态：run 0 成功 + run 1 取消 + run 2 成功（旧 run 迟到不得追加）");
            assertEquals(PortEvent.ExtractionEnd.SUCCEEDED, log.terminals().get(0).end());
            assertEquals(PortEvent.ExtractionEnd.CANCELLED, log.terminals().get(1).end());
            assertEquals(PortEvent.ExtractionEnd.SUCCEEDED, log.terminals().get(2).end());
            assertEquals(0, log.results().size(),
                    "旧 run 的迟到回写不得投递：宿主只收到 run 0 与 run 2 的统计各一次");
            assertSame(run0Stats, log.terminals().get(0).stats());
            assertSame(run2Stats, log.terminals().get(2).stats(), "最后一次交付是 run 2 的最终统计");
            assertFalse(coordinator.running.get(), "run 2 终态必须复位运行标记");
        } finally {
            gate.open();
        }
    }
}
