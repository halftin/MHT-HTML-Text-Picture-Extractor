package com.mhtextractor.uicore;

import java.nio.IntBuffer;

/**
 * 缩略图像素打包与尺寸校验纯函数（P3-3b-8）：{@code gui.util.ThumbnailFxBridge} 对
 * {@code ThumbnailPixels} 的两处纯静态调用经本类 1:1 收口 —— 参数顺序与返回语义
 * 与底层完全一致（只搬不改）。
 *
 * <p>像素契约（ARGB 非预乘、行优先连续、stride == width）由底层 {@link ThumbnailPixels}
 * 定义，本类不复制、不改写。</p>
 *
 * <p>本类<b>零 JavaFX、零 I18n</b>，属 {@code uicore} 层。</p>
 */
public final class PreviewPixelService {

    private PreviewPixelService() {
    }

    /**
     * 尺寸合法性（供调用方在构造 {@code WritableImage} 前做前置校验）：
     * 1:1 转发 {@link ThumbnailPixels#isUsable(int[], int, int)}。
     */
    public static boolean isUsable(int[] pixels, int width, int height) {
        return ThumbnailPixels.isUsable(pixels, width, height);
    }

    /**
     * 把 {@code IntBuffer}（position=0、limit=width*height）转成 int 数组：
     * 1:1 转发 {@link ThumbnailPixels#pack}；输入为 null 或尺寸非法时返回 null（表示"无法加载"）。
     */
    public static int[] pack(IntBuffer buffer, int width, int height) {
        return ThumbnailPixels.pack(buffer, width, height);
    }
}
