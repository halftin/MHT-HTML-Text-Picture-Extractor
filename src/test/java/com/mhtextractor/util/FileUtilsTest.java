package com.mhtextractor.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1：{@link FileUtils#findMhtFiles} 的扩展名口径收敛到 {@link SourceKinds} ——
 * 目录收集必须能收到 HTML/HTM（修复前只收 .mht/.mhtml，HTML 目录漏收 size=0），
 * 未知扩展名与隐藏文件不得混入，mtime 升序既有行为不变。
 */
class FileUtilsTest {

    @TempDir
    Path dir;

    @Test
    void collectsMhtAndHtmlSourcesOnly() throws Exception {
        Files.writeString(dir.resolve("page.html"), "<html></html>");
        Files.writeString(dir.resolve("page.htm"), "<html></html>");
        Files.writeString(dir.resolve("page.mht"), "x");
        Files.writeString(dir.resolve("PAGE.MHTML"), "x");
        Files.writeString(dir.resolve("output.v2"), "x");
        Files.writeString(dir.resolve(".html"), "x");
        Files.writeString(dir.resolve("note.txt"), "x");

        Set<String> names = FileUtils.findMhtFiles(dir.toString()).stream()
                .map(File::getName).collect(Collectors.toSet());

        assertEquals(Set.of("page.html", "page.htm", "page.mht", "PAGE.MHTML"), names,
                "A1：目录收集必须含 HTML/HTM 且不含未知扩展名与隐藏文件");
    }

    @Test
    void sortedByModifiedTimeStillHolds() throws Exception {
        Path a = Files.writeString(dir.resolve("a.mht"), "x");
        Path b = Files.writeString(dir.resolve("b.html"), "x");
        assertTrue(a.toFile().setLastModified(1000L));
        assertTrue(b.toFile().setLastModified(3000L));

        List<String> names = FileUtils.findMhtFiles(dir.toString()).stream()
                .map(File::getName).toList();

        assertEquals(List.of("a.mht", "b.html"), names, "既有排序行为（按 mtime 升序）不变");
    }

    @Test
    void missingOrNonDirectoryYieldsEmptyList() {
        assertEquals(List.of(), FileUtils.findMhtFiles(dir.resolve("nope").toString()));
        assertTrue(FileUtils.findMhtFiles(dir.resolve("empty").toString()).isEmpty());
    }
}
