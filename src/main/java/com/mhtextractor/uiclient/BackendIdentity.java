package com.mhtextractor.uiclient;

import com.mhtextractor.uiclient.BackendIdentity;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Compile-time release identity. It detects mixed builds; it is not an authenticity signature. */
public record BackendIdentity(int protocolVersion, String version, String buildId) {
    public static final int PROTOCOL_VERSION = 2;

    public BackendIdentity {
        if (protocolVersion < 1 || version == null || version.isBlank()
                || buildId == null || buildId.isBlank()) {
            throw new IllegalArgumentException("Nonempty backend version/buildId and positive protocolVersion required");
        }
    }

    public static BackendIdentity current() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = BackendIdentity.class.getResourceAsStream("/backend-build.properties")) {
            if (input == null) throw new IOException("Missing compile-time backend-build.properties");
            properties.load(input);
        }
        String version = properties.getProperty("version", "");
        String buildId = properties.getProperty("buildId", "");
        if (version.contains("${") || buildId.contains("${") || version.isBlank() || buildId.isBlank()) {
            throw new IOException("Unfiltered or empty compile-time backend identity");
        }
        return new BackendIdentity(PROTOCOL_VERSION, version, buildId);
    }

    public String mismatch(BackendIdentity actual) {
        if (protocolVersion != actual.protocolVersion) return "Protocol version mismatch: expected "
                + protocolVersion + ", backend declares " + actual.protocolVersion;
        if (!version.equals(actual.version)) return "Backend version mismatch: expected "
                + version + ", backend declares " + actual.version;
        if (!buildId.equals(actual.buildId)) return "Backend buildId mismatch: expected "
                + buildId + ", backend declares " + actual.buildId;
        return null;
    }
}
