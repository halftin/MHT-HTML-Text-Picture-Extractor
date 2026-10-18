package com.mhtextractor.uicore;

import org.junit.jupiter.api.Test;

import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缩略图像素打包/解包测试（会话 18 · E1.4）。
 *
 * <p><b>为什么必须有这组测试</b>：E1.4 把缩略图从 JavaFX {@code Image} 改为纯像素数组，
 * 渲染时需要把数组解包回图像。stride/尺寸写错会导致<b>图像斜切、行错位</b>——
 * 这类错误在重构前只能靠肉眼看真实预览才发现，而 GUI 自动化断言只检查
 * "cachedThumbs 非空 + cachedImages 为空"，<b>捕捉不到像素级错位</b>。
 * 本类把这部分逻辑抽到 {@link ThumbnailPixels}（零 JavaFX）后即可精确锁定。</p>
 *
 * <p>本测试**不依赖 JavaFX 运行时**（与既有 300+ 单测同风格）。</p>
 */
class ThumbnailPixelsTest {

    private static final int W = 5;
    private static final int H = 3;

    /** 造一组可辨识的 ARGB 像素（每格不同，便于发现行/列错位） */
    private static int[] sample() {
        int[] px = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                // 0xFF000000 | (y<<16) | (x<<8) | 0x7F：行列信息编进像素，错位即可见
                px[y * W + x] = 0xFF000000 | (y << 16) | (x << 8) | 0x7F;
            }
        }
        return px;
    }

    // ==================== pack ====================

    @Test
    void packReadsExactlyWidthTimesHeightElements() {
        int[] px = sample();
        IntBuffer buf = IntBuffer.allocate(W * H);
        buf.put(px);
        buf.flip();

        int[] packed = ThumbnailPixels.pack(buf, W, H);

        assertArrayEquals(px, packed, "pack 必须按行优先取出 width*height 个元素，不得偏移");
    }

    @Test
    void packRoundTripThroughUnpackPreservesPixels() {
        int[] px = sample();
        IntBuffer src = IntBuffer.allocate(W * H);
        src.put(px);
        src.flip();

        int[] packed = ThumbnailPixels.pack(src, W, H);

        // 模拟渲染层：解包回一个 IntBuffer（对应 WritableImage 的 PixelBuffer 形态）
        IntBuffer target = IntBuffer.allocate(W * H);
        assertTrue(ThumbnailPixels.unpack(packed, target, W, H));

        int[] back = new int[W * H];
        target.flip();
        target.get(back, 0, W * H);
        assertArrayEquals(px, back, "pack→unpack round-trip 必须逐像素一致（stride 错位会在此暴露）");
    }

    @Test
    void packRejectsIllegalInputs() {
        assertNull(ThumbnailPixels.pack(null, W, H), "null buffer → null（表示无法加载）");
        assertNull(ThumbnailPixels.pack(IntBuffer.allocate(10), 0, H), "width<=0 → null");
        assertNull(ThumbnailPixels.pack(IntBuffer.allocate(10), W, 0), "height<=0 → null");
        assertNull(ThumbnailPixels.pack(IntBuffer.allocate(W * H - 1), W, H),
                "缓冲区不足 width*height → null（不得越界读取）");
    }

    @Test
    void packToleratesBufferWithSurplusRemaining() {
        int[] px = sample();
        IntBuffer buf = IntBuffer.allocate(W * H + 4);
        buf.put(px);
        buf.rewind();

        int[] packed = ThumbnailPixels.pack(buf, W, H);

        assertArrayEquals(px, packed, "剩余空间多于所需时只取前 width*height 个");
    }

    // ==================== unpack ====================

    @Test
    void unpackRejectsIllegalInputs() {
        int[] px = sample();
        assertFalse(ThumbnailPixels.unpack(null, IntBuffer.allocate(W * H), W, H));
        assertFalse(ThumbnailPixels.unpack(px, null, W, H));
        assertFalse(ThumbnailPixels.unpack(px, IntBuffer.allocate(W * H), 0, H));
        assertFalse(ThumbnailPixels.unpack(px, IntBuffer.allocate(W * H), W, 0));
        assertFalse(ThumbnailPixels.unpack(new int[W * H - 1], IntBuffer.allocate(W * H), W, H),
                "像素数组不足 → false");
        assertFalse(ThumbnailPixels.unpack(px, IntBuffer.allocate(W * H - 1), W, H),
                "目标缓冲不足 → false（不得越界写入）");
    }

    // ==================== isUsable ====================

    @Test
    void isUsableMirrorsNullAndSizeSemantics() {
        int[] px = sample();
        assertTrue(ThumbnailPixels.isUsable(px, W, H));
        assertFalse(ThumbnailPixels.isUsable(null, W, H), "pixels==null 表示无法加载");
        assertFalse(ThumbnailPixels.isUsable(px, 0, H));
        assertFalse(ThumbnailPixels.isUsable(px, W, 0));
        assertFalse(ThumbnailPixels.isUsable(new int[W * H - 1], W, H));
    }

    @Test
    void singlePixelAndNonSquareSizesWork() {
        int[] one = {0xFF123456};
        IntBuffer b1 = IntBuffer.allocate(1);
        b1.put(one);
        b1.flip();
        assertArrayEquals(one, ThumbnailPixels.pack(b1, 1, 1), "1x1 必须可用");

        // 非方形、且宽 > 高与高 > 宽 两种情况都要覆盖（stride 错误常在非方形暴露）
        int[] tall = new int[2 * 7];
        for (int i = 0; i < tall.length; i++) tall[i] = 0xFF000000 | i;
        IntBuffer b2 = IntBuffer.allocate(tall.length);
        b2.put(tall);
        b2.flip();
        assertArrayEquals(tall, ThumbnailPixels.pack(b2, 2, 7), "非方形 2x7 必须逐元素一致");
    }
}
