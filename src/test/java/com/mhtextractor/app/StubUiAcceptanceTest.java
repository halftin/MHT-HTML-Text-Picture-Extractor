package com.mhtextractor.app;

import com.mhtextractor.baseline.BaselineCases;
import com.mhtextractor.baseline.BaselineHasher;
import com.mhtextractor.cache.PreviewCacheKey;
import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.cache.PreviewCacheMeta;
import com.mhtextractor.cache.PreviewCacheStore;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.i18n.ErrorCode;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.GroupItem;
import com.mhtextractor.util.ParamScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桩 UI 端到端验收（会话 18 · E6）—— **目标 ② 验收 V2 的完整版**。
 *
 * <p><b>它模拟什么</b>：一个"替换掉 JavaFX 的桩 UI"——用
 * {@link MhtExtractorApp}（无头门面）+ {@link WorkspaceModel}（工作区模型）
 * + {@link WorkspaceParamService}（缓存规则）+ {@link ExtractionPreconditions}（前置规则）
 * 完成<b>一位真实用户从头到尾的操作序列</b>，并断言最终结果（含产物 SHA-256 与 GUI 侧黄金基线一致）。</p>
 *
 * <p><b>与其它测试的分工</b>：{@code LayerBTest} 验"无头提取与基线一致"；
 * {@code WorkspaceModelTest} 验"工作区操作语义"；本条则把它们**拼装**成一条连贯的用户旅程 ——
 * 若某个 app 服务之间接线不齐，只有本条会失败。</p>
 *
 * <p><b>本测试不依赖 JavaFX</b>（由 {@code headless-layer-b} 执行在"无 JavaFX classpath"下再次运行，
 * 使"缺 UI 框架也能跑通全流程"成为运行时事实）。</p>
 */
class StubUiAcceptanceTest {

    @TempDir
    Path tmp;

    private Locale original;

    @AfterEach
    void restoreLocale() {
        if (original != null) {
            I18n.setLocale(original);
        }
    }

    private Path prepareInput(List<String> fixtures) throws Exception {
        Path in = tmp.resolve("in");
        Files.createDirectories(in);
        for (String fx : fixtures) {
            Path src = BaselineCases.FIXTURE_DIR.resolve(fx);
            assertTrue(Files.exists(src), "夹具缺失：" + src);
            Path dst = in.resolve(fx);
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            Files.setLastModifiedTime(dst, FileTime.fromMillis(BaselineCases.FIXED_MTIME_EPOCH_MILLI));
        }
        return in;
    }

    private List<BaselineHasher.TreeEntry> baselineProducts(String caseName, String loc) throws Exception {
        Path f = Path.of("src", "test", "resources", "baselines", caseName + "." + loc + ".json");
        assertTrue(Files.exists(f), "基线缺失：" + f);
        String json = Files.readString(f);
        int i = json.indexOf("\"products\"");
        int j = json.indexOf("\"contentDigests\"");
        String seg = json.substring(i, j);
        List<BaselineHasher.TreeEntry> out = new ArrayList<>();
        var m = java.util.regex.Pattern
                .compile("\\{\"path\": \"(.*?)\", \"sha256\": \"([0-9a-f]+)\"\\}").matcher(seg);
        while (m.find()) {
            out.add(new BaselineHasher.TreeEntry(m.group(1), m.group(2), null));
        }
        return out;
    }

    /** 模拟"用户点开始提取"时 UI 提供的事实（零 JavaFX） */
    private ExtractionPreconditions.Facts factsOf(Path in, Path out, List<PreviewItem> items) {
        long visible = items.stream().filter(i -> !i.removed).count();
        return new ExtractionPreconditions.Facts(in.toString(), out.toString(),
                Files.isDirectory(in), items.size(), (int) visible);
    }

    /** 断言输出目录的产物集与黄金基线一致（逐路径 + 逐字节 SHA-256） */
    private void assertProductsMatchBaseline(Path out, String caseName, String loc) throws Exception {
        assertEquals(baselineProducts(caseName, loc), BaselineHasher.hashTree(out),
                "无头产物应与 GUI 侧黄金基线逐字节一致：" + caseName + "." + loc);
    }

    // ==================== 完整用户旅程 ====================

    @Test
    void fullUserJourneyWithoutUiFramework() throws Exception {
        original = I18n.currentLocale();
        I18n.setLocale(Locale.SIMPLIFIED_CHINESE);

        final MhtExtractorApp app = new MhtExtractorApp();
        final WorkspaceModel ws = new WorkspaceModel();

        // ---------- ① 导入：桩 UI 从目录发现文件并加入工作区 ----------
        Path in = prepareInput(List.of("simple-text.mht", "titles.mht", "single-image.mht",
                "cn-filename.mht"));
        Path out = Files.createDirectories(tmp.resolve("out"));

        List<Path> discovered = app.importFolder(in);
        assertEquals(4, discovered.size(), "应发现 4 个 MHT");

        List<PreviewItem> items = new ArrayList<>(discovered.stream()
                .map(p -> new PreviewItem(p.toString(), p.getFileName().toString(),
                        LocalDateTime.now(), in.toString()))
                .toList());
        assertEquals(4, ws.addItems(items));
        assertEquals(4, ws.visibleCount());

        // ---------- ② 前置校验：此时应允许开始提取 ----------
        assertNull(ExtractionPreconditions.validate(factsOf(in, out, ws.allItems())),
                "目录与条目齐备时应允许提取");

        // ---------- ③ 工作区操作：把两个文本文件合并成组 ----------
        PreviewItem simple = items.stream().filter(i -> i.name.equals("simple-text.mht")).findFirst().orElseThrow();
        PreviewItem titles = items.stream().filter(i -> i.name.equals("titles.mht")).findFirst().orElseThrow();
        PreviewItem image = items.stream().filter(i -> i.name.equals("single-image.mht")).findFirst().orElseThrow();

        GroupItem<PreviewItem> g = ws.group(List.of(simple, titles));
        assertNotNull(g);
        assertSame(g, simple.parentGroup);
        assertNull(image.parentGroup, "未参与建组的条目应留在顶层");
        assertEquals(2, g.getMembers().size());

        // ---------- ④ 参数作用域：三层覆盖解析（组 > 条目 > 全局） ----------
        AppConfig globalImage = new AppConfig();
        globalImage.setEnableBatchRenaming(false);
        AppConfig groupImage = new AppConfig();
        groupImage.setEnableBatchRenaming(true);
        g.setImageParamsCache(groupImage);

        AppConfig imageCfg = new AppConfig();
        imageCfg.setEnableBatchRenaming(false);

        // 组内成员：组参数优先
        assertSame(groupImage, ParamScope.effectiveImageParams(g, null, globalImage),
                "组参数应覆盖全局");
        // 顶层条目：无组、无条目参数 → 回落全局
        assertSame(globalImage, ParamScope.effectiveImageParams(null, null, globalImage));
        // 顶层条目：有条目参数 → 条目优先于全局
        assertSame(imageCfg, ParamScope.effectiveImageParams(null, imageCfg, globalImage));

        // ---------- ⑤ 移除 + 撤销（工作区语义） ----------
        assertEquals(1, ws.removeItems(List.of(image)));
        assertTrue(image.removed);
        assertEquals(3, ws.visibleCount(), "4 项中移除 1 项");
        assertTrue(ws.undo(), "应能撤销移除");
        assertFalse(image.removed);
        assertEquals(4, ws.visibleCount());

        // ---------- ⑥ 预览（无头）：app 层自建缓存，桩 UI 只需"读缓存目录 + 解码" ----------
        // 这一步是步骤 2/4 的核心：预览的业务逻辑（算键/查缓存/解析+抽图+原子落盘/元数据）
        // 已全部在 com.mhtextractor.app.PreviewService 里，**不需要任何 UI 框架**。
        // 桩 UI 拿到的只是"图片目录 + 缩略图元数据（名字）"，解码与上屏留给各 UI 自己。
        PreviewItem cnItem = items.stream()
                .filter(i -> i.name.equals("cn-filename.mht")).findFirst().orElseThrow();
        AppConfig previewCfg = imageCfgWithImageMode();
        Path cacheRoot = Files.createDirectories(tmp.resolve("preview-cache"));
        PreviewCacheLifecycle lifecycle =
                new PreviewCacheLifecycle(new PreviewCacheStore(cacheRoot));
        PreviewService previewSvc = new PreviewService(lifecycle);

        PreviewService.PreviewResult pv = previewSvc.ensureCached(Path.of(cnItem.path), previewCfg, null);
        assertNotNull(pv, "无头预览应建立缓存");
        // 夹具 cn-filename.mht 内含 2 张图片 → meta 与缩略图元数据都应是 2 条
        assertEquals(2, pv.meta().images().size(), "应抽出 2 张图片");
        assertEquals(pv.meta().images().size(), pv.thumbs().size(),
                "缩略图元数据数应与图片数一致");

        // 会话级缓存的三要素（C1）：meta.json + images/ + .complete，且每张图真实可读
        for (PreviewCacheMeta.ImageEntry entry : pv.meta().images()) {
            Path img = pv.imagesDir().resolve(entry.file());
            assertTrue(Files.exists(img), "缓存图片应真实存在：" + entry.file());
            byte[] bytes = Files.readAllBytes(img);
            assertTrue(bytes.length > 0, "缓存图片不应为空：" + entry.file());
            // 非 JavaFX 消费者必须能自己认出格式 —— 魔数即证据（这正是"换 UI 框架仍可用"的前提）
            assertTrue(hasKnownImageMagic(bytes), "缓存图片应为可识别的图片格式：" + entry.file());
        }
        // 缩略图元数据只有名字，像素留给渲染层（服务不产像素）
        for (var t : pv.thumbs()) {
            assertNull(t.pixels(), "像素应由渲染层解码填充，服务不产出像素");
        }

        // 再查一次：命中（不重新解析）
        PreviewService.PreviewResult pv2 = previewSvc.ensureCached(Path.of(cnItem.path), previewCfg, null);
        assertNotNull(pv2);
        assertEquals(pv.meta().images().size(), pv2.meta().images().size());

        // ---------- ⑦ 提取（图片，复用 ⑥ 的缓存）→ 与 GUI 侧黄金基线逐字节一致 ----------
        List<String> cachedHashes = new ArrayList<>();
        for (PreviewCacheMeta.ImageEntry entry : pv.meta().images()) {
            cachedHashes.add(BaselineHasher.sha256(Files.readAllBytes(pv.imagesDir().resolve(entry.file()))));
        }

        Path imageOut = Files.createDirectories(tmp.resolve("out-image"));
        List<String> progress = new ArrayList<>();
        MhtExtractorApp.ExtractResult imgResult = app.extractImages(
                List.of(Path.of(cnItem.path)),
                previewCfg,
                imageOut,
                (idx, total, name, frac) -> progress.add(idx + "/" + total),
                () -> false,
                lifecycle);          // ← 注入同一个生命周期 = 复用 ⑥ 落盘的预览缓存

        assertEquals(1, imgResult.stats().getTotalFiles());
        assertFalse(progress.isEmpty(), "桩 UI 应收到进度回调");
        assertProductsMatchBaseline(imageOut, "image-cn-filename", "zh");
        assertEquals(baselineProducts("image-cn-filename", "zh"),
                BaselineHasher.hashTree(imageOut),
                "无头提取产物应与 GUI 侧基线逐字节一致");

        // 复用等价性：输出图片的字节与预览缓存里的图片一致。
        //
        // ⚠️ 诚实说明（由变异测试实测得出）：本断言**不能**证明"确实复用了缓存" ——
        // 把上面 extractImages 的 lifecycle 换成 null（禁用复用）后，这条断言**仍然通过**，
        // 因为重新提取产生的字节本来就与缓存里的一致。它证明的是"重算与复用结果等价"。
        // 真正证明"复用了缓存"的是紧随其后的规则 (b) 断言：只有走了缓存命中路径，
        // 项目缓存目录才会在提取完成后被删除（变异测试实测：禁用复用时该断言失败）。
        List<String> outHashes = baselineProducts("image-cn-filename", "zh").stream()
                .filter(e -> isImagePath(e.path()))
                .map(BaselineHasher.TreeEntry::sha256)
                .sorted()
                .toList();
        assertEquals(cachedHashes.stream().sorted().toList(), outHashes,
                "重算与复用产出的图片字节应完全一致（等价性，非复用证据）");

        // 规则 (b)：提取完成后该项目缓存被删除 —— 这一条才是"确实复用了缓存"的**哨兵断言**
        PreviewCacheKey usedKey = PreviewCacheKey.compute(Path.of(cnItem.path),
                previewCfg.imageExtractionFingerprint(), PreviewCacheMeta.FORMAT_VERSION);
        assertFalse(Files.exists(lifecycle.store().dirFor(usedKey)),
                "提取完成后该项目缓存目录应被删除（规则 b）；若此断言失败，说明提取没有走缓存命中路径");

        // ---------- ⑧ 提取（文本，合并）→ 同样与基线一致 ----------
        Path textOut = Files.createDirectories(tmp.resolve("out-text"));
        AppConfig textParams = new AppConfig();
        textParams.setEnableTemplate(true);
        textParams.setStartTemplate("=== {filename} @ {date} {time} ===\\n");
        textParams.setEndTemplate("\\n=== {filename} 结束 ===\\n");

        MhtExtractorApp.ExtractResult txtResult = app.extractText(
                List.of(Path.of(simple.path)), textParams, textOut,
                MhtExtractorApp.DEFAULT_MERGED_TEXT_NAME, null, null);

        assertEquals(1, txtResult.stats().getSuccessCount());
        assertEquals(baselineProducts("text-template-vars", "zh"),
                BaselineHasher.hashTree(textOut),
                "无头文本产物应与 GUI 侧基线逐字节一致");
    }

    /** 输出图片的魔数判定（零 JavaFX；证明"换 UI 框架后仍能自己认格式"） */
    private static boolean hasKnownImageMagic(byte[] b) {
        if (b.length < 4) return false;
        int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF, b2 = b[2] & 0xFF, b3 = b[3] & 0xFF;
        boolean png = b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47;
        boolean jpg = b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF;
        boolean gif = b0 == 0x47 && b1 == 0x49 && b2 == 0x46;
        boolean bmp = b0 == 0x42 && b1 == 0x4D;
        boolean webp = b.length >= 12 && b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46
                && b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50;
        return png || jpg || gif || bmp || webp;
    }

    private static boolean isImagePath(String p) {
        String s = p.toLowerCase(Locale.ROOT);
        return s.endsWith(".png") || s.endsWith(".jpg") || s.endsWith(".jpeg")
                || s.endsWith(".gif") || s.endsWith(".bmp") || s.endsWith(".webp");
    }

    /** 图片提取用的参数（图片模式开启） */
    private static AppConfig imageCfgWithImageMode() {
        AppConfig c = new AppConfig();
        c.setImageModeEnabled(true);
        return c;
    }

    // ==================== 前置校验与工作区的联动 ====================

    @Test
    void preconditionsRejectEmptyWorkspaceWithTheRightReason() throws Exception {
        original = I18n.currentLocale();
        I18n.setLocale(Locale.SIMPLIFIED_CHINESE);

        Path in = prepareInput(List.of("simple-text.mht"));
        Path out = Files.createDirectories(tmp.resolve("out2"));
        WorkspaceModel ws = new WorkspaceModel();

        // 从未导入 → NO_MHT_FOUND
        assertEquals(ErrorCode.NO_MHT_FOUND,
                ExtractionPreconditions.validate(factsOf(in, out, ws.allItems())));

        // 导入后可见 → 允许
        MhtExtractorApp app = new MhtExtractorApp();
        PreviewItem a = new PreviewItem(in.resolve("simple-text.mht").toString(),
                "simple-text.mht", LocalDateTime.now(), in.toString());
        ws.addItems(List.of(a));
        assertNull(ExtractionPreconditions.validate(factsOf(in, out, ws.allItems())));

        // 全部移除 → KEEP_AT_LEAST_ONE（与"从未导入"区分开）
        ws.removeItems(List.of(a));
        assertEquals(ErrorCode.KEEP_AT_LEAST_ONE,
                ExtractionPreconditions.validate(factsOf(in, out, ws.allItems())));

        // 输入目录不存在 → INPUT_DIR_MISSING，且其级别是 ERROR（唯一）
        var missing = ExtractionPreconditions.inputNotDirectory(tmp.resolve("nope").toString());
        assertEquals(ErrorCode.INPUT_DIR_MISSING, missing);
        assertEquals(ExtractionPreconditions.Level.ERROR, ExtractionPreconditions.levelOf(missing));
    }

    /** 工作区操作序列（建组→拆组→撤销）应回到初始状态，证明模型自洽 */
    @Test
    void groupThenUngroupThenUndoRestoresOriginalLayout() throws Exception {
        original = I18n.currentLocale();
        I18n.setLocale(Locale.SIMPLIFIED_CHINESE);

        WorkspaceModel ws = new WorkspaceModel();
        PreviewItem a = new PreviewItem("C:/x/a.mht", "a.mht", LocalDateTime.now(), "C:/x");
        PreviewItem b = new PreviewItem("C:/x/b.mht", "b.mht", LocalDateTime.now(), "C:/x");
        ws.addItems(List.of(a, b));
        // 注意：ws.topLevel() 返回**实时**列表的视图，必须拷贝快照，
        // 否则 assertEquals(initialTop, ws.topLevel()) 等于拿列表和自己比，永远通过（空断言）。
        List<Object> initialTop = List.copyOf(ws.topLevel());

        GroupItem<PreviewItem> g = ws.group(List.of(a, b));
        assertNotNull(g);
        assertEquals(1, ws.topLevel().size());

        // 撤销建组 → 回到初始顶层顺序
        assertTrue(ws.undo());
        assertEquals(initialTop, ws.topLevel(), "撤销建组应精确恢复原顶层顺序");
        assertFalse(ws.topLevel().contains(g));

        // 再建一次并拆组
        GroupItem<PreviewItem> g2 = ws.group(List.of(a, b));
        assertNotNull(g2);
        List<PreviewItem> members = ws.ungroup(g2);
        assertEquals(2, members.size());
        assertEquals(2, ws.topLevel().size());
        assertEquals(initialTop, ws.topLevel(), "拆组应回到原顶层顺序");
    }
}
