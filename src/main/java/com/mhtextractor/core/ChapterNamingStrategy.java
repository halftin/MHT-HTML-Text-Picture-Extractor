package com.mhtextractor.core;

/**
 * 章节命名策略（会话 15C，架构设计 §8.1）：按 locale 生成章节名。
 *
 * <p><b>契约</b>：输入为 0-based 序号（0 = 第 1 章 / Chapter 1），与
 * {@link ChapterGenerator#generateChapterName(int)} 的既有语义一致。</p>
 */
public interface ChapterNamingStrategy {

    /**
     * 生成章节名。
     *
     * @param index 0-based 章节序号
     * @return 章节名
     * @throws IllegalArgumentException index 为负
     */
    String generateChapterName(int index);

    /**
     * 中文策略：会话 15C 前 ChapterGenerator 的逻辑原样迁移
     * （含 DIGITS/UNITS/零 规则；章号 &gt; 9999 回退阿拉伯数字）。
     */
    final class ChineseChapterNamingStrategy implements ChapterNamingStrategy {

        private static final String[] DIGITS = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        private static final String[] UNITS = {"", "十", "百", "千"};

        @Override
        public String generateChapterName(int index) {
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
}
