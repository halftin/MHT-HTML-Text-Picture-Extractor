package com.mhtextractor.uicore;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GuiStateCompatibilityTest {
    @TempDir Path dir;

    @Test
    void existingDiskStateReadsAndWritesByteForByte() throws Exception {
        byte[] original;
        try (var in = getClass().getResourceAsStream("/gui-state-existing.json")) {
            assertNotNull(in);
            original = in.readAllBytes();
        }
        Files.write(dir.resolve("gui-state.json"), original);
        GuiState state = GuiStateManager.load(dir.toFile());
        assertNotNull(state);
        var json = new ObjectMapper();
        assertEquals(json.readTree(original), json.valueToTree(state));
        GuiStateManager.save(state, dir.toFile());
        assertArrayEquals(original, Files.readAllBytes(dir.resolve("gui-state.json")));
        String capture = System.getProperty("p5a8.capture");
        if (capture != null) {
            Path output = Path.of(capture);
            Files.createDirectories(output);
            Files.copy(dir.resolve("gui-state.json"), output.resolve("same-state.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        state.setExtractCheckedOnly(!state.isExtractCheckedOnly());
        state.setLocale("en_US");
        GuiStateManager.save(state, dir.toFile());
        byte[] changed = Files.readAllBytes(dir.resolve("gui-state.json"));
        assertEquals(state, GuiStateManager.load(dir.toFile()));
        assertArrayEquals(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(state), changed);
        var keys = new java.util.ArrayList<String>();
        json.readTree(changed).fieldNames().forEachRemaining(keys::add);
        assertEquals(List.of("imageModeEnabled", "inputFolder", "outputFolder", "openFolderAfterComplete", "extractCheckedOnly", "locale"), keys);
        try (var files = Files.list(dir)) { assertEquals(1, files.count()); }
        if (capture != null) Files.write(Path.of(capture).resolve("changed-state.json"), changed);
        System.out.println("GUI_STATE_SHA256=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
    }
}
