package com.mhtextractor.uicore;

import com.mhtextractor.uiport.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrontendNotificationEventsTest {
    @Test void forwardsNotificationPayloadsWithoutQueriesAndDropsAfterClose() {
        var sink = new AtomicReference<Consumer<PortEvent>>();
        Uiport source = new Uiport() {
            public WorkspaceCommands commands() { throw new AssertionError("Unexpected command"); }
            public WorkspaceQueries queries() { throw new AssertionError("Notification must not query the backend"); }
            public Confirmer confirmer() { throw new AssertionError("Unexpected confirmation"); }
            public PortEvents events() { return listener -> { sink.set(listener); return () -> { }; }; }
        };
        var received = new ArrayList<PortEvent>();
        var pending = new ArrayList<Runnable>();
        try (var frontend = new FrontendPort(source, pending::add, config -> { })) {
            frontend.events().subscribe(received::add);
            var alert = new PortEvent.CacheSizeAlert(1, 11, 10, "root", "cache.sizeAlert", Map.of(), "WARN", 3);
            var log = new PortEvent.BackendLog("ERROR", "key", Map.of("0", "argument"), 100, 4);
            sink.get().accept(alert); sink.get().accept(log);
            assertTrue(received.isEmpty());
            List.copyOf(pending).forEach(Runnable::run); pending.clear();
            assertEquals(List.of(alert, log), received);
            sink.get().accept(alert);
        }
        pending.forEach(Runnable::run);
        assertEquals(2, received.size());
    }
}
