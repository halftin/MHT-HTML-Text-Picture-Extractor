package com.mhtextractor.uicore;

/** Relative shares of the list's available file-name/source space. */
public record ListColumnWidths(double fileName, double source) {
    public static final ListColumnWidths DEFAULT = new ListColumnWidths(1, 1);

    public ListColumnWidths {
        if (!Double.isFinite(fileName) || !Double.isFinite(source) || fileName <= 0 || source <= 0
                || !Double.isFinite(fileName + source)) {
            throw new IllegalArgumentException("Column shares must be finite and positive");
        }
    }
}
