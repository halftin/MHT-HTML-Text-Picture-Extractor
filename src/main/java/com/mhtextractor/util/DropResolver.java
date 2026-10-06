package com.mhtextractor.util;

import com.mhtextractor.model.GroupItem;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 拖拽落点解析与执行（6.6 拖拽规则，阶段 7）：
 * 把（被拖对象, 目标行, 落点分区）解析为数据序操作，并负责执行、
 * no-op 检测与"拖拽致空组自动销毁"（R4，与移除致空组一致）。
 *
 * <p>落点分区约定：组条目行三区——TOP（组前空隙）/ INTO（整行：入组末尾或两组合并）/ BOTTOM（组后空隙）；
 * 项目行（顶层或组内成员）两区——TOP（行前）/ BOTTOM（行后）。折叠组只有组条目一行，
 * 项目拖到其 INTO = 入组末尾，其上下空隙仍为顶层落点，逻辑与展开状态自洽。</p>
 *
 * <p>本类不感知 GUI 状态，全部为纯函数（apply 直接修改传入的数据序列表），
 * 供 MainController 与单元测试共用。数据序约定：顶层混合列表（项目 | GroupItem），
 * 组内成员存于 GroupItem.members。</p>
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class DropResolver {

    private DropResolver() {
    }

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
     * @param row       该行的数据对象（{@code GroupItem} 走三区，其余走两区）
     * @param y         鼠标相对行顶部的 y
     * @param height    行高
     * @param edgeRatio 组行上下边缘带占行高的比例（原 {@code GROUP_ROW_EDGE_ZONE = 0.25}）
     */
    public static Zone zoneFor(Object row, double y, double height, double edgeRatio) {
        if (row instanceof GroupItem<?>) {
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
    public record InsertInto(Object item, GroupItem<?> group, int memberIndex) implements Operation {
        @Override
        public Object dragSource() {
            return item;
        }
    }

    /** 组条目移动到顶层指定位置（整组移动，成员跟随，仅改变组间顺序） */
    public record MoveGroup(GroupItem<?> group, int index) implements Operation {
        @Override
        public Object dragSource() {
            return group;
        }
    }

    /** 两组合并：dragged 组成员按原顺序插入 target 组 memberIndex，dragged 组销毁（组参数丢弃） */
    public record MergeInto(GroupItem<?> dragged, GroupItem<?> target, int memberIndex) implements Operation {
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
        if (dragged instanceof GroupItem dg) {
            return resolveGroupDrag(topLevel, dg, target, zone);
        }
        return resolveItemDrag(topLevel, dragged, target, zone);
    }

    private static Operation resolveItemDrag(List<?> topLevel, Object item, Object target, Zone zone) {
        if (target instanceof GroupItem tg) {
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
        GroupItem parent = findGroupOf(topLevel, target);
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

    private static Operation resolveGroupDrag(List<?> topLevel, GroupItem dg, Object target, Zone zone) {
        if (target instanceof GroupItem tg) {
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
        GroupItem parent = findGroupOf(topLevel, target);
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

    /**
     * 执行落点操作：直接修改数据序；结构未变化（原位 no-op）或前置条件不满足时
     * 返回 false 且不修改。removed 谓词用于空组销毁判定（全部成员满足即销毁）。
     */
    public static boolean apply(List<Object> topLevel, Operation op, Predicate<Object> removed) {
        if (op instanceof InsertTop it) {
            return applyInsertTop(topLevel, it, removed);
        }
        if (op instanceof InsertInto ii) {
            return applyInsertInto(topLevel, ii, removed);
        }
        if (op instanceof MoveGroup mg) {
            return applyMoveGroup(topLevel, mg);
        }
        if (op instanceof MergeInto mi) {
            return applyMerge(topLevel, mi);
        }
        return false;
    }

    private static boolean applyInsertTop(List<Object> topLevel, InsertTop op, Predicate<Object> removed) {
        Object item = op.item();
        int ci = topLevel.indexOf(item);
        GroupItem source = null;
        if (ci < 0) {
            source = findGroupOf(topLevel, item);
            if (source == null || !source.getMembers().contains(item)) {
                return false;
            }
        } else if (op.index() == ci || op.index() == ci + 1) {
            return false; // 顶层原位（插入自身前/后等价于不动）
        }
        int d = op.index();
        if (ci >= 0) {
            topLevel.remove(ci);
            if (ci < d) {
                d--;
            }
        } else {
            source.getMembers().remove(item);
            if (source.getMembers().stream().allMatch(removed)) {
                // 拖拽致原组为空 → 原组自动销毁（R4）
                int gIdx = topLevel.indexOf(source);
                topLevel.remove(gIdx);
                if (gIdx < d) {
                    d--;
                }
            }
        }
        topLevel.add(Math.max(0, Math.min(d, topLevel.size())), item);
        return true;
    }

    private static boolean applyInsertInto(List<Object> topLevel, InsertInto op, Predicate<Object> removed) {
        Object item = op.item();
        GroupItem target = op.group();
        if (!topLevel.contains(target)) {
            return false;
        }
        int d = Math.max(0, Math.min(op.memberIndex(), target.getMembers().size()));
        int ci = topLevel.indexOf(item);
        if (ci < 0) {
            GroupItem cur = findGroupOf(topLevel, item);
            if (cur == null || !cur.getMembers().contains(item)) {
                return false;
            }
            if (cur == target) {
                // 同组内排序：原位判定（插入自身前/后等价于不动）
                int mi = target.getMembers().indexOf(item);
                if (d == mi || d == mi + 1) {
                    return false;
                }
                target.getMembers().remove(mi);
                if (d > mi) {
                    d--;
                }
            } else {
                cur.getMembers().remove(item);
                if (cur.getMembers().stream().allMatch(removed)) {
                    topLevel.remove(cur);
                }
            }
        } else {
            topLevel.remove(ci);
        }
        target.getMembers().add(Math.max(0, Math.min(d, target.getMembers().size())), item);
        return true;
    }

    private static boolean applyMoveGroup(List<Object> topLevel, MoveGroup op) {
        GroupItem g = op.group();
        int ci = topLevel.indexOf(g);
        if (ci < 0) {
            return false;
        }
        if (op.index() == ci || op.index() == ci + 1) {
            return false; // 原位
        }
        topLevel.remove(ci);
        int d = op.index();
        if (ci < d) {
            d--;
        }
        topLevel.add(Math.max(0, Math.min(d, topLevel.size())), g);
        return true;
    }

    private static boolean applyMerge(List<Object> topLevel, MergeInto op) {
        GroupItem dragged = op.dragged();
        GroupItem target = op.target();
        if (dragged == target) {
            return false;
        }
        int gi = topLevel.indexOf(dragged);
        if (gi < 0 || !topLevel.contains(target)) {
            return false;
        }
        List<Object> members = new ArrayList<>(dragged.getMembers());
        dragged.getMembers().clear();
        topLevel.remove(gi);
        target.getMembers().addAll(
                Math.max(0, Math.min(op.memberIndex(), target.getMembers().size())), members);
        return true;
    }

    /** 顶层列表中查找包含指定成员的组（项目不在顶层时） */
    private static GroupItem findGroupOf(List<?> topLevel, Object member) {
        for (Object o : topLevel) {
            if (o instanceof GroupItem g && g.getMembers().contains(member)) {
                return g;
            }
        }
        return null;
    }
}
