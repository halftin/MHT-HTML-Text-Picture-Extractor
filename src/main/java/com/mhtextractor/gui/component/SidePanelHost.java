package com.mhtextractor.gui.component;

import com.mhtextractor.i18n.Texts;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 覆盖式侧边栏宿主（Q6a 的 R1 + R3 容器；Q8b/R2 起支持<b>左</b>侧）：
 * 从主界面左边框或右边框滑出、只占主区宽度 50%、覆盖而非挤压主界面，
 * 因此主界面布局代码零改动（R1.1）。
 *
 * <h2>两侧是<b>两个独立面板节点</b>（Q8b / R3.1）</h2>
 * <p>{@link #showRight} 与 {@link #showLeft} 各自拥有一个 {@link BorderPane}（各自的标题栏、
 * 收起按钮、动画与逻辑开合状态），因此<b>可以同时展开</b>、各占 50%，
 * 且各自的内容互不顶替（R3.1 的"并存"要求）。两者合起来会覆盖整个主界面 ——
 * 那是需求组合的必然结果（R2.1 与 R1.1 都要 50%），已确认可接受。</p>
 * <p>🚩 <b>不得</b>用一个"全尺寸透明容器"承载侧边栏（R3.3）：每个面板节点自身尺寸就是
 * 主区 × 50%，靠 {@link StackPane#setAlignment} 贴到左边或右边；不展开时整节点不可见，
 * 空区域不参与命中测试。</p>
 *
 * <h2>它不改变主界面布局，也不被主界面布局改变</h2>
 * <p>主界面根（{@code MainView.fxml} 的 {@code BorderPane}）被<b>原样</b>放进一个覆盖层
 * {@code StackPane}，侧边栏只是这个覆盖层的后续子节点，靠 {@link StackPane#setAlignment}
 * 定位（R3.3）。主界面自己的 {@code top/center/bottom/left} 一个都没动，
 * 所以"覆盖式 ⇒ 布局代码零改动"是可核对的：{@code MainView.fxml} 与 {@code MainController}
 * 的布局代码都不参与侧边栏的布局。</p>
 *
 * <h2>层序（R3.2：主界面 → 侧边栏 → 模态遮挡层 → 提醒卡片）</h2>
 * <p>本类<b>刻意不往 {@code shell} 里加兄弟节点</b>。{@code NotificationHost.install(scene, shell)}
 * 是把 toast 层与 {@code ModalPane} <b>追加</b>到 {@code shell} 的子节点末尾的
 * （后加入的兄弟节点盖住先加入的），所以任何"在 install 之后加进 shell"的节点都会盖住提醒层。
 * 这里的做法是：{@link #install(Parent)} 把主界面包进覆盖层并<b>返回</b>该覆盖层，
 * 由 {@code App.start()} 再把它包成 {@code shell} —— 于是两侧面板都是覆盖层的<b>后代</b>，
 * 而覆盖层是 {@code shell} 的<b>第一个</b>子节点，提醒两层永远在它之后。
 * 相对关系由父子结构决定，<b>后人往 shell 里追加层也不会破坏它</b>，也不需要（更不许）用
 * {@code toFront()} 去提权 —— 那正好会把侧边栏提到提醒层之上。</p>
 *
 * <h2>动画与裁剪（R3.4）</h2>
 * <p>滑出/滑入用 {@code translateX}（200ms）：右侧面板从 {@code +宽度} 滑到 0，
 * 左侧面板从 {@code -宽度} 滑到 0。节点越出父容器边界时 JavaFX 默认<b>不</b>裁剪，
 * 所以覆盖层挂了随其尺寸绑定的 {@link Rectangle} clip：越出主区的部分不画出来。
 * 收起是"先播完动画再 {@code setVisible(false)}"，{@link #isRightShowing()} /
 * {@link #isLeftShowing()} 返回的是<b>逻辑</b>开合状态（与动画帧无关），供守卫与断言使用。</p>
 *
 * <h2>开合状态不持久化（R3.5）</h2>
 * <p>启动默认全部折叠：{@link #install(Parent)} 里两侧面板一上来就是不可见，不碰
 * {@code GuiState} / {@code logs/gui-state.json}。</p>
 *
 * <h2>收起入口（R1.3）</h2>
 * <p>标题栏的线条图标按钮（R1.2；左侧为 {@code ANGLE_DOUBLE_LEFT}，R2.1）+
 * 面板内容里的出口按钮（由内容自己调 {@link #hideRight()} / {@link #hideLeft()}）。
 * 本类<b>不</b>给覆盖层挂"点击外部关闭"，所以点击主界面其它区域不会收起 ——
 * 那正是 Q6b 的 R1.6/R1.7/R1.8 要处理的事（切换目标确认卡 / dirty 禁用开始 /
 * 收起前提示），接缝见 {@link #showRight(Node, String, Runnable)} 的 {@code onCollapse}。</p>
 */
public final class SidePanelHost {

    /** 侧边栏宽度占主区宽度的比例（R1.1：50%）。 */
    private static final double WIDTH_RATIO = 0.5;
    /** 滑出/滑入时长（R3.4）。 */
    private static final Duration SLIDE_DURATION = Duration.millis(200);
    /** 覆盖层 → 宿主。Weak 键：窗口关闭后不留引用（与 NotificationHost 同法）。 */
    private static final Map<Parent, SidePanelHost> HOSTS = new WeakHashMap<>();

    /**
     * 悬浮圆钮与主区<b>右边缘</b>的间距（步骤 4 · 决策 H 的校正值）。
     *
     * <p>圆钮必须不压住主界面右侧的垂直滚动条：右侧信息栏外缘是"主区右缘 − HBox 内边距 8 −
     * VBox 内边距 16 = −24"，其文本区的滚动条再向内约 15px。44 = 24 + 15 + 5 呼吸，四档窗口
     * 下都成立（由 {@code UiProbe -Dprobe.reopen=true} 断言钉住 —— 改这里先跑那条探针）。
     * 左侧（预留）用同值镜像。</p>
     */
    private static final double REOPEN_EDGE_MARGIN = 44;

    private final StackPane overlay;
    /** 右侧面板（Q6a 的参数侧边栏；节点 id 保持 {@code sidePanel} 不变）。 */
    private final SideState right = new SideState("sidePanel", "sidePanelCollapse", "sidePanelReopen",
            Pos.CENTER_RIGHT, FontAwesomeSolid.ANGLE_DOUBLE_RIGHT, FontAwesomeSolid.ANGLE_LEFT, +1);
    /** 左侧面板（Q8b 的 HTML 源码查看器，R2.1）。与右侧是两个独立节点（R3.1）。 */
    private final SideState left = new SideState("sourceViewerPanel", "sourceViewerCollapse", "sidePanelReopen-left",
            Pos.CENTER_LEFT, FontAwesomeSolid.ANGLE_DOUBLE_LEFT, FontAwesomeSolid.ANGLE_RIGHT, -1);

    private Duration slideDuration = SLIDE_DURATION;

    /** 侧边栏两侧（步骤 4 · 决策 2.2：圆钮 API 按 side 参数化；本期只做右侧，左侧预留）。 */
    public enum Side { LEFT, RIGHT }

    /**
     * 一侧面板的全部可变态。
     *
     * <p>{@code slideSign} 是这一侧"滑出方向"的符号：右侧面板在右侧，收起位置是
     * {@code +translateX}；左侧面板在左侧，收起位置是 {@code -translateX}（R3.4）。</p>
     *
     * <p>{@code reopen} 是这一侧的悬浮圆钮（步骤 4 · 功能二，决策 2.1/2.12）：覆盖层里的
     * <b>真节点</b>（有尺寸、可命中），与侧边栏同层、在其之上；收起后由调用方经
     * {@link SidePanelHost#showReopen(Side, Runnable)} 显示。图标是<b>单</b>箭头线条图标
     * （收起按钮的双箭头刻意不用；决策 2.3：只有箭头、无目标名/角标）。</p>
     */
    private static final class SideState {
        private final String panelId;
        private final String collapseId;
        private final String reopenId;
        private final Pos alignment;
        private final FontAwesomeSolid icon;
        private final FontAwesomeSolid reopenIcon;
        private final double slideSign;

        private final BorderPane panel = new BorderPane();
        private final Label titleLabel = new Label();
        private final Button collapseButton = new Button();
        private final Button reopen = new Button();

        private TranslateTransition slide;
        /** 标题栏「收起」按钮的动作（null = 直接隐藏本侧）。 */
        private Runnable onCollapse;
        private boolean showing;

        SideState(String panelId, String collapseId, String reopenId, Pos alignment,
                  FontAwesomeSolid icon, FontAwesomeSolid reopenIcon, double slideSign) {
            this.panelId = panelId;
            this.collapseId = collapseId;
            this.reopenId = reopenId;
            this.alignment = alignment;
            this.icon = icon;
            this.reopenIcon = reopenIcon;
            this.slideSign = slideSign;
        }
    }

    private SidePanelHost(StackPane overlay) {
        this.overlay = overlay;
        initSide(right, false);
        initSide(left, true);
    }

    /**
     * 建一侧面板的标题栏与收起按钮。
     *
     * <p>收起按钮在<b>标题栏内侧</b>（两侧都是）：右侧面板的按钮贴右边缘（R1.2 的原样），
     * 左侧面板的按钮也贴右边缘、图标朝左（R2.1）—— 视觉上"往面板贴着的那条边收回去"。</p>
     */
    private void initSide(SideState side, boolean isLeft) {
        side.panel.getStyleClass().add("side-panel");
        // 探针/自动化靠 id 找节点，不靠内部结构（见 tools/probes/SidePanelProbe.java）
        side.panel.setId(side.panelId);
        side.titleLabel.getStyleClass().add("title-3");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        side.collapseButton.setId(side.collapseId);
        side.collapseButton.getStyleClass().add("side-panel-collapse");
        side.collapseButton.setGraphic(new FontIcon(side.icon));
        side.collapseButton.setTooltip(new Tooltip(Texts.get("sidepanel.collapse")));
        side.collapseButton.setOnAction(e -> collapseRequested(side));

        HBox header = new HBox(8, side.titleLabel, spacer, side.collapseButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 12, 10, 12));
        header.getStyleClass().add("side-panel-header");
        side.panel.setTop(header);
        if (isLeft) {
            // 左侧面板的投影与分隔线要镜像（css 里的 .side-panel 是按右侧写的）
            side.panel.getStyleClass().add("side-panel-left");
        }

        // 悬浮圆钮（步骤 4 · 功能二，决策 2.1/2.12）：右边缘垂直居中（Pos.CENTER_RIGHT 即垂直居中贴边）、
        // 圆形、单箭头线条图标；与侧边栏同层（都是覆盖层的子节点）。启动默认与面板同态：隐藏。
        side.reopen.setId(side.reopenId);
        side.reopen.getStyleClass().add("side-panel-reopen");
        side.reopen.setGraphic(new FontIcon(side.reopenIcon));
        side.reopen.setTooltip(new Tooltip(Texts.get("sidepanel.reopen")));
        side.reopen.setVisible(false);
        StackPane.setAlignment(side.reopen, side.alignment);
        // 右边缘间距见 REOPEN_EDGE_MARGIN（不得压住主界面滚动条）；左侧镜像（预留）
        StackPane.setMargin(side.reopen, isLeft
                ? new Insets(0, 0, 0, REOPEN_EDGE_MARGIN)
                : new Insets(0, REOPEN_EDGE_MARGIN, 0, 0));
    }

    // ==================== 接线 ====================

    /**
     * 把主界面根包进覆盖层并登记宿主。
     *
     * <p><b>调用方必须用返回的覆盖层作为场景根的子节点</b>（{@code App.start()} 里
     * {@code new StackPane(overlay)} 就是 shell），否则侧边栏无处安放。刻意<b>不</b>由本类
     * 自己改 {@code scene.setRoot(...)}：场景根由 App 决定，这里只交出覆盖层，
     * 层序推理才有一处唯一的真相。</p>
     *
     * @param mainRoot 主界面根节点（{@code MainView.fxml} 的 {@code BorderPane}）
     * @return 覆盖层（内含 mainRoot，左/右两侧面板之后加入其中）
     */
    public static StackPane install(Parent mainRoot) {
        StackPane overlay = new StackPane(mainRoot);
        // 空区域不拦鼠标（R3.3）：只有侧边栏节点自己覆盖它那一侧 50%，别处照常命中主界面
        overlay.setPickOnBounds(false);
        // clip（R3.4）：translateX 会让侧边栏越出主区边界，越出的部分不得画出来
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(overlay.widthProperty());
        clip.heightProperty().bind(overlay.heightProperty());
        overlay.setClip(clip);

        SidePanelHost host = new SidePanelHost(overlay);
        for (SideState side : new SideState[]{host.right, host.left}) {
            StackPane.setAlignment(side.panel, side.alignment);
            // 宽度 = 主区 × 50%（R1.1）。pref 与 max 都要绑：StackPane 会把子节点拉到 max，
            // 只绑 pref 的话面板会被拉满整宽（实测：面板盖住整个主界面）。
            side.panel.prefWidthProperty().bind(overlay.widthProperty().multiply(WIDTH_RATIO));
            side.panel.maxWidthProperty().bind(overlay.widthProperty().multiply(WIDTH_RATIO));
            // R3.5：启动默认折叠
            side.panel.setVisible(false);
            overlay.getChildren().add(side.panel);
            // 圆钮与面板同层（决策 2.12）；后加者在上，且与面板的可见态互斥，层序不影响命中
            overlay.getChildren().add(side.reopen);
        }

        HOSTS.put(overlay, host);
        return overlay;
    }

    /** 由主界面内的任意节点找到宿主（沿父链向上找登记过的覆盖层）；未安装 → {@code null}。 */
    public static SidePanelHost of(Node anchor) {
        for (Node n = anchor; n != null; n = n.getParent()) {
            SidePanelHost host = HOSTS.get(n);
            if (host != null) {
                return host;
            }
        }
        return null;
    }

    // ==================== 开合：右侧（R1.1，Q6a 原样） ====================

    /**
     * 右侧滑出（R1.1 / R3.4）。同一节点重复传入不会重新挂载（幂等）。
     *
     * @param content    面板内容（如 {@code ParamFormPane}）
     * @param title      标题栏文本（R1.2 的标题栏）
     * @param onCollapse 标题栏「收起」按钮的动作（{@code null} = 直接 {@link #hideRight()}）。
     *                   🚩 <b>Q6b/R1.8 接缝</b>：收起前的 dirty 提示卡挂在这里 ——
     *                   点「收起」时先弹提示卡、用户确认后再调 {@link #hideRight()}。
     */
    public void showRight(Node content, String title, Runnable onCollapse) {
        show(right, content, title, onCollapse);
    }

    /** 右侧滑入并隐藏；动画播完才置不可见（R3.4）。 */
    public void hideRight() {
        hide(right);
    }

    /**
     * 当前右侧<b>逻辑</b>开合状态（与动画帧无关；动画播完与否都返回同一值，便于守卫与断言）。
     *
     * <p>Q6b 的 R1.7（dirty 时禁用开始提取）与 R1.8（收起前提示）都要先问"侧边栏是不是开着"。</p>
     */
    public boolean isRightShowing() {
        return right.showing;
    }

    /**
     * 右侧标题栏「收起」按钮节点（R1.2 的那个入口）。
     *
     * <p>暴露它是为了让探针 / 自动化能驱动"两个收起入口之一"而<b>不必靠节点结构猜</b>
     * （{@code tools/probes/SidePanelProbe} 会核对它与 {@code #sidePanelCollapse} 是同一个节点）；
     * 内容里的「确认」按钮不在这里（那是内容自己的出口）。Q6b 的 R1.8（收起前 dirty 提示）
     * 若要给按钮加状态，也从这里拿。</p>
     */
    public Button collapseButton() {
        return right.collapseButton;
    }

    // ==================== 开合：左侧（Q8b / R2.1） ====================

    /**
     * 左侧滑出（R2.1）：与右侧完全对称 —— 挂的是另一个独立面板节点，
     * 所以两侧<b>可以同时展开</b>（R3.1），互不顶替、互不隐藏。
     *
     * @param content    面板内容（如 {@code HtmlSourceViewer} 的查看器节点）
     * @param title      标题栏文本
     * @param onCollapse 标题栏「收起」按钮的动作（{@code null} = 直接 {@link #hideLeft()}）
     */
    public void showLeft(Node content, String title, Runnable onCollapse) {
        show(left, content, title, onCollapse);
    }

    /** 左侧滑入并隐藏（R2.9：无草稿语义，收起即隐藏）。 */
    public void hideLeft() {
        hide(left);
    }

    /** 当前左侧逻辑开合状态（语义同 {@link #isRightShowing()}）。 */
    public boolean isLeftShowing() {
        return left.showing;
    }

    /** 左侧标题栏「收起」按钮节点（R2.1：线条图标 {@code ANGLE_DOUBLE_LEFT}）。 */
    public Button leftCollapseButton() {
        return left.collapseButton;
    }

    /** 左侧面板节点（供探针核对几何；未展开时也不可见，但节点恒存在）。 */
    public Node leftPanelNode() {
        return left.panel;
    }

    /** 右侧面板节点（同上；Q6a 起探针是靠 {@code #sidePanel} 找的，这里给一个类型化入口）。 */
    public Node rightPanelNode() {
        return right.panel;
    }

    // ==================== 悬浮圆钮（步骤 4 · 功能二，决策 2.1/2.12） ====================

    /**
     * 显示该侧的悬浮圆钮并挂上「再展开」动作（收起侧边栏后由调用方调用）。
     *
     * <p>圆钮是覆盖层里的真节点（有尺寸、可命中），与侧边栏同层；重复显示只是换动作（幂等）。
     * 本期只有右侧用它（{@code ParamSidePanel.collapse()}），左侧 API 已参数化但<b>预留</b>
     * （决策 2.2/2.12）。</p>
     */
    public void showReopen(Side side, Runnable action) {
        SideState s = state(side);
        s.reopen.setOnAction(e -> action.run());
        s.reopen.setVisible(true);
    }

    /** 隐藏该侧的悬浮圆钮（会话结束 / 面板展开时由调用方调用）。 */
    public void hideReopen(Side side) {
        state(side).reopen.setVisible(false);
    }

    /** 该侧圆钮当前是否显示（探针/断言用）。 */
    public boolean isReopenShowing(Side side) {
        return state(side).reopen.isVisible();
    }

    /** 该侧圆钮节点（探针/自动化驱动用；id 为 {@code #sidePanelReopen} / {@code #sidePanelReopen-left}）。 */
    public Button reopenButton(Side side) {
        return state(side).reopen;
    }

    // ==================== 内部 ====================

    /**
     * 滑出时长的覆盖点（**只为探针**）。
     *
     * <p>🚩 存在的理由：收起是"先播完动画再 {@code setVisible(false)}"，动画是 200ms 的真实时钟；
     * 而探针在 FX 线程上做测量时会把脉冲一起挡住（本项目栽过同类坑：等待卡片只显示几毫秒），
     * 于是"面板到底停在哪"变成时序问题。传 0 让滑出/滑入变成<b>同步落位</b>：
     * 直接设 {@code translateX} 到终点、立刻置可见性 —— 几何与命中测试都是确定性的。
     * 生产代码从不调它（默认恒为 200ms，由 {@code SidePanelProbe} 的动画断言钉住）。</p>
     *
     * <p>⚠️ 为什么不能只把时长设成 0 了事：实测（{@code XAnimProbe}）{@code TranslateTransition}
     * 在 0ms 下<b>只触发 onFinished、不写属性值</b>，面板会永远停在
     * {@code translateX = ±宽度}（屏幕外），命中测试于是全部落在旧几何上 ——
     * 探针会看到一串与实现无关的假失败。所以这里显式落位，不走动画。</p>
     */
    public void setSlideMillisForProbe(long millis) {
        this.slideDuration = Duration.millis(millis);
    }

    /** 当前滑出时长（毫秒）。生产恒为 {@link #SLIDE_DURATION}；探针用它证明"动画默认没被关掉"。 */
    public long slideMillis() {
        return (long) slideDuration.toMillis();
    }

    /** {@link Side} → 该侧的可变态（{@code null} 视为右侧之外的唯一另一侧，即左侧）。 */
    private SideState state(Side side) {
        return side == Side.RIGHT ? right : left;
    }

    private void show(SideState side, Node content, String title, Runnable onCollapse) {
        side.onCollapse = onCollapse;
        side.titleLabel.setText(title == null ? "" : title);
        if (content != null && side.panel.getCenter() != content) {
            side.panel.setCenter(content);
        }
        if (side.showing) {
            // 步骤 4 · 决策 2.7/2.8：已显示则<b>原地换内容</b> —— 只换内容/标题/收起动作，
            // 不重放滑入动画、不重置逻辑开合状态。这一处同时修好"切换目标"与"同目标重开"
            // 两条重放路径（切换目标 = 保持展开 + 静默替换内容）。
            return;
        }
        side.panel.setVisible(true);
        side.showing = true;
        // 先摆到主区之外再滑到 0。首次显示时面板还没被布局过、宽度是 0，
        // 所以先强制一次布局拿到真实宽度，否则第一次打开不会有滑出动画。
        overlay.applyCss();
        overlay.layout();
        side.panel.setTranslateX(side.slideSign * side.panel.getWidth());
        slideTo(side, 0, false);
    }

    private void hide(SideState side) {
        side.showing = false;
        slideTo(side, side.slideSign * Math.max(side.panel.getWidth(), 0), true);
    }

    private void collapseRequested(SideState side) {
        if (side.onCollapse != null) {
            side.onCollapse.run();
        } else {
            hide(side);
        }
    }

    private void slideTo(SideState side, double toX, boolean hideWhenFinished) {
        if (side.slide != null) {
            side.slide.stop();
            side.slide = null;
        }
        if (slideDuration.toMillis() <= 0 || slideDuration.isIndefinite()) {
            // 0ms = 探针要的"同步落位"（见 setSlideMillisForProbe：0ms 的 TranslateTransition
            // 只触发 onFinished、不写属性值，靠它落位会留下一块屏幕外的面板）
            side.panel.setTranslateX(toX);
            if (hideWhenFinished) {
                side.panel.setVisible(false);
            }
            return;
        }
        side.slide = new TranslateTransition(slideDuration, side.panel);
        side.slide.setToX(toX);
        side.slide.setOnFinished(e -> {
            side.slide = null;
            if (hideWhenFinished) {
                side.panel.setVisible(false);
            }
        });
        side.slide.playFromStart();
    }
}
