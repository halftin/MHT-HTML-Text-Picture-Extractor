package com.mhtextractor.baseline;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.processor.BatchProcessor;
import com.mhtextractor.processor.ImageBatchProcessor;
import com.mhtextractor.processor.TextBatchProcessor;
import com.mhtextractor.util.SourceKinds;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 产物基线用例集与运行器（会话 18 · E0.3）。
 *
 * <p>把"用例定义 + 无头运行 + 摘要 JSON 序列化"从测试类里独立出来，理由：</p>
 * <ol>
 *   <li>基线的<b>定义</b>与基线的<b>断言</b>是两件事，分开后各自可独立演进；</li>
 *   <li>E6.2 的桩 UI 验收要复用同一批用例（换 ProgressSink 实现即可），
 *       因此用例集必须是可复用的公开 API，而不是测试类里的私有方法；</li>
 *   <li>本类**零 JavaFX**，是 Layer B（无头驱动 API）的第一块砖。</li>
 * </ol>
 */
public final class BaselineCases {

    /** 确定性锚：2026-01-02T03:04:05 本地时间。所有夹具统一此 mtime（{datetime} 模板变量会消费它）。 */
    public static final long FIXED_MTIME_EPOCH_MILLI = 1767294245000L;

    /** 夹具所在目录（Maven 单测 CWD = 项目根） */
    public static final Path FIXTURE_DIR = Path.of("src", "test", "resources", "fixtures");

    private BaselineCases() {
    }

    // ==================== 用例定义 ====================

    /**
     * 一个基线用例。
     *
     * @param name         用例名（= 基线文件名前缀）
     * @param fixtures     参与的夹具文件名（顺序即处理顺序）
     * @param config       提取参数
     * @param imageMode    true = 图片链，false = 文本链
     * @param mergedOutput true = 合并输出（文本=单 TXT；图片=单目录），false = 单独输出
     * @param note         人读说明（写入基线，便于日后判断"这条基线在守什么"）
     * @param knownIssues  本用例固化的已知缺陷（会话 17 测试方法设计 §3.6 要求显式记录）
     */
    public record Case(String name, List<String> fixtures, AppConfig config, boolean imageMode,
                       boolean mergedOutput, String note, List<String> knownIssues) {
    }

    private static AppConfig imageConfig() {
        AppConfig c = new AppConfig();
        c.setImageModeEnabled(true);
        return c;
    }

    /** 全部用例。参数刻意覆盖需求 2 的指纹字段（名称提取/区域限定/重命名/单独模式）。 */
    public static List<Case> all() {
        List<Case> out = new ArrayList<>();

        // ---------- 文本 ----------
        AppConfig t1 = new AppConfig();
        t1.setAddChapterNumber(true);
        out.add(new Case("text-merged-chapter", List.of("simple-text.mht", "titles.mht"),
                t1, false, true, "合并文本 + 章节序号（zh 中文数字 / en Western）", List.of()));

        AppConfig t2 = new AppConfig();
        t2.setAddChapterNumber(true);
        t2.setEnableTitleExtraction(true);
        t2.setTitleStartMarker("<!--NAME_START-->");
        t2.setTitleEndMarker("<!--NAME_END-->");
        t2.setSeparateFiles(true);
        out.add(new Case("text-separate-titled",
                List.of("simple-text.mht", "titles.mht", "multi-image.mht"),
                t2, false, false, "单独文本 + 标题提取命名（无标记文件回退原名）", List.of()));

        AppConfig t3 = new AppConfig();
        t3.setEnableContentLimit(true);
        t3.setStartMarker("<!--START-->");
        t3.setEndMarker("<!--END-->");
        t3.setUseRegex(false);
        out.add(new Case("text-region-literal", List.of("region.mht"),
                t3, false, true, "内容限定（字面量标记，走 HtmlUtils.extractRegion）", List.of()));

        AppConfig t4 = new AppConfig();
        t4.setEnableContentLimit(true);
        t4.setStartMarker("<!--START-->");
        t4.setEndMarker("<!--END-->");
        t4.setUseRegex(true);
        out.add(new Case("text-region-regex", List.of("region.mht"),
                t4, false, true, "内容限定（正则；P0-4 修复点：此前语义反转为 Pattern.quote）", List.of()));

        AppConfig t5 = new AppConfig();
        t5.setEnableTemplate(true);
        t5.setStartTemplate("=== {filename} @ {date} {time} ===\\n");
        t5.setEndTemplate("\\n=== {filename} 结束 ===\\n");
        out.add(new Case("text-template-vars", List.of("simple-text.mht"),
                t5, false, true, "自定义模板变量 {filename}/{date}/{time}/\\n（契约见耦合点 #23）", List.of()));

        AppConfig t6 = new AppConfig();
        out.add(new Case("text-boundary", List.of("garbage.mht", "no-cte.mht", "deep-nesting.mht"),
                t6, false, true,
                "失败/跳过/递归截断路径（garbage 不可解析、no-cte 缺 CTE、deep 超 100 层截断）",
                List.of()));

        // ---------- 图片：单独输出 ----------
        AppConfig i1 = imageConfig();
        out.add(new Case("image-separate", List.of("single-image.mht", "multi-image.mht"),
                i1, true, false, "单独输出（cid + Content-Location 两种来源）", List.of()));

        // ---------- 曾登记为缺陷的隔离用例（步骤 7 已修复） ----------
        AppConfig i1b = imageConfig();
        out.add(new Case("image-data-uri", List.of("data-uri.mht"),
                i1b, true, false,
                "纯 Data URI 图片（无 cid/Content-Location part）—— 原为缺陷隔离用例，步骤 7 已修复",
                List.of()));

        AppConfig i1c = imageConfig();
        out.add(new Case("image-data-uri-mixed", List.of("data-uri-mixed.mht"),
                i1c, true, false,
                "1 张 cid 图 + 2 张 Data URI —— 隔离上述缺陷："
                        + "有 cid 时快速检查放行，可判定 Data URI 提取本身是否可用",
                List.of()));

        AppConfig i2 = imageConfig();
        i2.setEnableBatchRenaming(true);
        out.add(new Case("image-separate-renamed", List.of("single-image.mht", "multi-image.mht"),
                i2, true, false, "单独输出 + 批量重命名（00001.ext 序号 + 魔数扩展名纠正）", List.of()));

        // ---------- 图片：合并输出 ----------
        AppConfig i3 = imageConfig();
        i3.setSingleOutputMode(true);
        out.add(new Case("image-merged", List.of("single-image.mht", "multi-image.mht"),
                i3, true, true, "合并输出（全局序号、f%02d_ 前缀、CREATE_NEW 不覆盖）", List.of()));

        AppConfig i4 = imageConfig();
        i4.setSingleOutputMode(true);
        i4.setHighSpeedMemoryMode(true);
        out.add(new Case("image-merged-preload", List.of("single-image.mht", "multi-image.mht"),
                i4, true, true, "高速内存（预加载）模式 —— P0-1 索引错位修复点的产物锁定", List.of()));

        // ---------- 图片：区域限定 + 名称提取（指纹字段覆盖） ----------
        AppConfig i5 = imageConfig();
        i5.setEnableRegionExtraction(true);
        i5.setImageRegionStartMarker("<!--START-->");
        i5.setImageRegionEndMarker("<!--END-->");
        i5.setEnableNameExtraction(true);
        i5.setImageNameStartMarker("<!--NAME_START-->");
        i5.setImageNameEndMarker("<!--NAME_END-->");
        i5.setSingleOutputMode(true);
        out.add(new Case("image-region-named", List.of("region.mht", "titles.mht"),
                i5, true, true, "区域限定 + 名称提取（指纹 8 字段中 6 个参与）", List.of()));

        // ---------- 归档 ----------
        AppConfig i6 = imageConfig();
        i6.setEnableZipPackaging(true);
        out.add(new Case("image-zip", List.of("single-image.mht", "multi-image.mht"),
                i6, true, false, "单独输出 + ZIP 打包（P0-2 归档失败不删源）。"
                        + "摘要比对归档内条目解压后 SHA，非归档字节（含时间戳）", List.of()));

        AppConfig i7 = imageConfig();
        i7.setEnableCbzPackaging(true);
        out.add(new Case("image-cbz", List.of("single-image.mht", "multi-image.mht"),
                i7, true, false, "单独输出 + CBZ 打包",
                List.of("登记表 #3：createZip/createCbz 产出字节相同，CBZ 实际只是换扩展名")));

        // ---------- 中文文件名（P0-8） ----------
        AppConfig i8 = imageConfig();
        out.add(new Case("image-cn-filename", List.of("cn-filename.mht"),
                i8, true, false, "RFC 2047 编码中文文件名解码（P0-8 修复点）",
                List.of("裸 UTF-8 中文文件名（未用 RFC 2047）由 Jakarta Mail 按 ISO-8859-1 读头 → 预期乱码；"
                        + "基线锁定该既有行为，**不作为期望值**")));

        // ---------- Q11 / R6.7：HTML 与 HTML+侧车（浏览器"另存为"产物） ----------
        // ⚠️ 夹具写的是**目录**（如 html/sidecar）：HTML 与侧车必须保持"同目录同名"的相对关系，
        // 单拷 .html 会把 page_files/ 丢掉（那样断言的就只是"纯 HTML 无图"，与本用例的意图相反）。
        AppConfig h1 = new AppConfig();
        h1.setAddChapterNumber(true);
        out.add(new Case("html-plain", List.of("html/plain/plain.html", "simple-text.mht"),
                h1, false, true, "纯 HTML（无侧车）与 .mht 同批：验证\"混合来源同一条链路\" + 章节序号",
                List.of()));

        AppConfig h2 = imageConfig();
        out.add(new Case("html-sidecar-relative", List.of("html/sidecar"),
                h2, true, false,
                "HTML+侧车多图：相对路径图片（含中文名）+ Data URI；缺失文件 / ../ 逃逸 / 绝对 URL / "
                        + "侧车子目录一律跳过（见 HtmlFolderParser 的支持矩阵）",
                List.of()));

        AppConfig h3 = imageConfig();
        out.add(new Case("html-same-dir", List.of("html/same-dir"),
                h3, true, true, "图片与 HTML 同目录（无侧车）的扁平形态",
                List.of()));

        AppConfig h4 = imageConfig();
        out.add(new Case("html-cn-filename", List.of("html/中文页面"),
                h4, true, false, "HTML 名、侧车目录名、图片名全含中文的路径口径",
                List.of()));

        // ---------- HTML + 模板变量（决策 A：{filename} = 完整来源文件名，不剥扩展名） ----------
        AppConfig h5 = new AppConfig();
        h5.setEnableTemplate(true);
        h5.setStartTemplate("=== {filename} @ {date} {time} ===\\n");
        h5.setEndTemplate("\\n=== {filename} 结束 ===\\n");
        out.add(new Case("html-template-vars", List.of("html/plain/plain.html"),
                h5, false, true,
                "HTML 源 + 模板变量：锁定 {filename} = 完整来源文件名（plain.html），即完整文件名契约（决策 A）",
                List.of()));

        return out;
    }

    // ==================== 运行 ====================

    public record StatsSummary(int total, int success, int fail, int skip, int warn,
                               List<String> outcomes) {
    }

    public record RunResult(Path sessionOut, List<BaselineHasher.TreeEntry> products,
                            StatsSummary stats, Map<String, String> contentDigests) {

        public boolean allEquals(RunResult o) {
            return products.equals(o.products) && stats.equals(o.stats)
                    && contentDigests.equals(o.contentDigests);
        }
    }

    /**
     * 无头运行一个用例：准备输入 → 跑提取链 → 摘要产物。
     *
     * @param workDir 本次运行的独立工作目录（输入/输出都放它下面）—— 每次调用必须给不同目录，
     *                否则"第二遍读到第一遍的产物"会造成假绿
     */
    public static RunResult run(Case c, Path workDir) throws Exception {
        Path input = workDir.resolve("in");
        Path sessionOut = workDir.resolve("out").resolve("session_20260102_030405");
        Files.createDirectories(sessionOut);

        List<BatchProcessor.MhtFileInfo> files = prepareInput(c, input);

        // 产物与统计来自**同一次**运行
        BatchProcessor.ProcessStats stats;
        if (c.imageMode()) {
            ImageBatchProcessor p = new ImageBatchProcessor(c.config(), null);
            stats = c.mergedOutput()
                    ? p.process(files, sessionOut.toString(), null, null)
                    : p.process(files, sessionOut.toString(), null);
        } else {
            TextBatchProcessor p = new TextBatchProcessor(c.config());
            stats = c.mergedOutput()
                    ? p.processMerged(files, sessionOut.resolve("merged.txt").toString(), null,
                            Collections.emptyMap())
                    : p.processSeparate(files, sessionOut.toString(), null, Collections.emptyMap());
        }

        List<String> outcomes = new ArrayList<>();
        stats.getFileResults().forEach(r -> outcomes.add(fileName(r.path()) + "=" + r.outcome()));
        outcomes.sort(Comparator.naturalOrder());

        StatsSummary summary = new StatsSummary(stats.getTotalFiles(), stats.getSuccessCount(),
                stats.getFailCount(), stats.getSkipCount(), stats.getWarnCount(), outcomes);

        return summarize(sessionOut, summary, workDir);
    }

    /** Same fixtures and deterministic mtimes for processor, CLI and native baseline runners. */
    public static List<BatchProcessor.MhtFileInfo> prepareInput(Case c, Path input) throws IOException {
        Files.createDirectories(input);
        List<BatchProcessor.MhtFileInfo> files = new ArrayList<>();
        for (String fx : c.fixtures()) {
            Path src = FIXTURE_DIR.resolve(fx);
            if (!Files.exists(src)) {
                throw new IllegalStateException("夹具缺失：" + src
                        + "（运行 node .claude-work/gen-fixtures.js 生成）");
            }
            Path dst = input.resolve(fx);
            if (Files.isDirectory(src)) {
                // R6.7：HTML+侧车夹具是**目录树**（page.html + page_files/*.png），
                // 整个目录原样复制，保持"HTML 与侧车同目录同名"的相对关系；
                // 复制后统一 mtime，保证基线确定性自检（同例跑两遍必须逐字节一致）。
                List<Path> sources = copyTree(src, dst);
                setFixedMtime(dst);
                for (Path s : sources) {
                    files.add(BatchProcessor.toMhtFileInfo(s.toFile()));
                }
            } else {
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                Files.setLastModifiedTime(dst, FileTime.fromMillis(FIXED_MTIME_EPOCH_MILLI));
                files.add(BatchProcessor.toMhtFileInfo(dst.toFile()));
            }
        }

        return files;
    }

    /** ProductBaselineTest's exact product/content digest contract, with externally observed stats. */
    public static RunResult summarize(Path sessionOut, StatsSummary summary, Path workDir) throws IOException {
        List<BaselineHasher.TreeEntry> products = BaselineHasher.hashTree(sessionOut);
        Map<String, String> contentDigests = contentDigests(sessionOut, products, workDir);
        return new RunResult(sessionOut, products, summary, contentDigests);
    }

    /**
     * 递归复制夹具目录树（保留子目录结构；不跟随符号链接）。
     *
     * <p>⚠️ 必须先 {@code toList()} 把路径全部收集完再开始复制：{@code Files.walk} 是<b>惰性</b>的，
     * 而复制过程会在目标树下 {@code createDirectories}；若遍历仍在进行，目标目录一旦落在源树下
     * 就会被"越走越深"式地卷进来（本步实测过一次，表现为复制出一堆重复层级）。</p>
     *
     * @return 复制后目录内的候选来源文件（扩展名判定走 {@link SourceKinds}）
     */
    private static List<Path> copyTree(Path src, Path dst) throws IOException {
        List<Path> paths;
        try (var walk = Files.walk(src)) {
            paths = walk.toList();
        }
        List<Path> candidates = new ArrayList<>();
        for (Path p : paths) {
            Path target = dst.resolve(src.relativize(p).toString());
            if (Files.isDirectory(p)) {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                if (SourceKinds.isImportable(target.getFileName().toString())) {
                    candidates.add(target);
                }
            }
        }
        candidates.sort(Comparator.comparing(Path::toString));
        return candidates;
    }

    /**
     * 整棵子树统一锚点 mtime（含目录），消除"复制顺序不同导致 mtime 不同"的基线噪声 */
    private static void setFixedMtime(Path root) throws IOException {
        List<Path> paths;
        try (var walk = Files.walk(root)) {
            paths = walk.toList();
        }
        for (Path p : paths) {
            Files.setLastModifiedTime(p, FileTime.fromMillis(FIXED_MTIME_EPOCH_MILLI));
        }
    }

    /**
     * 内容摘要：文本文件剔除"环境相关"部分（绝对值、来源装饰行）后再摘要；
     * 归档文件记"条目名 → 解压后 SHA"（归档字节含时间戳，不能直接比）。
     */
    private static Map<String, String> contentDigests(Path sessionOut,
                                                      List<BaselineHasher.TreeEntry> products,
                                                      Path absRootToStrip) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (BaselineHasher.TreeEntry e : products) {
            Path f = sessionOut.resolve(e.path());
            String lower = e.path().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".txt")) {
                String text = BaselineHasher.normalizeText(
                        Files.readString(f, StandardCharsets.UTF_8), absRootToStrip);
                StringBuilder keep = new StringBuilder();
                for (String line : text.split("\n", -1)) {
                    if (!BaselineHasher.isDecorationLine(line)) keep.append(line).append('\n');
                }
                out.put(e.path(), BaselineHasher.sha256(keep.toString()));
            } else if (lower.endsWith(".zip") || lower.endsWith(".cbz")) {
                for (BaselineHasher.TreeEntry ze : BaselineHasher.listArchiveEntries(f)) {
                    out.put(e.path() + "!" + ze.path(), ze.sha256());
                }
            }
        }
        return out;
    }

    private static String fileName(String path) {
        String p = path.replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1);
    }

    // ==================== 基线 JSON ====================

    /** 捕获开关：系统属性优先，环境变量兜底（surefire 直接透传 -D 属性，无需改 pom） */
    public static boolean captureRequested() {
        String prop = System.getProperty("mht.baseline.capture");
        if (prop != null && !prop.isBlank() && !prop.equalsIgnoreCase("false")) return true;
        String env = System.getenv("MHT_BASELINE_CAPTURE");
        return env != null && !env.isBlank();
    }

    public static String toJson(Case c, String loc, RunResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"case\": ").append(str(c.name())).append(",\n");
        sb.append("  \"locale\": ").append(str(loc)).append(",\n");
        sb.append("  \"imageMode\": ").append(c.imageMode()).append(",\n");
        sb.append("  \"mergedOutput\": ").append(c.mergedOutput()).append(",\n");
        sb.append("  \"fixtures\": ").append(strList(c.fixtures())).append(",\n");
        sb.append("  \"note\": ").append(str(c.note())).append(",\n");
        sb.append("  \"knownIssues\": ").append(strList(c.knownIssues())).append(",\n");
        sb.append("  \"stats\": {\n");
        sb.append("    \"total\": ").append(r.stats().total()).append(",\n");
        sb.append("    \"success\": ").append(r.stats().success()).append(",\n");
        sb.append("    \"fail\": ").append(r.stats().fail()).append(",\n");
        sb.append("    \"skip\": ").append(r.stats().skip()).append(",\n");
        sb.append("    \"warn\": ").append(r.stats().warn()).append(",\n");
        sb.append("    \"outcomes\": ").append(strList(r.stats().outcomes())).append("\n");
        sb.append("  },\n");
        sb.append("  \"products\": [\n");
        for (int i = 0; i < r.products().size(); i++) {
            BaselineHasher.TreeEntry e = r.products().get(i);
            sb.append("    {\"path\": ").append(str(e.path()))
                    .append(", \"sha256\": ").append(str(e.sha256())).append("}");
            sb.append(i + 1 < r.products().size() ? ",\n" : "\n");
        }
        sb.append("  ],\n");
        sb.append("  \"contentDigests\": {\n");
        int i = 0;
        for (Map.Entry<String, String> e : r.contentDigests().entrySet()) {
            sb.append("    ").append(str(e.getKey())).append(": ").append(str(e.getValue()));
            sb.append(++i < r.contentDigests().size() ? ",\n" : "\n");
        }
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    public static String normalizeJson(String json) {
        return json.replace("\r\n", "\n").strip();
    }

    private static String str(String s) {
        return "\"" + escape(s) + "\"";
    }

    private static String strList(List<String> list) {
        List<String> copy = new ArrayList<>(list);
        copy.sort(Comparator.naturalOrder());
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < copy.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(str(copy.get(i)));
        }
        return sb.append("]").toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.toString();
    }
}
