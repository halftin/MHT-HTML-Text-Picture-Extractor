package com.mhtextractor.gui.util;

import com.mhtextractor.app.PreviewPixelService;
import com.mhtextractor.model.ThumbnailData;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.nio.IntBuffer;

/**
 * 缩略图的 <b>ARGB {@code int[]} ↔ JavaFX {@code Image}</b> 桥（P6-A 余下候选）：
 * 从 {@code MainController} 逐字搬出的两个静态转换。
 *
 * <h2>为什么是"桥"而不是模型方法</h2>
 * <p>{@link ThumbnailData} 刻意<b>零 JavaFX</b>（它持解码后的 ARGB 像素，不是 {@code Image} ——
 * 取舍见其 javadoc），所以"像素 → 图像"与"图像 → 像素"这两个方向<b>不属于模型层</b>，
 * 只能住在渲染层。搬迁前它们散在控制器里，且其中一处的 javadoc 被另一段代码用
 * {@code {@link #toFxImage}} 引用 —— 搬出来之后这条知识有了自己的名字。</p>
 *
 * <h2>失败语义（与搬迁前逐字一致，不得改）</h2>
 * <ul>
 *   <li>{@link #toFxImage}：像素不可用（{@code null}/尺寸非法）或构造抛异常 → 返回 {@code null}
 *       = "无法加载"，渲染层显示占位文案；</li>
 *   <li>{@link #toThumbnailData}：{@code img == null}、尺寸 ≤ 0、或取像素抛异常 →
 *       返回 {@code pixels == null} 的数据对象（同样表示"无法加载"）。</li>
 * </ul>
 * <p>两处都<b>吞异常不打印</b>：这是搬迁前的行为（占位即结论，不是错误条件）。</p>
 *
 * <h2>无头可测（实测，不是推断）</h2>
 * <p>P6-A 实测：未启动 toolkit 的 JVM 里 {@code new WritableImage(w,h)}、
 * {@code getPixelWriter().setPixels(...)} 与 {@code getPixelReader().getPixels(..., IntBuffer, stride)}
 * 全部可用，且 ARGB 往返<b>逐位精确</b>（含半透明 {@code 0x80FFFFFF}）。
 * 因此本类有真正的无头往返单测（{@code ThumbnailFxBridgeTest}）：像素与宽高必须原样回来。</p>
 */
public final class ThumbnailFxBridge {

    private ThumbnailFxBridge() {
    }

    /** 缩略图纯数据 → JavaFX 图像（渲染层唯一的 JavaFX 转换点；失败返回 null 表示无法加载） */
    public static Image toFxImage(ThumbnailData d) {
        int[] px = d.pixels();
        if (!PreviewPixelService.isUsable(px, d.width(), d.height())) {
            return null;
        }
        try {
            WritableImage img = new WritableImage(d.width(), d.height());
            // stride == width：像素为行优先连续的 ARGB int（打包/解包契约见 PreviewPixelService）
            img.getPixelWriter().setPixels(0, 0, d.width(), d.height(),
                    PixelFormat.getIntArgbInstance(), px, 0, d.width());
            return img;
        } catch (Exception e) {
            // 与 decodeThumb 一致：失败即视为无法加载（渲染层显示占位）
            return null;
        }
    }

    /** JavaFX 图像 → 缩略图纯数据（后台线程调用；失败返回 pixels=null 的数据对象） */
    public static ThumbnailData toThumbnailData(Image img, String coreName) {
        if (img == null) {
            return new ThumbnailData(coreName, 0, 0, null);
        }
        try {
            int w = (int) Math.round(img.getWidth());
            int h = (int) Math.round(img.getHeight());
            if (w <= 0 || h <= 0) {
                return new ThumbnailData(coreName, 0, 0, null);
            }
            // 经 IntBuffer 接收，再交由纯 Java 的 PreviewPixelService 打包 —— 该段逻辑有零 JavaFX 单测
            IntBuffer buf = IntBuffer.allocate(w * h);
            img.getPixelReader().getPixels(0, 0, w, h,
                    PixelFormat.getIntArgbInstance(), buf, w);
            int[] px = PreviewPixelService.pack(buf, w, h);
            if (px == null) {
                return new ThumbnailData(coreName, 0, 0, null);
            }
            return new ThumbnailData(coreName, w, h, px);
        } catch (Exception e) {
            return new ThumbnailData(coreName, 0, 0, null);
        }
    }
}
