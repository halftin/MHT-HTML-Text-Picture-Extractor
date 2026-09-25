package com.mhtextractor.output;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mhtextractor.extractor.ImageExtractor.ImageData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写入层加固回归（会话 4，P0-3/C-6）：
 * (a) 空名/null 不抛异常；(b) 同名并发/重复写入不覆盖已有文件；
 * (c) 含子目录的归档产生 warning 且进度数正确；(d) 归档失败后目标文件不存在。
 */
class ImageWriterTest {

    private Path tmpDir;

    @BeforeEach
    void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("iwtest-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tmpDir == null || !Files.exists(tmpDir)) return;
        try (var paths = Files.walk(tmpDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    /** PNG 魔数 + 可选尾部字节（区分不同图片内容） */
    private static byte[] pngBytes(int... tail) {
        byte[] sig = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] out = new byte[sig.length + tail.length];
        System.arraycopy(sig, 0, out, 0, sig.length);
        for (int i = 0; i < tail.length; i++) out[sig.length + i] = (byte) tail[i];
        return out;
    }

    // ---- (a) 空名/null 不抛异常 ----

    @Test
    void computeFileNameWithNullImageDataReturnsUnnamed() {
        assertEquals("unnamed.jpg", ImageWriter.computeFileName(null, "jpg", false, 0, null));
    }

    @Test
    void computeFileNameWithEmptyOrNullFilenameDoesNotThrow() {
        ImageData empty = new ImageData("", null, pngBytes(1, 2, 3, 4));
        assertEquals("unnamed.png", ImageWriter.computeFileName(empty, "jpg", false, 0, null));
        ImageData nullName = new ImageData(null, null, pngBytes(1, 2, 3, 4));
        assertEquals("unnamed.png", ImageWriter.computeFileName(nullName, "jpg", false, 0, null));
    }

    @Test
    void saveImageWithNullInputsReturnsNull() {
        assertNull(ImageWriter.saveImage(null, tmpDir.toString(), "jpg", false, new int[]{0}, ""));
        ImageData noData = new ImageData("a.png", null, null);
        assertNull(ImageWriter.saveImage(noData, tmpDir.toString(), "jpg", false, new int[]{0}, ""));
    }

    @Test
    void saveImageWithEmptyFilenameWritesUnnamedFile() throws IOException {
        byte[] png = pngBytes(1, 2, 3, 4);
        ImageData d = new ImageData("", null, png);
        String name = ImageWriter.saveImage(d, tmpDir.toString(), "jpg", false, new int[]{0}, "");
        assertEquals("unnamed.png", name);
        assertArrayEquals(png, Files.readAllBytes(tmpDir.resolve(name)));
    }

    // ---- (b) 同名重复/并发写入不覆盖 ----

    @Test
    void duplicateSaveDoesNotOverwriteExistingFile() throws IOException {
        byte[] png = pngBytes(9, 9);
        ImageData d = new ImageData("photo.png", null, png);
        String first = ImageWriter.saveImage(d, tmpDir.toString(), "jpg", false, new int[]{0}, "");
        String second = ImageWriter.saveImage(d, tmpDir.toString(), "jpg", false, new int[]{0}, "");
        assertEquals("photo.png", first);
        assertEquals("photo_1.png", second);
        assertArrayEquals(png, Files.readAllBytes(tmpDir.resolve(first)));
        assertArrayEquals(png, Files.readAllBytes(tmpDir.resolve(second)));
    }

    @Test
    void renameModeDuplicateDoesNotOverwrite() throws IOException {
        byte[] png = pngBytes(7, 7);
        ImageData d = new ImageData("a.png", null, png);
        // 现有行为：renameIndex 从 0 起（counter[0]++ 首值为 0）
        assertEquals("00000.png",
                ImageWriter.saveImage(d, tmpDir.toString(), "jpg", true, new int[]{0}, ""));
        // 模拟重跑：同序号已存在 → 不覆盖旧文件，返回 null
        assertNull(ImageWriter.saveImage(d, tmpDir.toString(), "jpg", true, new int[]{0}, ""));
        assertArrayEquals(png, Files.readAllBytes(tmpDir.resolve("00000.png")));
    }

    @Test
    void concurrentDuplicateSavesDoNotOverwrite() throws Exception {
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                byte[] payload = pngBytes(i, i, i, i);
                futures.add(pool.submit(() -> ImageWriter.saveImage(
                        new ImageData("same.png", null, payload),
                        tmpDir.toString(), "jpg", false, new int[]{0}, "")));
            }
            Set<String> names = new HashSet<>();
            for (Future<String> f : futures) {
                String name = f.get();
                assertNotNull(name);
                assertTrue(names.add(name), "文件名重复：" + name);
            }
            assertEquals(n, names.size());
            Set<String> contents = new HashSet<>();
            for (String name : names) {
                contents.add(Arrays.toString(Files.readAllBytes(tmpDir.resolve(name))));
            }
            assertEquals(n, contents.size(), "有图片被覆盖或丢失");
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- (c) 含子目录的归档：warning + 进度数正确 ----

    @Test
    void archiveWithSubdirectoryWarnsAndCountsFilesOnly() throws Exception {
        Path src = Files.createDirectory(tmpDir.resolve("src"));
        Files.write(src.resolve("a.txt"), "A".getBytes(StandardCharsets.UTF_8));
        Files.write(src.resolve("b.txt"), "B".getBytes(StandardCharsets.UTF_8));
        Path sub = Files.createDirectory(src.resolve("sub"));
        Files.write(sub.resolve("c.txt"), "C".getBytes(StandardCharsets.UTF_8));

        Logger logger = (Logger) LoggerFactory.getLogger(Archiver.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            List<Integer> progress = new ArrayList<>();
            String out = tmpDir.resolve("out.zip").toString();
            assertTrue(Archiver.createZip(src.toString(), out, progress::add));
            assertEquals(List.of(1, 2), progress);
            try (ZipFile zf = new ZipFile(out)) {
                List<String> entries = zf.stream().map(ZipEntry::getName).sorted().toList();
                assertEquals(List.of("a.txt", "b.txt"), entries);
            }
            assertTrue(appender.list.stream().anyMatch(e ->
                    e.getLevel() == Level.WARN && e.getFormattedMessage().contains("子目录")),
                    "子目录应产生 warning");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void archiveOfOnlySubdirectoriesReturnsFalse() throws Exception {
        Path src = Files.createDirectory(tmpDir.resolve("src"));
        Files.createDirectory(src.resolve("sub"));
        assertFalse(Archiver.createZip(src.toString(), tmpDir.resolve("out.zip").toString(), null));
        assertFalse(Files.exists(tmpDir.resolve("out.zip")));
    }

    // ---- (d) 归档失败后目标文件不存在 ----

    @Test
    void failedArchiveLeavesNoTargetFile() throws Exception {
        Path src = Files.createDirectory(tmpDir.resolve("src"));
        Files.write(src.resolve("a.txt"), "A".getBytes(StandardCharsets.UTF_8));
        // 输出父目录不存在 → 创建即失败
        String out = tmpDir.resolve("no-such-dir").resolve("out.zip").toString();
        assertFalse(Archiver.createZip(src.toString(), out, null));
        assertFalse(Files.exists(Path.of(out)));
    }

    @Test
    void earlyReturnDoesNotTouchPreExistingTarget() throws Exception {
        Path src = Files.createDirectory(tmpDir.resolve("src"));
        String out = tmpDir.resolve("out.zip").toString();
        Files.write(Path.of(out), "keep".getBytes(StandardCharsets.UTF_8));
        // 空目录 → 提前返回，不触碰已存在的目标文件
        assertFalse(Archiver.createZip(src.toString(), out, null));
        assertArrayEquals("keep".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(Path.of(out)));
    }
}
