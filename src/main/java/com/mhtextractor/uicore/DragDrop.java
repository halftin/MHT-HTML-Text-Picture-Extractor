package com.mhtextractor.uicore;

import java.util.List;

/** 视图几何与视图结构悬停预判；执行由 commands().applyDrop 裁决。 */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class DragDrop {
    private DragDrop() { }

    /** 落点分区：TOP = 行上边缘空隙；INTO = 组行整行（入组/合并语义）；BOTTOM = 行下边缘空隙 */
    public enum Zone { TOP, INTO, BOTTOM }

    /**
     * 由鼠标在行内的相对位置算出落点分区（步骤 3 从 {@code MainController.dropZone} 外迁）。
     *
     * <p><b>为什么外迁</b>：这是**纯几何**判定，不依赖任何 UI 框架，却在控制器里是私有方法 ——
     * 单元格类要调用它就必须放宽可见性（即增加"测试支撑暴露面"）。外迁后既不必放宽，
     * 也首次获得直接单测。</p>
     *
     * <p>规则：组行分三区（上下边缘 = 行间空隙，中间带 = 整行入组/合并）；项目行分两区（上/下）。</p>
     *
     * @param row       该行的数据对象（{@code GroupRow} 走三区，其余走两区）
     * @param y         鼠标相对行顶部的 y
     * @param height    行高
     * @param edgeRatio 组行上下边缘带占行高的比例（原 {@code GROUP_ROW_EDGE_ZONE = 0.25}）
     */
    public static Zone zoneFor(Object row, double y, double height, double edgeRatio) {
        if (row instanceof GroupRow<?>) {
            if (y < height * edgeRatio) {
                return Zone.TOP;
            }
            if (y < height * (1 - edgeRatio)) {
                return Zone.INTO;
            }
            return Zone.BOTTOM;
        }
        return y < height / 2 ? Zone.TOP : Zone.BOTTOM;
    }

    /** 一次拖放对应的数据序操作 */
    public sealed interface Operation {
        /** 被拖对象（项目或组条目） */
        Object dragSource();
    }

    /** 项目移动到顶层指定位置（出组到顶层 / 顶层重排） */
    public record InsertTop(Object item, int index) implements Operation {
        @Override
        public Object dragSource() {
            return item;
        }
    }

    /** 项目入组（组内指定落点；memberIndex == 组内成员数即入组末尾） */
    public record InsertInto(Object item, GroupRow<?> group, int memberIndex) implements Operation {
        @Override
        public Object dragSource() {
            return item;
        }
    }

    /** 组条目移动到顶层指定位置（整组移动，成员跟随，仅改变组间顺序） */
    public record MoveGroup(GroupRow<?> group, int index) implements Operation {
        @Override
        public Object dragSource() {
            return group;
        }
    }

    /** 两组合并：dragged 组成员按原顺序插入 target 组 memberIndex，dragged 组销毁（组参数丢弃） */
    public record MergeInto(GroupRow<?> dragged, GroupRow<?> target, int memberIndex) implements Operation {
        @Override
        public Object dragSource() {
            return dragged;
        }
    }

    /**
     * 落点解析：把（被拖对象, 目标行, 分区）解析为数据序操作。
     * 非法组合（拖到自身、组拖到自身组成员行/自身组行、目标不在数据序中等）返回 null。
     * 索引均以解析时刻的数据序为准，apply 执行时自行修正移除偏移。
     */
    public static Operation resolve(List<?> topLevel, Object dragged, Object target, Zone zone) {
        if (dragged == null || target == null || zone == null || dragged == target) {
            return null;
        }
        if (dragged instanceof GroupRow dg) {
            return resolveGroupDrag(topLevel, dg, target, zone);
        }
        return resolveItemDrag(topLevel, dragged, target, zone);
    }

    private static Operation resolveItemDrag(List<?> topLevel, Object item, Object target, Zone zone) {
        if (target instanceof GroupRow tg) {
            int ti = topLevel.indexOf(tg);
            if (ti < 0) {
                return null;
            }
            return switch (zone) {
                case TOP -> new InsertTop(item, ti);
                case INTO -> new InsertInto(item, tg, tg.getMembers().size());
                case BOTTOM -> new InsertTop(item, ti + 1);
            };
        }
        GroupRow parent = findGroupOf(topLevel, target);
        if (parent != null) {
            int mi = parent.getMembers().indexOf(target);
            if (mi < 0) {
                return null;
            }
            return zone == Zone.TOP
                    ? new InsertInto(item, parent, mi)
                    : new InsertInto(item, parent, mi + 1);
        }
        int ti = topLevel.indexOf(target);
        if (ti < 0) {
            return null;
        }
        return zone == Zone.TOP ? new InsertTop(item, ti) : new InsertTop(item, ti + 1);
    }

    private static Operation resolveGroupDrag(List<?> topLevel, GroupRow dg, Object target, Zone zone) {
        if (target instanceof GroupRow tg) {
            if (dg == tg) {
                return null;
            }
            int ti = topLevel.indexOf(tg);
            if (ti < 0) {
                return null;
            }
            return switch (zone) {
                case TOP -> new MoveGroup(dg, ti);
                case INTO -> new MergeInto(dg, tg, tg.getMembers().size());
                case BOTTOM -> new MoveGroup(dg, ti + 1);
            };
        }
        GroupRow parent = findGroupOf(topLevel, target);
        if (parent == dg) {
            // 拖到自己的成员行：无意义
            return null;
        }
        if (parent != null) {
            int mi = parent.getMembers().indexOf(target);
            if (mi < 0) {
                return null;
            }
            return zone == Zone.TOP
                    ? new MergeInto(dg, parent, mi)
                    : new MergeInto(dg, parent, mi + 1);
        }
        int ti = topLevel.indexOf(target);
        if (ti < 0) {
            return null;
        }
        return zone == Zone.TOP ? new MoveGroup(dg, ti) : new MoveGroup(dg, ti + 1);
    }

    /** 顶层列表中查找包含指定成员的组（项目不在顶层时） */
    private static GroupRow findGroupOf(List<?> topLevel, Object member) {
        for (Object o : topLevel) {
            if (o instanceof GroupRow g && g.getMembers().contains(member)) {
                return g;
            }
        }
        return null;
    }
}
