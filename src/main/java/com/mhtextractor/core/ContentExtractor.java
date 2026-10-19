package com.mhtextractor.core;

import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.ExtractWarning;
import com.mhtextractor.util.EncodingUtils;
import com.mhtextractor.util.HtmlUtils;
import lombok.Getter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容提取协调器
 * 基于 MimeParser 解析结果，提取文本和章节标题
 */
@Getter
public class ContentExtractor {
    private static final Logger logger = LoggerFactory.getLogger(ContentExtractor.class);

    private final List<ExtractWarning> lastWarnings = new ArrayList<>();

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
                    byte[] htmlBytes = htmlContent.getBytes(StandardCharsets.ISO_8859_1);
                    String reDecoded = new String(htmlBytes, detectedCharset);
                    if (!EncodingUtils.containsGarbled(reDecoded) && EncodingUtils.containsChinese(reDecoded)) {
                        htmlContent = reDecoded;
                        logger.info(I18n.msg("log.contentextractor.01", detectedCharset));
                    }
                } catch (Exception e) {
                    logger.warn(I18n.msg("log.contentextractor.02", e.getMessage()), e);
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
        String text = BlockTextBuilder.build(doc.body());

        // 乱码修复
        if (autoFixEncoding) {
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
            logger.warn(I18n.msg("log.contentextractor.03", e.getMessage()), e);
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
                    ExtractWarning warning = new ExtractWarning("core.areaMarkersNotFound",
                            Map.of("start", startMarker, "end", endMarker));
                    logger.warn(I18n.msg("log.contentextractor.04", I18n.render(warning)));
                    lastWarnings.add(warning);
                }
            } else {
                String result = HtmlUtils.extractRegion(html, startMarker, endMarker, lastWarnings);
                if (result != null) {
                    return result;
                }
                return html;
            }
        } catch (Exception e) {
            ExtractWarning warning = new ExtractWarning("core.areaExtractFailed",
                    Map.of("reason", String.valueOf(e.getMessage())));
            logger.warn(I18n.render(warning), e);
            lastWarnings.add(warning);
        }
        return html;
    }
}
