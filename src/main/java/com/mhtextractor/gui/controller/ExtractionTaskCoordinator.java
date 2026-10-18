package com.mhtextractor.gui.controller;

import com.mhtextractor.uiport.PortEvent;
import com.mhtextractor.uiport.WorkspaceQueries;
import com.mhtextractor.uicore.PreStartGate;
import com.mhtextractor.app.Texts;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.gui.component.GuiNotifications;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.model.ExtractionStats;
import com.mhtextractor.uicore.PreviewRow;
import com.mhtextractor.uicore.ExtractOutcomeStyles;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 提取启动编排与终态渲染（Q3 / P2；P3-B6 起只剩前端两段）：
 * <ol>
 *   <li>「deny 链 → R1.6 参数未确认卡 → {@link PreStartGate} 顺序门 → 启动」的编排
 *       （从 {@code MainController} 原样外迁，见 {@link #begin()}）；</li>
 *   <li>提取任务通道事件（{@link PortEvent.ExtractionChannelEvent}）的<b>前端渲染</b> ——
 *       任务代际、终态恰好一次、取消竞态、结果通道已随 B6 整体下沉
 *       端口的 {@code startExtraction/cancelExtraction}，本类只把事件翻译成控件状态与列表回写
 *       （{@link #applyExtractionEvent}）。</li>
 * </ol>
 *
 * <p><b>任务通道契约（P3-B6，逐字保留，权威实现见 app 层）</b>：① 每任务一代际（旧任务迟到
 * 回调 no-op）② 终态恰好一次（成功/取消/失败各恰好交付一次；取消请求 ≠ worker 已退出）
 * ③ 不得把派发器当隐式同步契约（回写必须先有显式完成信号）④ 取消后立即再开新任务不得互相污染。
 * 本类的事件处理是<b>幂等</b>的（重复投递只重复复位），但后端保证不重复投递。</p>
 *
 * <p><b>本类含 JavaFX，属 {@code gui} 层</b>；业务侧不得反向依赖它。</p>
 */
@Slf4j
public final class ExtractionTaskCoordinator {

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
     * 提取启动编排与终态渲染的宿主能力（步骤 2；由 {@code MainController} 用匿名类实现，方法逐个转发到
     * 其保留成员）。只收窄不发明：每个方法对应搬迁前 {@code onStartProcessing} /
     * {@code buildRuntimeConfig} / {@code startExtractionTask} / {@code extractionUi} /
     * 三终态回调里对主窗口状态的一处读取或回写。
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
        List<PreviewRow> allVisibleItems();

        /** 取消工作区后台活动（原 {@code cancelWorkspaceActivity()}，启动提取前调用）。 */
        void cancelWorkspaceActivity();

        /** 状态栏标签（提醒锚点：deny 展示 / R1.6 卡 / 递归深度卡 / 任务失败卡）。 */
        Label statusLabel();

        /** 状态栏文案回写（原 {@code refs.statusLabel().setText(text)}）。 */
        void setStatusText(String text);

        /** 满格进度条活动样式（原 {@code setProgressBarActivity(styleClass)}）。 */
        void setProgressBarActivity(String styleClass);

        /** 提取结果一次性回写（原 {@code infoPresenter.applyExtractResults(stats)}）。 */
        void applyExtractResults(ExtractionStats stats);

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
        default void clearExtractOutcomes(List<PreviewRow> items) {
        }

        /** 提取运行中锁定/恢复列表勾选框与"只提取选中"开关（禁用 + 刷新单元格）。默认无操作。 */
        default void setExtractionUiLocked(boolean locked) {
        }
    }

    private final Host host;
    private final WorkspaceQueries queries;
    private final com.mhtextractor.uiport.Uiport port;
    private com.mhtextractor.uiport.PreStartStateVm preStart;
    /**
     * 启动前顺序门（决策 C 的落位）：{@code begin()} 里按插入顺序串行询问。
     * 生产链 = {@link PreStartGates#productionChain(PreStartGateContext)}（草稿门 → 未命名组门 → 合并名门，
     * P1-5 下沉到 app 层）；测试经 {@link #preStartGates()} 插入。
     */
    private final List<PreStartGate> preStartGates = new ArrayList<>();
    /**
     * 开始按钮可用性的唯一派生函数（生产 = {@code MainController} 注入 {@code paramDomain.updateStartButtonState}
     * 的转发；判定条件只在那一份派生函数里，<b>不得在本类复制</b>）。终态落地时调用一次；
     * null = 历史兼容构造（无派生函数可重算，跳过）。
     */
    private final Runnable updateStartButtonState;
    /** 启动命令入口（组合根装配：{@code MainController} 在 {@code initialize()} 里绑定到端口命令；绑定前点击开始为空操作）。 */
    private Consumer<Boolean> startExtraction;

    /**
     * 编排宿主 + 开始按钮唯一派生函数的重算入口（三终态落地时调用，见
     * {@link #applyExtractionEvent}）。宿主非 null 时注册顺序门（P1-5：门规则已下沉
     * {@link PreStartGates#productionChain(PreStartGateContext)}，本类只做上下文转发与卡片渲染）。
     */
    public ExtractionTaskCoordinator(Host host, com.mhtextractor.uiport.Uiport port) {
        this(host, null, port);
    }

    public ExtractionTaskCoordinator(Host host, Runnable updateStartButtonState, com.mhtextractor.uiport.Uiport port) {
        this.host = host;
        this.port = java.util.Objects.requireNonNull(port);
        this.queries = port.queries();
        this.updateStartButtonState = updateStartButtonState;
        if (host != null) {
            preStartGates.add(proceed -> {
                if (!ParamSidePanel.isDirty(host.statusLabel())) proceed.accept(true);
                else { GuiNotifications.draftBlocksStart(host.statusLabel()); proceed.accept(false); }
            });
            preStartGates.add(proceed -> {
                var names = preStart.defaultNamedGroups().stream().map(no -> Texts.get("core.groupDefault") + no).toList();
                if (names.isEmpty()) proceed.accept(true);
                else GuiNotifications.unnamedGroupConfirm(host.statusLabel(),
                        Texts.get("group.unnamedConfirm", "names", String.join(Texts.get("list.nameSeparator"), names)), proceed);
            });
            preStartGates.add(proceed -> {
                if (!preStart.subsetMergedName() || host.visibleCheckedCount() >= host.visibleCount()) proceed.accept(true);
                else GuiNotifications.subsetMergedNameConfirm(host.statusLabel(),
                        Texts.get("extract.subsetMergedNameConfirm"), proceed);
            });
        }
    }

    /** 启动前顺序门链（决策 C 的插拔点；只增不删，顺序即优先级）。 */
    public List<PreStartGate> preStartGates() {
        return preStartGates;
    }

    public WorkspaceQueries queries() { return queries; }

    /** 组合根装配（{@code MainController.initialize()}）：把「启动提取」命令绑定到端口（后端任务通道）。 */
    public void bindStartExtraction(Consumer<Boolean> start) {
        this.startExtraction = start;
    }

    // ==================== 提取启动编排（步骤 2：deny 链 → R1.6 卡 → 顺序门 → 启动） ====================
    //
    // 从 MainController 原样外迁（台账 1.1-②）：onStartProcessing（deny 链 + 图片模式额外校验 +
    // R1.6 参数未确认卡）、buildRuntimeConfig、overrides、startExtractionTask、recursionDecision、
    // extractionUi。GUI 事实一律经 {@link Host} 收窄访问；日志键（log.maincontroller.30..33、39）、
    // 文案键（extract.confirmModeSwitch / extract.confirmParamsUnset / params.datetimeFormat /
    // alert.recursionDepth* / extract.groupFileCount）、撤销语义与缓存失效路径全部不变。
    //
    // 与搬迁前的唯一新增是 {@link #preStartGates}（决策 C 的可插拔顺序点；P1-5 起生产链由
    // {@link PreStartGates#productionChain(PreStartGateContext)} 提供）：
    // deny 链 + R1.6 卡通过之后、{@link #startExtractionTask} 之前按插入顺序串行询问。
    //
    // P3-B6：请求构建（会话目录 / 单元计划 / 逐文件覆盖 / 递归确认）与任务通道已下沉 app 层
    // （端口 startExtraction/cancelExtraction），{@link #startExtractionTask} 只做前端预清理、
    // 界面锁定与命令转发；日志键 log.maincontroller.31/32/33/39 随请求构建迁至 app 层（逐键不变）。

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
        if (host.isAnimating()) return;
        AppConfig runtime = buildRuntimeConfig();
        if (port instanceof com.mhtextractor.uicore.FrontendPort frontend)
            frontend.execute(backend -> backend.queries().preStartState(runtime, host.extractCheckedOnly()), this::beginWithState);
        else beginWithState(queries.preStartState(runtime, host.extractCheckedOnly()));
    }

    private void beginWithState(com.mhtextractor.uiport.PreStartStateVm state) {
        preStart = state;
        if (state.denial() != null) {
            GuiNotifications.preconditionDenied(host.statusLabel(), state.denial());
            return;
        }
        boolean confirmed = host.imageMode() ? state.imageConfirmed() : state.textConfirmed();
        boolean otherConfirmed = host.imageMode() ? state.textConfirmed() : state.imageConfirmed();
        if (!confirmed) {
            GuiNotifications.paramTargetConfirm(host.statusLabel(),
                    Texts.get(otherConfirmed ? "extract.confirmModeSwitch" : "extract.confirmParamsUnset"),
                    ok -> { if (ok) runPreStartGates(0); });
        } else runPreStartGates(0);
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
        AppConfig config=host.currentModeParamsOrDefaults().copyExtractionParams();
        config.setImageModeEnabled(host.imageMode());config.setInputFolder(host.inputFolder());
        config.setOutputFolder(host.outputFolder());config.setOpenFolderAfterComplete(host.openFolderAfterComplete());return config;
    }

    /**
     * 执行正式提取任务（P3-B6：请求构建与任务通道已下沉端口 {@code startExtraction}，
     * 此处只剩三件前端事：取消工作区后台活动、只提取选中时的旧结果色清理、界面锁定（原
     * {@code extractionUi.extractionStarted} 五连）+ 命令转发。日志键 log.maincontroller.39 随请求
     * 构建迁至 app 层（逐键不变），本处清理与锁定顺序与迁移前一致。）
     */
    private void startExtractionTask() {
        // 取消工作区后台活动，避免与提取任务争用进度条/状态栏
        host.cancelWorkspaceActivity();

        // 功能一：只提取选中项时，先清空"本次不参与的可见项"的旧提取结果色（避免误读 ✓/✗）
        if (host.extractCheckedOnly()) {
            List<PreviewRow> nonParticipating = host.allVisibleItems().stream()
                    .filter(i -> !i.selected).toList();
            if (!nonParticipating.isEmpty()) {
                host.clearExtractOutcomes(nonParticipating);
            }
        }

        // 提取任务已受理：控件切到"提取中"（与搬迁前 run() 里 extractionStarted 五连逐条对应）
        host.setExtractionUiLocked(true);   // 功能一：提取中禁用勾选框与"只提取选中"开关
        host.startButton().setDisable(true);
        host.stopButton().setDisable(false);
        host.progressBar().setVisible(true);
        host.progressBar().setProgress(0);
        host.setProgressBarActivity(ExtractOutcomeStyles.PROGRESS_EXTRACT_SUCCESS);   // 提取中 = 开始提取按钮绿

        if (startExtraction != null) {
            startExtraction.accept(host.extractCheckedOnly());
        }
    }

    // ==================== B6：提取任务通道事件的前端渲染 ====================

    /**
     * 提取任务通道事件入口（{@code MainController.onPortEvent} 的薄转发目标）：事件由后端
     * 任务通道（{@code startExtraction/cancelExtraction}）经派发器投递到 FX 线程、<b>恰好一次</b>
     * （成功/失败终态在 worker 收尾后；取消终态在取消请求时刻；取消 partial 经
     * {@link PortEvent.ExtractionResult} 单独投递）。本方法只做控件状态与列表回写渲染，
     * 与搬迁前 {@code onSucceeded/onFailed/onCancelled} 三回调逐条对应（幂等：重复投递只重复复位）。
     */
    public void applyExtractionEvent(PortEvent.ExtractionChannelEvent event) {
        if (event instanceof PortEvent.ExtractionProgress progress) {
            host.progressBar().setProgress(progress.overall());
        } else if (event instanceof PortEvent.ExtractionTerminal terminal) {
            landTerminalUi();
            switch (terminal.end()) {
                case SUCCEEDED -> {
                    host.progressBar().setProgress(1.0);
                    host.setStatusText(Texts.get("extract.done"));
                    ExtractionStats stats = terminal.stats();
                    if (stats != null) {
                        host.setStatusText(Texts.extractionSummary(stats));
                        // 满格条按结果配色（拍板 2026-09-24）：有失败/跳过 → 浅红；有警告 → 黄；否则保持绿
                        host.setProgressBarActivity(stats.failCount() + stats.skipCount() > 0
                                ? ExtractOutcomeStyles.PROGRESS_EXTRACT_FAILED
                                : stats.warnCount() > 0 ? ExtractOutcomeStyles.PROGRESS_EXTRACT_WARNED
                                : ExtractOutcomeStyles.PROGRESS_EXTRACT_SUCCESS);
                        host.applyExtractResults(stats);
                    }
                    openSessionFolder(terminal.sessionOutputFolder(), host.openFolderAfterComplete());
                }
                case FAILED -> {
                    host.setStatusText(Texts.get("extract.statusError"));
                    // 异常日志由后端 worker 收尾打（log.maincontroller.37，逐键不变），前端只渲染错误卡片
                    NotificationHost.error(host.statusLabel(), Texts.get("error.PROCESS_FAILED"),
                            FontAwesomeSolid.FILE_EXPORT);
                }
                case CANCELLED -> {
                    // 终态落地即止：「extract.stopped」文案由 MainController.onStopProcessing 恰一次写入；
                    // partial 回写经 ExtractionResult 在 worker 收尾后投递
                }
            }
        } else if (event instanceof PortEvent.ExtractionResult result) {
            // 拍板：中途取消回写已处理部分（未处理的保持原样）—— 后端结果通道在 worker 完成后恰好投递一次
            host.applyExtractResults(result.stats());
        }
    }

    /**
     * 三终态（成功 / 失败 / 取消）共用的 UI 终态落地（事件已由后端保证恰好一次；本动作幂等）：
     * 停止按钮禁用、勾选框与开关解锁（功能一）、开始按钮按唯一派生函数重算
     * （注入的 {@code updateStartButtonState}；null = 历史兼容构造，跳过）。
     * 运行标记复位由后端任务通道持有（{@code PortEvent.RunningChanged}），
     * 不在前端重复维护第二份状态。
     */
    private void landTerminalUi() {
        host.stopButton().setDisable(true);
        host.setExtractionUiLocked(false);   // 功能一：终态恢复勾选框与开关
        if (updateStartButtonState != null) {
            updateStartButtonState.run();
        }
    }

    /** 提取完成后按勾选打开会话输出目录（只由成功终态触发）。 */
    private void openSessionFolder(String sessionOutputFolder, boolean openFolderAfterComplete) {
        if (sessionOutputFolder == null || !openFolderAfterComplete
                || !new File(sessionOutputFolder).exists()) {
            return;
        }
        try {
            // 步骤 7 同款写法：ProcessBuilder 数组形式（Runtime.exec(String) 自 Java 18 弃用，
            // 且按空格切分会拆坏含空格的路径）；native 镜像规避 AWT，不用 Desktop.open
            new ProcessBuilder("explorer.exe", new File(sessionOutputFolder).getAbsolutePath()).start();
        } catch (Exception ex) {
            log.error(Texts.msg("log.maincontroller.38"), ex);
        }
    }
}
