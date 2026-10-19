package com.mhtextractor.engine;

import com.mhtextractor.model.PreviewItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导入规则服务测试（Q2/P1）：地址规范化 / 会话累计 / 轮次去重（含"移除后还能再加回来"的回归护栏）/
 * 无新文件不建组 / 配额轮次的传递 / 组名纯函数。
 *
 * <p>本类<b>不启动任何界面</b>，也不出现 UI 框架字面量 —— 这正是"导入规则已可无头单测"的证据
 * （{@code com.mhtextractor.app} 测试包由 {@code ArchitectureTest.headlessTestPackagesHaveNoJavaFx}
 * 强制）。生产路径的界面侧（线程、派发、模型命令）在
 * {@code com.mhtextractor.engine.WorkspaceImportCoordinator}，由 GUI 自动化覆盖。</p>
 */
class WorkspaceImportServiceTest {

    @TempDir
    Path dir;

    private final WorkspaceImportService service = new WorkspaceImportService();

    private File mht(Path parent, String name) throws Exception {
        return Files.createFile(parent.resolve(name)).toFile();
    }

    /** 造一个"无批次、无计数"的计划 */
    private static MhtImportPlanner.Plan plan(MhtImportPlanner.Batch... batches) {
        return new MhtImportPlanner.Plan(List.of(batches), 0, 0, 0, 0, 0, false, false);
    }

    /** 造一个"带累计计数"的计划（Plan 的计数是扫描开始以来的累计值） */
    private static MhtImportPlanner.Plan planWithCounters(int ignored, int hidden, int empty,
                                                         int unreadable, int depth) {
        return new MhtImportPlanner.Plan(List.of(), ignored, hidden, empty, unreadable, depth, false, false);
    }

    /** 与生产一致的口径：可见项 = removed == false（见 {@code MainController.visibleSnapshot()}） */
    private static List<String> visiblePaths(List<PreviewItem> workspace) {
        return workspace.stream().filter(i -> !i.removed).map(i -> i.path).toList();
    }

    // ==================== 2. 会话累计 ====================

    @Test
    void sessionStartsAtZeroAndTracksCumulativeCounters() {
        WorkspaceImportService.Session s = service.begin(List.of(dir.toString()), "test", false);

        assertTrue(s.accumulated().isEmpty(), "新会话的累计条目应为空");
        assertEquals(0, service.groupCount(s), "新会话的累计组数应为 0");
        assertEquals(0, s.ignoredNonMht());
        assertEquals(0, s.hiddenDirs());
        assertEquals(0, s.emptyDirs());
        assertEquals(0, s.unreadable());
        assertEquals(0, s.depthLimited());

        service.resolveRound(s, planWithCounters(1, 2, 3, 4, 5), List.of(), 0);
        assertEquals(1, s.ignoredNonMht());
        assertEquals(5, s.depthLimited());

        // 多轮之后：计数器是扫描器给的累计值，会话应等于最后一份 Plan 的值
        service.resolveRound(s, planWithCounters(6, 7, 8, 9, 10), List.of(), 0);
        assertEquals(6, s.ignoredNonMht(), "多轮后应等于最后一份 Plan 的累计值");
        assertEquals(7, s.hiddenDirs());
        assertEquals(8, s.emptyDirs());
        assertEquals(9, s.unreadable());
        assertEquals(10, s.depthLimited());
    }

    @Test
    void sessionAccumulatesItemsAndGroupCount() throws Exception {
        File f = mht(dir, "a.mht");
        WorkspaceImportService.Session s = service.begin(List.of(dir.toString()), "test", false);
        WorkspaceImportService.RoundOutcome out = service.resolveRound(
                s, plan(new MhtImportPlanner.TopLevelBatch(dir.toString(), List.of(f))), List.of(), 0);

        service.accumulate(s, out.applied().get(0).items());
        service.addCreatedGroups(s, 0);
        service.addCreatedGroups(s, 1);

        assertEquals(1, s.accumulated().size(), "累计条目应记录本轮新建的条目");
        assertEquals(1, service.groupCount(s), "累计组数应按 addCreatedGroups 叠加");
        assertEquals(f.getAbsolutePath(), s.accumulated().get(0).path);
    }

    // ==================== 3. resolveRound 去重（4dd4852 的回归护栏） ====================

    @Test
    void resolveRoundComparesAgainstVisibleItemsAndReimportsRemovedOnes() throws Exception {
        File f = mht(dir, "r1.mht");
        MhtImportPlanner.Plan p = plan(new MhtImportPlanner.TopLevelBatch(dir.toString(), List.of(f)));
        WorkspaceImportService.Session s = service.begin(List.of(dir.toString()), "test", false);

        // ① 列表为空 → 是"新文件"
        WorkspaceImportService.ResolvedBatch first =
                service.resolveRound(s, p, List.of(), 0).batches().get(0);
        assertFalse(first.skipped(), "空列表时同路径必须是新文件");
        assertEquals(List.of(f.getAbsolutePath()), first.items().stream().map(i -> i.path).toList());

        // ② 条目仍可见 → 重复导入是 no-op（不建组、不新增）
        PreviewItem item = new PreviewItem(f.getAbsolutePath(), f.getName(), null, dir.toString());
        List<PreviewItem> workspace = List.of(item);
        WorkspaceImportService.ResolvedBatch again =
                service.resolveRound(s, p, visiblePaths(workspace), workspace.size()).batches().get(0);
        assertTrue(again.skipped(), "条目仍可见 ⇒ 重复导入不得再产出条目");

        // ③ 条目被移除（仍在数据序里，但不在可见集合里）⇒ 同路径必须仍算新文件
        //    —— 这就是提交 4dd4852 修的缺陷：把"含已移除项的全量集合"当去重口径会让整批被跳过
        item.removed = true;
        WorkspaceImportService.ResolvedBatch afterRemove =
                service.resolveRound(s, p, visiblePaths(workspace), workspace.size()).batches().get(0);
        assertFalse(afterRemove.skipped(),
                "已移除项不得视为已导入（移除后必须能再加回来）；去重口径 = 可见项，不是全量项");
        assertEquals(List.of(f.getAbsolutePath()),
                afterRemove.items().stream().map(i -> i.path).toList());
    }

    @Test
    void resolveRoundKeepsInRoundIncrementalDedup() throws Exception {
        File a = mht(dir, "a.mht");
        File b = mht(dir, "b.mht");
        // 刻意让两个批次都含同一个文件：本轮内前一批已产出它 ⇒ 后一批必须视为已存在（原实现每批重读可见项）
        MhtImportPlanner.Plan p = plan(
                new MhtImportPlanner.TopLevelBatch(dir.toString(), List.of(a, b)),
                new MhtImportPlanner.TopLevelBatch(dir.toString(), List.of(b)));
        WorkspaceImportService.Session s = service.begin(List.of(dir.toString()), "test", false);

        WorkspaceImportService.RoundOutcome out = service.resolveRound(s, p, List.of(), 0);

        assertEquals(2, out.applied().get(0).items().size(), "第一批应产出两个文件");
        assertTrue(out.batches().get(1).skipped(),
                "第二批的同一个文件在本轮内已被第一批产出 ⇒ 不得再产出一份（原实现是增量的）");
    }

    @Test
    void resolveRoundMarksWasEmptyOnlyForFirstProducingBatch() throws Exception {
        File a = mht(dir, "a.mht");
        File b = mht(dir, "b.mht");
        MhtImportPlanner.Plan p = plan(
                new MhtImportPlanner.GroupBatch(dir.toString(), "sub", List.of(a)),
                new MhtImportPlanner.TopLevelBatch(dir.toString(), List.of(b)));
        WorkspaceImportService.Session s = service.begin(List.of(dir.toString()), "test", false);

        WorkspaceImportService.RoundOutcome out = service.resolveRound(s, p, List.of(), 0);

        assertTrue(out.batches().get(0).wasEmpty(), "本轮开始时列表为空 ⇒ 首批需选中首行");
        assertFalse(out.batches().get(1).wasEmpty(),
                "首批已让列表非空 ⇒ 后续批次不得重复触发选中（原实现是每批重读 visibleCount）");
    }

    @Test
    void resolveRoundDoesNotMarkWasEmptyWhenListWasNotEmpty() throws Exception {
        File a = mht(dir, "a.mht");
        MhtImportPlanner.Plan p = plan(new MhtImportPlanner.TopLevelBatch(dir.toString(), List.of(a)));
        WorkspaceImportService.Session s = service.begin(List.of(dir.toString()), "test", false);

        WorkspaceImportService.RoundOutcome out = service.resolveRound(s, p, List.of(), 3);

        assertFalse(out.batches().get(0).wasEmpty(), "列表本来就有可见项 ⇒ 不得触发选中首行");
    }

    // ==================== 4. 无新文件 ⇒ 该批不产出（RISK-1） ====================

    @Test
    void resolveRoundProducesNothingWhenNoFreshFiles() throws Exception {
        File f = mht(dir, "s1.mht");
        MhtImportPlanner.Plan p = plan(new MhtImportPlanner.GroupBatch(dir.toString(), "sub1", List.of(f)));
        WorkspaceImportService.Session s = service.begin(List.of(dir.toString()), "test", false);

        WorkspaceImportService.RoundOutcome out =
                service.resolveRound(s, p, List.of(f.getAbsolutePath()), 1);

        assertTrue(out.batches().get(0).skipped(), "无新文件的批次应为 skipped");
        assertTrue(out.batches().get(0).items().isEmpty(), "skipped 批次不得带条目");
        assertTrue(out.applied().isEmpty(), "无新文件的批次不得进入落地集合（RISK-1：不建组）");
        assertEquals(dir.toString(), out.batches().get(0).dirPath(), "skipped 批次仍要保留来源目录（debug 日志用）");
    }

    // ==================== 5. 批次类型与条目字段 ====================

    @Test
    void resolveRoundMapsBatchTypeAndItemFields() throws Exception {
        Path sub = Files.createDirectories(dir.resolve("sub1"));
        File inGroup = mht(sub, "g1.mht");
        MhtImportPlanner.Plan p = plan(
                new MhtImportPlanner.GroupBatch(sub.toString(), "sub1", List.of(inGroup)));

        WorkspaceImportService.Session s = service.begin(List.of(dir.toString()), "test", false);
        WorkspaceImportService.ResolvedBatch rb = service.resolveRound(s, p, List.of(), 0).batches().get(0);

        assertTrue(rb.grouped(), "GroupBatch 应标记为建组");
        assertEquals("sub1", rb.folderName(), "建组批次应带文件夹名");
        assertEquals(1, rb.items().size());
        assertEquals(inGroup.getAbsolutePath(), rb.items().get(0).path);
        assertEquals(inGroup.getName(), rb.items().get(0).name);
        assertEquals(sub.toString(), rb.items().get(0).sourceFolder, "来源目录应为文件的父目录");

        WorkspaceImportService.ResolvedBatch top = service.resolveRound(s,
                plan(new MhtImportPlanner.TopLevelBatch(dir.toString(), List.of(inGroup))),
                List.of(), 0).batches().get(0);
        assertFalse(top.grouped(), "TopLevelBatch 应为平铺");
        assertNull(top.folderName(), "平铺批次没有文件夹名");
    }

    // ==================== 6. 配额轮次：quotaHit / hasMore 的传递 ====================

    @Test
    void nextRoundPassesQuotaFlagsThrough() throws Exception {
        Path sub1 = Files.createDirectories(dir.resolve("sub1"));
        Path sub2 = Files.createDirectories(dir.resolve("sub2"));
        mht(sub1, "a.mht");
        mht(sub2, "b.mht");

        // 配额缩到每轮 1 组 / 1 文件 ⇒ 第一轮必然打满且还有剩余（生产恒用默认配额，见 begin 重载的 javadoc）
        WorkspaceImportService.Session s =
                service.begin(List.of(dir.toString()), "test", false, 1, 1);

        MhtImportPlanner.Plan r1 = service.nextRound(s);
        assertFalse(r1.batches().isEmpty(), "第一轮应有批次");
        assertTrue(r1.quotaHit(), "打满配额时 quotaHit 必须原样回传（调用方据此弹确认）");
        assertTrue(r1.hasMore(), "还有剩余工作时 hasMore 必须原样回传（调用方据此继续下一轮）");

        MhtImportPlanner.Plan r2 = service.nextRound(s);
        assertFalse(r2.batches().isEmpty(), "第二轮应产出剩余批次");
        assertFalse(r2.hasMore(), "第二轮扫完后 hasMore 应为 false（收尾）");
    }

    // ==================== 7. 组名纯函数 ====================

    @Test
    void groupNameFallsBackToDefaultPrefixWhenSanitizeYieldsEmpty() {
        WorkspaceImportService.GroupName byNull = service.resolveGroupName(null, 7, "组", List.of());
        assertEquals("组7", byNull.base(), "空名应退化为'默认前缀 + 组号'");
        assertEquals("组7", byNull.unique(), "无冲突时最终名等于原始名");

        WorkspaceImportService.GroupName byBlank = service.resolveGroupName("   ", 3, "Group", List.of());
        assertEquals("Group3", byBlank.base(), "纯空白名同样退化为默认名");
    }

    @Test
    void groupNameIsUniquifiedAgainstExistingNames() {
        WorkspaceImportService.GroupName same =
                service.resolveGroupName("sub1", 1, "组", List.of("other"));
        assertEquals("sub1", same.unique(), "无冲突时应保持原组名");

        WorkspaceImportService.GroupName conflict =
                service.resolveGroupName("sub2", 2, "组", List.of("sub2", "sub2(2)"));
        assertEquals("sub2", conflict.base(), "base 是唯一化之前的名字（日志 .09 的实参）");
        assertEquals("sub2(3)", conflict.unique(), "与既有组名冲突时应唯一化");

        WorkspaceImportService.GroupName nullExisting =
                service.resolveGroupName("sub2", 2, "组", null);
        assertEquals("sub2", nullExisting.unique(), "已有组名集合为 null 时按'无冲突'处理");
    }
}
