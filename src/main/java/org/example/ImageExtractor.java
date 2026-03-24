package org.example;


import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimePart;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 图片提取器，负责从 MHT 文件中提取指定区域的图片
 * 并自动跳过广告图片（基于关键词过滤）
 */
public class ImageExtractor {
    // 广告关键词列表（根据常见广告特征定义）
    private static final List<String> AD_KEYWORDS = Arrays.asList(
            "ad", "banner", "advert", "ads", "advertisement",
            "promo", "sponsored", "popup", "click", "banner-ad"
    );

    private String htmlContent;
    private String nameLimitStart;
    private String nameLimitEnd;
    private String contentLimitStart;
    private String contentLimitEnd;
    private Map<String, byte[]> cidImageMap;

    /**
     * 构造函数
     * @param htmlContent MHT文件解析后的 HTML 内容
     * @param nameLimitStart 标题限定起始标记
     * @param nameLimitEnd 标题限定结束标记
     * @param contentLimitStart 内容限定起始标记
     * @param contentLimitEnd 内容限定结束标记
     */
    public ImageExtractor(String htmlContent,
                          String nameLimitStart, String nameLimitEnd,
                          String contentLimitStart, String contentLimitEnd) {
        this.htmlContent = htmlContent;
        this.nameLimitStart = nameLimitStart;
        this.nameLimitEnd = nameLimitEnd;
        this.contentLimitStart = contentLimitStart;
        this.contentLimitEnd = contentLimitEnd;
        this.cidImageMap = new HashMap<>();
    }

    /**
     * 提取指定区域的图片列表（自动过滤广告图片）
     * @return 提取的图片 URL 列表
     */
    public List<String> extractImages() {
        // 1. 根据内容限定区域提取目标 HTML
        String targetHtml = extractContentLimitArea();
        if (targetHtml == null || targetHtml.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. 解析目标 HTML 获取所有图片
        Document doc = Jsoup.parse(targetHtml);
        Elements imgElements = doc.select("img[src]");

        // 3. 提取并过滤广告图片
        return imgElements.stream()
                .map(element -> element.attr("src"))
                .filter(this::isNotAdImage)
                .collect(Collectors.toList());
    }

    /**
     * 从 HTML 中提取指定内容限定区域
     * @return 指定区域的 HTML 字符串，若未找到返回 null
     */
    private String extractContentLimitArea() {
        if (contentLimitStart == null || contentLimitEnd == null) {
            return htmlContent;
        }

        int startIdx = htmlContent.indexOf(contentLimitStart);
        int endIdx = htmlContent.indexOf(contentLimitEnd, startIdx + 1);

        if (startIdx == -1 || endIdx == -1) {
            return null;
        }

        return htmlContent.substring(startIdx + contentLimitStart.length(), endIdx);
    }

    /**
     * 判断是否为广告图片
     * @param imageUrl 图片 URL
     * @return true 如果不是广告图片
     */
    private boolean isNotAdImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return false;
        }

        // 转为小写进行不敏感比较
        String lowerUrl = imageUrl.toLowerCase();

        // 检查是否包含任何广告关键词
        return !AD_KEYWORDS.stream().anyMatch(lowerUrl::contains);
    }

    /**
     * 从 HTML 中提取标题（用于文件夹/文件名）
     * @return 提取的标题，若未找到返回空字符串
     */
    public String extractTitle() {
        if (nameLimitStart == null || nameLimitEnd == null) {
            return "";
        }

        int startIdx = htmlContent.indexOf(nameLimitStart);
        int endIdx = htmlContent.indexOf(nameLimitEnd, startIdx + 1);

        if (startIdx == -1 || endIdx == -1) {
            return "";
        }

        return htmlContent.substring(startIdx + nameLimitStart.length(), endIdx)
                .trim()
                .replaceAll("\\s+", " "); // 替换多个空格为单个空格
    }
    /**
     * 从 MHT文件中提取所有图片（包括 MIME 部分的 base64 图片）
     * @param mhtPath MHT文件路径
     * @return 图片数据列表（每个元素包含 filename、contentId 和 imageData）
     */
    public List<ImageData> extractImagesFromMht(String mhtPath) {
        List<ImageData> images = new ArrayList<>();
        try {
            Properties props = new Properties();
            Session session = Session.getDefaultInstance(props);

            try (FileInputStream fis = new FileInputStream(mhtPath)) {
                MimeMessage message = new MimeMessage(session, fis);
                extractImagesFromMultipart(message.getContent(), images);
            }
        } catch (Exception e) {
            throw new RuntimeException("提取 MHT 图片失败", e);
        }
        return images;
    }

    /**
     * 递归从 Multipart 中提取图片
     */
    private void extractImagesFromMultipart(Object content, List<ImageData> images) throws Exception {
        if (content instanceof Multipart) {
            Multipart multipart = (Multipart) content;
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);

                String contentType = part.getContentType();
                if (contentType.startsWith("image/")) {
                    String filename = part.getFileName();

                    // 从 MimePart 中获取 Content-ID
                    String contentId = null;
                    if (part instanceof MimePart) {
                        String[] cids = ((MimePart) part).getHeader("Content-ID");
                        if (cids != null && cids.length > 0) {
                            contentId = cids[0];
                            // 去掉 <> 括号
                            if (contentId.startsWith("<") && contentId.endsWith(">")) {
                                contentId = contentId.substring(1, contentId.length() - 1);
                            }
                        }
                    }

                    try (InputStream is = part.getInputStream()) {
                        byte[] imageData = is.readAllBytes();

                        if (contentId != null && !contentId.isEmpty()) {
                            cidImageMap.put(contentId, imageData);
                        }

                        images.add(new ImageData(filename, contentId, imageData));
                    }
                } else if (part.getContent() instanceof Multipart) {
                    extractImagesFromMultipart(part.getContent(), images);
                }
            }
        }
    }

    /**
     * 获取 CID 图片数据映射表
     * @return CID 到图片字节的映射
     */
    public Map<String, byte[]> getCidImageMap() {
        return cidImageMap;
    }

    /**
     * 内部类：保存图片数据
     */
    public static class ImageData {
        public String filename;
        public String contentId;
        public byte[] imageData;

        public ImageData(String filename, String contentId, byte[] imageData) {
            this.filename = filename;
            this.contentId = contentId;
            this.imageData = imageData;
        }
    }
}