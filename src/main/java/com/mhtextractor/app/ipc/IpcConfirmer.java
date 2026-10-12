package com.mhtextractor.app.ipc;

import com.mhtextractor.uiport.*;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Owns frontend token lifetime; replacement, expiry and disconnect all cancel exactly once. */
public final class IpcConfirmer implements Confirmer, AutoCloseable {
    private final Confirmer frontend;
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("ipc-confirm-timeout").daemon(true).factory());
    private Token active;
    private boolean closed;
    public IpcConfirmer(Confirmer frontend) { this.frontend = Objects.requireNonNull(frontend); }

    @Override public synchronized ConfirmToken confirm(ConfirmRequest request) {
        if (closed) throw new IllegalStateException("Confirmer closed");
        if (active != null) active.cancel();
        var token = new Token(request);
        active = token;
        token.delegate = Objects.requireNonNull(frontend.confirm(request), "Frontend confirmation token");
        if (request.timeoutMs() > 0) token.timer = timers.schedule(token::cancel, request.timeoutMs(), TimeUnit.MILLISECONDS);
        Thread.ofVirtual().name("ipc-confirm-answer").start(() -> {
            try {
                String action = token.delegate.await();
                if (action == null) token.cancel(); else token.answer(action);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); token.cancel();
            } catch (RuntimeException e) { token.done.completeExceptionally(e); }
        });
        return token;
    }

    public void request(String id, ConfirmRequest request, Consumer<String> answer, Consumer<Throwable> failure) {
        var token = (Token) confirm(request);
        token.id = id;
        token.done.whenComplete((action,error) -> { if (error == null) answer.accept(action); else failure.accept(error); });
    }
    public synchronized void finish(String id) {
        if (active != null && Objects.equals(active.id,id)) active.cancel();
    }
    @Override public synchronized void close() {
        closed = true;
        if (active != null) active.cancel();
        timers.shutdownNow();
    }

    private final class Token implements ConfirmToken {
        private final ConfirmRequest request;
        private final CompletableFuture<String> done = new CompletableFuture<>();
        private ConfirmToken delegate;
        private ScheduledFuture<?> timer;
        private String id;
        private Token(ConfirmRequest request) { this.request = request; }
        public void answer(String action) {
            if (!request.actionKeys().contains(action)) throw new IllegalArgumentException("Unknown confirmation action: " + action);
            if (done.complete(action)) {
                if (timer != null) timer.cancel(false);
                delegate.answer(action);
            }
        }
        public void cancel() {
            if (done.complete(null)) {
                if (timer != null) timer.cancel(false);
                if (delegate != null) delegate.cancel();
            }
        }
        public String await() throws InterruptedException {
            try { return done.get(); }
            catch (ExecutionException e) { throw new IllegalStateException("Frontend confirmation failed",e.getCause()); }
        }
    }
}
