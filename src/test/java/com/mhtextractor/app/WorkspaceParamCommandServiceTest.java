package com.mhtextractor.app;

import com.mhtextractor.engine.PreviewSession;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.uiport.PortEvent;
import com.mhtextractor.uiport.PortEvent.ParamChangeKind;
import com.mhtextractor.uiport.TargetId;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B4（处置项 3 / 4）：参数命令服务（{@link WorkspaceParamCommandService}）的无头单测。
 *
 * <p>用真实 {@link WorkspaceModel} + 共享 {@link GlobalParameterCache} 断言
 * 「写入 + 撤销压栈 + 事件发布」三段语义与搬移前逐字一致：
 * 三层覆盖（全局 / 组 / 条目）、{@code imageMode} 分支、撤销载荷形状
 * （PARAM_SET / CLEAR_PARAMS / APPLY_GLOBAL）、勾选优先、no-op 不发布不压栈。
 * 零 JavaFX。</p>
 */
class WorkspaceParamCommandServiceTest {

    private static PreviewItem item(String name) {
        return new PreviewItem("C:/x/" + name, name, LocalDateTime.now(), "C:/x");
    }

    private static AppConfig params(String marker) {
        AppConfig c = new AppConfig();
        c.setStartMarker(marker);
        return c;
    }

    /** 测试夹具：模型 + 共享全局缓存 + 服务 + 事件收集器。 */
    private record Fixture(WorkspaceModel model, GlobalParameterCache cache,
                           WorkspaceParamCommandService service, List<PortEvent> events) {
    }

    private static Fixture fixture() {
        WorkspaceModel model = new WorkspaceModel();
        GlobalParameterCache cache = new GlobalParameterCache();
        WorkspaceParamService cacheService = new WorkspaceParamService(PreviewSession.create().lifecycle());
        ParamClipboard clipboard = new ParamClipboard(model::push,
                target -> com.mhtextractor.model.ParamScope.GLOBAL, (t, im, p) -> { });
        List<PortEvent> events = new ArrayList<>();
        WorkspaceParamCommandService service = new WorkspaceParamCommandService(
                new WorkspaceParamCommandService.Context() {
                    public List<PreviewItem> allItems() { return model.allItems(); }
                    public List<Object> dataOrder() { return model.topLevel(); }
                    public void pushUndo(UndoManager.Record<PreviewItem> record) { model.push(record); }
                    public void pushParamUndo(boolean imageMode, String path, Integer groupNo, AppConfig before) {
                        clipboard.pushParamUndo(imageMode, path, groupNo, before);
                    }
                    public WorkspaceParamService cacheService() { return cacheService; }
                    public GlobalParameterCache globalParams() { return cache; }
                });
        service.subscribe(events::add);
        return new Fixture(model, cache, service, events);
    }

    private static PortEvent.ParamStateChanged lastParamEvent(Fixture f) {
        PortEvent last = f.events().get(f.events().size() - 1);
        assertTrue(last instanceof PortEvent.ParamStateChanged,
                "最后一条事件应为参数状态变更，实际：" + last);
        return (PortEvent.ParamStateChanged) last;
    }

    @Test
    void groupUndoRepairsMembershipBeforeParameterClearWithoutAGui() {
        Fixture f=fixture();
        new InProcessUiport(f.model(),request->null);
        PreviewItem a=item("a.mht"),b=item("b.mht");
        f.model().addItems(List.of(a,b));
        assertNotNull(f.model().group(List.of(a,b)));
        f.model().addListener(change->{
            if(change.kind()==WorkspaceChange.Kind.UNDO_APPLIED){
                assertNull(a.parentGroup,"Undo event must expose canonical top-level membership");
                assertNull(b.parentGroup);
            }
        });
        assertTrue(f.model().undo());
        AppConfig config=params("after-group-undo");
        f.service().setItemParams(a.path,false,config);
        f.model().setChecked(List.of(a),true);
        f.service().clearParams(List.of(TargetId.ofItem(b.path)),false);
        assertNull(a.textItemParams,"Checked top-level item must clear after undoing its old group");
        assertEquals(ParamChangeKind.CLEAR,lastParamEvent(f).kind());
        assertTrue(f.model().undo(f.service()));
        assertEquals(config,a.textItemParams,"Parameter undo must restore the same top-level target");
        assertNull(a.parentGroup);
    }

    // ==================== setGlobalParams（原 applyConfirmedParams） ====================

    @Test
    void setGlobalParamsWritesConfirmedCachePushesParamSetAndEmitsAllPaths() {
        Fixture f = fixture();
        PreviewItem a = item("a.mht"), b = item("b.mht");
        f.model().addItems(List.of(a, b));
        AppConfig p = params("AAA");

        f.service().setGlobalParams(false, p);

        assertEquals(p, f.cache().text(), "文本模式全局缓存整体替换为入参引用");
        assertNull(f.cache().image(), "另一模式缓存不受影响");
        assertEquals(2, f.model().undoDepth(), "addItems 1 条 + PARAM_SET 1 条");
        UndoManager.Record<PreviewItem> record = f.model().popRecord();
        assertEquals(UndoManager.RecordType.PARAM_SET, record.type());
        assertFalse(record.paramImageMode(), "文本模式：paramImageMode = false");
        assertNull(record.paramTargetPath(), "全局作用域：paramTargetPath = null");
        assertNull(record.paramTargetGroupNo(), "全局作用域：paramTargetGroupNo = null");
        assertNull(record.paramBefore(), "应用前该模式未确认 → paramBefore = null");
        PortEvent.ParamStateChanged ev = lastParamEvent(f);
        assertEquals(ParamChangeKind.SET_GLOBAL, ev.kind());
        assertEquals(List.of(a.path, b.path), ev.affectedPaths(), "全局类事件：全部条目路径（含已移除）");
    }

    @Test
    void setGlobalParamsImageBranchLeavesTextUntouchedAndSnapshotsBefore() {
        Fixture f = fixture();
        AppConfig text = params("T");
        f.cache().set(false, text);
        AppConfig priorImage = params("I0");
        f.cache().set(true, priorImage);
        AppConfig image = params("I");

        f.service().setGlobalParams(true, image);

        assertEquals(image, f.cache().image(), "图片模式写图片缓存");
        assertEquals(text, f.cache().text(), "控制组语义：文本缓存不受图片模式写入影响");
        UndoManager.Record<PreviewItem> record = f.model().popRecord();
        assertTrue(record.paramImageMode());
        assertEquals(priorImage, record.paramBefore(), "撤销载荷 = 应用前该模式（图片）全局参数");
        assertTrue(lastParamEvent(f).imageMode(), "事件携带 imageMode 供前端渲染模式词");
    }

    // ==================== setItemParams（原 applyItemParams） ====================

    @Test
    void setItemParamsWritesItemLevelPushesParamSetAndEmitsSinglePath() {
        Fixture f = fixture();
        PreviewItem a = item("a.mht");
        f.model().addItems(List.of(a));
        AppConfig p = params("AAA");

        f.service().setItemParams(a.path, false, p);

        assertEquals(p, a.textItemParams, "条目级参数写入（文本模式）");
        assertNull(a.imageItemParams, "另一模式条目级参数不受影响");
        assertNull(f.cache().text(), "不写全局缓存");
        UndoManager.Record<PreviewItem> record = f.model().popRecord();
        assertEquals(UndoManager.RecordType.PARAM_SET, record.type());
        assertEquals(a.path, record.paramTargetPath(), "撤销载荷按路径寻址");
        assertNull(record.paramTargetGroupNo());
        assertNull(record.paramBefore(), "应用前无条目级参数 → paramBefore = null");
        PortEvent.ParamStateChanged ev = lastParamEvent(f);
        assertEquals(ParamChangeKind.SET_ITEM, ev.kind());
        assertEquals(List.of(a.path), ev.affectedPaths());
    }

    @Test
    void setItemParamsUnknownPathIsSilentNoOp() {
        Fixture f = fixture();
        PreviewItem a = item("a.mht");
        f.model().addItems(List.of(a));
        int before = f.events().size();

        f.service().setItemParams("C:/x/ghost.mht", false, params("AAA"));

        assertEquals(before, f.events().size(), "控制组：未知路径不发布事件");
        assertEquals(1, f.model().undoDepth(), "控制组：未知路径不压撤销记录（仍只有 addItems 的 1 条）");
        assertNull(a.textItemParams);
    }

    @Test
    void setItemParamsImageBranchWritesImageItemParamsOnly() {
        Fixture f = fixture();
        PreviewItem a = item("a.mht");
        f.model().addItems(List.of(a));
        AppConfig text = params("T");
        a.textItemParams = text;

        f.service().setItemParams(a.path, true, params("I"));

        assertEquals(text, a.textItemParams, "控制组语义：图片模式写入不动文本条目级参数");
        assertEquals("I", a.imageItemParams.getStartMarker());
        assertTrue(f.model().popRecord().paramImageMode());
    }

    // ==================== setGroupParams（原 applyGroupParams） ====================

    @Test
    void setGroupParamsWritesGroupCachePushesParamSetAndEmitsMemberPaths() {
        Fixture f = fixture();
        PreviewItem a = item("a.mht"), b = item("b.mht");
        f.model().addItems(List.of(a, b));
        GroupItem<PreviewItem> g = f.model().group(List.of(a, b), "组");
        AppConfig p = params("AAA");

        f.service().setGroupParams(g.getGroupNo(), false, p);

        assertEquals(p, g.getTextParamsCache(), "组参数写入（文本模式）");
        assertNull(g.getImageParamsCache(), "另一模式组参数不受影响");
        assertNull(f.cache().text(), "组参数不写全局缓存");
        UndoManager.Record<PreviewItem> record = f.model().popRecord();
        assertEquals(UndoManager.RecordType.PARAM_SET, record.type());
        assertNull(record.paramTargetPath());
        assertEquals(g.getGroupNo(), record.paramTargetGroupNo(), "撤销载荷按组号寻址");
        assertNull(record.paramBefore(), "应用前组无参数 → paramBefore = null");
        PortEvent.ParamStateChanged ev = lastParamEvent(f);
        assertEquals(ParamChangeKind.SET_GROUP, ev.kind());
        assertEquals(List.of(a.path, b.path), ev.affectedPaths(), "组成员路径（未移除成员）");
    }

    @Test
    void setGroupParamsUnknownGroupNoIsSilentNoOp() {
        Fixture f = fixture();
        PreviewItem a = item("a.mht");
        f.model().addItems(List.of(a));
        int before = f.events().size();

        f.service().setGroupParams(9999, false, params("AAA"));

        assertEquals(before, f.events().size(), "控制组：未知组号不发布事件");
        assertEquals(1, f.model().undoDepth(), "控制组：未知组号不压撤销记录");
    }

    // ==================== clearParams（原 clearParamsForTargets + resolveParamTargets） ====================

    @Test
    void clearParamsCheckedItemsWinOverFallbackAndPushSingleRecord() {
        Fixture f = fixture();
        PreviewItem checked = item("checked.mht"), fallback = item("fallback.mht");
        f.model().addItems(List.of(checked, fallback));
        checked.setSelected(true);
        AppConfig p = params("AAA");
        checked.textItemParams = p;
        fallback.textItemParams = p;

        f.service().clearParams(List.of(TargetId.ofItem(fallback.path)), false);

        assertNull(checked.textItemParams, "勾选优先：勾选条目被清除");
        assertEquals(p, fallback.textItemParams, "勾选优先：回退目标被忽略（保持原参数）");
        assertEquals(2, f.model().undoDepth(), "addItems 1 条 + CLEAR_PARAMS 恰好 1 条（单条撤销）");
        UndoManager.Record<PreviewItem> record = f.model().popRecord();
        assertEquals(UndoManager.RecordType.CLEAR_PARAMS, record.type());
        assertEquals(p, record.clearItemParamsBefore().get(checked.path), "before 快照 = 清除前该模式条目级参数");
        PortEvent.ParamStateChanged ev = lastParamEvent(f);
        assertEquals(ParamChangeKind.CLEAR, ev.kind());
        assertEquals(List.of(checked.path), ev.affectedPaths());
    }

    @Test
    void clearParamsFallbackUsedWhenNothingChecked() {
        Fixture f = fixture();
        PreviewItem a = item("a.mht"), b = item("b.mht");
        f.model().addItems(List.of(a, b));
        a.textItemParams = params("AAA");

        f.service().clearParams(List.of(TargetId.ofItem(a.path)), false);

        assertNull(a.textItemParams, "无勾选 → 回退目标被清除");
        assertEquals(2, f.model().undoDepth(), "单条 CLEAR_PARAMS 撤销记录");
    }

    @Test
    void clearParamsGroupMemberClearsWholeGroupParamsWithSingleUndo() {
        Fixture f = fixture();
        PreviewItem a = item("a.mht"), b = item("b.mht");
        f.model().addItems(List.of(a, b));
        GroupItem<PreviewItem> g = f.model().group(List.of(a, b), "组");
        AppConfig gp = params("GGG");
        g.setTextParamsCache(gp);
        a.textItemParams = params("AAA");   // 组内成员条目级参数：清除目标是整组组参数，条目级不参与

        f.service().clearParams(List.of(TargetId.ofItem(a.path)), false);

        assertNull(g.getTextParamsCache(), "组内成员 → 清整组组参数");
        assertEquals("AAA", a.textItemParams.getStartMarker(),
                "组内成员的条目级参数不参与清除（清除口径 = 组参数）");
        UndoManager.Record<PreviewItem> record = f.model().popRecord();
        assertEquals(UndoManager.RecordType.CLEAR_PARAMS, record.type());
        assertEquals(gp, record.clearGroupParamsBefore().get(g.getGroupNo()), "before 快照 = 清除前组参数");
        PortEvent.ParamStateChanged ev = lastParamEvent(f);
        assertEquals(List.of(a.path, b.path), ev.affectedPaths(), "受影响 = 组内未移除成员");
        assertEquals(2, f.model().undoDepth(), "GROUP 1 条 + CLEAR_PARAMS 1 条（无多余记录）");
    }

    @Test
    void clearParamsWithoutParamTargetsIsSilentNoOp() {
        Fixture f = fixture();
        PreviewItem a = item("a.mht");
        f.model().addItems(List.of(a));
        int before = f.events().size();

        f.service().clearParams(List.of(TargetId.ofItem(a.path)), false);

        assertEquals(before, f.events().size(), "控制组：无参数目标自然跳过 → 不发布事件");
        assertEquals(1, f.model().undoDepth(), "控制组：无参数目标不压撤销记录");
    }

    @Test
    void clearParamsImageModeClearsOnlyImageParams() {
        Fixture f = fixture();
        PreviewItem a = item("a.mht");
        f.model().addItems(List.of(a));
        a.textItemParams = params("T");
        a.imageItemParams = params("I");

        f.service().clearParams(List.of(TargetId.ofItem(a.path)), true);

        assertNull(a.imageItemParams, "图片模式清除条目级图片参数");
        assertEquals("T", a.textItemParams.getStartMarker(), "控制组语义：文本参数不受图片模式清除影响");
        assertTrue(f.model().popRecord().clearImageMode());
        assertTrue(lastParamEvent(f).imageMode());
    }

    // ==================== applyGlobalParams（原 applyGlobalParamsTo） ====================

    @Test
    void applyGlobalParamsItemTargetPushesApplyGlobalAndClearsAllIndependent() {
        Fixture f = fixture();
        PreviewItem a = item("a.mht"), b = item("b.mht"), c = item("c.mht");
        f.model().addItems(List.of(a, b));
        f.model().addItems(List.of(c));   // 追加（含已移除场景不在此测）
        GroupItem<PreviewItem> g = f.model().group(List.of(b, c), "组");
        AppConfig pa = params("AAA");
        a.textItemParams = pa;
        AppConfig pg = params("GGG");
        g.setTextParamsCache(pg);
        AppConfig globalBefore = params("OLD");
        f.cache().set(false, globalBefore);

        f.service().applyGlobalParams(TargetId.ofItem(a.path), false);

        assertEquals(pa, f.cache().text(), "目标生效参数（条目级）写入当前模式全局缓存");
        assertNull(a.textItemParams, "全部条目级参数清空（含目标自身）");
        assertNull(g.getTextParamsCache(), "全部组参数清空");
        assertNull(f.cache().image(), "另一模式独立参数/全局不受影响");
        UndoManager.Record<PreviewItem> record = f.model().popRecord();
        assertEquals(UndoManager.RecordType.APPLY_GLOBAL, record.type());
        assertEquals(globalBefore, record.applyGlobalBefore(), "撤销载荷 = 应用前全局参数");
        assertEquals(pa, record.applyGlobalItemParamsBefore().get(a.path), "撤销载荷 = 应用前条目级快照");
        assertEquals(pg, record.applyGlobalGroupParamsBefore().get(g.getGroupNo()), "撤销载荷 = 应用前组参数快照");
        PortEvent.ParamStateChanged ev = lastParamEvent(f);
        assertEquals(ParamChangeKind.APPLY_GLOBAL, ev.kind());
        assertEquals(List.of(a.path, b.path, c.path), ev.affectedPaths(), "全部条目路径");
    }

    @Test
    void applyGlobalParamsGroupTargetUsesGroupEffectiveParams() {
        Fixture f = fixture();
        PreviewItem a = item("a.mht"), b = item("b.mht");
        f.model().addItems(List.of(a, b));
        GroupItem<PreviewItem> g = f.model().group(List.of(a, b), "组");
        AppConfig pg = params("GGG");
        g.setTextParamsCache(pg);

        f.service().applyGlobalParams(TargetId.ofGroup(g.getGroupNo()), false);

        assertEquals(pg, f.cache().text(), "组目标 → 组参数成为新全局");
        assertNull(g.getTextParamsCache(), "目标自身组参数也清空（全部条目继承新全局）");
        assertNull(a.textItemParams);
        assertNull(b.textItemParams);
    }

    @Test
    void applyGlobalParamsTargetWithoutEffectiveParamsIsSilentNoOp() {
        Fixture f = fixture();
        PreviewItem a = item("a.mht");
        f.model().addItems(List.of(a));
        int before = f.events().size();

        f.service().applyGlobalParams(TargetId.ofItem(a.path), false);

        assertEquals(before, f.events().size(), "控制组：目标无生效参数（全局未确认且无独立参数）→ 不发布事件");
        assertEquals(1, f.model().undoDepth(), "控制组：不压撤销记录");
        assertNull(f.cache().text());
    }

    // ==================== 事件退订 ====================

    @Test
    void subscriptionCloseStopsDelivery() throws Exception {
        Fixture f = fixture();
        PreviewItem a = item("a.mht");
        f.model().addItems(List.of(a));
        List<PortEvent> second = new ArrayList<>();
        AutoCloseable sub = f.service().subscribe(second::add);

        f.service().setGlobalParams(false, params("AAA"));
        assertEquals(1, second.size(), "订阅方收到参数事件");

        sub.close();
        sub.close();   // 幂等
        f.service().setGlobalParams(false, params("BBB"));
        assertEquals(1, second.size(), "控制组：退订后不再收到事件");
    }
}
