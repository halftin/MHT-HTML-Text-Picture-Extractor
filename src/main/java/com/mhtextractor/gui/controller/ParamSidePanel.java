package com.mhtextractor.gui.controller;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.gui.component.SidePanelHost;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.util.GroupItem;
import com.mhtextractor.util.ParamScope;
import javafx.scene.Node;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

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
 * <h2>Q6b：非模态付出的代价在这里补回来（R1.6 / R1.7 / R1.8 / R3.6）</h2>
 * <p>模态窗口顺带提供的互斥（不能随便换目标 / 不能带草稿开始提取 / 不能随便离开编辑态）在非模态下
 * 全部消失，本类把它们<b>显式</b>补回来，且都只是"多问一次 {@link #isDirty()} 再决定"：</p>
 * <ul>
 *   <li><b>R1.6（切换目标确认卡）</b>：{@link #show} 里目标不同且当前 dirty 时先弹居中确认卡，
 *       确定 = 丢弃草稿并载入新目标，取消 = <b>目标与草稿都不变</b>（什么都不做）。</li>
 *   <li><b>R1.7（dirty 时禁用开始提取）</b>：本类只负责"草稿变了就通知一次"，
 *       真值判定仍是 {@code ParamFormPane.isDirty()}，按钮态由宿主的
 *       {@code MainController.updateStartButtonState()} 算（唯一派生函数）。</li>
 *   <li><b>R1.8（收起时 dirty 提示卡）</b>：{@link #collapse()} 见
 *       {@link #collapsing} 的自抑制说明。</li>
 *   <li><b>R3.6（关窗语义）</b>：本类<b>不碰</b>关窗路径，也不碰
 *       {@code NotificationHost.hasPendingDecision(...)} 守卫 —— dirty 草稿不拦关窗（草稿随退出消失），
 *       R1.6 的确认卡<b>本身就是</b>"未答确认卡"，由既有守卫自动拦住关窗，无需新写守卫。</li>
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
     * 「草稿状态可能变了」的通知出口（由 {@code MainController} 接到
     * {@code updateStartButtonState()} 上；每窗口一个面板实例，故只接一次）。
     */
    private Runnable dirtyListener;

    /**
     * 收起流程的<b>自抑制</b>标记（R1.8）。
     *
     * <p>点了「稍后」后仍要在<b>不重新弹卡</b>的前提下真的收起，而"收起"的唯一入口是
     * {@link #collapse()}（标题栏按钮与面板出口都走它）。若另开一条 {@code hideRight()} 旁路，
     * 就成了"收起"的第二个实现点。故在第一次 {@code collapse()} 里置位，后续同一轮收起
     * （包括 R1.8 卡片回调里的那次重入）自动跳过守卫 —— 与 {@code NotificationHost.confirm}
     * 的 {@code pendingDecision} 兜底同一个思路：<b>守卫只管第一跳</b>。</p>
     */
    private boolean collapsing;

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
     * <p>R1.7 的判定入口：{@code MainController.updateStartButtonState()} 把它并进"开始提取"
     * 的禁用条件。本方法<b>不会</b>因为被查询就创建面板（面板没建过 → false），也无副作用。</p>
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
        // Q6b/R1.7：草稿状态通知的落点 —— 信号只有"可能变了"，真值仍由 pane.isDirty() 回答，
        // 按钮态由 MainController.updateStartButtonState()（唯一派生函数）算。
        pane.setDraftStateListener(this::notifyDirtyChanged);
        // 🚨 「确定」在 ParamDialog.fxml 里带 defaultButton="true"，而 defaultButton 是**场景级**的：
        // 面板一旦嵌进主窗口，主窗口里任何地方按 ENTER（哪怕焦点在文件列表或目录框）都会触发这个按钮
        // ⇒ 静默提交参数 + 收起面板。侧边栏实例必须关掉它（模态壳自建实例，仍保留 FXML 的 ENTER 提交）。
        // 已由 tools/probes/SidePanelProbe 的 "ENTER in the main window does NOT fire..." 断言钉死。
        pane.confirmButton.setDefaultButton(false);
    }

    // ==================== 宿主接线（R1.7） ====================

    /**
     * 注入「草稿状态变了」的回调（{@code MainController} 接到 {@code updateStartButtonState}）。
     *
     * <p>只传一个 {@link Runnable}：本类<b>不</b>把 dirty 值一起传出去（真值永远来自
     * {@code ParamFormPane.isDirty()}），避免出现第二个 dirty 副本。</p>
     */
    public void setDirtyListener(Runnable listener) {
        this.dirtyListener = listener;
    }

    // ==================== 打开 / 切换目标（R1.6） ====================

    /**
     * 打开（或切换目标后重新打开）侧边栏。三条路径（R1.5 + R1.6）：
     *
     * <ol>
     *   <li><b>目标不同（或模式变了）</b>：需要 {@code load()}（重记基线 ⇒ 草稿作废）。
     *       切换<b>已有草稿</b>时先弹居中确认卡问"切换会丢弃草稿" —— 确定才载入，
     *       取消则<b>目标与草稿都不变</b>（连信息栏都不动）。</li>
     *   <li><b>同一目标重开</b>：什么都不载入 ⇒ 草稿保留（折叠 = 收起并保留草稿）。</li>
     *   <li>🚩 首次打开（{@code loadedTarget == null}）走第 1 条但<b>不弹卡</b>：
     *       没有任何草稿可丢，弹卡就成了"第一次打开参数面板先问一句要不要丢草稿"。</li>
     * </ol>
     *
     * <p>🚩 {@code onCommit} 只在<b>真的要载入</b>时更新：否则取消切换后，面板里那个旧目标的
     * 「确认」会把参数应用到新目标上（静默串味）。</p>
     *
     * @param target     参数目标（全局 / 组 / 条目）
     * @param imageMode  当前提取模式
     * @param textCache  全局文本参数缓存（另一模式的控件由它初始化）
     * @param imageCache 全局图片参数缓存
     * @param commit     点「确认」时的回调（参数已提交为草稿基线，见 {@link #onExit}）
     */
    public void show(ParamScope.Target target, boolean imageMode,
                     AppConfig textCache, AppConfig imageCache, Consumer<AppConfig> commit) {
        // 需要载入 = 没载入过 / 换了目标 / 换了模式；其中"换目标"才可能丢草稿（首次打开没有草稿）
        boolean needLoad = loadedTarget == null
                || !sameTarget(loadedTarget, target) || loadedImageMode != imageMode;
        if (!needLoad) {
            // 同一目标重开：只更新出口并展开（**不** load ⇒ 保草稿）
            this.onCommit = commit;
            host.showRight(pane, I18n.get("dialog.title"), this::collapse);
            return;
        }
        if (loadedTarget != null && isDirty()) {
            // 丢弃草稿是不可逆的：先问，确定才 load()（取消路径连 host.showRight 都不走）
            NotificationHost.confirm(pane, I18n.get("sidepanel.switchDiscardsDraft"),
                    FontAwesomeSolid.SLIDERS_H, ok -> {
                        if (ok) {
                            load(target, imageMode, textCache, imageCache, commit);
                        }
                    });
            return;
        }
        // 首次打开，或无草稿的目标切换：直接载入（不打扰用户）
        load(target, imageMode, textCache, imageCache, commit);
    }

    /** 载入新目标（= 重记基线，草稿作废）并展开侧边栏；只有确认切换 / 首次打开才走到这里。 */
    private void load(ParamScope.Target target, boolean imageMode,
                      AppConfig textCache, AppConfig imageCache, Consumer<AppConfig> commit) {
        this.onCommit = commit;
        pane.load(textCache, imageCache, imageMode, target);
        this.loadedTarget = target;
        this.loadedImageMode = imageMode;
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

    /**
     * 当前<b>已载入</b>的目标（{@code null} = 还没载入过）。
     *
     * <p>暴露它只为让探针能断言"R1.6 取消后目标没变"这件事本身；目标同一性口径见
     * {@link #sameTarget(ParamScope.Target, ParamScope.Target)}。</p>
     */
    public ParamScope.Target loadedTarget() {
        return loadedTarget;
    }

    private void notifyDirtyChanged() {
        if (dirtyListener != null) {
            dirtyListener.run();
        }
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
        if (collapsing) {
            host.hideRight();                          // R1.8 卡片回调里的重入：守卫只管第一跳
            return;
        }
        collapsing = true;
        if (isDirty()) {
            // R1.8：dirty 时离开编辑态先说明（居中卡片，与"移除全部"同型；两个按钮都不设 defaultButton）
            NotificationHost.confirm(pane, I18n.get("sidepanel.collapseDraftHint"),
                    FontAwesomeSolid.SLIDERS_H, ok -> {
                        if (ok) {
                            // 「确认并提交」：复用唯一的提交路径（#confirmButton → ParamFormPane.onConfirm
                            // 先 commitDraft() 再走 closeHandler）—— 本类不另拼一套"提交"
                            pane.confirmButton.fire();
                        } else {
                            // 「稍后」：收起并保留草稿（不 commitDraft、不 load，草稿随控件留在面板里）
                            collapseNow();
                        }
                    });
            return;
        }
        collapseNow();
    }

    /**
     * 收起动作本体（{@link #collapse()} 的收尾，或 R1.8 的「稍后」分支）。
     *
     * <p>它不是"收起的第二个实现点"：真正的收起入口仍只有 {@link #collapse()} 一处 ——
     * 这里只是"已经把该问的问完了"之后的同一个动作，顺手清掉 {@link #collapsing} 自抑制标记，
     * 使下一次收起重新走一遍守卫。</p>
     */
    private void collapseNow() {
        host.hideRight();
        collapsing = false;
    }

    /**
     * 目标同一性（R1.6 的"目标是否相同"口径）：<b>全局按类型、组与条目按实例身份（{@code ==}）</b>。
     *
     * <p>为什么不直接比 record 的 {@code equals}：{@code GroupTarget}/{@code ItemTarget} 的 record 组件里
     * 带着 {@link GroupItem} / 条目与两份 {@link AppConfig} 参数快照，值相等的两份快照不代表"同一个目标"，
     * 而"同一个目标"才是本方法要回答的问题（决定要不要 {@code load()} = 要不要丢草稿）。</p>
     * <p>{@code ItemTarget.item()} 是泛型 {@code T}：主路径传的是
     * {@link com.mhtextractor.model.PreviewItem}，它<b>没有</b>覆写 {@code equals}（身份语义），
     * 但泛型不保证这一点，故统一用 {@code ==} 钉死身份口径 —— 与 {@code GroupItem} 同法。</p>
     */
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
