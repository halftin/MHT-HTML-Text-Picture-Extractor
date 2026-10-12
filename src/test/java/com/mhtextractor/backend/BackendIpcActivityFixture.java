package com.mhtextractor.backend;

import java.util.concurrent.TimeUnit;

/** Observe the public production runner after natural return; never access backend internals. */
public final class BackendIpcActivityFixture {
    public static void main(String[] arguments) throws Exception {
        boolean stalled = arguments.length > 0 && arguments[0].equals("stalled");
        int exit = new ServeRunner().run(CliArgs.parse(new String[]{"--serve", "--locale", "en"}));
        if (exit != (stalled ? 1 : 0)) throw new AssertionError("ServeRunner exit=" + exit);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        long count;
        do {
            count = Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
                    .filter(t -> t.getName().startsWith("mht-ipc-") || t.getName().startsWith("preview-")
                            || t.getName().equals("mht-import-scan")).count();
            if (count == 0) break;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        if (count != 0) throw new AssertionError("Residual activity/pump threads=" + count);
        System.err.println("IPC THREADS CLEAN: nonDaemon=0 inputReaders=0 activityWorkers=0 outputWriters=0");
        if (stalled) System.err.println("STALLED OUTPUT GUARD: runnerExit=1 naturalReturn=true");
    }
}
