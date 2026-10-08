package com.mhtextractor.baseline;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CLI boundary tests use subprocesses, so malformed config must fail before importing or producing output. */
class BackendConfigTest {
    @Test
    void invalidConfigsFailWithReadableUsageErrors() throws Exception {
        Path root = BackendProcess.evidence("invalid-configs");
        List<String> invalid = List.of("{\"unknownSetting\":true}", "{\"addChapterNumber\":\"true\"}",
                "{\"addChapterNumber\":1}", "{\"addChapterNumber\":null}", "{\"startMarker\":42}",
                "{\"startMarker\":false}", "{\"startMarker\":[]}", "{\"enableTitleExtraction\":true}",
                "{\"outputFolder\":\"somewhere\"}", "{\"inputFolder\":null}", "{\"imageModeEnabled\":true}",
                "{\"openFolderAfterComplete\":true}", "{\"addChapterNumber\":true,\"addChapterNumber\":false}",
                "{} {}", "{", "[]", "null", "\"string\"");
        int index = 0;
        for (String value : invalid) {
            Path work = Files.createDirectories(root.resolve(Integer.toString(++index)));
            Path config = work.resolve("config.json");
            Files.writeString(config, value);
            Path out = work.resolve("out");
            var result = BackendProcess.run(work, List.of("--once", "--config", config.toString(),
                    "--input", work.resolve("missing.mht").toString(), "--output", out.toString()));
            assertEquals(2, result.code(), value + "\n" + result.stderr());
            assertTrue(result.stderr().contains("invalid --config") && result.stderr().contains(config.toString()),
                    "readable reason and config path: " + result.stderr());
            assertFalse(Files.exists(out), "validation must precede filesystem effects");
            System.out.println("[config-invalid] exit=" + result.code() + " JSON=" + value
                    + " reason=" + result.stderr().lines().filter(s -> s.startsWith("error: ")).findFirst().orElse(""));
        }
        var missing = BackendProcess.run(root.resolve("missing"), List.of("--once", "--config",
                root.resolve("missing.json").toString(), "--input", root.toString(),
                "--output", root.resolve("no-output").toString()));
        assertEquals(2, missing.code());
        assertTrue(missing.stderr().contains("invalid --config"));
    }

    @Test
    void explicitFlagsOverrideConfigInEitherOrderAndRetainUnspecifiedFields() throws Exception {
        Path root = BackendProcess.evidence("precedence");
        BaselineCases.Case fixture = BaselineCases.all().stream()
                .filter(c -> c.name().equals("text-template-vars")).findFirst().orElseThrow();
        for (boolean configFirst : List.of(true, false)) {
            Path work = Files.createDirectories(root.resolve(Boolean.toString(configFirst)));
            Path input = work.resolve("in");
            BaselineCases.prepareInput(fixture, input);
            Path config = work.resolve("config.json");
            Files.writeString(config, """
                    {"separateFiles":false,"enableCustomTemplate":false,
                     "startTemplate":"CONFIG_START\\n","endTemplate":"\\nCONFIG_END\\n",
                     "startMarker":null}
                    """);
            List<String> flags = List.of("--separate", "--template-start", "FLAG_START\\n");
            List<String> configFlags = List.of("--config", config.toString());
            List<String> options = new ArrayList<>(List.of("--once", "--locale", "en", "--input",
                    input.toString(), "--output", work.resolve("out").toString()));
            options.addAll(configFirst ? configFlags : flags);
            options.addAll(configFirst ? flags : configFlags);
            var result = BackendProcess.run(work, options);
            assertEquals(0, result.code(), result.stderr());
            assertFalse(Files.exists(work.resolve("out/merged.txt")));
            String text = Files.readString(work.resolve("out/simple-text.txt"));
            assertTrue(text.contains("FLAG_START") && text.contains("CONFIG_END"), text);
            assertFalse(text.contains("CONFIG_START"), text);
        }
    }

    @Test
    void configIsOnceOnlyAndHelpDocumentsItsContract() throws Exception {
        Path root = BackendProcess.evidence("help-serve");
        var serve = BackendProcess.run(root.resolve("serve"), List.of("--serve", "--config", "unused.json"));
        assertEquals(2, serve.code());
        assertTrue(serve.stderr().contains("not supported with --serve"));
        var help = BackendProcess.run(root.resolve("help"), List.of("--help"));
        assertEquals(0, help.code());
        assertTrue(help.stdout().contains("--config <AppConfig.json>"));
        assertTrue(help.stdout().contains("override only corresponding config fields"));
    }
}
