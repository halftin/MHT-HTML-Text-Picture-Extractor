package com.mhtextractor.gui.component;

import com.mhtextractor.logging.TextAreaAppender;
import javafx.scene.control.TextArea;

/**
 * 自定义日志 TextArea 控件
 * 注册到 Logback 的 TextAreaAppender
 */
public class LogTextArea extends TextArea {

    public LogTextArea() {
        setEditable(false);
        setWrapText(true);
        setPrefHeight(200);

        // 注册到 Logback Appender
        TextAreaAppender.setLogConsumer(this::appendLog);
    }

    private void appendLog(String message) {
        appendText(message);
        // 自动滚动到底部
        setScrollTop(Double.MAX_VALUE);
    }
}
