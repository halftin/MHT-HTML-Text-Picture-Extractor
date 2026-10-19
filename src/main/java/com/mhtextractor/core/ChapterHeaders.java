package com.mhtextractor.core;

import com.mhtextractor.util.SourceKinds;

/** Shared by output and preview; ordinal is zero based and used only when no chapter is known. */
public final class ChapterHeaders {
    private ChapterHeaders() {}

    public static String build(String extractedTitle, String fileName, int ordinal,
                               boolean addNumber, boolean enableTitle) {
        String title = extractedTitle == null ? "" : extractedTitle.strip();
        int number = ChapterNumbers.resolve(title, fileName);
        if (title.isEmpty() && enableTitle && fileName != null) {
            title = ChapterNumbers.stripPrefix(SourceKinds.stripKnownExtension(fileName).split("_", 2)[0]);
        }
        if (addNumber) {
            String header = ChapterGenerator.generateChapterName(number > 0 ? number - 1 : ordinal);
            title = ChapterNumbers.stripPrefix(title);
            return title.isEmpty() ? header : header + " " + title;
        }
        return title.isEmpty() ? null : title;
    }
}
