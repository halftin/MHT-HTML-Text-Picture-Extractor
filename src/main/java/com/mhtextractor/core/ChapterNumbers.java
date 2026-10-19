package com.mhtextractor.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/** Locale-independent chapter parsing. Zero means unknown in ordering keys. */
public final class ChapterNumbers {
    private static final Pattern CHAPTER = Pattern.compile(
            "第\\s*([0-9零〇一二三四五六七八九十百千两]+)\\s*章|\\bChapter\\s+([0-9]+)(?![0-9])",
            Pattern.CASE_INSENSITIVE);
    private ChapterNumbers() {}

    public static OptionalInt parse(String value) {
        if (value == null) return OptionalInt.empty();
        var matcher = CHAPTER.matcher(value);
        if (!matcher.find()) return OptionalInt.empty();
        String number = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
        try {
            int result;
            if (number.matches("[0-9]+")) result = Integer.parseInt(number);
            else {
                result = 0;
                int digit = 0, lastUnit = 10000;
                boolean previousDigit = false;
                for (int i = 0; i < number.length(); i++) {
                    char c = number.charAt(i);
                    int unit = switch (c) { case '十' -> 10; case '百' -> 100; case '千' -> 1000; default -> 0; };
                    if (unit > 0) {
                        if (unit >= lastUnit) return OptionalInt.empty();
                        result += (digit == 0 ? 1 : digit) * unit;
                        digit = 0; lastUnit = unit; previousDigit = false;
                    } else {
                        int d = "零一二三四五六七八九".indexOf(c == '〇' ? '零' : c == '两' ? '二' : c);
                        if (d < 0 || (previousDigit && digit != 0)) return OptionalInt.empty();
                        digit = d; previousDigit = true;
                    }
                }
                result += digit;
            }
            return result > 0 && (number.matches("[0-9]+") || result <= 9999) ? OptionalInt.of(result) : OptionalInt.empty();
        } catch (NumberFormatException ignored) { return OptionalInt.empty(); }
    }

    public static int resolve(String title, String fileName) {
        return parse(title).orElseGet(() -> parse(fileName).orElse(0));
    }

    public static String stripPrefix(String title) {
        if (title == null) return "";
        String stripped = title.strip();
        var match = CHAPTER.matcher(stripped);
        return match.find() && match.start() == 0 ? stripped.substring(match.end()).strip() : stripped;
    }

    /** Known chapters first; ties and the unknown tail retain import order. Never mutates the input. */
    public static <T> List<T> stableOrder(List<T> items, ToIntFunction<T> number) {
        var ordered = new ArrayList<>(items);
        ordered.sort(Comparator.comparingInt(item -> {
            int n = number.applyAsInt(item);
            return n > 0 ? n : Integer.MAX_VALUE;
        }));
        return ordered;
    }
}
