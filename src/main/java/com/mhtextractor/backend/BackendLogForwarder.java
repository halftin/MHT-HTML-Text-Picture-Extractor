package com.mhtextractor.backend;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.mhtextractor.i18n.LogMessage;
import com.mhtextractor.uiport.PortEvent;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

/** Session-owned synchronous appender. No appender lock is held while the callback pump applies backpressure. */
final class BackendLogForwarder extends UnsynchronizedAppenderBase<ILoggingEvent> implements AutoCloseable {
    private final Consumer<PortEvent.BackendLog> sink;
    private final ch.qos.logback.classic.Logger root;

    BackendLogForwarder(Consumer<PortEvent.BackendLog> sink) {
        this.sink = sink;
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        setContext(context);
        setName("IPC_WARN_ERROR");
        root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        start();
        root.addAppender(this);
    }

    @Override protected void append(ILoggingEvent event) {
        var message = LogMessage.take(event.getMessage());
        if (event.getLevel() != Level.WARN && event.getLevel() != Level.ERROR) return;
        String key = message == null ? "log.backend.unstructured" : message.key();
        Map<String, String> params = message == null ? Map.of("0", event.getLoggerName(),
                "1", event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getClassName())
                : message.namedParams();
        sink.accept(new PortEvent.BackendLog(event.getLevel().toString(), key, params, event.getTimeStamp(), 0));
    }

    @Override public void close() { root.detachAppender(this); stop(); }
}
