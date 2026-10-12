package com.mhtextractor.backend;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cancel our own stalled Windows stdout I/O; CloseHandle and Thread.interrupt can both wait for WriteFile. */
final class IpcWindowsIo {
    private static final AtomicBoolean failureLogged = new AtomicBoolean();

    @FunctionalInterface
    interface Cancellation {
        boolean cancel() throws Throwable;
    }

    private IpcWindowsIo() { }

    static boolean cancelOutput() {
        return bestEffort(() -> cancel(-11));
    }

    static boolean cancelInput() {
        return bestEffort(() -> cancel(-10));
    }

    static boolean bestEffort(Cancellation cancellation) {
        Throwable failure = null;
        try {
            if (cancellation.cancel()) return true;
        } catch (Throwable e) { failure = e; }
        if (failureLogged.compareAndSet(false, true)) {
            // Logging must not be able to break the interrupt/join fallback either.
            try {
                org.slf4j.LoggerFactory.getLogger(IpcWindowsIo.class).warn(
                        "Windows stdio cancellation unavailable; continuing bounded shutdown", failure);
            } catch (Throwable ignored) { }
        }
        return false;
    }

    private static boolean cancel(int standardHandle) throws Throwable {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return false;
        try (var arena = Arena.ofConfined()) {
            var kernel = SymbolLookup.libraryLookup("Kernel32.dll", arena);
            var linker = Linker.nativeLinker();
            var getStdHandle = linker.downcallHandle(kernel.find("GetStdHandle").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            var cancelIo = linker.downcallHandle(kernel.find("CancelIoEx").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MemorySegment stream = (MemorySegment) getStdHandle.invokeExact(standardHandle);
            // FALSE may mean the write just completed (ERROR_NOT_FOUND). The writer's abort flag
            // prevents another write; its bounded join remains the authority for actual termination.
            int requested = (int) cancelIo.invokeExact(stream, MemorySegment.NULL);
            return requested != 0;
        }
    }
}
