package com.mhtextractor.app;

import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.GroupStructure;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkspaceModel} 语义锚点（差分测试）。
 *
 * <p><b>为什么单独一个类</b>：{@link WorkspaceModelTest} 覆盖的是单个 API 的局部行为。
 * 本类补的是"<b>一串操作序列</b>"的**逐检查点期望值** —— 因为接下去要把它接入生产、
 * 让 {@code MainController.items} 变成它的单向投影，届时的回归风险不再是"某个方法错了"，
 * 而是"<b>序列走完后状态与 GUI 既有语义不一致</b>"。这类回归用单点断言抓不到。</p>
 *
 * <p>因此本类扮演"对照实现"的角色：用**独立于 WorkspaceModel 的判据**
 * （{@link GroupStructure#flatten} 重算显示序、逐元素身份比对、按记录的语义反推期望结构）
 * 固定住模型的当前语义。接投影后，GUI 侧只需把同一串序列跑一遍即可与本类对齐。</p>
 *
 * <p><b>零 JavaFX</b>：只用模型与 util 的公开 API，不引用任何 UI 框架类型
 * （{@code ArchitectureTest.headlessTestPackagesHaveNoJavaFx} 会扫本包）。</p>
 */
class WorkspaceModelParityTest {

    private static PreviewItem item(String name) {
        return new PreviewItem("C:/x/" + name, name, LocalDateTime.now(), "C:/x");
    }

    /** 建立模型并导入给定条目（每个测试的第一步都是它）。 */
    private static WorkspaceModel workspaceWith(PreviewItem... items) {
        WorkspaceModel m = new WorkspaceModel();
        m.addItems(List.of(items));
        return m;
    }

    /**
     * 与 {@link GroupStructure#flatten} 逐元素身份比对（<b>不采信模型自己的 displayOrder()</b>）。
     *
     * <p>这是本类的核心判据：显示序由 GUI 现用的同一个纯函数独立重算，
     * 从而"模型说的显示序"与"GUI 会渲染出的显示序"必须一致。</p>
     */
    private static void assertDisplayOrderMatchesStructure(WorkspaceModel m, Predicate<Object> removed) {
        List<Object> expected = GroupStructure.flatten(m.topLevel(), removed);
        List<Object> actual = m.displayOrder();
        assertEquals(expected.size(), actual.size(), "显示序长度应与独立重算一致");
        for (int i = 0; i < expected.size(); i++) {
            assertSame(expected.get(i), actual.get(i), "显示序第 " + i + " 位应为同一对象");
        }
    }

    private static Predicate<Object> removedPredicate() {
        return o -> o instanceof PreviewItem it && it.removed;
    }

    /**
     * 断言顶层为给定的精确顺序（身份比对）。
     *
     * <p>⚠️ {@code msg} 必须在 <b>varargs 之前</b>：早期版本把它当最后一个元素传给
     * {@code Object...}，于是说明文字被当成"期望的顶层条目"，期望长度恒比实际多 1
     * —— 六条测试同时假失败，而模型其实是对的。给人看的文字绝不能混进可变参数。</p>
     */
    private static void assertTopLevelIs(WorkspaceModel m, String msg, Object... expected) {
        List<Object> actual = m.topLevel();
        assertEquals(List.of(expected).size(), actual.size(), msg + "：顶层长度不符");
        for (int i = 0; i < expected.length; i++) {
            assertSame(expected[i], actual.get(i), msg + "：顶层第 " + i + " 位不符");
        }
    }

    // ==================== 1. 锚点：完整操作序列的往返一致性 ====================

    /**
     * 序列：导入 → 建组 → 拆组 → 移除 → 撤销。
     *
     * <p>关键断言是**最后一步撤销后逐元素身份回到序列开始之前** ——
     * 这正是 H31 记录过的"撤销建组后重新建组产生重复项"一类状态分叉的探测器。</p>
     */
    @Test
    void fullSequenceRoundTripRestoresExactIdentityOrder() {
        PreviewItem a = item("a.mht"), b = item("b.mht"), c = item("c.mht");
        WorkspaceModel m = new WorkspaceModel();

        m.addItems(List.of(a, b, c));
        assertEquals(1, m.undoDepth(), "一次导入 = 一条撤销记录");
        assertTopLevelIs(m, "扁平结构", a, b, c);

        GroupItem<PreviewItem> g = m.group(List.of(a, b));
        assertNotNull(g);
        assertEquals(2, m.undoDepth());
        assertTopLevelIs(m, "建组后", g, c);
        assertEquals(3, m.visibleCount(), "组条目不计入可见数，成员计入");

        m.ungroup(g);
        assertEquals(3, m.undoDepth());
        assertTopLevelIs(m, "扁平结构", a, b, c);
        assertTrue(m.groups().isEmpty());

        m.removeItems(List.of(b));
        assertEquals(4, m.undoDepth());
        assertEquals(2, m.visibleCount());
        assertEquals(List.of(a, b, c), m.allItems(), "隐藏式移除：条目必须留在数据序（供撤销恢复）");
        assertTopLevelIs(m, "移除后数据序", a, b, c);

        assertTrue(m.undo(), "撤销移除");
        assertEquals(3, m.visibleCount());
        assertEquals(3, m.undoDepth());

        // 撤销栈：现在是"撤拆组"→ 组回位；再一步"撤建组"→ 回到扁平；
        // 最后一步"撤导入"→ 清空。注意 g 的成员会被清空，但 g 本身仍是同一对象。
        assertTrue(m.undo(), "撤销拆组：应把组放回顶层");
        assertEquals(2, m.undoDepth());
        assertTopLevelIs(m, "撤销拆组后", g, c);
        assertEquals(List.of(a, b), g.getMembers(), "撤销拆组应恢复组成员");

        assertTrue(m.undo(), "撤销建组");
        assertEquals(1, m.undoDepth());
        assertTopLevelIs(m, "撤销建组后", a, b, c);
        // 实测语义：撤销建组后条目的 parentGroup 仍指向那个已脱离顶层的旧组对象
        //（与撤销移除后保留父指针的做法一致，供后续撤销/恢复使用）。
        // 调用方不得据此判断"成员仍在组内" —— 归属必须看 topLevel/structure，而不是这个指针。
        assertSame(g, a.parentGroup, "撤销建组后 parentGroup 仍残留指向旧组对象");
        assertFalse(m.topLevel().contains(g), "旧组必须已脱离顶层");

        assertTrue(m.undo(), "撤销导入");
        assertEquals(0, m.undoDepth(), "栈应恰好清空（每步一条记录：导入/建组/拆组/移除）");
        assertTrue(m.topLevel().isEmpty());
    }

    // ==================== 2. 跨组建组：显示序 + 无重复项 ====================

    /**
     * 两个组的成员被合并成一个新组。
     *
     * <p>这是 {@code WorkspaceModel.removeFromItsParent} 那段"同时摘两层 + 兜底扫全组"
     * 逻辑的守卫：若只按 {@code parentGroup} 摘除，同一对象会既留在旧组、又进新组，
     * 于是<b>顶层出现重复项</b>。本测试用顶层精确顺序把该故障钉死。</p>
     */
    @Test
    void groupingAcrossExistingGroupsLeavesNoDuplicateEntries() {
        PreviewItem a = item("a"), b = item("b"), c = item("c"), d = item("d"), e = item("e");
        WorkspaceModel m = workspaceWith(a, b, c, d, e);

        GroupItem<PreviewItem> g1 = m.group(List.of(a, b));
        GroupItem<PreviewItem> g2 = m.group(List.of(c, d));
        assertNotNull(g1);
        assertNotNull(g2);
        assertTopLevelIs(m, "两组建组后", g1, g2, e);

        // 跨组：从 g1 取 a、从 g2 取 c 合成新组
        GroupItem<PreviewItem> g3 = m.group(List.of(a, c));

        assertNotNull(g3);
        assertEquals(List.of(a, c), g3.getMembers());
        assertEquals(List.of(b), g1.getMembers(), "b 必须留在原组");
        assertEquals(List.of(d), g2.getMembers(), "d 必须留在原组");
        assertSame(g3, a.parentGroup);
        assertSame(g3, c.parentGroup);
        // 插入槽位 = 目标中最靠前的落点：a 在 g1 内（g1 在索引 0）→ 落点 1；c 在 g2 内（g2 在索引 1）→ 落点 2
        // → 槽位 = 1，即紧接 g1 之后（生产语义，见 groupSlotIsEarliestTopLevelPositionIncludingOwningGroup）
        assertTopLevelIs(m, "跨组建组后", g1, g3, g2, e);
        // allItems() 按顶层保留顺序遍历：g1(b) → g3(a,c) → g2(d) → e
        assertEquals(List.of(b, a, c, d, e), m.allItems(), "成员总数不得因建组而增加，且不得出现重复项");
        assertEquals(5, m.visibleCount());
        // 显示序由 GUI 现用纯函数独立重算 → 组条目 + 其可见成员
        assertDisplayOrderMatchesStructure(m, removedPredicate());

        // 移除 b、d：两者分别是 g1、g2 的唯一成员 → 两个组都应变空并被销毁
        m.removeItems(List.of(b, d));
        assertTrue(b.removed && d.removed);
        assertTopLevelIs(m, "移除 b,d 后（致空组已销毁）", g3, e);
        assertEquals(1, m.groups().size(), "g1/g2 已致空，应自动销毁；只剩 g3");
        assertEquals(3, m.visibleCount(), "a、c、e 可见");

        // 显示序必须排除已移除项（这一步才真正让 b/d 处于 removed 状态）
        List<Object> display = m.displayOrder();
        assertFalse(display.contains(b), "被移除的条目 b 不得出现在显示序（无论组是否仍在顶层）");
        assertFalse(display.contains(d), "被移除的条目 d 不得出现在显示序");
    }

    // ==================== 3. 撤销重放 ====================

    /**
     * 撤销建组 → 撤销拆组 → 撤销建组，逐步核对顶层精确顺序。
     *
     * <p>只断言最终结果会漏掉"中间某步把成员同时留在顶层与组内"的瞬态错误，
     * 因此每一步都取快照核对。</p>
     */
    @Test
    void undoReplayKeepsTopLevelExactAtEveryStep() {
        PreviewItem a = item("a"), b = item("b"), c = item("c");
        WorkspaceModel m = workspaceWith(a, b, c);

        GroupItem<PreviewItem> g = m.group(List.of(b, c));
        assertNotNull(g);
        assertTopLevelIs(m, "b,c 建组后", a, g);
        assertEquals(2, m.undoDepth(), "导入 + 建组 = 2 条记录");

        assertTrue(m.undo(), "撤销建组");
        assertTopLevelIs(m, "扁平结构", a, b, c);
        assertTrue(m.groups().isEmpty());

        assertTrue(m.undo(), "撤销导入");
        assertTrue(m.topLevel().isEmpty());

        // 重新导入并建组，确认"撤销后重建"不产生重复项（H17 记录的故障模式）
        m.addItems(List.of(a, b, c));
        GroupItem<PreviewItem> g2 = m.group(List.of(a, b));
        assertNotNull(g2);
        assertTopLevelIs(m, "重新建组后", g2, c);
        assertEquals(3, m.allItems().size());
        assertDisplayOrderMatchesStructure(m, removedPredicate());

        // ★ 显式覆盖 undoGroup 的"恢复 parentGroup 指针"：拆组会把成员指针置 null，
        //   若 undoGroup 不重新指向被恢复的组，父组指针就会永久丢失（后续按 parentGroup
        //   定位成员的功能全部错位）。用"拆组 → 撤销拆组"就能把该分支逼出来。
        m.ungroup(g2);
        assertNull(a.parentGroup, "拆组应把成员父组指针置 null");
        assertNull(b.parentGroup);

        assertTrue(m.undo(), "撤销拆组");
        assertSame(g2, a.parentGroup, "撤销拆组应把成员父组指针恢复回被撤销的组");
        assertSame(g2, b.parentGroup);
        assertTopLevelIs(m, "撤销拆组后", g2, c);
        assertEquals(List.of(a, b), g2.getMembers());
    }

    // ==================== 4. 顶层槽位语义 ====================

    /**
     * 插入槽位 = 所有目标中**最靠前的落点**；落点定义为
     * {@code 目标在顶层 → 其自身索引；目标在组内 → 其所属组索引 + 1}。
     *
     * <p><b>规格修正（接入生产时）</b>：原实现只取"目标（或其所属组）在顶层的最靠前索引"，
     * 组内成员用的是**所属组自身的索引**而非"组索引 + 1"，与生产
     * {@code MainController.onGroupClicked} 不一致。两者在"跨组重建组"时给出不同顺序 ——
     * 本测试原来钉的是旧模型的顺序（{@code a, g2, g}），现按生产顺序修正为 {@code a, g, g2}。
     * 这正是"接入生产前必须逐行核对语义"的价值所在：否则用户会看到组位置变化。</p>
     */
    @Test
    void groupSlotIsEarliestTopLevelPositionIncludingOwningGroup() {
        PreviewItem a = item("a"), b = item("b"), c = item("c"), d = item("d");
        WorkspaceModel m = workspaceWith(a, b, c, d);

        GroupItem<PreviewItem> g = m.group(List.of(b, c));   // b 在顶层索引 1
        assertNotNull(g);
        assertTopLevelIs(m, "b,c 建组后", a, g, d);

        // b 在组 g 内（g 位于顶层索引 1）→ 落点 = 1+1 = 2；d 在顶层索引 2 → 落点 = 2。
        // 槽位 = 2 → 组插在 g 之后（生产语义：组内成员的落点在其所属组之后）。
        GroupItem<PreviewItem> g2 = m.group(List.of(b, d));
        assertNotNull(g2);
        assertTopLevelIs(m, "跨组重建组后", a, g, g2);
        assertEquals(List.of(b, d), g2.getMembers());
        assertEquals(List.of(c), g.getMembers(), "c 必须留在原组");

        // 空组自动销毁的反面：仍有成员的 g 必须留在顶层
        assertEquals(2, m.groups().size());
        assertDisplayOrderMatchesStructure(m, removedPredicate());
    }

    // ==================== 5. 拒绝路径不得产生撤销记录 ====================

    /**
     * 拒绝路径（空输入 / null / 不在工作区内的条目）必须是纯 no-op：结构、栈深、可见数都不变。
     *
     * <p><b>规格修正（接入生产时）</b>：原断言把"单项建组"列为拒绝路径，但生产
     * （{@code MainController.onGroupClicked}）允许单项建组（R9）。模型的 {@code < 2} 守卫与生产
     * 不符，已改为只拒绝空输入；并补上"不在工作区内的条目"这一条 —— 它是实测出来的真实故障：
     * 若只用 {@code parentGroup != null} 判断归属，{@code clear()} 后残留的幽灵归属会让空工作区
     * 建出组来。</p>
     */
    @Test
    void groupRejectionIsPureNoOp() {
        PreviewItem a = item("a"), b = item("b");
        PreviewItem ghost = item("ghost");      // 从未加入本工作区
        WorkspaceModel m = workspaceWith(a, b);
        int depthBefore = m.undoDepth();

        assertNull(m.group(List.of()), "空列表不得建组");
        assertNull(m.group(null), "null 不得建组");
        assertNull(m.group(List.of(ghost)), "不在工作区内的条目不得建组");

        assertEquals(depthBefore, m.undoDepth(), "no-op 不得产生撤销记录");
        assertEquals(2, m.visibleCount());
        assertTopLevelIs(m, "拒绝路径后", a, b);
        assertNull(a.parentGroup);
    }

    // ==================== 6. 撤销栈深语义（撤销对象是最近一次操作） ====================

    /**
     * 撤销的语义是"<b>最近一次操作</b>"，不是"最早一次"。
     *
     * <p>用可见数区分"撤销建组"与"撤销移除"：前者不改可见数（3→3），
     * 后者恢复被移除项（2→3）。若栈序反了，第一条断言就会读到 2。</p>
     */
    @Test
    void undoActsOnMostRecentOperationAndDepthTracksTheStack() {
        PreviewItem a = item("a"), b = item("b"), c = item("c");
        WorkspaceModel m = workspaceWith(a, b, c);   // 深度 1

        m.group(List.of(a, b));                      // 深度 2（GROUP）
        m.removeItems(List.of(c));                   // 深度 3（REMOVE）
        assertEquals(2, m.visibleCount());

        assertTrue(m.undo(), "栈顶应是 REMOVE 记录");
        assertEquals(3, m.visibleCount(), "撤销移除应恢复可见");
        assertEquals(2, m.undoDepth(), "每撤销一步栈深应减一");

        assertTrue(m.undo(), "栈顶应是 GROUP 记录");
        assertTrue(m.groups().isEmpty(), "撤销建组应回到扁平结构");
        assertEquals(3, m.visibleCount(), "撤销建组不改变可见数");
        assertEquals(1, m.undoDepth());
    }

    // ==================== 7. clear() ====================

    /** {@code clear()} 必须清结构、清撤销栈、并把组号计数器一起复位（避免"新建会话"后组号续接）。 */
    @Test
    void clearResetsStructureUndoStackAndGroupNumbering() {
        PreviewItem a = item("a"), b = item("b");
        WorkspaceModel m = workspaceWith(a, b);
        GroupItem<PreviewItem> g = m.group(List.of(a, b));
        assertNotNull(g);
        assertEquals("组1", g.getName(), "首次建组应为 组1");
        assertTrue(m.undoDepth() > 0);

        m.clear();

        assertTrue(m.topLevel().isEmpty());
        assertTrue(m.allItems().isEmpty());
        assertTrue(m.groups().isEmpty());
        assertTrue(m.displayOrder().isEmpty());
        assertEquals(0, m.visibleCount());
        assertEquals(0, m.undoDepth());
        assertFalse(m.undo(), "清空后栈空，撤销应返回 false");

        // ⚠️ 实测的重要语义（接入生产时必须知道）：
        //  (1) clear() 只清模型内部状态，**不触碰条目对象**的 parentGroup —— 清空后旧条目的
        //      parentGroup 仍指向已脱离的旧组对象；
        //  (2) 因此"归属判定"不能只看 parentGroup，必须校验其所属组**仍在顶层**
        //      （WorkspaceModel.slotOf 即如此实现），于是"clear 后拿旧条目再建组"是纯 no-op。
        GroupItem<PreviewItem> afterClear = m.group(List.of(a, b));
        assertNull(afterClear, "clear 后未重新导入，旧条目不在数据序中 → 建组必须是无副作用的 null");
        assertTrue(m.topLevel().isEmpty());

        // 正确用法：重新导入（新对象）后再建组 → 组号计数器确实复位到 1
        PreviewItem fresh1 = item("fresh1"), fresh2 = item("fresh2");
        m.addItems(List.of(fresh1, fresh2));
        GroupItem<PreviewItem> again = m.group(List.of(fresh1, fresh2));
        assertNotNull(again, "clear + 重新导入后应可建组");
        assertEquals("组1", again.getName(), "clear 应把组号计数器复位到 1");
        assertSame(again, fresh1.parentGroup);
    }

    // ==================== 8. null 容错 ====================

    /** 三个批量入口对 {@code null} 必须是 no-op，不得 NPE（GUI 侧调用点常来自可能为空的集合）。 */
    @Test
    void nullInputsAreNoOpsAcrossMutationApis() {
        WorkspaceModel m = new WorkspaceModel();

        assertEquals(0, m.addItems(null));
        assertEquals(0, m.removeItems(null));
        assertTrue(m.topLevel().isEmpty());
        assertEquals(0, m.undoDepth(), "no-op 不得产生撤销记录");

        m.addItems(List.of(item("a")));
        int depth = m.undoDepth();
        assertEquals(0, m.removeItems(List.of()));
        assertEquals(depth, m.undoDepth());
    }

    // ==================== 9. 惰性销毁：不提前建快照 ====================

    /**
     * 已移除项必须<b>留在数据序里</b>，直到所属组的所有成员都不可见才销毁组。
     *
     * <p>若实现"移除即从列表删除"，撤销就无处恢复，且 {@code visibleCount} 会算错。</p>
     */
    @Test
    void removedItemsStayInDataOrderUntilTheirGroupBecomesEmpty() {
        PreviewItem a = item("a"), b = item("b"), c = item("c");
        WorkspaceModel m = workspaceWith(a, b, c);
        GroupItem<PreviewItem> g = m.group(List.of(a, b));   // 顶层：g + c
        assertNotNull(g);
        assertEquals(3, m.allItems().size());

        m.removeItems(List.of(a));

        assertTrue(a.removed);
        assertEquals(1, m.groups().size(), "只剩一个可见成员时组不得销毁");
        assertEquals(3, m.allItems().size(), "被移除项必须留在数据序");
        assertEquals(2, m.visibleCount(), "b + c");
        assertDisplayOrderMatchesStructure(m, removedPredicate());

        m.removeItems(List.of(b));

        assertTrue(m.groups().isEmpty(), "成员全被移除后组才销毁");
        assertEquals(1, m.visibleCount(), "只剩 c");
        // 组销毁时成员被移出模型（不留在顶层）—— 撤销靠 REMOVE 记录里的成员引用与快照恢复，
        // 因此 a/b 的 parentGroup 仍指向已销毁的组对象（与 GUI 现有实现一致）。
        assertTopLevelIs(m, "空组销毁后", c);
        assertEquals(List.of(a, b), g.getMembers(), "销毁的组对象仍持有成员引用（撤销所需）");
        assertDisplayOrderMatchesStructure(m, removedPredicate());

        // ★ 显式覆盖"顶层已移除项"这一分支：组被销毁后成员被移出顶层，
        //   再移除 c 就得到"顶层含已移除项"的结构 —— flatten 必须把它过滤掉。
        //   （早期版本只断言 displayOrder 与"同一个 flatten 的重算结果"一致，
        //    而两处都调用 flatten，参数错了两边一起错 → 断言恒真，形同虚设。）
        m.removeItems(List.of(c));
        List<Object> display = m.displayOrder();
        assertFalse(display.contains(c), "已移除的顶层条目不得出现在显示序");
        assertFalse(display.contains(a), "随组销毁移出顶层的已移除成员不得出现在显示序");
        assertTrue(display.isEmpty(), "三项均不可见时显示序应为空");
    }

    // ==================== 10. 查询视图语义（真实快照，而非内部视图） ====================

    /**
     * {@link WorkspaceModel#topLevel()} 返回的是<b>快照</b>而非内部列表。
     *
     * <p>这一条直接决定接入生产时的安全性：{@code MainController} 会把它投影进
     * {@code ObservableList}。若哪天改成返回实时视图，GUI 的写入会<b>绕过模型</b>，
     * 于是"两套状态"重新出现 —— 本断言就是那个漂移的报警器。</p>
     */
    @Test
    void topLevelReturnsSnapshotNotLiveView() {
        PreviewItem a = item("a"), b = item("b");
        WorkspaceModel m = workspaceWith(a, b);

        List<Object> first = m.topLevel();
        assertEquals(2, first.size());

        // 改动模型后，先前取到的快照必须不变（证明是快照）
        m.addItems(List.of(item("c")));
        assertEquals(2, first.size(), "topLevel() 应是快照，不得随后续变更而变");
        assertEquals(3, m.topLevel().size());

        // 快照必须不可变（返回 List.copyOf）→ 外部无法绕过模型改状态
        assertThrows(UnsupportedOperationException.class, () -> m.topLevel().add(item("z")),
                "topLevel() 的返回值必须不可变");
        assertEquals(3, m.topLevel().size());
    }

    // ==================== 11. 导入去重的身份语义 ====================

    /**
     * 重复导入（同一对象的批次）被身份跳过，但<b>撤销批次仍按原始入参记录</b>。
     *
     * <p>这条把"调用方不得把已有条目重复塞进 addItems"这一隐含契约写下来：
     * 因为撤销是按调用方给的整批记录执行的，重复入参会连带撤销掉本来不该动的条目。</p>
     */
    @Test
    void duplicateImportIsSkippedButUndoRestoresTheWholeGivenBatch() {
        PreviewItem a = item("a"), b = item("b");
        WorkspaceModel m = workspaceWith(a, b);

        assertEquals(0, m.addItems(List.of(a, b)), "两个都已存在，跳过");
        assertEquals(1, m.undoDepth(), "跳过时不产生新的撤销记录");
        assertTopLevelIs(m, "跳过重复导入后", a, b);

        // 单条新对象：只新增 1 条，但撤销记录携带的是整批（含已存在的 a）
        PreviewItem c = item("c");
        assertEquals(1, m.addItems(List.of(a, c)));
        assertEquals(3, m.visibleCount());

        assertTrue(m.undo());
        assertTopLevelIs(m, "撤销该导入批次后（批次内含已存在的 a → 被一并撤销）", b);
    }

    // ==================== 12. groups() / allItems() 的视图形状 ====================

    /** {@code groups()} 只返回顶层组（含组内成员关系），{@code allItems()} 展开后顺序与数据序一致。 */
    @Test
    void groupsAndAllItemsExposeFlattenedTopLevelView() {
        PreviewItem a = item("a"), b = item("b"), c = item("c"), d = item("d");
        WorkspaceModel m = workspaceWith(a, b, c, d);
        GroupItem<PreviewItem> g = m.group(List.of(a, b));
        assertNotNull(g);

        List<GroupItem<PreviewItem>> groups = m.groups();
        assertEquals(1, groups.size());
        assertSame(g, groups.get(0));

        List<PreviewItem> all = m.allItems();
        assertEquals(4, all.size());
        // 数据序：组（a,b）在前，随后 c、d
        assertSame(a, all.get(0));
        assertSame(b, all.get(1));
        assertSame(c, all.get(2));
        assertSame(d, all.get(3));

        // allItems() 是新建列表，调用方改动它不得影响模型
        List<PreviewItem> copy = new ArrayList<>(all);
        copy.clear();
        assertEquals(4, m.allItems().size(), "allItems() 的返回值应是独立副本");
    }
}
