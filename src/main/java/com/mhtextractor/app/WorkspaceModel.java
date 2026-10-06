package com.mhtextractor.app;

import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.DropResolver;
import com.mhtextractor.util.GroupStructure;
import com.mhtextractor.util.UndoManager;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 无头工作区模型（会话 18 · E4-4）—— 验收 V2「桩 UI 跑通**工作区**功能」的载体。
 *
 * <p><b>与 {@code MainController} 的关系（Q4 / P3 起为"所有者—投影"）</b>：本类是工作区业务数据的
 * <b>唯一所有者</b>（{@code topLevel} 为私有存储）；GUI 的列表是它的<b>投影</b>：</p>
 * <ul>
 *   <li>GUI 的显示序由 {@code gui.WorkspaceFxAdapter} 经 {@link #displayOrder()} 投影而来；</li>
 *   <li>GUI 侧保留的同名 {@code items} 入口是 {@link #topLevelView()} 的只读包装
 *       （写操作会抛 {@code UnsupportedOperationException}；唯一例外见该类注释），
 *       于是"数据序被谁写"这件事在类型层面就已定死；</li>
 *   <li>每条<b>成功</b>命令经 {@link #addListener} 发布一次 {@link WorkspaceChange}
 *       （失败 / no-op 命令不发布）。</li>
 * </ul>
 *
 * <p><b>本类仍是一个可独立运行、可单测的工作区语义实现</b>（分组/拆分/移除/撤销/参数作用域），
 * 供无头调用方与"桩 UI"使用（V2 的能力证明）；所有 UI 交互（确认框、动画、选中管理）都不在本类。</p>
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
@Slf4j
public final class WorkspaceModel {

    /** 顶层顺序（含组条目）；被移除的条目仍留在其组/顶层中，由谓词过滤 —— <b>本类私有存储</b>（Q4/P3） */
    private final List<Object> topLevel = new ArrayList<>();

    /**
     * {@link #topLevel} 的**只读实时视图**（零拷贝，缓存包装器）。
     *
     * <p><b>与 {@link #topLevel()}（不可变快照）的分工</b>：快照给"取一份就固定"的调用方；
     * 本视图给"每次读都必须看到当前数据序、且绝不许写"的投影层（GUI 的 {@code items} 入口）——
     * 用快照替代会让 GUI 在同一条命令的前后混读时读到<b>陈旧结构</b>，
     * 而用可变列表替代就等于把写入权交回 GUI（那正是 Q4/P3 要拆掉的东西）。</p>
     */
    private final List<Object> topLevelView = Collections.unmodifiableList(topLevel);

    /** 变更订阅者（无事件总线 / 无 DI 容器：一个列表足够，见执行约束第 4 条） */
    private final List<Consumer<WorkspaceChange>> listeners = new ArrayList<>();

    private final UndoManager<PreviewItem> undoManager = new UndoManager<>();

    private int lastGroupNo = 0;

    /** 上一次 {@link #applyUndo} 中受影响的条目（供 {@link #applyUndoAffected} 查询；见其 javadoc 的状态契约） */
    private List<PreviewItem> lastUndoAffected = List.of();

    private static final Predicate<Object> REMOVED = o -> o instanceof PreviewItem it && it.removed;

    // ==================== 构造 / 订阅 ====================

    /**
     * 唯一构造：模型**自持**数据序存储。
     *
     * <p><b>为什么删掉了 {@code WorkspaceModel(List<Object> sharedTopLevel)}</b>（Q4 / P3 的核心动作）：
     * 共享构造让"模型拥有的结构"与"GUI 渲染的列表"是同一个可变对象，于是 GUI 里任何一处
     * {@code items.add(...)} 都会绕过撤销记录与结构维护 —— 不报错、只<b>静默分叉</b>。
     * 需要"带初始内容的模型"的调用方改用复制型命令 {@link #importAll(List)}。</p>
     */
    public WorkspaceModel() {
    }

    /**
     * 订阅模型变更（每个成功命令回调一次）。
     *
     * <p>回调在<b>命令调用线程</b>上同步执行（生产路径即 FX 线程）；允许在回调里再读写模型
     * —— 遍历用的是 {@code List.copyOf} 的订阅者副本，回调内增删订阅者不会抛
     * {@code ConcurrentModificationException}。</p>
     */
    public void addListener(Consumer<WorkspaceChange> listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /** 退订（与 {@link #addListener} 配对；未订阅过是 no-op） */
    public void removeListener(Consumer<WorkspaceChange> listener) {
        listeners.remove(listener);
    }

    /** 发布一次变更（只在命令**确实改变了状态**之后调用；订阅者异常向上传播，不静默吞掉） */
    private void publish(WorkspaceChange.Kind kind, List<PreviewItem> affected) {
        if (listeners.isEmpty()) {
            return;
        }
        WorkspaceChange change = new WorkspaceChange(kind, affected);
        for (Consumer<WorkspaceChange> listener : List.copyOf(listeners)) {
            listener.accept(change);
        }
    }

    // ==================== 查询 ====================

    /**
     * 显示序（展开的组后跟随其可见成员；已移除项被过滤）—— 委托 {@link GroupStructure#flatten}。
     *
     * <p>返回<b>不可变快照</b>（Q4/P3 起：{@code GroupStructure.flatten} 本身给的是新建可变列表，
     * 这里再包一层不可变，使"查询只返回不可变快照"对三个查询
     * ——{@link #topLevel()} / {@link #allItems()} / {@link #displayOrder()}—— 口径一致）。</p>
     */
    public List<Object> displayOrder() {
        return List.copyOf(GroupStructure.flatten(topLevel, REMOVED));
    }

    /** 顶层条目（含组），数据序 —— <b>不可变快照</b>（调用方改动它不影响模型） */
    public List<Object> topLevel() {
        return List.copyOf(topLevel);
    }

    /**
     * 顶层条目的**只读实时视图**（零拷贝）：读操作直达内部存储；任何写操作抛
     * {@code UnsupportedOperationException}（{@code Collections.unmodifiableList} 保证）。
     *
     * <p>用途只有一个：GUI 侧的 {@code items} 入口（{@code WorkspaceFxAdapter.dataOrder()}）需要
     * "每次读都是当前数据序"，否则同一条命令前后的混读会看到陈旧结构。见字段 javadoc 的分工说明。</p>
     */
    public List<Object> topLevelView() {
        return topLevelView;
    }

    /** 全部条目（含组内成员、含已移除），数据序 —— <b>不可变快照</b> */
    public List<PreviewItem> allItems() {
        List<PreviewItem> out = new ArrayList<>();
        for (Object o : topLevel) {
            addItemsOf(o, out);
        }
        return List.copyOf(out);
    }

    /** 可见（未被移除）条目数 */
    public int visibleCount() {
        return (int) allItems().stream().filter(i -> !i.removed).count();
    }

    /** 顶层组 —— <b>不可变快照</b> */
    public List<GroupItem<PreviewItem>> groups() {
        List<GroupItem<PreviewItem>> out = new ArrayList<>();
        for (Object o : topLevel) {
            if (o instanceof GroupItem<?> g) {
                @SuppressWarnings("unchecked") GroupItem<PreviewItem> gg = (GroupItem<PreviewItem>) g;
                out.add(gg);
            }
        }
        return List.copyOf(out);
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
     * <p>成功（{@code added > 0}）时发布一次 {@link WorkspaceChange.Kind#ITEMS_ADDED}，
     * 载荷是<b>实际新增</b>的条目（不是入参整批 —— 入参里被身份跳过的那些没有进入数据序）；
     * 全部被跳过时既不留撤销记录、也不发布变更。</p>
     *
     * @return 实际新增的条目数（已存在同一对象则跳过）
     */
    public int addItems(List<PreviewItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        List<PreviewItem> addedItems = new ArrayList<>();
        for (PreviewItem it : items) {
            if (it != null && !containsIdentity(it)) {
                topLevel.add(it);
                addedItems.add(it);
            }
        }
        if (!addedItems.isEmpty()) {
            // 一次导入压一条 IMPORT 撤销记录（与 GUI 一致：批量导入=单次撤销）
            undoManager.push(new UndoManager.Record<>(UndoManager.RecordType.IMPORT,
                    new ArrayList<>(items)));
            publish(WorkspaceChange.Kind.ITEMS_ADDED, addedItems);
        }
        return addedItems.size();
    }

    /**
     * 复制型初始导入：把给定顶层条目**逐个接进本模型自己的存储**（不与调用方共享列表实例）。
     *
     * <p>存在的理由只有一个：它取代了 Q4/P3 删掉的
     * {@code WorkspaceModel(List<Object> sharedTopLevel)} —— 需要"构造后带初始内容"的调用方
     * 必须走这条<b>复制</b>路径，共享实例这条路已在类型层面关闭。</p>
     *
     * <p>语义与 {@link #appendItems} / {@link #appendGroup} 一致（不去重、不压撤销记录），
     * 只是按元素类型分派；末尾发布<b>一次</b> {@link WorkspaceChange.Kind#ITEMS_ADDED}
     * （载荷 = 新增的 {@link PreviewItem}；{@code GroupItem} 不是条目，只随 {@code topLevel} 进入数据序）。</p>
     */
    public void importAll(List<Object> initialItems) {
        if (initialItems == null || initialItems.isEmpty()) {
            return;
        }
        List<PreviewItem> added = new ArrayList<>();
        for (Object o : initialItems) {
            if (o instanceof PreviewItem it) {
                if (!containsIdentity(it)) {
                    topLevel.add(it);
                    added.add(it);
                }
            } else if (o instanceof GroupItem<?> raw) {
                GroupItem<PreviewItem> g = GroupStructure.asPreviewGroup(raw);
                if (!topLevel.contains(g)) {
                    topLevel.add(g);
                }
            }
        }
        if (!added.isEmpty()) {
            publish(WorkspaceChange.Kind.ITEMS_ADDED, added);
        }
    }

    private boolean containsIdentity(PreviewItem item) {
        for (PreviewItem existing : allItems()) {
            if (existing == item) return true;
        }
        return false;
    }

    // ==================== 变更：分组 / 拆分 ====================

    /**
     * 便捷重载：默认组名前缀为 zh 的"组"。
     *
     * <p>供无头调用方与单测使用；<b>GUI 一律用下面带前缀的重载</b>并传入
     * {@code I18n.get("core.groupDefault")} —— 本层不产出面向用户的文案
     * （与 {@code SourceViewPage} 页头文案由调用方传入同一处理）。</p>
     */
    public GroupItem<PreviewItem> group(List<PreviewItem> members) {
        return group(members, defaultGroupName());
    }

    /**
     * 把给定条目合并为一个新组（F5）。
     *
     * <p><b>⚠️ 本方法的语义在"接入生产"阶段被逐行核对并修正过</b>。修正前它与
     * {@code MainController.onGroupClicked}（生产实现）有 <b>3 处</b>不一致，若直接接入会造成
     * "用户可见的顺序/命名变化"：</p>
     * <ol>
     *   <li><b>单项也可建组</b>（R9）—— 原实现要求 ≥2 项，与生产的"勾选集合为空时退化为当前选中项"不符；</li>
     *   <li><b>插入槽位</b> —— 原实现只取"目标（或其所属组）在顶层的最靠前索引"；
     *       生产取"最靠前的<b>落点</b>"，落点定义为 {@code 目标在顶层 → 其自身索引；
     *       目标在组内 → 其所属组索引 + 1}，且每摘除一个位于槽位之前的条目槽位随之左移。
     *       两者在"跨组重建组"时给出不同顺序（实测：生产 [a, g, g2]，原实现 [a, g2, g]）；</li>
     *   <li><b>摘除成员后致空的组要自动销毁</b>，销毁位置同样参与槽位左移 —— 原实现不做，
     *       会留下空组。</li>
     * </ol>
     *
     * <p>其余语义与原实现一致并保留：成员从原位置摘除、入组清除条目级参数、
     * 组名唯一化（R7）、组号全局单调递增（{@link #nextGroupNo()}）、入栈一条 GROUP 撤销记录。</p>
     *
     * @param defaultNamePrefix 默认组名前缀（GUI 传本地化文案；无头调用方传 "组"）
     * @return 新建的组；目标为空或均不在本工作区时返回 {@code null}（不产生任何变更）
     */
    public GroupItem<PreviewItem> group(List<PreviewItem> members, String defaultNamePrefix) {
        List<PreviewItem> targets = distinctNonNull(members);
        if (targets.isEmpty()) {
            return null;
        }
        // 插入槽位 = 所有目标中最靠前的落点（组内成员的落点在其所属组之后）
        int insertIdx = Integer.MAX_VALUE;
        for (PreviewItem t : targets) {
            int at = slotOf(t);
            if (at >= 0) {
                insertIdx = Math.min(insertIdx, at);
            }
        }
        if (insertIdx == Integer.MAX_VALUE) {
            return null;   // 目标均不在本工作区 → 纯 no-op
        }

        List<Object> snapshot = GroupStructure.snapshotStructure(topLevel);

        // 从原位置（顶层或其所在组）摘除；致空组自动销毁；槽位随摘除左移
        for (PreviewItem t : targets) {
            GroupItem<PreviewItem> pg = t.parentGroup;
            if (pg != null && topLevel.indexOf(pg) >= 0) {
                pg.getMembers().remove(t);
                if (GroupStructure.isEmptyGroup(pg, m -> m.removed)) {
                    int i = topLevel.indexOf(pg);
                    topLevel.remove(pg);
                    if (i >= 0 && i < insertIdx) insertIdx--;
                    log.info(I18n.msg("log.workspacemodel.01", pg.getName()));
                }
            } else {
                int i = topLevel.indexOf(t);
                topLevel.remove(t);
                if (i >= 0 && i < insertIdx) insertIdx--;
            }
            t.parentGroup = null;
            t.textItemParams = null;   // 入组清除条目级参数（GUI 语义：组内成员不持有独立参数）
            t.imageItemParams = null;
        }

        int groupNo = nextGroupNo();
        String name = GroupStructure.uniqueGroupName(defaultNamePrefix + groupNo, groupNames());
        GroupItem<PreviewItem> g = new GroupItem<>(groupNo, name);
        g.setDefaultNamed(true);   // 手工建组 = 「未命名组」：提取前拦截标记的唯一来源（步骤 3 · 功能一）
        g.getMembers().addAll(targets);
        for (PreviewItem t : targets) {
            t.parentGroup = g;
        }

        int at = Math.max(0, Math.min(insertIdx, topLevel.size()));
        topLevel.add(at, g);

        undoManager.push(new UndoManager.Record<>(UndoManager.RecordType.GROUP,
                targets, g, at, List.of(), List.of(), snapshot));
        publish(WorkspaceChange.Kind.GROUPED, targets);
        return g;
    }

    /**
     * 取下一个全局组号（单调递增，不复用已销毁组的编号）。
     *
     * <p><b>为什么公开</b>：自动建组（导入时按文件夹建组）走的是控制器自己的构造路径，
     * 但也必须与手工建组<b>共用同一个计数器</b>，否则两条路径会各自 +1、产生重号。
     * 计数器由本类独占后，控制器不再持有 {@code groupNoCounter}。</p>
     */
    public int nextGroupNo() {
        lastGroupNo = GroupStructure.nextGroupNo(lastGroupNo);
        return lastGroupNo;
    }

    /**
     * 把一个既有组追加到顶层末尾（自动建组用）。
     *
     * <p><b>不产生撤销记录</b>：导入的撤销记录由调用方按"整批一次"的粒度压入
     * （一次导入可能跨多个批次，逐批压栈会破坏"一次撤销撤整批"的既有语义）。</p>
     *
     * <p>确实追加时发布一次 {@link WorkspaceChange.Kind#GROUPED}（语义即"数据序里多了一个组"），
     * 载荷是该组<b>操作时已有的</b>成员 —— 自动建组的新成员只存在于组内、从不出现在数据序顶层，
     * 这正是 GUI 侧"条目进了数据序顶层才挂勾选监听"的既有语义。</p>
     */
    public void appendGroup(GroupItem<PreviewItem> group) {
        if (group == null) {
            return;
        }
        for (Object o : topLevel) {
            if (o == group) return;
        }
        topLevel.add(group);
        List<PreviewItem> members = new ArrayList<>();
        for (Object m : group.getMembers()) {
            if (m instanceof PreviewItem it) {
                members.add(it);
            }
        }
        publish(WorkspaceChange.Kind.GROUPED, members);
    }

    /**
     * 把若干条目追加到顶层末尾（平铺导入用）。
     *
     * <p><b>与 {@link #addItems} 的区别（两个方法必须分清）</b>：</p>
     * <ul>
     *   <li>{@link #addItems} 是<b>语义操作</b>：按身份去重，并压一条 IMPORT 撤销记录
     *       —— 供无头调用方与桩 UI 使用；</li>
     *   <li>本方法是<b>结构原语</b>：只把给定条目接到数据序末尾，不去重、不压记录
     *       —— 供导入路径使用。导入路径的去重已由 {@code IncrementalImport} 完成，
     *       而撤销记录必须保持"一次用户导入 = 一条"的粒度（跨批次先累积、最后压一条），
     *       逐批压栈会让用户要按 N 次才能撤掉一次导入。</li>
     * </ul>
     */
    public void appendItems(List<PreviewItem> items) {
        if (items == null) {
            return;
        }
        List<PreviewItem> added = new ArrayList<>();
        for (PreviewItem it : items) {
            if (it != null) {
                topLevel.add(it);
                added.add(it);
            }
        }
        if (!added.isEmpty()) {
            publish(WorkspaceChange.Kind.ITEMS_ADDED, added);
        }
    }

    /**
     * 拆分组（F6）：组从顶层移除，成员按原组内顺序插回原位置。
     *
     * <p>与 GUI 对齐：拆分时把组参数**复制为成员条目级参数**（GUI 语义），并压一条 UNGROUP 记录。
     * 成功拆出时发布一次 {@link WorkspaceChange.Kind#UNGROUPED}（载荷 = 拆出的成员，它们回到了数据序顶层）。</p>
     *
     * @return 拆出的成员列表；组不在顶层时返回空列表且不产生变更（也不发布）
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
        publish(WorkspaceChange.Kind.UNGROUPED, members);
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
                    log.info(I18n.msg("log.workspacemodel.01", g.getName()));
                }
            }
        }
        undoManager.push(new UndoManager.Record<>(UndoManager.RecordType.REMOVE,
                targets, null, -1, destroyed, destroyedIdx, snapshot));
        publish(WorkspaceChange.Kind.ITEMS_REMOVED, targets);
        return targets.size();
    }

    /**
     * 整组移除（D7）：组内成员全部标记 {@code removed}，组条目从顶层摘除但不销毁
     * （组对象连同组名与组参数保留在撤销记录里，可整体恢复）。
     *
     * <p>撤销记录沿用与 {@link #removeItems} 相同的形状：把"被摘除的组 + 其原顶层索引"
     * 放进 {@code destroyedGroups/destroyedIndexes}，于是 GUI 侧 {@code REMOVE / REMOVE_GROUP}
     * 两个分支可以共用同一段恢复代码。</p>
     *
     * @return 受影响的成员数；组不在顶层时返回 0 且不产生任何变更
     */
    public int removeGroup(GroupItem<PreviewItem> group) {
        if (group == null) {
            return 0;
        }
        int idx = topLevel.indexOf(group);
        if (idx < 0) {
            return 0;
        }
        List<Object> snapshot = GroupStructure.snapshotStructure(topLevel);
        List<PreviewItem> members = distinctNonNull(new ArrayList<>(group.getMembers()));
        for (PreviewItem m : members) {
            m.removed = true;
            m.setSelected(false);
        }
        topLevel.remove(group);
        undoManager.push(new UndoManager.Record<>(UndoManager.RecordType.REMOVE_GROUP,
                members, group, idx, List.of(group), List.of(idx), snapshot));
        publish(WorkspaceChange.Kind.GROUP_REMOVED, members);
        return members.size();
    }

    // ==================== 变更：拖拽落点（REORDER / MERGE_GROUPS） ====================

    /**
     * 一次拖放执行的结构结果。
     *
     * <p><b>为什么是"结构结果"而不是"日志载荷"</b>：{@code app} 层不产出面向用户的文案
     * （见类 javadoc 的取舍；{@code businessLayerI18nUsageDoesNotGrow} 也只约束非日志引用）。
     * 因此这里只回传<b>模型侧事实</b>——谁变了、压的是哪类记录、合并涉及哪个组——由调用方
     * （GUI）决定怎么记日志、怎么清缓存、怎么重解析。</p>
     *
     * <p><b>字段顺序即调用顺序</b>：{@code affectedBefore} 的父组指针必须在
     * {@code DropResolver.apply} <b>之前</b>采集，故由本记录一并带出，调用方不得自行重采。</p>
     *
     * <p><b>不含"哪些组被拖拽致空销毁"</b>：那是 {@code DropResolver} 内部（R4）的结构效果，
     * 且原实现与新实现下都<b>不打日志</b>；本记录若再回传一份，就成了对同一语义的第二处（事后反推的）
     * 实现 —— 违反"同一语义全仓库只有一个实现点"。</p>
     *
     * @param changed        数据序是否真的发生了结构变化（原位 no-op 为 false，此时<b>不</b>压撤销记录）
     * @param affected       拖拽中父组可能变化的条目（原 {@code MainController.parentChangeTargets(op)} 的语义）
     * @param type           {@link UndoManager.RecordType#REORDER} 或 {@link UndoManager.RecordType#MERGE_GROUPS}
     * @param affectedBefore 与 {@code affected} 逐一对应的<b>操作前</b>父组指针（同一份 IdentityHashMap 语义）
     * @param merged        合并语义下被拖（销毁）的组；非合并为 {@code null}
     * @param mergedMembers 合并语义下被拖组的成员副本（操作前的组内顺序）；非合并为空列表
     * @param mergedIndex   合并语义下被拖组操作前的顶层索引；非合并为 -1
     * @param mergeTarget   合并语义下的目标组；非合并为 {@code null}（仅用于调用方产
     *                      {@code log.maincontroller.42} 的本地化文案）
     */
    public record DropResult(boolean changed,
                             List<PreviewItem> affected,
                             UndoManager.RecordType type,
                             Map<PreviewItem, GroupItem<PreviewItem>> affectedBefore,
                             GroupItem<PreviewItem> merged,
                             List<PreviewItem> mergedMembers,
                             int mergedIndex,
                             GroupItem<PreviewItem> mergeTarget) {
    }

    /**
     * 执行一次**已解析**的拖放操作（{@link DropResolver.Operation}）：整包收进模型，
     * 内部完成"快照 → 应用 → 重建父组指针 → 压撤销记录"。
     *
     * <p><b>为什么必须收进来</b>：{@code DropResolver.apply(topLevel, ...)} 是**间接写** ——
     * 它与 GUI 的直写一样绕过模型的撤销记录与结构维护，不报错、只静默分叉。
     * 本方法把"共享数据序的唯一写入者"这一性质补全到拖拽路径
     * （{@code ArchitectureTest.guiDoesNotMutateDataOrderDirectly} 的间接写处数因此归零）。</p>
     *
     * <p><b>采集顺序即语义（本方法最容易写错的地方）</b>：结构快照、受影响条目、各条目<b>操作前</b>的
     * 父组指针、以及合并载荷（被拖组 / 其顶层索引 / 其成员副本）—— 五者都必须在
     * {@link DropResolver#apply} <b>之前</b>采集。顺序错了不会报错，只会把<b>操作后</b>的状态
     * 记成"操作前"，使撤销一步之后回到错误的结构。</p>
     *
     * <p><b>落点解析不在本类</b>：{@link DropResolver#resolve} 依赖"目标行在显示序中的位置"等
     * GUI 概念，仍留在调用方；本类只负责"把已解析的操作落到数据序上"。
     * {@link DropResolver#apply} 的签名与语义<b>不变</b>（{@code DropResolverTest} 的 35 条断言依赖它）。</p>
     *
     * @param op 已解析的落点操作（{@code null} 时返回"未变更"的空结果，不触碰任何状态）
     * @return 结构结果；{@code changed == false} 时数据序与撤销栈均未被改动
     */
    @SuppressWarnings("unchecked")
    public DropResult applyResolvedDrop(DropResolver.Operation op) {
        if (op == null) {
            return new DropResult(false, List.of(), UndoManager.RecordType.REORDER,
                    Map.of(), null, List.of(), -1, null);
        }
        // ① 快照与合并载荷须在数据序修改前采集
        List<Object> before = GroupStructure.snapshotStructure(topLevel);
        List<PreviewItem> affected = collectAffected(op);
        Map<PreviewItem, GroupItem<PreviewItem>> prevParents = new IdentityHashMap<>();
        for (PreviewItem it : affected) {
            prevParents.put(it, it.parentGroup);
        }
        boolean merge = op instanceof DropResolver.MergeInto;
        UndoManager.RecordType type = merge
                ? UndoManager.RecordType.MERGE_GROUPS : UndoManager.RecordType.REORDER;
        GroupItem<PreviewItem> merged = null;
        GroupItem<PreviewItem> mergeTarget = null;
        int mergedIdx = -1;
        List<PreviewItem> mergedMembers = List.of();
        if (merge) {
            merged = (GroupItem<PreviewItem>) ((DropResolver.MergeInto) op).dragged();
            mergeTarget = (GroupItem<PreviewItem>) ((DropResolver.MergeInto) op).target();
            mergedIdx = topLevel.indexOf(merged);
            mergedMembers = new ArrayList<>(merged.getMembers());
        }
        // ② 落点生效
        boolean changed = DropResolver.apply(topLevel, op, REMOVED);
        // ③ 父组指针以数据序为准统一重建（撤销恢复也走这里，不留第二份实现）
        rebuildParentPointers();
        // ④ 一次拖放会话 = 一条撤销记录（载荷与迁移前逐字段一致）
        //    ⚠️ 拖拽致空组（R4）的销毁由 DropResolver.apply 内部完成，且在原实现与新实现下都是
        //    **静默**的（只改结构、不打日志）—— 因此本方法不回传"哪些组被销毁"，调用方也无日志可打。
        //    log.workspacemodel.01 只属于 group(...) / removeItems(...) 两条路径，本步未触碰。
        if (changed) {
            if (merge) {
                undoManager.push(new UndoManager.Record<>(type, mergedMembers, merged,
                        mergedIdx, List.of(), List.of(), before));
            } else {
                undoManager.push(new UndoManager.Record<>(type,
                        op instanceof DropResolver.InsertTop it && it.item() instanceof PreviewItem p
                                ? List.of(p)
                                : op instanceof DropResolver.InsertInto ii && ii.item() instanceof PreviewItem p2
                                        ? List.of(p2)
                                        : List.of(),
                        null, -1, List.of(), List.of(), before));
            }
            publish(merge ? WorkspaceChange.Kind.MERGED : WorkspaceChange.Kind.REORDERED, affected);
        }
        return new DropResult(changed, affected, type, prevParents,
                merged, mergedMembers, mergedIdx, mergeTarget);
    }

    /**
     * 拖拽中父组可能变化的条目（原 {@code MainController.parentChangeTargets} 语义，逐字迁移）：
     * 入组/出组的条目自身；组合并时为<b>被拖组的全部成员</b>（它们的生效参数随父组切换）。
     */
    private List<PreviewItem> collectAffected(DropResolver.Operation op) {
        if (op instanceof DropResolver.InsertTop it && it.item() instanceof PreviewItem p) {
            return List.of(p);
        }
        if (op instanceof DropResolver.InsertInto ii && ii.item() instanceof PreviewItem p) {
            return List.of(p);
        }
        if (op instanceof DropResolver.MergeInto mi && mi.dragged() instanceof GroupItem<?> g) {
            List<PreviewItem> out = new ArrayList<>();
            for (Object m : g.getMembers()) {
                if (m instanceof PreviewItem p) {
                    out.add(p);
                }
            }
            return out;
        }
        return List.of();
    }

    /**
     * 重建父组指针：以数据序为准统一维护 {@link PreviewItem#parentGroup}。
     *
     * <p>模型内部在 {@link #applyResolvedDrop} 与 {@link #applyUndo} 后调用；
     * GUI 的显示序重建（{@code rebuildDisplay}）也委托本方法 —— <b>只保留这一份实现</b>，
     * 避免"同一语义两处各写一遍"（那正是本次收口要消灭的形态）。</p>
     */
    public void rebuildParentPointers() {
        for (Object o : topLevel) {
            if (o instanceof GroupItem<?> raw) {
                GroupItem<PreviewItem> g = GroupStructure.asPreviewGroup(raw);
                for (Object m : g.getMembers()) {
                    if (m instanceof PreviewItem it) {
                        it.parentGroup = g;
                    }
                }
            } else if (o instanceof PreviewItem it) {
                it.parentGroup = null;
            }
        }
    }

    // ==================== 变更：撤销 ====================

    /**
     * 撤销一步。
     *
     * <p><b>范围说明（诚实标注）</b>：本类实现与"列表结构"相关的撤销类型
     * （{@code IMPORT / GROUP / UNGROUP / REMOVE / REMOVE_GROUP}），
     * 覆盖了"桩 UI 跑通工作区功能"与生产 GUI 的结构类撤销所需。
     * 参数类撤销（{@code PARAM_SET / CLEAR_PARAMS / APPLY_GLOBAL}）<b>仍</b>未纳入 ——
     * 它们需要参数快照与作用域寻址，属后续步骤（见 {@code docs/handoff/H17.md}）。
     * 原本文中列为"尚未纳入"的 {@code REORDER / MERGE_GROUPS} 已于 Q0/P0 纳入本类
     * （见 {@link #applyUndo}：恢复方式是结构快照，不需要落点解析）。</p>
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
        if (!applyUndo(record)) {
            undoManager.push(record);   // 原样压回，保持栈序不变
            return false;
        }
        return true;
    }

    /**
     * 应用一条**已被弹出**的结构类撤销记录（本方法<b>不触碰撤销栈</b>）。
     *
     * <p><b>为什么需要它</b>：GUI 的撤销流程必须先拿到记录、据类型做 UI 收尾
     * （选中跳转、缓存规则 (c)、重新预解析、日志），因此不能用 {@link #undo()}；
     * 但"结构如何恢复"这份实现必须只有一处。本方法即那唯一一处 ——
     * {@link #undo()} 与 GUI 都经它，从而消除"栈统一了、应用仍有两份"的重复。</p>
     *
     * <p><b>支持类型</b>：{@code REMOVE / REMOVE_GROUP / GROUP / UNGROUP / IMPORT / REORDER / MERGE_GROUPS}。
     * 其余（参数类）返回 {@code false} 且<b>不改变任何状态</b>，由调用方自行应用。</p>
     *
     * <p>拖拽类（{@code REORDER / MERGE_GROUPS}）自 Q0/P0 起纳入：它们此前落在 {@code default} 分支，
     * 是最后两处"GUI 把共享数据序交给 util 层改写"的间接写。恢复方式与
     * {@code GROUP} 同构 —— {@link GroupStructure#restoreStructure} 按操作前快照重建顶层顺序与
     * 各组成员（组对象复用，故组名/组参数保留），随后重建父组指针。</p>
     *
     * <p><b>受影响条目怎么拿</b>：用 {@link #applyUndoAffected} —— 它按"上一次
     * {@code applyUndo} 的记录"作答，因为布尔返回值必须保持既有语义
     * （5 个既有类型的调用点依赖它，不能为了多回传一个列表就改签名）。</p>
     *
     * @return true = 本类应用了该记录；false = 该类型本类尚未支持（未做任何变更）
     */
    public boolean applyUndo(UndoManager.Record<PreviewItem> record) {
        lastUndoAffected = List.of();
        if (record == null) {
            return false;
        }
        switch (record.type()) {
            case REMOVE, REMOVE_GROUP -> undoRemove(record);
            case GROUP -> undoGroup(record);
            case UNGROUP -> undoUngroup(record);
            case IMPORT -> undoImport(record);
            case REORDER, MERGE_GROUPS -> undoRestructure(record);
            default -> {
                return false;
            }
        }
        publish(WorkspaceChange.Kind.UNDO_APPLIED, undoAffectedOf(record));
        return true;
    }

    /**
     * 本次撤销"恢复进数据序"的条目（{@link WorkspaceChange#affected()} 的口径，见其 javadoc）。
     *
     * @param record 刚刚成功应用的那条记录（非 null）
     */
    private List<PreviewItem> undoAffectedOf(UndoManager.Record<PreviewItem> record) {
        return switch (record.type()) {
            // 拖拽类：与 applyUndoAffected() 同源（undoRestructure 刚写入 lastUndoAffected）
            case REORDER, MERGE_GROUPS -> lastUndoAffected == null ? List.of() : lastUndoAffected;
            // 撤销导入是把条目**移出**数据序，没有"恢复进数据序"的条目
            case IMPORT -> List.of();
            // REMOVE / REMOVE_GROUP / GROUP / UNGROUP：记录里的条目回到数据序
            default -> record.items() == null ? List.of() : record.items();
        };
    }

    /**
     * 上一次 {@link #applyUndo} 中<b>受影响</b>的条目（已过滤掉 {@code removed} 项）。
     *
     * <p><b>为什么是"配套查询"而不是改返回值</b>：{@code applyUndo} 的布尔语义已有 5 个类型的调用点
     * 依赖，升级为 record 会波及它们全部；而本方法只有拖拽类撤销一个调用点。代价是<b>有状态</b> ——
     * 故契约写死：<b>只能紧跟在一次成功的 {@link #applyUndo} 之后调用</b>，
     * 它描述的是那一次撤销的结果，不描述当前工作区状态。</p>
     *
     * <p>返回内容：{@code REORDER / MERGE_GROUPS} 记录里 {@code !removed} 的条目
     * （{@code REORDER} 为被拖项目、组移动时为空；{@code MERGE_GROUPS} 为被拖组成员副本）；
     * 其余类型（含未支持类型与 {@code record == null}）返回空列表。
     * GUI 据此做缓存失效（规则 (c) 的邻近语义）与重新预解析。</p>
     */
    public List<PreviewItem> applyUndoAffected() {
        List<PreviewItem> out = new ArrayList<>();
        for (PreviewItem it : lastUndoAffected) {
            if (!it.removed) {
                out.add(it);
            }
        }
        return List.copyOf(out);
    }

    /** 拖拽类撤销：按操作前快照恢复顶层顺序与各组成员（组对象复用），并重建父组指针 */
    private void undoRestructure(UndoManager.Record<PreviewItem> record) {
        if (record.structureBefore() != null) {
            GroupStructure.restoreStructure(topLevel, record.structureBefore());
        }
        rebuildParentPointers();
        lastUndoAffected = record.items() == null ? List.of() : record.items();
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
        // 与生产（MainController.onUndoClicked 的 UNGROUP 分支）**同一套机制**：
        // 用 restoreGroup 把组按原顶层索引插回并恢复成员。原实现走 restoreStructure
        // （整体清空 topLevel 再按快照重建），虽然在本例结果相同，但机制不同、边界语义不同
        // —— 既然要"接入生产"，就统一到生产那一套，避免留下第二份语义。
        GroupStructure.restoreGroup(topLevel, record.group(), record.groupIndex(), record.items());
        for (PreviewItem m : record.items()) {
            m.parentGroup = record.group();   // 生产侧靠 rebuildParentPointers 兜底，这里显式维护
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

    /** 清空全部状态（供测试与"新建会话"用）：数据序 + 撤销栈 + 组号计数器，并发布一次 {@link WorkspaceChange.Kind#CLEARED} */
    public void clear() {
        topLevel.clear();
        undoManager.clear();
        lastGroupNo = 0;
        publish(WorkspaceChange.Kind.CLEARED, List.of());
    }

    /**
     * 只清空**数据序结构**（不动撤销栈、不动组号计数器），并发布一次
     * {@link WorkspaceChange.Kind#CLEARED}。
     *
     * <p>这是 GUI 侧 {@code items.clear()} 入口（{@code WorkspaceFxAdapter.dataOrder()}
     * 所包装的那一个）的转发目标 —— 它的语义必须与"清场只清列表"的既有实现逐字一致：
     * 调用方随后自行 {@link #clearUndoStack()}（{@code FeatureExercise} 就是这么写的），
     * 组号计数器则<b>不得</b>被重置（重置会让清场后的首个自动建组改名，属用户可见的变化）。</p>
     */
    public void clearItems() {
        topLevel.clear();
        publish(WorkspaceChange.Kind.CLEARED, List.of());
    }

    // ==================== 内部工具 ====================

    /** 默认组名前缀（zh "组" / en "Group"；产物命名属"产物本地化"，由 bundle 决定） */
    private String defaultGroupName() {
        return I18n.get("core.groupDefault");
    }

    /**
     * 目标在顶层的**落点**：在顶层 → 其自身索引；在某组内**且该组也仍在顶层** → 该组索引 + 1；
     * 否则返回 -1（该目标不在本工作区内）。
     *
     * <p><b>为什么必须先判"是否直接在顶层"</b>：{@code parentGroup} 会残留 ——
     * ① {@link #clear()} 只清模型内部状态，不触碰条目对象；
     * ② 撤销建组后成员回到顶层，但其 {@code parentGroup} 仍指向已脱离顶层的旧组
     * （这是**有意保留**的既有语义，见 {@code WorkspaceModelParityTest} 的实测记录）。
     * 若先看 {@code parentGroup}，就会把这些条目误判为"仍在该组内"，实测会导致
     * **在空工作区上建出组**或**把已回到顶层的条目漏删而留下重复项**。
     * 这正是 {@code docs/handoff/H32.md} §三.3 的教训："任何靠 parentGroup 判断成员归属的代码
     * 都不可靠，必须看结构" —— 本方法即"看结构"，且顶层直接成员优先。</p>
     */
    private int slotOf(PreviewItem t) {
        int selfIdx = topLevel.indexOf(t);
        if (selfIdx >= 0) {
            return selfIdx;                      // 直接在顶层（残留 parentGroup 不参与判定）
        }
        GroupItem<PreviewItem> pg = t.parentGroup;
        if (pg != null) {
            int gi = topLevel.indexOf(pg);
            if (gi >= 0 && pg.getMembers().contains(t)) {
                return gi + 1;                   // 确在某顶层组内 → 落点在组之后
            }
        }
        return -1;                               // 不在本工作区内
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

    /**
     * 全部顶层组名（去重、保持顶层顺序）。
     *
     * <p><b>为什么公开</b>：这是「现有组名集合」的<b>唯一来源</b>。手工建组（{@link #group}）与
     * 导入自动建组（{@code MainController.createGroupForImport}）都必须用同一份集合做组名唯一化
     * （{@link GroupStructure#uniqueGroupName}），否则两条路径会各自维护一份而可能生成重名组。
     * 控制器侧的私有副本已删除，改为直接调用本方法（R8-① 收口）。</p>
     *
     * <p>顺序取顶层顺序（而非模型内部任何散列结构），保证"组1 → 组1(2) → 组1(3)"的既有唯一化结果
     * 与迁移前<b>逐字一致</b>。</p>
     */
    public Set<String> groupNames() {
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
}
