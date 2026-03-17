package org.example;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 增强版编码转换工具类
 * 支持多种编码检测和修复
 */
public class EncodingUtils {

    // 常见中文编码组合
    private static final String[] COMMONCharsets = {
            "UTF-8", "GBK", "GB2312", "GB18030", "Big5", "ISO-8859-1", "Windows-1252"
    };

    /**
     * 修复UTF-8被误读为ISO-8859-1导致的乱码
     */
    public static String fixUtf8Garbled(String garbledText) {
        if (garbledText == null || garbledText.isEmpty()) {
            return garbledText;
        }

        try {
            byte[] bytes = garbledText.getBytes(StandardCharsets.ISO_8859_1);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return garbledText;
        }
    }

    /**
     * 修复GBK被误读为UTF-8导致的乱码
     */
    public static String fixGbkGarbled(String garbledText) {
        if (garbledText == null || garbledText.isEmpty()) {
            return garbledText;
        }

        try {
            byte[] bytes = garbledText.getBytes(StandardCharsets.UTF_8);
            return new String(bytes, "GBK");
        } catch (Exception e) {
            return garbledText;
        }
    }

    /**
     * 尝试多种编码组合修复乱码
     * @param garbledText 乱码文本
     * @return 修复后的文本
     */
    public static String tryFixGarbled(String garbledText) {
        if (garbledText == null || garbledText.isEmpty()) {
            return garbledText;
        }

        // 编码修复组合：[错误编码, 正确编码]
        String[][] fixPairs = {
                {"ISO-8859-1", "UTF-8"},      // UTF-8被误读为ISO-8859-1
                {"UTF-8", "GBK"},             // GBK被误读为UTF-8
                {"UTF-8", "GB2312"},          // GB2312被误读为UTF-8
                {"ISO-8859-1", "GBK"},        // GBK被误读为ISO-8859-1
                {"Windows-1252", "UTF-8"},    // UTF-8被误读为Windows-1252
        };

        for (String[] pair : fixPairs) {
            try {
                byte[] bytes = garbledText.getBytes(pair[0]);
                String result = new String(bytes, pair[1]);
                if (containsChinese(result) && !containsGarbled(result)) {
                    return result;
                }
            } catch (Exception e) {
                // 尝试下一个组合
            }
        }

        return garbledText;
    }

    /**
     * 从HTML内容中检测编码（通过meta标签）
     * @param html HTML内容
     * @return 检测到的编码，未检测到返回null
     */
    public static String detectCharsetFromHtml(String html) {
        if (html == null || html.isEmpty()) {
            return null;
        }

        // 匹配 <meta charset="UTF-8">
        Pattern pattern1 = Pattern.compile("<meta\\s+charset=[\"']?([^\"'>\\s]+)[\"']?", Pattern.CASE_INSENSITIVE);
        Matcher matcher1 = pattern1.matcher(html);
        if (matcher1.find()) {
            return matcher1.group(1).trim().toUpperCase();
        }

        // 匹配 <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
        Pattern pattern2 = Pattern.compile("charset\\s*=\\s*[\"']?([^\"'>\\s]+)[\"']?", Pattern.CASE_INSENSITIVE);
        Matcher matcher2 = pattern2.matcher(html);
        if (matcher2.find()) {
            return matcher2.group(1).trim().toUpperCase();
        }

        return null;
    }

    /**
     * 智能修复文本编码（自动检测并尝试多种方案）
     * @param text 可能包含乱码的文本
     * @return 修复后的文本
     */
    public static String smartFix(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // 如果没有乱码，直接返回
        if (!containsGarbled(text)) {
            return text;
        }

        // 尝试各种修复方案
        String[] results = {
                text,  // 原始文本
                fixUtf8Garbled(text),
                fixGbkGarbled(text),
                tryFixGarbled(text)
        };

        // 选择最好的结果（包含中文且乱码最少）
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
     * 计算文本质量分数（用于选择最佳修复结果）
     */
    private static int calculateTextQuality(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int score = 0;
        int chineseCount = 0;
        int garbledCount = 0;

        for (char c : text.toCharArray()) {
            // 中文字符加分
            if (c >= '\u4e00' && c <= '\u9fff') {
                chineseCount++;
                score += 10;
            }
            // 常见乱码字符减分（使用Unicode转义序列）
            else if (c == '\uFFFD' || c == '\u00BF' || c == '\u0081') {
                garbledCount++;
                score -= 50;
            }
            // 正常ASCII字符
            else if (c < 128) {
                score += 1;
            }
        }

        // 如果有中文且乱码少，给予额外加分
        if (chineseCount > 0 && garbledCount == 0) {
            score += 100;
        }

        return score;
    }

    /**
     * 判断字符串是否包含中文字符
     */
    public static boolean containsChinese(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断字符串是否包含乱码字符
     */
    public static boolean containsGarbled(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (char c : text.toCharArray()) {
            // 使用Unicode转义序列表示特殊字符
            if (c == '\uFFFD' || c == '\u00BF' || c == '¿') {
                return true;
            }
        }
        return false;
    }
}