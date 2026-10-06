package com.mhtextractor.app;

import com.mhtextractor.cache.PreviewCacheKey;
import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.cache.PreviewCacheMeta;
import com.mhtextractor.cache.PreviewCacheStore;
import com.mhtextractor.cache.SourceFingerprint;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.core.MimeParser;
import com.mhtextractor.core.SourceParser;
import com.mhtextractor.core.SourceParsers;
import com.mhtextractor.extractor.ImageExtractor;
import com.mhtextractor.i18n.I18n;
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

        /**
         * 图片清单的<b>中性</b>投影：与 {@code meta().images()} 逐条对应、顺序与字段逐字一致，
         * 但不暴露 cache 类型（供 gui 层读取图片字节做解码，P3-3b-17）。
         */
        public List<ImageRef> images() {
            List<ImageRef> refs = new ArrayList<>(meta.images().size());
            for (PreviewCacheMeta.ImageEntry entry : meta.images()) {
                refs.add(new ImageRef(entry.file(), entry.originalName(), entry.sourceId()));
            }
            return refs;
        }

        /** 单条图片的中性引用（file = 缓存内图片文件名，originalName/sourceId 与 ImageEntry 逐字一致）。 */
        public record ImageRef(String file, String originalName, String sourceId) {
        }
    }

    /**
     * 一次预览加载的准备结果（P3-3b-2b）：{@link #prepare} 在<b>调用方线程</b>算好的缓存键，
     * 连同取缓存所需的文件与参数。gui 层只把它当<b>不透明令牌</b>使用 —— 不展开、不引用任何
     * cache 类型，原样回传给 {@link #ensureCachedLocked(Prepared, Supplier)}。
     *
     * @param cacheKey 已算好的缓存键（内部实现细节，调用方不得展开）
     */
    public record Prepared(Path mht, AppConfig params, PreviewCacheKey cacheKey) {
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
     *   <li>算缓存键（绝对路径 + size + mtime + 参数指纹 + <b>附属指纹</b> + 格式版本）；
     *       键由<b>调用方传入</b>（见下方"为什么键是参数"）；</li>
     *   <li>{@code store.load(key)} 命中 → 直接返回（不解析、不落盘）；</li>
     *   <li>meta <b>损坏</b>（有 {@code .complete} 但反序列化失败）→ 删目录后重建
     *       （否则同 key 下会反复报错、无法自愈）；</li>
     *   <li>未命中 → 解析来源、按参数抽图、构建 meta（图片文件名与写盘名严格一致），
     *       经 {@code store.write} 原子落盘（{@code .tmp} → 改名 → {@code .complete}）；</li>
     *   <li>落盘成功后触发一次磁盘占用台阶检查（命中路径不触发，与搬迁前一致）。</li>
     * </ol>
     *
     * <p><b>为什么键是参数（R6.5 的必然结果）</b>：键里多了"侧车目录内容摘要"，
     * 计算它要遍历并读取侧车文件。若本方法自己算、调用方（GUI 的每项目写锁需要
     * {@code key.absolutePath()}）也算一遍，同一次预览就会把侧车读两遍；
     * 更糟的是两处算法一旦分叉就会"写进去的键 ≠ 查的键"⇒ 缓存永不命中且不报错。
     * 因此口径统一为：<b>算一次、传进来</b>（{@link com.mhtextractor.cache.PreviewCacheKey#compute}）。</p>
     *
     * @param cacheKey  已算好的缓存键（{@link com.mhtextractor.cache.PreviewCacheKey#compute}）
     * @param mht       来源文件（MHT 或 HTML），仅用于日志与解析
     * @param cancelled 取消判定，可为 null（null = 不可取消）。返回 true 时在抽图后放弃写盘并返回 null
     * @return 结果；{@code cancelled} 在抽图后为 true 时返回 <b>null</b>（未写缓存）
     * @throws Exception 解析来源失败
     */
    public PreviewResult ensureCached(Path mht, AppConfig params, PreviewCacheKey cacheKey,
                                      Supplier<Boolean> cancelled) throws Exception {
        PreviewCacheMeta meta = loadOrRebuild(mht, params, cacheKey, cancelled);
        if (meta == null) {
            return null;
        }
        return new PreviewResult(meta, previewCache.store().dirFor(cacheKey), buildThumbMeta(meta, params));
    }

    /**
     * 便捷重载：由本方法按 {@code (文件, 参数)} 现算缓存键。
     *
     * <p>⚠️ GUI 主路径<b>不应</b>用它：那里需要「FX 线程算键（失败不进加载态）+ 每项目写锁」，
     * 走 {@link #prepare} + {@link #ensureCachedLocked(Prepared, Supplier)}。
     * 本重载存在的意义是无头测试与"不需要锁"的调用方不必重复三段实参。</p>
     */
    public PreviewResult ensureCached(Path mht, AppConfig params, Supplier<Boolean> cancelled)
            throws Exception {
        return ensureCached(mht, params,
                PreviewCacheKey.compute(mht, params.imageExtractionFingerprint(),
                        PreviewCacheMeta.FORMAT_VERSION), cancelled);
    }

    /**
     * GUI 预览主路径第一步（P3-3b-2b）：在<b>调用方线程</b>（GUI 为 FX 线程）按搬迁前
     * {@code PreviewLoader} 的口径算缓存键 —— 与搬迁前 FX 线程上的
     * {@link PreviewCacheKey#compute} 逐字一致（含 HTML 侧车附属指纹）；读不到 size/mtime 时抛
     * {@link IOException}（与搬迁前同类型）。
     *
     * <p><b>为什么键必须在调用方线程算</b>（P3-3b-2 教训）：算键失败属于「读取文件信息失败」，
     * GUI 必须在<b>设置加载态之前</b>同步处理（日志 + 提醒 + 返回）—— 若把算键挪进加载线程，
     * 失败时就会"先亮加载态、再复位"，多一次可观察的闪烁。因此算键不放在
     * {@link #ensureCachedLocked(Prepared, Supplier)} 里，而由本方法提前完成。</p>
     *
     * @throws IOException 读取文件信息失败（size / mtime 不可得）
     */
    public Prepared prepare(Path mht, AppConfig params) throws IOException {
        return new Prepared(mht, params, PreviewCacheKey.compute(mht, params.imageExtractionFingerprint(),
                SourceFingerprint.of(mht), PreviewCacheMeta.FORMAT_VERSION));
    }

    /**
     * GUI 预览主路径第二步（P3-3b-2b）：持 {@code prepared} 键对应的<b>每项目写锁</b>取缓存 ——
     * 锁覆盖「查缓存 → 解析抽图 → 落盘（含台阶检查）」整段；同项目串行，不同项目并行。
     *
     * @param prepared  {@link #prepare} 的产物（键已在调用方线程算好，本方法不再算）
     * @param cancelled 取消判定（语义同 {@link #ensureCached(Path, AppConfig, Supplier)}），可为 null
     */
    public PreviewResult ensureCachedLocked(Prepared prepared, Supplier<Boolean> cancelled)
            throws Exception {
        synchronized (previewCache.lockFor(prepared.cacheKey().absolutePath())) {
            return ensureCached(prepared.mht(), prepared.params(), prepared.cacheKey(), cancelled);
        }
    }

    /**
     * 某次加载结果对应的缓存目录名 = {@link PreviewCacheKey#toDirName()} 的 64 位十六进制目录名
     * （{@code imagesDir} 是 {@code <root>/<64hex>} 的项目目录本身，末段即目录名）。
     * GUI 日志专用（如 {@code log.maincontroller.63} 的实参），与搬迁前
     * {@code cacheKey.toDirName()} <b>逐字一致</b>；避免 GUI 自己摆弄缓存路径语义。
     */
    public String cacheDirName(PreviewResult pr) {
        return pr.imagesDir().getFileName().toString();
    }

    /**
     * 删除某次加载结果对应的缓存目录（GUI 陈旧清理专用，P3-3b-2）。
     *
     * <p>key 由 {@code pr.meta()} 四要素 + 附属指纹 + 格式版本反推（与
     * {@link PreviewCacheStore#imageFileFor} / {@link PreviewCacheStore#listKeys()} 同法），
     * 与搬迁前 GUI 持有的那个 key 是<b>同一个</b> —— 落盘时 {@link PreviewCacheStore#write}
     * 已用 {@code meta.matches(key)} 校验一致性，反推必然回到原 key，
     * 删除语义与搬迁前 {@code store().delete(cacheKey)} 逐字一致。</p>
     */
    public boolean deleteCacheOf(PreviewResult pr) throws IOException {
        return previewCache.store().delete(keyOf(pr.meta()));
    }

    // ==================== 内部：取缓存 ====================

    /** 命中即返回；否则重建。语义见 {@link #ensureCached}。 */
    private PreviewCacheMeta loadOrRebuild(Path mht, AppConfig params, PreviewCacheKey cacheKey,
                                           Supplier<Boolean> cancelled) throws Exception {
        try {
            Optional<PreviewCacheMeta> hit = previewCache.store().load(cacheKey);
            if (hit.isPresent()) {
                log.info(I18n.msg("log.previewservice.01", mht.getFileName()));
                return hit.get();
            }
        } catch (IOException e) {
            // 有 .complete 但 meta 反序列化失败：删目录重建，否则同 key 下反复报错无法恢复
            log.warn(I18n.msg("log.previewservice.02", cacheKey.toDirName(), e.getMessage()));
            try {
                previewCache.store().delete(cacheKey);
            } catch (IOException del) {
                log.warn(I18n.msg("log.previewservice.03", del.getMessage()));
            }
        }
        return rebuild(mht, params, cacheKey, cancelled);
    }

    /** 解析 + 抽图 + 原子落盘。 */
    private PreviewCacheMeta rebuild(Path mht, AppConfig params, PreviewCacheKey cacheKey,
                                     Supplier<Boolean> cancelled) throws Exception {
        SourceParser parser = SourceParsers.forPath(mht.toString());
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
                new PreviewCacheMeta.Source(cacheKey.absolutePath(), cacheKey.size(), cacheKey.mtime(),
                        cacheKey.extraFingerprint()),
                cacheKey.paramFingerprint(), true, entries,
                imageExtractor.getLastWarnings());
        previewCache.store().write(cacheKey, meta, imagesDir -> {
            for (int i = 0; i < images.size(); i++) {
                Files.write(imagesDir.resolve(plainNames.get(i)), images.get(i).getData());
            }
        });
        log.info(I18n.msg("log.previewservice.04", mht.getFileName(), images.size()));
        // 写盘成功后检查磁盘占用台阶（命中路径不触发；检查在每项目写锁内，见 H12 取舍）
        previewCache.checkSizeAlert();
        return meta;
    }

    // ==================== 内部：缩略图元数据 ====================

    /** 由 meta 反推缓存键（{@link PreviewCacheStore#listKeys()} 同法）。 */
    private static PreviewCacheKey keyOf(PreviewCacheMeta meta) {
        return new PreviewCacheKey(meta.source().absolutePath(), meta.source().size(),
                meta.source().mtime(), meta.paramFingerprint(), meta.source().sidecarFingerprint(),
                meta.formatVersion());
    }

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
