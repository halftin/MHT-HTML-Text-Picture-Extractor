package com.mhtextractor.extractor;

import com.mhtextractor.core.MimeParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ImageExtractor 纯逻辑测试（P0-5/P0-6，见《项目需求文档.md》§三）。
 * 直接构造 MimeParser.ParsedMht，无 GUI 依赖。
 */
class ImageExtractorTest {

    private static final byte[] DATA = {1, 2, 3};
    private static final String HTML = "<html><body><p>x</p></body></html>";

    private static MimeParser.ParsedMht parsed(String html, List<MimeParser.ImageInfo> images) {
        MimeParser.ParsedMht p = new MimeParser.ParsedMht();
        p.setHtmlContent(html);
        p.setImageInfoList(images);
        return p;
    }

    private static MimeParser.ImageInfo img(String cid) {
        return new MimeParser.ImageInfo(cid, null, cid, DATA);
    }

    private static ImageExtractor noRegion(String html, List<MimeParser.ImageInfo> images) {
        return new ImageExtractor(parsed(html, images), null, null, null, null);
    }

    private static ImageExtractor region(String html, List<MimeParser.ImageInfo> images, String start, String end) {
        return new ImageExtractor(parsed(html, images), null, null, start, end);
    }

    private static List<String> filenames(List<ImageExtractor.ImageData> out) {
        return out.stream().map(ImageExtractor.ImageData::getFilename).toList();
    }

    // (a) header@ / download / shadow 这类含 "ad" 子串的正常 CID 不被误删
    @Test
    void headerDownloadShadowCidsAreNotFilteredAsAds() {
        List<MimeParser.ImageInfo> images = List.of(
                img("header@01D8AD12.9A2B3C4D"),
                img("download_12345"),
                img("shadow"));
        ImageExtractor ex = noRegion(HTML, images);
        List<ImageExtractor.ImageData> out = ex.extractImages();
        assertEquals(3, out.size());
        assertEquals(List.of("header@01D8AD12.9A2B3C4D", "download_12345", "shadow"), filenames(out));
        assertEquals("extract.region.r2", ex.getLastWarnings().getFirst().key());
    }

    // (b) 空字符串 CID 与 null CID 统一保留（此前空串被删、null 保留，行为不一致）
    @Test
    void emptyAndNullCidAreBothKept() {
        MimeParser.ImageInfo emptyCid = new MimeParser.ImageInfo("", "http://x/empty.png", "empty.png", DATA);
        MimeParser.ImageInfo nullCid = new MimeParser.ImageInfo(null, "http://x/null.png", "null.png", DATA);
        ImageExtractor ex = noRegion(HTML, List.of(emptyCid, nullCid));
        List<ImageExtractor.ImageData> out = ex.extractImages();
        assertEquals(2, out.size());
        assertEquals(List.of("empty.png", "null.png"), filenames(out));
        assertEquals("extract.region.r2", ex.getLastWarnings().getFirst().key());
    }

    // (3.3) 独立成词的广告标识符仍被过滤，且删除记入 warnings
    @Test
    void adImagesAreFilteredAndRecordedInWarnings() {
        ImageExtractor ex = noRegion(HTML, List.of(img("ad_123"), img("images/ads/banner-ad.png"), img("photo1")));
        List<ImageExtractor.ImageData> out = ex.extractImages();
        assertEquals(1, out.size());
        assertEquals("photo1", out.get(0).getFilename());
        assertEquals(3, ex.getLastWarnings().size());
        assertEquals("extract.adFiltered", ex.getLastWarnings().get(1).key());
        assertEquals("ad_123", ex.getLastWarnings().get(1).args().get("name"));
    }

    // P4-15: failed region parsing falls back with traceable warnings (final D1/D2).
    @Test
    void regionParseFailureFallsBackWithWarning() {
        String html = "<html><body><img src=\"cid:out1\"><div>START<img src=\"cid:in1\">END</div>"
                + "<img src=\"cid:out2\"></body></html>";
        List<MimeParser.ImageInfo> images = List.of(img("out1"), img("in1"), img("out2"));
        ImageExtractor ex = new ImageExtractor(parsed(html, images), null, null, "START", "END") {
            @Override
            List<String> extractImageReferencesInOrder(String html) {
                return null;
            }
        };
        List<ImageExtractor.ImageData> out = ex.extractImages();
        assertEquals(3, out.size());
        assertEquals(2, ex.getLastWarnings().size());
        assertEquals("extract.areaImageRefFailed", ex.getLastWarnings().get(0).key());
    }

    // P4-15: extraction implicitly accepts R5; preview explicitly defers that decision.
    @Test
    void regionWithNoImagesFallsBackOnlyWhenAccepted() {
        String html = "<html><body><div>START<p>no images here</p>END</div></body></html>";
        ImageExtractor ex = region(html, List.of(img("cid1"), img("cid2")), "START", "END");
        List<ImageExtractor.ImageData> out = ex.extractImages();
        assertEquals(2, out.size());
        assertEquals("extract.region.r5", ex.getLastWarnings().getFirst().key());
        assertTrue(ex.extractImages(null, false).isEmpty());
        assertTrue(ex.requiresFullDocumentConfirmation());
    }

    // 区域限定的正常路径：只提取区域内图片，顺序不变
    @Test
    void regionLimitKeepsOnlyInRegionImages() {
        String html = "<html><body><img src=\"cid:out1\"><div>START<img src=\"cid:in1\"><img src=\"cid:in2\">END</div>"
                + "<img src=\"cid:out2\"></body></html>";
        ImageExtractor ex = region(html, List.of(img("out1"), img("in1"), img("in2"), img("out2")), "START", "END");
        List<ImageExtractor.ImageData> out = ex.extractImages();
        assertEquals(2, out.size());
        assertEquals(List.of("in1", "in2"), filenames(out));
    }

    // (d) 正常图片（含 Outlook 风格 CID）的提取结果不发生变化
    @Test
    void normalImagesExtractionIsUnchanged() {
        List<MimeParser.ImageInfo> images = List.of(
                img("image001.png@01D8C2E7.9A2B3C4D"),
                img("logo"),
                img("photo_1"));
        ImageExtractor ex = noRegion(HTML, images);
        List<ImageExtractor.ImageData> out = ex.extractImages();
        assertEquals(3, out.size());
        assertEquals(List.of("image001.png@01D8C2E7.9A2B3C4D", "logo", "photo_1"), filenames(out));
        for (int i = 0; i < 3; i++) {
            assertEquals(images.get(i).getContentId(), out.get(i).getContentId());
            assertArrayEquals(DATA, out.get(i).getData());
        }
        assertEquals("extract.region.r2", ex.getLastWarnings().getFirst().key());
    }

    // ==================== 步骤 7 修复：纯 Data URI 的 MHT ====================

    /** 1×1 PNG 的 base64（与测试夹具同源形态） */
    private static final String PNG_B64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    private static String dataUriHtml() {
        return "<html><body><img src=\"data:image/png;base64," + PNG_B64 + "\"></body></html>";
    }

    /**
     * 修复点（步骤 7）：{@code imageInfoList} 为**空**时也必须提取 HTML 内嵌的 Data URI 图片。
     *
     * <p>修复前的实现：{@code noRegionLimit} 分支在 {@code imageInfoList} 为 null/空时直接
     * {@code return Collections.emptyList()} —— 而纯 Data URI 的 MHT 本来就没有图片 part，
     * {@code imageInfoList} 必然为空，于是 Data URI 提取成了**不可达的死代码**，
     * 表现为"有图却报跳过 / 一张都提不出来"。</p>
     */
    @Test
    void dataUriImagesAreExtractedWhenImageInfoListIsEmpty() {
        ImageExtractor ex = noRegion(dataUriHtml(), List.of());
        List<ImageExtractor.ImageData> out = ex.extractImages();

        assertEquals(1, out.size(), "空 imageInfoList 时仍应提取出 Data URI 图片");
        ImageExtractor.ImageData d = out.get(0);
        assertTrue(d.getFilename().startsWith("image_0."), "命名应为 image_0.<ext>，实际 " + d.getFilename());
        assertTrue(d.getFilename().endsWith(".png"), "应据魔数识别为 png，实际 " + d.getFilename());
        assertTrue(d.getData().length > 0, "解出的字节不应为空");
        // 魔数校验：确认真的解出了 PNG，而不是把 base64 文本当字节
        assertEquals((byte) 0x89, d.getData()[0]);
        assertEquals((byte) 0x50, d.getData()[1]);
        assertEquals((byte) 0x4E, d.getData()[2]);
        assertEquals((byte) 0x47, d.getData()[3]);
    }

    /** 回归：{@code imageInfoList} 为空且 HTML 里没有 Data URI 时，仍应返回空列表（不得凭空造图） */
    @Test
    void emptyImageInfoListWithoutDataUriStillYieldsNothing() {
        ImageExtractor ex = noRegion(HTML, List.of());
        assertTrue(ex.extractImages().isEmpty(), "无图片 part 且无 Data URI 时应为空");
    }

    /**
     * 回归：图片 part **与** Data URI 同时存在时，两者都要提取，且 Data URI **不得重复提取**。
     *
     * <p>这个用例针对我在修复过程中犯的一个错：在 {@code noRegionLimit} 分支额外调了一次
     * {@code extractDataUriImages}，而 {@code extractAllImagesInOrder} 内部已经调过 ——
     * 结果是每张 Data URI 图片被提取两遍（产出 image_1/image_3 内容相同的重复文件）。
     * 该缺陷被基线比对抓到（data-uri-mixed 产物多出两项）。</p>
     */
    @Test
    void dataUriIsNotDoubleExtractedWhenImagePartsExist() {
        List<MimeParser.ImageInfo> images = List.of(img("img001"));
        ImageExtractor ex = noRegion(dataUriHtml(), images);
        List<ImageExtractor.ImageData> out = ex.extractImages();

        assertEquals(2, out.size(), "1 张图片 part + 1 张 Data URI = 2，不得重复：实际 " + filenames(out));
        long pngCount = out.stream().filter(d -> d.getFilename().endsWith(".png")).count();
        assertEquals(1, pngCount, "Data URI 的 PNG 只能出现一次");
    }
}
