package com.mhtextractor.util;

import com.mhtextractor.model.PreviewItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * 分组结构纯逻辑（F5 阶段 4）：显示序构建、组名唯一化、组编号自增、空组检测与结构快照恢复。
 *
 * <p>本类不感知 GUI 状态，全部方法为纯函数，供 MainController 与单元测试共用。</p>
 */
public final class GroupStructure {

    private GroupStructure() {
    }

    /**
     * 把 {@code GroupItem<?>} 安全转换为 {@code GroupItem<PreviewItem>}（unchecked 转换的唯一收口点，E1.3）。
     *
     * <p>转换前先验证全部成员确为 {@code PreviewItem}：验证通过则转换必然安全
     * （本工作区的建组路径只会产生 {@code GroupItem<PreviewItem>}）；验证失败立即抛异常，
     * 避免堆污染导致的 {@code ClassCastException} 在远离根因处爆发。此前 {@code WorkspaceModel}
     * 与 {@code ListInteractionController} 各有一份逐字相同的实现，已删除并统一走本入口。</p>
     */
    @SuppressWarnings("unchecked")
    public static GroupItem<PreviewItem> asPreviewGroup(GroupItem<?> g) {
        for (Object m : g.getMembers()) {
            if (!(m instanceof PreviewItem)) {
                throw new IllegalStateException("Non-PreviewItem member in group " + g.getName() + ": " + m);
            }
        }
        return (GroupItem<PreviewItem>) g;
    }

    /** 结构快照条目：原组 + 成员列表副本（项目对象不深拷贝，恢复时复用原组对象） */
    public record GroupSnap<M>(GroupItem<M> group, List<M> members) {
    }

    /**
     * 显示序构建：顶层条目与组条目平铺，展开的组后跟随其组内成员。
     * 已移除（removed）项由谓词过滤——不进入显示序（VirtualFlow 布局会强制恢复 cell 的
     * visible，靠单元格隐藏不可靠，会产生占位空洞）；数据序仍保留 removed 项，撤销恢复
     * 位置不受影响。
     */
    public static List<Object> flatten(List<?> topLevel, Predicate<Object> removed) {
        List<Object> flat = new ArrayList<>();
        for (Object o : topLevel) {
            if (o instanceof GroupItem<?> g) {
                flat.add(g);
                if (g.isExpanded()) {
                    for (Object m : g.getMembers()) {
                        if (!removed.test(m)) {
                            flat.add(m);
                        }
                    }
                }
            } else if (!removed.test(o)) {
                flat.add(o);
            }
        }
        return flat;
    }

    /**
     * 组名唯一化（R7）：与现有组名冲突时自动追加序号直至唯一（组1 → 组1(2) → 组1(3)…）。
     * 空白名不处理，原样返回。
     */
    public static String uniqueGroupName(String desired, Collection<String> existing) {
        if (desired == null || desired.isBlank()) {
            return desired;
        }
        String candidate = desired;
        int n = 2;
        while (existing.contains(candidate)) {
            candidate = desired + "(" + n++ + ")";
        }
        return candidate;
    }

    /** 组编号自增：全局单调递增，不复用已销毁组的编号 */
    public static int nextGroupNo(int lastNo) {
        return lastNo + 1;
    }

    /** 空组检测：组内所有成员均满足 removed 判定（成员列表为空同样视为空组） */
    public static <M> boolean isEmptyGroup(GroupItem<M> group, Predicate<M> removed) {
        return group.getMembers().stream().allMatch(removed);
    }

    /**
     * 拆分组（F6）：组从顶层移除，组内成员（含已移除项）按组内相对顺序插入原组条目所在位置。
     * 与组展开/折叠无关（整组操作）。组对象（组名/组参数）原样保留，供撤销记录恢复。
     *
     * @return 拆出的成员列表副本（组内顺序）；组不在顶层时返回空列表且不修改
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <M> List<M> dissolveGroup(List<Object> topLevel, GroupItem<M> group) {
        int idx = topLevel.indexOf(group);
        if (idx < 0) {
            return List.of();
        }
        List<M> members = new ArrayList<>(group.getMembers());
        group.getMembers().clear();
        topLevel.remove(idx);
        topLevel.addAll(idx, new ArrayList<Object>((List) members));
        return members;
    }

    /**
     * 组重建（F6 撤销拆分）：成员从顶层移除并重新划入组（组内顺序恢复），组对象插入原顶层索引。
     * 组名与组参数随组对象保留，无需额外恢复。
     */
    public static <M> void restoreGroup(List<Object> topLevel, GroupItem<M> group, int index, List<M> members) {
        for (M m : members) {
            topLevel.remove(m);
        }
        // 会话 18 · E1.2：原 setAll（ObservableList，JavaFX）→ clear+addAll（等价整体替换）
        List<M> current = group.getMembers();
        current.clear();
        current.addAll(members);
        topLevel.add(Math.max(0, Math.min(index, topLevel.size())), group);
    }

    /** 结构快照：顶层顺序 + 各组成员列表副本（撤销 GROUP 恢复原归属用） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List<Object> snapshotStructure(List<?> topLevel) {
        List<Object> snap = new ArrayList<>();
        for (Object o : topLevel) {
            if (o instanceof GroupItem g) {
                snap.add(new GroupSnap(g, new ArrayList(g.getMembers())));
            } else {
                snap.add(o);
            }
        }
        return snap;
    }

    /** 结构恢复：按快照重建顶层顺序与各组成员（组对象复用，不深拷贝） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void restoreStructure(List<Object> topLevel, List<Object> snapshot) {
        topLevel.clear();
        for (Object o : snapshot) {
            if (o instanceof GroupSnap<?> gs) {
                // 会话 18 · E1.2：原用 ObservableList.setAll（JavaFX 依赖）；
                // 成员列表已改回普通 List，clear+addAll 为等价替换（同样是整体替换并保持顺序）
                List members = gs.group().getMembers();
                members.clear();
                members.addAll(gs.members());
                topLevel.add(gs.group());
            } else {
                topLevel.add(o);
            }
        }
    }
}
