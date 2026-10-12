package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mhtextractor.uiport.PortEvent;

import java.nio.file.Files;
import java.nio.file.Path;

/** Independent observation of the real InProcessUiport before comparing its values to IPC files. */
public final class BackendIpcExtractionFixture {
    public static void main(String[] arguments) throws Exception {
        com.mhtextractor.i18n.I18n.setLocale(java.util.Locale.US);
        var json = new ObjectMapper();
        var observed = json.createArrayNode();
        try (var session = new IpcSession(json)) {
            session.port.events().subscribe(event -> {
                if (!(event instanceof PortEvent.ExtractionChannelEvent) && !(event instanceof PortEvent.RunningChanged)) return;
                var entry = json.createObjectNode().put("event", event.getClass().getSimpleName());
                entry.set("data", json.valueToTree(event));
                observed.add(entry);
                try { Files.write(Path.of("direct-events.json"), json.writeValueAsBytes(observed)); }
                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            });
            new IpcServer(session).run(System.in, System.out);
        }
    }
}
