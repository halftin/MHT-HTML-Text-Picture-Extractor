package com.mhtextractor.engine;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 撤销载荷构造工厂（P1-4）：gui 层三条 {@code new UndoManager.Record<>(...)} 的<b>唯一构造出口</b>，
 * 构造下沉到 app 层（零 JavaFX、零 I18n），gui 调用方只负责压栈（{@code ws.push(...)} /
 * {@code host.pushUndo(...)}），压栈时机与顺序不变。
 *
 * <p><b>只搬不改</b>：字段值、构造参数与搬移前逐字一致（含 {@code List.of()} 占位、{@code -1}、
 * {@code false}、{@code null} 的位置；IMPORT 的 {@code new ArrayList<>} 拷贝语义保持在工厂内）。</p>
 */
public final class UndoRecords {

    private UndoRecords() {
    }

    /** IMPORT 三参重载：一次导入 = 一条记录携带全部自动创建的组（BC-5）；拷贝语义与搬移前一致。 */
    public static UndoManager.Record<PreviewItem> importRecord(List<PreviewItem> items,
                                                               List<GroupItem<PreviewItem>> groups) {
        return new UndoManager.Record<>(UndoManager.RecordType.IMPORT,
                new ArrayList<>(items), new ArrayList<>(groups));
    }

    /** APPLY_GLOBAL 全参记录：应用前的全局参数 + 全部独立参数快照，撤销可完整回退。 */
    public static UndoManager.Record<PreviewItem> applyGlobal(boolean imageMode, AppConfig globalBefore,
                                                              Map<String, AppConfig> itemBefore,
                                                              Map<Integer, AppConfig> groupBefore) {
        return new UndoManager.Record<>(UndoManager.RecordType.APPLY_GLOBAL, List.of(),
                null, -1, List.of(), List.of(), List.of(), List.of(),
                imageMode, globalBefore, itemBefore, groupBefore, false, null, null, null,
                false, null, null);
    }

    /** CLEAR_PARAMS 全参记录：清除前的该模式条目级/组级参数快照。 */
    public static UndoManager.Record<PreviewItem> clearParams(boolean imageMode,
                                                              Map<String, AppConfig> itemBefore,
                                                              Map<Integer, AppConfig> groupBefore) {
        return new UndoManager.Record<>(UndoManager.RecordType.CLEAR_PARAMS, List.of(),
                null, -1, List.of(), List.of(), List.of(), List.of(),
                false, null, null, null, false, null, null, null,
                imageMode, itemBefore, groupBefore);
    }
}
