package com.mhtextractor.app;

import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.GroupItem;
import com.mhtextractor.util.GroupStructure;
import com.mhtextractor.util.UndoManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 无头工作区模型（会话 18 · E4-4）—— 验收 V2「桩 UI 跑通**工作区**功能」的载体。
 *
 * <p><b>与 {@code MainController} 的关系</b>：GUI 的条目列表是 {@code ObservableList} 且被
 * {@code ListView} 直接绑定、{@code initialize} 还对其注册了 {@code ListChangeListener}
 * （见 {@code docs/handoff/H17.md} §六之九）。在剩余预算内把它整体迁出风险过高，
 * 因此本类**不是** GUI 状态的替代品，而是：</p>
 * <ul>
 *   <li>一个<b>可独立运行、可单测</b>的工作区语义实现（分组/拆分/移除/撤销/参数作用域），
 *       供无头调用方与"桩 UI"使用（这正是 V2 要证明的能力）；</li>
 *   <li>未来 GUI 若愿意，可订阅本模型的变更事件并把自己变成纯视图 ——
 *       这条路径**不需要**改本类，属可选的后续演进。</li>
 * </ul>
 *
 * <p><b>零 JavaFX</b>：仅依赖 {@code model} 与 {@code util}（两者均已零 JavaFX，由
 * {@code ArchitectureTest} 强制）。</p>
 *
 * <p><b>复用既有纯逻辑</b>：分组结构操作委托 {@link GroupStructure}，撤销栈委托
 * {@link UndoManager} —— 这两者分别有 23 / 14 条既有单测，本类不重复实现它们。</p>
 *
 * <p><b>语义来源</b>：所有操作语义对齐 GUI 的对应功能（F1 勾选、F2 隐藏式移除、F5 建组、
 * F6 拆分组、撤销栈深 35），但不含任何 UI 交互（确认框、动画、选中管理）。</p>
 */
public final class WorkspaceModel {

    /** 顶层顺序（含组条目）；被移除的条目仍留在其组/顶层中，由谓词过滤 */
    private final List<Object> topLevel;

    private final UndoManager<PreviewItem> undoManager = new UndoManager<>();

    private int lastGroupNo = 0;

    private static final Predicate<Object> REMOVED = o -> o instanceof PreviewItem it && it.removed;

    // ==================== 构造 ====================

    /** 无头构造：自持数据序列表（单测与桩 UI 使用）。 */
    public WorkspaceModel() {
        this(new ArrayList<>());
    }

    /**
     * 共享构造：由调用方提供数据序列表，本模型**在它上面直接操作**。
     *
     * <p><b>为什么这样设计</b>：GUI 的数据序是 {@code ObservableList}（被 {@code ListView} 的变更监听
     * 依赖），而本类不得触碰任何 UI 框架类型。把列表**作为 {@code java.util.List} 注入**，
     * 既让两侧共享同一份存储（不存在"两套状态"与投影时滞），又让本类字节码保持零 javafx ——
     * 这正是 {@code ArchitectureTest.businessPackagesHaveNoUiFrameworkDependency} 允许的形态
     * （它扫的是常量池，而 {@code List} 不是 UI 类型）。</p>
     *
     * <p><b>由此消除的风险</b>：接入生产时若采用"模型持一份、GUI 持一份 + 单向投影"，
     * 迁移期间任何未迁移的直写都会造成分叉。共享存储下，GUI 的直写与模型的写是同一份数据，
     * 因此可以逐操作迁移、每一步都自洽。</p>
     *
     * @param sharedTopLevel 由调用方持有的数据序列表（不得为 null）
     */
    public WorkspaceModel(List<Object> sharedTopLevel) {
        this.topLevel = Objects.requireNonNull(sharedTopLevel, "sharedTopLevel");
    }

    // ==================== 查询 ====================

    /** 显示序（展开的组后跟随其可见成员；已移除项被过滤）—— 委托 {@link GroupStructure#flatten} */
    public List<Object> displayOrder() {
        return GroupStructure.flatten(topLevel, REMOVED);
    }

    /** 顶层条目（含组），数据序 */
    public List<Object> topLevel() {
        return List.copyOf(topLevel);
    }

    /** 全部条目（含组内成员、含已移除），数据序 */
    public List<PreviewItem> allItems() {
        List<PreviewItem> out = new ArrayList<>();
        for (Object o : topLevel) {
            addItemsOf(o, out);
        }
        return out;
    }

    /** 可见（未被移除）条目数 */
    public int visibleCount() {
        return (int) allItems().stream().filter(i -> !i.removed).count();
    }

    /** 顶层组 */
    public List<GroupItem<PreviewItem>> groups() {
        List<GroupItem<PreviewItem>> out = new ArrayList<>();
        for (Object o : topLevel) {
            if (o instanceof GroupItem<?> g) {
                @SuppressWarnings("unchecked") GroupItem<PreviewItem> gg = (GroupItem<PreviewItem>) g;
                out.add(gg);
            }
        }
        return out;
    }

    /** 撤销栈深度 */
    public int undoDepth() {
        return undoManager.size();
    }

    /** 撤销栈是否为空（GUI 的撤销按钮可用性据此判定，取代控制器自有的第二条栈） */
    public boolean canUndo() {
        return !undoManager.isEmpty();
    }

    /**
     * 压入一条撤销记录（供 GUI 侧记录本模型尚未负责的类型，如参数类 / 拖拽类）。
     *
     * <p><b>为什么需要它</b>：撤销栈必须<b>唯一</b>，否则两套栈会分叉。因此栈由本类独占，
     * 但"如何应用"按类型分工：结构类由 {@link #undo()} 负责，参数 / 拖拽类由 GUI 负责
     * —— 后者需要缓存失效（{@code WorkspaceParamService}）与文案，仍属 GUI 职责。</p>
     */
    public void push(UndoManager.Record<PreviewItem> record) {
        undoManager.push(record);
    }

    /**
     * 弹出栈顶记录并交给调用方自行应用（<b>不</b>改动任何状态）。
     *
     * <p>为什么需要：{@link #undo()} 只应用结构类记录，遇到参数 / 拖拽类会把记录原样压回。
     * 若栈由本类独占而 GUI 又需要处理那些类型，就必须有一个"只弹出、不应用"的入口，
     * 否则未支持类型会永久卡在栈顶，把后面的结构撤销全部堵死。</p>
     *
     * @return 栈顶记录；栈空时返回 {@code null}
     */
    public UndoManager.Record<PreviewItem> popRecord() {
        return undoManager.isEmpty() ? null : undoManager.pop();
    }

    /** 清空撤销栈（<b>不动</b>工作区结构；供 GUI 的新会话 / 清空流程使用） */
    public void clearUndoStack() {
        undoManager.clear();
    }

    private void addItemsOf(Object o, List<PreviewItem> out) {
        if (o instanceof PreviewItem it) {
            out.add(it);
        } else if (o instanceof GroupItem<?> g) {
            for (Object m : g.getMembers()) {
                addItemsOf(m, out);
            }
        }
    }

    // ==================== 变更：导入 / 添加 ====================

    /**
     * 追加条目到顶层末尾（对应 GUI 的导入追加）。
     *
     * @return 实际新增的条目数（已存在同一对象则跳过）
     */
    public int addItems(List<PreviewItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (PreviewItem it : items) {
            if (it != null && !containsIdentity(it)) {
                topLevel.add(it);
                added++;
            }
        }
        if (added > 0) {
            // 一次导入压一条 IMPORT 撤销记录（与 GUI 一致：批量导入=单次撤销）
            undoManager.push(new UndoManager.Record<>(UndoManager.RecordType.IMPORT,
                    new ArrayList<>(items)));
        }
        return added;
    }

    private boolean containsIdentity(PreviewItem item) {
        for (PreviewItem existing : allItems()) {
            if (existing == item) return true;
        }
        return false;
    }

    // ==================== 变更：分组 / 拆分 ====================

    /**
     * 把给定条目合并为一个新组（F5）。
     *
     * <p>与 GUI 对齐的关键语义：<b>插入槽位 = 目标中最靠前的位置</b>；成员从原位置摘除；
     * 组名冲突自动唯一化；组号全局单调递增；入栈一条 GROUP 撤销记录（携带结构快照）。</p>
     *
     * @return 新建的组；输入不足 2 项时返回 null（不产生任何变更）
     */
    public GroupItem<PreviewItem> group(List<PreviewItem> members) {
        List<PreviewItem> targets = distinctNonNull(members);
        if (targets.size() < 2) {
            return null;
        }
        // 插入槽位 = 目标中最靠前的顶层位置
        int insertAt = firstTopLevelIndex(targets);
        if (insertAt < 0) {
            return null;
        }

        List<Object> snapshot = GroupStructure.snapshotStructure(topLevel);

        // 从原位置（顶层或其所在组）摘除
        for (PreviewItem t : targets) {
            removeFromItsParent(t);
            t.parentGroup = null;
            t.textItemParams = null;   // 入组清除条目级参数（GUI 语义：组内成员不持有独立参数）
            t.imageItemParams = null;
        }

        int groupNo = GroupStructure.nextGroupNo(lastGroupNo);
        lastGroupNo = groupNo;
        String name = GroupStructure.uniqueGroupName(defaultGroupName() + groupNo, allGroupNames());
        GroupItem<PreviewItem> g = new GroupItem<>(groupNo, name);
        g.getMembers().addAll(targets);
        for (PreviewItem t : targets) {
            t.parentGroup = g;
        }

        int at = Math.min(insertAt, topLevel.size());
        topLevel.add(at, g);

        undoManager.push(new UndoManager.Record<>(UndoManager.RecordType.GROUP,
                targets, g, at, List.of(), List.of(), snapshot));
        return g;
    }

    /**
     * 拆分组（F6）：组从顶层移除，成员按原组内顺序插回原位置。
     *
     * <p>与 GUI 对齐：拆分时把组参数**复制为成员条目级参数**（GUI 语义），并压一条 UNGROUP 记录。</p>
     *
     * @return 拆出的成员列表；组不在顶层时返回空列表且不产生变更
     */
    public List<PreviewItem> ungroup(GroupItem<PreviewItem> group) {
        if (group == null) return List.of();
        int idx = topLevel.indexOf(group);
        if (idx < 0) return List.of();

        List<Object> snapshot = GroupStructure.snapshotStructure(topLevel);
        // 组参数下发给成员（条目级），使拆分前后成员生效参数不变
        for (PreviewItem m : group.getMembers()) {
            m.parentGroup = null;
            m.textItemParams = group.getTextParamsCache();
            m.imageItemParams = group.getImageParamsCache();
        }
        List<PreviewItem> members = GroupStructure.dissolveGroup(topLevel, group);

        undoManager.push(new UndoManager.Record<>(UndoManager.RecordType.UNGROUP,
                members, group, idx, List.of(), List.of(), snapshot));
        return members;
    }

    // ==================== 变更：移除 ====================

    /**
     * 隐藏式移除（F2）：条目标记 {@code removed=true} 但不从列表删除；致空组自动销毁。
     *
     * @return 实际被移除的条目数
     */
    public int removeItems(List<PreviewItem> items) {
        List<PreviewItem> targets = distinctNonNull(items);
        if (targets.isEmpty()) {
            return 0;
        }
        List<Object> snapshot = GroupStructure.snapshotStructure(topLevel);
        for (PreviewItem t : targets) {
            t.removed = true;
            t.setSelected(false);
        }
        // 致空组自动销毁（降序记录原索引，撤销时按原序插回）
        List<GroupItem<PreviewItem>> destroyed = new ArrayList<>();
        List<Integer> destroyedIdx = new ArrayList<>();
        for (int i = topLevel.size() - 1; i >= 0; i--) {
            if (topLevel.get(i) instanceof GroupItem<?> raw) {
                @SuppressWarnings("unchecked") GroupItem<PreviewItem> g = (GroupItem<PreviewItem>) raw;
                if (GroupStructure.isEmptyGroup(g, m -> m.removed)) {
                    destroyed.add(g);
                    destroyedIdx.add(i);
                    topLevel.remove(i);
                }
            }
        }
        undoManager.push(new UndoManager.Record<>(UndoManager.RecordType.REMOVE,
                targets, null, -1, destroyed, destroyedIdx, snapshot));
        return targets.size();
    }

    // ==================== 变更：撤销 ====================

    /**
     * 撤销一步。
     *
     * <p><b>范围说明（诚实标注）</b>：本类只实现与"列表结构"相关的撤销类型
     * （{@code IMPORT / GROUP / UNGROUP / REMOVE}），这四类覆盖了"桩 UI 跑通工作区功能"所需。
     * 参数类撤销（{@code PARAM_SET / CLEAR_PARAMS / APPLY_GLOBAL}）与
     * {@code REORDER / MERGE_GROUPS} 尚未纳入 —— 它们需要参数快照与落点解析，
     * 属后续步骤（见 {@code docs/handoff/H17.md}）。</p>
     *
     * @return true = 确实撤销了一步；false = 栈空或该类型尚未支持（不产生任何变更）
     */
    public boolean undo() {
        if (undoManager.isEmpty()) {
            return false;
        }
        // UndoManager 只提供 pop()（无 peek）。故先弹出，遇到尚未支持的类型再原样压回 ——
        // 这样"不支持"不会静默改变状态（若直接忽略，用户会看到"撤销了但什么都没变"）。
        UndoManager.Record<PreviewItem> record = undoManager.pop();
        switch (record.type()) {
            case REMOVE -> undoRemove(record);
            case GROUP -> undoGroup(record);
            case UNGROUP -> undoUngroup(record);
            case IMPORT -> undoImport(record);
            default -> {
                undoManager.push(record);   // 原样压回，保持栈序不变
                return false;
            }
        }
        return true;
    }

    private void undoRemove(UndoManager.Record<PreviewItem> record) {
        for (PreviewItem t : record.items()) {
            t.removed = false;
        }
        // 被销毁的空组按原索引插回（降序记录 → 升序插回）
        List<GroupItem<PreviewItem>> destroyed = record.destroyedGroups();
        List<Integer> idx = record.destroyedIndexes();
        if (destroyed != null && idx != null) {
            for (int i = destroyed.size() - 1; i >= 0; i--) {
                int at = Math.max(0, Math.min(idx.get(i), topLevel.size()));
                topLevel.add(at, destroyed.get(i));
            }
        }
    }

    private void undoGroup(UndoManager.Record<PreviewItem> record) {
        // 把成员从所在组/顶层的**两个**容器里都摘除，二者缺一不可：
        //  ① 从 parentGroup 指向的组里移除；
        //  ② 从 topLevel 里移除。
        // 只做 ① 不够：GroupStructure.restoreStructure 会把组内成员清空并把 parentGroup 置 null，
        // 此后成员虽不在任何组里、却仍留在 topLevel（见 diag：撤销后 topLevel=[a,b]，g.members=[]）
        // —— 于是下一次建组时 removeFromItsParent 因 parentGroup==null 只从 topLevel 删一次，
        // 而快照恢复又把它们放回顶层，最终产生重复项。
        if (record.group() != null) {
            record.group().getMembers().clear();
        }
        for (PreviewItem t : record.items()) {
            topLevel.remove(t);
        }
        if (record.structureBefore() != null) {
            GroupStructure.restoreStructure(topLevel, record.structureBefore());
        }
        for (PreviewItem t : record.items()) {
            t.parentGroup = record.group();
        }
    }

    private void undoUngroup(UndoManager.Record<PreviewItem> record) {
        if (record.structureBefore() != null) {
            GroupStructure.restoreStructure(topLevel, record.structureBefore());
        }
        for (PreviewItem m : record.items()) {
            m.parentGroup = record.group();
            m.textItemParams = null;   // 撤销拆分：清除拆分时下发的条目级参数
            m.imageItemParams = null;
        }
    }

    private void undoImport(UndoManager.Record<PreviewItem> record) {
        for (PreviewItem t : record.items()) {
            removeFromItsParent(t);
            t.parentGroup = null;
        }
        // 撤销导入可能致空组：直接销毁
        for (int i = topLevel.size() - 1; i >= 0; i--) {
            if (topLevel.get(i) instanceof GroupItem<?> raw) {
                @SuppressWarnings("unchecked") GroupItem<PreviewItem> g = (GroupItem<PreviewItem>) raw;
                if (GroupStructure.isEmptyGroup(g, m -> m.removed)) {
                    topLevel.remove(i);
                }
            }
        }
    }

    /** 清空全部状态（供测试与"新建会话"用） */
    public void clear() {
        topLevel.clear();
        undoManager.clear();
        lastGroupNo = 0;
    }

    // ==================== 内部工具 ====================

    /** 默认组名前缀（"组"，与 zh bundle 的 core.groupDefault 一致；en 为 "Group"） */
    private String defaultGroupName() {
        return "组";
    }

    private int firstTopLevelIndex(List<PreviewItem> targets) {
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < topLevel.size(); i++) {
            Object o = topLevel.get(i);
            if (targets.contains(o)) {
                best = Math.min(best, i);
            } else if (o instanceof GroupItem<?> g && g.getMembers().stream().anyMatch(targets::contains)) {
                best = Math.min(best, i);
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    /**
     * 把条目从它当前所在的容器里摘除。
     *
     * <p><b>为什么不能只信 {@code parentGroup}</b>：{@link GroupStructure#restoreStructure} 会把组内成员清空，
     * 但成员的 {@code parentGroup} 也要由调用方重置。实测出现过
     * "条目仍留在某个组的 members 里、而 parentGroup 已是 null" 的状态
     * （撤销建组后又重新建组的序列）—— 此时只按 {@code parentGroup} 查找就会漏删，
     * 导致同一对象既在组内又在顶层 → 顶层出现重复项。
     * 因此这里<b>同时</b>做两件事：从 {@code parentGroup} 指向的组移除 + 从顶层移除，
     * 并兜底扫描所有组，把该条目从任何仍持有它的组里摘掉。</p>
     */
    private void removeFromItsParent(PreviewItem t) {
        if (t.parentGroup != null) {
            t.parentGroup.getMembers().remove(t);
        }
        topLevel.remove(t);
        // 兜底：任何仍持有该条目的组都要摘除（防止状态不一致时产生重复项）
        for (Object o : topLevel) {
            if (o instanceof GroupItem<?> raw) {
                @SuppressWarnings("unchecked") GroupItem<PreviewItem> g = (GroupItem<PreviewItem>) raw;
                g.getMembers().remove(t);
            }
        }
    }

    private Set<String> allGroupNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Object o : topLevel) {
            if (o instanceof GroupItem<?> g) {
                names.add(g.getName());
            }
        }
        return names;
    }

    private static List<PreviewItem> distinctNonNull(List<PreviewItem> in) {
        List<PreviewItem> out = new ArrayList<>();
        if (in != null) {
            for (PreviewItem i : in) {
                if (i != null && !out.contains(i)) out.add(i);
            }
        }
        return out;
    }

    /**
     * 测试支撑：压入一条"本模型尚未支持"的撤销记录，用于验证
     * {@link #undo()} 对未支持类型的行为（返回 false 且原样压回）。
     *
     * <p>仅包级可见，生产路径不应调用。</p>
     */
    void pushUnsupportedForTest() {
        undoManager.push(new UndoManager.Record<>(UndoManager.RecordType.PARAM_SET, List.of()));
    }
}
