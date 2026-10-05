package com.mhtextractor.uiport;

/**
 * 确认往返的答案通道（恰好交付一次：{@link #answer} / {@link #cancel} / 超时兜底三选一）。
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
}
