package com.mhtextractor.engine;

import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.cache.PreviewCacheStore;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 预览缓存生命周期构造工厂（P1-2）—— 把 {@code MainController} 里的「存储层 + 生命周期」
 * 构造下沉到 {@code app}，使 gui 不再直接构造预览缓存。
 *
 * <p>生产根 = {@code <java.io.tmpdir>/mht-extractor-preview}（P6/P8，与
 * {@code HtmlExportBuilder} 的 {@code mht-extractor-view} 并列）；构造与装配顺序与原
 * {@code MainController} 代码逐字一致：{@code new PreviewCacheLifecycle(new PreviewCacheStore(root))}。</p>
 *
 * <p><b>零 JavaFX 依赖</b>：本类只依赖 {@code cache} 业务包，
 * 由 {@code ArchitectureTest} 强制其依赖闭包不含 {@code javafx}。</p>
 */
public final class PreviewCacheFactory {

    /** 预览缓存根目录名（相对 {@code java.io.tmpdir}）。 */
    public static final String ROOT_DIR_NAME = "mht-extractor-preview";

    private PreviewCacheFactory() {
    }

    /** 生产根：{@code <java.io.tmpdir>/mht-extractor-preview}。 */
    public static PreviewCacheLifecycle create() {
        return create(Paths.get(System.getProperty("java.io.tmpdir"), ROOT_DIR_NAME));
    }

    /** 指定根目录构造（测试/探针可用）。 */
    public static PreviewCacheLifecycle create(Path root) {
        return new PreviewCacheLifecycle(new PreviewCacheStore(root));
    }
}
