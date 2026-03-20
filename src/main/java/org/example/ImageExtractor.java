package org.example;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
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

    /**
     * 构造函数
     * @param htmlContent MHT 文件解析后的 HTML 内容
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
}