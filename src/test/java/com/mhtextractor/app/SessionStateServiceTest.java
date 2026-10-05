package com.mhtextractor.app;

import com.mhtextractor.config.GuiState;
import com.mhtextractor.config.GuiStateManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionStateService} 无头单测（P3-3b-5）：Session ↔ {@link GuiState} 字段映射、
 * save 的写入顺序（经 {@code GuiStateManager} 落盘后逐字段对账）与 null 语义。
 * 生产路径即 logs/gui-state.json，测试前后保存/恢复原文件，不留测试痕迹。
 */
class SessionStateServiceTest {

    /** 与生产同一持久化路径（logs/gui-state.json，相对工作目录） */
    private static final Path STATE_FILE = Path.of("logs", "gui-state.json");
    private static byte[] original;

    @BeforeAll
    static void snapshotExistingState() throws IOException {
        if (Files.exists(STATE_FILE)) {
            original = Files.readAllBytes(STATE_FILE);
        }
        Files.deleteIfExists(STATE_FILE);
    }

    @AfterAll
    static void restoreExistingState() throws IOException {
        Files.deleteIfExists(STATE_FILE);
        if (original != null) {
            Files.write(STATE_FILE, original);
        }
    }

    @Test
    void missingFileLoadsNullThenSaveRoundTripsAllFields() throws IOException {
        Files.deleteIfExists(STATE_FILE);
        // 无状态文件 → null（与 GuiStateManager.load 的缺失/失败短路一致）
        assertNull(SessionStateService.load());

        SessionStateService.Session session = new SessionStateService.Session(
                "H:\\TEST\\mht\\in", "H:\\TEST\\mht\\out", true, true, false, "zh_CN");
        SessionStateService.save(session);

        // 落盘产物逐字段对账（GuiState 各字段都被写入且未串位）
        GuiState gs = GuiStateManager.load();
        assertNotNull(gs);
        assertEquals("H:\\TEST\\mht\\in", gs.getInputFolder());
        assertEquals("H:\\TEST\\mht\\out", gs.getOutputFolder());
        assertTrue(gs.isOpenFolderAfterComplete());
        assertTrue(gs.isExtractCheckedOnly(), "extractCheckedOnly 必须原样写入（控制组哨兵字段）");
        assertFalse(gs.isImageModeEnabled());
        assertEquals("zh_CN", gs.getLocale());

        // load 往返：六字段全等（record 结构相等）
        assertEquals(session, SessionStateService.load());
    }

    @Test
    void loadMapsNullFoldersVerbatim() throws IOException {
        Files.deleteIfExists(STATE_FILE);
        SessionStateService.Session session = new SessionStateService.Session(
                null, "", false, false, true, "en_US");
        SessionStateService.save(session);
        assertEquals(session, SessionStateService.load());
    }
}
