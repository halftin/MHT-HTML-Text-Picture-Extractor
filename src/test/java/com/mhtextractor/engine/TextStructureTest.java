package com.mhtextractor.engine;

import com.mhtextractor.core.*;
import com.mhtextractor.extractor.TextExtractor;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.*;
import com.mhtextractor.processor.*;
import com.mhtextractor.uiport.*;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Structural acceptance uses paragraphs and boundaries, never character counts. */
class TextStructureTest {
    @TempDir Path tmp;
    private static final String[] DECORATIONS = {"网址", "记住本站", "声明", "Copyright", "本章节未完", "字体设置", "翻页"};

    private AppConfig config() {
        var c = new AppConfig();
        c.setAddChapterNumber(true); c.setEnableTitleExtraction(true); c.setAutoFixEncoding(false);
        c.setInputFolder(tmp.toString()); c.setOutputFolder(tmp.toString());
        return c;
    }

    private String fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/fixtures/pf12-blocks.html")) {
            assertNotNull(in); return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Path html(String name, String html) throws Exception {
        return Files.writeString(tmp.resolve(name + ".html"), html);
    }

    private String merged(List<Path> paths, AppConfig config, String name) throws Exception {
        Path out = tmp.resolve(name + ".txt");
        var stats = new TextBatchProcessor(config).processMerged(paths.stream()
                .map(p -> BatchProcessor.toMhtFileInfo(p.toFile())).toList(), out.toString(), null, Map.of());
        assertEquals(paths.size(), stats.getSuccessCount()); assertEquals(0, stats.getFailCount());
        return Files.readString(out);
    }

    @Test void syntheticStructureAllFiveRequirements() throws Exception {
        Locale old = I18n.currentLocale();
        try {
            I18n.setLocale(Locale.SIMPLIFIED_CHINESE);
            Path first = html("第一章 甲_site", fixture());
            String extra = java.util.stream.IntStream.range(7,17).mapToObj(i -> "<p>合成增补段"+i+"。</p>").collect(java.util.stream.Collectors.joining());
            Path second = html("第二章 乙_site", fixture().replace("</div><p>本章节未完", extra+"</div><p>本章节未完"));
            Path third = html("第三章 丙_site", fixture());
            String text = merged(List.of(third, first, second), config(), "synthetic");
            var paragraphs = text.split("\n\n", -1);
            assertAll("PF12 structural requirements 1-5",
                    () -> assertEquals(34, paragraphs.length, "1: three headings + thirty-one body paragraphs"),
                    () -> assertTrue(Arrays.stream(paragraphs).allMatch(p -> !p.isBlank() && !p.contains("\n"))
                            && text.contains("正文段甲。\n\n正文段乙。\n\n正文段丙。\n\n正文段丁。\n\n正文段戊。\n\n正文段己。"), "2: exactly one blank line"),
                    () -> assertTrue(text.startsWith("第一章 甲\n\n一\n\n") && text.contains("正文段己。\n\n第二章 乙\n\n一\n\n")
                            && text.contains("合成增补段16。\n\n第三章 丙\n\n一\n\n"), "3: chapter boundaries"),
                    () -> assertEquals(3, Arrays.stream(paragraphs).filter("一"::equals).count(), "4: standalone 一"),
                    () -> { for (String decoration : DECORATIONS) assertFalse(text.contains(decoration), "5: " + decoration); });
            System.out.println("PF12_SYNTHETIC paragraphs=7/17/7 total=31 blankLines=33 headings=第一章 甲 -> 第二章 乙 -> 第三章 丙 decorationHits=0");
        } finally { I18n.setLocale(old); }
    }

    @Test void naturalOrderUnknownTiesSeparateAndGroupBoundaries() throws Exception {
        List<Path> paths = new ArrayList<>();
        for (String name : List.of("第十章 A", "第十一章 B", "第二十三章 C", "Chapter 7 D", "第12章 E", "第十章 F", "z", "a", "m"))
            paths.add(html(name, "<p>" + name.charAt(name.length()-1) + "</p>"));
        var c = config(); c.setAddChapterNumber(false); c.setEnableTitleExtraction(false);
        assertEquals("D\n\nA\n\nF\n\nB\n\nE\n\nC\n\nz\n\na\n\nm", merged(paths, c, "natural"));
        Path out = Files.createDirectory(tmp.resolve("separate"));
        List<String> observed = new ArrayList<>();
        new TextBatchProcessor(c).processSeparate(paths.stream().map(p -> BatchProcessor.toMhtFileInfo(p.toFile())).toList(),
                out.toString(), (i,n,file,p) -> { if (p == 0.05) observed.add(file); }, Map.of());
        assertEquals(paths.stream().map(p -> p.getFileName().toString()).toList(), observed);

        // The application planner keeps groups in data order, even when later groups contain earlier chapters.
        var model = new WorkspaceModel();
        var items = paths.stream().map(this::item).toList(); model.addItems(items);
        model.group(items.subList(0, 3), "first"); model.group(items.subList(3, 5), "second");
        var units = ExtractionPlanService.plan(model.topLevel(), false, c, null, tmp.toString(), tmp.toString(), tmp.toString(), false, false);
        assertEquals(items.subList(0,3).stream().map(i -> i.path).toList(), units.getFirst().getPaths());
        assertEquals(items.subList(3,5).stream().map(i -> i.path).toList(), units.get(1).getPaths());
        assertEquals("A\n\nB\n\nC", merged(paths.subList(0,3), c, "group-one"));
        assertEquals("D\n\nE", merged(paths.subList(3,5), c, "group-two"));
    }

    @Test void filenameControlsOrderWhileExtractedTitleControlsHeaderAndFallbackHonorsSwitch() throws Exception {
        Locale old = I18n.currentLocale();
        try {
            I18n.setLocale(Locale.SIMPLIFIED_CHINESE);
            var c = config(); c.setTitleStartMarker("<!--T-->"); c.setTitleEndMarker("<!--/T-->");
            Path a = html("第十章 File_site", "<head><title><!--T-->第二章 Extracted<!--/T--></title></head><body><p>A</p></body>");
            Path b = html("第七章 Other_site", "<p>B</p>");
            assertEquals("第七章 Other\n\nB\n\n第二章 Extracted\n\nA", merged(List.of(a,b), c, "conflict"));
            c.setEnableTitleExtraction(false);
            assertEquals("第十章\n\nA", merged(List.of(a), c, "disabled"));
            I18n.setLocale(Locale.US);
            assertEquals("Chapter 7 Name", ChapterHeaders.build(null, "Chapter 7 Name_site.mht", 0, true, true));
            assertEquals("Name", ChapterHeaders.build(null, "Chapter 7 Name_site.mht", 0, false, true));
        } finally { I18n.setLocale(old); }
    }

    @Test void mergedThreeFixturesUseFilenameAndTitleKeysWithUnknownLast() throws Exception {
        // 排序键为 String，不持有 ParsedMht；无章号文件只在预扫描局部解析取标题。
        // Assert observable ordering for filename, extracted-title and unknown keys.
        var c = config(); c.setAddChapterNumber(false);
        c.setTitleStartMarker("<!--T-->"); c.setTitleEndMarker("<!--/T-->");
        Path tenth = html("Chapter 10", "<p>tenth</p>");
        Path unknown = html("appendix", "<p>unknown</p>");
        Path second = html("title-only", "<head><title><!--T-->Chapter 2<!--/T--></title></head><body><p>second</p></body>");
        assertEquals("Chapter 2\n\nsecond\n\ntenth\n\nappendix\n\nunknown",
                merged(List.of(tenth, unknown, second), c, "mixed-keys"));
    }

    @Test void portPreviewEqualsSeparateFileAndEachMergedPayloadInBothLocales() throws Exception {
        Locale old = I18n.currentLocale();
        try {
            List<Path> paths = List.of(html("Chapter 12 Name_site", fixture()), html("unknown", fixture()), html("Chapter 2 Other_site", fixture()));
            for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.US)) {
                I18n.setLocale(locale);
                var c = config(); c.setEnableTemplate(true); c.setStartTemplate("[{basename}]\\n"); c.setEndTemplate("\\n[end {filename}]");
                var previews = previews(paths, c);
                var ordered = ChapterNumbers.stableOrder(paths, p -> ChapterNumbers.parse(p.getFileName().toString()).orElse(0));
                String actual = merged(paths, c, "parity-"+locale);
                assertArrayEquals(String.join("\n\n", ordered.stream().map(previews::get).toList()).getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
                c.setSeparateFiles(true);
                var separatePreviews = previews(paths, c);
                Path out = Files.createDirectory(tmp.resolve("out-"+locale));
                var stats = new TextBatchProcessor(c).processSeparate(paths.stream().map(p -> BatchProcessor.toMhtFileInfo(p.toFile())).toList(), out.toString(), null, Map.of());
                assertEquals(3, stats.getSuccessCount());
                for (Path p : paths) assertArrayEquals(separatePreviews.get(p).getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(out.resolve(com.mhtextractor.util.SourceKinds.stripKnownExtension(p.getFileName().toString())+".txt")));
                System.out.println("PF12_PARITY locale="+locale+" separate=3 mergedPayloads=3 byteEquality=PASS");
            }
        } finally { I18n.setLocale(old); }
    }

    private PreviewItem item(Path path) {
        var info = BatchProcessor.toMhtFileInfo(path.toFile());
        return new PreviewItem(info.getPath(), info.getName(), info.getModifiedTime(), tmp.toString());
    }

    @Test void brPreAndTrailingLineBreaksUseTheSamePreviewPayload() throws Exception {
        Path p = html("unknown-pre", "<article><p>A<br>B</p><pre>  x\n    y  </pre><p>z</p></article>");
        var c = config(); c.setEnableTitleExtraction(false); c.setAddChapterNumber(false);
        String actual = merged(List.of(p), c, "pre");
        assertEquals("A\nB\n\n  x\n    y  \n\nz", actual);
        assertEquals(previews(List.of(p), c).get(p), actual);
        var parts = com.mhtextractor.output.FileWriter.composeText("A\n\n", "x.html", null, "", "", "");
        assertEquals("A", parts.body());
    }

    private Map<Path,String> previews(List<Path> paths, AppConfig c) throws Exception {
        var model = new WorkspaceModel(); var items = paths.stream().map(this::item).toList(); model.addItems(items);
        var plans = new ExtractionPlanService(new ExtractionPlanService.Context() {
            public List<Object> dataOrder() { return model.topLevel(); }
            public boolean imageMode() { return false; }
            public AppConfig textParamsCache() { return c; }
            public AppConfig imageParamsCache() { return null; }
            public String inputFolder() { return tmp.toString(); }
            public String outputFolder() { return tmp.toString(); }
            public boolean openFolderAfterComplete() { return false; }
        });
        var port = new InProcessUiport(model, request -> null, plans);
        port.commands().setGlobalParams(false, c);
        var done = new CountDownLatch(paths.size());
        try (var subscription = port.events().subscribe(e -> { if (e instanceof PortEvent.PreparseFinished) done.countDown(); })) {
            port.commands().startPreparseFor(items.stream().map(i -> i.path).toList());
            assertTrue(done.await(15, TimeUnit.SECONDS));
        }
        var result = new HashMap<Path,String>();
        for (var vm : port.queries().presentation(items.stream().map(i -> i.path).toList(), c)) {
            assertEquals("READY", vm.status());
            result.put(Path.of(vm.path()), vm.head()+Files.readString(Path.of(vm.textFile().path()))+vm.tail());
        }
        // Paths passed to the port are normalized by the same MhtFileInfo adapter as extraction.
        var mapped = new HashMap<Path,String>();
        for (Path p : paths) mapped.put(p, result.get(Path.of(item(p).path)));
        return mapped;
    }

    @Test void realMhtStructureAndOrder() throws Exception {
        String location = System.getProperty("pf12.fixtures");
        assumeTrue(location != null && Files.isDirectory(Path.of(location)), "Pass -Dpf12.fixtures=<external novel directory>");
        Locale old = I18n.currentLocale();
        try {
            I18n.setLocale(Locale.SIMPLIFIED_CHINESE);
            List<Path> files;
            try (var stream = Files.list(Path.of(location))) { files = stream.filter(p -> p.toString().endsWith(".mht")).toList(); }
            assertEquals(3, files.size());
            files = ChapterNumbers.stableOrder(files, p -> ChapterNumbers.parse(p.getFileName().toString()).orElse(0));
            int[] counts = {7,17,7};
            var expected = new ArrayList<String>();
            var headers = List.of("第一章 眉娘浪语", "第二章 赵甲狂言", "第三章 小甲傻话");
            for (int i=0;i<3;i++) {
                var parsed = SourceParsers.forPath(files.get(i).toString()).parse(files.get(i).toString(), true, () -> false, null, null);
                var p = Jsoup.parse(parsed.getHtmlContent()).select("#content p").stream()
                        .map(e -> e.text().strip()).filter(s -> !s.startsWith("我们马上记住本站网址")).filter(s -> !s.isBlank()).toList();
                assertEquals(counts[i], p.size());
                String body = new TextExtractor(parsed).extractText(false, null, null, false);
                assertEquals(p, List.of(body.split("\n\n", -1)), "valid p boundaries must survive conversion");
                expected.add(headers.get(i)); expected.addAll(p);
            }
            String actual = merged(List.of(files.get(2), files.get(0), files.get(1)), config(), "real");
            assertEquals(expected, List.of(actual.split("\n\n", -1)));
            assertFalse(actual.contains("\n\n\n")); assertFalse(actual.endsWith("\n"));
            assertTrue(actual.contains("\n\n一\n\n"));
            for (String decoration : DECORATIONS) assertFalse(actual.contains(decoration), decoration);
            var preview = previews(files, config());
            assertArrayEquals(String.join("\n\n", files.stream().map(preview::get).toList()).getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
            String evidence = System.getProperty("pf12.evidence");
            if (evidence != null) { Files.createDirectories(Path.of(evidence)); Files.writeString(Path.of(evidence, "real-merged.txt"), actual); }
            System.out.println("PF12_REAL paragraphsByChapter=7/17/7 directoryOrder=7/7/17 total=31 blankLines=33 nonemptyLines=34 decorationHits=0 parity=PASS");
            System.out.println("PF13_REAL headings="+String.join(" -> ",headers));
            System.out.println("PF12_REAL_SHA256="+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual.getBytes(StandardCharsets.UTF_8))));
        } finally { I18n.setLocale(old); }
    }
}
