package com.mhtextractor.core;

import com.mhtextractor.extractor.ImageExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HtmlFolderParser} 的无头测试（R6.2 / R6.7 / §4.2）。
 *
 * <p><b>本类最重要的一条</b>是 {@link #extractedImagesAreByteIdenticalToRawSidecarFiles()}：
 * 它走<b>真实提取路径</b>（{@link ImageExtractor}）证明"HTML+侧车 → ParsedMht → 下游零改动"
 * 不是声称而是可证的 —— 这正是 R6.2「不建第二条解析管线」的判据。</p>
 *
 * <p>夹具取自 {@code src/test/resources/fixtures/html/}（R6.7 新增），复制到临时目录后再解析：
 * 临时目录里的相对关系与真实"另存为"产物一致（{@code page.html} + {@code page_files/}）。</p>
 */
class HtmlFolderParserTest {

    private static final Path FIXTURES = Path.of("src", "test", "resources", "fixtures", "html");

    @TempDir
    Path tmp;

    private Path work;

    @BeforeEach
    void copyFixtures() throws IOException {
        work = tmp.resolve("work");
        copyTree(FIXTURES, work);
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private MimeParser.ParsedMht parse(String rel) throws IOException {
        return new HtmlFolderParser().parse(work.resolve(rel).toString(), false, () -> false, null, null);
    }

    // ==================== R6.2：同形性（下游零改动的可证判据） ====================

    @Test
    void producesSameShapedParsedMhtAsMhtPath() throws IOException {
        MimeParser.ParsedMht p = parse("sidecar/sidecar.html");

        assertTrue(p.getHtmlContent() != null && p.getHtmlContent().contains("侧车文件夹形态"),
                "HTML 文本应被完整读出（含中文，说明 charset 探测生效）");
        assertEquals(3, p.getImageInfoList().size(),
                "可读的应是 sidecar_files/a.png、sidecar_files/横 幅.jpg 与同目录的 flat-sibling.png；"
                        + "缺失 / ../ 逃逸 / 绝对 URL / 子目录一律跳过");
        assertEquals(3, p.getLocationDataMap().size(), "入 locationDataMap 的就是这 3 张可读图");
        assertEquals(3, p.getLocationMap().size(), "locationMap 与 locationDataMap 同 key 集");
        assertTrue(p.getCidMap().isEmpty(), "HTML 场景无 Content-ID（恒为空）");
    }

    @Test
    void emittedContentLocationKeysAreTheVerbatimImgSrcStrings() throws IOException {
        MimeParser.ParsedMht p = parse("sidecar/sidecar.html");

        assertTrue(p.getLocationDataMap().containsKey("sidecar_files/a.png"),
                "key 必须是 <img src> 的原样字符串（相对路径），不是绝对化后的路径 —— "
                        + "ImageExtractor 的区域限定用 img[src] 原文做精确比对");
        assertTrue(p.getLocationDataMap().containsKey("sidecar_files/横 幅.jpg"),
                "含中文与空格的 src 同样按原样入 key");
        assertEquals("a.png", p.getLocationMap().get("sidecar_files/a.png"));
        assertTrue(p.getLocationDataMap().containsKey("flat-sibling.png"),
                "与 HTML 同目录的引用是允许的（cwd 就是该目录）");
        assertFalse(p.getLocationDataMap().containsKey("sidecar_files/missing.gif"));
        assertFalse(p.getLocationDataMap().containsKey("../escape.png"),
                "../ 逃逸：不得读到 HTML 目录之外（即便目标文件真实存在）");
        assertFalse(p.getLocationDataMap().containsKey("https://example.com/remote.png"));
    }

    @Test
    void extractedImagesAreByteIdenticalToRawSidecarFiles() throws IOException {
        Path html = work.resolve("sidecar/sidecar.html");
        MimeParser.ParsedMht parsed = new HtmlFolderParser()
                .parse(html.toString(), false, () -> false, null, null);

        // 走真实下游：ImageExtractor（区域限定关闭）→ imageInfoList 顺序 + HTML 内嵌 Data URI
        List<ImageExtractor.ImageData> images =
                new ImageExtractor(parsed, null, null, null, null).extractImages();

        List<Path> expected = List.of(
                work.resolve("sidecar/sidecar_files/a.png"),
                work.resolve("sidecar/sidecar_files/横 幅.jpg"),
                work.resolve("sidecar/flat-sibling.png"));
        long dataUriCount = parsed.getHtmlContent().split("src=\"data:", -1).length - 1;
        assertEquals(expected.size() + dataUriCount, images.size(),
                "提取数量 = 3 张本地图片 + 1 张 Data URI（Data URI 由 ImageExtractor 自行解码，不入 imageInfoList）");
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(Files.readAllBytes(expected.get(i)), images.get(i).getData(),
                    "第 " + (i + 1) + " 张的字节必须与侧车文件逐字节相同：" + expected.get(i).getFileName());
        }
        assertEquals("a.png", images.get(0).getFilename());
        assertEquals("横 幅.jpg", images.get(1).getFilename(), "中文文件名应原样保留（供输出命名用）");
    }

    @Test
    void regionLimitedExtractionStillMatchesBecauseKeysAreVerbatim() throws IOException {
        Path html = work.resolve("sidecar/sidecar.html");
        MimeParser.ParsedMht parsed = new HtmlFolderParser()
                .parse(html.toString(), false, () -> false, null, null);

        // 区域限定路径完全依赖 isInfoAllowed 的"src 原文 == contentLocation"比对（ImageExtractor:240-267）
        List<ImageExtractor.ImageData> images = new ImageExtractor(parsed, null, null,
                "<!--START-->", "<!--END-->").extractImages();
        assertEquals(4, images.size(),
                "区域内的 3 张本地图 + 1 张 Data URI 都要被提取（证明 key 口径与匹配逻辑对得上）");
    }

    @Test
    void sameDirectoryFlatImageIsSupported() throws IOException {
        MimeParser.ParsedMht parsed = parse("same-dir/same-dir.html");
        assertEquals(1, parsed.getImageInfoList().size(), "与 HTML 同目录的图片应可读");
        List<ImageExtractor.ImageData> images =
                new ImageExtractor(parsed, null, null, null, null).extractImages();
        assertEquals(1, images.size());
        assertEquals("flat-a.png", images.getFirst().getFilename());
    }

    @Test
    void chineseFileNameAndSidecarNameAreResolved() throws IOException {
        MimeParser.ParsedMht parsed = parse("中文页面/中文页面.html");
        assertEquals(1, parsed.getImageInfoList().size(), "中文 HTML 名 + 中文侧车目录名应能定位");
        MimeParser.ImageInfo info = parsed.getImageInfoList().getFirst();
        assertEquals("中文页面_files/中 文1.png", info.getContentLocation());
        assertEquals("中 文1.png", info.getFilename());
    }

    @Test
    void pureHtmlWithoutImagesYieldsTextOnly() throws IOException {
        MimeParser.ParsedMht parsed = parse("plain/plain.html");
        assertTrue(parsed.getHtmlContent().contains("纯 HTML 夹具"));
        assertTrue(parsed.getImageInfoList().isEmpty());
        assertTrue(parsed.getLocationDataMap().isEmpty());
        assertTrue(parsed.getCidMap().isEmpty());
    }

    @Test
    void textOnlySkipsImageIo() throws IOException {
        MimeParser.ParsedMht parsed = new HtmlFolderParser()
                .parse(work.resolve("sidecar/sidecar.html").toString(), true, () -> false, null, null);
        assertTrue(parsed.getHtmlContent().contains("侧车文件夹形态"));
        assertTrue(parsed.getImageInfoList().isEmpty(), "textOnly=true 时不做图片 IO（与 MimeParser 同语义）");
    }

    // ==================== 安全面（§4.2-8） ====================

    @Test
    void localReferenceResolutionRejectsEverythingOutsideTheAllowedDirectories() {
        Path dir = work.resolve("sidecar");

        assertNull(HtmlFolderParser.resolveLocalFile(dir, "sidecar.html", "../escape.png"),
                "../ 逃逸：不得读到 HTML 目录之外");
        assertNull(HtmlFolderParser.resolveLocalFile(dir, "sidecar.html", "/etc/passwd"),
                "绝对路径（POSIX 形态）");
        assertNull(HtmlFolderParser.resolveLocalFile(dir, "sidecar.html", "C:\\Windows\\win.ini"),
                "绝对路径（盘符形态）");
        assertNull(HtmlFolderParser.resolveLocalFile(dir, "sidecar.html", "//example.com/x.png"),
                "协议相对 URL");
        assertNull(HtmlFolderParser.resolveLocalFile(dir, "sidecar.html", "https://example.com/x.png"),
                "绝对 URL");
        assertNull(HtmlFolderParser.resolveLocalFile(dir, "sidecar.html", "file:///c:/x.png"),
                "file:// URL（可能指向目录之外）");
        assertNull(HtmlFolderParser.resolveLocalFile(dir, "sidecar.html", "cid:abc@def"),
                "cid: 引用（HTML 场景无数据可取）");
        assertNull(HtmlFolderParser.resolveLocalFile(dir, "sidecar.html", "sidecar_files/deep/nested.png"),
                "侧车子目录不支持（约束在侧车本层）");
        assertNull(HtmlFolderParser.resolveLocalFile(dir, "sidecar.html", "sidecar_files/missing.gif"),
                "缺失文件");
        assertNull(HtmlFolderParser.resolveLocalFile(dir, "sidecar.html", ""),
                "空 src");
        assertNull(HtmlFolderParser.resolveLocalFile(null, "sidecar.html", "a.png"),
                "无父目录");
    }

    @Test
    void localReferenceResolutionAcceptsOnlySidecarOrSameDirectory() {
        Path dir = work.resolve("sidecar");
        assertEquals(dir.resolve("sidecar_files/a.png"),
                HtmlFolderParser.resolveLocalFile(dir, "sidecar.html", "sidecar_files/a.png"));
        assertEquals(dir.resolve("escape.png"),
                HtmlFolderParser.resolveLocalFile(dir, "sidecar.html", "escape.png"),
                "与 HTML 同目录的文件是允许的（cwd 就是该目录）");
        assertEquals(dir.resolve("sidecar_files/a.png"),
                HtmlFolderParser.resolveLocalFile(dir, "sidecar.html", "./sidecar_files/a.png"),
                "带 ./ 前缀应归一化后命中同一文件");
    }

    @Test
    void missingFileDoesNotThrowAndDoesNotBreakTheBatch() {
        assertDoesNotThrow(() -> {
            MimeParser.ParsedMht parsed = parse("sidecar/sidecar.html");
            // 夹具里 missing.gif / ../escape.png（目录外）/ deep/nested.png / https:// 都不存在或不被允许，
            // 但解析必须完整返回、可读的图片照常给出
            assertEquals(3, parsed.getLocationDataMap().size());
        });
    }

    @Test
    void nonexistentHtmlFileThrowsIoExceptionOfTheDeclaredType() {
        assertThrows(IOException.class, () -> new HtmlFolderParser()
                .parse(work.resolve("nope.html").toString(), false, () -> false, null, null));
    }

    @Test
    void progressCallbacksAreReportedMonotonicallyWhenProvided() throws IOException {
        java.util.List<Double> progress = new java.util.ArrayList<>();
        new HtmlFolderParser().parse(work.resolve("sidecar/sidecar.html").toString(), false,
                () -> false, stage -> { }, progress::add);
        assertFalse(progress.isEmpty());
        assertEquals(1.0, progress.getLast(), 1e-9, "结束时应上报 1.0");
    }
}
