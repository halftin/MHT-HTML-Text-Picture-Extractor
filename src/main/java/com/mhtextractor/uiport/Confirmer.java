package com.mhtextractor.uiport;

/**
 * 确认往返（最小切片接缝）。
 *
 * <p><b>方案 §14 决策 11</b>：卡片被断开/顶替 = 按「取消」交付；超时按请求内 {@code timeoutMs} 兜底；
 * 前端渲染卡片，答案经 {@link ConfirmToken} 回传，恰好交付一次
 * （{@code answer} / {@code cancel} / 超时兜底三选一）。</p>
 */
public interface Confirmer {

    /**
     * 发起一次确认往返。
     *
     * @param request 确认请求（文案 = 键 + 具名参数，决策 13）
     * @return 答案通道；后端保证恰好交付一次
     */
    ConfirmToken confirm(ConfirmRequest request);
}
