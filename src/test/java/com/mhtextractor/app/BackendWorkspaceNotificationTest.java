package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uiport.PortEvent;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BackendWorkspaceNotificationTest {
    @TempDir Path work;

    @Test void inProcessTransportRegistersTheSameCacheEventAndCleansExportSources() throws Exception {
        var runtime = new AppConfig();
        runtime.setImageModeEnabled(true);
        runtime.setEnableRegionExtraction(false);
        Path source = Files.writeString(work.resolve("input.html"), "<html><body>local</body></html>");
        Path root;
        try (var workspace = new BackendWorkspace(request -> { throw new AssertionError("Unexpected confirmation"); },
                () -> runtime, Runnable::run)) {
            var port = workspace.port();
            port.commands().setGlobalParams(true, runtime);
            var imported = new CountDownLatch(1);
            var measured = new CountDownLatch(1);
            var alert = new AtomicReference<PortEvent.CacheSizeAlert>();
            port.events().subscribe(event -> {
                if (event instanceof PortEvent.ImportFinished) imported.countDown();
                if (event instanceof PortEvent.CacheSizeAlert value) { alert.set(value); measured.countDown(); }
            });
            root = Path.of(port.queries().cacheState().root()); Files.createDirectories(root);
            try (var file = FileChannel.open(root.resolve("pad"), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
                file.position(10L * 1024 * 1024 * 1024 - 1); file.write(ByteBuffer.wrap(new byte[]{0}));
            }
            port.commands().importPaths(List.of(source.toString()), "drag", false);
            assertTrue(imported.await(5, TimeUnit.SECONDS));
            port.commands().loadPreview(source.toString(), true);
            assertTrue(measured.await(5, TimeUnit.SECONDS));
            assertEquals("cache.sizeAlert", alert.get().key()); assertEquals("WARN", alert.get().level());
            assertTrue(alert.get().actualBytes() >= 10L * 1024 * 1024 * 1024);
            assertEquals(1, alert.get().revision()); assertEquals(root.toString(), alert.get().root());
            var exported = port.queries().exportSource(source.toString());
            assertEquals("success", exported.status()); assertTrue(Path.of(exported.htmlFile().path()).startsWith(root));
        }
        try (var entries = Files.list(root)) { assertEquals(0, entries.count()); }
    }
}
