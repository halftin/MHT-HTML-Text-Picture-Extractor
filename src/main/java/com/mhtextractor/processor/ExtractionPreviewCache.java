package com.mhtextractor.processor;

import com.mhtextractor.cache.PreviewCacheKey;
import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.cache.PreviewCacheMeta;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.i18n.I18n;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * 提取期的<b>预览缓存策略</b>（Q12 / 指南 §3 "建议组件"第 3 项，首选落在 {@code processor}）：
 * 计算提取缓存键、验证命中可复用条件、暴露提取完成后的清理动作。
 *
 * <p><b>为什么放在 {@code processor} 而不是 {@code cache}</b>：本组件的判定条件依赖<b>提取语义</b>
 * ——"哪些缓存形态与'未命中路径'等价"这件事只有提取侧说得清（例如"必须有 ≥1 张带来源 ID 的图"
 * 等价于未命中路径的『无图片内容』快速检查会放行）。把它放进 {@code cache} 会把提取语义漏进纯存储层，
 * 也就是指南明令"不要把输出策略或批处理统计塞进 {@code PreviewCacheStore}"的反向版本。</p>
 *
 * <p><b>本组件只做四件事，四条规则各自对应一处</b>（指南 §1.3-4 要求逐条保留）：</p>
 * <ol>
 *   <li><b>只复用含可用来源 ID 的缓存</b>：{@link #lookup} 的可复用判据 ——
 *       元数据含 ≥1 张图，且 ≥1 张的 {@code sourceId} 非空；</li>
 *   <li><b>全 Data URI / 空图片缓存回退原提取路径</b>：同上判据的直接后果（0 图或全 data URI
 *       ⇒ 不可复用 ⇒ 走原路径，结果与无缓存完全一致）；</li>
 *   <li><b>命名依赖标题时缓存命中仍解析 HTML</b>：不在本组件里 —— 那是 {@code processUnified} 的
 *       条件（{@code needsTitleParse}），本组件只提供"命中事实"；</li>
 *   <li><b>提取完成后的清理（规则 b）</b>：{@link #isReusable()} + {@link #onExtractionCompleted}
 *       （保持 {@code hasCachedEntries} 守卫，会话缓存为空时跳过逐文件扫描）。</li>
 * </ol>
 *
 * <p><b>null 语义</b>：{@code lifecycle == null} 表示"复用关闭"，此时 {@link #lookup} 恒为
 * {@link Optional#empty()}、{@link #isReusable()} 为 false —— 与旧实现里 {@code previewCache == null}
 * 的分支逐字等价。</p>
 */
@Slf4j
final class ExtractionPreviewCache {

    /**
     * 命中结果：{@code key} 用于取缓存目录与规则 (b) 的删除，{@code meta} 提供图片与警告。
     */
    record CacheHit(PreviewCacheKey key, PreviewCacheMeta meta) {
    }

    private final PreviewCacheLifecycle lifecycle;
    private final AppConfig config;

    ExtractionPreviewCache(PreviewCacheLifecycle lifecycle, AppConfig config) {
        this.lifecycle = lifecycle;
        this.config = config;
    }

    /** 是否启用了缓存复用（对应旧实现的 {@code previewCache != null}） */
    boolean isReusable() {
        return lifecycle != null;
    }

    /**
     * 命中结果里的缓存目录（{@code key} → 真实目录）。
     *
     * <p>只暴露"由 key 得到目录"这一件事，不把 {@code PreviewCacheStore} 整体交出去：提取侧需要的
     * 只是"图片在哪"，而不是存储层的写/删能力（那些必须经规则收口）。</p>
     */
    java.nio.file.Path dirFor(PreviewCacheKey key) {
        return lifecycle.store().dirFor(key);
    }

    /**
     * 查该项目在当前参数下的预览缓存。
     *
     * <p>未命中 / 不可用（0 图、全 Data URI、sourceId 全空、IO 失败、meta 损坏）一律返回
     * {@link Optional#empty()} ⇒ 调用方走原提取路径。判定口径见类 javadoc。</p>
     */
    Optional<CacheHit> lookup(BatchProcessor.MhtFileInfo fileInfo) {
        if (lifecycle == null) {
            return Optional.empty();
        }
        PreviewCacheKey key;
        try {
            key = PreviewCacheKey.compute(Path.of(fileInfo.getPath()),   // R6.5：键含 HTML 侧车目录内容摘要
                    config.imageExtractionFingerprint(), PreviewCacheMeta.FORMAT_VERSION);
        } catch (IOException e) {
            log.warn(I18n.msg("log.imagebatchprocessor.36", fileInfo.getName(), e.getMessage()));
            return Optional.empty();
        }
        try {
            Optional<PreviewCacheMeta> meta = lifecycle.store().load(key);
            if (meta.isEmpty()) {
                return Optional.empty();
            }
            PreviewCacheMeta m = meta.get();
            boolean reusable = hasUsableSourceId(m);
            if (!reusable) {
                log.info(I18n.msg("log.imagebatchprocessor.37", fileInfo.getName()));
                return Optional.empty();
            }
            return Optional.of(new CacheHit(key, m));
        } catch (IOException e) {
            log.warn(I18n.msg("log.imagebatchprocessor.38", fileInfo.getName(), e.getMessage()));
            return Optional.empty();
        }
    }

    /**
     * 可复用判据：元数据含 ≥1 张图、且 ≥1 张有<b>非空来源标识</b>（cid）。
     *
     * <p>后者等价于未命中路径的「无图片内容」快速检查（{@code cidMap}/{@code locationDataMap} 非空）
     * 会放行 —— 只有能证明未命中路径会走到提取阶段的缓存才可复用；0 图或全 data URI 的缓存按未命中
     * 处理（回全量路径，结果与无缓存时完全一致）。</p>
     */
    private static boolean hasUsableSourceId(PreviewCacheMeta meta) {
        return !meta.images().isEmpty()
                && meta.images().stream()
                        .anyMatch(e -> e.sourceId() != null && !e.sourceId().isEmpty());
    }

    /**
     * 规则 (b)：该项目提取完成（SUCCESS / SUCCESS_WARNED）→ 删其缓存目录。
     *
     * <p><b>命中与否都删</b>（未命中也可能残留旧指纹目录，是规则 (a) 的兜底）；取消（未处理到该项目）
     * 或 FAILED / SKIPPED 时调用方不调本方法，缓存保留以便重试（C10）。
     * {@code hasCachedEntries} 守卫让"会话缓存为空"时跳过逐文件扫描。</p>
     */
    void onExtractionCompleted(String path) {
        if (lifecycle != null && lifecycle.hasCachedEntries()) {
            lifecycle.onExtractionCompleted(path);
        }
    }

    /**
     * HTML 里是否存在 Data URI 形式的图片引用（{@code <img src="data:...">}）。
     *
     * <p>用于"无图片内容"的快速判据（步骤 7 修复）：Data URI 图片既无 {@code Content-ID} 也无
     * {@code Content-Location}，因此只看 {@code cidMap}/{@code locationDataMap} 会把"纯 Data URI 的 MHT"
     * 误判为空。这里用<b>宽松的字符串判据</b>（只做快速筛查，不是提取判据）——精确判据仍是
     * {@code ImageExtractor} 里基于 Jsoup 的 {@code img[src^=data:]}；宽松只会让极少数文件多走一次
     * 完整提取（其结果是 0 张图），不会漏图。</p>
     */
    static boolean hasDataUriImages(String html) {
        if (html == null || html.isEmpty()) {
            return false;
        }
        return html.toLowerCase(Locale.ROOT).contains("src=\"data:");
    }
}
