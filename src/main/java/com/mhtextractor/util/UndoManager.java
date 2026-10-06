package com.mhtextractor.util;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.model.GroupItem;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 撤销栈（F4）：按"操作类型 + 受影响项目"记录压栈，后进先出弹出。深度 35 步，超出时丢弃最旧记录。
 *
 * <p>记录类型：REMOVE（隐藏式移除，含空组销毁）、IMPORT（增量导入）、
 * GROUP（合并为组，含操作前结构快照）、UNGROUP（拆分组，组名/参数随组对象保留）、
 * REMOVE_GROUP（整组移除，组名/参数随组对象保留）、REORDER（拖拽排序，含操作前结构快照）、
 * MERGE_GROUPS（拖拽组合并，含操作前结构快照，被拖组名/参数随组对象保留）、
 * APPLY_GLOBAL（将参数应用到全局，含应用前全局参数与全部独立参数快照）、
 * PARAM_SET（设置提取参数，条目/组/全局三作用域，含设置前参数）、
 * CLEAR_PARAMS（清除参数，条目/组作用域，含清除前参数快照，一次撤销全部恢复）。</p>
 *
 * @param <T> 列表项目类型
 */
public final class UndoManager<T> {

    /** 撤销栈深度上限（F4） */
    public static final int DEFAULT_LIMIT = 35;

    /** 可撤销操作类型（F4 5.4 + R12 + 阶段 7 + 条目独立参数需求） */
    public enum RecordType {
        REMOVE, IMPORT, GROUP, UNGROUP, REMOVE_GROUP, REORDER, MERGE_GROUPS, APPLY_GLOBAL, PARAM_SET, CLEAR_PARAMS
    }

    /**
     * 一条撤销记录。
     *
     * @param type             操作类型
     * @param items            受影响项目（按操作时顺序）；REORDER：被拖项目（组移动时为空，供撤销后重解析）；MERGE_GROUPS：被拖组成员副本
     * @param group            GROUP：新建的组；UNGROUP：被解散的组；REMOVE_GROUP：被移除的组；MERGE_GROUPS：被拖（销毁）的组；其余为 null
     * @param groupIndex       group 在顶层数据序中的位置（无组时为 -1）
     * @param groups           IMPORT：本次导入自动创建的组（撤销时随成员移除一并销毁）；其余为空
     * @param destroyedGroups  REMOVE/REMOVE_GROUP：因成员全部被移除而销毁的组（撤销时按索引恢复）
     * @param destroyedIndexes 与 destroyedGroups 一一对应的顶层索引
     * @param structureBefore  GROUP/REORDER/MERGE_GROUPS：操作前的顶层结构快照（撤销时恢复用）
     * @param applyGlobalImageMode            APPLY_GLOBAL：应用的是图片模式（false = 文本模式）
     * @param applyGlobalBefore               APPLY_GLOBAL：应用前的该模式全局参数（null = 未确认）
     * @param applyGlobalItemParamsBefore     APPLY_GLOBAL：应用前各条目该模式条目级参数快照（path → 参数，值可为 null）
     * @param applyGlobalGroupParamsBefore    APPLY_GLOBAL：应用前各组该模式参数快照（groupNo → 参数，值可为 null）
     * @param paramImageMode                  PARAM_SET：设置的是图片模式（false = 文本模式）
     * @param paramTargetPath                 PARAM_SET：目标条目路径（条目级参数；全局时为 null）
     * @param paramTargetGroupNo              PARAM_SET：目标组号（组参数；全局/条目级时为 null）
     * @param paramBefore                     PARAM_SET：设置前的该模式参数（null = 未设置）
     * @param clearImageMode                  CLEAR_PARAMS：清除的是图片模式（false = 文本模式）
     * @param clearItemParamsBefore           CLEAR_PARAMS：清除前各条目该模式条目级参数快照（path → 参数）
     * @param clearGroupParamsBefore          CLEAR_PARAMS：清除前各组该模式参数快照（groupNo → 参数）
     */
    public record Record<T>(RecordType type, List<T> items,
                            GroupItem<T> group, int groupIndex,
                            List<GroupItem<T>> groups,
                            List<GroupItem<T>> destroyedGroups, List<Integer> destroyedIndexes,
                            List<Object> structureBefore,
                            boolean applyGlobalImageMode,
                            AppConfig applyGlobalBefore,
                            Map<String, AppConfig> applyGlobalItemParamsBefore,
                            Map<Integer, AppConfig> applyGlobalGroupParamsBefore,
                            boolean paramImageMode,
                            String paramTargetPath,
                            Integer paramTargetGroupNo,
                            AppConfig paramBefore,
                            boolean clearImageMode,
                            Map<String, AppConfig> clearItemParamsBefore,
                            Map<Integer, AppConfig> clearGroupParamsBefore) {
        public Record {
            items = List.copyOf(items);
            groups = groups == null ? List.of() : List.copyOf(groups);
            destroyedGroups = List.copyOf(destroyedGroups);
            destroyedIndexes = List.copyOf(destroyedIndexes);
            structureBefore = List.copyOf(structureBefore);
            applyGlobalItemParamsBefore = applyGlobalItemParamsBefore == null
                    ? Map.of() : new HashMap<>(applyGlobalItemParamsBefore);
            applyGlobalGroupParamsBefore = applyGlobalGroupParamsBefore == null
                    ? Map.of() : new HashMap<>(applyGlobalGroupParamsBefore);
            clearItemParamsBefore = clearItemParamsBefore == null
                    ? Map.of() : new HashMap<>(clearItemParamsBefore);
            clearGroupParamsBefore = clearGroupParamsBefore == null
                    ? Map.of() : new HashMap<>(clearGroupParamsBefore);
        }

        /** 仅含类型与项目的记录（不含分组载荷；groups = 空） */
        public Record(RecordType type, List<T> items) {
            this(type, items, null, -1, List.of(), List.of(), List.of(), List.of(),
                    false, null, null, null, false, null, null, null,
                    false, null, null);
        }

        /** 结构类记录（GROUP/UNGROUP/REMOVE_GROUP/REORDER/MERGE_GROUPS；groups = 空） */
        public Record(RecordType type, List<T> items, GroupItem<T> group, int groupIndex,
                      List<GroupItem<T>> destroyedGroups, List<Integer> destroyedIndexes,
                      List<Object> structureBefore) {
            this(type, items, group, groupIndex, List.of(), destroyedGroups, destroyedIndexes,
                    structureBefore,
                    false, null, null, null, false, null, null, null,
                    false, null, null);
        }

        /** IMPORT 多组载荷：一次导入 = 一条记录携带全部自动创建的组（BC-5） */
        public Record(RecordType type, List<T> items, List<GroupItem<T>> groups) {
            this(type, items, null, -1, groups, List.of(), List.of(), List.of(),
                    false, null, null, null, false, null, null, null,
                    false, null, null);
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
