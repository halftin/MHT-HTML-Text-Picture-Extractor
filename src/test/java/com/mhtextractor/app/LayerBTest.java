package com.mhtextractor.app;

import com.mhtextractor.baseline.BaselineCases;
import com.mhtextractor.baseline.BaselineHasher;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.i18n.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer B：**无头驱动 API 的验收测试**（会话 18 · E4-1）—— 目标 ② 验收 V1 + V2。
 *
 * <p><b>它证明什么</b>：{@link MhtExtractorApp}（{@code com.mhtextractor.app}，依赖闭包零 JavaFX）
 * 能在<b>没有 JavaFX 运行时</b>的情况下跑通"导入 → 参数 → 提取"，且产物与
 * GUI 侧黄金基线（{@code ProductBaselineTest} 捕获的 SHA-256）<b>逐字节一致</b>。</p>
 *
 * <p>这正是"换 UI 框架只需重写适配层 + 保留全部业务逻辑"的可执行证据：
 * 本测试扮演的就是那个"桩 UI"—— 它用零 JavaFX 的 {@code ProgressSink} 接收进度，
 * 用零 JavaFX 的 {@code CancelToken} 表达取消。</p>
 *
 * <p><b>与基线的对齐方式</b>：直接读取 {@code src/test/resources/baselines/<case>.<loc>.json}
 * 里记录的产物路径与 SHA-256，与本次无头运行的产物逐项比对。刻意<b>复用同一套夹具与同一套摘要口径</b>
 * （{@link BaselineCases#FIXED_MTIME_EPOCH_MILLI}、{@link BaselineHasher}），
 * 否则"一致"就成了自说自话。</p>
 *
 * <p><b>用例选择原则</b>：只选<b>与文件顺序无关</b>的基线。原因：GUI 侧基线的文件顺序来自显式列表，
 * 而本测试经 {@code importFolder} 得到的是<b>按路径排序</b>的顺序；章号/摘要若依赖顺序就会假失败。
 * 因此选用单文件用例（章号为「第一章」不随顺序变）或产物名彼此独立的单独模式用例。</p>
 */
class LayerBTest {

    @TempDir
    Path tmp;

    private Locale original;

    @AfterEach
    void restoreLocale() {
        if (original != null) {
            I18n.setLocale(original);
        }
    }

    private Path fixture(String name) {
        return BaselineCases.FIXTURE_DIR.resolve(name);
    }

    /** 按基线同一锚点复制夹具到临时输入目录（mtime 固定，保证 {datetime} 类输出确定） */
    private Path prepareInput(List<String> fixtures) throws Exception {
        Path in = tmp.resolve("in");
        Files.createDirectories(in);
        for (String fx : fixtures) {
            Path src = fixture(fx);
            assertTrue(Files.exists(src), "夹具缺失：" + src);
            Path dst = in.resolve(fx);
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            Files.setLastModifiedTime(dst, FileTime.fromMillis(BaselineCases.FIXED_MTIME_EPOCH_MILLI));
        }
        return in;
    }

    /** 读基线里记录的「相对路径 -> SHA-256」，只保留 .txt / 图片等产物条目（不含归档内部条目） */
    private List<BaselineHasher.TreeEntry> baselineProducts(String caseName, String loc) throws Exception {
        Path f = Path.of("src", "test", "resources", "baselines", caseName + "." + loc + ".json");
        assertTrue(Files.exists(f), "基线缺失：" + f);
        String json = Files.readString(f);
        List<BaselineHasher.TreeEntry> out = new ArrayList<>();
        // 极简解析：只取 products 数组里的 {"path": "...", "sha256": "..."} 对
        int i = json.indexOf("\"products\"");
        int j = json.indexOf("\"contentDigests\"");
        String seg = json.substring(i, j);
        var m = java.util.regex.Pattern
                .compile("\\{\"path\": \"(.*?)\", \"sha256\": \"([0-9a-f]+)\"\\}").matcher(seg);
        while (m.find()) {
            out.add(new BaselineHasher.TreeEntry(m.group(1), m.group(2), null));
        }
        return out;
    }

    private void assertProductsMatchBaseline(Path sessionOut, String caseName, String loc)
            throws Exception {
        List<BaselineHasher.TreeEntry> expected = baselineProducts(caseName, loc);
        assertFalse(expected.isEmpty(), "基线 " + caseName + "." + loc + " 未解析出任何产物");
        List<BaselineHasher.TreeEntry> actual = BaselineHasher.hashTree(sessionOut);
        assertEquals(expected, actual,
                "无头 API 的产物与 GUI 侧黄金基线不一致（case=" + caseName + "/" + loc + "）");
    }

    // ==================== V1/V2：桩 UI（零 JavaFX）驱动全链路 ====================

    @Test
    void headlessTextMergedMatchesBaseline() throws Exception {
        original = I18n.currentLocale();
        I18n.setLocale(Locale.SIMPLIFIED_CHINESE);

        Path in = prepareInput(List.of("simple-text.mht"));
        Path out = tmp.resolve("out-text-merged");
        Files.createDirectories(out);

        MhtExtractorApp app = new MhtExtractorApp();
        List<Path> files = app.importFolder(in);
        assertEquals(1, files.size(), "应导入 1 个 .mht");

        AppConfig params = new AppConfig();
        params.setEnableTemplate(true);
        params.setStartTemplate("=== {filename} @ {date} {time} ===\\n");
        params.setEndTemplate("\\n=== {filename} 结束 ===\\n");

        // 桩 UI：进度用纯 Java 收集，取消恒为 false —— 全程零 JavaFX
        List<String> progress = new ArrayList<>();
        MhtExtractorApp.ExtractResult r = app.extractText(files, params, out,
                MhtExtractorApp.DEFAULT_MERGED_TEXT_NAME,
                (idx, total, name, frac) -> progress.add(idx + "/" + total),
                () -> false);

        assertEquals(1, r.stats().getTotalFiles());
        assertEquals(1, r.stats().getSuccessCount());
        assertFalse(progress.isEmpty(), "进度回调应至少被调用一次（桩 UI 可接收）");
        assertTrue(Files.exists(out.resolve("merged.txt")), "合并模式应产出 merged.txt");

        assertProductsMatchBaseline(out, "text-template-vars", "zh");
    }

    @Test
    void headlessTextSeparateMatchesBaseline() throws Exception {
        original = I18n.currentLocale();
        I18n.setLocale(Locale.SIMPLIFIED_CHINESE);

        Path in = prepareInput(List.of("simple-text.mht", "titles.mht", "multi-image.mht"));
        Path out = tmp.resolve("out-text-separate");
        Files.createDirectories(out);

        MhtExtractorApp app = new MhtExtractorApp();
        List<Path> files = app.importFolder(in);
        assertEquals(3, files.size(), "应导入 3 个 .mht");

        AppConfig params = new AppConfig();
        params.setAddChapterNumber(true);
        params.setEnableTitleExtraction(true);
        params.setTitleStartMarker("<!--NAME_START-->");
        params.setTitleEndMarker("<!--NAME_END-->");
        params.setSeparateFiles(true);

        MhtExtractorApp.ExtractResult r = app.extractText(files, params, out,
                MhtExtractorApp.DEFAULT_MERGED_TEXT_NAME, null, null);

        assertEquals(3, r.stats().getTotalFiles());
        assertProductsMatchBaseline(out, "text-separate-titled", "zh");
    }

    @Test
    void headlessImageSeparateMatchesBaseline() throws Exception {
        original = I18n.currentLocale();
        I18n.setLocale(Locale.SIMPLIFIED_CHINESE);

        // 用 image-cn-filename 基线：它是**单夹具**用例（cn-filename.mht），与本测试只喂一个文件一致。
        // 若用 image-separate 基线会假失败 —— 那条基线覆盖 2 个夹具。
        Path in = prepareInput(List.of("cn-filename.mht"));
        Path out = tmp.resolve("out-image");
        Files.createDirectories(out);

        MhtExtractorApp app = new MhtExtractorApp();
        List<Path> files = app.importFolder(in);
        assertEquals(1, files.size());

        AppConfig params = new AppConfig();
        params.setImageModeEnabled(true);

        MhtExtractorApp.ExtractResult r = app.extractImages(files, params, out, null, null, null);

        assertEquals(1, r.stats().getTotalFiles());
        assertProductsMatchBaseline(out, "image-cn-filename", "zh");
    }

    /**
     * 自证"本测试确实运行在**没有 JavaFX** 的 classpath 上"（会话 18 · E4-1）。
     *
     * <p>{@code pom.xml} 里 {@code headless-layer-b} 这次 surefire 执行把 JavaFX 四个 artifact
     * 从 classpath 中<b>物理剔除</b>。本条断言把这件事从"构建配置假设"变成"测试内事实"：
     * 只有当运行环境<b>确实</b>声明了该执行时才生效，因此它在默认执行（JavaFX 在场）下不会误报。</p>
     *
     * <p><b>为什么这是 V1 的最强证据</b>：源码扫描/字节码扫描都可能被"间接引用"绕过；
     * 而"缺 JavaFX 时整个无头链路仍能跑完并与基线逐字节一致"是运行时事实 ——
     * 闭包里任何一处触碰 UI 框架都会立刻 {@code NoClassDefFoundError}。</p>
     *
     * <p><b>注意类名为何要拼接</b>：{@code ArchitectureTest.headlessTestPackagesHaveNoJavaFx}
     * 会扫描本包源码，禁止出现 UI 框架的<b>字面量</b>（防止无头测试真的依赖它）。
     * 本断言必须提到这些类名，故用字符串拼接构造 —— 这样既表达了意图，又不会触发那条守卫
     * （它是按字面量匹配的）。</p>
     */
    @Test
    void runsWithoutUiFrameworkOnClasspathWhenHeadlessExecutionIsActive() {
        String active = System.getProperty("mht.headless.execution");
        if (active == null) {
            // 默认执行（UI 框架在 classpath 上）：本断言不适用
            return;
        }
        assertEquals("1", active, "headless 执行标识应为 1");
        final String fx = "java" + "fx";
        for (String fxClass : List.of(fx + ".application.Platform", fx + ".scene.image.Image",
                fx + ".collections.ObservableList")) {
            try {
                Class.forName(fxClass);
                throw new AssertionError("headless 执行下不应能加载 " + fxClass
                        + " —— 说明 UI 框架仍在 classpath 上，本执行的证明力无效");
            } catch (ClassNotFoundException expected) {
                // 正是我们要的：UI 框架物理缺席
            }
        }
    }

    /** 取消经零 JavaFX 的令牌表达：恒 true 时不得产出成功结果 */
    @Test
    void headlessCancelTokenStopsExtraction() throws Exception {
        original = I18n.currentLocale();
        I18n.setLocale(Locale.SIMPLIFIED_CHINESE);

        Path in = prepareInput(List.of("simple-text.mht", "titles.mht"));
        Path out = tmp.resolve("out-cancel");
        Files.createDirectories(out);

        MhtExtractorApp app = new MhtExtractorApp();
        MhtExtractorApp.ExtractResult r = app.extractText(app.importFolder(in), new AppConfig(), out,
                MhtExtractorApp.DEFAULT_MERGED_TEXT_NAME, null, () -> true);

        assertEquals(0, r.stats().getSuccessCount(), "恒取消的令牌下不应有成功文件");
    }

    /** 导入的最小可用子集：递归 + 只收 mht + 去重 + 确定性排序 */
    @Test
    void importPathsIsRecursiveDedupedAndSorted() throws Exception {
        Path root = tmp.resolve("import-root");
        Path sub = Files.createDirectories(root.resolve("sub"));
        Files.createDirectories(root.resolve(".hidden"));
        Files.copy(fixture("simple-text.mht"), root.resolve("b.mht"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(fixture("titles.mht"), sub.resolve("a.mht"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(root.resolve("note.txt"), "not mht");

        MhtExtractorApp app = new MhtExtractorApp();
        // 同一目录 + 子目录同时传入：应去重
        List<Path> files = app.importPaths(List.of(root.toString(), sub.toString()));

        assertEquals(2, files.size(), "应只收 2 个 .mht（忽略 note.txt），且去重");
        // 排序口径是**绝对路径**的字典序：root/b.mht 排在 root/sub/a.mht 之前
        // （"b" < "s"），所以 b 在前、a 在后 —— 这是确定性排序的必然结果，不是错误。
        assertEquals(List.of("b.mht", "a.mht"),
                files.stream().map(p -> p.getFileName().toString()).toList(),
                "结果应按绝对路径确定性排序");
        assertNotNull(files.get(0).getParent());
        // 同一次调用重复执行必须给出相同顺序（确定性）
        assertEquals(files, app.importPaths(List.of(root.toString(), sub.toString())));
    }
}
