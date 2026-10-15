import com.mhtextractor.uicore.GuiState;
import com.mhtextractor.uicore.GuiStateManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Native regression for the relocated GUI state Jackson registrations. */
public final class NativeGuiStateProbe {
    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable failure) {
            System.err.println("GUI_STATE_ASSERTION_FAILED");
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        Path stateFile = Path.of("logs", "gui-state.json");
        if (!Files.isRegularFile(stateFile)) throw new AssertionError("Existing fixture missing");
        // Only the production public API may read or write the state.
        GuiState state = GuiStateManager.load();
        if (state == null || !state.isImageModeEnabled() || !state.isExtractCheckedOnly()
                || state.isOpenFolderAfterComplete() || !"en_US".equals(state.getLocale())
                || !"input-\u4e2d\u6587".equals(state.getInputFolder())
                || !"output-\u4e2d\u6587".equals(state.getOutputFolder())) {
            throw new AssertionError("Loaded fields differ");
        }
        System.out.println("[PASS] native load existing logs/gui-state.json: 6 fields");
        state.setImageModeEnabled(false);
        state.setInputFolder("saved-\u4e2d\u6587");
        state.setOutputFolder("saved-output");
        state.setOpenFolderAfterComplete(true);
        state.setExtractCheckedOnly(false);
        state.setLocale("zh_CN");
        GuiStateManager.save(state);
        // Independent literal oracle, including Jackson's CRLF pretty printing and UTF-8.
        byte[] expected = String.join("\r\n", "{",
                "  \"imageModeEnabled\" : false,",
                "  \"inputFolder\" : \"saved-\u4e2d\u6587\",",
                "  \"outputFolder\" : \"saved-output\",",
                "  \"openFolderAfterComplete\" : true,",
                "  \"extractCheckedOnly\" : false,",
                "  \"locale\" : \"zh_CN\"",
                "}").getBytes(StandardCharsets.UTF_8);
        byte[] actual = Files.readAllBytes(stateFile);
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError("Saved UTF-8 bytes differ: expected=" + expected.length
                    + " actual=" + actual.length + "\n" + new String(actual, StandardCharsets.UTF_8));
        }
        System.out.println("[PASS] native save exact UTF-8 bytes=" + actual.length);
        GuiState restored = GuiStateManager.load();
        if (!state.equals(restored)) throw new AssertionError("Saved state reload differs");
        System.out.println("[PASS] native reload saved fields");
        System.out.println("GUI_STATE_OK");
    }
}
