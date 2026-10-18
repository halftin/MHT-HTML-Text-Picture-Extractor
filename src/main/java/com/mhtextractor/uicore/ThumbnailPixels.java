package com.mhtextractor.uicore;

import java.nio.IntBuffer;

/**
 * 缩略图像素打包/解包（会话 18 · E1.4）。
 *
 * <p><b>为什么独立成类</b>：E1.4 把缩略图从"JavaFX {@code Image} 对象"改为"纯像素数组"，
 * 于是渲染层需要把像素数组解包回 {@code WritableImage}。这段打包/解包逻辑是<b>最容易悄悄写错</b>
 * 的地方（stride 填错会让图像斜切/错位），而它此前只能靠真实 GUI 预览才发现。
 * 抽成纯 Java 类后可以<b>零 JavaFX 单测</b>直接锁定 round-trip 正确性。</p>
 *
 * <p>约定：像素为 {@code ARGB}（非预乘）int，行优先连续存放，每行 {@code width} 个元素
 * （即 stride == width）。</p>
 */
public final class ThumbnailPixels {

    private ThumbnailPixels() {
    }

    /**
     * 把 {@code IntBuffer}（position=0、limit=width*height）转成 int 数组。
     *
     * @return 数组；输入为 null 或尺寸非法时返回 null（表示"无法加载"）
     */
    public static int[] pack(IntBuffer buffer, int width, int height) {
        if (buffer == null || width <= 0 || height <= 0) {
            return null;
        }
        int n = width * height;
        if (buffer.remaining() < n) {
            return null;
        }
        int[] out = new int[n];
        buffer.get(out, 0, n);
        return out;
    }

    /**
     * 把 int 数组拷入 {@code IntBuffer}（用于 JavaFX {@code PixelWriter.setPixels} 的
     * {@code PixelBuffer} 形态）。
     *
     * @return true = 拷入成功；false = 参数非法（尺寸不匹配/空数组）
     */
    public static boolean unpack(int[] pixels, IntBuffer target, int width, int height) {
        if (pixels == null || target == null || width <= 0 || height <= 0) {
            return false;
        }
        int n = width * height;
        if (pixels.length < n || target.remaining() < n) {
            return false;
        }
        target.put(pixels, 0, n);
        return true;
    }

    /** 尺寸合法性（供调用方在构造 {@code WritableImage} 前做前置校验） */
    public static boolean isUsable(int[] pixels, int width, int height) {
        if (pixels == null || width <= 0 || height <= 0) {
            return false;
        }
        return pixels.length >= width * height;
    }
}
