package com.mhtextractor.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
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

    /**
     * 两份 bundle 中<b>同一个键</b>的占位符集合必须一致。
     *
     * <p>为什么需要：键集一致（{@link #bundlesHaveIdenticalKeySets()}）只保证"两边都有这个键"，
     * 不保证"两边的 {@code {0}} / {@code {1}} 对得上"。日志文案改为经 {@link I18n#msg}
     * 按位置索引取值后，翻译侧少写或多写一个索引不会报错，只会在日志里原样打印出 {@code {2}} ——
     * 而那种缺陷没有任何测试能发现。本断言把它变成红灯。</p>
     */
    @Test
    void placeholderSetsMatchAcrossBundles() throws IOException {
        Properties zh = load("i18n/messages_zh_CN.properties");
        Properties en = load("i18n/messages_en_US.properties");
        List<String> mismatches = new ArrayList<>();
        for (String key : zh.stringPropertyNames()) {
            Set<String> a = placeholders(zh.getProperty(key));
            Set<String> b = placeholders(en.getProperty(key));
            if (!a.equals(b)) {
                mismatches.add(key + "：zh" + a + " vs en" + b);
            }
        }
        assertTrue(mismatches.isEmpty(),
                mismatches.size() + " 个键的占位符集合不一致：\n  " + String.join("\n  ", mismatches));
    }

    private static Set<String> placeholders(String value) {
        Set<String> out = new java.util.TreeSet<>();
        Matcher m = Pattern.compile("\\{(\\w+)}").matcher(value == null ? "" : value);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * {@link I18n#msg} 是日志专用的位置占位符入口，两条容易踩的行为在此钉死：
     * <ul>
     *   <li><b>null 实参渲染成 "null"</b>，而不是把 {@code {0}} 原样留在日志里 ——
     *       日志实参为 null 是常态（如 {@code config.getInputFolder()} 未设置），
     *       logback 的 {@code {}} 遇到 null 也打印 "null"，两者必须一致。
     *       本条是真实缺陷的回归：单元测试日志里曾出现 {@code 输入文件夹：{0}}。</li>
     *   <li><b>无实参</b>时按"模板不含占位符"处理，原样返回文案（不报错）。</li>
     * </ul>
     */
    @Test
    void msgRendersNullArgumentAsNullNotNullPlaceholder() {
        I18n.setLocale(new Locale("zh", "CN"));
        assertEquals("输入文件夹：null", I18n.msg("log.textbatchprocessor.13", (Object) null));
        assertEquals("输入文件夹：C:\\data", I18n.msg("log.textbatchprocessor.13", "C:\\data"));
        I18n.setLocale(new Locale("en", "US"));
        assertEquals("Input folder: null", I18n.msg("log.textbatchprocessor.13", (Object) null));
        assertEquals("Input folder: C:\\data", I18n.msg("log.textbatchprocessor.13", "C:\\data"));
    }

    /**
     * 全部 {@code log.*} 模板在两种 locale 下、按模板自带的占位符个数补足实参后，
     * 渲染结果都<b>不得再出现</b> {@code {数字}} 形态。
     *
     * <p>这是"位置占位符"这一新机制的端到端安全网：翻译侧漏写/错写一个索引、
     * 或 {@code I18n.msg} 的替换逻辑退化，都会在这里变成红灯（含 229 条日志文案全量覆盖）。</p>
     */
    @Test
    void everyLogTemplateRendersWithoutLeftoverPlaceholders() throws IOException {
        int checked = 0;
        for (var loc : List.of(new Locale("zh", "CN"), new Locale("en", "US"))) {
            I18n.setLocale(loc);
            String resource = loc.getLanguage().equals("zh")
                    ? "i18n/messages_zh_CN.properties" : "i18n/messages_en_US.properties";
            Properties props = load(resource);
            for (String key : props.stringPropertyNames()) {
                if (!key.startsWith("log.")) {
                    continue;
                }
                Set<String> ph = placeholders(props.getProperty(key));
                int maxIdx = -1;
                for (String name : ph) {
                    assertTrue(name.matches("\\d+"),
                            "[" + loc + "] 日志键 " + key + " 出现非位置占位符 {" + name + "}");
                    maxIdx = Math.max(maxIdx, Integer.parseInt(name));
                }
                Object[] args = new Object[maxIdx + 1];
                for (int i = 0; i < args.length; i++) {
                    args[i] = "V" + i;
                }
                String rendered = I18n.msg(key, args);
                assertFalse(rendered.matches(".*\\{\\d+}.*"),
                        "[" + loc + "] 键 " + key + " 渲染后仍含未替换占位符：" + rendered);
                assertFalse(rendered.isBlank(), "[" + loc + "] 键 " + key + " 渲染为空");
                checked++;
            }
        }
        assertTrue(checked >= 200, "log.* 键太少（" + checked + "），疑似前缀或扫描口径出错");
    }

    private static Set<String> loadKeys(String resource) throws IOException {        Properties p = load(resource);
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
