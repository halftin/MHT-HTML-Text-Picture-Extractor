package com.mhtextractor.uiport;

/**
 * 端口层门面：后端实现本接口，前端只经它访问工作区。
 *
 * <p>四条通道：命令（{@link #commands()}）、查询（{@link #queries()}）、事件（{@link #events()}）、
 * 确认往返（{@link #confirmer()}）。</p>
 */
public interface Uiport {

    /** 工作区命令（撤销 / 清场 / 勾选 / 取消在途活动）。 */
    WorkspaceCommands commands();

    /** 工作区查询（视图模型快照）。 */
    WorkspaceQueries queries();

    /** 变更事件流（订阅句柄 {@code close()} 即退订）。 */
    PortEvents events();

    /** 确认往返（卡片断开/顶替 = 取消交付，方案 §14 决策 11）。 */
    Confirmer confirmer();
}
