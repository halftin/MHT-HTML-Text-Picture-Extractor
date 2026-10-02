package com.mhtextractor.gui.component;

import com.mhtextractor.i18n.I18n;
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
 * 覆盖式侧边栏宿主（Q6a 的 R1 + R3 容器）：从主界面<b>右边框滑出</b>、只占主区宽度 50%、
 * 覆盖而非挤压主界面，因此主界面布局代码零改动（R1.1）。
 *
 * <h2>它不改变主界面布局，也不被主界面布局改变</h2>
 * <p>主界面根（{@code MainView.fxml} 的 {@code BorderPane}）被<b>原样</b>放进一个覆盖层
 * {@code StackPane}，侧边栏只是这个覆盖层的第二个子节点，靠 {@link StackPane#setAlignment}
 * 定位到右侧（R3.3）。主界面自己的 {@code top/center/bottom/left} 一个都没动，
 * 所以"覆盖式 ⇒ 布局代码零改动"是可核对的：{@code MainView.fxml} 与 {@code MainController}
 * 的布局代码都不参与侧边栏的布局。</p>
 *
 * <h2>层序（R3.2：主界面 → 侧边栏 → 模态遮挡层 → 提醒卡片）</h2>
 * <p>本类<b>刻意不往 {@code shell} 里加兄弟节点</b>。{@code NotificationHost.install(scene, shell)}
 * 是把 toast 层与 {@code ModalPane} <b>追加</b>到 {@code shell} 的子节点末尾的
 * （后加入的兄弟节点盖住先加入的），所以任何"在 install 之后加进 shell"的节点都会盖住提醒层。
 * 这里的做法是：{@link #install(Parent)} 把主界面包进覆盖层并<b>返回</b>该覆盖层，
 * 由 {@code App.start()} 再把它包成 {@code shell} —— 于是侧边栏是覆盖层的<b>后代</b>，
 * 而覆盖层是 {@code shell} 的<b>第一个</b>子节点，提醒两层永远在它之后。
 * 相对关系由父子结构决定，<b>后人往 shell 里追加层也不会破坏它</b>，也不需要（更不许）用
 * {@code toFront()} 去提权 —— 那正好会把侧边栏提到提醒层之上。</p>
 *
 * <h2>动画与裁剪（R3.4）</h2>
 * <p>滑出/滑入用 {@code translateX}（200ms）。节点越出父容器边界时 JavaFX 默认<b>不</b>裁剪，
 * 所以覆盖层挂了随其尺寸绑定的 {@link Rectangle} clip：越出主区的部分不画出来。
 * 收起是"先播完动画再 {@code setVisible(false)}"，{@link #isRightShowing()} 返回的是
 * <b>逻辑</b>开合状态（与动画帧无关），供守卫与断言使用。</p>
 *
 * <h2>开合状态不持久化（R3.5）</h2>
 * <p>启动默认全部折叠：{@link #install(Parent)} 里侧边栏一上来就是不可见，不碰
 * {@code GuiState} / {@code logs/gui-state.json}。</p>
 *
 * <h2>只有两个收起入口（R1.3）</h2>
 * <p>标题栏的线条图标按钮（R1.2）+ 面板内容里的「确认」按钮（由内容自己调
 * {@link #hideRight()}）。本类<b>不</b>给覆盖层挂"点击外部关闭"，所以点击主界面其它区域
 * 不会收起 —— 那正是 Q6b 的 R1.6/R1.7/R1.8 要处理的事（切换目标确认卡 / dirty 禁用开始 /
 * 收起前提示），接缝见 {@link #showRight(Node, String, Runnable)} 的 {@code onCollapse}。</p>
 */
public final class SidePanelHost {

    /** 侧边栏宽度占主区宽度的比例（R1.1：50%）。 */
    private static final double WIDTH_RATIO = 0.5;
    /** 滑出/滑入时长（R3.4）。 */
    private static final Duration SLIDE_DURATION = Duration.millis(200);
    /** 覆盖层 → 宿主。Weak 键：窗口关闭后不留引用（与 NotificationHost 同法）。 */
    private static final Map<Parent, SidePanelHost> HOSTS = new WeakHashMap<>();

    private final StackPane overlay;
    private final BorderPane panel = new BorderPane();
    private final Label titleLabel = new Label();
    private final Button collapseButton = new Button();

    private TranslateTransition slide;
    /** 标题栏「收起」按钮的动作（null = 直接 {@link #hideRight()}）。 */
    private Runnable onCollapse;
    private boolean rightShowing;

    private SidePanelHost(StackPane overlay) {
        this.overlay = overlay;

        panel.getStyleClass().add("side-panel");
        // 探针/自动化靠 id 找节点，不靠内部结构（见 tools/probes/SidePanelProbe.java）
        panel.setId("sidePanel");
        titleLabel.getStyleClass().add("title-3");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        collapseButton.setId("sidePanelCollapse");
        collapseButton.getStyleClass().add("side-panel-collapse");
        collapseButton.setGraphic(new FontIcon(FontAwesomeSolid.ANGLE_DOUBLE_RIGHT));
        collapseButton.setTooltip(new Tooltip(I18n.get("sidepanel.collapse")));
        collapseButton.setOnAction(e -> collapseRequested());

        HBox header = new HBox(8, titleLabel, spacer, collapseButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 12, 10, 12));
        header.getStyleClass().add("side-panel-header");
        panel.setTop(header);
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
     * @return 覆盖层（内含 mainRoot，侧边栏之后加入其中）
     */
    public static StackPane install(Parent mainRoot) {
        StackPane overlay = new StackPane(mainRoot);
        // 空区域不拦鼠标（R3.3）：只有侧边栏节点自己覆盖右侧 50%，左侧照常命中主界面
        overlay.setPickOnBounds(false);
        // clip（R3.4）：translateX 会让侧边栏越出主区边界，越出的部分不得画出来
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(overlay.widthProperty());
        clip.heightProperty().bind(overlay.heightProperty());
        overlay.setClip(clip);

        SidePanelHost host = new SidePanelHost(overlay);
        StackPane.setAlignment(host.panel, Pos.CENTER_RIGHT);
        // 宽度 = 主区 × 50%（R1.1）。pref 与 max 都要绑：StackPane 会把子节点拉到 max，
        // 只绑 pref 的话面板会被拉满整宽（实测：面板盖住整个主界面）。
        host.panel.prefWidthProperty().bind(overlay.widthProperty().multiply(WIDTH_RATIO));
        host.panel.maxWidthProperty().bind(overlay.widthProperty().multiply(WIDTH_RATIO));
        // R3.5：启动默认折叠
        host.panel.setVisible(false);
        overlay.getChildren().add(host.panel);

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

    // ==================== 开合 ====================

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
        this.onCollapse = onCollapse;
        titleLabel.setText(title == null ? "" : title);
        if (content != null && panel.getCenter() != content) {
            panel.setCenter(content);
        }
        panel.setVisible(true);
        rightShowing = true;
        // 先摆到主区右侧之外再滑到 0。首次显示时面板还没被布局过、宽度是 0，
        // 所以先强制一次布局拿到真实宽度，否则第一次打开不会有滑出动画。
        overlay.applyCss();
        overlay.layout();
        panel.setTranslateX(panel.getWidth());
        slideTo(0, false);
    }

    /** 右侧滑入并隐藏；动画播完才置不可见（R3.4）。 */
    public void hideRight() {
        rightShowing = false;
        slideTo(Math.max(panel.getWidth(), 0), true);
    }

    /**
     * 当前逻辑开合状态（与动画帧无关；动画播完与否都返回同一值，便于守卫与断言）。
     *
     * <p>Q6b 的 R1.7（dirty 时禁用开始提取）与 R1.8（收起前提示）都要先问"侧边栏是不是开着"。</p>
     */
    public boolean isRightShowing() {
        return rightShowing;
    }

    /**
     * 标题栏「收起」按钮节点（R1.2 的那个入口）。
     *
     * <p>暴露它是为了让探针 / 自动化能驱动"两个收起入口之一"而<b>不必靠节点结构猜</b>
     * （{@code tools/probes/SidePanelProbe} 会核对它与 {@code #sidePanelCollapse} 是同一个节点）；
     * 内容里的「确认」按钮不在这里（那是内容自己的出口）。Q6b 的 R1.8（收起前 dirty 提示）
     * 若要给按钮加状态，也从这里拿。</p>
     */
    public Button collapseButton() {
        return collapseButton;
    }

    // ==================== 内部 ====================

    private void collapseRequested() {
        if (onCollapse != null) {
            onCollapse.run();
        } else {
            hideRight();
        }
    }

    private void slideTo(double toX, boolean hideWhenFinished) {
        if (slide != null) {
            slide.stop();
        }
        slide = new TranslateTransition(SLIDE_DURATION, panel);
        slide.setToX(toX);
        slide.setOnFinished(e -> {
            if (hideWhenFinished) {
                panel.setVisible(false);
            }
        });
        slide.playFromStart();
    }
}
