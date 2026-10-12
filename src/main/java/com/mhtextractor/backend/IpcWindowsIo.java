package com.mhtextractor.backend;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;

/** Cancel our own stalled Windows stdout I/O; CloseHandle and Thread.interrupt can both wait for WriteFile. */
final class IpcWindowsIo {
    private IpcWindowsIo() { }

    static void cancelOutput() throws IOException {
        cancel(-11);
    }

    static void cancelInput() throws IOException {
        cancel(-10);
    }

    private static void cancel(int standardHandle) throws IOException {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return;
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
        } catch (Throwable e) { throw new IOException("Cannot cancel Windows stdio", e); }
    }
}
