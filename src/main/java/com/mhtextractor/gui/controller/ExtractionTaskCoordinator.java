package com.mhtextractor.gui.controller;

import com.mhtextractor.app.WorkspaceExtractionService;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.i18n.ErrorCode;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.processor.BatchProcessor;
import javafx.concurrent.Task;
import javafx.scene.control.Label;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
 *   <li>{@link #lastExtractStats} —— <b>partial 统计的唯一可变 holder</b>。取消时 JavaFX
 *       {@code Task.getValue()} 在 {@code onCancelled} 里拿不到（值只在 SUCCEEDED 后可见），
 *       故由服务返回的 stats 在本类里落一次，取消回调只读它。搬迁前它长在 {@code MainController} 上、
 *       由 {@code Task.call()} 写入；本类接管后<b>全项目仍只有这一处写</b>；</li>
 *   <li>{@code currentTask} —— 在途任务（停止按钮据此取消）。</li>
 * </ul>
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

    /** 满格进度条三态（与 {@code MainController} 的 STYLE_PROGRESS_EXTRACT_* 一一对应）。 */
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
    /** 最近一次提取的 partial/完整统计：取消时 {@code Task.getValue()} 不可用，回写经此 holder */
    final AtomicReference<BatchProcessor.ProcessStats> lastExtractStats = new AtomicReference<>();
    private volatile Task<BatchProcessor.ProcessStats> currentTask;

    public ExtractionTaskCoordinator(WorkspaceExtractionService extractionService) {
        this.extractionService = extractionService;
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
        Task<BatchProcessor.ProcessStats> task = new Task<>() {
            @Override
            protected BatchProcessor.ProcessStats call() {
                // 限定名：匿名 Task 继承自 FutureTask，裸 run(...) 会被解析成 FutureTask.run()；
                // 进度适配用 lambda 而非 this::updateProgress（后者在匿名 Task 内无法绑到单参 void 目标）
                BatchProcessor.ProcessStats stats = ExtractionTaskCoordinator.this.extractionService
                        .run(request, () -> isCancelled(), v -> updateProgress(v, 1.0)).stats();
                // 取消路径经 holder 回写（Task 取消时 getValue 不可用）
                ExtractionTaskCoordinator.this.lastExtractStats.set(stats);
                return stats;
            }
        };

        task.progressProperty().addListener((_, _, newVal) -> ui.setProgress(newVal.doubleValue()));

        task.setOnSucceeded(_ -> {
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
            running.set(false);
            currentTask = null;
            ui.setStatusText(I18n.get("extract.statusError"));
            Throwable ex = task.getException();
            log.error(I18n.msg("log.maincontroller.37"), ex);
            NotificationHost.error(statusLabel, ErrorCode.PROCESS_FAILED.message(),
                    FontAwesomeSolid.FILE_EXPORT);
        });

        task.setOnCancelled(_ -> {
            // 拍板：中途取消回写已处理部分（未处理的保持原样）
            BatchProcessor.ProcessStats partial = lastExtractStats.get();
            if (partial != null) {
                ui.applyResults(partial);
            }
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
