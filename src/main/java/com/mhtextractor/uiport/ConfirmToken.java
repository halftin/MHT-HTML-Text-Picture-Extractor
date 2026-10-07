package com.mhtextractor.uiport;

/**
 * 确认往返的答案通道（恰好交付一次：{@link #answer} / {@link #cancel} / 超时兜底三选一）。
 *
 * <p><b>B5-1 起的两端分工</b>：前端渲染卡片，用户选择后由<b>前端</b>调 {@link #answer} / {@link #cancel}
 * 交付答案；需要确认往返的<b>后端</b>工作线程调 {@link #await} 阻塞等待（方案 §14 决策 11：
 * 断开 / 顶替 = 按「取消」交付，超时按请求内 {@code timeoutMs} 兜底，答案恰好交付一次）。</p>
 */
public interface ConfirmToken {

    /**
     * 交付所选动作键。
     *
     * @param actionKey 必须是 {@link ConfirmRequest#actionKeys()} 之一
     */
    void answer(String actionKey);

    /** 按「取消」交付。 */
    void cancel();

    /**
     * 阻塞等待答案（后端在需要确认往返的工作线程上调用；不得在 UI 线程上调用 —— 卡片渲染需要它）。
     *
     * @return 所选动作键（{@link ConfirmRequest#actionKeys()} 之一）；按「取消」交付时为 {@code null}
     * @throws InterruptedException 等待线程被中断（按「取消」处理；调用方负责恢复中断标志）
     */
    String await() throws InterruptedException;
}
