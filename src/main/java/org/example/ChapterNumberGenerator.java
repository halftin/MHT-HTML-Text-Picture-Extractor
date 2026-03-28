package org.example;

/**
 * 章节序号生成器（中文全汉字实现，支持1-9999）
 */
public class ChapterNumberGenerator {

    private static final String[] DIGITS = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
    private static final String[] UNITS = {"", "十", "百", "千"};

    /**
     * 生成指定索引的章节名（中文全汉字）
     * @param index 0-based文件索引（0=第一章）
     * @return 章节名字符串（如"第一章"、"第一百二十三章"）
     */
    public static String generateChapterName(int index) {
        if (index < 0) throw new IllegalArgumentException("Index must be non-negative");
        int number = index + 1; // 1-based

        // 如果超过9999，直接显示数字，避免复杂逻辑出错
        if (number > 9999) {
            return "第" + number + "章";
        }

        return "第" + toChineseNumber(number) + "章";
    }

    /**
     * 将数字转换为中文汉字（1-9999）
     * 使用字符串遍历法，严格处理“十”、“零”等特殊规则
     * @param number 1-9999的整数
     * @return 中文数字字符串
     */
    private static String toChineseNumber(int number) {
        if (number < 1 || number > 9999) {
            return String.valueOf(number);
        }

        String s = String.valueOf(number);
        StringBuilder result = new StringBuilder();
        int len = s.length();

        for (int i = 0; i < len; i++) {
            int digit = s.charAt(i) - '0';
            int unitIndex = len - 1 - i; // 计算单位位置：3=千，2=百，1=十，0=个

            if (digit == 0) {
                // 处理零的逻辑：
                // 只有当后面还有非零数字时，才添加“零”
                // 且避免连续添加多个“零”
                boolean hasNonZeroAfter = false;
                for (int j = i + 1; j < len; j++) {
                    if (s.charAt(j) != '0') {
                        hasNonZeroAfter = true;
                        break;
                    }
                }

                if (hasNonZeroAfter) {
                    // 如果结果最后一位还不是“零”，则添加
                    if (result.isEmpty() || result.charAt(result.length() - 1) != '零') {
                        result.append("零");
                    }
                }
            } else {
                // 处理非零数字
                // 特殊规则：如果是两位数，且十位是1，且这是第一位数字（即10-19），省略“一”
                // 例如：10 -> "十", 11 -> "十一", 15 -> "十五"
                if (unitIndex == 1 && digit == 1 && i == 0) {
                    result.append(UNITS[unitIndex]); // 只加“十”
                } else {
                    result.append(DIGITS[digit]); // 加数字
                    if (unitIndex > 0) {
                        result.append(UNITS[unitIndex]); // 加单位（个位不加单位）
                    }
                }
            }
        }

        return result.toString();
    }

}