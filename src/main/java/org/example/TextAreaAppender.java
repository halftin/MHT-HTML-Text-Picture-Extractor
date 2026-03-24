package org.example;

import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.classic.spi.ILoggingEvent;
import javax.swing.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class TextAreaAppender extends AppenderBase<ILoggingEvent> {
    private static JTextArea textArea;
    // 线程安全的日期时间格式化器
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public static void setTextArea(JTextArea area) {
        textArea = area;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (textArea != null) {
            String formattedMessage = event.getFormattedMessage();
            String finalMessage;
            if (formattedMessage.startsWith("======") || formattedMessage.matches("^=+\\|*=+$")) {
                finalMessage = formattedMessage + "\n";
            } else {
                String timestamp = TIME_FORMATTER.format(Instant.ofEpochMilli(event.getTimeStamp()));
                finalMessage = timestamp + " " + formattedMessage + "\n";
            }
            SwingUtilities.invokeLater(() -> {
                if (textArea != null) {
                    textArea.append(finalMessage);
                    textArea.setCaretPosition(textArea.getDocument().getLength());
                }
            });
        }
    }
}