package com.mhtextractor.engine;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.PreviewItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OutputNameService} 无头单测（P3-3b）：文本模式文件名 / 图片模式目录名的名字本体计算，
 * 覆盖 separate/merged 两路与「解析中 / 有标题 / 回落」三分支。
 */
class OutputNameServiceTest {

    /** 就绪态条目（cachedTitle 未设置，由各用例按需赋值） */
    private static PreviewItem item(String name) {
        PreviewItem it = new PreviewItem("C:/x/" + name, name, LocalDateTime.now(), "C:/x");
        it.status = PreviewItem.Status.READY;
        return it;
    }

    // ==================== 文本模式 ====================

    @Test
    void textSeparateStripsKnownExtensionAndAppendsTxt() {
        AppConfig params = new AppConfig();
        params.setSeparateFiles(true);
        assertEquals("a.txt", OutputNameService.textOutputName(item("a.mht"), params, "C:/in").finalFileName());
    }

    @Test
    void textMergedUsesInputFolderName() {
        AppConfig params = new AppConfig();
        params.setSeparateFiles(false);
        assertEquals("in.txt", OutputNameService.textOutputName(item("a.mht"), params, "C:/in").finalFileName());
    }

    @Test
    void textMergedFallsBackToMhtOutputWhenInputFolderNameEmpty() {
        AppConfig params = new AppConfig();
        params.setSeparateFiles(false);
        assertEquals("mht_output.txt", OutputNameService.textOutputName(item("a.mht"), params, "").finalFileName());
        // 以分隔符结尾的路径，getName() 为空 → 同样回落
        assertEquals("mht_output.txt", OutputNameService.textOutputName(item("a.mht"), params, "C:/").finalFileName());
    }

    @Test
    void textSeparateDiffersFromMerged() {
        AppConfig separate = new AppConfig();
        separate.setSeparateFiles(true);
        AppConfig merged = new AppConfig();
        merged.setSeparateFiles(false);
        String s = OutputNameService.textOutputName(item("a.mht"), separate, "C:/in").finalFileName();
        String m = OutputNameService.textOutputName(item("a.mht"), merged, "C:/in").finalFileName();
        assertEquals("a.txt", s);
        assertEquals("in.txt", m);
        assertNotEquals(s, m);
    }

    // ==================== 图片模式 · separate ====================

    @Test
    void imageSeparateFallsBackToStrippedNameWhenNameExtractionOff() {
        AppConfig params = new AppConfig(); // singleOutputMode=false, enableNameExtraction=false
        OutputNameService.Name n = OutputNameService.imageOutputName(item("b.mht"), params, null, "C:/in");
        assertFalse(n.parsing());
        assertEquals("b", n.value());
    }

    @Test
    void imageSeparateParsingWhenWaiting() {
        AppConfig params = new AppConfig();
        params.setEnableNameExtraction(true);
        PreviewItem it = item("b.mht");
        it.status = PreviewItem.Status.WAITING;
        OutputNameService.Name n = OutputNameService.imageOutputName(it, params, null, "C:/in");
        assertTrue(n.parsing());
        assertNull(n.value());
    }

    @Test
    void imageSeparateUsesSanitizedTitle() {
        AppConfig params = new AppConfig();
        params.setEnableNameExtraction(true);
        PreviewItem it = item("b.mht");
        it.cachedTitle = "My:Title/A";
        OutputNameService.Name n = OutputNameService.imageOutputName(it, params, null, "C:/in");
        assertFalse(n.parsing());
        assertEquals("My_Title_A", n.value());
    }

    @Test
    void imageSeparateFallsBackToStrippedNameWhenTitleMissing() {
        AppConfig params = new AppConfig();
        params.setEnableNameExtraction(true);
        // READY 且 cachedTitle == null → 回落 stripKnownExtension
        OutputNameService.Name n = OutputNameService.imageOutputName(item("b.mht"), params, null, "C:/in");
        assertFalse(n.parsing());
        assertEquals("b", n.value());
    }

    // ==================== 图片模式 · merged（singleOutput） ====================

    @Test
    void imageMergedUsesFirstVisibleTitle() {
        AppConfig params = new AppConfig();
        params.setSingleOutputMode(true);
        params.setEnableNameExtraction(true);
        PreviewItem first = item("first.mht");
        first.cachedTitle = "My:Title/A";
        OutputNameService.Name n = OutputNameService.imageOutputName(item("second.mht"), params, first, "C:/in");
        assertFalse(n.parsing());
        assertEquals("My_Title_A", n.value());
    }

    @Test
    void imageMergedParsingWhenFirstVisibleWaiting() {
        AppConfig params = new AppConfig();
        params.setSingleOutputMode(true);
        params.setEnableNameExtraction(true);
        PreviewItem first = item("first.mht");
        first.status = PreviewItem.Status.WAITING;
        OutputNameService.Name n = OutputNameService.imageOutputName(item("second.mht"), params, first, "C:/in");
        assertTrue(n.parsing());
        assertNull(n.value());
    }

    @Test
    void imageMergedFallsBackToDefaultFolderNameWhenNameExtractionOff() {
        AppConfig params = new AppConfig();
        params.setSingleOutputMode(true);
        OutputNameService.Name n = OutputNameService.imageOutputName(item("x.mht"), params, null, "C:/my in");
        assertFalse(n.parsing());
        assertEquals("my in", n.value());
        // 空输入目录 → determineDefaultFolderName 回落 All_Images
        assertEquals("All_Images", OutputNameService.imageOutputName(item("x.mht"), params, null, "").value());
    }

    @Test
    void imageMergedFallsBackToDefaultFolderNameWhenNoVisibleItem() {
        AppConfig params = new AppConfig();
        params.setSingleOutputMode(true);
        params.setEnableNameExtraction(true);
        // firstVisible == null → 跳过标题判定，回落 determineDefaultFolderName
        OutputNameService.Name n = OutputNameService.imageOutputName(item("x.mht"), params, null, "C:/in");
        assertFalse(n.parsing());
        assertEquals("in", n.value());
    }

    @Test
    void imageSeparateDiffersFromMerged() {
        AppConfig separate = new AppConfig();
        AppConfig merged = new AppConfig();
        merged.setSingleOutputMode(true);
        OutputNameService.Name a = OutputNameService.imageOutputName(item("a.mht"), separate, null, "C:/in");
        OutputNameService.Name b = OutputNameService.imageOutputName(item("a.mht"), merged, null, "C:/in");
        assertEquals("a", a.value());
        assertEquals("in", b.value());
        assertNotEquals(a.value(), b.value());
    }
}
