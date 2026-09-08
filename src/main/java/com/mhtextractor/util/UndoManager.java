package com.mhtextractor.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 撤销栈（F4）：按"操作类型 + 受影响项目"记录压栈，后进先出弹出。深度 35 步，超出时丢弃最旧记录。
 *
 * <p>记录类型：REMOVE（隐藏式移除，含空组销毁）、IMPORT（增量导入）、
 * GROUP（合并为组，含操作前结构快照）、UNGROUP（拆分组，组名/参数随组对象保留）、
 * REMOVE_GROUP（整组移除，组名/参数随组对象保留）、REORDER（拖拽排序，含操作前结构快照）、
 * MERGE_GROUPS（拖拽组合并，含操作前结构快照，被拖组名/参数随组对象保留）。</p>
 *
 * @param <T> 列表项目类型
 */
public final class UndoManager<T> {

    /** 撤销栈深度上限（F4） */
    public static final int DEFAULT_LIMIT = 35;

    /** 可撤销操作类型（F4 5.4 + R12 + 阶段 7） */
    public enum RecordType { REMOVE, IMPORT, GROUP, UNGROUP, REMOVE_GROUP, REORDER, MERGE_GROUPS }

    /**
     * 一条撤销记录。
     *
     * @param type             操作类型
     * @param items            受影响项目（按操作时顺序）；REORDER：被拖项目（组移动时为空，供撤销后重解析）；MERGE_GROUPS：被拖组成员副本
     * @param group            GROUP：新建的组；UNGROUP：被解散的组；REMOVE_GROUP：被移除的组；MERGE_GROUPS：被拖（销毁）的组；其余为 null
     * @param groupIndex       group 在顶层数据序中的位置（无组时为 -1）
     * @param destroyedGroups  REMOVE/REMOVE_GROUP：因成员全部被移除而销毁的组（撤销时按索引恢复）
     * @param destroyedIndexes 与 destroyedGroups 一一对应的顶层索引
     * @param structureBefore  GROUP/REORDER/MERGE_GROUPS：操作前的顶层结构快照（撤销时恢复用）
     */
    public record Record<T>(RecordType type, List<T> items,
                            GroupItem<T> group, int groupIndex,
                            List<GroupItem<T>> destroyedGroups, List<Integer> destroyedIndexes,
                            List<Object> structureBefore) {
        public Record {
            items = List.copyOf(items);
            destroyedGroups = List.copyOf(destroyedGroups);
            destroyedIndexes = List.copyOf(destroyedIndexes);
            structureBefore = List.copyOf(structureBefore);
        }

        /** 仅含类型与项目的记录（不含分组载荷） */
        public Record(RecordType type, List<T> items) {
            this(type, items, null, -1, List.of(), List.of(), List.of());
        }
    }

    private final Deque<Record<T>> stack = new ArrayDeque<>();
    private final int limit;

    public UndoManager() {
        this(DEFAULT_LIMIT);
    }

    public UndoManager(int limit) {
        this.limit = limit;
    }

    /** 压入一条完整记录；超出深度上限时丢弃最旧记录 */
    public void push(Record<T> record) {
        stack.addLast(record);
        while (stack.size() > limit) {
            stack.removeFirst();
        }
    }

    /** 压入仅含类型与项目的记录（简单操作） */
    public void push(RecordType type, List<T> items) {
        push(new Record<>(type, items));
    }

    /** 弹出最新一条记录；栈空时返回 null */
    public Record<T> pop() {
        return stack.pollLast();
    }

    public boolean isEmpty() {
        return stack.isEmpty();
    }

    public int size() {
        return stack.size();
    }

    public void clear() {
        stack.clear();
    }
}
