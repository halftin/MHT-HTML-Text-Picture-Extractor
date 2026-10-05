package com.mhtextractor.processor;

/**
 * 每文件提取覆盖项（预览窗口逐文件调整的收集结果）
 *
 * @param chapterEnabled 是否输出章节头（原字段）
 * @param titleOverride  用户编辑的提取标题；null/空白 = 使用提取值
 */
public record PerFileOverrides(boolean chapterEnabled, String titleOverride) {

    public static final PerFileOverrides DEFAULTS = new PerFileOverrides(true, null);

    /**
     * 单参兼容构造（既有调用点/测试仍用）：只指定章节开关，不设置标题覆盖。
     */
    public PerFileOverrides(boolean chapterEnabled) {
        this(chapterEnabled, null);
    }

    /** 是否有用户编辑的标题覆盖（null / 纯空白 = 没有）。 */
    public boolean hasTitleOverride() {
        return titleOverride != null && !titleOverride.trim().isEmpty();
    }
}
