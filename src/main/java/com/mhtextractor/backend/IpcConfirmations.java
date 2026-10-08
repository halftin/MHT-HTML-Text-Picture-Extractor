package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhtextractor.uiport.ConfirmRequest;
import com.mhtextractor.uiport.ConfirmToken;
import com.mhtextractor.uiport.Confirmer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Session-local answers. No localized text or business types cross this boundary. */
final class IpcConfirmations implements Confirmer {
    static final long DEFAULT_TIMEOUT_MS = 30_000;
    private final ObjectMapper json;
    private final Consumer<Runnable> dispatch;
    private final Consumer<ObjectNode> events;
    private final long timeoutMs;
    private final Map<String, Pending> pending = new HashMap<>();
    private final String sessionId = java.util.UUID.randomUUID().toString();
    private long sequence;
    private boolean disconnected;

    IpcConfirmations(ObjectMapper json, Consumer<Runnable> dispatch, Consumer<ObjectNode> events) {
        this.json = json;
        this.dispatch = dispatch;
        this.events = events;
        String configured = System.getProperty("mht.ipc.confirmTimeoutMs", String.valueOf(DEFAULT_TIMEOUT_MS));
        timeoutMs = Long.parseLong(configured);
        if (timeoutMs <= 0) throw new IllegalArgumentException("mht.ipc.confirmTimeoutMs must be positive");
    }

    @Override public ConfirmToken confirm(ConfirmRequest request) {
        Pending token;
        synchronized (this) {
            long limit = request.timeoutMs() > 0 ? Math.min(request.timeoutMs(), timeoutMs) : timeoutMs;
            token = new Pending(sessionId + "-" + ++sequence, request, limit);
            if (disconnected) token.answer.complete(null);
            else pending.put(token.id, token);
        }
        dispatch.accept(() -> {
            synchronized (this) {
                if (!pending.containsKey(token.id)) return;
            }
            ObjectNode data = json.createObjectNode().put("confirmationId", token.id).put("key", request.key())
                    .putNull("defaultAction").put("timeoutMs", token.limit);
            ObjectNode params = data.putObject("namedParams");
            request.namedParams().forEach(params::put);
            var actions = data.putArray("actionKeys");
            request.actionKeys().forEach(actions::add);
            // message() is deliberately never serialized: clients render keys and named parameters.
            events.accept(data);
        });
        return token;
    }

    synchronized boolean answer(String id, String action) {
        Pending token = pending.get(id);
        if (token == null) return false; // Expired / duplicate / old-session answers cannot affect another card.
        if (action != null && !token.request.actionKeys().contains(action)) {
            throw new IllegalArgumentException("Answer must be an offered action or null");
        }
        if (System.nanoTime() - token.started >= TimeUnit.MILLISECONDS.toNanos(token.limit)) {
            complete(token, null);
            return false;
        }
        return complete(token, action);
    }

    private synchronized boolean complete(Pending token, String action) {
        if (!pending.remove(token.id, token)) return false;
        return token.answer.complete(action);
    }

    synchronized void disconnect() {
        disconnected = true;
        pending.values().forEach(token -> token.answer.complete(null));
        pending.clear();
    }

    private final class Pending implements ConfirmToken {
        final String id;
        final ConfirmRequest request;
        final long limit;
        final long started = System.nanoTime();
        final CompletableFuture<String> answer = new CompletableFuture<>();

        Pending(String id, ConfirmRequest request, long limit) {
            this.id = id;
            this.request = request;
            this.limit = limit;
        }

        @Override public void answer(String actionKey) { IpcConfirmations.this.answer(id, actionKey); }
        @Override public void cancel() { complete(this, null); }

        @Override public String await() throws InterruptedException {
            try {
                long remaining = TimeUnit.MILLISECONDS.toNanos(limit) - (System.nanoTime() - started);
                return answer.get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                cancel();
                return answer.join(); // An answer that won the race remains the sole delivered decision.
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            } catch (InterruptedException e) {
                cancel();
                throw e;
            }
        }
    }
}
