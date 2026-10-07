package com.mhtextractor.gui.component;

import com.mhtextractor.app.Texts;
import com.mhtextractor.uiport.Confirmer;
import com.mhtextractor.uiport.ConfirmRequest;
import com.mhtextractor.uiport.ConfirmToken;
import javafx.scene.Node;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 端口确认往返的界面实现（B5-1）：把 {@link Confirmer} 的确认请求渲染为应用内居中确认卡
 * （{@link NotificationHost#confirm}），答案经 {@link ConfirmToken} 回传、<b>恰好交付一次</b>
 * （复用 {@link NotificationHost} 的既有语义：卡片被顶替 / 宿主不可用 = 按「取消」交付）。
 *
 * <p><b>线程约定</b>：{@link #confirm} 可来自任意线程（导入后台线程是首个真实消费方 —— 配额卡的
 * 等待方是导入后台线程，不阻塞 FX）；{@link NotificationHost} 内部会把卡片渲染转投 FX 线程。
 * 后端工作线程经 {@link ConfirmToken#await()} 阻塞等待答案。</p>
 *
 * <p><b>按钮与图标口径（与迁移前逐字一致）</b>：按钮文案恒为 {@code notification.ok} /
 * {@code notification.cancel}（迁移前 {@code GuiNotifications.quotaConfirm} 就是这两个固定标签）；
 * 图标按请求键映射 —— 配额卡（{@code import.quotaReached}）用 {@code CLIPBOARD_LIST}
 * （与迁移前同图标），其余用问号默认图标。动作键只在「继续 / 停止」判定时作为答案标识回传，
 * 不参与按钮渲染（若按动作键渲染会改变迁移前的按钮文案，属用户可见变化）。</p>
 */
public final class GuiConfirmer implements Confirmer {

    /** 配额确认卡图标映射键（迁移前 {@code GuiNotifications.quotaConfirm} → {@code CLIPBOARD_LIST}）。 */
    private static final String QUOTA_KEY = "import.quotaReached";

    private final Supplier<Node> anchor;

    /** @param anchor 卡片锚点（渲染时才求值 —— 构造早于控件装配时也可安全注入） */
    public GuiConfirmer(Supplier<Node> anchor) {
        this.anchor = Objects.requireNonNull(anchor, "anchor");
    }

    @Override
    public ConfirmToken confirm(ConfirmRequest request) {
        Objects.requireNonNull(request, "request");
        BlockingToken token = new BlockingToken(request);
        // NotificationHost.confirm 内部处理线程转投（非 FX 线程 → runLater）；顶替/宿主不可用 → false
        NotificationHost.confirm(anchor.get(), messageOf(request), iconOf(request), accepted -> {
            if (accepted) {
                token.answer(request.actionKeys().isEmpty() ? "ok" : request.actionKeys().get(0));
            } else {
                token.cancel();
            }
        });
        return token;
    }

    /** 文案：预渲染 message 直接展示；否则按 key + 具名参数渲染（键 + 参数的口径与 Notice 一致）。 */
    private static String messageOf(ConfirmRequest request) {
        if (request.message() != null) {
            return request.message();
        }
        Map<String, String> params = request.namedParams();
        Object[] args = new Object[params.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> e : params.entrySet()) {
            args[i++] = e.getKey();
            args[i++] = e.getValue();
        }
        return Texts.get(request.key(), args);
    }

    private static FontAwesomeSolid iconOf(ConfirmRequest request) {
        return QUOTA_KEY.equals(request.key()) ? FontAwesomeSolid.CLIPBOARD_LIST
                : FontAwesomeSolid.QUESTION_CIRCLE;
    }

    /**
     * 答案通道：{@link #answer} / {@link #cancel} 由 FX 线程（卡片按钮回调 / 顶替交付）调用，
     * {@link #await} 被后端工作线程阻塞等待；超时兜底（{@code timeoutMs > 0}）由守护线程按「取消」交付。
     */
    private static final class BlockingToken implements ConfirmToken {

        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicBoolean delivered = new AtomicBoolean();
        private volatile String actionKey;

        private BlockingToken(ConfirmRequest request) {
            if (request.timeoutMs() > 0) {
                Thread timer = new Thread(() -> {
                    try {
                        Thread.sleep(request.timeoutMs());
                        cancel();                 // 超时兜底 = 按「取消」交付（恰好一次）
                    } catch (InterruptedException ignored) {
                        // 已交付（answer/cancel）时中断：无需动作
                    }
                }, "confirm-timeout");
                timer.setDaemon(true);
                timer.start();
            }
        }

        @Override
        public void answer(String actionKey) {
            if (delivered.compareAndSet(false, true)) {
                this.actionKey = actionKey;
                latch.countDown();
            }
        }

        @Override
        public void cancel() {
            if (delivered.compareAndSet(false, true)) {
                actionKey = null;
                latch.countDown();
            }
        }

        @Override
        public String await() throws InterruptedException {
            latch.await();
            return actionKey;
        }
    }
}
