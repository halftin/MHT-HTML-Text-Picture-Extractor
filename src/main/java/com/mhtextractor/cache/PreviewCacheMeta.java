package com.mhtextractor.cache;

import java.util.List;

/**
 * 预览缓存元数据（需求 2 §2.5 schema / P13）：存图片字节 + 来源标识 + 检测扩展名 + 警告，
 * 不存最终输出文件名（最终命名依赖单元上下文，必须提取期重算）。
 * 失效判定只看本对象即可得出（schema 自洽性原则）：matches 覆盖 formatVersion + paramFingerprint + source 三项。
 */
public record PreviewCacheMeta(int formatVersion, Source source, String paramFingerprint,
                               boolean imageMode, List<ImageEntry> images, List<String> warnings) {

    /** 缓存格式版本：meta.json 结构或内容语义变化时递增，旧缓存自然失效（对齐 HtmlExportBuilder:40 的既有约定） */
    public static final int FORMAT_VERSION = 1;

    /** MHT 源文件的三要素（P9）：绝对路径、大小、修改时间（epoch millis） */
    public record Source(String absolutePath, long size, long mtime) {}

    /**
     * 单张缓存图片的元数据。
     *
     * @param file         相对缓存目录的图片文件路径（如 images/0001.png），字节本体在磁盘上
     * @param sourceId     来源标识：cid:xxx / location:xxx / datauri / 原始文件名（可能为 null）
     * @param originalName 原始文件名（可能为 null）
     * @param detectedExt  写入时按魔数检测到的扩展名（不含点；最终输出命名不依赖它）
     */
    public record ImageEntry(String file, String sourceId, String originalName,
                             String detectedExt, List<String> warnings) {
        public ImageEntry {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public PreviewCacheMeta {
        images = images == null ? List.of() : List.copyOf(images);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * 命中自校验（P9 四要素 + 格式版本）：formatVersion、paramFingerprint、
     * source(absolutePath/size/mtime) 全部一致才返回 true。
     * .complete 标记与图片文件的存在性由 {@link PreviewCacheStore#load} 负责。
     */
    public boolean matches(PreviewCacheKey key) {
        return formatVersion == key.formatVersion()
                && paramFingerprint.equals(key.paramFingerprint())
                && source.absolutePath().equals(key.absolutePath())
                && source.size() == key.size()
                && source.mtime() == key.mtime();
    }
}
