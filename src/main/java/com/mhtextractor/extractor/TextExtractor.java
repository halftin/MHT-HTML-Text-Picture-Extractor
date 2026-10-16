package com.mhtextractor.extractor;

import com.mhtextractor.core.ContentExtractor;
import com.mhtextractor.core.MimeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文本提取器
 * 基于 MimeParser 的解析结果进行文本提取
 */
public class TextExtractor extends BaseExtractor {
    private static final Logger logger = LoggerFactory.getLogger(TextExtractor.class);

    public TextExtractor(MimeParser.ParsedMht parsedMht) {
        super(parsedMht);
    }

    /**
     * 提取纯文本
     */
    public String extractText(boolean autoFixEncoding,
                              String startMarker, String endMarker, boolean useRegex) {
        if (parsedMht.getHtmlContent() == null) {
            logger.warn("HTML内容为空，无法提取文本");
            return null;
        }
        ContentExtractor contentExtractor = new ContentExtractor();
        String text = contentExtractor.extractText(parsedMht.getHtmlContent(),
                autoFixEncoding, startMarker, endMarker, useRegex);
        lastWarnings.clear();
        lastWarnings.addAll(contentExtractor.getLastWarnings());
        return text;
    }

    /**
     * 提取章节标题
     */
    public String extractTitle(String startMarker, String endMarker, boolean useRegex) {
        if (parsedMht.getHtmlContent() == null) return null;
        ContentExtractor contentExtractor = new ContentExtractor();
        return contentExtractor.extractChapterTitle(parsedMht.getHtmlContent(),
                startMarker, endMarker, useRegex);
    }

}
