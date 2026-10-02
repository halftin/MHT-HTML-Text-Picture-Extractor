package com.mhtextractor.gui.controller;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.gui.component.SidePanelHost;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.util.GroupItem;
import com.mhtextractor.util.ParamScope;
import javafx.scene.Node;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * 参数配置的<b>非模态右侧侧边栏</b>（Q6a）：把 Q5a 的 {@link ParamFormPane} 装进
 * {@link SidePanelHost} 的覆盖式侧边栏里，取代原来「提取参数设置」模态窗口的用户路径。
 *
 * <p>面板本体就是那个 Pane（R1.10：模态窗口与非模态侧边栏共用同一个 Pane，
 * 本类<b>不</b>复制它的控件/联动/文案/收集逻辑），本类只负责"装在哪里 + 开合语义"：</p>
 * <ul>
 *   <li><b>一个窗口一个面板实例</b>（{@link #of(Node)} 按宿主缓存）：草稿状态机住在
 *       {@code ParamFormPane} 里，实例复用才谈得上"折叠 = 保留草稿"。</li>
 *   <li><b>载入时机</b>（R1.5）：只在<b>换了目标</b>（或换了模式）时 {@code load()}。
 *       同一目标折叠后再展开<b>不</b>重新载入 —— 草稿留在控件里，这正是"折叠 = 收起面板并保留草稿"。
 *       折叠/展开路径<b>绝不</b>调 {@code load()} / {@code commitDraft()}
 *       （前者会重记基线并重填控件 = 丢草稿；后者会把草稿当已提交）。</li>
 *   <li><b>提交语义</b>（R1.9）：仅「确认」提交 —— {@code ParamFormPane.onConfirm} 内部先
 *       {@code commitDraft()} 再把出口交到这里，本类只在 {@code ok==true} 时把采集结果交给
 *       宿主回调。取消只收起面板、草稿保留。</li>
 *   <li><b>命名配置 CRUD</b>：与模态壳共用 {@link ParamPanelActions}（见其 javadoc 的"为什么共用"）。</li>
 * </ul>
 *
 * <h2>留给 Q6b 的接缝（本步刻意不做）</h2>
 * <ul>
 *   <li><b>R1.7（dirty 时禁用开始提取）</b>：{@link #isDirty(Node)} 已把"当前是否有未提交草稿"
 *       转出去（<b>无副作用</b>：面板没建过就是 false），
 *       {@code MainController.updateStartButtonState()} 里加一个守卫即可（本步不加）。</li>
 *   <li><b>R1.6（切换目标确认卡）</b>：{@link #show} 里切换目标<a>之前</a>那一处，已留 TODO。</li>
 *   <li><b>R1.8（收起时 dirty 提示卡）</b>：{@link #collapse()} 里，已留 TODO。</li>
 *   <li><b>R3.6（关窗语义）</b>：本类不碰关窗路径，也不碰
 *       {@code NotificationHost.hasPendingDecision(...)} 守卫。</li>
 * </ul>
 */
public final class ParamSidePanel {

    /** 宿主 → 面板。Weak 键：窗口关闭后不留引用（与 NotificationHost / SidePanelHost 同法）。 */
    private static final Map<SidePanelHost, ParamSidePanel> PANELS = new WeakHashMap<>();

    private final SidePanelHost host;
    private final ParamFormPane pane = new ParamFormPane();

    /** 当前已载入的目标 / 模式（= 面板基线与草稿归属谁）；null = 还没载入过。 */
    private ParamScope.Target loadedTarget;
    private boolean loadedImageMode;

    /** 宿主回调：确认时把采集到的参数交回去（由 {@code MainController} 接线）。 */
    private Consumer<AppConfig> onCommit;

    /**
     * 取（首次调用则创建）该窗口的参数侧边栏。
     *
     * @param anchor 主窗口内任意节点（用于定位覆盖层里的 {@link SidePanelHost}）；
     *               宿主必须已由 {@code App.start()} 经 {@link SidePanelHost#install} 装配 ——
     *               没装就抛异常，由调用方按"打开失败"路径如实报给用户（不静默吞掉）。
     */
    public static ParamSidePanel of(Node anchor) {
        SidePanelHost sidePanelHost = SidePanelHost.of(anchor);
        if (sidePanelHost == null) {
            throw new IllegalStateException("SidePanelHost 未安装：见 App.start() 的 SidePanelHost.install(root)");
        }
        return PANELS.computeIfAbsent(sidePanelHost, ParamSidePanel::new);
    }

    /**
     * 当前是否有<b>未提交</b>的参数草稿（R1.5 的 {@code ParamDraft} 判定）。
     *
     * <p>🚩 <b>Q6b/R1.7 接缝</b>：{@code MainController.updateStartButtonState()} 里加上
     * {@code || ParamSidePanel.isDirty(statusLabel)} 即可实现"有草稿时禁用开始提取"。本步<b>不接</b>。
     * 本方法<b>不会</b>因为被查询就创建面板（面板没建过 → false）。</p>
     */
    public static boolean isDirty(Node anchor) {
        SidePanelHost sidePanelHost = SidePanelHost.of(anchor);
        ParamSidePanel panel = sidePanelHost == null ? null : PANELS.get(sidePanelHost);
        return panel != null && panel.isDirty();
    }

    private ParamSidePanel(SidePanelHost host) {
        this.host = host;
        new ParamPanelActions(pane).install();
        pane.setCloseHandler(this::onExit);
        // 🚨 「确定」在 ParamDialog.fxml 里带 defaultButton="true"，而 defaultButton 是**场景级**的：
        // 面板一旦嵌进主窗口，主窗口里任何地方按 ENTER（哪怕焦点在文件列表或目录框）都会触发这个按钮
        // ⇒ 静默提交参数 + 收起面板。侧边栏实例必须关掉它（模态壳自建实例，仍保留 FXML 的 ENTER 提交）。
        // 已由 tools/probes/SidePanelProbe 的 "ENTER in the main window does NOT fire..." 断言钉死。
        pane.confirmButton.setDefaultButton(false);
    }

    /**
     * 打开（或切换目标后重新打开）侧边栏。
     *
     * @param target     参数目标（全局 / 组 / 条目）
     * @param imageMode  当前提取模式
     * @param textCache  全局文本参数缓存（另一模式的控件由它初始化）
     * @param imageCache 全局图片参数缓存
     * @param commit     点「确认」时的回调（参数已提交为草稿基线，见 {@link #onExit}）
     */
    public void show(ParamScope.Target target, boolean imageMode,
                     AppConfig textCache, AppConfig imageCache, Consumer<AppConfig> commit) {
        this.onCommit = commit;
        if (loadedTarget == null || !sameTarget(loadedTarget, target) || loadedImageMode != imageMode) {
            // TODO(Q6b / R1.6)：目标不同且当前 dirty 时，先弹"切换目标会丢弃草稿"确认卡再 load；
            //  本步按指南先实现"直接切换"（新目标 = 新基线，旧草稿随之作废）。
            pane.load(textCache, imageCache, imageMode, target);
            loadedTarget = target;
            loadedImageMode = imageMode;
        }
        host.showRight(pane, I18n.get("dialog.title"), this::collapse);
    }

    /** 侧边栏当前是否展开（Q6b 的 R1.7/R1.8 守卫用）。 */
    public boolean isShowing() {
        return host.isRightShowing();
    }

    /** 当前是否有未提交草稿（实例入口，见 {@link #isDirty(Node)} 的类级接缝说明）。 */
    public boolean isDirty() {
        return pane.isDirty();
    }

    // ==================== 内部 ====================

    /**
     * 面板出口（确认 / 取消）。
     *
     * <p>{@code ParamFormPane.onConfirm} 已经把本次编辑提交成新基线（{@link ParamFormPane#commitDraft()}），
     * 所以这里只需要把采集结果交回宿主；取消则什么都不提交。</p>
     */
    private void onExit(boolean ok) {
        if (ok && onCommit != null) {
            onCommit.accept(pane.collect());
        }
        // R1.3：面板里的「确认」/「取消」都是"收起"入口；点主界面其它区域不会走到这里。
        // R1.5：取消也<b>不</b>丢草稿 —— 下一个 show() 只要目标没变就不会 load()。
        collapse();
    }

    private void collapse() {
        // TODO(Q6b / R1.8)：收起时若 isDirty() 先弹提示卡（"收起会保留草稿"），用户确认后再 hideRight。
        host.hideRight();
    }

    /** 目标同一性：全局按类型、组与条目按<b>实例</b>（record 的 equals 会把参数快照也比进去）。 */
    private static boolean sameTarget(ParamScope.Target a, ParamScope.Target b) {
        if (a instanceof ParamScope.GroupTarget(GroupItem<?> ga)) {
            return b instanceof ParamScope.GroupTarget gb && gb.group() == ga;
        }
        if (a instanceof ParamScope.ItemTarget<?> ia) {
            return b instanceof ParamScope.ItemTarget<?> ib && ib.item() == ia.item();
        }
        return b instanceof ParamScope.GlobalTarget;
    }
}
