package com.mhtextractor.uicore;

import com.mhtextractor.uicore.ThumbnailPixels;
import org.junit.jupiter.api.Test;

import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PreviewPixelService} 无头单测（P3-3b-8）：两类包装与底层
 * {@link ThumbnailPixels#isUsable} / {@link ThumbnailPixels#pack} 同参同返逐字等价（只搬不改）。
 */
class PreviewPixelServiceTest {

    // ==================== isUsable（→ ThumbnailPixels.isUsable） ====================

    @Test
    void isUsableAcceptsExactOrOversizedPixels() {
        assertTrue(PreviewPixelService.isUsable(new int[]{1, 2, 3, 4}, 2, 2));
        // 长度可以大于 w*h（调用方只保证"足够"）
        assertTrue(PreviewPixelService.isUsable(new int[]{1, 2, 3, 4, 5}, 2, 2));
        assertEquals(ThumbnailPixels.isUsable(new int[]{1, 2, 3, 4}, 2, 2),
                PreviewPixelService.isUsable(new int[]{1, 2, 3, 4}, 2, 2));
    }

    @Test
    void isUsableRejectsNullNonPositiveSizeAndTooSmall() {
        assertFalse(PreviewPixelService.isUsable(null, 2, 2));
        assertFalse(PreviewPixelService.isUsable(new int[4], 0, 2));
        assertFalse(PreviewPixelService.isUsable(new int[4], 2, -1));
        // 长度不足 w*h：必须判为不可用（否则 WritableImage 构造会越界/错位）
        assertFalse(PreviewPixelService.isUsable(new int[]{1, 2, 3}, 2, 2));
        assertEquals(ThumbnailPixels.isUsable(null, 2, 2),
                PreviewPixelService.isUsable(null, 2, 2));
        assertEquals(ThumbnailPixels.isUsable(new int[]{1, 2, 3}, 2, 2),
                PreviewPixelService.isUsable(new int[]{1, 2, 3}, 2, 2));
    }

    // ==================== pack（→ ThumbnailPixels.pack） ====================

    @Test
    void packCopiesBufferContent() {
        IntBuffer buf = IntBuffer.wrap(new int[]{0xFF000000, 0x80FFFFFF, 0x11223344});
        assertArrayEquals(new int[]{0xFF000000, 0x80FFFFFF, 0x11223344},
                PreviewPixelService.pack(buf, 3, 1));
        int[] viaService = PreviewPixelService.pack(IntBuffer.wrap(new int[]{1, 2, 3}), 3, 1);
        int[] viaUnderlying = ThumbnailPixels.pack(IntBuffer.wrap(new int[]{1, 2, 3}), 3, 1);
        assertArrayEquals(viaUnderlying, viaService);
    }

    @Test
    void packReturnsNullForInvalidInput() {
        assertNull(PreviewPixelService.pack(null, 2, 2));
        assertNull(PreviewPixelService.pack(IntBuffer.allocate(4), 0, 2));
        assertNull(PreviewPixelService.pack(IntBuffer.allocate(4), 2, -1));
        // remaining < w*h：容量不足
        assertNull(PreviewPixelService.pack(IntBuffer.allocate(3), 2, 2));
    }
}
