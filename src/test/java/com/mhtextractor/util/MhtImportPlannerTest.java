package com.mhtextractor.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导入规划器测试（导入功能改造 §8.2）：
 * 地址分派 / 目录递归与自动建组规则（R-a~R-i）/ 批次顺序与文件排序 / 每轮配额 /
 * 计数器 / 盘符根目录判定。纯逻辑层，无 GUI 依赖。
 */
class MhtImportPlannerTest {

    @TempDir
    Path dir;

    @TempDir
    Path dirB;

    private File mht(Path parent, String name) throws Exception {
        return Files.createFile(parent.resolve(name)).toFile();
    }

    private MhtImportPlanner.Scanner scanner(String... addresses) {
        return MhtImportPlanner.scanner(List.of(addresses),
                MhtImportPlanner.DEFAULT_MAX_DEPTH,
                MhtImportPlanner.DEFAULT_MAX_GROUPS,
                MhtImportPlanner.DEFAULT_MAX_FILES);
    }

    private static List<MhtImportPlanner.Batch> allBatches(MhtImportPlanner.Scanner sc) {
        List<MhtImportPlanner.Batch> out = new ArrayList<>();
        MhtImportPlanner.Plan plan;
        do {
            plan = sc.nextRound();
            out.addAll(plan.batches());
        } while (plan.hasMore());
        return out;
    }

    private static List<MhtImportPlanner.GroupBatch> groupBatches(List<MhtImportPlanner.Batch> batches) {
        return batches.stream()
                .filter(b -> b instanceof MhtImportPlanner.GroupBatch)
                .map(b -> (MhtImportPlanner.GroupBatch) b)
                .toList();
    }

    // ---- 1. 单文件地址（.mht）：1 个 TopLevelBatch，1 文件 ----

    @Test
    void singleMhtFileAddressYieldsOneTopLevelBatch() throws Exception {
        File f = mht(dir, "a.mht");

        MhtImportPlanner.Plan plan = scanner(f.getAbsolutePath()).nextRound();

        assertEquals(1, plan.batches().size());
        MhtImportPlanner.Batch b = plan.batches().get(0);
        assertTrue(b instanceof MhtImportPlanner.TopLevelBatch);
        assertEquals(List.of(f), b.files());
        assertEquals(dir.toString(), b.dirPath());
        assertFalse(plan.hasMore());
    }

    // ---- 2. 单文件地址（.MHTML 大写）：命中（大小写不敏感） ----

    @Test
    void upperCaseMhtmlExtensionIsMatched() throws Exception {
        File f = mht(dir, "a.MHTML");

        MhtImportPlanner.Plan plan = scanner(f.getAbsolutePath()).nextRound();

        assertEquals(1, plan.fileCount());
        assertEquals(0, plan.ignoredNonMht());
    }

    // ---- 3. 非 MHT 文件地址：0 批次，ignoredNonMht == 1 ----

    @Test
    void nonMhtFileAddressIsIgnoredAndCounted() throws Exception {
        File f = mht(dir, "note.txt");

        MhtImportPlanner.Plan plan = scanner(f.getAbsolutePath()).nextRound();

        assertTrue(plan.isEmpty());
        assertEquals(1, plan.ignoredNonMht());
    }

    // ---- 4. 不存在路径：0 批次，unreadable == 1，不抛异常 ----

    @Test
    void nonexistentPathIsCountedUnreadableWithoutException() {
        MhtImportPlanner.Plan plan = assertDoesNotThrow(
                () -> scanner(dir.resolve("no-such-dir").toString()).nextRound());

        assertTrue(plan.isEmpty());
        assertEquals(1, plan.unreadable());
    }

    // ---- 5. null / 空地址列表：空 Plan，不抛异常 ----

    @Test
    void nullAddressListYieldsEmptyPlan() {
        MhtImportPlanner.Plan plan = assertDoesNotThrow(() ->
                MhtImportPlanner.scanner(null, MhtImportPlanner.DEFAULT_MAX_DEPTH,
                        MhtImportPlanner.DEFAULT_MAX_GROUPS, MhtImportPlanner.DEFAULT_MAX_FILES)
                        .nextRound());
        assertTrue(plan.isEmpty());
        assertFalse(plan.hasMore());
    }

    @Test
    void emptyAddressListYieldsEmptyPlan() {
        MhtImportPlanner.Plan plan = scanner().nextRound();
        assertTrue(plan.isEmpty());
        assertFalse(plan.hasMore());
    }

    // ---- 6. 目录无子目录，含 3 个 MHT：1 个 TopLevelBatch，3 文件 ----

    @Test
    void flatDirectoryYieldsOneTopLevelBatch() throws Exception {
        File f1 = mht(dir, "a.mht");
        File f2 = mht(dir, "b.mht");
        File f3 = mht(dir, "c.mht");

        MhtImportPlanner.Plan plan = scanner(dir.toString()).nextRound();

        assertEquals(1, plan.batches().size());
        assertEquals(3, plan.fileCount());
        assertEquals(List.of(f1, f2, f3), plan.batches().get(0).files());
    }

    // ---- 7. 目录含 2 个子目录各含 MHT：3 批次（1 顶层 + 2 组），组名 = 目录名 ----

    @Test
    void directoryWithSubdirectoriesProducesGroups() throws Exception {
        mht(dir, "root.mht");
        Path sub1 = Files.createDirectory(dir.resolve("sub1"));
        Path sub2 = Files.createDirectory(dir.resolve("sub2"));
        mht(sub1, "s1.mht");
        mht(sub2, "s2.mht");

        List<MhtImportPlanner.Batch> batches = allBatches(scanner(dir.toString()));

        assertEquals(3, batches.size());
        assertTrue(batches.get(0) instanceof MhtImportPlanner.TopLevelBatch);
        List<MhtImportPlanner.GroupBatch> groups = groupBatches(batches);
        assertEquals(List.of("sub1", "sub2"), groups.stream().map(MhtImportPlanner.GroupBatch::folderName).toList());
    }

    // ---- 8. 子目录本层无 MHT，更深层有：该子目录不产批次；更深层产 GroupBatch ----

    @Test
    void emptyLayerProducesNoBatchButDeeperLayerDoes() throws Exception {
        Path mid = Files.createDirectory(dir.resolve("noMhtHere"));
        Path deep = Files.createDirectory(mid.resolve("deeper"));
        mht(deep, "d1.mht");

        List<MhtImportPlanner.Batch> batches = allBatches(scanner(dir.toString()));

        assertEquals(1, batches.size());
        List<MhtImportPlanner.GroupBatch> groups = groupBatches(batches);
        assertEquals(1, groups.size());
        assertEquals("deeper", groups.get(0).folderName());
    }

    // ---- 9. 同名目录出现在不同深度：2 个 GroupBatch 都在（planner 侧不唯一化） ----

    @Test
    void sameNamedDirectoriesAtDifferentDepthsBothProduceGroupBatches() throws Exception {
        Path sub = Files.createDirectory(dir.resolve("sub"));
        Path inner = Files.createDirectory(sub.resolve("sub"));
        mht(sub, "s1.mht");
        mht(inner, "s2.mht");

        List<MhtImportPlanner.GroupBatch> groups = groupBatches(allBatches(scanner(dir.toString())));

        assertEquals(2, groups.size());
        assertEquals(2, groups.stream().filter(g -> g.folderName().equals("sub")).count());
    }

    // ---- 10. 隐藏目录 .git 含 MHT：不产批次，hiddenDirs == 1 ----

    @Test
    void hiddenDirectoryIsSkippedAndCounted() throws Exception {
        Path hidden = Files.createDirectory(dir.resolve(".git"));
        mht(hidden, "h1.mht");

        MhtImportPlanner.Plan plan = scanner(dir.toString()).nextRound();

        assertTrue(plan.isEmpty());
        assertEquals(1, plan.hiddenDirs());
    }

    // ---- 11. 空目录：不产批次，emptyDirs 计数 ----

    @Test
    void emptyDirectoryIsCounted() throws Exception {
        Files.createDirectory(dir.resolve("empty"));

        MhtImportPlanner.Plan plan = scanner(dir.toString()).nextRound();

        assertTrue(plan.isEmpty());
        assertEquals(1, plan.emptyDirs());
    }

    // ---- 12. 深度 40 层目录树：不抛异常，depthLimited > 0 ----

    @Test
    void deepDirectoryTreeIsDepthLimited() throws Exception {
        Path p = dir;
        Path depth30 = null;
        for (int i = 0; i < 40; i++) {
            p = Files.createDirectory(p.resolve("d" + i));
            if (i == 30) {
                depth30 = p;
            }
        }
        File reachable = mht(depth30, "deep.mht");   // 深度 30，仍在 32 层上限内

        MhtImportPlanner.Plan plan = assertDoesNotThrow(
                () -> scanner(dir.toString()).nextRound());

        assertTrue(plan.depthLimited() > 0);
        assertEquals(1, plan.fileCount());
        assertTrue(plan.batches().get(0).files().contains(reachable),
                "上限内深度的 MHT 应被找到");
    }

    // ---- 13. 同一文件同时被祖先与后代地址覆盖：plan 内不重复出现（seenFiles） ----

    @Test
    void ancestorAndDescendantAddressesDoNotDuplicateFiles() throws Exception {
        Path sub = Files.createDirectory(dir.resolve("sub"));
        mht(sub, "s1.mht");
        mht(dir, "root.mht");

        List<MhtImportPlanner.Batch> batches = allBatches(scanner(dir.toString(), sub.toString()));

        List<String> paths = new ArrayList<>();
        for (MhtImportPlanner.Batch b : batches) {
            for (File f : b.files()) {
                paths.add(f.getAbsolutePath());
            }
        }
        assertEquals(List.copyOf(paths).stream().distinct().count(), paths.size(),
                "同一文件不得在计划中重复出现");
        assertEquals(2, paths.size());   // root.mht + s1.mht
    }

    // ---- 14. 批次内文件名排序：按 CASE_INSENSITIVE_ORDER 再按大小写敏感次序 ----

    @Test
    void filesInBatchAreSortedByName() throws Exception {
        File b1 = mht(dir, "b1.mht");
        File b10 = mht(dir, "b10.mht");
        File b2upper = mht(dir, "B2.mht");   // Windows 文件系统大小写不敏感，无法并存 B2/b2

        MhtImportPlanner.Plan plan = scanner(dir.toString()).nextRound();

        assertEquals(List.of(b1, b10, b2upper), plan.batches().get(0).files());
    }

    // ---- 15. 批次顺序：本层 TopLevelBatch 在子目录 GroupBatch 之前 ----

    @Test
    void topLevelBatchPrecedesGroupBatches() throws Exception {
        mht(dir, "root.mht");
        Path sub = Files.createDirectory(dir.resolve("sub"));
        mht(sub, "s1.mht");

        List<MhtImportPlanner.Batch> batches = allBatches(scanner(dir.toString()));

        assertEquals(2, batches.size());
        assertTrue(batches.get(0) instanceof MhtImportPlanner.TopLevelBatch);
        assertTrue(batches.get(1) instanceof MhtImportPlanner.GroupBatch);
    }

    // ---- 16. 每轮配额：25 个组，maxGroups=10：逐轮推进，累计组数 = 25 ----

    @Test
    void groupQuotaSplitsRounds() throws Exception {
        for (int i = 1; i <= 25; i++) {
            Path sub = Files.createDirectory(dir.resolve("g" + i));
            mht(sub, "f.mht");
        }
        MhtImportPlanner.Scanner sc = MhtImportPlanner.scanner(List.of(dir.toString()),
                MhtImportPlanner.DEFAULT_MAX_DEPTH, 10, MhtImportPlanner.DEFAULT_MAX_FILES);

        int total = 0;
        int rounds = 0;
        MhtImportPlanner.Plan plan;
        do {
            plan = sc.nextRound();
            rounds++;
            total += plan.groupCount();
            assertTrue(plan.groupCount() <= 10, "每轮组数不得超过配额");
            if (plan.hasMore()) {
                assertTrue(plan.quotaHit(), "还有剩余工作时本轮必须命中配额");
            }
        } while (plan.hasMore());
        assertEquals(25, total);
        assertEquals(3, rounds);
    }

    // ---- 17. 每轮配额：maxFiles 触发，轮内文件数 ≤ maxFiles ----

    @Test
    void fileQuotaSplitsRounds() throws Exception {
        File f1 = mht(dir, "a.mht");
        File f2 = mht(dir, "b.mht");
        File f3 = mht(dir, "c.mht");
        MhtImportPlanner.Scanner sc = MhtImportPlanner.scanner(
                List.of(f1.getAbsolutePath(), f2.getAbsolutePath(), f3.getAbsolutePath()),
                MhtImportPlanner.DEFAULT_MAX_DEPTH, MhtImportPlanner.DEFAULT_MAX_GROUPS, 2);

        MhtImportPlanner.Plan round1 = sc.nextRound();
        assertTrue(round1.fileCount() <= 2);
        assertTrue(round1.quotaHit());
        assertTrue(round1.hasMore());

        MhtImportPlanner.Plan round2 = sc.nextRound();
        assertEquals(1, round2.fileCount());
        assertFalse(round2.hasMore());
        assertEquals(3, round1.fileCount() + round2.fileCount());
    }

    // ---- 18. 各计数正确性：一次综合场景下的计数器 ----

    @Test
    void countersAreAccurateInCombinedScenario() throws Exception {
        mht(dir, "root.mht");                            // 顶层批次
        Path sub = Files.createDirectory(dir.resolve("sub"));
        mht(sub, "s1.mht");                              // 组批次
        Files.createDirectory(dir.resolve("empty"));     // 空目录
        Path hidden = Files.createDirectory(dir.resolve(".h"));
        mht(hidden, "h.mht");                            // 隐藏目录
        mht(dir, "other.txt");                           // 目录内非 MHT → ignoredNonMht
        File note = mht(dirB, "note.txt");               // 目录外：非 MHT 地址 → ignoredNonMht

        MhtImportPlanner.Plan plan = scanner(
                dir.toString(),
                dir.resolve("no-such").toString(),       // 不存在 → unreadable
                note.getAbsolutePath())                  // 非 MHT 地址 → ignoredNonMht
                .nextRound();

        assertEquals(2, plan.ignoredNonMht());           // 目录内 other.txt + 目录外地址 note.txt
        assertEquals(1, plan.hiddenDirs());
        assertEquals(1, plan.emptyDirs());
        assertEquals(1, plan.unreadable());
        assertEquals(2, plan.fileCount());
        assertEquals(1, plan.groupCount());
    }

    // ---- 18b. FeatureExercise 统一导入「阶段一」纯扫描断言 1:1 外迁（步骤 6 / 需求 C3）：
    // 原断言（FeatureExercise 旧行 858–866）：组批次数 = 4（sub1,sub2,sub2(2),deeper）、
    // 非 MHT = 1、隐藏目录 = 1。FE 保留 GUI 可观察面（应用计划后的列表项与组名）。 ----

    @Test
    void combinedTreeYieldsFourGroupBatchesWithExactNoiseCounts() throws Exception {
        Path sub1 = Files.createDirectory(dir.resolve("sub1"));
        Path sub2 = Files.createDirectory(dir.resolve("sub2"));
        Path sub2deep = Files.createDirectory(sub2.resolve("sub2"));  // 同名深层 → sub2(2)
        Path hidden = Files.createDirectory(dir.resolve(".hidden"));
        Path emptyLayer = Files.createDirectory(dir.resolve("noMhtHere"));
        Path deeper = Files.createDirectory(emptyLayer.resolve("deeper"));

        mht(dir, "r1.mht");            // 根层 → 顶层平铺
        mht(dir, "r2.mht");            // 根层
        mht(sub1, "s1.mht");           // 组 sub1
        mht(sub2, "s2.mht");           // 组 sub2
        mht(sub2deep, "s3.mht");       // 组 sub2(2)（同名 → 唯一化）
        mht(hidden, "h1.mht");         // 应跳过
        mht(deeper, "d1.mht");         // 组 deeper（noMhtHere 本层无 MHT → 不建组）
        Files.writeString(dir.resolve("note.txt"), "x");  // 非 MHT → 忽略

        MhtImportPlanner.Plan plan = scanner(dir.toString()).nextRound();
        long groupBatches = plan.batches().stream()
                .filter(b -> b instanceof MhtImportPlanner.GroupBatch).count();
        assertEquals(4, groupBatches);              // sub1,sub2,sub2(2),deeper
        assertEquals(1, plan.ignoredNonMht());      // note.txt
        assertEquals(1, plan.hiddenDirs());         // .hidden
    }

    // ---- 19. findDriveRoots："D:\" 命中；"D:\a" 不命中 ----

    @Test
    void findDriveRootsDetectsRootPaths() {
        assertEquals(List.of("D:\\"), MhtImportPlanner.findDriveRoots(List.of("D:\\")));
        assertTrue(MhtImportPlanner.findDriveRoots(List.of("D:\\a")).isEmpty());
        assertEquals(List.of("D:\\"),
                MhtImportPlanner.findDriveRoots(List.of("C:\\x", "D:\\", "E:\\y")));
    }

    // ---- 20. 组批次的 dirPath = 该子目录的绝对路径 ----

    @Test
    void groupBatchDirPathIsTheSubDirectoryAbsolutePath() throws Exception {
        Path sub = Files.createDirectory(dir.resolve("sub"));
        mht(sub, "s1.mht");

        List<MhtImportPlanner.GroupBatch> groups = groupBatches(allBatches(scanner(dir.toString())));

        assertEquals(1, groups.size());
        assertEquals(sub.toString(), groups.get(0).dirPath());
    }
}
