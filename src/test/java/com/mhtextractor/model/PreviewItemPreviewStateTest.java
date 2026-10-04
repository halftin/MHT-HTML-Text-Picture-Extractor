package com.mhtextractor.model;

import com.mhtextractor.cache.PreviewCacheMeta;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * E1.2 —— 「清预览态」两套语义的唯一实现（{@link PreviewItem#resetPreviewState} /
 * {@link PreviewItem#releaseImageData}）的精确字段集断言：只直连 public 字段/方法，不用反射。
 *
 * <p>背景缺陷：{@code ParamDomainController.refreshAfterTargetedParamsChange} 曾手写字段清单而
 * 漏清 {@code cachedMeta}（陈旧 meta 残留）；{@code ListInteractionController} 的"只清图片数据"
 * 是另一种慎重语义。本测试把两种语义的<b>完整字段集</b>钉死：哪个字段被漏掉/多清，测试必然变红
 * （E1.2 的变异对照就以"删掉 resetPreviewState 里的 cachedMeta 赋值"验证过）。</p>
 */
class PreviewItemPreviewStateTest {

    /** 五个缓存字段 + status 全部置为非默认值的"已渲染"条目。 */
    private static PreviewItem seededItem() {
        PreviewItem item = new PreviewItem("C:\\fixtures\\a.mht", "a.mht", LocalDateTime.now(), "C:\\fixtures");
        item.cachedText = "正文";
        item.cachedTitle = "标题";
        item.cachedImages = new ArrayList<>();          // 历史遗留字段：只被写 null、从不被读取
        item.cachedThumbs = List.of(new ThumbnailData("0001.png", 2, 2,
                new int[]{0xFF0000FF, 0xFF00FF00, 0xFFFF0000, 0xFFFFFFFF}));
        item.cachedMeta = new PreviewCacheMeta(PreviewCacheMeta.FORMAT_VERSION,
                new PreviewCacheMeta.Source(item.path, 1L, 2L, ""), "probe-fp", true,
                List.of(new PreviewCacheMeta.ImageEntry("images/0001.png", "cid:x", "0001.png", "png", List.of())),
                List.of(new ExtractWarning("w1", Map.of())));
        item.status = PreviewItem.Status.READY;
        return item;
    }

    /** resetPreviewState 后五个缓存字段全 null 且 status == WAITING（含曾被漏清的 cachedMeta）。 */
    @Test
    void resetPreviewStateClearsAllFiveCacheFieldsAndReturnsToWaiting() {
        PreviewItem item = seededItem();

        item.resetPreviewState();

        assertNull(item.cachedText, "resetPreviewState 必须清 cachedText");
        assertNull(item.cachedTitle, "resetPreviewState 必须清 cachedTitle");
        assertNull(item.cachedImages, "resetPreviewState 必须清 cachedImages");
        assertNull(item.cachedThumbs, "resetPreviewState 必须清 cachedThumbs");
        assertNull(item.cachedMeta, "resetPreviewState 必须清 cachedMeta（此前的缺陷：漏清）");
        assertEquals(PreviewItem.Status.WAITING, item.status, "resetPreviewState 必须回到 WAITING");
    }

    /** releaseImageData 只清 cachedImages/cachedThumbs；text/title/meta/status 保持不动。 */
    @Test
    void releaseImageDataClearsOnlyImagesAndThumbs() {
        PreviewItem item = seededItem();

        item.releaseImageData();

        assertNull(item.cachedImages, "releaseImageData 必须清 cachedImages");
        assertNull(item.cachedThumbs, "releaseImageData 必须清 cachedThumbs");
        assertEquals("正文", item.cachedText, "releaseImageData 不得动 cachedText");
        assertEquals("标题", item.cachedTitle, "releaseImageData 不得动 cachedTitle");
        assertNotNull(item.cachedMeta, "releaseImageData 不得动 cachedMeta");
        assertEquals(PreviewItem.Status.READY, item.status, "releaseImageData 不得动 status");
    }

    /** 两种语义互不混淆：resetPreviewState 不含"保留文本"语义，releaseImageData 不含"回 WAITING"语义。 */
    @Test
    void theTwoSemanticsDoNotOverlap() {
        PreviewItem item = seededItem();
        item.resetPreviewState();
        assertNull(item.cachedText, "reset 必须清 text（不是 release 语义）");
        assertEquals(PreviewItem.Status.WAITING, item.status, "reset 必须回 WAITING");

        PreviewItem other = seededItem();
        other.releaseImageData();
        assertEquals(PreviewItem.Status.READY, other.status, "release 必须保留 status（不是 reset 语义）");
        assertNotNull(other.cachedMeta, "release 必须保留 meta");
        assertEquals("正文", other.cachedText, "release 必须保留 text");
    }
}
