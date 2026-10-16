package com.mhtextractor.core;

/**
 * 章节序号生成器（中文全汉字，支持1-9999）
 */
public class ChapterGenerator {

    private static final String[] DIGITS = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
    private static final String[] UNITS = {"", "十", "百", "千"};

    /**
     * 生成章节名（0-based索引 → "第一章"、"第一百二十三章"）
     */
    public static String generateChapterName(int index) {
        if (index < 0) throw new IllegalArgumentException("Index must be non-negative");
        int number = index + 1;
        if (number > 9999) return "第" + number + "章";
        return "第" + toChineseNumber(number) + "章";
    }

    private static String toChineseNumber(int number) {
        if (number < 1 || number > 9999) return String.valueOf(number);

        String s = String.valueOf(number);
        StringBuilder result = new StringBuilder();
        int len = s.length();

        for (int i = 0; i < len; i++) {
            int digit = s.charAt(i) - '0';
            int unitIndex = len - 1 - i;

            if (digit == 0) {
                boolean hasNonZeroAfter = false;
                for (int j = i + 1; j < len; j++) {
                    if (s.charAt(j) != '0') { hasNonZeroAfter = true; break; }
                }
                if (hasNonZeroAfter && (result.isEmpty() || result.charAt(result.length() - 1) != '零')) {
                    result.append("零");
                }
            } else {
                if (unitIndex == 1 && digit == 1 && i == 0) {
                    result.append(UNITS[unitIndex]);
                } else {
                    result.append(DIGITS[digit]);
                    if (unitIndex > 0) result.append(UNITS[unitIndex]);
                }
            }
        }
        return result.toString();
    }
}
