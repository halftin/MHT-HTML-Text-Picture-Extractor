package com.mhtextractor.uiport;

/**
 * 提醒级别（前端据此选择呈现形式）。
 *
 * <p>语义与既有三层提醒一致：错误 = 居中卡片必须确认；警告 / 信息 = 右上 toast。</p>
 */
public enum NoticeLevel {
    ERROR,
    WARNING,
    INFO
}
