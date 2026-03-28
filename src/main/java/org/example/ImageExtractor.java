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
        return AD_KEYWORDS.stream().noneMatch(lowerUrl::contains);
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
                System.out.println("[DEBUG] Part " + i + " - Content-Type: " + contentType);

                if (contentType.startsWith("image/")) {
                    // 尝试多种方式获取文件名
                    String filename = getFilenameFromPart(part);

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

                        // 尝试获取 Content-Location
                        String[] locations = ((MimePart) part).getHeader("Content-Location");
                        if (locations != null && locations.length > 0) {
                            System.out.println("[DEBUG] Content-Location: " + locations[0]);
                            // 如果 filename 为空，尝试使用 Content-Location
                            if (filename == null || filename.isEmpty()) {
                                filename = locations[0];
                                System.out.println("[DEBUG] 从 Content-Location 获取到：" + filename);
                            }
                        }
                    }

                    // 如果从 Content-Location 获取到的是 URL，只保留文件名部分
                    if (filename != null && (filename.startsWith("http://") || filename.startsWith("https://"))) {
                        // 提取 URL 中的文件名
                        int lastSlash = filename.lastIndexOf('/');
                        if (lastSlash != -1 && lastSlash < filename.length() - 1) {
                            String urlFilename = filename.substring(lastSlash + 1);
                            System.out.println("[DEBUG] 从 URL 提取文件名：" + urlFilename);
                            filename = urlFilename;
                        }
                    }

                    System.out.println("[DEBUG] Part " + i + " - Filename: " + filename + ", CID: " + contentId);

                    try (InputStream is = part.getInputStream()) {
                        byte[] imageData = is.readAllBytes();

                        if (contentId != null && !contentId.isEmpty()) {
                            cidImageMap.put(contentId, imageData);
                        }

                        // 即使 filename 为 null，也要保存 imageData 和 contentId
                        images.add(new ImageData(filename, contentId, imageData));
                    }
                } else if (part.getContent() instanceof Multipart) {
                    extractImagesFromMultipart(part.getContent(), images);
                }
            }
        }
    }

    /**
     * 从 MIME Part 中提取文件名（支持多种头部）
     * @param part MIME 部分
     * @return 文件名，如果无法获取则返回 null
     */
    private String getFilenameFromPart(BodyPart part) throws MessagingException {
        // 方法 1: 尝试从 Content-Disposition 获取 filename
        String disposition = part.getDisposition();
        if (disposition != null) {
            // disposition 格式类似：attachment; filename="image001.jpg"
            int filenameIndex = disposition.toLowerCase().indexOf("filename");
            if (filenameIndex != -1) {
                int equalsIndex = disposition.indexOf('=', filenameIndex);
                if (equalsIndex != -1 && equalsIndex < disposition.length() - 1) {
                    String filename = disposition.substring(equalsIndex + 1).trim();
                    // 去掉引号
                    if (filename.startsWith("\"") && filename.endsWith("\"")) {
                        filename = filename.substring(1, filename.length() - 1);
                    }
                    if (!filename.isEmpty()) {
                        System.out.println("[DEBUG] 从 Content-Disposition 获取到文件名：" + filename);
                        return filename;
                    }
                }
            }
        }

        // 方法 2: 如果是 MimePart，尝试从 Content-Type 的 name 参数获取
        if (part instanceof MimePart) {
            String contentType = part.getContentType();
            // contentType 格式类似：image/jpeg; name="image001.jpg"
            int nameIndex = contentType.toLowerCase().indexOf("name");
            if (nameIndex != -1) {
                int equalsIndex = contentType.indexOf('=', nameIndex);
                if (equalsIndex != -1 && equalsIndex < contentType.length() - 1) {
                    String name = contentType.substring(equalsIndex + 1).trim();
                    // 去掉引号
                    if (name.startsWith("\"") && name.endsWith("\"")) {
                        name = name.substring(1, name.length() - 1);
                    }
                    if (!name.isEmpty()) {
                        System.out.println("[DEBUG] 从 Content-Type 获取到文件名：" + name);
                        return name;
                    }
                }
            }
        }

        // 方法 3: 使用 getFileName() 作为后备
        String fallbackName = part.getFileName();
        System.out.println("[DEBUG] getFileName() 返回：" + fallbackName);
        System.out.println("[DEBUG] Content-Type: " + part.getContentType());
        System.out.println("[DEBUG] Disposition: " + part.getDisposition());
        return fallbackName;
    }

    /**
     * 获取 CID 图片数据映射表
     * @return CID 到图片字节的映射
     */
    public Map<String, byte[]> getCidImageMap() {
        return cidImageMap;
    }

    /**
     * 从 MHT 文件中提取图片，并根据 HTML 中的区域限定进行过滤
     * @param mhtPath MHT 文件路径
     * @return 过滤后的图片数据列表
     */
    public List<ImageData> extractImagesFromMhtWithRegionFilter(String mhtPath) {
        // 先提取所有图片
        List<ImageData> allImages = extractImagesFromMht(mhtPath);

        // 如果没有设置区域限定，返回所有图片
        if (contentLimitStart == null || contentLimitEnd == null) {
            return allImages;
        }

        // 提取限定区域的 HTML
        String targetHtml = extractContentLimitArea();
        if (targetHtml == null || targetHtml.isEmpty()) {
            return new ArrayList<>();
        }

        // 从限定区域中提取允许的图片引用
        Set<String> allowedImageRefs = extractImageReferencesFromHtml(targetHtml);

        // 过滤图片，只保留限定区域内的引用
        List<ImageData> filteredImages = new ArrayList<>();
        for (ImageData image : allImages) {
            if (isImageInAllowedList(image, allowedImageRefs)) {
                filteredImages.add(image);
            }
        }

        return filteredImages;
    }

    /**
     * 从 HTML 中提取所有图片引用（src、cid 等）
     * @param html HTML 内容
     * @return 图片引用集合
     */
    private Set<String> extractImageReferencesFromHtml(String html) {
        Set<String> imageRefs = new HashSet<>();
        try {
            Document doc = Jsoup.parse(html);
            Elements imgElements = doc.select("img[src]");

            for (var img : imgElements) {
                String src = img.attr("src");
                if (src != null && !src.isEmpty()) {
                    imageRefs.add(src);

                    // 处理 cid: 引用
                    if (src.startsWith("cid:")) {
                        imageRefs.add(src.substring(4));
                    }

                    // 处理 data-cid 属性
                    String dataCid = img.attr("data-cid");
                    if (dataCid != null && !dataCid.isEmpty()) {
                        imageRefs.add(dataCid);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("解析 HTML 图片引用失败：" + e.getMessage());
        }
        return imageRefs;
    }

    /**
     * 判断图片是否在允许的引用列表中
     * @param image 图片数据
     * @param allowedRefs 允许的引用集合
     * @return true 如果图片在允许列表中
     */
    private boolean isImageInAllowedList(ImageData image, Set<String> allowedRefs) {
        // 如果允许列表为空，说明没有限定，返回 true
        if (allowedRefs.isEmpty()) {
            return true;
        }

        // 检查 Content-ID
        if (image.contentId != null && !image.contentId.isEmpty()) {
            if (allowedRefs.contains(image.contentId)) {
                return true;
            }
        }

        // 检查文件名
        if (image.filename != null && !image.filename.isEmpty()) {
            if (allowedRefs.contains(image.filename)) {
                return true;
            }

            // 模糊匹配（防止文件名不完全一致）
            String lowerFilename = image.filename.toLowerCase();
            for (String ref : allowedRefs) {
                if (ref.toLowerCase().contains(lowerFilename) ||
                        lowerFilename.contains(ref.toLowerCase())) {
                    return true;
                }
            }
        }

        return false;
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