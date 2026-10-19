package com.mhtextractor.uiclient.ipc;

/** Distinguishes transport/protocol failure from a backend's explicit rejection. */
public final class IpcFailure extends RuntimeException {
    public enum Kind { UNAVAILABLE, DISCONNECTED, PROTOCOL, REMOTE, TIMEOUT, CLOSED }
    private final Kind kind;
    private final String code;

    public IpcFailure(Kind kind, String code, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.code = code;
    }
    public Kind kind() { return kind; }
    public String code() { return code; }
}
