package com.mhtextractor.cache;

import com.mhtextractor.extractor.ImageExtractor;
import com.mhtextractor.extractor.ImageSource;
import com.mhtextractor.model.ExtractWarning;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 磁盘图片数据源：包装缓存目录 + meta.json，实现 {@link ImageSource}（需求 2 §2.6(2)，会话 10 提取复用用）。
 *
 * <p><b>内存语义（重要）</b>：接口的 {@code get(i)} 返回持有 {@code byte[]} 的 ImageData，
 * 因此「不在内存缓存全尺寸字节」的正确实现是：
 * <ul>
 *   <li>构造时<b>不</b>预读任何图片字节（只有 meta 常驻内存）；</li>
 *   <li>每次 {@code get(i)} 只读当前这一张，<b>不用</b> Map 缓存已读字节，调用方用完即可回收。</li>
 * </ul>
 * 这样写入层顺序遍历时，任意时刻内存里至多一张全尺寸图。
 * 现有最小接口（{@code size()/get(int)}）已足以达成该目标，无需扩展 ImageSource 签名。
 */
public class DirectoryImageSource implements ImageSource {

    private final PreviewCacheMeta meta;
    private final Path cacheDir;

    public DirectoryImageSource(PreviewCacheMeta meta, Path cacheDir) {
        this.meta = meta;
        this.cacheDir = cacheDir;
    }

    @Override
    public int size() {
        return meta.images().size();
    }

    /** 按需从磁盘读入第 index 张图片的字节（读失败抛 UncheckedIOException，由写入层现有异常路径处置） */
    @Override
    public ImageExtractor.ImageData get(int index) {
        PreviewCacheMeta.ImageEntry entry = meta.images().get(index);
        try {
            byte[] data = Files.readAllBytes(cacheDir.resolve(entry.file()));
            // filename=原始文件名（提示名，非最终名，P13）、contentId=来源标识（cid/location/…）
            return new ImageExtractor.ImageData(entry.originalName(), entry.sourceId(), data);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read cached image: " + cacheDir.resolve(entry.file()), e);
        }
    }

    @Override
    public List<ExtractWarning> warnings() {
        return meta.warnings();
    }
}
