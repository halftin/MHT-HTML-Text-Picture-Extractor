package com.mhtextractor.gui.util;

import com.mhtextractor.model.ThumbnailData;
import javafx.scene.image.Image;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ThumbnailFxBridge} 的<b>无头</b>往返单测（P6-A）。
 *
 * <h2>"{@code Image}/{@code PixelWriter} 需要 toolkit 吗"——查证过，不需要</h2>
 * <p>按提示词要求先查证。独立探针（{@code .claude-work/p6a/ToolkitProbe.java}，<b>不</b>启动
 * {@code Platform}）在 JavaFX 25.0.3 上的原始输出：</p>
 * <pre>
 * toolkit-started=false
 * OK   | new WritableImage(2,2)
 * OK   | WritableImage setPixels (ARGB int[])
 * OK   | WritableImage getPixels -&gt; IntBuffer
 * OK   | Image.getWidth on WritableImage (no toolkit)
 *        in =[ffff0000, ff00ff00, ff0000ff, 80ffffff]
 *        out=[ffff0000, ff00ff00, ff0000ff, 80ffffff]
 * OK   | ARGB round-trip incl. translucent alpha (0x80FFFFFF)
 * </pre>
 * <p>结论：{@code WritableImage} / {@code PixelWriter} / {@code PixelReader} 全部可用，
 * 且 <b>alpha ≠ 0 的像素逐位精确</b>（含半透明 0x80FFFFFF）⇒ 补<b>真正的无头往返单测</b>，
 * 不必把断言推到 GUI 探针。</p>
 *
 * <h2>🚩 一条<b>先于本次重构就存在</b>的边界：alpha == 0 的像素会丢 RGB</h2>
 * <p>同一探针实测（原始输出）：</p>
 * <pre>
 * in =[ffff0000, 0, 7f123456, ffffffff, ffffff, ff00ff00]
 * out=[ffff0000, 0, 7f123456, ffffffff, 0, ff00ff00]
 * FAIL | ARGB round-trip with fully transparent / translucent pixels
 * </pre>
 * <p>即：{@code 0x00FFFFFF}（全透明但 RGB=白）读回来变成 {@code 0x00000000}。
 * 这是 {@code WritableImage} + {@code PixelFormat.getIntArgbInstance()} 的<b>JavaFX 行为</b>，
 * 与本次重构无关（探针直接用 JavaFX API，没经过本类）—— 而且它对渲染<b>没有影响</b>：
 * alpha=0 的像素画出来完全一样。</p>
 * <p>故 {@link #fullyTransparentPixelsKeepTheirAlphaAndRenderIdentically()} 只钉
 * <b>"alpha 位不变（⇒ 渲染结果不变）"</b>，不去断言那个会随 JavaFX 版本变动的 RGB 载荷 ——
 * 那属于"给上游实现细节上锁"，会让测试因为无关的升级变红。</p>
 */
class ThumbnailFxBridgeTest {

    /** 2×3、含半透明与全不透明像素（不含 alpha=0，见类注释的边界说明）。 */
    private static final int[] PIXELS = {
            0xFFFF0000, 0xFF00FF00, 0xFF0000FF,
            0x80FFFFFF, 0x7F123456, 0xFFABCDEF,
    };

    @Test
    void roundTripPreservesPixelsAndSize() {
        ThumbnailData source = new ThumbnailData("img001", 3, 2, PIXELS);

        Image img = ThumbnailFxBridge.toFxImage(source);
        assertNotNull(img, "可用像素必须构造出图像");
        assertEquals(3.0, img.getWidth());
        assertEquals(2.0, img.getHeight());

        ThumbnailData back = ThumbnailFxBridge.toThumbnailData(img, "img001");
        assertEquals(3, back.width());
        assertEquals(2, back.height());
        assertEquals("img001", back.coreName());
        assertArrayEquals(PIXELS, back.pixels(), "ARGB 像素必须逐位往返（含半透明）");
    }

    /** 1×1 与"单像素"边界（stride == width 的退化情形，最容易把 stride 写错的地方）。 */
    @Test
    void singlePixelRoundTrips() {
        ThumbnailData source = new ThumbnailData("one", 1, 1, new int[]{0xFF336699});
        ThumbnailData back = ThumbnailFxBridge.toThumbnailData(
                ThumbnailFxBridge.toFxImage(source), "one");
        assertEquals(1, back.width());
        assertEquals(1, back.height());
        assertArrayEquals(new int[]{0xFF336699}, back.pixels());
    }

    /**
     * 全透明像素：<b>alpha 位</b>必须保持为 0（⇒ 渲染结果与源一致）。
     * RGB 载荷由 JavaFX 归零（见类注释的实测），故不断言它。
     */
    @Test
    void fullyTransparentPixelsKeepTheirAlphaAndRenderIdentically() {
        int[] src = {0x00FFFFFF, 0x00000000, 0xFF102030};
        ThumbnailData back = ThumbnailFxBridge.toThumbnailData(
                ThumbnailFxBridge.toFxImage(new ThumbnailData("t", 3, 1, src)), "t");
        int[] out = back.pixels();
        assertNotNull(out);
        for (int i = 0; i < src.length; i++) {
            assertEquals(src[i] & 0xFF000000, out[i] & 0xFF000000,
                    "第 " + i + " 个像素的 alpha 位必须保持不变");
        }
        System.out.println("[thumbnailfx] 全透明像素载荷实测：in=" + hex(src) + " out=" + hex(out)
                + "（alpha 位一致；alpha=0 的 RGB 由 JavaFX 归零，属既有行为）");
    }

    // ==================== 失败语义（与搬迁前逐字一致：失败 = "无法加载"，不抛异常） ====================

    @Test
    void unusablePixelsYieldNullImageInsteadOfThrowing() {
        assertNull(ThumbnailFxBridge.toFxImage(new ThumbnailData("n", 2, 2, null)), "pixels == null");
        assertNull(ThumbnailFxBridge.toFxImage(new ThumbnailData("n", 0, 2, new int[]{1, 2})), "宽为 0");
        assertNull(ThumbnailFxBridge.toFxImage(new ThumbnailData("n", 2, 0, new int[]{1, 2})), "高为 0");
        assertNull(ThumbnailFxBridge.toFxImage(new ThumbnailData("n", 4, 4, new int[3])), "像素数不足");
    }

    @Test
    void nullImageBecomesUnloadableDataInsteadOfThrowing() {
        ThumbnailData d = ThumbnailFxBridge.toThumbnailData(null, "core");
        assertEquals("core", d.coreName());
        assertEquals(0, d.width());
        assertEquals(0, d.height());
        assertNull(d.pixels(), "null 图像 ⇒ pixels=null（渲染层据此显示占位文案）");
    }

    /** 反向：像素可用时必须真的拿到非 null 像素数组（防止"两边都返回失败"也算通过）。 */
    @Test
    void usablePixelsDoProduceAnImage() {
        assertNotNull(ThumbnailFxBridge.toFxImage(new ThumbnailData("n", 1, 1, new int[]{0xFF000000})));
        ThumbnailData back = ThumbnailFxBridge.toThumbnailData(
                ThumbnailFxBridge.toFxImage(new ThumbnailData("n", 1, 1, new int[]{0xFF000000})), "n");
        assertNotNull(back.pixels());
        assertTrue(back.width() > 0 && back.height() > 0);
    }

    private static String hex(int[] px) {
        return Arrays.toString(Arrays.stream(px).mapToObj(Integer::toHexString).toArray());
    }
}
