package com.mhtextractor.core;

import com.mhtextractor.i18n.I18n;

/**
 * 章节命名策略（会话 15C，架构设计 §8.1）：按 locale 生成章节名。
 *
 * <p><b>契约</b>：输入为 0-based 序号（0 = 第 1 章 / Chapter 1），与
 * {@link ChapterGenerator#generateChapterName(int)} 的既有语义一致。</p>
 *
 * <p><b>文案来源</b>：数字表（{@code core.digit.*}）、前缀/后缀（{@code core.chapter.prefix} /
 * {@code core.chapter.suffix}）全部取自 bundle，本类不再内嵌中文数字字面量。
 * zh 下逐字等价于历史实现；非 zh locale 走 {@link WesternChapterNamingStrategy}。</p>
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
     * （含数字/单位/零 规则；章号 &gt; 9999 回退阿拉伯数字）。
     */
    final class ChineseChapterNamingStrategy implements ChapterNamingStrategy {

        /** 数字表（0 号位留空，与历史 DIGITS 一致）；值取 {@code core.digit.*} */
        private static final String[] DIGIT_KEYS = {
                null, "core.digit.one", "core.digit.two", "core.digit.three", "core.digit.four",
                "core.digit.five", "core.digit.six", "core.digit.seven", "core.digit.eight",
                "core.digit.nine"};
        /** 单位表（个/十/百/千）；值取 {@code core.digit.*} */
        private static final String[] UNIT_KEYS = {
                null, "core.digit.ten", "core.digit.hundred", "core.digit.thousand"};

        @Override
        public String generateChapterName(int index) {
            if (index < 0) throw new IllegalArgumentException("Index must be non-negative");
            int number = index + 1;
            String prefix = I18n.get("core.chapter.prefix");
            String suffix = I18n.get("core.chapter.suffix");
            if (number > 9999) return prefix + number + suffix;
            return prefix + toLocalizedNumber(number) + suffix;
        }

        /** 中文数字：与历史 {@code toChineseNumber} 逐字等价，仅把字面量换成 bundle 取值。 */
        private static String toLocalizedNumber(int number) {
            if (number < 1 || number > 9999) return String.valueOf(number);

            String s = String.valueOf(number);
            String zero = I18n.get("core.digit.zero");
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
                    if (hasNonZeroAfter && (result.isEmpty()
                            || !result.substring(result.length() - zero.length()).equals(zero))) {
                        result.append(zero);
                    }
                } else {
                    if (unitIndex == 1 && digit == 1 && i == 0) {
                        result.append(I18n.get(UNIT_KEYS[unitIndex]));
                    } else {
                        result.append(I18n.get(DIGIT_KEYS[digit]));
                        if (unitIndex > 0) result.append(I18n.get(UNIT_KEYS[unitIndex]));
                    }
                }
            }
            return result.toString();
        }
    }
}
