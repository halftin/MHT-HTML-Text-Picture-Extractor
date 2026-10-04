package com.mhtextractor.gui.controller;

import com.mhtextractor.app.WorkspaceExtractionService;
import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.cache.PreviewCacheStore;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.processor.BatchProcessor;
import com.mhtextractor.util.OutputUnitPlanner;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 步骤 2 —— 提取启动<b>可插拔顺序门</b>（{@link ExtractionTaskCoordinator.PreStartGate}）的可观察证据。
 *
 * <p>决策 C 要求：deny 链 + R1.6 参数未确认卡之后、真正启动之前，按插入顺序<b>串行</b>询问门链，
 * 前一道门取消（{@code proceed(false)}）即整体中止、不回退到后续门。本测试注入假门断言：
 * ① 按插入顺序被询问；② 取消即中止（后续门不被询问、启动路径零副作用）；
 * ③ 全部放行则到达启动（{@code startExtractionTask} 的第一个副作用 =
 * {@code host.cancelWorkspaceActivity()} + 运行标记置位）。不是注释声称 —— 三条都有断言。</p>
 *
 * <h2>与真实 GUI 的分工</h2>
 * <p>①②③的"到达启动"在无 toolkit 环境下仍可全绿（①②不触任何 JavaFX 节点：桩宿主给合法目录、
 * 已确认参数，deny 链与 R1.6 卡都不会弹）。③启动真实 {@code Task} 需要 FX 事件循环，
 * 与 {@link ExtractionTaskCoordinatorCancelRaceTest} 同口径：可用则完整执行，被 {@code Platform.exit()}
 * 占用则按假设跳过 —— 全链放行 → 提取真的跑起来，由 GUI 自动化的两条提取用例端到端覆盖。</p>
 */
class ExtractionTaskCoordinatorGateTest {

    private static final long WAIT_SECONDS = 20;

    @TempDir
    Path dir;

    // ==================== 桩宿主 ====================

    /** 只记录调用的桩宿主。无 toolkit 用例不触碰任何控件字段（保持 null 即可）。 */
    private static final class StubHost implements ExtractionTaskCoordinator.Host {

        final String input;
        final String output;
        final List<String> events = new ArrayList<>();
        int cancelActivityCalls;

        // 只有"到达启动"的 toolkit 用例在 FX 线程上填充这四个控件
        Label statusLabel;
        ProgressBar progressBar;
        Button startButton;
        Button stopButton;

        StubHost(String input, String output) {
            this.input = input;
            this.output = output;
        }

        @Override public boolean isAnimating() { return false; }
        @Override public String inputFolder() { return input; }
        @Override public String outputFolder() { return output; }
        @Override public int itemCount() { return 1; }
        @Override public long visibleCount() { return 1; }
        @Override public boolean imageMode() { return false; }
        @Override public boolean openFolderAfterComplete() { return false; }
        @Override public AppConfig textParamsCache() { return null; }
        @Override public AppConfig imageParamsCache() { return null; }
        @Override public boolean currentModeConfirmed() { return true; }
        @Override public boolean otherModeConfirmed() { return false; }
        @Override public AppConfig currentModeParamsOrDefaults() { return new AppConfig(); }
        @Override public List<Object> dataOrder() { return List.of(); }
        @Override public OutputUnitPlanner.ItemView<PreviewItem> itemView() { return VIEW; }
        @Override public List<PreviewItem> allVisibleItems() { return List.of(); }
        @Override public void cancelWorkspaceActivity() { cancelActivityCalls++; events.add("cancelActivity"); }
        @Override public Label statusLabel() { return statusLabel; }
        @Override public void setStatusText(String text) { events.add("status:" + text); }
        @Override public void setProgressBarActivity(String styleClass) { events.add("style:" + styleClass); }
        @Override public void applyExtractResults(BatchProcessor.ProcessStats stats) { events.add("results"); }
        @Override public ProgressBar progressBar() { return progressBar; }
        @Override public Button startButton() { return startButton; }
        @Override public Button stopButton() { return stopButton; }
    }

    /** 桩条目视图（数据序为空，实际不会被问到字段）。 */
    private static final OutputUnitPlanner.ItemView<PreviewItem> VIEW = new OutputUnitPlanner.ItemView<>() {
        @Override public String path(PreviewItem item) { return item.path; }
        @Override public String sourceFolder(PreviewItem item) { return item.sourceFolder; }
        @Override public boolean removed(PreviewItem item) { return item.removed; }
        @Override public AppConfig textItemParams(PreviewItem item) { return null; }
        @Override public AppConfig imageItemParams(PreviewItem item) { return null; }
    };

    /** 记录询问顺序的假门：被询问即记名，随后按预置答案交付（同步，无 toolkit 也可跑）。 */
    private static final class FakeGate implements ExtractionTaskCoordinator.PreStartGate {
        final List<String> order;
        final String name;
        final boolean allow;

        FakeGate(List<String> order, String name, boolean allow) {
            this.order = order;
            this.name = name;
            this.allow = allow;
        }

        @Override
        public void ask(Consumer<Boolean> proceed) {
            order.add(name);
            proceed.accept(allow);
        }
    }

    // ==================== 工具 ====================

    private StubHost host() throws Exception {
        return new StubHost(Files.createDirectories(dir.resolve("in")).toString(),
                Files.createDirectories(dir.resolve("out")).toString());
    }

    private ExtractionTaskCoordinator coordinator(StubHost host) {
        return new ExtractionTaskCoordinator(new WorkspaceExtractionService(
                new PreviewCacheLifecycle(new PreviewCacheStore(dir.resolve("cache")))), host);
    }

    /** 与 {@link ExtractionTaskCoordinatorCancelRaceTest} 同口径的 toolkit 前置。 */
    private static void requireToolkit() {
        Assumptions.assumeFalse("1".equals(System.getProperty("mht.headless.execution")),
                "headless-layer-b 执行（JavaFX 已物理剔除）：本用例需要真实 FX 事件循环");
        try {
            Platform.startup(() -> { });
            Platform.setImplicitExit(false);
        } catch (IllegalStateException e) {
            if (!toolkitResponds()) {
                Assumptions.assumeTrue(false,
                        "FX toolkit 已被同 JVM 的其它用例 Platform.exit()：本用例跳过；"
                                + "单独 -Dtest=ExtractionTaskCoordinatorGateTest 可完整执行");
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

    private static void waitUntil(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_SECONDS * 1000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() >= deadline) {
                fail("等待超时：" + what);
            }
            Thread.sleep(50);
        }
    }

    // ==================== 用例 ====================

    /** ① 顺序 + 取消中止：第一道门取消 → 第二道门不被询问、启动路径零副作用（无 toolkit）。 */
    @Test
    void firstGateCancelAbortsWithoutAskingLaterGates() throws Exception {
        StubHost host = host();
        ExtractionTaskCoordinator c = coordinator(host);
        List<String> asked = new ArrayList<>();
        c.preStartGates().add(new FakeGate(asked, "g1", false));
        c.preStartGates().add(new FakeGate(asked, "g2", true));

        c.begin();

        assertEquals(List.of("g1"), asked, "第一道门取消后不得再询问后续门");
        assertEquals(0, host.cancelActivityCalls, "中止路径不得触达 startExtractionTask（首个副作用）");
        assertFalse(c.running.get(), "中止路径不得置运行标记");
    }

    /** ① 顺序 + ② 第二道门取消：前两道按插入顺序被询问，第三道不被询问、启动零副作用（无 toolkit）。 */
    @Test
    void middleGateCancelKeepsOrderAndAborts() throws Exception {
        StubHost host = host();
        ExtractionTaskCoordinator c = coordinator(host);
        List<String> asked = new ArrayList<>();
        c.preStartGates().add(new FakeGate(asked, "g1", true));
        c.preStartGates().add(new FakeGate(asked, "g2", false));
        c.preStartGates().add(new FakeGate(asked, "g3", true));

        c.begin();

        assertEquals(List.of("g1", "g2"), asked, "按插入顺序串行询问；g2 取消后 g3 不得被询问");
        assertEquals(0, host.cancelActivityCalls, "中止路径不得触达 startExtractionTask");
        assertFalse(c.running.get());
    }

    /** ③ 全部放行 → 到达启动：cancelWorkspaceActivity 被调一次、运行标记置位、开始按钮切到"提取中"，
     *  任务自然完成（空单元）后运行标记复位 —— 需要真实 FX 事件循环（toolkit 不可用时按假设跳过）。 */
    @Test
    void allGatesPassReachesStartExtractionTask() throws Exception {
        requireToolkit();
        StubHost host = host();
        ExtractionTaskCoordinator c = coordinator(host);
        List<String> asked = new ArrayList<>();
        c.preStartGates().add(new FakeGate(asked, "g1", true));
        c.preStartGates().add(new FakeGate(asked, "g2", true));

        try {
            // "到达启动"的证据必须在 begin() 返回的同一 FX 帧内取：空单元任务完成得极快，
            // 其 onSucceeded 回调排在 FX 队列里，返回测试线程后运行标记可能已被复位。
            runOnFx(() -> {
                host.statusLabel = new Label();
                host.progressBar = new ProgressBar();
                host.startButton = new Button();
                host.stopButton = new Button();
                c.begin();
                assertEquals(List.of("g1", "g2"), asked, "按插入顺序串行询问");
                assertEquals(1, host.cancelActivityCalls, "全链放行必须触达 startExtractionTask");
                assertTrue(c.running.get(), "启动路径必须置运行标记");
                assertTrue(host.startButton.isDisabled(), "提取中开始按钮必须禁用");
            });

            // 空单元任务即刻完成：onSucceeded 经 FX 事件循环复位运行标记
            waitUntil("空单元任务完成 → 运行标记复位", () -> !c.running.get());
            assertTrue(host.events.stream().anyMatch(e -> e.startsWith("status:")),
                    "任务完成应回写状态栏文案：" + host.events);
        } finally {
            c.cancel();
        }
    }

    /** ④ 终态落地（成功路径，真实 {@code extractionUi} 生产组装）：空单元任务自然完成后 ——
     *  运行标记复位、停止按钮禁用、开始按钮按注入的派生函数重算恰一次（需要真实 FX 事件循环）。 */
    @Test
    void naturalCompletionLandsTerminalUiState() throws Exception {
        requireToolkit();
        StubHost host = host();
        java.util.concurrent.atomic.AtomicInteger derivedCalls = new java.util.concurrent.atomic.AtomicInteger();
        ExtractionTaskCoordinator c = new ExtractionTaskCoordinator(new WorkspaceExtractionService(
                new PreviewCacheLifecycle(new PreviewCacheStore(dir.resolve("cache")))), host,
                derivedCalls::incrementAndGet);
        try {
            runOnFx(() -> {
                host.statusLabel = new Label();
                host.progressBar = new ProgressBar();
                host.startButton = new Button();
                host.stopButton = new Button();
                c.begin();
                assertTrue(c.running.get(), "启动路径必须置运行标记");
                assertTrue(host.startButton.isDisabled(), "提取中开始按钮必须禁用");
                assertFalse(host.stopButton.isDisabled(), "提取中停止按钮必须启用");
            });

            waitUntil("空单元任务完成 → 运行标记复位", () -> !c.running.get());
            runOnFx(() -> { });   // 排空 FX：onSucceeded 收尾（落地 → 状态栏）全部执行完再断言
            assertTrue(host.stopButton.isDisabled(), "成功后停止按钮必须禁用（终态落地）");
            assertEquals(1, derivedCalls.get(), "成功后开始按钮必须按派生函数重算恰一次（终态落地）");
        } finally {
            c.cancel();
        }
    }
}
