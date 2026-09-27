package com.mhtextractor.extractor;

import com.mhtextractor.core.MimeParser;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.util.HtmlUtils;
import com.mhtextractor.util.SanitizeUtils;
import lombok.Data;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * 图片提取器
 * 基于 MimeParser 返回的 cidMap 直接获取图片数据
 */
public class ImageExtractor extends BaseExtractor {
    private static final Logger logger = LoggerFactory.getLogger(ImageExtractor.class);

    private static final List<String> AD_KEYWORDS = List.of(
            "ad", "banner", "advert", "ads", "advertisement",
            "promo", "sponsored", "popup", "click", "banner-ad"
    );

    private final String nameLimitStart;
    private final String nameLimitEnd;
    private final String contentLimitStart;
    private final String contentLimitEnd;

    public ImageExtractor(MimeParser.ParsedMht parsedMht,
                          String nameLimitStart, String nameLimitEnd,
                          String contentLimitStart, String contentLimitEnd) {
        super(parsedMht);
        this.nameLimitStart = nameLimitStart;
        this.nameLimitEnd = nameLimitEnd;
        this.contentLimitStart = contentLimitStart;
        this.contentLimitEnd = contentLimitEnd;
    }

    /**
     * 提取限定区域内的图片数据列表（已过滤广告）
     * 按照 HTML 中 <img> 标签的声明顺序返回，并保留原始文件名
     */
    public List<ImageData> extractImages() {
        return extractImages(null);
    }

    /**
     * 提取限定区域内的图片数据列表（已过滤广告）
     * 按照 HTML 中 <img> 标签的声明顺序返回，并保留原始文件名
     *
     * @param progressCallback 进度回调（0.0~1.0），可为 null
     */
    public List<ImageData> extractImages(Consumer<Double> progressCallback) {
        lastWarnings.clear();
        String html = parsedMht.getHtmlContent();
        if (html == null) {
            if (progressCallback != null) progressCallback.accept(1.0);
            return Collections.emptyList();
        }

        // 内容区域限定
        String targetHtml = extractContentArea(html);
        if (targetHtml == null || targetHtml.isEmpty()) {
            if (progressCallback != null) progressCallback.accept(0.3);
            return Collections.emptyList();
        }

        if (progressCallback != null) progressCallback.accept(0.2);

        // 从HTML中提取图片引用（按声明顺序）；null 表示区域 HTML 解析失败
        List<String> orderedImageRefs = extractImageReferencesInOrder(targetHtml);

        if (progressCallback != null) progressCallback.accept(0.4);

        // 如果没有设置区域限定，则使用所有图片（按 imageInfoList 顺序）
        boolean noRegionLimit = (contentLimitStart == null || contentLimitEnd == null);

        if (noRegionLimit) {
            // 没有区域限定：imageInfoList（MHT 声明顺序）+ HTML 内嵌 Data URI 图片。
            //
            // 修复（步骤 7）：原实现在 imageInfoList 为 null/空时**直接返回空列表**，
            // 于是「纯 Data URI 的 MHT」永远提取不到图片 —— 这类文件本就没有图片 part，
            // imageInfoList 必然为空，而 Data URI 提取恰好写在 extractAllImagesInOrder 内部，
            // 成了只在"有图片 part"时才可达的死代码。
            // 现在统一委托给 extractAllImagesInOrder：它内部先遍历 imageInfoList（空则跳过），
            // 再提取 Data URI。**注意不可在此处再调一次 extractDataUriImages**，否则会重复提取。
            return extractAllImagesInOrder(progressCallback);
        }

        // 有区域限定：解析失败则中止该文件的区域限定提取，不静默放行区域外图片
        if (orderedImageRefs == null) {
            String msg = I18n.get("extract.areaImageRefFailed");
            logger.warn(I18n.msg("log.imageextractor.01", msg));
            lastWarnings.add(msg);
            if (progressCallback != null) progressCallback.accept(1.0);
            return Collections.emptyList();
        }

        // 有区域限定：只保留 HTML 中出现在限定区域内的图片引用
        Set<String> allowedImageRefs = new HashSet<>(orderedImageRefs);
        List<ImageData> images = new ArrayList<>();
        Set<String> addedKeys = new HashSet<>();

        if (parsedMht.getImageInfoList() == null) {
            if (progressCallback != null) progressCallback.accept(1.0);
            return images;
        }

        List<MimeParser.ImageInfo> allImages = parsedMht.getImageInfoList();
        for (int i = 0; i < allImages.size(); i++) {
            MimeParser.ImageInfo info = allImages.get(i);
            if (isInfoAllowed(info, allowedImageRefs)) {
                String dedupKey = getDedupKey(info);
                if (dedupKey != null && !addedKeys.contains(dedupKey)) {
                    if (dedupKey != null) addedKeys.add(dedupKey);
                    String filename = extractOriginalFilename(info);
                    images.add(new ImageData(filename, info.getContentId(), info.getData()));
                }
            }
            // 周期性报告进度
            if (progressCallback != null && (i + 1) % 5 == 0) {
                double p = 0.5 + 0.4 * ((double) (i + 1) / allImages.size());
                progressCallback.accept(p);
            }
        }

        // 同时处理 Data URI 中的图片
        extractDataUriImages(targetHtml, images);

        if (progressCallback != null) progressCallback.accept(1.0);
        return images;
    }

    /**
     * 没有区域限定时，按 MHT 中声明顺序提取所有图片
     */
    private List<ImageData> extractAllImagesInOrder(Consumer<Double> progressCallback) {
        List<ImageData> images = new ArrayList<>();
        Set<String> addedKeys = new HashSet<>();

        if (parsedMht.getImageInfoList() == null) {
            return images;
        }

        List<MimeParser.ImageInfo> allImages = parsedMht.getImageInfoList();
        for (int i = 0; i < allImages.size(); i++) {
            MimeParser.ImageInfo info = allImages.get(i);
            String cid = info.getContentId();
            // 过滤广告图片（null/空标识符保留：无法判定为广告）
            if (isAdImage(cid)) {
                String msg = I18n.get("extract.adFiltered", "name", cid);
                logger.warn(I18n.msg("log.imageextractor.01", msg));
                lastWarnings.add(msg);
                continue;
            }

            String dedupKey = getDedupKey(info);
            if (dedupKey != null && !addedKeys.contains(dedupKey)) {
                if (dedupKey != null) addedKeys.add(dedupKey);
                String filename = extractOriginalFilename(info);
                images.add(new ImageData(filename, cid, info.getData()));
            }
            // 周期性报告进度
            if (progressCallback != null && (i + 1) % 5 == 0) {
                double p = 0.5 + 0.4 * ((double) (i + 1) / allImages.size());
                progressCallback.accept(p);
            }
        }

        // 同时处理 Data URI 中的图片
        String html = parsedMht.getHtmlContent();
        if (html != null) {
            extractDataUriImages(html, images);
        }

        return images;
    }

    /**
     * 从 ImageInfo 中提取原始文件名
     * 优先级：原始 filename > 从 Content-Location 提取 > 从 CID 推断
     */
    private String extractOriginalFilename(MimeParser.ImageInfo info) {
        // 1. 优先使用原始 filename
        if (info.getFilename() != null && !info.getFilename().isEmpty()) {
            return info.getFilename();
        }

        // 2. 从 Content-Location 提取
        if (info.getContentLocation() != null && !info.getContentLocation().isEmpty()) {
            String locFilename = SanitizeUtils.extractFilenameFromUrl(info.getContentLocation());
            if (locFilename != null && !locFilename.isEmpty()) {
                return SanitizeUtils.sanitize(locFilename);
            }
        }

        // 3. 从 CID 推断
        if (info.getContentId() != null && !info.getContentId().isEmpty()) {
            String clean = info.getContentId().replace("<", "").replace(">", "");
            return SanitizeUtils.sanitize(clean);
        }

        return "";
    }

    /**
     * 按顺序提取 HTML 中的图片引用
     *
     * @return 引用列表；解析失败返回 null（调用方须中止区域限定，不得静默放行）
     */
    List<String> extractImageReferencesInOrder(String html) {
        List<String> refs = new ArrayList<>();
        try {
            Document doc = Jsoup.parse(html);
            Elements imgs = doc.select("img[src]");
            for (var img : imgs) {
                String src = img.attr("src");
                if (!src.isEmpty()) {
                    refs.add(src);
                    if (src.startsWith("cid:")) refs.add(src.substring(4));
                    String dataCid = img.attr("data-cid");
                    if (!dataCid.isEmpty()) refs.add(dataCid);
                }
            }
        } catch (Exception e) {
            logger.warn(I18n.msg("log.imageextractor.03", e.getMessage()), e);
            return null;
        }
        return refs;
    }

    /**
     * 判断 ImageInfo 是否在允许的引用列表中
     */
    private boolean isInfoAllowed(MimeParser.ImageInfo info, Set<String> allowedRefs) {
        // 允许集为空 = 区域内确实无图片引用：任何区域外图片都不得放行
        // （此前对空集合一律返回 true，导致区域限定整体失效）

        // 检查 Content-ID
        String cid = info.getContentId();
        if (cid != null && allowedRefs.contains(cid)) return true;

        // 检查 Content-Location
        String loc = info.getContentLocation();
        if (loc != null && allowedRefs.contains(loc)) return true;

        // 检查 filename
        String fname = info.getFilename();
        if (fname != null && allowedRefs.contains(fname)) return true;

        // 模糊匹配
        for (String ref : allowedRefs) {
            String lowerRef = ref.toLowerCase();
            if (cid != null && (lowerRef.contains(cid.toLowerCase()) || cid.toLowerCase().contains(lowerRef)))
                return true;
            if (loc != null && (lowerRef.contains(loc.toLowerCase()) || loc.toLowerCase().contains(lowerRef)))
                return true;
            if (fname != null && (lowerRef.contains(fname.toLowerCase()) || fname.toLowerCase().contains(lowerRef)))
                return true;
        }
        return false;
    }

    /**
     * 获取去重键
     */
    private String getDedupKey(MimeParser.ImageInfo info) {
        if (info.getContentId() != null && !info.getContentId().isEmpty()) return "cid:" + info.getContentId();
        if (info.getContentLocation() != null && !info.getContentLocation().isEmpty()) return "loc:" + info.getContentLocation();
        return null;
    }

    /**
     * 从HTML中提取标题
     */
    public String extractTitle() {
        String html = parsedMht.getHtmlContent();
        if (html == null || nameLimitStart == null || nameLimitEnd == null) return "";

        int startIdx = html.indexOf(nameLimitStart);
        int endIdx = html.indexOf(nameLimitEnd, startIdx + 1);
        if (startIdx == -1 || endIdx == -1) return "";

        return html.substring(startIdx + nameLimitStart.length(), endIdx)
                .trim().replaceAll("\\s+", " ");
    }

    private String extractContentArea(String html) {
        if (contentLimitStart == null || contentLimitEnd == null
                || contentLimitStart.isEmpty() || contentLimitEnd.isEmpty()) return html;
        String result = HtmlUtils.extractRegion(html, contentLimitStart, contentLimitEnd, lastWarnings);
        if (result == null) {
            logger.warn(I18n.msg("log.imageextractor.01", lastWarnings.getLast()));
        }
        return result;
    }

    /**
     * 广告关键词词边界匹配：关键词须独立成词（前后均为非字母数字字符或串首/串尾）。
     * 仅凭子串匹配会把 header@…、download…、shadow… 乃至十六进制 CID 段（如 01D8AD12）误判为广告。
     * null/空标识符无法判定为广告，返回 false（保留）。
     */
    private boolean isAdImage(String urlOrCid) {
        if (urlOrCid == null || urlOrCid.isEmpty()) return false;
        String lower = urlOrCid.toLowerCase();
        for (String keyword : AD_KEYWORDS) {
            int idx = lower.indexOf(keyword);
            while (idx >= 0) {
                boolean beforeOk = idx == 0 || !isWordChar(lower.charAt(idx - 1));
                int end = idx + keyword.length();
                boolean afterOk = end >= lower.length() || !isWordChar(lower.charAt(end));
                if (beforeOk && afterOk) return true;
                idx = lower.indexOf(keyword, idx + 1);
            }
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private void extractDataUriImages(String html, List<ImageData> images) {
        try {
            Document doc = Jsoup.parse(html);
            Elements imgs = doc.select("img[src^=data:]");
            for (var img : imgs) {
                String src = img.attr("src");
                if (!src.isEmpty()) {
                    byte[] data = SanitizeUtils.decodeDataUri(src);
                    if (data != null) {
                        String ext = SanitizeUtils.getExtensionFromUrl(src);
                        ext = SanitizeUtils.detectExtensionByMagicNumber(data, ext);
                        images.add(new ImageData("image_" + images.size() + "." + ext, null, data));
                    }
                }
            }
        } catch (Exception e) {
            logger.debug(I18n.msg("log.imageextractor.05", e.getMessage()), e);
        }
    }

    @Data
    public static class ImageData {
        private String filename;
        private String contentId;
        private byte[] data;

        public ImageData(String filename, String contentId, byte[] data) {
            this.filename = filename;
            this.contentId = contentId;
            this.data = data;
        }
    }
}
