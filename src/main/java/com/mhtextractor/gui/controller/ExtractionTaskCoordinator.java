package com.mhtextractor.gui.controller;

import com.mhtextractor.app.ExtractionPlanService;
import com.mhtextractor.app.ExtractionPreconditions;
import com.mhtextractor.app.PreStartGate;
import com.mhtextractor.app.PreStartGateContext;
import com.mhtextractor.app.PreStartGates;
import com.mhtextractor.app.WorkspaceExtractionService;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.gui.component.GuiNotifications;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.processor.BatchProcessor;
import com.mhtextractor.uicore.ExtractOutcomeStyles;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 提取任务协调器（Q3 / P2；步骤 2 起兼作提取启动编排的收口）—— 两段职责：
 * JavaFX {@link Task} 的<b>生命周期与 UI 回调</b>（业务段一律委托 {@link WorkspaceExtractionService}），
 * 以及「deny 链 → R1.6 参数未确认卡 → {@link PreStartGate} 顺序门 → 启动」的编排
 * （从 {@code MainController} 原样外迁，见 {@link #begin()}）。
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

    /**
     * 提取启动编排的宿主能力（步骤 2；由 {@code MainController} 用匿名类实现，方法逐个转发到
     * 其保留成员）。只收窄不发明：每个方法对应搬迁前 {@code onStartProcessing} /
     * {@code buildRuntimeConfig} / {@code startExtractionTask} / {@code extractionUi} 里对
     * 主窗口状态的一处读取或回写。
     */
    public interface Host {
        /** 列表动画进行中（原 {@code groupCommand.isAnimating()} 入口守卫）。 */
        boolean isAnimating();

        /** 输入目录框文本（原 {@code refs.inputFolderField().getText()}）。 */
        String inputFolder();

        /** 输出目录框文本（原 {@code refs.outputFolderField().getText()}）。 */
        String outputFolder();

        /** 数据序条目数（deny 链 {@code noExtractableItems} 的实参之一：原 {@code items.size()}）。 */
        int itemCount();

        /** 可见文件数（deny 链实参之二：原 {@code visibleCount()}）。 */
        long visibleCount();

        /** 当前是否图片模式（原 {@code refs.imageModeToggle().isSelected()}）。 */
        boolean imageMode();

        /** 「完成后打开输出目录」勾选（原 {@code refs.openFolderAfterCompleteCheck().isSelected()}）。 */
        boolean openFolderAfterComplete();

        /** 文本模式已确认参数缓存（可为 null = 未确认；原 {@code paramDomain.textParamsCache}）。 */
        AppConfig textParamsCache();

        /** 图片模式已确认参数缓存（可为 null = 未确认；原 {@code paramDomain.imageParamsCache}）。 */
        AppConfig imageParamsCache();

        /** 当前模式参数是否已确认（原 {@code currentModeConfirmed()}）。 */
        boolean currentModeConfirmed();

        /** 另一模式参数是否已确认（R1.6 提醒文案的区分依据）。 */
        boolean otherModeConfirmed();

        /** 当前模式已确认参数；未确认时返回默认参数（图片模式 ZIP/CBZ 校验用）。 */
        AppConfig currentModeParamsOrDefaults();

        /** 数据序只读投影（输出单元规划入参：原 {@code items}）。 */
        List<Object> dataOrder();

        /** 全部有效项目（原 {@code allVisibleItems()}，逐文件覆盖构造用）。 */
        List<PreviewItem> allVisibleItems();

        /** 取消工作区后台活动（原 {@code cancelWorkspaceActivity()}，启动提取前调用）。 */
        void cancelWorkspaceActivity();

        /** 状态栏标签（提醒锚点：deny 展示 / R1.6 卡 / 递归深度卡 / 任务失败卡）。 */
        Label statusLabel();

        /** 状态栏文案回写（原 {@code refs.statusLabel().setText(text)}）。 */
        void setStatusText(String text);

        /** 满格进度条活动样式（原 {@code setProgressBarActivity(styleClass)}）。 */
        void setProgressBarActivity(String styleClass);

        /** 提取结果一次性回写（原 {@code infoPresenter.applyExtractResults(stats)}）。 */
        void applyExtractResults(BatchProcessor.ProcessStats stats);

        /** 进度条控件（原 {@code refs.progressBar()}）。 */
        ProgressBar progressBar();

        /** 开始按钮（提取中禁用）。 */
        Button startButton();

        /** 停止按钮（提取中启用）。 */
        Button stopButton();

        // ==================== 功能一：只提取选中的列表项 ====================

        /** 是否只提取列表中被勾选的项目（"开始提取"点击时快照）。默认 false。 */
        default boolean extractCheckedOnly() {
            return false;
        }

        /** 本次会参与提取的可见项数（开关打开时 = 勾选且未移除的数量）。默认 0。 */
        default int visibleCheckedCount() {
            return 0;
        }

        /** 清空指定条目的提取结果色（不参与本次提取的可见项，避免误读 ✓/✗）。默认无操作。 */
        default void clearExtractOutcomes(List<PreviewItem> items) {
        }

        /** 提取运行中锁定/恢复列表勾选框与"只提取选中"开关（禁用 + 刷新单元格）。默认无操作。 */
        default void setExtractionUiLocked(boolean locked) {
        }
    }

    private final WorkspaceExtractionService extractionService;
    private final Host host;
    /**
     * 启动前顺序门（决策 C 的落位）：{@code begin()} 里按插入顺序串行询问。
     * 生产链 = {@link PreStartGates#productionChain(PreStartGateContext)}（草稿门 → 未命名组门 → 合并名门，
     * P1-5 下沉到 app 层）；测试经 {@link #preStartGates()} 插入。
     */
    private final List<PreStartGate> preStartGates = new ArrayList<>();
    /**
     * 提取运行标记（解析回调据此让出进度条 / 状态栏）。
     *
     * <p>🚨 <b>必须在 {@link #run} 的<b>第一行</b>置 true</b>：{@code MainController.updateProgressStatus}
     * 拿它当"提取期间进度条/状态栏归提取任务独占"的守卫。守卫<b>只认这个字段</b>——忘了置 true
     * （Q3/P2 真的漏过一次）会让守卫恒不生效，预解析完成回调随即把刚设好的满格条配色清成默认色，
     * GUI 自动化两条提取配色断言会稳定变红。复位见 {@link #run} 的三个终态回调（经 {@link #landTerminalState}）。</p>
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
    /**
     * 开始按钮可用性的唯一派生函数（生产 = {@code MainController} 注入 {@code paramDomain.updateStartButtonState}
     * 的转发；判定条件只在那一份派生函数里，<b>不得在本类复制</b>）。三终态落地时调用一次；
     * null = 历史兼容构造（无派生函数可重算，跳过）。
     */
    private final Runnable updateStartButtonState;

    /**
     * 只跑 {@link Task} 生命周期的构造（历史兼容：无编排宿主；{@link #begin()} 前必须换用
     * {@link #ExtractionTaskCoordinator(WorkspaceExtractionService, Host)}）。
     */
    public ExtractionTaskCoordinator(WorkspaceExtractionService extractionService) {
        this(extractionService, null, null);
    }

    /** 历史兼容构造：编排宿主（{@code MainController} 的匿名 {@link Host}）随本类创建时注入；派生函数为 null。 */
    public ExtractionTaskCoordinator(WorkspaceExtractionService extractionService, Host host) {
        this(extractionService, host, null);
    }

    /**
     * 生产构造：编排宿主 + 开始按钮唯一派生函数的重算入口（三终态落地时调用，见
     * {@link #landTerminalState}）。宿主非 null 时注册顺序门（P1-5：门规则已下沉
     * {@link PreStartGates#productionChain(PreStartGateContext)}，本类只做上下文转发与卡片渲染）。
     */
    public ExtractionTaskCoordinator(WorkspaceExtractionService extractionService, Host host,
                                     Runnable updateStartButtonState) {
        this.extractionService = extractionService;
        this.host = host;
        this.updateStartButtonState = updateStartButtonState;
        if (host != null) {
            // 查询全部转发 host；三个展示回调 = 搬迁前私有门类里的 GuiNotifications + I18n.get 文案渲染（逐字）
            PreStartGateContext ctx = new PreStartGateContext() {
                @Override public List<Object> dataOrder() { return host.dataOrder(); }
                @Override public ExtractionPlanService.ItemView itemView() {
                    return ExtractionPlanService.itemView(host.extractCheckedOnly());
                }
                @Override public boolean imageMode() { return host.imageMode(); }
                @Override public AppConfig textParamsCache() { return host.textParamsCache(); }
                @Override public AppConfig imageParamsCache() { return host.imageParamsCache(); }
                @Override public String outputFolder() { return host.outputFolder(); }
                @Override public boolean extractCheckedOnly() { return host.extractCheckedOnly(); }
                @Override public int visibleCheckedCount() { return host.visibleCheckedCount(); }
                @Override public long visibleCount() { return host.visibleCount(); }
                @Override public boolean draftDirty() { return ParamSidePanel.isDirty(host.statusLabel()); }

                @Override
                public void confirmUnnamedGroups(List<String> names, Consumer<Boolean> proceed) {
                    GuiNotifications.unnamedGroupConfirm(host.statusLabel(),
                            I18n.get("group.unnamedConfirm", "names",
                                    String.join(I18n.get("list.nameSeparator"), names)),
                            proceed);
                }

                @Override
                public void confirmSubsetMergedName(Consumer<Boolean> proceed) {
                    GuiNotifications.subsetMergedNameConfirm(host.statusLabel(),
                            I18n.get("extract.subsetMergedNameConfirm"), proceed);
                }

                @Override
                public void draftBlocksStart() {
                    GuiNotifications.draftBlocksStart(host.statusLabel());
                }
            };
            preStartGates.addAll(PreStartGates.productionChain(ctx)); // 决策 C：草稿门 → 未命名组门 → 合并名门
        }
    }

    /** 启动前顺序门链（决策 C 的插拔点；只增不删，顺序即优先级）。 */
    public List<PreStartGate> preStartGates() {
        return preStartGates;
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
            landTerminalState();
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
            landTerminalState();
            ui.setStatusText(I18n.get("extract.statusError"));
            Throwable ex = task.getException();
            log.error(I18n.msg("log.maincontroller.37"), ex);
            NotificationHost.error(statusLabel, I18n.get("error.PROCESS_FAILED"),
                    FontAwesomeSolid.FILE_EXPORT);
        });

        task.setOnCancelled(_ -> {
            if (!channel.isCurrent(runGeneration)) {
                return; // 陈旧任务的迟到回调：no-op（不得为旧 run 回写）
            }
            landTerminalState();
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

    /**
     * 三终态（成功 / 失败 / 取消）共用的 UI 终态落地（必须在<b>陈旧守卫之后</b>、派发目标线程（FX）上调用）：
     * 运行标记复位（解析回调据此让出进度条 / 状态栏）、在途任务清空、停止按钮禁用
     * （历史兼容构造 host 为 null 时跳过；搬迁前的 {@code ExtractionUi#extractionFinished} 唯一动作就是
     * {@code host.stopButton().setDisable(true)}）、开始按钮按唯一派生函数重算
     * （注入的 {@code updateStartButtonState}；null = 历史兼容构造，跳过）。
     * 包级可见：无头测试（{@code ExtractionTaskCoordinatorRunChannelTest} ⑦）逐终态驱动并断言落地契约。
     *
     * <p><b>幂等</b>（E1.1）：{@link #cancelAndReset} 已同步落地一次之后，任务线程迟到的 {@code onCancelled}
     * 仍会在陈旧守卫通过时再调一次本方法 —— 两次落地结果相同（复位动作全部幂等）、不得抛异常、
     * 不得改状态栏文案（文案只由终态回调自身与 {@code MainController.onStopProcessing} 各写一次）。</p>
     */
    void landTerminalState() {
        running.set(false);
        currentTask = null;
        if (host != null) {
            host.stopButton().setDisable(true);
            host.setExtractionUiLocked(false);   // 功能一：终态恢复勾选框与开关
        }
        if (updateStartButtonState != null) {
            updateStartButtonState.run();
        }
    }

    /**
     * 停止按钮的复位入口（E1.1）：取消在途任务 + {@link #landTerminalState} 同步落地。
     * {@code MainController.onStopProcessing} 的唯一业务入口 —— 运行标记、停止按钮与开始按钮
     * 的复位<b>只经此路</b>（不再有绕过落地直写 {@code running} 的路径）。
     */
    void cancelAndReset() {
        cancel();
        landTerminalState();
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

    // ==================== 提取启动编排（步骤 2：deny 链 → R1.6 卡 → 顺序门 → 启动） ====================
    //
    // 从 MainController 原样外迁（台账 1.1-②）：onStartProcessing（deny 链 + 图片模式额外校验 +
    // R1.6 参数未确认卡）、buildRuntimeConfig、overrides、startExtractionTask、recursionDecision、
    // extractionUi。GUI 事实一律经 {@link Host} 收窄访问；日志键（log.maincontroller.30..33）、
    // 文案键（extract.confirmModeSwitch / extract.confirmParamsUnset / params.datetimeFormat /
    // alert.recursionDepth* / extract.groupFileCount）、撤销语义与缓存失效路径全部不变。
    //
    // 与搬迁前的唯一新增是 {@link #preStartGates}（决策 C 的可插拔顺序点；P1-5 起生产链由
    // {@link PreStartGates#productionChain(PreStartGateContext)} 提供）：
    // deny 链 + R1.6 卡通过之后、{@link #startExtractionTask} 之前按插入顺序串行询问。

    /**
     * 提取启动编排入口（<b>必须在 FX 线程调用</b>；{@code MainController.onStartProcessing}
     * 的薄转发目标）。
     *
     * <p>顺序与搬迁前 {@code onStartProcessing} 逐条一致：</p>
     * <ol>
     *   <li>动画期间忽略；</li>
     *   <li>deny 链：blankInput → blankOutput → inputNotDirectory → noExtractableItems，
     *       逐条委托 {@link ExtractionPreconditions}（<b>顺序与级别不得变</b>），命中即展示并 return；</li>
     *   <li>图片模式额外校验（ZIP/CBZ 打包时确保输出目录存在，失败打 {@code log.maincontroller.30}）；</li>
     *   <li>R1.6 当前模式参数未确认 → 窗口内确认卡（区分「从未设置」与「检测到模式切换」两种文案），
     *       确认后进入顺序门（保留既有行为与位置，不属于本步改造范围）；</li>
     *   <li>{@link #preStartGates} 顺序门（生产链：草稿门 → 未命名组门 → 合并名门，任一取消即整体中止）；</li>
     *   <li>{@link #startExtractionTask}。</li>
     * </ol>
     */
    public void begin() {
        if (host.isAnimating()) {
            return;
        }
        String inputFolder = host.inputFolder();
        String outputFolder = host.outputFolder();

        // 会话 18 · E4-3：前置校验**规则**（含"该用 WARNING 还是 ERROR"）已抽到
        // ExtractionPreconditions（零 JavaFX、有独立单测）。
        // 逐条委托以保持原判定**顺序**；级别由规则给出，UI 只负责展示。
        // 刻意不调用聚合形态 validate(Facts)：那会把顺序与级别压平，属行为变更。
        var denial = ExtractionPreconditions.deny(ExtractionPreconditions.blankInput(inputFolder));
        if (denial == null) {
            denial = ExtractionPreconditions.deny(ExtractionPreconditions.blankOutput(outputFolder));
        }
        if (denial == null) {
            denial = ExtractionPreconditions.deny(ExtractionPreconditions.inputNotDirectory(inputFolder));
        }
        if (denial == null) {
            denial = ExtractionPreconditions.deny(ExtractionPreconditions.noExtractableItems(
                    host.itemCount(), (int) host.visibleCount()));
        }
        if (denial == null && host.extractCheckedOnly()) {
            // 功能一：开启"只提取选中的列表项"但一项未勾选 → ERROR 居中卡片（须确认）
            denial = ExtractionPreconditions.deny(
                    ExtractionPreconditions.noCheckedItems(host.visibleCheckedCount()));
        }
        if (denial != null) {
            // 会话 19：ERROR 走居中错误卡片（必须确认），WARNING 走右上角 toast（非阻塞）
            GuiNotifications.preconditionDenied(host.statusLabel(), denial);
            return;
        }

        boolean imageMode = host.imageMode();

        // 图片模式额外校验
        if (imageMode) {
            AppConfig params = host.currentModeParamsOrDefaults();
            if (params.isEnableZipPackaging() || params.isEnableCbzPackaging()) {
                File outRoot = new File(outputFolder);
                if (!outRoot.isDirectory() && !outRoot.mkdirs() && !outRoot.isDirectory()) {
                    log.warn(I18n.msg("log.maincontroller.30", outputFolder));
                }
            }
        }

        // 当前模式参数未确认 → 提醒（区分"从未设置"与"检测到模式切换"两种文案；会话 19：回调式确认）
        boolean otherConfirmed = imageMode ? host.textParamsCache() != null : host.imageParamsCache() != null;
        if (!host.currentModeConfirmed()) {
            String msg = otherConfirmed
                    ? I18n.get("extract.confirmModeSwitch")
                    : I18n.get("extract.confirmParamsUnset");
            GuiNotifications.paramTargetConfirm(host.statusLabel(), msg, ok -> {
                if (ok) {
                    runPreStartGates(0);
                }
            });
            return;
        }

        runPreStartGates(0);
    }

    /** 顺序门链：从第 {@code index} 道门起串行询问；全部放行（或链为空）才启动；任一取消即整体中止。 */
    private void runPreStartGates(int index) {
        if (index >= preStartGates.size()) {
            startExtractionTask();
            return;
        }
        preStartGates.get(index).ask(ok -> {
            if (ok) {
                runPreStartGates(index + 1);
            }
        });
    }

    /** 以已确认参数（未确认时用默认参数）+ 主窗口 GUI 状态构建运行时提取配置（{@code AcceptanceDriver} 公开契约，签名不变；实现已下沉 {@code app.ExtractionPlanService}）。 */
    AppConfig buildRuntimeConfig() {
        return ExtractionPlanService.runtimeConfig(host.imageMode(), host.imageParamsCache(), host.textParamsCache(),
                host.inputFolder(), host.outputFolder(), host.openFolderAfterComplete());
    }

    /**
     * 执行正式提取任务（6.4/6.8：按输出单元拆分批次——未分组按来源 + 各组；
     * 进度跨单元聚合、取消传播、stats 汇总、单元失败不中断整体）
     */
    private void startExtractionTask() {
        // 取消工作区后台活动，避免与提取任务争用进度条/状态栏
        host.cancelWorkspaceActivity();
        boolean imageMode = host.imageMode();

        // 生成时间戳会话目录，隔离每次提取运行
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern(I18n.get("params.datetimeFormat")));
        String sessionOutputFolder = new File(host.outputFolder(), timestamp).getPath();
        if (!new File(sessionOutputFolder).mkdirs()) {
            log.warn(I18n.msg("log.maincontroller.31", sessionOutputFolder));
        }
        log.info(I18n.msg("log.maincontroller.32", sessionOutputFolder));

        // 功能一：只提取选中项时，先清空"本次不参与的可见项"的旧提取结果色（避免误读 ✓/✗）
        if (host.extractCheckedOnly()) {
            List<PreviewItem> nonParticipating = host.allVisibleItems().stream()
                    .filter(i -> !i.selected).toList();
            if (!nonParticipating.isEmpty()) {
                host.clearExtractOutcomes(nonParticipating);
            }
            log.info(I18n.msg("log.maincontroller.39", host.visibleCheckedCount(),
                    (int) host.visibleCount()));
        }

        // 构建输出单元计划（FX 线程快照；计划与单元参数补充已下沉 app.ExtractionPlanService，P3-3b-3）
        var units = ExtractionPlanService.plan(host.dataOrder(), imageMode, host.textParamsCache(),
                host.imageParamsCache(), sessionOutputFolder, host.inputFolder(), host.outputFolder(),
                host.openFolderAfterComplete(), host.extractCheckedOnly());
        log.info(I18n.msg("log.maincontroller.33", units.stream()
                .map(u -> I18n.get("extract.groupFileCount", "group", u.label(),
                        "count", u.fileCount())).toList()));
        var overrides = ExtractionPlanService.overrides(host.allVisibleItems());

        run(new WorkspaceExtractionService.ExtractionRequest(sessionOutputFolder, units, overrides, imageMode,
                        recursionDecision()),
                host.statusLabel(),
                extractionUi(sessionOutputFolder, host.openFolderAfterComplete()));
    }

    /**
     * 递归深度超限回调：窗口内居中确认（会话 19）。等待方是提取后台线程，不冻结界面 ——
     * {@code Platform.runLater} 把确认卡排到 FX 线程，本线程 {@code future.join()} 等结果。
     */
    private java.util.function.Supplier<Boolean> recursionDecision() {
        return () -> {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            String msg = I18n.get("alert.recursionDepthHeader") + "\n"
                    + I18n.get("alert.recursionDepthContent")
                    + I18n.get("alert.recursionDepthContinue")
                    + I18n.get("alert.recursionDepthSkip");
            Platform.runLater(() -> GuiNotifications.recursionDepthConfirm(host.statusLabel(), msg, future::complete));
            return future.join();
        };
    }

    /** 提取任务的界面收尾动作（Q3 / P2）：只有控件状态与列表回写，与搬迁前三个回调逐条对应。 */
    private ExtractionUi extractionUi(String sessionOutputFolder,
                                      boolean openFolderAfterComplete) {
        return new ExtractionUi() {
            @Override
            public File sessionOutputFolder() {
                return new File(sessionOutputFolder);
            }

            @Override
            public boolean openFolderAfterComplete() {
                return openFolderAfterComplete;
            }

            @Override
            public void setProgress(double value) {
                host.progressBar().setProgress(value);
            }

            @Override
            public void setProgressBarOutcome(ExtractOutcomeStyle style) {
                host.setProgressBarActivity(switch (style) {
                    case FAILED -> ExtractOutcomeStyles.PROGRESS_EXTRACT_FAILED;
                    case WARNED -> ExtractOutcomeStyles.PROGRESS_EXTRACT_WARNED;
                    case SUCCESS -> ExtractOutcomeStyles.PROGRESS_EXTRACT_SUCCESS;
                });
            }

            @Override
            public void extractionStarted() {
                host.setExtractionUiLocked(true);   // 功能一：提取中禁用勾选框与"只提取选中"开关
                host.startButton().setDisable(true);
                host.stopButton().setDisable(false);
                host.progressBar().setVisible(true);
                host.progressBar().setProgress(0);
                host.setProgressBarActivity(ExtractOutcomeStyles.PROGRESS_EXTRACT_SUCCESS);   // 提取中 = 开始提取按钮绿
            }

            @Override
            public void applyResults(BatchProcessor.ProcessStats stats) {
                host.applyExtractResults(stats);
            }

            @Override
            public void setStatusText(String text) {
                host.setStatusText(text);
            }
        };
    }
}
