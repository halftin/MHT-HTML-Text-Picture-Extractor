package com.mhtextractor.engine;

import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 未命名组规则测试（步骤 3 · 功能一，需求 §1.1 决策 1.1–1.12）。
 *
 * <p>两部分：</p>
 * <ol>
 *   <li>纯规则四态 —— {@link ExtractionPreconditions#unnamedGroups}：手工未命名 → 命中；
 *       手工组已改名 → 不命中；导入组（默认 false）→ 不命中；改回默认名 → 不命中；</li>
 *   <li>标记生命周期 —— {@link WorkspaceModel} 真实路径：手工建组置 true；撤销/撤销拆分恢复的组
 *       保留其<b>当时的</b>标记（{@code UndoManager.Record} 与 {@code GroupSnap} 持组<b>实例</b>引用，
 *       标记随对象走）；撤销后再建的新组重新置 true。</li>
 * </ol>
 *
 * <p>零 JavaFX：本类只碰 {@code app} + {@code util} + {@code model}，可无头执行。</p>
 */
class UnnamedGroupsRuleTest {

    private static PreviewItem item(String name) {
        return new PreviewItem("C:/x/" + name, name, LocalDateTime.now(), "C:/x");
    }

    private static GroupItem<PreviewItem> group(int no, String name) {
        return new GroupItem<>(no, name);
    }

    // ==================== 纯规则四态（决策 1.1–1.12） ====================

    @Test
    void manualUnnamedGroupIsReported() {
        GroupItem<PreviewItem> g = group(1, "组1");
        g.setDefaultNamed(true);   // 等价于手工建组路径（WorkspaceModel.group 置 true）
        assertEquals(List.of("组1"), ExtractionPreconditions.unnamedGroups(List.of(g)));
    }

    @Test
    void renamedGroupIsNotReported() {
        GroupItem<PreviewItem> g = group(1, "我的组");
        g.setDefaultNamed(false);   // 用户提交过改名
        assertEquals(List.of(), ExtractionPreconditions.unnamedGroups(List.of(g)));
    }

    @Test
    void importedGroupIsNotReportedByDefault() {
        // 导入自动建组走 new GroupItem（WorkspaceImportCoordinator.createGroupForImport），标记默认 false
        GroupItem<PreviewItem> g = group(1, "子目录");
        assertEquals(List.of(), ExtractionPreconditions.unnamedGroups(List.of(g)));
    }

    @Test
    void renameBackToDefaultNameIsStillNotReported() {
        // 决策 1.2：改回默认名后不再提醒 —— 标记一经清除即为永久
        GroupItem<PreviewItem> g = group(1, "组1");
        g.setDefaultNamed(false);
        g.setName("组1");
        assertEquals(List.of(), ExtractionPreconditions.unnamedGroups(List.of(g)));
    }

    @Test
    void emptyOrNullGroupsPass() {
        assertEquals(List.of(), ExtractionPreconditions.unnamedGroups(List.of()));
        assertEquals(List.of(), ExtractionPreconditions.unnamedGroups(null));
    }

    @Test
    void onlyUnnamedGroupsAreListedAmongMixed() {
        GroupItem<PreviewItem> a = group(1, "组1");
        a.setDefaultNamed(true);
        GroupItem<PreviewItem> b = group(2, "组2");   // 导入或已改名
        GroupItem<PreviewItem> c = group(3, "组3");
        c.setDefaultNamed(true);
        assertEquals(List.of("组1", "组3"),
                ExtractionPreconditions.unnamedGroups(List.of(a, b, c)));
    }

    // ==================== 标记生命周期（WorkspaceModel 真实路径） ====================

    @Test
    void manualGroupPathSetsMarkerAndUndoKeepsIt() {
        WorkspaceModel ws = new WorkspaceModel();
        ws.importAll(List.of(item("a.mht"), item("b.mht")));

        GroupItem<PreviewItem> g = ws.group(ws.allItems());   // 手工建组唯一入口
        assertTrue(g.isDefaultNamed(), "手工建组必须置默认名标记");

        assertTrue(ws.undo(), "撤销建组应成功");
        assertFalse(ws.groups().contains(g), "撤销后组应离开数据序");
        assertTrue(g.isDefaultNamed(), "撤销不得丢标记（Record 持组实例，标记随对象走）");
    }

    @Test
    void undoingUngroupRestoresTheSameInstanceWithItsMarker() {
        WorkspaceModel ws = new WorkspaceModel();
        ws.importAll(List.of(item("a.mht"), item("b.mht")));
        GroupItem<PreviewItem> g = ws.group(ws.allItems());
        assertTrue(g.isDefaultNamed());

        ws.ungroup(g);                       // 拆分组：组实例进入 UNGROUP 记录
        assertTrue(g.isDefaultNamed(), "拆分操作不得动标记");
        assertTrue(ws.undo(), "撤销拆分应成功");
        assertEquals(List.of(g), ws.groups(), "撤销拆分必须恢复同一组实例");
        assertTrue(g.isDefaultNamed(), "撤销拆分恢复的组保留其标记（GroupSnap 持实例引用）");
    }

    @Test
    void regroupAfterUndoProducesFreshMarkedGroup() {
        WorkspaceModel ws = new WorkspaceModel();
        ws.importAll(List.of(item("a.mht"), item("b.mht")));
        ws.group(ws.allItems());
        assertTrue(ws.undo());

        GroupItem<PreviewItem> g2 = ws.group(ws.allItems());   // 撤销后再建（本应用无重做栈）
        assertTrue(g2.isDefaultNamed(), "撤销后再建组：新组仍以默认名标记");
    }

    @Test
    void importedGroupAppendedViaModelIsNotMarked() {
        WorkspaceModel ws = new WorkspaceModel();
        // WorkspaceImportCoordinator 同款构造：new GroupItem + appendGroup（自动建组，标记默认 false）
        GroupItem<PreviewItem> imported = new GroupItem<>(ws.nextGroupNo(), "子目录");
        imported.getMembers().add(item("s1.mht"));
        ws.appendGroup(imported);

        assertFalse(imported.isDefaultNamed(), "导入自动建组不得带默认名标记");
        assertEquals(List.of(), ExtractionPreconditions.unnamedGroups(ws.groups()));
    }

    @Test
    void userRenameClearsMarkerPermanentlyAtModelLevel() {
        WorkspaceModel ws = new WorkspaceModel();
        ws.importAll(List.of(item("a.mht"), item("b.mht")));
        GroupItem<PreviewItem> g = ws.group(ws.allItems());

        // 生产路径：MainController.onGroupNameEdited 提交分支同时置 false（系统唯一化改名不清标记）
        g.setName("我的组");
        g.setDefaultNamed(false);
        g.setName("组1");                    // 改回默认名
        assertFalse(g.isDefaultNamed(), "一旦提交过改名，标记永久为 false");
        assertEquals(List.of(), ExtractionPreconditions.unnamedGroups(ws.groups()));
    }
}
