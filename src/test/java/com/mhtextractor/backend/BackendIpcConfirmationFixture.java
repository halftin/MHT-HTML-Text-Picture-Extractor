package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Test-only lifecycle observation around the production host; no production test switches. */
public final class BackendIpcConfirmationFixture {
    public static void main(String[] arguments) throws Exception {
        System.setProperty("mht.ipc.confirmTimeoutMs", arguments[0]);
        var terminals = new java.util.concurrent.atomic.AtomicInteger();
        try (var session = new IpcSession(new ObjectMapper())) {
            session.port.events().subscribe(event -> {
                if (event instanceof com.mhtextractor.uiport.PortEvent.ExtractionTerminal) terminals.incrementAndGet();
            });
            new IpcServer(session).run(System.in, System.out);
        }
        if (terminals.get() != 1) throw new AssertionError("Backend terminal delivered " + terminals.get() + " times");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        long workers;
        long readers;
        do {
            var threads = Thread.getAllStackTraces().keySet();
            workers = threads.stream().filter(t -> t.isAlive() && !t.isDaemon() && t != Thread.currentThread()).count();
            readers = threads.stream().filter(t -> t.isAlive() && t.getName().equals("mht-ipc-input")).count();
            if (workers == 0 && readers == 0) break;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        if (workers != 0 || readers != 0) throw new AssertionError("Residual IPC threads: " + workers + "/" + readers);
        System.err.println("IPC THREADS CLEAN: nonDaemon=0 inputReaders=0 terminalCount=1");
        // Natural return: System.exit would hide a blocked non-daemon extraction worker.
    }
}
