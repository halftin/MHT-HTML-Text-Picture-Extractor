package com.mhtextractor.gui.controller;

import com.mhtextractor.app.WorkspaceExtractionService;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.i18n.ErrorCode;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.processor.BatchProcessor;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.Label;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 提取任务协调器（Q3 / P2）—— 只负责 JavaFX {@link Task} 的<b>生命周期与 UI 回调</b>，
 * 业务段一律委托 {@link WorkspaceExtractionService}。
 *
 * <p><b>为什么要有它</b>：{@code MainController.startExtractionTask()} 里同时住着三件不同性质的事 ——
 * FX 线程上的快照与控件状态、后台线程上的业务编排、任务结束后的 UI 收尾。中间那层"线程 + 取消标志 +
 * 进度属性"的适配收在本类；业务编排回到 {@code app}（可无头单测）；控件动作经 {@link ExtractionUi}
 * 窄接口回调给 {@code MainController}。</p>
 *
 * <p><b>三个提取状态字段归本类持有</b>（搬迁前它们在 {@code MainController} 上）：</p>
 * <ul>
 *   <li>{@link #running} —— 提取是否在进行（解析回调据此让出进度条 / 状态栏）；</li>
 *   <li>{@link #lastExtractStats} —— <b>最近一次提取统计的可变 holder</b>。由 {@code Task.call()}
 *       在服务返回后写一次，经 {@link #extractStats()} 供只读访问器（GUI 自动化断言）读取。
 *       取消回写<b>不</b>读它（见下）；</li>
 *   <li>{@code currentTask} —— 在途任务（停止按钮据此取消）。</li>
 * </ul>
 *
 * <p><b>B —— 取消统计回写的三个时点</b>：① 请求取消（{@code Task.cancel(true)} 立即置 CANCELLED 并
 * 触发 {@code onCancelled}）② worker 停止并产出 partial（{@code call()} 返回前完成本次 run 的
 * {@link RunChannel#result}）③ UI 收到终态并回写。① ≠ ②：{@code onCancelled} 跑在①，此刻 partial
 * 尚不存在，直接读 {@code lastExtractStats} 会拿到 null（首次运行）或上一次运行的残留 ——
 * 把 {@code AtomicReference} 当同步保证是错的。因此取消回写在 {@link RunChannel} 上挂
 * "②完成后经 FX 投递一次"的回调；每 run 另有 {@code runGeneration} 递增的陈旧守卫，
 * 旧任务迟到的终态回调对已开始的新 run 一律 no-op。</p>
 *
 * <p><b>本类含 JavaFX，属 {@code gui} 层</b>；业务侧不得反向依赖它。</p>
 */
@Slf4j
public final class ExtractionTaskCoordinator {

    /**
     * 提取任务的 UI 收尾窄接口：本类不持有任何控件字段，只按这些动作驱动界面。与搬迁前
     * {@code onSucceeded} / {@code onFailed} / {@code onCancelled} 里对控件的动作逐条对应。
     */
    public interface ExtractionUi {
        /** 会话输出目录（勾选了"完成后打开输出目录"时由本类打开它）。 */
        File sessionOutputFolder();

        /** 是否勾选了"完成后打开输出目录"（按下"开始提取"那一刻的取值）。 */
        boolean openFolderAfterComplete();

        /** 进度条当前值（对应搬迁前 {@code progressBar.setProgress(v)}）。 */
        void setProgress(double value);

        /** 满格条三态配色（{@code MainController.setProgressBarActivity} 的提取三态）。 */
        void setProgressBarOutcome(ExtractOutcomeStyle style);

        /**
         * 提取任务已受理（<b>必须在 {@link ExtractionTaskCoordinator#run} 里、任务线程启动之前调用</b>）：
         * 调用方在此把控件切到"提取中"——开始按钮禁用、停止按钮启用、进度条可见且归零、
         * 满格条先置成功色。与搬迁前 {@code startExtractionTask} 紧接着
         * {@code running.set(true)} 之后那 5 行逐条对应。
         */
        void extractionStarted();

        /** 结果一次性回写列表条目（{@code MainController.applyExtractResults}）。 */
        void applyResults(BatchProcessor.ProcessStats stats);

        /** 状态栏文案（成功 = 摘要行；失败 = {@code extract.statusError}）。 */
        void setStatusText(String text);
    }

    /** 满格进度条三态（与 {@code ExtractOutcomeStyles.PROGRESS_EXTRACT_*} 一一对应，P6-A 起样式类名收口于该类）。 */
    public enum ExtractOutcomeStyle {
        /** 有失败或跳过 */
        FAILED,
        /** 无失败/跳过但有警告 */
        WARNED,
        /** 全部成功 */
        SUCCESS
    }

    private final WorkspaceExtractionService extractionService;
    /**
     * 提取运行标记（解析回调据此让出进度条 / 状态栏）。
     *
     * <p>🚨 <b>必须在 {@link #run} 的<b>第一行</b>置 true</b>：{@code MainController.updateProgressStatus}
     * 拿它当"提取期间进度条/状态栏归提取任务独占"的守卫。守卫<b>只认这个字段</b>——忘了置 true
     * （Q3/P2 真的漏过一次）会让守卫恒不生效，预解析完成回调随即把刚设好的满格条配色清成默认色，
     * GUI 自动化两条提取配色断言会稳定变红。复位见 {@link #run} 的三个终态回调。</p>
     */
    final AtomicBoolean running = new AtomicBoolean(false);
    /**
     * 最近一次提取统计的唯一 holder：{@code Task.call()} 在服务返回后写一次，经 {@link #extractStats()}
     * 供只读访问器读取（GUI 自动化据此断言提取完成与结果内容）。取消回写不经此 holder（见 {@link RunChannel}）。
     */
    final AtomicReference<BatchProcessor.ProcessStats> lastExtractStats = new AtomicReference<>();
    private volatile Task<BatchProcessor.ProcessStats> currentTask;
    /** 每 run 递增的运行代数（<b>只在 FX 线程上读写</b>）：旧任务迟到的终态回调凭它识别自己是陈旧调用 */
    private long runGeneration;

    public ExtractionTaskCoordinator(WorkspaceExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    /**
     * B —— 每次 {@link ExtractionTaskCoordinator#run} 的结果通道与陈旧守卫（<b>零 JavaFX，可无头单测</b>）。
     *
     * <p><b>三个时点</b>：① 请求取消（{@code Task.cancel(true)} 立即置 CANCELLED 并触发
     * {@code onCancelled}）② worker 停止并产出 partial（{@code call()} 返回前 {@link #result}
     * 完成）③ UI 收到终态并回写。① ≠ ②：{@code onCancelled} 跑在①，此刻 partial 尚不存在 ——
     * 直接读 {@code lastExtractStats} 会拿到 null（首次运行）或上一次运行的残留。
     * 因此取消回写不读 holder，只在本通道上挂"②完成后投递一次"的回调。</p>
     *
     * <p><b>陈旧守卫</b>：新 run 一开（{@code runGeneration} 递增），旧 run 的一切迟到回调
     * （三个终态回调与本通道挂的回写）都在派发目标线程上发现
     * {@code runId != runGeneration} 而 no-op —— 取消后立即再开新任务时旧回调不得污染新任务。</p>
     */
    static final class RunChannel {
        /** 本次 run 的序号（每 run 递增一次；旧任务回调凭它识别自己是陈旧调用） */
        final long runId;
        /** 本次 run 的结果容器：worker 完成处理后在 {@code call()} 里完成，<b>恰好一次</b>（异常路径完成 null） */
        final CompletableFuture<BatchProcessor.ProcessStats> result = new CompletableFuture<>();
        /** 到"UI 线程"的派发器（生产 = {@code Platform::runLater}；测试注入由测试线程按序 drain 的队列） */
        final Consumer<Runnable> dispatcher;

        RunChannel(long runId, Consumer<Runnable> dispatcher) {
            this.runId = runId;
            this.dispatcher = dispatcher;
        }

        /**
         * 终态回调（{@code onSucceeded/onFailed/onCancelled}）的陈旧守卫：
         * {@code currentRunId == runId} 才是当前 run。必须在派发目标线程（FX）上调用
         * （{@code runGeneration} 只在 FX 线程上读写）。
         */
        boolean isCurrent(long currentRunId) {
            return currentRunId == runId;
        }

        /**
         * 在 worker 完成处理后经派发器投递一次回写 —— <b>取消路径专用</b>（成功路径不走此通道，避免双回写）。
         *
         * <p>注册时机是 {@code onCancelled}（时点①）；回写动作在 {@link #result} 完成（时点②）之后、
         * 派发目标线程（时点③）上执行，且执行前<b>复查陈旧守卫</b> —— 注册之后又开了新 run，
         * 本次回写必须 no-op。回调无论注册时 {@code result} 是否已完成都恰好执行一次
         * （{@link CompletableFuture#whenComplete} 对已完成 future 仍会执行）；
         * worker 异常路径完成 null 时回写动作不触发（无统计可回写）。</p>
         *
         * @param currentRunId 当前 run 序号的只读入口（在派发目标线程上求值）
         * @param onFx         UI 回写动作（只收非 null 统计）
         */
        void deliverAfterWorkerFinished(LongSupplier currentRunId,
                                        Consumer<BatchProcessor.ProcessStats> onFx) {
            result.whenComplete((stats, error) -> dispatcher.accept(() -> {
                if (currentRunId.getAsLong() != runId) {
                    return;                     // 陈旧 run 的迟到回写：no-op
                }
                if (stats != null) {
                    onFx.accept(stats);
                }
            }));
        }
    }

    /**
     * 创建并启动提取任务（<b>必须在 FX 线程调用</b>）。
     *
     * @param request     不可变请求快照（单元计划、逐文件覆盖、会话目录、递归深度确认回调）
     * @param statusLabel 错误提醒的锚点节点
     * @param ui          界面收尾动作
     */
    public void run(WorkspaceExtractionService.ExtractionRequest request,
                    Label statusLabel,
                    ExtractionUi ui) {
        // 与搬迁前的时序一致：先置运行标记并切控件，再建任务、最后启动线程
        running.set(true);
        ui.extractionStarted();
        File sessionOutputFolder = new File(request.sessionOutputFolder());
        boolean openFolderAfterComplete = ui.openFolderAfterComplete();
        // B：每次 run 建立新的运行上下文（序号 + 结果通道）；旧任务的迟到回调据此识别陈旧
        long runId = ++runGeneration;
        RunChannel channel = new RunChannel(runId, Platform::runLater);
        Task<BatchProcessor.ProcessStats> task = new Task<>() {
            @Override
            protected BatchProcessor.ProcessStats call() {
                try {
                    // 限定名：匿名 Task 继承自 FutureTask，裸 run(...) 会被解析成 FutureTask.run()；
                    // 进度适配用 lambda 而非 this::updateProgress（后者在匿名 Task 内无法绑到单参 void 目标）
                    BatchProcessor.ProcessStats stats = ExtractionTaskCoordinator.this.extractionService
                            .run(request, () -> isCancelled(), v -> updateProgress(v, 1.0)).stats();
                    // 时点②：worker 停止并产出 partial —— 先落只读 holder，再完成本 run 的结果通道
                    ExtractionTaskCoordinator.this.lastExtractStats.set(stats);
                    channel.result.complete(stats);
                    return stats;
                } catch (Exception e) {
                    channel.result.complete(null);  // worker 异常：通道仍收尾（无统计可回写），异常照常抛出
                    throw e;
                }
            }
        };

        task.progressProperty().addListener((_, _, newVal) -> ui.setProgress(newVal.doubleValue()));

        task.setOnSucceeded(_ -> {
            if (!channel.isCurrent(runGeneration)) {
                return; // 陈旧任务的迟到回调：no-op（取消后立即再开新任务时不得污染新任务）
            }
            running.set(false);
            currentTask = null;
            ui.setProgress(1.0);
            ui.setStatusText(I18n.get("extract.done"));
            BatchProcessor.ProcessStats stats = task.getValue();
            if (stats != null) {
                ui.setStatusText(stats.getSummaryLine());
                // 满格条按结果配色（拍板 2026-09-24）：有失败/跳过 → 浅红；有警告 → 黄；否则保持绿
                ui.setProgressBarOutcome(stats.getFailCount() + stats.getSkipCount() > 0
                        ? ExtractOutcomeStyle.FAILED
                        : stats.getWarnCount() > 0 ? ExtractOutcomeStyle.WARNED
                        : ExtractOutcomeStyle.SUCCESS);
                ui.applyResults(stats);
            }
            openSessionFolder(sessionOutputFolder, openFolderAfterComplete);
        });

        task.setOnFailed(_ -> {
            if (!channel.isCurrent(runGeneration)) {
                return; // 陈旧任务的迟到回调：no-op
            }
            running.set(false);
            currentTask = null;
            ui.setStatusText(I18n.get("extract.statusError"));
            Throwable ex = task.getException();
            log.error(I18n.msg("log.maincontroller.37"), ex);
            NotificationHost.error(statusLabel, ErrorCode.PROCESS_FAILED.message(),
                    FontAwesomeSolid.FILE_EXPORT);
        });

        task.setOnCancelled(_ -> {
            if (!channel.isCurrent(runGeneration)) {
                return; // 陈旧任务的迟到回调：no-op（不得为旧 run 回写）
            }
            // 拍板：中途取消回写已处理部分（未处理的保持原样）。
            // 时点①：取消请求 ≠ worker 已退出 —— 此刻不得读 lastExtractStats（null/残留旧值），
            // 只在结果通道上挂"worker 完成后投递一次"的回写，partial 到那时才存在。
            channel.deliverAfterWorkerFinished(() -> runGeneration, ui::applyResults);
        });

        currentTask = task;
        new Thread(task).start();
    }

    /** 取消在途提取任务（无任务时为空操作）。 */
    public void cancel() {
        Task<BatchProcessor.ProcessStats> task = currentTask;
        if (task != null) {
            task.cancel(true);
        }
    }

    /** 供 UI 侧读取"最近一次提取统计"的唯一 holder（只读入口；写入只发生在 {@link #run} 内）。 */
    public AtomicReference<BatchProcessor.ProcessStats> extractStats() {
        return lastExtractStats;
    }

    /** 提取完成后按勾选打开会话输出目录（只由成功回调触发）。 */
    private void openSessionFolder(File sessionOutputFolder, boolean openFolderAfterComplete) {
        if (!openFolderAfterComplete || !sessionOutputFolder.exists()) {
            return;
        }
        try {
            // 步骤 7 同款写法：ProcessBuilder 数组形式（Runtime.exec(String) 自 Java 18 弃用，
            // 且按空格切分会拆坏含空格的路径）；native 镜像规避 AWT，不用 Desktop.open
            new ProcessBuilder("explorer.exe", sessionOutputFolder.getAbsolutePath()).start();
        } catch (Exception ex) {
            log.error(I18n.msg("log.maincontroller.38"), ex);
        }
    }
}
