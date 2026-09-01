package com.mhtextractor.processor;

/**
 * 每文件提取覆盖项（预览窗口逐文件调整的收集结果）
 */
public record PerFileOverrides(boolean chapterEnabled) {

    public static final PerFileOverrides DEFAULTS = new PerFileOverrides(true);
}
