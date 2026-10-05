package com.mhtextractor.uiport;

import java.util.function.Consumer;

/**
 * 工作区变更事件流（最小切片）。
 *
 * <p>事件种类见 {@link PortEvent}：结构化变更（{@link PortEvent.WorkspaceChanged}）、
 * 运行状态（{@link PortEvent.RunningChanged}）、提醒（{@link PortEvent.Notice}）。</p>
 */
public interface PortEvents {

    /**
     * 订阅事件；返回句柄 {@code close()} 即退订。
     *
     * @param listener 同步回调（后端保证按序送达）
     * @return 退订句柄（{@link AutoCloseable#close()}）
     */
    AutoCloseable subscribe(Consumer<PortEvent> listener);
}
