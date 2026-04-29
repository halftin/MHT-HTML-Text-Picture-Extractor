package com.mhtextractor.util;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 编码修复工具类
 */
public class EncodingUtils {
    private static final Logger logger = LoggerFactory.getLogger(EncodingUtils.class);

    /**
     * 修复UTF-8被误读为ISO-8859-1导致的乱码
     */
    public static String fixUtf8Garbled(String garbledText) {
        if (garbledText == null || garbledText.isEmpty()) return garbledText;
        try {
            byte[] bytes = garbledText.getBytes(StandardCharsets.ISO_8859_1);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.debug("UTF-8 修复失败：{}", e.getMessage(), e);
            return garbledText;
        }
    }

    /**
     * 修复GBK被误读为UTF-8导致的乱码
     */
    public static String fixGbkGarbled(String garbledText) {
        if (garbledText == null || garbledText.isEmpty()) return garbledText;
        try {
            byte[] bytes = garbledText.getBytes(StandardCharsets.UTF_8);
            return new String(bytes, "GBK");
        } catch (Exception e) {
            logger.debug("GBK 修复失败：{}", e.getMessage(), e);
            return garbledText;
        }
    }

    /**
     * 从HTML内容中检测编码（通过meta标签）
     */
    public static String detectCharsetFromHtml(String html) {
        if (html == null || html.isEmpty()) return null;
        Pattern pattern = Pattern.compile("charset\\s*=\\s*[\"']?([^\"'>\\s]+)[\"']?", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        if (matcher.find()) {
            return matcher.group(1).trim().toUpperCase();
        }
        return null;
    }

    /**
     * 智能修复文本编码
     * 尝试多种编码组合：原始、UTF-8修复、GBK修复、乱码兜底修复
     */
    public static String smartFix(String text) {
        if (text == null || text.isEmpty()) return text;
        if (!containsGarbled(text)) return text;

        String[] results = {
                text,
                fixUtf8Garbled(text),
                fixGbkGarbled(text),
                tryFixGarbled(text),
        };

        String bestResult = text;
        int bestScore = calculateTextQuality(text);
        for (String result : results) {
            int score = calculateTextQuality(result);
            if (score > bestScore) {
                bestScore = score;
                bestResult = result;
            }
        }
        return bestResult;
    }

    /**
     * 尝试多种错误编码组合来修复乱码
     * 适用于编码声明与实际编码不一致的情况
     */
    private static String tryFixGarbled(String garbledText) {
        if (garbledText == null || garbledText.isEmpty()) return garbledText;

        // 5种常见错误编码对的交叉尝试
        String[][] encodingPairs = {
                {"ISO-8859-1", "GBK"},
                {"UTF-8", "GBK"},
                {"GBK", "ISO-8859-1"},
                {"GBK", "UTF-8"},
                {"UTF-8", "ISO-8859-1"},
        };

        String bestResult = garbledText;
        int bestScore = calculateTextQuality(garbledText);

        for (String[] pair : encodingPairs) {
            try {
                byte[] bytes = garbledText.getBytes(pair[0]);
                String result = new String(bytes, pair[1]);
                int score = calculateTextQuality(result);
                if (score > bestScore) {
                    bestScore = score;
                    bestResult = result;
                }
            } catch (Exception e) {
                logger.debug("编码对 {}/{} 转换失败：{}", pair[0], pair[1], e.getMessage(), e);
            }
        }

        return bestResult;
    }

    public static boolean containsChinese(String text) {
        if (text == null || text.isEmpty()) return false;
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') return true;
        }
        return false;
    }

    public static boolean containsGarbled(String text) {
        if (text == null || text.isEmpty()) return false;
        for (char c : text.toCharArray()) {
            if (c == '\uFFFD' || c == '\u00BF' || c == '¿') return true;
        }
        return false;
    }

    private static int calculateTextQuality(String text) {
        if (text == null || text.isEmpty()) return 0;
        int score = 0;
        int chineseCount = 0;
        int garbledCount = 0;
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') {
                chineseCount++;
                score += 10;
            } else if (c == '\uFFFD' || c == '\u00BF' || c == '\u0081') {
                garbledCount++;
                score -= 50;
            } else if (c < 128) {
                score += 1;
            }
        }
        if (chineseCount > 0 && garbledCount == 0) score += 100;
        return score;
    }
}
