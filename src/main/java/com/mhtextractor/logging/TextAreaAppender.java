package com.mhtextractor.logging;

import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.classic.spi.ILoggingEvent;
import javafx.application.Platform;
import lombok.Setter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Logback 自定义 Appender
 * 将日志事件路由到 JavaFX TextArea，使用 Platform.runLater() 保证线程安全
 */
public class TextAreaAppender extends AppenderBase<ILoggingEvent> {

    /**
     * -- SETTER --
     *  设置日志消费者（由 JavaFX 控件注册）
     */
    @Setter
    private static volatile Consumer<String> logConsumer;

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @Override
    protected void append(ILoggingEvent event) {
        if (logConsumer == null) return;

        String formattedMessage = event.getFormattedMessage();
        String finalMessage;

        // 分隔线不添加时间戳
        if (formattedMessage.startsWith("======") || formattedMessage.matches("^=+\\|*=+$")) {
            finalMessage = formattedMessage + "\n";
        } else {
            String timestamp = TIME_FORMATTER.format(Instant.ofEpochMilli(event.getTimeStamp()));
            finalMessage = timestamp + " " + formattedMessage + "\n";
        }

        // 使用 Platform.runLater() 在 JavaFX 线程更新 UI
        Platform.runLater(() -> {
            Consumer<String> consumer = logConsumer;
            if (consumer != null) {
                consumer.accept(finalMessage);
            }
        });
    }
}
