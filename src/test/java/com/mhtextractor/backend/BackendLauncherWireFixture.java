package com.mhtextractor.backend;

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
        var frame = IpcTestClient.JSON.createObjectNode().put("v", 1).put("type", "response").put("id", "launcher-hello");
        frame.putObject("result").put("kind", "welcome").put("protocolVersion", identity.protocolVersion())
                .put("version", identity.version()).put("buildId", "spoofed-build").put("confirmationRoundTrip", false)
                .putArray("capabilities").add("workspace");
        System.out.println(frame);
        while (input.readLine() != null) { }
    }
}
