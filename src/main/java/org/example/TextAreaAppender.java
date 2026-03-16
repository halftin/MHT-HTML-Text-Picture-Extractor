package org.example;

import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.classic.spi.ILoggingEvent;
import javax.swing.*;

public class TextAreaAppender extends AppenderBase<ILoggingEvent> {
    private static JTextArea textArea;

    public static void setTextArea(JTextArea area) {
        textArea = area;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (textArea != null) {
            String message = new String(event.getFormattedMessage() + "\n");
            SwingUtilities.invokeLater(() -> {
                textArea.append(message);
                textArea.setCaretPosition(textArea.getDocument().getLength());
            });
        }
    }
}