package com.mhtextractor.core;

import com.mhtextractor.util.EncodingUtils;
import com.mhtextractor.util.HtmlUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容提取协调器
 * 基于 MimeParser 解析结果，提取文本和章节标题
 */
public class ContentExtractor {
    private static final Logger logger = LoggerFactory.getLogger(ContentExtractor.class);

    private final List<String> lastWarnings = new ArrayList<>();

    public List<String> getLastWarnings() {
        return lastWarnings;
    }

    /**
     * 从HTML内容中提取纯文本
     */
    public String extractText(String htmlContent, boolean autoFixEncoding,
                              String startMarker, String endMarker, boolean useRegex) {
        lastWarnings.clear();
        if (htmlContent == null || htmlContent.isEmpty()) return null;

        // 检测编码并修复（仅在MimeParser未正确解码时生效）
        if (!EncodingUtils.containsGarbled(htmlContent)) {
            String detectedCharset = EncodingUtils.detectCharsetFromHtml(htmlContent);
            if (detectedCharset != null) {
                try {
                    byte[] htmlBytes = htmlContent.getBytes("ISO-8859-1");
                    String reDecoded = new String(htmlBytes, detectedCharset);
                    if (!EncodingUtils.containsGarbled(reDecoded) && EncodingUtils.containsChinese(reDecoded)) {
                        htmlContent = reDecoded;
                        logger.info("编码修复成功，使用 {} 重新解码", detectedCharset);
                    }
                } catch (Exception e) {
                    logger.warn("编码修复失败：{}", e.getMessage(), e);
                }
            }
        }

        // 内容区域限定
        if (startMarker != null && !startMarker.trim().isEmpty()
                && endMarker != null && !endMarker.trim().isEmpty()) {
            htmlContent = extractByMarker(htmlContent, startMarker.trim(), endMarker.trim(), useRegex);
        }

        // HTML → 纯文本
        Document doc = Jsoup.parse(htmlContent);
        String text = doc.body() != null ? doc.body().wholeText() : "";

        // 乱码修复
        if (autoFixEncoding && text != null) {
            text = EncodingUtils.smartFix(text);
        }
        return text;
    }

    /**
     * 从HTML源码中提取章节标题
     */
    public String extractChapterTitle(String htmlContent,
                                      String startMarker, String endMarker, boolean useRegex) {
        lastWarnings.clear();
        if (htmlContent == null || htmlContent.isEmpty()) return null;

        String patternStr = useRegex
                ? startMarker + "(.*?)" + endMarker
                : Pattern.quote(startMarker) + "(.*?)" + Pattern.quote(endMarker);

        try {
            Pattern pattern = Pattern.compile(patternStr, Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(htmlContent);
            if (matcher.find()) {
                String title = matcher.group(1).trim()
                        .replaceAll("<[^>]*>", "")
                        .replaceAll("\\s+", " ");
                return title.isEmpty() ? null : title;
            }
        } catch (Exception e) {
            logger.warn("提取章节标题失败：{}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * 根据起始/结束标记截取HTML内容
     */
    private String extractByMarker(String html, String startMarker, String endMarker, boolean useRegex) {
        try {
            if (useRegex) {
                Pattern pattern = Pattern.compile(
                        startMarker + "(.*?)" + endMarker,
                        Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(html);
                if (matcher.find()) {
                    return matcher.group(1);
                } else {
                    String msg = String.format("内容限定标记未找到 - 起始: %s, 结束: %s", startMarker, endMarker);
                    logger.warn("[警告] {}", msg);
                    lastWarnings.add(msg);
                }
            } else {
                String result = HtmlUtils.extractRegion(html, startMarker, endMarker, lastWarnings);
                if (result != null) {
                    return result;
                }
                return html;
            }
        } catch (Exception e) {
            String msg = String.format("内容截取失败: %s", e.getMessage());
            logger.warn(msg, e);
            lastWarnings.add(msg);
        }
        return html;
    }
}
