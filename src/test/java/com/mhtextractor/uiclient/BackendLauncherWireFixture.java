package com.mhtextractor.uiclient;

import com.mhtextractor.uiclient.BackendIdentity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Adversarial wire peer for launcher validation, with no hooks in the production CLI. */
public final class BackendLauncherWireFixture {
    public static void main(String[] arguments) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        if (arguments[0].equals("stall")) {
            input.readLine();
            return;
        }
        System.out.println("{\"v\":1,\"type\":\"event\",\"event\":\"ready\",\"data\":{\"methods\":[\"hello\"]}}");
        input.readLine();
        BackendIdentity identity = BackendIdentity.current();
        var frame = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("v", 1).put("type", "response").put("id", "launcher-hello");
        frame.putObject("result").put("kind", "welcome").put("protocolVersion",
                        arguments[0].equals("protocol") ? identity.protocolVersion() + 1 : identity.protocolVersion())
                .put("version", identity.version()).put("buildId", arguments[0].equals("spoof") ? "spoofed-build" : identity.buildId())
                .put("confirmationRoundTrip", arguments[0].equals("capabilities"))
                .putArray("capabilities").add("workspace");
        System.out.println(frame);
        while (input.readLine() != null) { }
    }
}
