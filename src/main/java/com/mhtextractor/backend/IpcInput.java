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
    private final InputStream input;
    private final boolean standardInput;
    private volatile Received end;

    IpcInput(InputStream input) throws IOException {
        this.input = input;
        standardInput = input == System.in || (input instanceof java.io.FileInputStream file && file.getFD() == java.io.FileDescriptor.in);
        reader = new Thread(() -> {
            InputStream readable = input instanceof java.io.FileInputStream file
                    ? java.nio.channels.Channels.newInputStream(file.getChannel()) : input;
            var stream = new BufferedReader(new InputStreamReader(readable, StandardCharsets.UTF_8));
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
    Received poll(long millis) throws InterruptedException {
        return lines.poll(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    Received end() { return end; }

    @Override public void close() throws IOException {
        if (end == null && reader.isAlive() && standardInput) IpcWindowsIo.cancelInput();
        reader.interrupt();
        input.close(); // Unblock a read when output fails while the peer still has stdin open.
        try {
            reader.join(2000);
            if (reader.isAlive()) throw new IOException("IPC input reader did not stop");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("IPC reader join interrupted", e);
        }
    }
}
