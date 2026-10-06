package com.mhtextractor.processor;

import com.mhtextractor.baseline.SourceScanUtils;
import com.mhtextractor.cache.PreviewCacheKey;
import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.cache.PreviewCacheMeta;
import com.mhtextractor.cache.PreviewCacheStore;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.core.SourceParser;
import com.mhtextractor.core.SourceParsers;
import com.mhtextractor.model.ExtractOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q12 / §3.2 交叉回归矩阵：<b>高速预加载 / 流式 × 合并 / 单独</b> 四种模式的产物与语义锁定。
 *
 * <p><b>为什么单独一个测试类</b>：这一步是 Q12 的<b>验收面本身</b>（指南 §3"具体迁移顺序"第 1 步：
 * "先用现有私有接口冻结输入/输出契约，给四种模式建立交叉回归用例"）。它与既有的
 * {@code ImageBatchProcessorTest}（27 项，覆盖 P0-1/2/3 与缓存规则）分工不同：本类只回答
 * "四种模式<b>各自</b>的产物形态、归档结果、进度与取消语义，以及模式间的交叉等价"。</p>
 *
 * <p><b>测量装置</b>：{@link ParserProbe} 覆写 {@code ImageBatchProcessor#parserFor}
 * （一次解析请求 = 一次计数）并可在第 N 次请求时向处理器请求取消。
 * "流式合并模式首文件只解析一次"的<b>被测 + 控制组</b>见
 * {@link #streamingMergedParsesFirstFileExactlyOnce()} /
 * {@link #streamingMergedControlGroupReparsesWhenPreParseFails()}。</p>
 *
 * <p><b>⚠️ 本类如实记录了两处"与直觉不符的既有行为</b>（非本步引入，也未在本步改动）：</p>
 * <ol>
 *   <li>文件进度<b>不是严格单调</b>：解析阶段末尾的 {@code IMAGES_EXTRACTED} 阶段回调（0.30）会先于
 *       解析器细粒度字节进度（0.25）到达 ⇒ 实测出现 0.30 → 0.25 的回跳（幅度 0.05）。断言按实测口径写。</li>
 *   <li>取消语义<b>因输入模式而不同</b>（正是指南 §1.4-1"两种输入策略不应合并"的实证）：流式模式在
 *       取消请求发出后，当前文件仍会走完（因为取消只在主循环顶部被检查），而预加载模式在预加载阶段
 *       取消则主循环一次都不进。<b>两种模式的"被解析文件数"因此不同</b>，本类分别断言。</li>
 * </ol>
 */
class ImageExtractionMatrixTest {

    @TempDir
    Path tmp;

    // ==================== 夹具 ====================

    /** 含一张 webp（Content-ID: img001）的合法 MHT；extraHtml 插在 <body> 开头 */
    private Path writeImageMht(Path dir, String fileName, String extraHtml) throws Exception {
        byte[] webpBytes;
        try (InputStream is = getClass().getResourceAsStream("/sample-preview.webp")) {
            assertNotNull(is, "sample-preview.webp 资源缺失");
            webpBytes = is.readAllBytes();
        }
        String boundary = "----=_matrix_boundary";
        StringBuilder sb = new StringBuilder();
        sb.append("From: <test@example.com>\r\n");
        sb.append("Subject: ").append(fileName).append("\r\n");
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: multipart/related; boundary=\"").append(boundary).append("\"\r\n\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: text/html; charset=\"utf-8\"\r\n");
        sb.append("Content-Transfer-Encoding: 8bit\r\n\r\n");
        sb.append("<html><body>").append(extraHtml == null ? "" : extraHtml)
                .append("<img src=\"cid:img001\"></body></html>\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: image/webp\r\n");
        sb.append("Content-ID: <img001>\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
        sb.append(Base64.getEncoder().encodeToString(webpBytes)).append("\r\n");
        sb.append("--").append(boundary).append("--\r\n");
        Path f = dir.resolve(fileName);
        Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    /** 合法 MIME 但无 HTML 部件 → 解析成功而 htmlContent == null → SKIPPED（"HTML 为空"路径） */
    private Path writeNoHtmlMht(Path dir, String fileName) throws Exception {
        String boundary = "----=_matrix_noh_boundary";
        StringBuilder sb = new StringBuilder();
        sb.append("From: <test@example.com>\r\n");
        sb.append("Subject: ").append(fileName).append("\r\n");
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: text/plain; charset=\"utf-8\"\r\n\r\n");
        sb.append("no html here\r\n");
        sb.append("--").append(boundary).append("--\r\n");
        Path f = dir.resolve(fileName);
        Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    /** 两种输入模式 */
    private enum Input {
        /** 高速内存（预加载） */
        PRELOAD("preload"),
        /** 流式 */
        STREAM("stream");

        final String label;

        Input(String label) {
            this.label = label;
        }
    }

    /** 两种输出模式 */
    private enum Output {
        /** 合并输出（单目录 + 全局序号 + 末尾归档） */
        MERGED("merged"),
        /** 单独输出（每文件一目录 + 逐文件归档） */
        SEPARATE("separate");

        final String label;

        Output(String label) {
            this.label = label;
        }
    }

    private AppConfig config(Path source, Input input, Output output) {
        AppConfig config = new AppConfig();
        config.setInputFolder(source.toString());
        config.setHighSpeedMemoryMode(input == Input.PRELOAD);
        config.setSingleOutputMode(output == Output.MERGED);
        return config;
    }

    /** 兜底合并目录名（{@code determineDefaultFolderName(inputFolder)}）：合并模式无标题时用它 */
    private static String defaultMergedName(Path inputFolder) {
        return ImageBatchProcessor.determineDefaultFolderName(inputFolder.toString());
    }

    // ==================== 产物树快照 ====================

    /** 相对路径 + SHA-256（目录以 "/" 结尾）——用于"模式间逐字节等价"与"产物名集合"断言 */
    private static List<String> snapshotTree(Path root) throws Exception {
        List<String> entries = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return entries;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted().toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (Files.isDirectory(p)) {
                    if (!p.equals(root)) {
                        entries.add(rel + "/");
                    }
                } else {
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
                    entries.add(rel + "  sha256=" + HexFormat.of().formatHex(digest));
                }
            }
        }
        return entries;
    }

    /** 只取相对路径（去哈希），用于"文件名与数量"断言 */
    private static Set<String> namesOf(Path root) throws Exception {
        Set<String> out = new TreeSet<>();
        for (String e : snapshotTree(root)) {
            out.add(e.split("  sha256=")[0]);
        }
        return out;
    }

    private static void assertTreeEquals(String label, Path expected, Path actual) throws Exception {
        assertEquals(snapshotTree(expected), snapshotTree(actual),
                label + "：两棵输出树必须逐字节一致（文件名 + 内容 SHA-256）");
    }

    /**
     * 只取<b>文件</b>相对路径（忽略目录项）。
     *
     * <p>用途：合并输出模式会<b>先建</b>合并目录再处理文件，因此"取消后不得产出"只能断言
     * "没有任何文件"，不能断言"输出目录为空"（那个空目录是既有行为，不是产物）。</p>
     */
    private static Set<String> fileNamesOf(Path root) throws Exception {
        Set<String> out = new TreeSet<>();
        for (String e : snapshotTree(root)) {
            if (!e.endsWith("/")) {
                out.add(e.split("  sha256=")[0]);
            }
        }
        return out;
    }

    // ==================== 进度装置 ====================

    /**
     * 进度记录器：以<b>有序文件下标</b>（{@code orderedNames[current-1]}）为键记录
     * {@code (current, subProgress)} 序列。
     *
     * <p>为什么按下标而不是文件名：矩阵用例里两个文件<b>同名</b>（重复文件名的极端形态），
     * 按名会合并成一条序列、失去"每个文件都收敛到 1.0"的判别力。</p>
     *
     * <p><b>实测口径</b>：不要求严格单调（见类 javadoc）。断言写成：</p>
     * <ul>
     *   <li>所有值 ∈ [0,1]；</li>
     *   <li>每个文件的<b>最后</b>一个进度恰为 1.0；</li>
     *   <li>单个文件的进度"回退次数"不超过 {@link #MAX_RESTARTS}（实测上界 2）。</li>
     * </ul>
     */
    private static final class ProgressRecorder implements BatchProcessor.ProgressCallback {

        /**
         * 单个文件允许的进度"回退次数"上界（实测 ≤ 2）。
         *
         * <p>为何不是 0（严格单调）：① 解析阶段末尾的 {@code IMAGES_EXTRACTED}（0.30）会先于
         * 解析器细粒度字节进度（0.25）到达；② 预加载模式下同一文件会被<b>两段流程</b>上报进度
         * （{@code preloadAll} 的 0.05→0.5→1.0，随后主循环提取的 0.3→…→1.0）。两者都是既有行为。</p>
         */
        static final int MAX_RESTARTS = 2;

        private final List<String> orderedNames;

        final Map<Integer, List<Double>> byIndex = new LinkedHashMap<>();

        ProgressRecorder(List<BatchProcessor.MhtFileInfo> files) {
            this.orderedNames = files.stream().map(BatchProcessor.MhtFileInfo::getName).toList();
        }

        @Override
        public void onProgress(int current, int total, String fileName, double subProgress) {
            int index = current - 1;
            assertEquals(orderedNames.get(index), fileName,
                    "进度回调的 current 必须与传入顺序一致（否则文件名会串位）");
            byIndex.computeIfAbsent(index, _ -> new ArrayList<>()).add(subProgress);
        }

        void assertSaneAndComplete(String label, int expectedFiles) {
            assertEquals(expectedFiles, byIndex.size(), label + "：应有 " + expectedFiles
                    + " 个文件上报进度，实际下标 " + byIndex.keySet());
            for (Map.Entry<Integer, List<Double>> e : byIndex.entrySet()) {
                List<Double> seq = e.getValue();
                double prev = -1;
                int restarts = 0;
                for (double p : seq) {
                    assertTrue(p >= 0.0 && p <= 1.0, label + "：进度必须在 [0,1]，实际 " + p);
                    if (p < prev) {
                        restarts++;
                    }
                    prev = p;
                }
                String who = orderedNames.get(e.getKey());
                assertEquals(1.0, seq.get(seq.size() - 1), 1e-9,
                        label + "：" + who + " 的最后一个进度必须是 1.0，实际 " + seq);
                assertTrue(restarts <= MAX_RESTARTS, label + "：" + who + " 的进度回退次数 "
                        + restarts + " 超过实测上界 " + MAX_RESTARTS + "，实际序列 " + seq);
            }
        }
    }

    // ==================== 1. 逐模式产物形态（命名 / 序号 / 重复文件名） ====================

    /**
     * 四组合的产物名集合 + 输入模式间的逐字节等价。
     *
     * <p><b>重复文件名</b>口径：两个输入文件<b>同名</b>（{@code d1/dup.mht} 与 {@code d2/dup.mht}，
     * 内容相同）：合并模式靠 {@code f%02d_} 前缀区分；单独模式两者标题相同 ⇒ 落到同一目录，
     * 靠 {@code _1} 后缀去重。</p>
     */
    @Test
    void fourCombinationsProductNamesAndCrossInputEquivalence() throws Exception {
        Path source = Files.createDirectories(tmp.resolve("in"));
        Path d1 = Files.createDirectories(source.resolve("d1"));
        Path d2 = Files.createDirectories(source.resolve("d2"));
        Path a1 = writeImageMht(d1, "dup.mht", "TITLE-START漫画A标题TITLE-END");
        Path a2 = writeImageMht(d2, "dup.mht", "TITLE-START漫画A标题TITLE-END");
        List<BatchProcessor.MhtFileInfo> files = BatchProcessor.buildMhtFileInfoList(
                List.of(a1.toString(), a2.toString()));

        Map<String, Set<String>> names = new LinkedHashMap<>();

        for (Input in : Input.values()) {
            for (Output out : Output.values()) {
                String key = in.label + "-" + out.label;
                AppConfig config = config(source, in, out);
                config.setEnableNameExtraction(true);
                config.setImageNameStartMarker("TITLE-START");
                config.setImageNameEndMarker("TITLE-END");
                Path outDir = Files.createDirectories(tmp.resolve("out-" + key));

                ProgressRecorder rec = new ProgressRecorder(files);
                BatchProcessor.ProcessStats stats = new ImageBatchProcessor(config)
                        .process(files, outDir.toString(), null, rec);

                rec.assertSaneAndComplete(key, 2);
                assertEquals(2, stats.getSuccessCount(), key + "：两份同名文件都应成功：" + stats);
                assertEquals(0, stats.getFailCount(), key + "：" + stats);
                assertEquals(0, stats.getSkipCount(), key + "：" + stats);
                names.put(key, namesOf(outDir));
            }
        }

        // ---- 单独输出：目录名来自标题；同名文件镜像到同一目录，靠 _1 后缀去重 ----
        for (Input in : Input.values()) {
            assertEquals(Set.of("漫画A标题/", "漫画A标题/img001.webp", "漫画A标题/img001_1.webp"),
                    names.get(in.label + "-separate"), in.label + "-separate 的产物树");
        }
        // ---- 合并输出：同名文件靠 f%02d_ 前缀区分（未开批量重命名时） ----
        for (Input in : Input.values()) {
            assertEquals(Set.of("漫画A标题/",
                            "漫画A标题/f01_img001.webp",
                            "漫画A标题/f02_img001.webp"),
                    names.get(in.label + "-merged"), in.label + "-merged 的产物树");
        }

        // ---- 交叉等价：同一输出模式下，预加载与流式必须产出逐字节相同的树 ----
        assertTreeEquals("merged：preload vs stream",
                tmp.resolve("out-preload-merged"), tmp.resolve("out-stream-merged"));
        assertTreeEquals("separate：preload vs stream",
                tmp.resolve("out-preload-separate"), tmp.resolve("out-stream-separate"));

        System.out.println("[q12-matrix] 四组合产物名：" + names);
    }

    /** 批量重命名开启时：合并模式走全局序号，单独模式每目录从 00001 起（命名差异的硬证据） */
    @Test
    void batchRenamingNamingDiffersBetweenMergedAndSeparate() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("in"));
        Path a = writeImageMht(src, "a.mht", null);
        Path b = writeImageMht(src, "b.mht", null);
        List<BatchProcessor.MhtFileInfo> files = BatchProcessor.buildMhtFileInfoList(
                List.of(a.toString(), b.toString()));
        String fallback = defaultMergedName(src);

        for (Input in : Input.values()) {
            AppConfig merged = config(src, in, Output.MERGED);
            merged.setEnableBatchRenaming(true);
            Path outMerged = Files.createDirectories(tmp.resolve("ren-" + in.label + "-merged"));
            BatchProcessor.ProcessStats s1 = new ImageBatchProcessor(merged)
                    .process(files, outMerged.toString(), null, null);
            assertEquals(2, s1.getSuccessCount(), in.label + "：" + s1);
            // 合并模式（无标题标记 ⇒ 目录名回退输入目录名）：全局序号跨文件递增到 00002
            assertEquals(Set.of(fallback + "/", fallback + "/00001.webp", fallback + "/00002.webp"),
                    namesOf(outMerged), in.label + " 合并 + 重命名");

            AppConfig separate = config(src, in, Output.SEPARATE);
            separate.setEnableBatchRenaming(true);
            Path outSeparate = Files.createDirectories(tmp.resolve("ren-" + in.label + "-separate"));
            BatchProcessor.ProcessStats s2 = new ImageBatchProcessor(separate)
                    .process(files, outSeparate.toString(), null, null);
            assertEquals(2, s2.getSuccessCount(), in.label + "：" + s2);
            // 单独模式：每个文件自己的目录里都从 00001 起
            assertEquals(Set.of("a/", "a/00001.webp", "b/", "b/00001.webp"),
                    namesOf(outSeparate), in.label + " 单独 + 重命名");
        }

        // 合并模式跨输入等价
        assertTreeEquals("重命名合并：preload vs stream",
                tmp.resolve("ren-preload-merged"), tmp.resolve("ren-stream-merged"));
    }

    // ==================== 2. 归档：ZIP/CBZ 成功与失败 ====================

    /**
     * 四组合 × ZIP 归档成功/失败。
     *
     * <p>语义差异（指南 §1.4-2）：<b>成功</b>时两种输出模式都删源目录、只剩归档；
     * <b>失败</b>时两者都保留源目录，但结果不同 —— 单独模式把归档失败记为该文件的
     * <b>FAILED</b>（异常从 saveImages 抛出 → 主循环异常分支），合并模式把归档失败记为
     * <b>整体 failCount +1</b>（末尾归档阶段，文件级仍 SUCCESS）。</p>
     */
    @Test
    void archiveSuccessAndFailureAcrossAllFourCombinations() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("in"));
        Path a = writeImageMht(src, "a.mht", null);
        String fallback = defaultMergedName(src);   // 合并模式（无名字提取）的目录名 = 输入目录名

        for (Input in : Input.values()) {
            for (Output out : Output.values()) {
                String key = in.label + "-" + out.label;
                String archiveBase = out == Output.MERGED ? fallback : "a";
                String sourceDirName = out == Output.MERGED ? fallback : "a";

                // ---- 归档成功：源目录被删，只剩 .zip ----
                AppConfig ok = config(src, in, out);
                ok.setEnableZipPackaging(true);
                Path outOk = Files.createDirectories(tmp.resolve("zipok-" + key));
                BatchProcessor.ProcessStats okStats = new ImageBatchProcessor(ok)
                        .process(BatchProcessor.buildMhtFileInfoList(List.of(a.toString())),
                                outOk.toString(), null, null);
                assertEquals(1, okStats.getSuccessCount(), key + " 归档成功：" + okStats);
                assertEquals(0, okStats.getFailCount(), key + " 归档成功：" + okStats);
                assertEquals(Set.of(archiveBase + ".zip"), namesOf(outOk),
                        key + " 归档成功后源目录必须被删除，只剩归档：" + namesOf(outOk));
                assertTrue(Files.size(outOk.resolve(archiveBase + ".zip")) > 0, key + "：归档不应为空");

                // ---- 归档失败：预置同名目录 → createZip 打开失败 → 源目录保留 ----
                AppConfig bad = config(src, in, out);
                bad.setEnableZipPackaging(true);
                Path outBad = Files.createDirectories(tmp.resolve("zipbad-" + key));
                Files.createDirectories(outBad.resolve(archiveBase + ".zip"));   // 占位目录使归档必失败
                BatchProcessor.ProcessStats badStats = new ImageBatchProcessor(bad)
                        .process(BatchProcessor.buildMhtFileInfoList(List.of(a.toString())),
                                outBad.toString(), null, null);

                Set<String> badNames = namesOf(outBad);
                assertTrue(badNames.contains(sourceDirName + "/"),
                        key + " 归档失败必须保留源目录：" + badNames);
                assertTrue(badNames.contains(sourceDirName + "/img001.webp"),
                        key + " 归档失败必须保留图片：" + badNames);
                assertTrue(badStats.getFailDetails().stream().anyMatch(d -> d.contains("归档")),
                        key + " 归档失败必须记入 failDetails：" + badStats);

                if (out == Output.SEPARATE) {
                    assertEquals(1, badStats.getFailCount(), key + "：" + badStats);
                    assertEquals(0, badStats.getSuccessCount(), key + "：" + badStats);
                    assertEquals(ExtractOutcome.FAILED, badStats.getFileResults().get(0).outcome(),
                            key + "：单独模式归档失败记为该文件 FAILED");
                } else {
                    assertEquals(1, badStats.getSuccessCount(),
                            key + "：合并模式文件级写入不受归档失败影响：" + badStats);
                    assertEquals(1, badStats.getFailCount(), key + "：" + badStats);
                    assertEquals(ExtractOutcome.SUCCESS, badStats.getFileResults().get(0).outcome(),
                            key + "：合并模式归档失败不改写文件级结果");
                }
            }
        }
    }

    /** CBZ 归档：单独模式逐文件 .cbz（与 ZIP 同路径，仅扩展名不同） */
    @Test
    void cbzArchiveAcrossInputModesSeparate() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("in"));
        Path a = writeImageMht(src, "a.mht", null);
        for (Input in : Input.values()) {
            AppConfig cbz = config(src, in, Output.SEPARATE);
            cbz.setEnableCbzPackaging(true);
            Path out = Files.createDirectories(tmp.resolve("cbz-" + in.label));
            BatchProcessor.ProcessStats stats = new ImageBatchProcessor(cbz)
                    .process(BatchProcessor.buildMhtFileInfoList(List.of(a.toString())),
                            out.toString(), null, null);
            assertEquals(1, stats.getSuccessCount(), in.label + "：" + stats);
            assertEquals(Set.of("a.cbz"), namesOf(out), in.label + " CBZ 产物：" + namesOf(out));
        }
    }

    // ==================== 3. 进度 + 取消 ====================

    /** 四组合 × 三个文件（含一个"HTML 为空"）的进度完整性（口径见 {@link ProgressRecorder}） */
    @Test
    void progressIsSaneAndCompleteInAllFourCombinations() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("in"));
        Path a = writeImageMht(src, "a.mht", null);
        Path b = writeImageMht(src, "b.mht", null);
        Path plain = writeNoHtmlMht(src, "plain.mht");
        List<BatchProcessor.MhtFileInfo> files = BatchProcessor.buildMhtFileInfoList(
                List.of(a.toString(), plain.toString(), b.toString()));

        for (Input in : Input.values()) {
            for (Output out : Output.values()) {
                String key = in.label + "-" + out.label;
                AppConfig config = config(src, in, out);
                Path outDir = Files.createDirectories(tmp.resolve("prog-" + key));
                ProgressRecorder rec = new ProgressRecorder(files);
                BatchProcessor.ProcessStats stats = new ImageBatchProcessor(config)
                        .process(files, outDir.toString(), null, rec);
                rec.assertSaneAndComplete(key, 3);
                assertEquals(3, stats.getFileResults().size(), key + "：" + stats);
                System.out.println("[q12-matrix] " + key + " 进度序列（按下标）" + rec.byIndex);
            }
        }
    }

    /**
     * 取消：<b>第 1 次解析请求</b>时就请求取消。
     *
     * <p>实测语义（两组合各自稳定，见类 javadoc 第 3 条）：</p>
     * <ul>
     *   <li><b>单独输出</b>：解析发生在主循环内 ⇒ 首文件的解析正常返回并完成提取与写盘（取消只在
     *       主循环顶部被检查），第二个文件在主循环顶部被取消 ⇒ <b>1 条</b>结果（首文件 SUCCESS）；</li>
     *   <li><b>合并输出</b>：首文件的解析发生在主循环之前的"取标题"预解析 ⇒ 取消请求在这一步发出，
     *       主循环顶部立即看到 ⇒ <b>0 条</b>结果、零产物。</li>
     * </ul>
     * <p>这正是"流式与预加载各有独立语义"的旁证：同一个取消请求在两种输出/输入组合下后果不同，
     * 因此不应把它们压成一个隐式开关。</p>
     */
    @Test
    void cancelAtFirstParseRequestInStreamingMode() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("in"));
        Path a = writeImageMht(src, "a.mht", null);
        Path b = writeImageMht(src, "b.mht", null);
        List<BatchProcessor.MhtFileInfo> files = BatchProcessor.buildMhtFileInfoList(
                List.of(a.toString(), b.toString()));

        // ---- 单独输出：首文件完成，第二个文件被取消 ----
        AppConfig sep = config(src, Input.STREAM, Output.SEPARATE);
        Path outSep = Files.createDirectories(tmp.resolve("cancel-stream-separate"));
        ParserProbe probeSep = new ParserProbe(1);
        BatchProcessor.ProcessStats sSep = probeSep.processor(sep)
                .process(files, outSep.toString(), null, null);
        assertEquals(1, sSep.getFileResults().size(),
                "流式单独输出：取消前首文件已走完 ⇒ 恰好 1 条结果：" + sSep);
        assertEquals(ExtractOutcome.SUCCESS, sSep.getFileResults().get(0).outcome(), sSep.toString());
        assertEquals(Set.of("a/", "a/img001.webp"), namesOf(outSep),
                "取消后第二个文件不得产出：" + namesOf(outSep));

        // ---- 合并输出：预解析即被取消 ⇒ 主循环不进入 ----
        AppConfig mer = config(src, Input.STREAM, Output.MERGED);
        Path outMer = Files.createDirectories(tmp.resolve("cancel-stream-merged"));
        ParserProbe probeMer = new ParserProbe(1);
        BatchProcessor.ProcessStats sMer = probeMer.processor(mer)
                .process(files, outMer.toString(), null, null);
        assertEquals(0, sMer.getFileResults().size(),
                "流式合并输出：取标题预解析被取消 ⇒ 零文件结果：" + sMer);
        assertEquals(Set.of(), fileNamesOf(outMer), "取消后不得产出任何文件：" + namesOf(outMer));
    }

    /**
     * 取消：<b>第 2 次解析请求</b>（= 预加载阶段的第二个文件）时请求取消。
     *
     * <p>预加载把解析全部提前到主循环之前 ⇒ 取消发生在主循环开始之前，主循环一次都不进：
     * <b>零</b>文件结果，且解析请求只发生 2 次（不是 2 个文件各 2 次）。这与上一条的"流式取消后
     * 首文件结果仍在"是<b>同一取消请求的两种后果</b>。</p>
     */
    @Test
    void preloadCancelDuringPreloadLeavesNoFileResults() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("in"));
        Path a = writeImageMht(src, "a.mht", null);
        Path b = writeImageMht(src, "b.mht", null);
        List<BatchProcessor.MhtFileInfo> files = BatchProcessor.buildMhtFileInfoList(
                List.of(a.toString(), b.toString()));

        for (Output out : Output.values()) {
            AppConfig config = config(src, Input.PRELOAD, out);
            Path outDir = Files.createDirectories(tmp.resolve("cancel-preload-" + out.label));
            ParserProbe probe = new ParserProbe(2);
            BatchProcessor.ProcessStats stats = probe.processor(config)
                    .process(files, outDir.toString(), null, null);

            assertEquals(List.of(1, 1), probe.callsPerFile(),
                    out.label + "：预加载每个文件解析一次，第 2 次即取消");
            assertEquals(0, stats.getFileResults().size(),
                    out.label + "：预加载阶段取消 ⇒ 主循环不进入，零文件结果：" + stats);
            assertEquals(Set.of(), fileNamesOf(outDir),
                    out.label + "：取消后不得产出文件（合并模式会先建空目录）：" + namesOf(outDir));
        }
    }

    // ==================== 4. 高速模式的"预加载失败" vs "HTML 为空" ====================

    /**
     * 两种"不可用文件"的<b>不同</b>结果（指南 §1.3-2 第 1 条）：
     * 解析<b>抛异常</b> → FAILED（原因 = 加载 MHT 文件失败）；解析成功但<b>HTML 为空</b> → SKIPPED。
     *
     * <p>同时锁定"预加载阶段不写 fileResult"的注释语义（{@code loadFailures} 只留给主循环记，
     * 避免 GUI 按路径取最后一条时互相覆盖）：每文件<b>恰好一条</b>结果。</p>
     */
    @Test
    void preloadFailureVersusEmptyHtmlHaveDistinctOutcomes() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("in"));
        Path a = writeImageMht(src, "a.mht", null);
        Path plain = writeNoHtmlMht(src, "plain.mht");
        Path badDir = Files.createDirectories(src.resolve("bad.mht"));   // 目录冒充 .mht → 解析抛异常

        for (Input in : Input.values()) {
            AppConfig config = config(src, in, Output.SEPARATE);
            Path outDir = Files.createDirectories(tmp.resolve("mixed-" + in.label));
            BatchProcessor.ProcessStats stats = new ImageBatchProcessor(config)
                    .process(BatchProcessor.buildMhtFileInfoList(List.of(
                                    a.toString(), plain.toString(), badDir.toString())),
                            outDir.toString(), null, null);

            assertEquals(3, stats.getFileResults().size(), in.label + "：每文件恰好一条结果：" + stats);
            Map<String, ExtractOutcome> byName = new LinkedHashMap<>();
            stats.getFileResults().forEach(r ->
                    byName.put(Path.of(r.path()).getFileName().toString(), r.outcome()));
            assertEquals(ExtractOutcome.SUCCESS, byName.get("a.mht"), in.label + "：" + stats);
            assertEquals(ExtractOutcome.SKIPPED, byName.get("plain.mht"),
                    in.label + "：HTML 为空 → SKIPPED：" + stats);
            assertEquals(ExtractOutcome.FAILED, byName.get("bad.mht"),
                    in.label + "：解析异常 → FAILED：" + stats);
            assertEquals(1, stats.getSkipCount(), in.label + "：" + stats);
            assertEquals(1, stats.getFailCount(), in.label + "：" + stats);
            assertEquals(Set.of("a/", "a/img001.webp"), namesOf(outDir),
                    in.label + "：只有可解析文件产生输出：" + namesOf(outDir));
        }
    }

    // ==================== 5. 🚩 "流式合并模式首文件只解析一次" + 控制组 ====================

    /**
     * 被测：<b>流式 + 合并</b>模式下首文件只解析一次。
     *
     * <p>测量装置 = {@link ParserProbe}（每次解析请求计数，并在第 N 次请求时请求取消）。
     * 三个互相独立的证据：</p>
     * <ol>
     *   <li>计数：首文件恰好 {@code 1} 次解析请求；</li>
     *   <li>那次解析的结果<b>确实被消费</b>：合并目录名 = 从结果提取出的标题（若该次调用没发生、
     *       或结果被丢弃，目录名会是兜底名 {@code in}）；</li>
     *   <li>首文件结果 SUCCESS。</li>
     * </ol>
     */
    @Test
    void streamingMergedParsesFirstFileExactlyOnce() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("in"));
        Path a = writeImageMht(src, "a.mht", "TITLE-START漫画A标题TITLE-END");
        Path b = writeImageMht(src, "b.mht", null);
        List<BatchProcessor.MhtFileInfo> files = BatchProcessor.buildMhtFileInfoList(
                List.of(a.toString(), b.toString()));

        AppConfig config = config(src, Input.STREAM, Output.MERGED);
        config.setEnableNameExtraction(true);
        config.setImageNameStartMarker("TITLE-START");
        config.setImageNameEndMarker("TITLE-END");

        Path outDir = Files.createDirectories(tmp.resolve("once-fixed"));
        ParserProbe probe = new ParserProbe(3);   // 第 3 次解析请求（= b.mht 解析前）时取消
        BatchProcessor.ProcessStats stats = probe.processor(config)
                .process(files, outDir.toString(), null, null);

        assertEquals(1, probe.callsFor("a.mht"),
                "流式合并模式：预解析的首文件结果必须被复用（首文件只解析一次）");
        assertEquals(1, probe.callsFor("b.mht"), "第二个文件也恰好解析一次");
        assertEquals(2, stats.getSuccessCount(), "两个文件都应成功（证明预解析结果被真正使用）：" + stats);
        assertEquals(0, stats.getFailCount(), stats.toString());
        assertTrue(Files.isDirectory(outDir.resolve("漫画A标题")),
                "合并目录名必须来自预解析结果（标题），实际内容：" + namesOf(outDir));
        assertEquals(Set.of("漫画A标题/",
                        "漫画A标题/f01_img001.webp",
                        "漫画A标题/f02_img001.webp"), namesOf(outDir),
                "预解析结果被复用 + 无重复解析 ⇒ 两个文件的产物都在：" + namesOf(outDir));
        System.out.println("[q12-matrix] 只解析一次·被测：callsPerFile=" + probe.callsPerFile());
    }

    /**
     * 🚩 <b>控制组</b>：同一个测量装置、同一个输入，只让<b>预解析</b>失败
     * （装置第 1、2 次请求返回"必抛"的解析器）⇒ 首文件<b>必须</b>被重新请求解析，计数从 1 变成 <b>2</b>。
     *
     * <p><b>实测语义（如实记录）</b>：预解析失败 ⇒ {@code firstParsed == null} ⇒ 主循环对首文件再次
     * 走 {@code provide(0)}（计数 2）⇒ 这次解析同样按设计失败 ⇒ 该文件被记为 <b>SKIPPED</b>
     * （与"无缓存路径的无法解析"一致），而第二个文件的解析请求<b>正常发生</b>（计数 1）。
     * 这恰好排除了"计数恒为 1 是因为主循环根本不解析"这一假通过。</p>
     */
    @Test
    void streamingMergedControlGroupReparsesWhenPreParseFails() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("in"));
        Path a = writeImageMht(src, "a.mht", "TITLE-START漫画A标题TITLE-END");
        Path b = writeImageMht(src, "b.mht", null);
        List<BatchProcessor.MhtFileInfo> files = BatchProcessor.buildMhtFileInfoList(
                List.of(a.toString(), b.toString()));

        AppConfig config = config(src, Input.STREAM, Output.MERGED);
        config.setEnableNameExtraction(true);
        config.setImageNameStartMarker("TITLE-START");
        config.setImageNameEndMarker("TITLE-END");

        Path outDir = Files.createDirectories(tmp.resolve("once-control"));
        ParserProbe probe = new ParserProbe(3, true);   // 第 1、2 次请求必抛 + 第 3 次请求取消
        BatchProcessor.ProcessStats stats = probe.processor(config)
                .process(files, outDir.toString(), null, null);

        assertEquals(2, probe.callsFor("a.mht"),
                "控制组：预解析失败 ⇒ 首文件必须在主循环里被重新请求解析（读数 1 → 2）");
        assertEquals(List.of(2, 1), probe.callsPerFile(),
                "控制组整体读数（首文件 2 次、第二个文件 1 次）：" + probe.callsPerFile());
        assertTrue(Files.isDirectory(outDir.resolve(defaultMergedName(src))),
                "控制组预解析失败 ⇒ 合并目录名回退输入目录名，实际：" + namesOf(outDir));
        assertEquals(1, probe.callsFor("b.mht"), "第二个文件的解析请求正常发生（计数 1）");
        assertEquals(1, stats.getSuccessCount(),
                "控制组：第二个文件仍正常成功（证明主循环确实在解析）：" + stats);
        assertEquals(1, stats.getFailCount(), "首文件重新解析按设计失败：" + stats);
        assertEquals(List.of("in"), namesOf(outDir).stream()
                        .map(n -> n.split("/")[0]).distinct().toList(),
                "预解析失败 ⇒ 目录名回退输入目录名（in），实际：" + namesOf(outDir));
        System.out.println("[q12-matrix] 只解析一次·控制组：callsPerFile=" + probe.callsPerFile());
    }

    /**
     * 测量装置：覆写 {@code parserFor} 记录每一次解析请求，并可
     * （1）让第 1 次请求返回"必抛"的解析器（控制组用）、
     * （2）在第 N 次请求时向处理器请求取消（把"解析边界"变成确定的可复算停点）。
     */
    private static final class ParserProbe {

        private final int cancelAfterCalls;
        private final boolean failFirstCall;
        private final List<String> paths = new ArrayList<>();
        private final List<ImageBatchProcessor> processors = new ArrayList<>();
        private int calls = 0;

        ParserProbe(int cancelAfterCalls) {
            this(cancelAfterCalls, false);
        }

        ParserProbe(int cancelAfterCalls, boolean failFirstCall) {
            this.cancelAfterCalls = cancelAfterCalls;
            this.failFirstCall = failFirstCall;
        }

        /** 造一个用本装置选解析器的处理器（同包子类覆写 {@code parserFor}） */
        ImageBatchProcessor processor(AppConfig config) {
            return processor(config, null);
        }

        /** 同上，但装配预览缓存（缓存规则用例必须走这一个，否则测的是"未命中"路径） */
        ImageBatchProcessor processor(AppConfig config, PreviewCacheLifecycle lifecycle) {
            ImageBatchProcessor processor = new ImageBatchProcessor(config, lifecycle) {
                @Override
                protected SourceParser parserFor(String path) {
                    return ParserProbe.this.next(path);
                }
            };
            processors.add(processor);
            return processor;
        }

        private SourceParser next(String path) {
            paths.add(path);
            calls++;
            final int call = calls;
            // failFirstCall 同时作用于第 1、2 次请求（模拟"预解析那次调用拿到的结果不可用"）
            boolean fail = failFirstCall && call <= 2;
            if (call == cancelAfterCalls) {
                for (ImageBatchProcessor p : processors) {
                    p.setCancelSupplier(() -> true);
                }
            }
            SourceParser delegate = SourceParsers.forPath(path);
            if (!fail) {
                return delegate::parse;
            }
            return (p, textOnly, continueCallback, stageCallback, byteProgressCallback) -> {
                throw new java.io.IOException("parser-probe: 第 " + call + " 次解析按设计失败（控制组）");
            };
        }

        /** 每个不同文件被解析请求的次数（按首次出现顺序） */
        List<Integer> callsPerFile() {
            Map<String, Integer> byPath = new LinkedHashMap<>();
            for (String p : paths) {
                byPath.merge(Path.of(p).getFileName().toString(), 1, Integer::sum);
            }
            return new ArrayList<>(byPath.values());
        }

        int callsFor(String fileName) {
            int n = 0;
            for (String p : paths) {
                if (Path.of(p).getFileName().toString().equals(fileName)) {
                    n++;
                }
            }
            return n;
        }
    }

    // ==================== 6. 缓存规则：命名依赖标题时命中仍解析 HTML ====================

    /**
     * 规则（§1.3-4 第 3 条）：<b>命名依赖标题时缓存命中仍要解析 HTML</b>。
     *
     * <p>用 {@link ParserProbe} 直接数"解析请求次数"。关键在于<b>流式 + 合并</b>模式：
     * 首文件在取标题的预解析里必然被解析一次，因此</p>
     * <ul>
     *   <li><b>不依赖标题</b>（名称提取关）：未命中 = 预解析 1 + 主循环 1 = <b>2</b>；
     *       命中 = 预解析 1 + 主循环 <b>0</b> = <b>1</b> ⇒ 命中确实省掉了主循环那次解析；</li>
     *   <li><b>命名依赖标题</b>（单独输出 + 名称提取）：未命中 = <b>1</b>；命中 = 预解析 0（单独输出
     *       不取标题）+ 主循环 1（为标题解析）= <b>1</b> ⇒ 命中<b>没有</b>省掉这次解析 —— 这正是
     *       规则原文要保住的语义。</li>
     * </ul>
     * <p>两段的"字数相同但含义相反"互为控制组：若解析计数装置失灵，两组会同时塌成同一个值。</p>
     */
    @Test
    void cacheHitWithTitleNamingStillParsesHtml() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("in"));
        Path a = writeImageMht(src, "a.mht", "TITLE-START漫画A标题TITLE-END");
        List<BatchProcessor.MhtFileInfo> files =
                BatchProcessor.buildMhtFileInfoList(List.of(a.toString()));

        // ================= 第一段：命名依赖标题（单独输出 + 名称提取） =================
        AppConfig named = config(src, Input.STREAM, Output.SEPARATE);
        named.setEnableNameExtraction(true);
        named.setImageNameStartMarker("TITLE-START");
        named.setImageNameEndMarker("TITLE-END");

        // 第一次：无缓存 ⇒ 未命中 ⇒ 解析 1 次
        Path outMiss = Files.createDirectories(tmp.resolve("named-miss"));
        ParserProbe missProbe = new ParserProbe(Integer.MAX_VALUE);
        BatchProcessor.ProcessStats miss = missProbe.processor(named)
                .process(files, outMiss.toString(), null, null);
        assertEquals(1, miss.getSuccessCount(), miss.toString());
        assertEquals(1, missProbe.callsFor("a.mht"), "未命中路径必须解析一次");
        byte[] webpBytes = Files.readAllBytes(outMiss.resolve("漫画A标题").resolve("img001.webp"));

        // 落缓存（模拟预览落盘）→ 再把文件换成等长垃圾并还原 mtime（键不变、解析必失败）
        PreviewCacheStore namedStore = new PreviewCacheStore(tmp.resolve("cache-named"));
        PreviewCacheKey namedKey = PreviewCacheKey.compute(a, named.imageExtractionFingerprint(),
                PreviewCacheMeta.FORMAT_VERSION);
        writeSingleImageCache(namedStore, namedKey, webpBytes);
        long mtime = Files.getLastModifiedTime(a).toMillis();
        overwriteWithGarbageSameSize(a, mtime);

        // 第二次：命中缓存，但命名依赖标题 ⇒ 仍须解析 ⇒ 解析失败 ⇒ 与未命中路径一致
        Path outHit = Files.createDirectories(tmp.resolve("named-hit"));
        ParserProbe hitProbe = new ParserProbe(Integer.MAX_VALUE);
        BatchProcessor.ProcessStats hit = hitProbe.processor(named,
                        new PreviewCacheLifecycle(namedStore))
                .process(files, outHit.toString(), null, null);
        assertEquals(1, hitProbe.callsFor("a.mht"),
                "命名依赖标题时，缓存命中仍必须解析 HTML（解析请求次数不得为 0）");
        assertEquals(0, hit.getSuccessCount(), "垃圾文件解析必然失败 ⇒ 不得 SUCCESS：" + hit);
        assertEquals(Set.of(), fileNamesOf(outHit), "失败路径不得产出：" + namesOf(outHit));

        // ================= 第二段：对照组（合并输出 + 名称提取关） =================
        AppConfig plain = config(src, Input.STREAM, Output.MERGED);
        plain.setEnableNameExtraction(false);
        PreviewCacheStore plainStore = new PreviewCacheStore(tmp.resolve("cache-plain"));
        PreviewCacheKey plainKey = PreviewCacheKey.compute(a, plain.imageExtractionFingerprint(),
                PreviewCacheMeta.FORMAT_VERSION);
        writeSingleImageCache(plainStore, plainKey, webpBytes);

        Path outPlain = Files.createDirectories(tmp.resolve("plain-hit"));
        ParserProbe plainProbe = new ParserProbe(Integer.MAX_VALUE);
        BatchProcessor.ProcessStats plainStats = plainProbe.processor(plain,
                        new PreviewCacheLifecycle(plainStore))
                .process(files, outPlain.toString(), null, null);
        assertEquals(1, plainProbe.callsFor("a.mht"),
                "【对照】不依赖标题时，命中只解析取标题的预解析那一次（主循环不再解析）"
                        + "—— 未命中时是 2 次（预解析 1 + 主循环 1）");
        assertEquals(1, plainStats.getSuccessCount(), "【对照】应命中缓存成功：" + plainStats);
        assertEquals(1, Files.list(outPlain.resolve(defaultMergedName(src))).count(),
                "【对照】应产出缓存里的图片：" + namesOf(outPlain));
        System.out.println("[q12-matrix] 缓存命中解析次数：未命中(命名)=" + missProbe.callsFor("a.mht")
                + " 命中(命名)=" + hitProbe.callsFor("a.mht")
                + " 命中(不命名)=" + plainProbe.callsFor("a.mht"));
    }

    /** 落一份"1 张 cid 图"的预览缓存（图片按 meta 里的相对路径落盘） */
    private static void writeSingleImageCache(PreviewCacheStore store, PreviewCacheKey key,
                                              byte[] webpBytes) throws Exception {
        store.write(key, new PreviewCacheMeta(PreviewCacheMeta.FORMAT_VERSION,
                        new PreviewCacheMeta.Source(key.absolutePath(), key.size(), key.mtime(),
                                key.extraFingerprint()),
                        key.paramFingerprint(), true,
                        List.of(new PreviewCacheMeta.ImageEntry(
                                "images/0001.webp", "img001", "img001", "webp", List.of())),
                        List.of()),
                imagesDir -> Files.write(imagesDir.resolve("0001.webp"), webpBytes));
    }

    /** 等长垃圾覆盖 + 还原 mtime：缓存键（size/mtime）不变，而任何解析都必然失败 */
    private static void overwriteWithGarbageSameSize(Path file, long mtimeMillis) throws Exception {
        byte[] garbage = new byte[(int) Files.size(file)];
        Arrays.fill(garbage, (byte) 0x42);
        Files.write(file, garbage);
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtimeMillis));
    }

    // ==================== 7. 缓存规则：可用来源 ID / 全 Data URI / 空图片 ====================

    /**
     * 三条"必须回退全量路径"的缓存形态在四组合下都成立（既有测试只覆盖单独输出的
     * {@code preloadSeparateConfig} 路径，本步把四组合补齐）：
     *
     * <ol>
     *   <li><b>空图片</b>（{@code images} 为空）→ 命中条件要求 ≥1 张图 ⇒ 未命中；</li>
     *   <li>四组合逐一验证该回退（合并不合并且都回原提取路径）；</li>
     *   <li><b>可用来源 ID</b>：sourceId 全为 null/空 ⇒ 未命中（单独一例）。</li>
     * </ol>
     * <p>全 Data URI 的形态另由既有 {@code dataUriOnlyMhtConsistentWithNoCache} 覆盖
     * （0 图缓存 + 纯 Data URI 的 MHT 必须与无缓存结果一致）。</p>
     */
    @Test
    void threeUnusableCacheShapesFallBackToFullPathInAllFourCombinations() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("in"));
        Path a = writeImageMht(src, "a.mht", null);
        List<BatchProcessor.MhtFileInfo> files =
                BatchProcessor.buildMhtFileInfoList(List.of(a.toString()));
        PreviewCacheStore store = new PreviewCacheStore(tmp.resolve("cache"));

        for (Input in : Input.values()) {
            for (Output out : Output.values()) {
                String key = in.label + "-" + out.label;
                AppConfig config = config(src, in, out);
                Path outDir = Files.createDirectories(tmp.resolve("useless-" + key));

                // 空图片缓存（0 图）
                PreviewCacheKey emptyKey = PreviewCacheKey.compute(a,
                        config.imageExtractionFingerprint(), PreviewCacheMeta.FORMAT_VERSION);
                store.write(emptyKey, new PreviewCacheMeta(PreviewCacheMeta.FORMAT_VERSION,
                                new PreviewCacheMeta.Source(emptyKey.absolutePath(), emptyKey.size(),
                                        emptyKey.mtime(), emptyKey.extraFingerprint()),
                                emptyKey.paramFingerprint(), true, List.of(), List.of()),
                        imagesDir -> { });

                PreviewCacheLifecycle lifecycle = new PreviewCacheLifecycle(store);
                BatchProcessor.ProcessStats stats = new ImageBatchProcessor(config, lifecycle)
                        .process(files, outDir.toString(), null, null);

                // 0 图缓存不可用 ⇒ 回原提取路径 ⇒ 正常提取出那张 cid 图（1 张）
                assertEquals(1, stats.getSuccessCount(), key + "：0 图缓存必须回退原路径：" + stats);
                String dirName = out == Output.MERGED ? defaultMergedName(src) : "a";
                assertEquals(1, Files.list(outDir.resolve(dirName)).count(),
                        key + "：回退路径应正常提取出图片：" + namesOf(outDir));
            }
        }

        // 可用来源 ID：sourceId 全空 ⇒ 未命中（同样回原路径）
        AppConfig config = config(src, Input.STREAM, Output.SEPARATE);
        Path outDir = Files.createDirectories(tmp.resolve("no-source-id"));
        PreviewCacheKey key = PreviewCacheKey.compute(a, config.imageExtractionFingerprint(),
                PreviewCacheMeta.FORMAT_VERSION);
        byte[] webpBytes;
        try (InputStream is = getClass().getResourceAsStream("/sample-preview.webp")) {
            assertNotNull(is, "sample-preview.webp 资源缺失");
            webpBytes = is.readAllBytes();
        }
        store.write(key, new PreviewCacheMeta(PreviewCacheMeta.FORMAT_VERSION,
                        new PreviewCacheMeta.Source(key.absolutePath(), key.size(), key.mtime(),
                                key.extraFingerprint()),
                        key.paramFingerprint(), true,
                        List.of(new PreviewCacheMeta.ImageEntry(
                                "images/0001.webp", null, "image_0.webp", "webp", List.of())),
                        List.of()),
                imagesDir -> Files.write(imagesDir.resolve("0001.webp"), webpBytes));

        BatchProcessor.ProcessStats stats = new ImageBatchProcessor(config,
                new PreviewCacheLifecycle(store)).process(files, outDir.toString(), null, null);
        assertEquals(1, stats.getSuccessCount(), "sourceId 为空的缓存不可复用，应回原路径：" + stats);
        assertEquals(Set.of("a/", "a/img001.webp"), namesOf(outDir),
                "回原路径产出的应是 CID 推断名：" + namesOf(outDir));
    }

    // ==================== 8. 结构判据：「不应合并的差异」的机械化检查 ====================

    /**
     * 指南 §1.4 两条"不应合并"的<b>机械化</b>判据（§3.3 要求）。
     *
     * <p>判据一：<b>没有"隐式缓存开关"式的字段</b> —— 四个策略实现类的实例字段里，
     * 既不允许 {@code boolean} 类型（模式开关的通用形态），也不允许名字含
     * {@code mode}/{@code cache}/{@code stream}/{@code preload} 的字段。
     * ⚠️ {@code merged} <b>不在</b>禁用词里：合并输出持有 {@code mergedDir} 是"已解析好的目录"
     * （数据），不是"我是不是合并模式"（开关）—— 把数据字段当违规会让这条断言变成噪音。</p>
     *
     * <p>判据二：<b>没有"大量布尔分支的 writer"</b> —— 两个 {@code saveImages} 实现的<b>方法体内</b>
     * 不含"模式语义"的 {@code boolean} 局部变量（{@code mode}/{@code merged}/… 这类名字），
     * 且 {@code if} 语句数不超过 {@link #MAX_IF_IN_SAVE_IMAGES}（实测 3）。这条刻意留了余量：
     * 真正的判据是"没有一个越写越长的 {@code if/else} 开关链"，而不是"方法里一个 {@code if} 都不许有"
     * （那会把正常防御写成不自然的代码）。像 {@code boolean archived = …} 这种"操作结果"不算模式开关，
     * 因此不参与本条。</p>
     *
     * <p><b>为什么这两条查得动</b>：策略差异已被"两个类各自的状态"表达 —— 合并输出持有
     * {@code mergedDir/folderName/globalIndex}，单独输出只持有 {@code outputFolder}；
     * 谁都不需要"我是不是合并模式"这个布尔量，因为类本身就是那个答案。</p>
     */
    @Test
    void strategyComponentsHaveNoModeFlagsAndNoBranchHeavyWriter() throws Exception {
        List<String> implementations = List.of(
                "PreloadedImageInput.java", "StreamingImageInput.java",
                "MergedImageOutput.java", "SeparateImageOutput.java");
        List<String> forbiddenFieldNameParts = List.of("mode", "cache", "stream", "preload");
        Path dir = Path.of("src", "main", "java", "com", "mhtextractor", "processor");

        for (String file : implementations) {
            Path p = dir.resolve(file);
            assertTrue(Files.isRegularFile(p), "找不到策略实现类：" + p);
            String src = Files.readString(p, StandardCharsets.UTF_8);

            // 判据一：实例字段不得是 boolean，也不得带模式语义的名字
            for (String field : instanceFieldTypes(src)) {
                assertFalse(field.endsWith(" boolean"),
                        file + "：策略实现类不得持有 boolean 实例字段（隐式模式开关）：" + field);
                String name = field.substring(field.lastIndexOf(' ') + 1);
                String lower = name.toLowerCase();
                for (String bad : forbiddenFieldNameParts) {
                    assertFalse(lower.contains(bad),
                            file + "：策略实现类的字段名不得含模式语义 '" + bad + "'：" + field);
                }
            }


        }

        // 判据二：两个 saveImages 实现不得是"布尔分支 writer"
        for (String file : List.of("MergedImageOutput.java", "SeparateImageOutput.java")) {
            String body = methodBody(Files.readString(dir.resolve(file), StandardCharsets.UTF_8),
                    "int saveImages(");
            assertFalse(body.isBlank(), file + "：应能找到 saveImages 方法体");
            // 只盯"模式语义"的 boolean 局部变量：像 `boolean archived = ...` 这种"操作结果"不是模式开关。
            // 判据的靶子是"靠布尔量决定走哪条命名/序号/归档路线"，那种变量名必然带模式词。
            for (String bad : List.of("boolean merged", "boolean separate", "boolean mode",
                    "boolean cache", "boolean stream", "boolean preload")) {
                assertFalse(body.contains(bad),
                        file + "：saveImages 内出现模式语义的 boolean 局部变量（模式分流）："
                                + firstLineWith(body, bad));
            }
            int ifs = SourceScanUtils.countOccurrences(body, "if (") + SourceScanUtils.countOccurrences(body, "if(");
            assertTrue(ifs <= MAX_IF_IN_SAVE_IMAGES,
                    file + "：saveImages 内 if 语句 " + ifs + " 处，超过实测上界 "
                            + MAX_IF_IN_SAVE_IMAGES + "（这是「越写越长的布尔分支 writer」的前兆）");
        }

        // 反向控制：判据确实有区分力 —— 用一段"故意违规"的假源码跑同一套检查必须被抓到
        String fake = "package x;\nclass Fake {\n  private boolean mergedMode = false;\n"
                + "  int saveImages(Object a) {\n"
                + "    boolean mode = mergedMode;\n"
                + "    if (mode) { return 1; } else if (a == null) { return 2; }\n"
                + "    if (a != null) { return 3; }\n"
                + "    if (a == this) { return 4; }\n    return 0;\n  }\n}\n";
        List<String> fakeFields = instanceFieldTypes(fake);
        assertTrue(fakeFields.stream().anyMatch(f -> f.equals("boolean mergedMode")),
                "控制组：假源码里的 boolean 字段必须被 instanceFieldTypes 抓到（否则本断言是假通过）；"
                        + "实际 " + fakeFields);
        String fakeBody = methodBody(fake, "int saveImages(");
        assertTrue(fakeBody.contains("boolean mode"),
                "控制组：假源码里的模式布尔局部变量必须被名字判据抓到");
        assertTrue(SourceScanUtils.countOccurrences(fakeBody, "if (") > MAX_IF_IN_SAVE_IMAGES,
                "控制组：假源码里的 4 处 if 必须超过上界 " + MAX_IF_IN_SAVE_IMAGES);

        // 结构性证据（人工复核用）：只打"方法/构造器签名"（含括号且以 ) ; 或 ) { 收尾）
        for (String file : List.of("ImageInputStrategy.java", "ImageOutputStrategy.java",
                "PreloadedImageInput.java", "StreamingImageInput.java",
                "MergedImageOutput.java", "SeparateImageOutput.java")) {
            for (String line : Files.readAllLines(dir.resolve(file), StandardCharsets.UTF_8)) {
                String t = line.trim();
                boolean signature = t.contains("(")
                        && (t.endsWith(");") || t.endsWith(") {"))
                        && !t.startsWith("//") && !t.startsWith("*")
                        && !t.startsWith("log.") && !t.startsWith("return")
                        && !t.contains("= new ");
                if (signature) {
                    System.out.println("[q12-structure] " + file + " :: " + t);
                }
            }
        }
    }

    /** saveImages 内允许的 if 语句数上界（实测：合并 2 处、单独 3 处；再多一处就是分支膨胀的信号） */
    private static final int MAX_IF_IN_SAVE_IMAGES = 3;

    /**
     * 粗略提取"实例字段声明"：形如 {@code private final String foo;} / {@code private boolean flag = false;}。
     *
     * <p>做法：取 {@code ;} 之前、{@code =} 之后（若有）的那一段，剥掉可见性与 {@code final} 修饰符，
     * 要求剩下恰好是「类型 名字」两段。含 {@code (} 的（方法签名/调用）、{@code static} 字段、
     * {@code package}/{@code import}/{@code return} 这类两段语句都排除。</p>
     *
     * <p>⚠️ 本启发式与控制组一起演进（实测踩过三次假失败：{@code package x;} 被当字段、
     * {@code return mergedDir;} 被当字段、{@code private final String mergedDir;} 被 4 段规则漏掉）。
     * 因此控制组用一段"故意违规"的假源码跑<b>同一套检查</b>，确保它不是恒真的。</p>
     */
    private static List<String> instanceFieldTypes(String src) {
        List<String> out = new ArrayList<>();
        List<String> modifiers = List.of("private", "protected", "public", "final", "transient",
                "volatile");
        for (String raw : src.split("\n")) {
            String line = raw.trim();
            if (!line.endsWith(";") || line.startsWith("//") || line.startsWith("*")
                    || line.contains("(")) {
                continue;
            }
            String decl = line.substring(0, line.length() - 1);
            int eq = decl.indexOf('=');
            if (eq >= 0) {
                decl = decl.substring(0, eq);
            }
            List<String> parts = new ArrayList<>(List.of(decl.trim().split("\\s+")));
            if (parts.contains("static")) {
                continue;   // 类级字段不参与本断言
            }
            while (!parts.isEmpty() && modifiers.contains(parts.getFirst())) {
                parts.removeFirst();
            }
            if (parts.size() == 2 && !List.of("package", "import", "return", "throw", "break",
                    "continue", "assert").contains(parts.getFirst())) {
                out.add(parts.get(0) + " " + parts.get(1));
            }
        }
        return out;
    }

    /** 取某个方法（按签名片段定位）花括号配平后的方法体文本 */
    private static String methodBody(String src, String signatureFragment) {
        int at = src.indexOf(signatureFragment);
        if (at < 0) {
            return "";
        }
        int open = src.indexOf('{', at);
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(open + 1, i);
                }
            }
        }
        return "";
    }

    private static String firstLineWith(String text, String needle) {
        for (String line : text.split("\n")) {
            if (line.contains(needle)) {
                return line.trim();
            }
        }
        return "";
    }
}
