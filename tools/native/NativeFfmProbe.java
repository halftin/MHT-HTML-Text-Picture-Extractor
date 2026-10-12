import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;

/** Same two downcalls as IpcWindowsIo; FALSE is valid when no I/O is pending. */
public final class NativeFfmProbe {
    public static void main(String[] args) {
        try (var arena = Arena.ofConfined()) {
            var kernel = SymbolLookup.libraryLookup("Kernel32.dll", arena);
            var linker = Linker.nativeLinker();
            var get = linker.downcallHandle(kernel.find("GetStdHandle").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            var cancel = linker.downcallHandle(kernel.find("CancelIoEx").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            for (int standardHandle : new int[] {-11, -10}) {
                MemorySegment stream = (MemorySegment) get.invokeExact(standardHandle);
                int requested = (int) cancel.invokeExact(stream, MemorySegment.NULL);
                System.out.println("FFM_OK standardHandle=" + standardHandle
                        + " handle=" + stream.address() + " cancelResult=" + requested);
            }
        } catch (Throwable failure) {
            System.out.println("FFM_FAIL " + failure);
            System.exit(1);
        }
    }
}
