package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mhtextractor.i18n.I18n;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import org.slf4j.LoggerFactory;

/** Test-only stimuli; transport, hook, logging appender and teardown are production implementations. */
public final class BackendEventsWireFixture {
    public static void main(String[] args) throws Exception {
        System.setProperty("logback.configurationFile", "logback-headless.xml");
        I18n.setLocale(Locale.US);
        try (var session = new IpcSession(new ObjectMapper())) {
            var cache = Files.createDirectories(session.files().root.resolve("preview-cache"));
            try (var file = java.nio.channels.FileChannel.open(cache.resolve("pad"),
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.SPARSE)) {
                file.position(10L * 1024 * 1024 * 1024 - 1);
                file.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
            }
            var logger = LoggerFactory.getLogger("p5a7.wire");
            var output = new OutputStream() {
                boolean injected;
                public void write(int value) { System.out.write(value); }
                public void write(byte[] bytes, int offset, int length) {
                    System.out.write(bytes, offset, length); System.out.flush();
                    if (!injected && new String(bytes, offset, length, StandardCharsets.UTF_8).contains("\"kind\":\"welcome\"")) {
                        injected = true;
                        logger.info(I18n.msg("log.previewcachelifecycle.04", "INFO_SENTINEL"));
                        logger.warn(I18n.msg("log.previewcachelifecycle.04", "WARN_SENTINEL"));
                        logger.error(I18n.msg("log.previewcachelifecycle.04", "ERROR_SENTINEL"));
                    }
                }
            };
            new IpcServer(session).run(System.in, output);
        }
    }
}
