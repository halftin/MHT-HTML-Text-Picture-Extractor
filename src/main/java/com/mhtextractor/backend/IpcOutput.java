package com.mhtextractor.backend;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Exactly one writer. A stalled peer cannot indefinitely block the model/input host. */
final class IpcOutput implements AutoCloseable {
    private final ArrayBlockingQueue<String> frames = new ArrayBlockingQueue<>(256);
    private final Thread writer;
    private volatile IOException failure;
    private volatile boolean stopping;
    private volatile boolean aborting;
    private volatile long writingSince;
    private final IpcWindowsIo.Cancellation cancellation;

    IpcOutput(OutputStream output) throws IOException {
        this(output, output == System.out || (output instanceof java.io.FileOutputStream file && file.getFD() == java.io.FileDescriptor.out)
                ? IpcWindowsIo::cancelOutput : () -> false);
    }

    IpcOutput(OutputStream output, IpcWindowsIo.Cancellation cancellation) {
        this.cancellation = cancellation;
        writer = new Thread(() -> {
            var stream = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            try {
                while (!aborting && (!stopping || !frames.isEmpty())) {
                    String frame = frames.poll(25, TimeUnit.MILLISECONDS);
                    if (frame == null) continue;
                    writingSince = System.nanoTime();
                    stream.write(frame);
                    stream.write('\n');
                    stream.flush();
                    if (output instanceof java.io.PrintStream print && print.checkError()) throw new IOException("IPC stdout failed");
                    writingSince = 0;
                }
            } catch (IOException e) { failure = e; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "mht-ipc-output");
        writer.setDaemon(true);
        writer.start();
    }

    void check() throws IOException {
        if (failure != null) throw failure;
        long since = writingSince;
        if (since != 0 && System.nanoTime() - since > TimeUnit.SECONDS.toNanos(3))
            throw new IOException("IPC output stalled for 3 seconds");
    }

    void write(String frame) throws IOException {
        check();
        try {
            if (!frames.offer(frame, 3, TimeUnit.SECONDS)) throw new IOException("IPC output queue exhausted");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("IPC output interrupted", e);
        }
    }

    @Override public void close() throws IOException {
        stopping = true;
        try {
            writer.join(3500);
            if (writer.isAlive()) {
                aborting = true;
                IpcWindowsIo.bestEffort(cancellation);
                writer.interrupt();
                writer.join(1000);
                if (writer.isAlive()) throw new IOException("IPC output worker did not stop after cancellation");
                throw new IOException("IPC output timed out; pending I/O cancelled");
            }
            check();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("IPC writer join interrupted", e);
        }
    }
}
