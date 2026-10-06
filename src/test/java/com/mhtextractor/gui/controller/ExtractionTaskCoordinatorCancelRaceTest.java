package com.mhtextractor.gui.controller;

import com.mhtextractor.app.ExtractionStats;
import com.mhtextractor.app.WorkspaceExtractionService;
import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.cache.PreviewCacheStore;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.OutputUnitPlanner;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
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
 * B —— 端到端复现「取消统计回写竞态」：真实 {@link javafx.concurrent.Task} + 真实 FX 事件循环
 * + 真实 {@link WorkspaceExtractionService}（公开构造：{@code PreviewCacheLifecycle} → 生产 dispatch）。
 *
 * <h2>确定性复现手法（不用 sleep 抢时序）</h2>
 * <ol>
 *   <li><b>闸门</b>：worker 解析 300 层嵌套的 {@code deep-nesting.mht} 时同步调用
 *       {@code ExtractionRequest.recursionCallback()}（公开 seam）——回调阻塞在闸门上，
 *       worker 被钉在单元处理中途；</li>
 *   <li><b>闸门吞中断</b>：{@code Task.cancel(true)} 的中断被吞掉（取消请求 ≠ worker 已退出，场景⑤）；</li>
 *   <li>FX 线程上 {@code cancel()}，随后排空一次 FX 队列：{@code onCancelled} 已先跑（时点①），
 *       worker 仍未返回；</li>
 *   <li>放开闸门：worker 返回 partial（时点②）。断言 {@code applyResults} 在 worker 完成后
 *       <b>恰好一次</b>、载荷 == worker 返回的统计（{@code coordinator.extractStats()} 同一对象）。</li>
 * </ol>
 * 旧实现：{@code onCancelled} 直接读 {@code lastExtractStats} → null（首次运行，从不回写）
 * 或上一次运行的残留（回写错误统计）→ 各用例断言失败。
 *
 * <h2>运行环境</h2>
 * <p>本测试需要真实 FX toolkit：单独 {@code -Dtest=ExtractionTaskCoordinatorCancelRaceTest} 运行时
 * 完整执行；全量 {@code mvn test} 中若 toolkit 已被同 JVM 的其它用例 {@code Platform.exit()}
 * （{@code WorkspaceFxAdapterTest} 末尾如此），则按假设跳过 —— 该逻辑的常开回归由无头测试
 * {@link ExtractionTaskCoordinatorRunChannelTest} 承担。headless-layer-b 执行（JavaFX 已物理剔除）
 * 同样按假设跳过。</p>
 */
class ExtractionTaskCoordinatorCancelRaceTest {

    private static final long WAIT_SECONDS = 20;

    @TempDir
    Path tmp;

    // ==================== 桩 UI ====================

    /** 只记录调用的桩（在 FX 线程上被调用）：断言用 applied 列表（同步） */
    private static final class FakeUi implements ExtractionTaskCoordinator.ExtractionUi {

        final List<ExtractionStats> applied = Collections.synchronizedList(new ArrayList<>());
        final List<String> statusTexts = Collections.synchronizedList(new ArrayList<>());
        final List<Double> progress = Collections.synchronizedList(new ArrayList<>());
        final List<ExtractionTaskCoordinator.ExtractOutcomeStyle> outcomes =
                Collections.synchronizedList(new ArrayList<>());
        final java.util.concurrent.atomic.AtomicInteger started = new java.util.concurrent.atomic.AtomicInteger(0);

        @Override
        public File sessionOutputFolder() {
            return new File(".");
        }

        @Override
        public boolean openFolderAfterComplete() {
            return false;
        }

        @Override
        public void setProgress(double value) {
            progress.add(value);
        }

        @Override
        public void setProgressBarOutcome(ExtractionTaskCoordinator.ExtractOutcomeStyle style) {
            outcomes.add(style);
        }

        @Override
        public void extractionStarted() {
            started.incrementAndGet();
        }

        @Override
        public void applyResults(ExtractionStats stats) {
            applied.add(stats);
        }

        @Override
        public void setStatusText(String text) {
            statusTexts.add(text);
        }
    }

    // ==================== 桩宿主（E1.1：landTerminalState 只触达 stopButton） ====================

    /**
     * 只记录 {@code stopButton()} 调用的最小桩宿主：E1.1 起终态落地经
     * {@code host.stopButton().setDisable(true)}（每次落地恰一次），按钮在 FX 线程上惰性创建。
     * 其余宿主方法：{@code run()} 直驱路径的 UI 动作全走 {@link FakeUi} seam，不会触达宿主。
     */
    private static final class CancelHost implements ExtractionTaskCoordinator.Host {

        final AtomicInteger stopButtonCalls = new AtomicInteger();
        Button stopButton;

        @Override public Button stopButton() {
            stopButtonCalls.incrementAndGet();
            if (stopButton == null) {
                stopButton = new Button();   // 落地只发生在 FX 线程，toolkit 已就绪
            }
            return stopButton;
        }

        @Override public boolean isAnimating() { return false; }
        @Override public String inputFolder() { return ""; }
        @Override public String outputFolder() { return ""; }
        @Override public int itemCount() { return 0; }
        @Override public long visibleCount() { return 0; }
        @Override public boolean imageMode() { return false; }
        @Override public boolean openFolderAfterComplete() { return false; }
        @Override public AppConfig textParamsCache() { return null; }
        @Override public AppConfig imageParamsCache() { return null; }
        @Override public boolean currentModeConfirmed() { return true; }
        @Override public boolean otherModeConfirmed() { return false; }
        @Override public AppConfig currentModeParamsOrDefaults() { return new AppConfig(); }
        @Override public List<Object> dataOrder() { return List.of(); }
        @Override public List<PreviewItem> allVisibleItems() { return List.of(); }
        @Override public void cancelWorkspaceActivity() { }
        @Override public Label statusLabel() { return null; }
        @Override public void setStatusText(String text) { }
        @Override public void setProgressBarActivity(String styleClass) { }
        @Override public void applyExtractResults(ExtractionStats stats) { }
        @Override public ProgressBar progressBar() { return null; }
        @Override public Button startButton() { return null; }
    }

    // ==================== 闸门（递归深度回调 seam） ====================

    /** 阻塞 worker 的递归回调：吞掉 cancel(true) 的中断（取消请求 ≠ worker 已退出）。 */
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
                                + "单独 -Dtest=ExtractionTaskCoordinatorCancelRaceTest 可完整执行");
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

    /** 排空 FX 事件队列：此调用之前入队的事件（含 onSucceeded/onFailed/onCancelled）必已处理。 */
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

    /** 当前用例的桩宿主（coordinator 每次重造时替换；断言读它的 stopButtonCalls）。 */
    private CancelHost cancelHost;

    private ExtractionTaskCoordinator coordinator(AtomicInteger derivedCalls) {
        cancelHost = new CancelHost();
        return new ExtractionTaskCoordinator(new WorkspaceExtractionService(
                new PreviewCacheLifecycle(new PreviewCacheStore(tmp.resolve("cache")))), cancelHost,
                derivedCalls::incrementAndGet);
    }

    // ==================== 用例 ====================

    /**
     * 复现核心（首次运行）：onCancelled 先跑、worker 后完成 ——
     * 旧实现读到 null 从不回写；新实现 worker 完成后恰好回写一次 partial。
     */
    @Test
    void cancelWhileWorkerBlockedInUnitWritesBackPartialExactlyOnce() throws Exception {
        requireToolkit();
        AtomicInteger derivedCalls = new AtomicInteger();
        ExtractionTaskCoordinator coordinator = coordinator(derivedCalls);
        FakeUi ui = new FakeUi();
        Label label = new Label();
        Gate gate = new Gate();
        Path deep = fixture("/fixtures/deep-nesting.mht", "deep.mht");
        Path session = Files.createDirectories(tmp.resolve("session-a"));
        WorkspaceExtractionService.ExtractionRequest req =
                request(List.of(deep), gate, session.toString());

        try {
            runOnFx(() -> coordinator.run(req, label, ui));
            assertTrue(gate.entered.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "worker 应已进入递归深度回调（闸门）");

            runOnFx(coordinator::cancel);
            drainFx();                       // 时点①：onCancelled 已先跑，worker 仍阻塞在闸门
            assertTrue(ui.applied.isEmpty(),
                    "onCancelled 已跑但 worker 未完成：不得回写（旧实现此刻读 holder 会拿到 null/残留）");
            assertFalse(coordinator.running.get(),
                    "onCancelled 必须落地运行标记复位（不等到 worker 退出）");
            assertEquals(1, cancelHost.stopButtonCalls.get(),
                    "onCancelled 必须落地停止禁用恰一次（E1.1：经 host.stopButton）");
            assertEquals(1, derivedCalls.get(), "onCancelled 必须按派生函数重算开始按钮恰一次");

            gate.open();                     // 时点②：放行 worker → 返回 partial
            waitUntil("取消后 worker 完成 → partial 回写", () -> !ui.applied.isEmpty());
            drainFx();
            assertEquals(1, ui.applied.size(), "partial 必须恰好回写一次（worker 完成后）");
            assertSame(coordinator.extractStats().get(), ui.applied.get(0),
                    "回写载荷 == worker 返回的统计（同一对象）");
            assertEquals(1, cancelHost.stopButtonCalls.get(), "落地不得随 partial 回写重复（仍恰一次）");
            assertEquals(1, derivedCalls.get(), "派生函数不得随 partial 回写重复调用");
        } finally {
            gate.open();                     // 任何失败路径都放行 worker，防非 daemon 线程残留
        }
    }

    /** 第二次运行取消：旧实现回写上一次运行的残留统计；新实现回写本次 partial。 */
    @Test
    void cancelOnSecondRunWritesBackThisRunsPartialNotStaleHolder() throws Exception {
        requireToolkit();
        ExtractionTaskCoordinator coordinator = coordinator(new AtomicInteger());
        FakeUi ui = new FakeUi();
        Label label = new Label();
        Path simple = fixture("/fixtures/simple-text.mht", "simple-b.mht");
        Path deep = fixture("/fixtures/deep-nesting.mht", "deep-b.mht");
        Path session1 = Files.createDirectories(tmp.resolve("session-b1"));
        Path session2 = Files.createDirectories(tmp.resolve("session-b2"));

        // run 1：自然完成（成功路径回写一次）
        runOnFx(() -> coordinator.run(request(List.of(simple), () -> true, session1.toString()), label, ui));
        waitUntil("run 1 自然完成并回写", () -> !ui.applied.isEmpty());
        drainFx();
        ExtractionStats run1Stats = ui.applied.get(0);

        // run 2：deep 文件在闸门上取消
        Gate gate = new Gate();
        try {
            runOnFx(() -> coordinator.run(
                    request(List.of(deep), gate, session2.toString()), label, ui));
            assertTrue(gate.entered.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "run 2 的 worker 应已进入闸门");

            runOnFx(coordinator::cancel);
            drainFx();                       // onCancelled(run 2) 已先跑（旧实现此刻已误回写 run 1 残留）
            gate.open();                     // 放行 worker → run 2 partial
            // 以 holder 变化为"worker 已停"的信号（回写计数在旧实现下会提前到位，不能当完成信号用）
            waitUntil("run 2 worker 完成", () -> coordinator.extractStats().get() != run1Stats);
            drainFx();
            assertEquals(2, ui.applied.size(), "取消回写恰好一次：run 1 成功一次 + run 2 partial 一次");
            assertSame(coordinator.extractStats().get(), ui.applied.get(1),
                    "第二次回写必须是 run 2 的 partial，不得是 run 1 的残留统计");
            assertSame(run1Stats, ui.applied.get(0), "run 1 的成功回写不受影响");
            assertEquals(2, cancelHost.stopButtonCalls.get(), "两次终态（run 1 成功 + run 2 取消）各落地一次");
            assertFalse(coordinator.running.get(), "取消终态必须复位运行标记");
        } finally {
            gate.open();
        }
    }

    /** 取消后立即再开新任务：旧 run 的迟到回调不得污染新 run（UI 只收到 run0 与 run2 的统计各一次）。 */
    @Test
    void cancelThenImmediatelyStartNewRunKeepsLateCallbacksFromPolluting() throws Exception {
        requireToolkit();
        ExtractionTaskCoordinator coordinator = coordinator(new AtomicInteger());
        FakeUi ui = new FakeUi();
        Label label = new Label();
        Path simple0 = fixture("/fixtures/simple-text.mht", "simple-c0.mht");
        Path deep = fixture("/fixtures/deep-nesting.mht", "deep-c.mht");
        Path simple2 = fixture("/fixtures/simple-text.mht", "simple-c2.mht");
        Path session0 = Files.createDirectories(tmp.resolve("session-c0"));
        Path session1 = Files.createDirectories(tmp.resolve("session-c1"));
        Path session2 = Files.createDirectories(tmp.resolve("session-c2"));

        // run 0：自然完成（应用一次）
        runOnFx(() -> coordinator.run(request(List.of(simple0), () -> true, session0.toString()), label, ui));
        waitUntil("run 0 自然完成并回写", () -> !ui.applied.isEmpty());
        drainFx();
        ExtractionStats run0Stats = ui.applied.get(0);

        // run 1：闸门上取消，随后不排空 FX 立即开 run 2 —— onCancelled(run 1) 可能落在 run 2 前后
        Gate gate = new Gate();
        try {
            runOnFx(() -> coordinator.run(request(List.of(deep), gate, session1.toString()), label, ui));
            assertTrue(gate.entered.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "run 1 的 worker 应已进入闸门");
            runOnFx(coordinator::cancel);
            runOnFx(() -> coordinator.run(
                    request(List.of(simple2), () -> true, session2.toString()), label, ui));

            waitUntil("run 2 自然完成并回写", () -> ui.applied.size() >= 2);
            drainFx();
            ExtractionStats run2Stats = ui.applied.get(1);

            gate.open();                     // run 1 worker 迟到完成 → 其回写必须 no-op
            waitUntil("run 1 worker 迟到完成", () -> coordinator.extractStats().get() != run2Stats);
            drainFx();
            assertEquals(2, ui.applied.size(),
                    "旧 run 的迟到回调不得再回写：UI 只收到 run 0 与 run 2 的统计各一次");
            assertSame(run0Stats, ui.applied.get(0));
            assertSame(run2Stats, ui.applied.get(1), "最后一次回写是 run 2 的最终统计");
            assertFalse(coordinator.running.get(), "run 2 终态必须复位运行标记");
        } finally {
            gate.open();
        }
    }
}
