package com.mhtextractor.gui.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mhtextractor.app.EffectiveParamsService;
import com.mhtextractor.app.ParamClipboard;
import com.mhtextractor.app.ParamsInfo;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.uicore.FileMenuModel;
import com.mhtextractor.model.ParamScope;
import com.mhtextractor.app.UndoManager;

import org.junit.jupiter.api.Test;

/**
 * {@link ParamClipboard} 的无头测试（Q10 / R7.3-R7.7）。
 *
 * <p><b>为什么能无头测</b>：本类零 JavaFX、也不直接写模型 —— 作用域路由与应用本体都由宿主注入
 * （{@link ParamScope.Target} + {@link ParamClipboard.ApplyFn}），撤销压栈走注入的出口。
 * 于是这里可以用"内存参数槽位 + 既有 apply* 的压栈形态"精确复现宿主的调用序列，
 * 从而钉住两件事：<b>批量应用只压一条 PARAM_SET</b>（R7.5）与<b>单目标应用粒度不变</b>。</p>
 *
 * <p>本用例里的 apply 函数刻意复刻宿主 {@code applyItemParams}/{@code applyGroupParams} 的形态：
 * 先写槽位、再经 {@link ParamClipboard#pushParamUndo} 压栈（"每个目标压一条"）。</p>
 */
class ParamClipboardTest {

    private final Map<String, AppConfig> itemSlots = new HashMap<>();
    private final Map<Integer, AppConfig> groupSlots = new HashMap<>();
    private final List<UndoManager.Record<PreviewItem>> pushed = new ArrayList<>();
    private final ParamClipboard clipboard = new ParamClipboard(pushed::add, this::scopeOf, this::applyLikeHost);

    private ParamScope.Target scopeOf(Object selected) {
        return switch (selected) {
            case GroupItem<?> g -> new ParamScope.GroupTarget(g);
            case PreviewItem it -> new ParamScope.ItemTarget<>(it, it.textItemParams, it.imageItemParams);
            default -> ParamScope.GLOBAL;
        };
    }

    /** 复刻宿主 apply* 的形态：写槽位（put 的返回值即 before 快照）+ 压栈 */
    private void applyLikeHost(ParamScope.Target target, boolean imageMode, AppConfig params) {
        switch (target) {
            case ParamScope.GroupTarget(GroupItem<?> g) ->
                    clipboard.pushParamUndo(imageMode, null, g.getGroupNo(), groupSlots.put(g.getGroupNo(), params));
            case ParamScope.ItemTarget<?> it -> {
                PreviewItem pi = (PreviewItem) it.item();
                clipboard.pushParamUndo(imageMode, pi.path, null, itemSlots.put(pi.path, params));
            }
            default -> clipboard.pushParamUndo(imageMode, null, null, null);
        }
    }

    private static PreviewItem item(String name) {
        return new PreviewItem("C:/q10/" + name + ".mht", name + ".mht", LocalDateTime.now(), "C:/q10");
    }

    private static AppConfig params(String marker) {
        AppConfig c = new AppConfig();
        c.setStartMarker(marker);
        return c;
    }

    // ==================== R7.3：未复制 ⇒ 空；清空 ⇒ 回到空 ====================

    @Test
    void clipboardIsEmptyUntilCopiedAndEmptyAgainAfterClear() {
        assertTrue(clipboard.isEmpty(), "初始未复制过");
        assertNull(clipboard.snapshot(), "未复制过时取快照返回 null");
        clipboard.copy(params("A"));
        assertFalse(clipboard.isEmpty(), "复制后非空");
        clipboard.clear();
        assertTrue(clipboard.isEmpty(), "清空后回到空");
        assertNull(clipboard.snapshot(), "清空后取快照返回 null");
        clipboard.copy(null);
        assertTrue(clipboard.isEmpty(), "复制 null（无参数目标）视为未复制");
    }

    // ==================== R7.7：值拷贝语义 + 运行时字段不参与 ====================

    @Test
    void copyIsAValueSnapshotThatNeverCarriesRuntimeFields() {
        AppConfig source = params("SRC");
        source.setSeparateFiles(true);
        source.setInputFolder("C:/in");
        source.setOutputFolder("C:/out");
        source.setImageModeEnabled(true);
        source.setOpenFolderAfterComplete(true);

        clipboard.copy(source);
        AppConfig taken = clipboard.snapshot();
        assertNotSame(source, taken, "复制必须产生新实例（AppConfig 可变，共享会串味）");
        assertEquals(EffectiveParamsService.signatureOf(source), EffectiveParamsService.signatureOf(taken),
                "提取参数必须值相等");
        assertNull(taken.getInputFolder(), "运行时字段 inputFolder 不参与复制");
        assertNull(taken.getOutputFolder(), "运行时字段 outputFolder 不参与复制");
        assertFalse(taken.isImageModeEnabled(), "运行时字段 imageModeEnabled 不参与复制");
        assertFalse(taken.isOpenFolderAfterComplete(), "运行时字段 openFolderAfterComplete 不参与复制");

        // 改副本不影响源；改源不影响剪贴板
        taken.setStartMarker("MUTATED");
        assertEquals("SRC", source.getStartMarker(), "改副本不得影响源");
        source.setStartMarker("SOURCE-CHANGED");
        assertEquals("SRC", clipboard.snapshot().getStartMarker(), "改源不得影响剪贴板里的快照");

        // 两次取出的快照也互不影响（多目标应用时每个目标各拿一份）
        AppConfig a = clipboard.snapshot();
        AppConfig b = clipboard.snapshot();
        assertNotSame(a, b, "两次取快照必须是两个实例");
        a.setStartMarker("A-ONLY");
        assertEquals("SRC", b.getStartMarker(), "快照之间不得共享可变状态");
    }

    // ==================== R7.5：批量应用只压一条 PARAM_SET（载荷 = 各目标 before 快照） ====================

    @Test
    void batchApplyPushesExactlyOneParamSetRecordCarryingEveryTargetBeforeSnapshot() {
        PreviewItem x = item("x");
        PreviewItem y = item("y");
        GroupItem<PreviewItem> g = new GroupItem<>(7, "g");
        itemSlots.put(x.path, params("BEFORE-X"));
        itemSlots.put(y.path, params("BEFORE-Y"));
        groupSlots.put(7, params("BEFORE-G"));

        clipboard.copy(params("COPIED"));
        clipboard.applyToSelection(new ArrayList<>(List.of(x, y, g)), true);

        assertEquals(1, pushed.size(), "一次批量应用必须只压一条 PARAM_SET（R7.5），实际 " + pushed.size());
        UndoManager.Record<PreviewItem> record = pushed.get(0);
        assertEquals(UndoManager.RecordType.PARAM_SET, record.type(), "记录类型必须是 PARAM_SET");
        assertTrue(record.paramImageMode(), "模式位 = 本次应用的模式");
        assertNull(record.paramTargetPath(), "批量记录的条目目标字段为空（载荷在两个映射里）");
        assertNull(record.paramTargetGroupNo(), "批量记录的组目标字段为空");
        assertEquals(2, record.clearItemParamsBefore().size(), "每个条目目标都要有自己的 before 快照");
        assertEquals("BEFORE-X", record.clearItemParamsBefore().get(x.path).getStartMarker());
        assertEquals("BEFORE-Y", record.clearItemParamsBefore().get(y.path).getStartMarker());
        assertEquals(1, record.clearGroupParamsBefore().size(), "组目标也要有自己的 before 快照");
        assertEquals("BEFORE-G", record.clearGroupParamsBefore().get(7).getStartMarker());

        // 写入生效：三处槽位都换成了复制的参数（且与剪贴板不是同一实例）
        assertEquals("COPIED", itemSlots.get(x.path).getStartMarker());
        assertEquals("COPIED", itemSlots.get(y.path).getStartMarker());
        assertEquals("COPIED", groupSlots.get(7).getStartMarker());
        assertNotSame(itemSlots.get(x.path), itemSlots.get(y.path), "两个目标不得共享同一个参数实例");
    }

    @Test
    void singleTargetApplyKeepsTheExistingGranularity() {
        // 非批量：每次应用各自压一条（宿主在"点确认"与"右键单个目标"时走这条）
        clipboard.copy(params("COPIED"));
        clipboard.pushParamUndo(false, "C:/q10/one.mht", null, params("BEFORE-1"));
        clipboard.pushParamUndo(false, null, 3, params("BEFORE-2"));
        clipboard.pushParamUndo(false, null, null, params("BEFORE-GLOBAL"));
        assertEquals(3, pushed.size(), "非批量调用者逐次压栈，粒度不变");
        assertEquals("C:/q10/one.mht", pushed.get(0).paramTargetPath());
        assertEquals(3, pushed.get(1).paramTargetGroupNo());
        assertEquals("BEFORE-GLOBAL", pushed.get(2).paramBefore().getStartMarker());
        assertEquals(0, pushed.get(0).clearItemParamsBefore().size(), "单目标记录的映射载荷为空");
    }

    @Test
    void batchWithNothingAppliedPushesNoRecord() {
        clipboard.copy(params("COPIED"));
        clipboard.applyToSelection(new ArrayList<>(), false);
        assertEquals(0, pushed.size(), "空目标集合不压记录");
    }

    @Test
    void batchWithoutCopiedParamsDoesNothing() {
        PreviewItem x = item("x");
        itemSlots.put(x.path, params("BEFORE-X"));
        clipboard.applyToSelection(new ArrayList<>(List.of(x)), false);
        assertEquals(0, pushed.size(), "未复制过 ⇒ 不写、不压栈");
        assertEquals("BEFORE-X", itemSlots.get(x.path).getStartMarker(), "未复制过 ⇒ 槽位不动");
    }

    // ==================== R7.2/R7.6：判定快照来自 EffectiveParamsService + 剪贴板状态 ====================

    @Test
    void paramsInfoCombinesEffectiveParamsJudgementWithClipboardState() {
        EffectiveParamsService service = new EffectiveParamsService();
        EffectiveParamsService.Context ctx = new EffectiveParamsService.Context(false, null, null);
        PreviewItem a = item("a");
        a.textItemParams = params("SAME");
        PreviewItem b = item("b");
        b.textItemParams = params("SAME");

        ParamsInfo before = clipboard.paramsInfo(List.of(a, b), true, service, ctx, 0, 0);
        assertTrue(before.independent(), "独立参数位由宿主传入");
        assertTrue(before.hasEffective(), "两条目都有生效参数");
        assertTrue(before.uniform(), "签名相同 ⇒ 统一");
        assertFalse(before.copied(), "未复制过 ⇒ copied=false");
        assertTrue(FileMenuModel.stateFor(FileMenuModel.TargetType.TOP_ITEM, before).copyParams());

        clipboard.copy(params("X"));
        ParamsInfo after = clipboard.paramsInfo(List.of(a, b), true, service, ctx, 0, 0);
        assertTrue(after.copied(), "复制后 copied=true");
        assertTrue(FileMenuModel.stateFor(FileMenuModel.TargetType.TOP_ITEM, after).applyToSelection());
        clipboard.clear();
        assertFalse(clipboard.paramsInfo(List.of(a, b), true, service, ctx, 0, 0).copied(), "清空后 copied 回到 false");

        // 混合：a 有参数、b 没有 ⇒ 服务判为"不统一" ⇒ 文案走 mixed 键（null 算一种取值，见服务 javadoc）
        b.textItemParams = null;
        ParamsInfo mixed = clipboard.paramsInfo(List.of(a, b), true, service, ctx, 0, 0);
        assertTrue(mixed.hasEffective());
        assertFalse(mixed.uniform());
        FileMenuModel.State state = FileMenuModel.stateFor(FileMenuModel.TargetType.TOP_ITEM, mixed);
        assertFalse(state.copyParams());
        assertEquals(FileMenuModel.KEY_COPY_MIXED_PARAMS, state.copyParamsTextKey());

        // 全无参数 ⇒ hasEffective=false ⇒ 文案走"无参数"键
        a.textItemParams = null;
        ParamsInfo none = clipboard.paramsInfo(List.of(a, b), false, service, ctx, 0, 0);
        assertFalse(none.hasEffective());
        assertEquals(FileMenuModel.KEY_COPY_NO_PARAMS,
                FileMenuModel.stateFor(FileMenuModel.TargetType.TOP_ITEM, none).copyParamsTextKey());
    }

    /** 装置自检：桩 apply 确实按宿主的形态调用了 pushParamUndo（否则上面的"一条记录"断言可能假通过） */
    @Test
    void harnessSelfCheckApplyIsInvokedPerTarget() {
        List<Object> applied = new ArrayList<>();
        ParamClipboard probe = new ParamClipboard(pushed::add, this::scopeOf,
                (target, imageMode, params) -> applied.add(target));
        probe.copy(params("COPIED"));
        PreviewItem x = item("x");
        probe.applyToSelection(new ArrayList<>(List.of(x, item("y"))), false);
        assertEquals(2, applied.size(), "装置自检：每个目标调用一次应用函数");
        assertEquals(0, pushed.size(), "装置自检：该 apply 不压栈 ⇒ 无记录（本例只验调用次数）");
    }
}
