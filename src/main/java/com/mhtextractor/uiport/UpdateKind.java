package com.mhtextractor.uiport;

/**
 * 列表条目更新类别（列表动画用）：无动画 / 出现 / 消失。
 *
 * <p>后端在视图模型快照里给出每条的动画类别，前端据此播放出现/消失动画。</p>
 */
public enum UpdateKind {
    NONE,
    APPEARED,
    DISAPPEARED
}
