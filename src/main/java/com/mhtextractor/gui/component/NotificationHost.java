package com.mhtextractor.gui.component;

import atlantafx.base.controls.ModalPane;
import atlantafx.base.controls.Message;
import atlantafx.base.controls.Notification;
import atlantafx.base.theme.Styles;
import atlantafx.base.util.Animations;
import com.mhtextractor.i18n.I18n;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * 应用内提醒宿主：把"模态 Alert 弹窗"换成<b>窗口内</b>的提醒卡片（AtlantaFX {@code Notification}）。
 *
 * <p>三类语义（2026-09-27 拍板）：</p>
 * <ul>
 *   <li>{@link #toastWarning}/{@link #toastInfo} —— 右上角 <b>非阻塞</b> toast，可堆叠，点 ✕ 关闭；</li>
 *   <li>{@link #error} —— 屏内 <b>居中</b> 卡片，必须点"确定"才消失（persistent，遮挡层/ESC 不关）；</li>
 *   <li>{@link #confirm} —— 屏内居中卡片 + 回调，替代 {@code showAndWait()} 的<b>阻塞式</b>确认；</li>
 *   <li>{@link #busy} —— 屏内居中 {@code Message} 卡片，<b>零可交互控件</b>：后台干活，干完自动收卡片 + 收尾。</li>
 * </ul>
 *
 * <p><b>为什么要回调、不能 showAndWait</b>：AtlantaFX 的 {@code ModalPane} 只提供非阻塞的
 * {@code show(Node)}/{@code hide()}，因此"确认后再继续"的流程必须写成回调（{@link #confirm} 的
 * {@code onDecision} 在 FX 线程上、按钮点击时同步调用）。好处是确认期间不再占用 FX 线程，
 * 自动化也不再被模态 Alert 卡死（见 {@code FeatureExercise} 里被跳过的用例）。</p>
 *
 * <p><b>接线</b>：每个窗口（主窗口、参数设置窗口）把场景根包一层 {@link StackPane}，
 * 然后调用一次 {@link #install(Scene, StackPane)}；调用点用 {@link #toastInfo} 等静态方法，
 * 只传一个"本窗口内的任意节点"作为锚点即可（不需要控制器持有宿主字段）。</p>
 *
 * <p>⚠️ <b>刻意不设 {@code defaultButton}</b>：{@code Button.defaultButton} 是<b>场景级</b>的，
 * 实测在通知里设了它之后，焦点停在主界面其它按钮上按回车会触发<b>通知</b>的按钮（应用按钮不再响应）。
 * 本项目主源码原本零 {@code setDefaultButton}，设上去等于新增全局行为。</p>
 *
 * <p>⚠️ <b>居中卡片必须限制最大高度</b>：{@code ModalPane} 会把内容拉满整窗高度
 * （实测 400×761 → 加 {@code USE_PREF_SIZE} 后 400×149），见 {@link #showModal}。</p>
 */
@Slf4j
public final class NotificationHost {

    /** 卡片宽度：中英文长文案都要能在卡片内换行而不被截断（en 实测最宽 400 仍完整）。 */
    private static final double CARD_WIDTH = 400;
    /** 右上角 toast 上限：超出先移除最早的，防连续触发时无限堆叠。 */
    private static final int MAX_TOASTS = 5;
    /** 场景 → 宿主。Weak 键：窗口关闭后不留引用。 */
    private static final Map<Scene, NotificationHost> HOSTS = new WeakHashMap<>();

    private final VBox toastLayer = new VBox(10);
    private final ModalPane modalPane = new ModalPane();
    /**
     * 当前那张确认卡"回答后要交付给谁"。非 null = 有一张确认卡还没被回答。
     * 除了 {@link #hasPendingDecision} 给关窗用，它还是防"永不回答"的兜底：任何新卡片顶掉它之前，
     * 都先按"取消"交付决定（见 {@link #showModal}），否则调用方会永久阻塞。
     */
    private Consumer<Boolean> pendingDecision;

    private NotificationHost(StackPane shell) {
        toastLayer.setAlignment(Pos.TOP_RIGHT);
        toastLayer.setPadding(new Insets(12));
        // 空区域不拦截鼠标：toast 图层铺满窗口，但只有卡片本身响应点击（实测背景按钮仍可点）
        toastLayer.setPickOnBounds(false);
        StackPane.setAlignment(toastLayer, Pos.TOP_RIGHT);

        modalPane.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        modalPane.setAlignment(Pos.CENTER);
        // persistent=true：点遮挡层/ESC 不关闭 —— 与用户拍板一致（必须点明确按钮）
        modalPane.setPersistent(true);

        // 顺序即 z 序：内容 < toast < 居中模态
        shell.getChildren().addAll(toastLayer, modalPane);
    }

    // ==================== 接线 ====================

    /** 在场景上安装宿主。必须传入已包含原本场景根的 StackPane（见类 javadoc 的接线说明）。 */
    public static void install(Scene scene, StackPane shell) {
        if (scene == null || shell == null) {
            log.error(I18n.msg("log.notificationhost.02"));
            return;
        }
        if (!HOSTS.containsKey(scene)) {
            HOSTS.put(scene, new NotificationHost(shell));
        }
    }

    /**
     * 由锚点节点找到本窗口的宿主；场景上还没安装时<b>就地补装</b>
     * （根不是 StackPane 就包一层），避免"提醒被静默吞掉"。
     */
    private static NotificationHost of(Node anchor) {
        Scene scene = anchor == null ? null : anchor.getScene();
        if (scene == null) {
            log.error(I18n.msg("log.notificationhost.01"));
            return null;
        }
        NotificationHost host = HOSTS.get(scene);
        if (host != null) {
            return host;
        }
        Parent root = scene.getRoot();
        StackPane shell = root instanceof StackPane sp ? sp : new StackPane(root);
        if (shell != root) {
            scene.setRoot(shell);
        }
        NotificationHost created = new NotificationHost(shell);
        HOSTS.put(scene, created);
        return created;
    }

    // ==================== 公开入口（全部 1 行可调用） ====================
    //
    // 图标是"按用途"传的（不是按严重级别一套通用图标）：调用方给一个贴合语境的图标，
    // 卡片一眼就能看出"这是导入的事 / 缓存的事 / 配置的事"。不带图标的旧签名保留，
    // 用一个中性默认值（纯记日志/调试路径用）。

    /** 右上角信息 toast（非阻塞）。{@code icon} = 该卡片用途对应的图标。 */
    public static void toastInfo(Node anchor, String message, FontAwesomeSolid icon) {
        withHost(anchor, h -> h.pushToast(h.card(message, icon, null)));
    }

    /** 右上角信息 toast（默认中性图标）。 */
    public static void toastInfo(Node anchor, String message) {
        toastInfo(anchor, message, FontAwesomeSolid.INFO_CIRCLE);
    }

    /** 右上角警告 toast（非阻塞）。{@code icon} = 该卡片用途对应的图标。 */
    public static void toastWarning(Node anchor, String message, FontAwesomeSolid icon) {
        withHost(anchor, h -> h.pushToast(h.card(message, icon, Styles.WARNING)));
    }

    /** 右上角警告 toast（默认警告图标）。 */
    public static void toastWarning(Node anchor, String message) {
        toastWarning(anchor, message, FontAwesomeSolid.EXCLAMATION_TRIANGLE);
    }

    /** 居中错误卡片（必须点"确定"）。{@code icon} = 该卡片用途对应的图标。 */
    public static void error(Node anchor, String message, FontAwesomeSolid icon) {
        withHost(anchor, h -> {
            Notification ntf = h.card(message, icon, Styles.DANGER);
            ntf.setPrimaryActions(h.button(I18n.get("notification.ok"), h::hideModal));
            ntf.setOnClose(e -> {
                h.flash(ntf);
                h.hideModal();
            });
            h.showModal(ntf);
        });
    }

    /** 居中错误卡片（默认错误图标）。 */
    public static void error(Node anchor, String message) {
        error(anchor, message, FontAwesomeSolid.EXCLAMATION_CIRCLE);
    }

    /**
     * 无交互按钮的<b>等待卡片</b>：显示 {@link Message}（标题 + 说明 + 图标），
     * 后台线程执行 {@code work}，<b>完成后自动移除卡片并执行 {@code onDone}</b>。
     *
     * <p>为什么不用"点确定"的卡片：清空缓存这类"等着就行"的流程，用户无事可做，
     * 也不该能把它关掉（{@code persistent=true} 挡住 ESC 与遮挡层点击），所以不需要任何按钮；
     * 清完自动收卡片 + 自动收尾（关窗）。</p>
     *
     * <p>⚠️ {@code work} 跑在<b>后台线程</b>（别在里头碰 JavaFX 节点）；它抛异常也会走到
     * {@code onDone} —— 退出路径不允许因为缓存问题把程序卡住。</p>
     *
     * <p>⚠️ <b>卡片显示不出来也必须走完</b>：调用方（关窗流程）已经把关闭事件 consume 掉了，
     * 如果这里因为锚点没挂场景而直接返回，就会变成"窗口关不掉 + 收尾永不执行"的硬锁。</p>
     *
     * @param title       卡片标题（如"正在清空预览缓存…"）
     * @param description 卡片说明（可含 {@code \n}，换成多行的都是同一段文本）
     * @param icon        该卡片用途对应的图标（如清空缓存 → 扫帚）
     * @param work        后台工作（如清空缓存）
     * @param onDone      工作结束（成功或失败）后在 <b>FX 线程</b>执行（如关窗）
     */
    public static void busy(Node anchor, String title, String description, FontAwesomeSolid icon,
                            Runnable work, Runnable onDone) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> busy(anchor, title, description, icon, work, onDone));
            return;
        }
        NotificationHost host = of(anchor);
        if (host == null) {
            log.error(I18n.msg("log.notificationhost.04", "busy"));
        } else {
            host.showModal(host.messageCard(title, description, icon));
        }
        Thread t = new Thread(() -> {
            try {
                if (work != null) {
                    work.run();
                }
            } catch (Throwable ex) {
                log.error(I18n.msg("log.notificationhost.03", String.valueOf(ex)), ex);
            } finally {
                Platform.runLater(() -> {
                    if (host != null) {
                        host.hideModal();
                    }
                    if (onDone != null) {
                        onDone.run();
                    }
                });
            }
        }, "notification-busy");
        t.setDaemon(true);
        t.start();
    }

    /** 等待卡片（默认"处理中"图标）。 */
    public static void busy(Node anchor, String title, String description, Runnable work, Runnable onDone) {
        busy(anchor, title, description, FontAwesomeSolid.SPINNER, work, onDone);
    }

    /**
     * 当前是否有一张确认卡<b>还没被回答</b>。
     *
     * <p>用途：关窗。旧实现的 Alert 是 {@code APPLICATION_MODAL}，等回答期间<b>主窗根本关不掉</b>；
     * 换成窗口内卡片后窗口的 ✕ 仍可点，于是"关窗卡片顶掉确认卡 → 回调永不触发 → 后台线程
     * （非 daemon！）永久阻塞 → 窗口消失但进程残留"。这条断言就是用来恢复旧语义的。</p>
     */
    public static boolean hasPendingDecision(Node anchor) {
        NotificationHost host = of(anchor);
        return host != null && host.pendingDecision != null;
    }

    /** 居中确认卡片（默认问号图标）：替代 {@code showAndWait()} 的阻塞确认。 */
    public static void confirm(Node anchor, String message, Consumer<Boolean> onDecision) {
        confirm(anchor, message, FontAwesomeSolid.QUESTION_CIRCLE, onDecision);
    }

    /**
     * 居中确认卡片：替代 {@code showAndWait()} 的阻塞确认。
     *
     * @param icon       该确认"要决定什么"对应的图标（如移除 → 垃圾桶、配额 → 清单）
     * @param onDecision 用户选择（确定 = true）；卡片被顶替或宿主不可用时按 false 交付
     */
    public static void confirm(Node anchor, String message, FontAwesomeSolid icon, Consumer<Boolean> onDecision) {
        withHost(anchor, h -> {
            Notification ntf = h.card(message, icon, null);
            Consumer<Boolean> deliver = accepted -> {
                h.pendingDecision = null;
                if (onDecision != null) {
                    onDecision.accept(accepted);
                }
            };
            ntf.setPrimaryActions(
                    h.button(I18n.get("notification.ok"), () -> h.decide(deliver, true), true),
                    h.button(I18n.get("notification.cancel"), () -> h.decide(deliver, false), false));
            // ✕ 等价于"取消"（persistent 只挡遮挡层与 ESC，不挡显式按钮）
            ntf.setOnClose(e -> h.decide(deliver, false));
            // ⚠️ 顺序不能反：showModal 开头会把"上一张未回答的确认卡"按取消交付，
            //    若先登记 pendingDecision，等于新卡片一上来就被自己取消掉（实测踩过）
            h.showModal(ntf);
            h.pendingDecision = deliver;
        }, () -> {
            // 宿主解析不出来（锚点没挂场景）：绝不能静默——调用方可能正阻塞等待这个决定
            log.error(I18n.msg("log.notificationhost.04", "confirm"));
            if (onDecision != null) {
                onDecision.accept(false);
            }
        });
    }

    // ==================== 内部实现 ====================

    private static void withHost(Node anchor, Consumer<NotificationHost> action) {
        withHost(anchor, action, null);
    }

    /**
     * @param fallback 宿主解析不出来时的<b>降级路径</b>（null = 只记日志）。
     *                 对 {@link #confirm} 必须给降级路径：否则会变成"永不回答的确认"。
     */
    private static void withHost(Node anchor, Consumer<NotificationHost> action, Runnable fallback) {
        // 锚点是 JavaFX 节点：其 getScene() 只在 FX 线程上读（后台线程调用时先转投再解析）
        Runnable task = () -> {
            NotificationHost host = of(anchor);
            if (host != null) {
                action.accept(host);
            } else if (fallback != null) {
                fallback.run();
            }
        };
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    private Notification card(String message, FontAwesomeSolid icon, String styleClass) {
        Notification ntf = new Notification(message, new FontIcon(icon));
        ntf.getStyleClass().add(Styles.ELEVATED_1);
        if (styleClass != null) {
            ntf.getStyleClass().add(styleClass);
        }
        ntf.setPrefWidth(CARD_WIDTH);
        ntf.setMaxWidth(CARD_WIDTH);
        return ntf;
    }

    private Button button(String text, Runnable action) {
        return button(text, action, false);
    }

    private Button button(String text, Runnable action, boolean accent) {
        Button b = new Button(text);
        if (accent) {
            b.getStyleClass().add(Styles.ACCENT);
        }
        b.setOnAction(e -> action.run());
        return b;
    }

    private void decide(Consumer<Boolean> onDecision, boolean accepted) {
        hideModal();
        if (onDecision != null) {
            onDecision.accept(accepted);
        }
    }

    /**
     * {@link Message} 卡片（标题 + 说明 + 图标）。
     *
     * <p><b>刻意不设 {@code onClose}</b>：{@code MessageSkin} 用 {@code CLOSEABLE} 伪类控制那个 ✕
     * （字节码里就是 {@code getOnClose() != null}），不设就没有 ✕；再加上不设 primary actions，
     * 这张卡片<b>一个可交互控件都没有</b>，正是"等待中"要的形态。</p>
     */
    private Message messageCard(String title, String description, FontAwesomeSolid icon) {
        Message msg = new Message(title, description, new FontIcon(icon));
        msg.setPrefWidth(CARD_WIDTH);
        msg.setMaxWidth(CARD_WIDTH);
        return msg;
    }

    private void showModal(Node content) {
        // 有新卡片要顶掉一张"还没被回答的确认卡"时，先按"取消"交付决定：
        // 否则等它的调用方（如提取配额确认的后台线程，非 daemon）会永久阻塞 → 关窗后进程残留。
        if (pendingDecision != null) {
            Consumer<Boolean> stale = pendingDecision;
            pendingDecision = null;
            log.warn(I18n.msg("log.notificationhost.05"));
            stale.accept(false);
        }
        // 限制最大高度：否则 ModalPane 的内容容器会把卡片拉满整窗高度（实测 Notification 400×761，Message 同理）
        if (content instanceof Region region) {
            region.setMaxHeight(Region.USE_PREF_SIZE);
        }
        // ⚠️ 顺序不能颠倒，两步都是实测踩出来的（启动早期就发提醒时必现）：
        // ① 皮肤必须先于 setContent 存在：卡片内容是**皮肤的内容监听器**挂到它自己容器上的；
        //    若先 setContent 再建皮肤，监听器还没注册 → 内容变更事件已经过去 → 内容永远挂不上
        //    （现象：display=true 但高度 0、画面上什么都没有）。
        // ② 皮肤构造时还会自己 hide() 一次（doHide → setDisplay(false)），所以 show() 必须在它之后调用。
        modalPane.applyCss();
        modalPane.setContent(content);
        if (!modalPane.isDisplay()) {
            modalPane.show(content);
        }
    }

    private void hideModal() {
        if (modalPane.isDisplay()) {
            modalPane.hide();
        }
    }

    private void pushToast(Notification ntf) {
        ntf.setOnClose(e -> {
            flash(ntf);
            toastLayer.getChildren().remove(ntf);
        });
        toastLayer.getChildren().add(ntf);
        while (toastLayer.getChildren().size() > MAX_TOASTS) {
            toastLayer.getChildren().remove(0);
        }
    }

    private void flash(Node node) {
        Animations.flash(node).playFromStart();
    }
}
