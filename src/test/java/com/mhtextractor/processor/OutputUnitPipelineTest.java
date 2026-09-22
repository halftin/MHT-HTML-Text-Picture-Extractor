package com.mhtextractor.processor;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.util.ExtractOutcome;
import com.mhtextractor.util.GroupItem;
import com.mhtextractor.util.OutputUnitPlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输出单元拆分端到端回归（6.4/6.8，阶段 8）：单元计划 + 真实处理器，
 * 验证无组单来源输出结构与改造前一致、章号按单元重算、组单独/合并输出结构。
 */
class OutputUnitPipelineTest {

    @TempDir
    Path tmp;

    private record TItem(String path, String source, boolean removed,
                         AppConfig textParams, AppConfig imageParams) {
        TItem(String path, String source, boolean removed) {
            this(path, source, removed, null, null);
        }
    }

    private static final OutputUnitPlanner.ItemView<TItem> VIEW = new OutputUnitPlanner.ItemView<>() {
        @Override
        public String path(TItem item) {
            return item.path();
        }

        @Override
        public String sourceFolder(TItem item) {
            return item.source();
        }

        @Override
        public boolean removed(TItem item) {
            return item.removed();
        }

        @Override
        public AppConfig textItemParams(TItem item) {
            return item.textParams();
        }

        @Override
        public AppConfig imageItemParams(TItem item) {
            return item.imageParams();
        }
    };

    private Path writeTextMht(Path dir, String fileName, String body) throws Exception {
        String boundary = "----=_unit_test_boundary";
        StringBuilder sb = new StringBuilder();
        sb.append("From: <test@example.com>\r\n");
        sb.append("Subject: ").append(fileName).append("\r\n");
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: multipart/related; boundary=\"").append(boundary).append("\"\r\n\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: text/html; charset=\"utf-8\"\r\n");
        sb.append("Content-Transfer-Encoding: 8bit\r\n\r\n");
        sb.append("<html><body>").append(body).append("</body></html>\r\n");
        sb.append("--").append(boundary).append("--\r\n");
        Path f = dir.resolve(fileName);
        Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private Path writeImageMht(Path dir, String fileName) throws Exception {
        byte[] webpBytes;
        try (InputStream is = getClass().getResourceAsStream("/sample-preview.webp")) {
            assertTrue(is != null, "sample-preview.webp 资源缺失");
            webpBytes = is.readAllBytes();
        }
        String boundary = "----=_unit_test_img_boundary";
        StringBuilder sb = new StringBuilder();
        sb.append("From: <test@example.com>\r\n");
        sb.append("Subject: ").append(fileName).append("\r\n");
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: multipart/related; boundary=\"").append(boundary).append("\"\r\n\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: text/html; charset=\"utf-8\"\r\n");
        sb.append("Content-Transfer-Encoding: 8bit\r\n\r\n");
        sb.append("<html><body><img src=\"cid:img001\"></body></html>\r\n");
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

    // ==================== 回归：无组、单来源 ====================

    @Test
    void ungroupedSingleSourceMergedTextMatchesLegacyStructure() throws Exception {
        Path source = tmp.resolve("小说A");
        Files.createDirectories(source);
        Path f1 = writeTextMht(source, "1.mht", "第一章内容 aaaa");
        Path f2 = writeTextMht(source, "2.mht", "第二章内容 bbbb");
        Path session = Files.createDirectories(tmp.resolve("S"));

        List<Object> dataOrder = List.of(
                new TItem(f1.toString(), source.toString(), false),
                new TItem(f2.toString(), source.toString(), false));
        List<OutputUnitPlanner.OutputUnit> units =
                OutputUnitPlanner.plan(dataOrder, VIEW, false, null, null, session.toString());
        assertEquals(1, units.size());
        OutputUnitPlanner.OutputUnit unit = units.get(0);

        // 与改造前 MainController 合并输出命名完全一致：会话目录/<输入目录名>.txt
        assertEquals(new File(session.toFile(), "小说A.txt").getPath(), unit.getOutputPath());

        TextBatchProcessor processor = new TextBatchProcessor(unit.getParams());
        BatchProcessor.ProcessStats stats = processor.processMerged(
                BatchProcessor.buildMhtFileInfoList(unit.getPaths()), unit.getOutputPath(), null, Map.of());
        assertEquals(2, stats.getSuccessCount());

        String out = Files.readString(Path.of(unit.getOutputPath()), StandardCharsets.UTF_8);
        assertTrue(out.contains("aaaa"));
        assertTrue(out.contains("bbbb"));
        // 会话目录结构：仅一个输出文件（与改造前一致）
        assertEquals(List.of("小说A.txt"),
                Files.list(session).map(p -> p.getFileName().toString()).sorted().toList());
    }

    @Test
    void ungroupedSingleSourceMergedImageOutputsToSourceNamedDir() throws Exception {
        Path source = tmp.resolve("漫画B");
        Files.createDirectories(source);
        Path f1 = writeImageMht(source, "1.mht");
        Path session = Files.createDirectories(tmp.resolve("S"));

        AppConfig imageGlobal = new AppConfig();
        imageGlobal.setSingleOutputMode(true);
        List<Object> dataOrder = List.of(new TItem(f1.toString(), source.toString(), false));
        List<OutputUnitPlanner.OutputUnit> units =
                OutputUnitPlanner.plan(dataOrder, VIEW, true, null, imageGlobal, session.toString());
        assertEquals(1, units.size());
        OutputUnitPlanner.OutputUnit unit = units.get(0);

        ImageBatchProcessor processor = new ImageBatchProcessor(unit.getParams());
        BatchProcessor.ProcessStats stats = processor.process(
                BatchProcessor.buildMhtFileInfoList(unit.getPaths()), session.toString(),
                unit.getOutputName(), null);
        assertEquals(1, stats.getSuccessCount());

        Path mergedDir = session.resolve("漫画B");
        assertTrue(Files.isDirectory(mergedDir));
        assertEquals(1, Files.list(mergedDir).count());
    }

    // ==================== 组单元：章号重算 / 单独输出文件夹 ====================

    @Test
    void chapterNumberingRestartsPerGroupUnit() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        GroupItem<TItem> g1 = new GroupItem<>(1, "组1");
        g1.getMembers().add(new TItem(writeTextMht(src, "a.mht", "组1第一篇").toString(), src.toString(), false));
        g1.getMembers().add(new TItem(writeTextMht(src, "b.mht", "组1第二篇").toString(), src.toString(), false));
        GroupItem<TItem> g2 = new GroupItem<>(2, "组2");
        g2.getMembers().add(new TItem(writeTextMht(src, "c.mht", "组2第一篇").toString(), src.toString(), false));
        Path session = Files.createDirectories(tmp.resolve("S"));

        AppConfig textGlobal = new AppConfig();
        textGlobal.setAddChapterNumber(true);
        textGlobal.setSeparateFiles(false);
        List<Object> dataOrder = List.of(g1, g2);
        List<OutputUnitPlanner.OutputUnit> units =
                OutputUnitPlanner.plan(dataOrder, VIEW, false, textGlobal, null, session.toString());
        assertEquals(2, units.size());

        for (OutputUnitPlanner.OutputUnit unit : units) {
            TextBatchProcessor processor = new TextBatchProcessor(unit.getParams());
            BatchProcessor.ProcessStats stats = processor.processMerged(
                    BatchProcessor.buildMhtFileInfoList(unit.getPaths()), unit.getOutputPath(), null, Map.of());
            assertEquals(unit.fileCount(), stats.getSuccessCount(), unit.getOutputName() + ": " + stats);
            String out = Files.readString(Path.of(unit.getOutputPath()), StandardCharsets.UTF_8);
            // 章号在单元内独立编号（组内重算）：每个单元都从第一章开始
            assertTrue(out.contains("第一章"), unit.getOutputName() + " 应包含第一章");
            assertEquals(unit.fileCount() >= 2, out.contains("第二章"),
                    unit.getOutputName() + " 第二章出现与否与单元文件数一致");
        }
        assertTrue(session.resolve("组1.txt").toFile().exists());
        assertTrue(session.resolve("组2.txt").toFile().exists());
    }

    // ==================== 提取结果记录（提取结果状态需求） ====================

    private static Map<String, FileResult> resultsByName(BatchProcessor.ProcessStats stats) {
        Map<String, FileResult> m = new HashMap<>();
        for (FileResult r : stats.getFileResults()) {
            m.put(new File(r.path()).getName(), r);
        }
        return m;
    }

    @Test
    void extractResultsRecordedPerFileOutcome() throws Exception {
        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        Path ok = writeTextMht(source, "ok.mht", "正常内容 hello world");
        Path empty = writeTextMht(source, "empty.mht", "");
        Path warn = writeTextMht(source, "warn.mht", "带警告的内容 abc");
        Path session = Files.createDirectories(tmp.resolve("S"));

        // 单元一：无标记配置 → ok 成功（SUCCESS）、empty 跳过（SKIPPED）
        List<Object> unit1Data = List.of(
                new TItem(ok.toString(), source.toString(), false),
                new TItem(empty.toString(), source.toString(), false));
        List<OutputUnitPlanner.OutputUnit> u1 =
                OutputUnitPlanner.plan(unit1Data, VIEW, false, new AppConfig(), null, session.toString());
        assertEquals(1, u1.size());
        TextBatchProcessor p1 = new TextBatchProcessor(u1.get(0).getParams());
        BatchProcessor.ProcessStats s1 = p1.processMerged(
                BatchProcessor.buildMhtFileInfoList(u1.get(0).getPaths()),
                u1.get(0).getOutputPath(), null, Map.of());
        assertEquals(1, s1.getSuccessCount());
        assertEquals(1, s1.getSkipCount());
        Map<String, FileResult> r1 = resultsByName(s1);
        assertEquals(ExtractOutcome.SUCCESS, r1.get("ok.mht").outcome());
        assertEquals(ExtractOutcome.SKIPPED, r1.get("empty.mht").outcome());

        // 单元二：内容限定标记配置但不存在 → HtmlUtils 警告（起始标记未找到）→ SUCCESS_WARNED
        AppConfig markers = new AppConfig();
        markers.setStartMarker("___不存在的起始标记___");
        markers.setEndMarker("___不存在的结束标记___");
        Path session2 = Files.createDirectories(tmp.resolve("S2"));
        List<Object> unit2Data = List.of(new TItem(warn.toString(), source.toString(), false));
        List<OutputUnitPlanner.OutputUnit> u2 =
                OutputUnitPlanner.plan(unit2Data, VIEW, false, markers, null, session2.toString());
        assertEquals(1, u2.size());
        TextBatchProcessor p2 = new TextBatchProcessor(u2.get(0).getParams());
        BatchProcessor.ProcessStats s2 = p2.processMerged(
                BatchProcessor.buildMhtFileInfoList(u2.get(0).getPaths()),
                u2.get(0).getOutputPath(), null, Map.of());
        assertEquals(1, s2.getSuccessCount());
        assertEquals(1, s2.getWarnCount());
        FileResult wr = s2.getFileResults().get(0);
        assertEquals(warn.toString(), wr.path());
        assertEquals(ExtractOutcome.SUCCESS_WARNED, wr.outcome());
        assertTrue(wr.warnings().stream().anyMatch(w -> w.contains("起始标记未找到")));
    }

    @Test
    void imageModeSkipsTextOnlyMhtAndRecordsResult() throws Exception {
        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        Path textOnly = writeTextMht(source, "plain.mht", "只有文字没有图片");
        Path session = Files.createDirectories(tmp.resolve("S"));

        AppConfig imageGlobal = new AppConfig();
        imageGlobal.setSingleOutputMode(true);
        List<Object> dataOrder = List.of(new TItem(textOnly.toString(), source.toString(), false));
        List<OutputUnitPlanner.OutputUnit> units =
                OutputUnitPlanner.plan(dataOrder, VIEW, true, null, imageGlobal, session.toString());
        assertEquals(1, units.size());
        OutputUnitPlanner.OutputUnit unit = units.get(0);

        ImageBatchProcessor processor = new ImageBatchProcessor(unit.getParams());
        BatchProcessor.ProcessStats stats = processor.process(
                BatchProcessor.buildMhtFileInfoList(unit.getPaths()), session.toString(),
                unit.getOutputName(), null);
        assertEquals(1, stats.getSkipCount());
        assertEquals(1, stats.getFileResults().size());
        FileResult r = stats.getFileResults().get(0);
        assertEquals(textOnly.toString(), r.path());
        assertEquals(ExtractOutcome.SKIPPED, r.outcome());
    }

    @Test
    void groupSeparateTextWritesIntoGroupFolder() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        GroupItem<TItem> g = new GroupItem<>(1, "组1");
        g.getMembers().add(new TItem(writeTextMht(src, "x.mht", "组内文件").toString(), src.toString(), false));
        Path session = Files.createDirectories(tmp.resolve("S"));

        AppConfig textGlobal = new AppConfig();
        textGlobal.setSeparateFiles(true);
        List<OutputUnitPlanner.OutputUnit> units =
                OutputUnitPlanner.plan(List.of(g), VIEW, false, textGlobal, null, session.toString());
        assertEquals(1, units.size());
        OutputUnitPlanner.OutputUnit unit = units.get(0);
        assertEquals(new File(session.toFile(), "组1").getPath(), unit.getOutputPath());

        // 与 MainController.processUnit 文本单独分支一致：先建目录再提取
        Files.createDirectories(Path.of(unit.getOutputPath()));
        TextBatchProcessor processor = new TextBatchProcessor(unit.getParams());
        BatchProcessor.ProcessStats stats = processor.processSeparate(
                BatchProcessor.buildMhtFileInfoList(unit.getPaths()), unit.getOutputPath(), null, Map.of());
        assertEquals(1, stats.getSuccessCount());
        assertTrue(session.resolve("组1").resolve("x.txt").toFile().exists());
    }
}
