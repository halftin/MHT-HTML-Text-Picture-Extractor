package com.mhtextractor.extractor;

import com.mhtextractor.core.ContentExtractor;
import com.mhtextractor.core.MimeParser;
import com.mhtextractor.util.EncodingUtils;
import lombok.Data;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 文本提取器
 * 基于 MimeParser 的解析结果进行文本提取
 */
public class TextExtractor {
    private static final Logger logger = LoggerFactory.getLogger(TextExtractor.class);

    private final MimeParser.ParsedMht parsedMht;

    public TextExtractor(MimeParser.ParsedMht parsedMht) {
        this.parsedMht = parsedMht;
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
        return contentExtractor.extractText(parsedMht.getHtmlContent(),
                autoFixEncoding, startMarker, endMarker, useRegex);
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
