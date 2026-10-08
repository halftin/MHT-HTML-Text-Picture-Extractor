package com.mhtextractor.backend;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;

/** Read-ahead only: this daemon never executes a port command or touches the workspace. */
final class IpcInput implements AutoCloseable {
    record Received(String line, IOException failure) { }
    private final ArrayBlockingQueue<Received> lines = new ArrayBlockingQueue<>(256);
    private final Thread reader;
    private volatile Received end;

    IpcInput(InputStream input) {
        reader = new Thread(() -> {
            var stream = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = stream.readLine()) != null) lines.put(new Received(line, null));
                end = new Received(null, null);
                lines.put(end);
            } catch (IOException e) {
                end = new Received(null, e);
                try { lines.put(end); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "mht-ipc-input");
        reader.setDaemon(true); // Borrowed stdin may remain open after a failed stdout write.
        reader.start();
    }

    Received take() throws InterruptedException { return lines.take(); }
    Received poll() { return lines.poll(); }
    Received end() { return end; }

    @Override public void close() { reader.interrupt(); }
}
