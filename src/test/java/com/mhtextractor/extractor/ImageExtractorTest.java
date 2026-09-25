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
        assertTrue(ex.getLastWarnings().isEmpty());
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
        assertTrue(ex.getLastWarnings().isEmpty());
    }

    // (3.3) 独立成词的广告标识符仍被过滤，且删除记入 warnings
    @Test
    void adImagesAreFilteredAndRecordedInWarnings() {
        ImageExtractor ex = noRegion(HTML, List.of(img("ad_123"), img("images/ads/banner-ad.png"), img("photo1")));
        List<ImageExtractor.ImageData> out = ex.extractImages();
        assertEquals(1, out.size());
        assertEquals("photo1", out.get(0).getFilename());
        assertEquals(2, ex.getLastWarnings().size());
        assertTrue(ex.getLastWarnings().get(0).contains("ad_123"));
    }

    // (c) 区域 HTML 解析失败：中止区域限定，不静默放行区域外图片，且有 warning
    @Test
    void regionParseFailureAbortsInsteadOfAllowingEverything() {
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
        assertTrue(out.isEmpty());
        assertEquals(1, ex.getLastWarnings().size());
        assertTrue(ex.getLastWarnings().get(0).contains("解析失败"));
    }

    // 区域内确实没有 <img>（解析正常、引用为空）：不得回退为提取全部
    @Test
    void regionWithNoImagesDoesNotFallBackToAll() {
        String html = "<html><body><div>START<p>no images here</p>END</div></body></html>";
        ImageExtractor ex = region(html, List.of(img("cid1"), img("cid2")), "START", "END");
        List<ImageExtractor.ImageData> out = ex.extractImages();
        assertTrue(out.isEmpty());
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
        assertTrue(ex.getLastWarnings().isEmpty());
    }
}
