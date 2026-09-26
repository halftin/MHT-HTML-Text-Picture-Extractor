package com.mhtextractor.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I18n 纯逻辑测试（零 JavaFX）：具名占位符、缺键策略、缺参保留、双 bundle 键集一致、
 * locale 切换、无未翻译占位。键值断言均取 zh_CN = 现有中文原文（行为零变更）。
 */
class I18nTest {

    /** 缺键 fallback 的形态：!key!（既非 null，也非原样键名） */
    private static final Pattern BANG_KEY = Pattern.compile("^![A-Za-z0-9_.]+!$");

    @AfterEach
    void resetLocale() {
        I18n.setLocale(null);
    }

    // (a) 已知键 + 具名占位符 + 多参数
    @Test
    void knownKeyReturnsExactChineseValue() {
        I18n.setLocale(new Locale("zh", "CN"));
        assertEquals("MHT 资源提取器", I18n.get("app.title"));
        assertEquals("组", I18n.get("core.groupDefault"));
    }

    @Test
    void namedPlaceholdersAreSubstituted() {
        I18n.setLocale(new Locale("zh", "CN"));
        assertEquals("[组1] 3 个文件", I18n.get("extract.groupFileCount", "group", "组1", "count", 3));
        assertEquals("目录 D:\\data", I18n.get("dir.label", "path", "D:\\data"));
    }

    @Test
    void multipleArgsAndEnglishLocale() {
        I18n.setLocale(new Locale("en", "US"));
        assertEquals("File a.mht failed (reason: boom)",
                I18n.get("extract.fileFailed", "file", "a.mht", "reason", "boom"));
        assertEquals("(5 items)", I18n.get("list.itemCount", "count", 5));
    }

    // (b) 缺键：!key!，非 null、非原样键名
    @Test
    void missingKeyReturnsBangKey() {
        String result = I18n.get("no.such.key.for.test");
        assertNotNull(result);
        assertEquals("!no.such.key.for.test!", result);
        assertTrue(BANG_KEY.matcher(result).matches());
    }

    // (c) 占位符缺参：保留 {name} 原样且不抛
    @Test
    void missingPlaceholderArgIsKeptAsIs() {
        I18n.setLocale(new Locale("zh", "CN"));
        assertEquals("文件：a.mht 失败，失败原因：{reason}",
                I18n.get("extract.fileFailed", "file", "a.mht"));
    }

    @Test
    void noArgsReturnsTemplateVerbatim() {
        I18n.setLocale(new Locale("zh", "CN"));
        assertEquals("（{count} 项）", I18n.get("list.itemCount"));
    }

    // (d) 两份 bundle 键集完全一致（防遗漏关键断言）
    @Test
    void bundlesHaveIdenticalKeySets() throws IOException {
        Set<String> zh = loadKeys("i18n/messages_zh_CN.properties");
        Set<String> en = loadKeys("i18n/messages_en_US.properties");
        assertEquals(zh, en, "两份 bundle 键集必须完全一致");
        assertFalse(zh.isEmpty());
    }

    // (e) 切 locale 后同一键返回不同值
    @Test
    void localeSwitchChangesValue() {
        I18n.setLocale(new Locale("zh", "CN"));
        String zh = I18n.get("app.title");
        I18n.setLocale(new Locale("en", "US"));
        assertEquals(new Locale("en", "US"), I18n.currentLocale());
        String en = I18n.get("app.title");
        assertNotEquals(zh, en);
        assertEquals("MHT Resource Extractor", en);
    }

    @Test
    void setLocaleNullFollowsSystemDefault() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("en", "US"));
            I18n.setLocale(null);
            assertEquals(new Locale("en", "US"), I18n.currentLocale());
            assertEquals("MHT Resource Extractor", I18n.get("app.title"));
        } finally {
            Locale.setDefault(saved);
        }
    }

    // (f) 两份 bundle 的值均不含未翻译占位（!前后夹键名! 形式）
    @Test
    void noUntranslatedBangMarkersInBundles() throws IOException {
        for (String name : new String[]{"i18n/messages_zh_CN.properties", "i18n/messages_en_US.properties"}) {
            Properties p = load(name);
            for (String key : p.stringPropertyNames()) {
                String value = p.getProperty(key);
                assertFalse(value.isBlank(), name + " 键 " + key + " 的值为空");
                assertFalse(BANG_KEY.matcher(value).matches(),
                        name + " 键 " + key + " 的值是未翻译占位：" + value);
            }
        }
    }

    @Test
    void parseLocaleSupportsPersistenceFormat() {
        assertEquals(new Locale("zh", "CN"), I18n.parseLocale("zh_CN"));
        assertEquals(new Locale("en", "US"), I18n.parseLocale("en_US"));
        assertEquals(new Locale("zh", "CN"), I18n.parseLocale("zh-CN"));
        assertEquals(new Locale("zh"), I18n.parseLocale("zh"));
        assertNull(I18n.parseLocale(null));
        assertNull(I18n.parseLocale(""));
        assertNull(I18n.parseLocale("  "));
        assertNull(I18n.parseLocale("_"));
    }

    private static Set<String> loadKeys(String resource) throws IOException {
        Properties p = load(resource);
        return p.stringPropertyNames();
    }

    private static Properties load(String resource) throws IOException {
        Properties p = new Properties();
        try (InputStream in = I18nTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "资源缺失：" + resource);
            p.load(in);
        }
        return p;
    }
}
