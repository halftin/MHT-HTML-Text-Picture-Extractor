package com.mhtextractor.app;

import com.mhtextractor.extractor.ImageExtractor;
import com.mhtextractor.output.FileWriter;
import com.mhtextractor.output.ImageWriter;
import com.mhtextractor.util.SanitizeUtils;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PreviewNamingService} 无头单测（P3-3b）：三类包装与底层
 * {@link FileWriter#processTemplate} / {@link SanitizeUtils#detectExtensionByMagicNumber} /
 * {@link ImageWriter#computeFileName} 同参同返逐字等价（只搬不改）。
 */
class PreviewNamingServiceTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 6, 9, 8, 7);

    private static ImageExtractor.ImageData img(String filename, byte[] data) {
        return new ImageExtractor.ImageData(filename, null, data);
    }

    private static PreviewImageData pimg(String filename, byte[] data) {
        return new PreviewImageData(filename, null, data);
    }

    // ==================== applyTemplate（→ FileWriter.processTemplate） ====================

    @Test
    void templateFilenameReplacementMatchesUnderlying() {
        String template = "《{filename}》";
        String name = "报告.mht";
        assertEquals("《报告.mht》", PreviewNamingService.applyTemplate(template, name, T));
        assertEquals(FileWriter.processTemplate(template, name, T),
                PreviewNamingService.applyTemplate(template, name, T));
    }

    @Test
    void templateNullOrEmptyReturnsEmpty() {
        assertEquals("", PreviewNamingService.applyTemplate(null, "x.mht", T));
        assertEquals("", PreviewNamingService.applyTemplate("", "x.mht", T));
        assertEquals(FileWriter.processTemplate(null, "x.mht", T),
                PreviewNamingService.applyTemplate(null, "x.mht", T));
    }

    @Test
    void templateDateTimeVariablesReplace() {
        String template = "{datetime}|{date}|{time}";
        String expected = "2026-10-06 09:08:07|2026-10-06|09:08:07";
        assertEquals(expected, PreviewNamingService.applyTemplate(template, "x.mht", T));
        assertEquals(FileWriter.processTemplate(template, "x.mht", T),
                PreviewNamingService.applyTemplate(template, "x.mht", T));
    }

    @Test
    void templateEmptyVariablesWhenTimeNullAndNewlineEscape() {
        assertEquals("x|||", PreviewNamingService.applyTemplate("{filename}|{datetime}|{date}|{time}", "x", null));
        assertEquals("a\nb", PreviewNamingService.applyTemplate("a\\nb", "x", T));
        assertEquals(FileWriter.processTemplate("{filename}|{datetime}|{date}|{time}", "x", null),
                PreviewNamingService.applyTemplate("{filename}|{datetime}|{date}|{time}", "x", null));
        assertEquals(FileWriter.processTemplate("a\\nb", "x", T),
                PreviewNamingService.applyTemplate("a\\nb", "x", T));
    }

    // ==================== detectExtension（→ SanitizeUtils.detectExtensionByMagicNumber） ====================

    @Test
    void detectExtensionMatchesMagicNumber() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A};
        byte[] gif = {0x47, 0x49, 0x46, 0x38, 0x39, 0x61};
        byte[] ico = {0, 0, 1, 0, 0, 0};
        assertEquals("jpg", PreviewNamingService.detectExtension(jpeg, "jpg"));
        assertEquals("png", PreviewNamingService.detectExtension(png, "jpg"));
        assertEquals("gif", PreviewNamingService.detectExtension(gif, "jpg"));
        assertEquals("ico", PreviewNamingService.detectExtension(ico, "jpg"));
        assertEquals(SanitizeUtils.detectExtensionByMagicNumber(jpeg, "jpg"),
                PreviewNamingService.detectExtension(jpeg, "jpg"));
        assertEquals(SanitizeUtils.detectExtensionByMagicNumber(png, "jpg"),
                PreviewNamingService.detectExtension(png, "jpg"));
        assertEquals(SanitizeUtils.detectExtensionByMagicNumber(gif, "jpg"),
                PreviewNamingService.detectExtension(gif, "jpg"));
        assertEquals(SanitizeUtils.detectExtensionByMagicNumber(ico, "jpg"),
                PreviewNamingService.detectExtension(ico, "jpg"));
    }

    @Test
    void detectExtensionFallsBackOnShortNullOrUnknownData() {
        assertEquals("png", PreviewNamingService.detectExtension(new byte[]{1, 2, 3}, "png"));
        assertEquals("jpg", PreviewNamingService.detectExtension(null, "jpg"));
        assertEquals("bmp", PreviewNamingService.detectExtension(new byte[]{9, 9, 9, 9, 9, 9}, "bmp"));
        assertEquals(SanitizeUtils.detectExtensionByMagicNumber(new byte[]{9, 9, 9, 9, 9, 9}, "bmp"),
                PreviewNamingService.detectExtension(new byte[]{9, 9, 9, 9, 9, 9}, "bmp"));
    }

    // ==================== imageFileName（→ ImageWriter.computeFileName） ====================

    @Test
    void imageFileNameMatchesUnderlying() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
        ImageExtractor.ImageData d = img("image_0.jpg", jpeg);
        PreviewImageData p = pimg("image_0.jpg", jpeg);
        // 批量重命名：序号格式 00001.ext（renameIndex 1 起）
        assertEquals("00002.jpg", PreviewNamingService.imageFileName(p, "jpg", true, 2, ""));
        assertEquals(ImageWriter.computeFileName(d, "jpg", true, 2, ""),
                PreviewNamingService.imageFileName(p, "jpg", true, 2, ""));
        // 合并不重命名：文件前缀 + 去扩展名原名
        assertEquals("f01_image_0.jpg", PreviewNamingService.imageFileName(p, "jpg", false, 1, "f01_"));
        assertEquals(ImageWriter.computeFileName(d, "jpg", false, 1, "f01_"),
                PreviewNamingService.imageFileName(p, "jpg", false, 1, "f01_"));
        // 纯数字原名：加 img_ 前缀
        ImageExtractor.ImageData n = img("123.jpg", jpeg);
        PreviewImageData pn = pimg("123.jpg", jpeg);
        assertEquals("img_123.jpg", PreviewNamingService.imageFileName(pn, "jpg", false, 1, ""));
        assertEquals(ImageWriter.computeFileName(n, "jpg", false, 1, ""),
                PreviewNamingService.imageFileName(pn, "jpg", false, 1, ""));
    }

    @Test
    void imageFileNameNullDataUsesUnnamedFallback() {
        assertEquals("unnamed.jpg", PreviewNamingService.imageFileName(null, "jpg", false, 1, ""));
        assertEquals(ImageWriter.computeFileName(null, "jpg", false, 1, ""),
                PreviewNamingService.imageFileName(null, "jpg", false, 1, ""));
        // data 为 null 同样回落 unnamed
        assertEquals("unnamed.png", PreviewNamingService.imageFileName(pimg("x.png", null), "png", false, 1, ""));
        assertEquals(ImageWriter.computeFileName(img("x.png", null), "png", false, 1, ""),
                PreviewNamingService.imageFileName(pimg("x.png", null), "png", false, 1, ""));
        // filename 为 null：sanitize → ""，stripExtension 回落 unnamed
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
        assertEquals("unnamed.jpg", PreviewNamingService.imageFileName(pimg(null, jpeg), "jpg", false, 1, ""));
        assertEquals(ImageWriter.computeFileName(img(null, jpeg), "jpg", false, 1, ""),
                PreviewNamingService.imageFileName(pimg(null, jpeg), "jpg", false, 1, ""));
    }
}
