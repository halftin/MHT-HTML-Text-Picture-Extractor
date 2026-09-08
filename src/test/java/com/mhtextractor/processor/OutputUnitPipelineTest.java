package com.mhtextractor.processor;

import com.mhtextractor.config.AppConfig;
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

    private record TItem(String path, String source, boolean removed) {
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
