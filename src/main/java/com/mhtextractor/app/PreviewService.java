package com.mhtextractor.app;

import com.mhtextractor.cache.PreviewCacheKey;
import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.cache.PreviewCacheMeta;
import com.mhtextractor.cache.PreviewCacheStore;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.core.MimeParser;
import com.mhtextractor.extractor.ImageExtractor;
import com.mhtextractor.model.ThumbnailData;
import com.mhtextractor.output.ImageWriter;
import com.mhtextractor.util.SanitizeUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 预览服务（步骤 2）—— 把「预览缓存」这条链路的<b>业务逻辑</b>从 GUI 控制器搬进无头应用层。
 *
 * <p><b>为什么需要它</b>：在此之前，{@code MhtExtractorApp} <b>没有任何 preview 方法</b>，
 * "预览"的编排（算缓存键 → 查缓存 → 未命中则解析+抽图+原子落盘 → 组装缩略图元数据）全部长在
 * {@code MainController} 里。那意味着换 UI 框架时这条链路必须重新实现或反过来驱动旧控制器，
 * 目标 ②（业务逻辑脱离 UI 框架可复用）在预览这一环上是空的。本类补上这个缺口。</p>
 *
 * <p><b>边界（有意不做什么）</b>：</p>
 * <ul>
 *   <li><b>不做像素解码</b>：缩略图的 ARGB 像素解码依赖 JavaFX {@code Image}（缩放解码）与
 *       {@code PixelReader}，属渲染层职责，留在 GUI。本类只产出
 *       {@link ThumbnailData} 的<b>元数据部分</b>（{@code coreName}），{@code pixels} 为 null，
 *       由渲染层解码后填充。这样"需要哪些缩略图、它们叫什么"这件事不再依赖 UI 框架。</li>
 *   <li><b>不做失效判定与删除</b>：参数变化、条目移除、提取完成、退出清空这四条规则由
 *       {@link com.mhtextractor.cache.PreviewCacheLifecycle} 承担，调用方按自己的工作流触发。
 *       本类只负责"按 key 取到或建立缓存"。</li>
 *   <li><b>不管线程</b>：{@link #ensureCached} 同步阻塞，不要求特定线程，也不自查代次 ——
 *       调用方若需要"过期即放弃"，自行在调用前后检查自己的代次标记。</li>
 * </ul>
 *
 * <p><b>本类零 JavaFX</b>，由 {@code ArchitectureTest.businessPackagesHaveNoUiFrameworkDependency}
 * 按字节码常量池强制。</p>
 */
@Slf4j
public final class PreviewService {

    /**
     * 一次预览加载的结果。
     *
     * @param meta   缓存元数据（命中即来自磁盘，未命中即刚落盘的那份）
     * @param imagesDir 该缓存目录下的图片目录（调用方据此读取图片字节做解码）
     * @param thumbs 缩略图<b>元数据</b>列表（{@code pixels} 为 null，待渲染层解码填充）；
     *               顺序与 {@code meta.images()} 一致
     */
    public record PreviewResult(PreviewCacheMeta meta, Path imagesDir, List<ThumbnailData> thumbs) {
    }

    private final PreviewCacheLifecycle previewCache;

    public PreviewService(PreviewCacheLifecycle previewCache) {
        this.previewCache = previewCache;
    }

    /** 暴露缓存生命周期（供调用方做删除规则、容量提醒等，无需自己再持一份引用） */
    public PreviewCacheLifecycle lifecycle() {
        return previewCache;
    }

    /**
     * 按 {@code (文件, 参数)} 取预览缓存：命中即返回；未命中则解析、抽图、<b>原子落盘</b>后返回。
     *
     * <p>语义与搬迁前 {@code MainController.loadOrWritePreviewCache} <b>逐行等价</b>：</p>
     * <ol>
     *   <li>算缓存键（绝对路径 + size + mtime + 参数指纹 + 格式版本）；</li>
     *   <li>{@code store.load(key)} 命中 → 直接返回（不解析、不落盘）；</li>
     *   <li>meta <b>损坏</b>（有 {@code .complete} 但反序列化失败）→ 删目录后重建
     *       （否则同 key 下会反复报错、无法自愈）；</li>
     *   <li>未命中 → 解析 MHT、按参数抽图、构建 meta（图片文件名与写盘名严格一致），
     *       经 {@code store.write} 原子落盘（{@code .tmp} → 改名 → {@code .complete}）；</li>
     *   <li>落盘成功后触发一次磁盘占用台阶检查（命中路径不触发，与搬迁前一致）。</li>
     * </ol>
     *
     * <p><b>取消信号</b>：搬迁前控制器在"抽图完成、写盘之前"会检查自己的代次标记
     * （{@code token != requestToken} 即放弃，<b>不写缓存</b>）。该语义由 {@code cancelled} 承接 ——
     * 服务不认识"代次"这种 UI 概念，只认"调用方说别写了"。</p>
     *
     * @param cancelled 取消判定，可为 null（null = 不可取消）。返回 true 时在抽图后放弃写盘并返回 null
     * @return 结果；{@code cancelled} 在抽图后为 true 时返回 <b>null</b>（未写缓存）
     * @throws Exception 计算缓存键失败（无法读取文件属性），或解析 MHT 失败
     */
    public PreviewResult ensureCached(Path mht, AppConfig params, Supplier<Boolean> cancelled)
            throws Exception {
        PreviewCacheKey cacheKey = PreviewCacheKey.compute(mht,
                params.imageExtractionFingerprint(), PreviewCacheMeta.FORMAT_VERSION);

        PreviewCacheMeta meta = loadOrRebuild(mht, params, cacheKey, cancelled);
        if (meta == null) {
            return null;
        }
        return new PreviewResult(meta, previewCache.store().dirFor(cacheKey), buildThumbMeta(meta, params));
    }

    // ==================== 内部：取缓存 ====================

    /** 命中即返回；否则重建。语义见 {@link #ensureCached}。 */
    private PreviewCacheMeta loadOrRebuild(Path mht, AppConfig params, PreviewCacheKey cacheKey,
                                           Supplier<Boolean> cancelled) throws Exception {
        try {
            Optional<PreviewCacheMeta> hit = previewCache.store().load(cacheKey);
            if (hit.isPresent()) {
                log.info("预览缓存命中：{}", mht.getFileName());
                return hit.get();
            }
        } catch (IOException e) {
            // 有 .complete 但 meta 反序列化失败：删目录重建，否则同 key 下反复报错无法恢复
            log.warn("预览缓存元数据损坏，删除重建：{}（{}）", cacheKey.toDirName(), e.getMessage());
            try {
                previewCache.store().delete(cacheKey);
            } catch (IOException del) {
                log.warn("删除损坏缓存失败：{}", del.getMessage());
            }
        }
        return rebuild(mht, params, cacheKey, cancelled);
    }

    /** 解析 + 抽图 + 原子落盘。 */
    private PreviewCacheMeta rebuild(Path mht, AppConfig params, PreviewCacheKey cacheKey,
                                     Supplier<Boolean> cancelled) throws Exception {
        MimeParser parser = new MimeParser();
        MimeParser.ParsedMht parsed = parser.parse(mht.toString(), false, () -> false, null, null);
        ImageExtractor imageExtractor = new ImageExtractor(parsed,
                params.getImageNameStartMarker(), params.getImageNameEndMarker(),
                params.getImageRegionStartMarker(), params.getImageRegionEndMarker());
        List<ImageExtractor.ImageData> images = imageExtractor.extractImages(null);
        // 与搬迁前一致：抽图后、写盘前检查取消信号；已取消则不写缓存（旧缓存保持可用）
        if (cancelled != null && Boolean.TRUE.equals(cancelled.get())) {
            return null;
        }

        // 元数据先行构建；写盘文件名必须与 meta.images[].file 一致（store.load 据此校验）
        List<String> plainNames = new ArrayList<>();
        List<PreviewCacheMeta.ImageEntry> entries = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            ImageExtractor.ImageData img = images.get(i);
            String ext = SanitizeUtils.detectExtensionByMagicNumber(img.getData(), "jpg");
            String plain = String.format("%04d.%s", i + 1, ext);
            plainNames.add(plain);
            entries.add(new PreviewCacheMeta.ImageEntry(
                    PreviewCacheStore.IMAGES_DIR_NAME + "/" + plain,
                    img.getContentId(), img.getFilename(), ext, List.of()));
        }
        PreviewCacheMeta meta = new PreviewCacheMeta(PreviewCacheMeta.FORMAT_VERSION,
                new PreviewCacheMeta.Source(cacheKey.absolutePath(), cacheKey.size(), cacheKey.mtime()),
                cacheKey.paramFingerprint(), true, entries,
                imageExtractor.getLastWarnings());
        previewCache.store().write(cacheKey, meta, imagesDir -> {
            for (int i = 0; i < images.size(); i++) {
                Files.write(imagesDir.resolve(plainNames.get(i)), images.get(i).getData());
            }
        });
        log.info("预览图片已落盘缓存：{}（{} 张）", mht.getFileName(), images.size());
        // 写盘成功后检查磁盘占用台阶（命中路径不触发；检查在每项目写锁内，见 H12 取舍）
        previewCache.checkSizeAlert();
        return meta;
    }

    // ==================== 内部：缩略图元数据 ====================

    /**
     * 由 meta 的图片清单算出缩略图<b>元数据</b>（{@code pixels} 留空待渲染层解码）。
     *
     * <p>命名与搬迁前 {@code decodeThumb} 内的算法<b>逐字一致</b>：
     * {@code detectExtensionByMagicNumber} 不参与命名，而是由 meta 已记录的 {@code detectedExt}
     * 与 {@code ImageWriter.computeFileName(..., index + 1, "")} 决定，故改名与重排语义不变。</p>
     */
    private List<ThumbnailData> buildThumbMeta(PreviewCacheMeta meta, AppConfig params) {
        boolean rename = params != null && params.isEnableBatchRenaming();
        List<ThumbnailData> thumbs = new ArrayList<>(meta.images().size());
        for (int i = 0; i < meta.images().size(); i++) {
            PreviewCacheMeta.ImageEntry entry = meta.images().get(i);
            String coreName = ImageWriter.computeFileName(
                    new ImageExtractor.ImageData(entry.originalName(), entry.sourceId(), null),
                    entry.detectedExt(), rename, i + 1, "");
            thumbs.add(new ThumbnailData(coreName, 0, 0, null));
        }
        return thumbs;
    }
}
