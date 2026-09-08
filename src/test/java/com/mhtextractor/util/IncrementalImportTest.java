package com.mhtextractor.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 增量导入去重/合并纯逻辑测试（F3）：
 * 按绝对路径去重，新文件保持扫描顺序追加，同名不同路径视为不同文件。
 */
class IncrementalImportTest {

    @TempDir
    Path dirA;

    @TempDir
    Path dirB;

    private File mht(Path dir, String name) throws Exception {
        return Files.createFile(dir.resolve(name)).toFile();
    }

    @Test
    void emptyExistingImportsAllInScanOrder() throws Exception {
        File f1 = mht(dirA, "1.mht");
        File f2 = mht(dirA, "2.mht");
        File f3 = mht(dirA, "3.mht");

        List<File> added = IncrementalImport.findNewFiles(List.of(f1, f2, f3), List.of());

        assertEquals(List.of(f1, f2, f3), added);
    }

    @Test
    void existingPathsAreSkipped() throws Exception {
        File f1 = mht(dirA, "1.mht");
        File f2 = mht(dirA, "2.mht");

        List<File> added = IncrementalImport.findNewFiles(
                List.of(f1, f2), List.of(f1.getAbsolutePath()));

        assertEquals(List.of(f2), added);
    }

    @Test
    void rescanningSameFolderAddsNothing() throws Exception {
        File f1 = mht(dirA, "1.mht");
        File f2 = mht(dirA, "2.mht");
        List<String> existing = List.of(f1.getAbsolutePath(), f2.getAbsolutePath());

        List<File> added = IncrementalImport.findNewFiles(List.of(f1, f2), existing);

        assertTrue(added.isEmpty());
    }

    @Test
    void sameNameDifferentPathBothKept() throws Exception {
        File a1 = mht(dirA, "1.mht");
        File b1 = mht(dirB, "1.mht");

        List<File> added = IncrementalImport.findNewFiles(
                List.of(b1), List.of(a1.getAbsolutePath()));

        assertEquals(List.of(b1), added);
    }

    @Test
    void partialOverlapKeepsScanOrder() throws Exception {
        File f1 = mht(dirA, "1.mht");
        File f2 = mht(dirA, "2.mht");
        File f3 = mht(dirA, "3.mht");

        List<File> added = IncrementalImport.findNewFiles(
                List.of(f1, f2, f3), List.of(f2.getAbsolutePath()));

        assertEquals(List.of(f1, f3), added);
    }
}
