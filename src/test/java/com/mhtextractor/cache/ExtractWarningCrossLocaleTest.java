package com.mhtextractor.cache;

import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.ExtractWarning;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E4.1 跨语言回归（决策 #11 同类要求）：结构化警告的渲染随当前 locale 走，
 * 且落进预览缓存的永远是码值（语言中立），不携带任何已解析文案。
 * 无头、无反射、不属 {@code com.mhtextractor.gui} 驱动包。
 */
class ExtractWarningCrossLocaleTest {

    @TempDir
    Path tempDir;

    /** 渲染随 locale 走：zh/en 文案不同，且都不含缺键标记。结束时必须还原 locale（防污染同 JVM 其它测试）。 */
    @Test
    void renderFollowsCurrentLocaleAndNeverShowsMissingKeyMarker() {
        Locale saved = I18n.currentLocale();
        try {
            ExtractWarning warning = new ExtractWarning("extract.adFiltered", Map.of("name", "ad"));
            I18n.setLocale(Locale.SIMPLIFIED_CHINESE);
            String zh = I18n.render(warning);
            I18n.setLocale(Locale.US);
            String en = I18n.render(warning);
            assertNotEquals(zh, en);
            assertFalse(zh.contains("!"), "zh 渲染不得出现缺键标记：" + zh);
            assertFalse(en.contains("!"), "en 渲染不得出现缺键标记：" + en);
            assertTrue(zh.contains("ad") && en.contains("ad"), "参数 name 必须进入两种语言的文案");
        } finally {
            I18n.setLocale(saved);
        }
    }

    /** 两参数键（core.areaMarkersNotFound：start/end）在两种 locale 下都替换全部占位符。 */
    @Test
    void twoArgumentWarningRendersAllPlaceholdersInBothLocales() {
        Locale saved = I18n.currentLocale();
        try {
            ExtractWarning warning = new ExtractWarning("core.areaMarkersNotFound",
                    Map.of("start", "S标记", "end", "E标记"));
            I18n.setLocale(Locale.SIMPLIFIED_CHINESE);
            String zh = I18n.render(warning);
            I18n.setLocale(Locale.US);
            String en = I18n.render(warning);
            assertNotEquals(zh, en);
            for (String rendered : List.of(zh, en)) {
                assertFalse(rendered.contains("!"), "不得出现缺键标记：" + rendered);
                assertFalse(rendered.contains("{start}"), "start 占位符未替换：" + rendered);
                assertFalse(rendered.contains("{end}"), "end 占位符未替换：" + rendered);
                assertTrue(rendered.contains("S标记") && rendered.contains("E标记"),
                        "参数必须进入文案：" + rendered);
            }
        } finally {
            I18n.setLocale(saved);
        }
    }

    /** 强校验：JSON 往返后缓存里仍是码值（不含任何语言的已解析文案），同一份缓存换 locale 渲染两次结果不同。 */
    @Test
    void cachedMetaKeepsLanguageNeutralWarningsAcrossJsonRoundTrip() throws IOException {
        Locale saved = I18n.currentLocale();
        try {
            Path mht = tempDir.resolve("sample.mht");
            Files.write(mht, "fake mht".getBytes(StandardCharsets.UTF_8));
            PreviewCacheKey key = PreviewCacheKey.compute(mht, "fp".repeat(32),
                    PreviewCacheMeta.FORMAT_VERSION);
            ExtractWarning warning = new ExtractWarning("extract.adFiltered", Map.of("name", "ad"));
            PreviewCacheMeta meta = new PreviewCacheMeta(PreviewCacheMeta.FORMAT_VERSION,
                    new PreviewCacheMeta.Source(key.absolutePath(), key.size(), key.mtime(),
                            key.extraFingerprint()),
                    key.paramFingerprint(), true, List.of(), List.of(warning));

            PreviewCacheStore store = new PreviewCacheStore(tempDir.resolve("preview"));
            Path dir = store.write(key, meta, imagesDir -> { });

            // 落盘的 meta.json 文本：只含码值，不含任何语言的已解析文案
            String json = Files.readString(dir.resolve(PreviewCacheStore.META_FILE_NAME),
                    StandardCharsets.UTF_8);
            assertTrue(json.contains("extract.adFiltered"), "meta.json 必须携带警告码值：" + json);
            assertFalse(json.contains("已过滤"), "缓存不得携带 zh 已解析文案：" + json);
            assertFalse(json.contains("Ad image filtered"), "缓存不得携带 en 已解析文案：" + json);

            // 读回：警告仍是结构化码值
            Optional<PreviewCacheMeta> loaded = store.load(key);
            assertTrue(loaded.isPresent());
            assertEquals(List.of(warning), loaded.get().warnings());

            // 同一份缓存，切 locale 渲染两次：文案随语言走
            I18n.setLocale(Locale.SIMPLIFIED_CHINESE);
            String zh = I18n.render(loaded.get().warnings().getFirst());
            I18n.setLocale(Locale.US);
            String en = I18n.render(loaded.get().warnings().getFirst());
            assertNotEquals(zh, en);
            assertFalse(zh.contains("!") || en.contains("!"), "读回的警告渲染不得缺键");
        } finally {
            I18n.setLocale(saved);
        }
    }
}
