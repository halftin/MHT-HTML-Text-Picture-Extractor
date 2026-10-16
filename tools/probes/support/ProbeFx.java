import com.mhtextractor.uicore.FrontendPort;
import javafx.application.Platform;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Bounded waits that keep port results and close callbacks moving on the FX thread. */
final class ProbeFx {
    private ProbeFx() { }

    static void pumpFx(long ms) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
        do {
            Object key = new Object();
            Platform.runLater(() -> Platform.exitNestedEventLoop(key, null));
            Platform.enterNestedEventLoop(key);
        } while (System.nanoTime() < deadline);
    }

    static boolean await(BooleanSupplier ready, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) pumpFx(10);
        return ready.getAsBoolean();
    }

    static void awaitCommands(FrontendPort port) {
        var done = new CompletableFuture<Void>();
        port.execute(ignored -> null, ignored -> done.complete(null));
        if (!await(done::isDone, 3000)) throw new AssertionError("Port result not delivered within 3 seconds");
    }
}
