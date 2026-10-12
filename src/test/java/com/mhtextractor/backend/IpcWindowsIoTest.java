package com.mhtextractor.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class IpcWindowsIoTest {
    static Stream<Throwable> cancellationFailures() {
        return Stream.of(new LinkageError("MissingForeignRegistrationError: Cannot perform downcall"),
                new IllegalCallerException("native access disabled"),
                new java.util.NoSuchElementException("GetStdHandle"), new IOException("cancel failed"));
    }

    @ParameterizedTest @MethodSource("cancellationFailures")
    void cancellationFailureNeverEscapes(Throwable failure) {
        assertFalse(assertDoesNotThrow(() -> IpcWindowsIo.bestEffort(() -> { throw failure; })));
    }

    @Test void cancellationResultIsPreserved() {
        assertFalse(assertDoesNotThrow(() -> IpcWindowsIo.bestEffort(() -> false)));
        assertTrue(assertDoesNotThrow(() -> IpcWindowsIo.bestEffort(() -> true)));
    }

    @Test @ResourceLock("java.lang.System.properties")
    void bothEntrypointsAreNonThrowingWhenPlatformIsUnavailable() {
        String previous = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Unavailable platform");
            assertFalse(assertDoesNotThrow(IpcWindowsIo::cancelOutput));
            assertFalse(assertDoesNotThrow(IpcWindowsIo::cancelInput));
        } finally {
            if (previous == null) System.clearProperty("os.name");
            else System.setProperty("os.name", previous);
        }
    }

    @Test void unavailableCancellationStillInterruptsAndJoinsStalledWriter() throws Exception {
        assertBoundedWriter(() -> false);
    }

    @Test void throwingCancellationStillInterruptsAndJoinsStalledWriter() throws Exception {
        assertBoundedWriter(() -> { throw new LinkageError("MissingForeignRegistrationError"); });
    }

    private static void assertBoundedWriter(IpcWindowsIo.Cancellation cancellation) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var interrupts = new AtomicInteger();
        var attempts = new AtomicInteger();
        var output = new IpcOutput(new OutputStream() {
            @Override public void write(int value) {
                entered.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); }
                    catch (InterruptedException e) { interrupts.incrementAndGet(); }
                }
                finished.countDown();
            }
        }, () -> { attempts.incrementAndGet(); return cancellation.cancel(); });
        try {
            output.write("stalled");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long start = System.nanoTime();
            IOException failure = assertThrows(IOException.class, output::close);
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals("IPC output worker did not stop after cancellation", failure.getMessage());
            assertEquals(1, attempts.get());
            assertEquals(1, interrupts.get(), "Fallback must interrupt the writer");
            assertTrue(millis >= 4400 && millis < 8000, "Both bounded joins must run: " + millis);
        } finally {
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertDoesNotThrow(output::close);
        }
    }

    @Test void throwingCancellationStillInterruptsAndJoinsStalledReader() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var interrupts = new AtomicInteger();
        var attempts = new AtomicInteger();
        var input = new IpcInput(new InputStream() {
            @Override public int read() {
                entered.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); }
                    catch (InterruptedException e) { interrupts.incrementAndGet(); }
                }
                finished.countDown();
                return -1;
            }
        }, () -> { attempts.incrementAndGet(); throw new IllegalCallerException("unavailable"); });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long start = System.nanoTime();
            IOException failure = assertThrows(IOException.class, input::close);
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals("IPC input reader did not stop", failure.getMessage());
            assertEquals(1, attempts.get());
            assertEquals(1, interrupts.get());
            assertTrue(millis >= 1900 && millis < 5000, "Reader join must run: " + millis);
        } finally {
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertDoesNotThrow(input::close);
        }
    }
}
