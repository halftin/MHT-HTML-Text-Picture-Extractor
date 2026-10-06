package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.uiport.Confirmer;
import com.mhtextractor.uiport.ConfirmToken;
import com.mhtextractor.uiport.PortEvent;
import com.mhtextractor.uiport.PortEvent.ParamChangeKind;
import com.mhtextractor.uiport.TargetId;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B4：端口参数命令族（{@code WorkspaceCommands} 的 setGlobalParams/setGroupParams/setItemParams/
 * clearParams/applyGlobalParams）经 {@link InProcessUiport} 的无头单测。
 *
 * <p>用默认构造（{@link InProcessUiport#defaultParamCommands} 自持缓存）断言适配器桥接语义：
 * 命令转发、{@link PortEvent.ParamStateChanged} 经 {@code events()} 送达与退订、
 * 撤销载荷形状（经 {@link WorkspaceModel#popRecord} 读栈顶）、寻址与 no-op。
 * 不复制 {@code WorkspaceParamCommandServiceTest} 的服务级断言。零 JavaFX。</p>
 */
class InProcessUiportParamTest {

    private static PreviewItem item(String name) {
        return new PreviewItem("C:/x/" + name, name, LocalDateTime.now(), "C:/x");
    }

    private static AppConfig params(String marker) {
        AppConfig c = new AppConfig();
        c.setStartMarker(marker);
        return c;
    }

    /** 桩确认器：本测试不触确认卡片语义。 */
    private static Confirmer stubConfirmer() {
        return request -> new ConfirmToken() {
            @Override
            public void answer(String actionKey) {
            }

            @Override
            public void cancel() {
            }
        };
    }

    private static PortEvent.ParamStateChanged lastParamEvent(List<PortEvent> events) {
        PortEvent last = events.get(events.size() - 1);
        assertTrue(last instanceof PortEvent.ParamStateChanged, "最后一条事件应为参数状态变更，实际：" + last);
        return (PortEvent.ParamStateChanged) last;
    }

    @Test
    void setGlobalParamsThroughPortEmitsEventAndPushesParamSet() {
        WorkspaceModel model = new WorkspaceModel();
        InProcessUiport uiport = new InProcessUiport(model, stubConfirmer());
        PreviewItem a = item("a.mht");
        model.addItems(List.of(a));
        List<PortEvent> events = new ArrayList<>();
        uiport.events().subscribe(events::add);

        uiport.commands().setGlobalParams(false, params("AAA"));

        PortEvent.ParamStateChanged ev = lastParamEvent(events);
        assertEquals(ParamChangeKind.SET_GLOBAL, ev.kind());
        assertEquals(List.of(a.path), ev.affectedPaths());
        UndoManager.Record<PreviewItem> record = model.popRecord();
        assertEquals(UndoManager.RecordType.PARAM_SET, record.type());
        assertNull(record.paramTargetPath());
        assertNull(record.paramTargetGroupNo());
    }

    @Test
    void setItemParamsThroughPortWritesItemAndEmitsSetItem() {
        WorkspaceModel model = new WorkspaceModel();
        InProcessUiport uiport = new InProcessUiport(model, stubConfirmer());
        PreviewItem a = item("a.mht"), b = item("b.mht");
        model.addItems(List.of(a, b));
        List<PortEvent> events = new ArrayList<>();
        uiport.events().subscribe(events::add);

        uiport.commands().setItemParams(a.path, true, params("I"));

        assertEquals("I", a.imageItemParams.getStartMarker());
        assertNull(b.imageItemParams, "未寻址条目不受影响");
        PortEvent.ParamStateChanged ev = lastParamEvent(events);
        assertEquals(ParamChangeKind.SET_ITEM, ev.kind());
        assertTrue(ev.imageMode());
        assertEquals(List.of(a.path), ev.affectedPaths());
        assertEquals(a.path, model.popRecord().paramTargetPath(), "撤销载荷按路径寻址");
    }

    @Test
    void setGroupParamsThroughPortUsesGroupNumberAddressing() {
        WorkspaceModel model = new WorkspaceModel();
        InProcessUiport uiport = new InProcessUiport(model, stubConfirmer());
        PreviewItem a = item("a.mht"), b = item("b.mht");
        model.addItems(List.of(a, b));
        GroupItem<PreviewItem> g = model.group(List.of(a, b), "组");
        List<PortEvent> events = new ArrayList<>();
        uiport.events().subscribe(events::add);

        uiport.commands().setGroupParams(g.getGroupNo(), false, params("AAA"));

        assertEquals("AAA", g.getTextParamsCache().getStartMarker());
        PortEvent.ParamStateChanged ev = lastParamEvent(events);
        assertEquals(ParamChangeKind.SET_GROUP, ev.kind());
        assertEquals(List.of(a.path, b.path), ev.affectedPaths());
        assertEquals(g.getGroupNo(), model.popRecord().paramTargetGroupNo(), "撤销载荷按组号寻址");
    }

    @Test
    void clearParamsThroughPortResolvesCheckedFirstFromModel() {
        WorkspaceModel model = new WorkspaceModel();
        InProcessUiport uiport = new InProcessUiport(model, stubConfirmer());
        PreviewItem checked = item("checked.mht"), fallback = item("fallback.mht");
        model.addItems(List.of(checked, fallback));
        checked.setSelected(true);
        checked.textItemParams = params("AAA");
        fallback.textItemParams = params("BBB");
        List<PortEvent> events = new ArrayList<>();
        uiport.events().subscribe(events::add);

        uiport.commands().clearParams(List.of(TargetId.ofItem(fallback.path)), false);

        assertNull(checked.textItemParams, "勾选优先：后端按模型勾选事实解析（回退目标被忽略）");
        assertEquals("BBB", fallback.textItemParams.getStartMarker());
        assertEquals(ParamChangeKind.CLEAR, lastParamEvent(events).kind());
        UndoManager.Record<PreviewItem> record = model.popRecord();
        assertEquals(UndoManager.RecordType.CLEAR_PARAMS, record.type());
        assertNotNull(record.clearItemParamsBefore().get(checked.path));
        assertNull(record.clearItemParamsBefore().get(fallback.path), "before 快照只收录实际清除的目标");
    }

    @Test
    void applyGlobalParamsThroughPortUsesTargetAddressingAndFullSnapshot() {
        WorkspaceModel model = new WorkspaceModel();
        InProcessUiport uiport = new InProcessUiport(model, stubConfirmer());
        PreviewItem a = item("a.mht"), b = item("b.mht");
        model.addItems(List.of(a, b));
        a.textItemParams = params("AAA");
        List<PortEvent> events = new ArrayList<>();
        uiport.events().subscribe(events::add);

        uiport.commands().applyGlobalParams(TargetId.ofItem(a.path), false);

        assertNull(a.textItemParams, "应用后条目级参数清空");
        assertEquals(ParamChangeKind.APPLY_GLOBAL, lastParamEvent(events).kind());
        UndoManager.Record<PreviewItem> record = model.popRecord();
        assertEquals(UndoManager.RecordType.APPLY_GLOBAL, record.type());
        assertEquals("AAA", record.applyGlobalItemParamsBefore().get(a.path).getStartMarker());

        // 第二次应用到全局（目标无独立参数）：生效参数 = 上次写入的全局缓存 —— 证明默认服务缓存确实写入
        uiport.commands().applyGlobalParams(TargetId.ofItem(b.path), false);
        UndoManager.Record<PreviewItem> second = model.popRecord();
        assertEquals("AAA", second.applyGlobalBefore().getStartMarker(),
                "第二次应用的 before 快照 = 第一次写入的全局参数（默认服务缓存生效）");
    }

    @Test
    void unknownAddressesAreSilentNoOpsThroughPort() {
        WorkspaceModel model = new WorkspaceModel();
        InProcessUiport uiport = new InProcessUiport(model, stubConfirmer());
        model.addItems(List.of(item("a.mht")));
        List<PortEvent> events = new ArrayList<>();
        uiport.events().subscribe(events::add);
        int undoDepth = model.undoDepth();

        uiport.commands().setItemParams("C:/x/ghost.mht", false, params("AAA"));
        uiport.commands().setGroupParams(9999, false, params("AAA"));
        uiport.commands().applyGlobalParams(TargetId.ofItem("C:/x/ghost.mht"), false);

        assertTrue(events.isEmpty(), "控制组：全部 no-op 均不发布事件");
        assertEquals(undoDepth, model.undoDepth(), "控制组：全部 no-op 均不压撤销记录");
    }

    @Test
    void paramEventsUnsubscribeWithEventsHandle() throws Exception {
        WorkspaceModel model = new WorkspaceModel();
        InProcessUiport uiport = new InProcessUiport(model, stubConfirmer());
        model.addItems(List.of(item("a.mht")));
        List<PortEvent> events = new ArrayList<>();
        AutoCloseable subscription = uiport.events().subscribe(events::add);

        uiport.commands().setGlobalParams(false, params("AAA"));
        assertEquals(1, events.size());

        subscription.close();
        subscription.close();   // 幂等
        uiport.commands().setGlobalParams(false, params("BBB"));
        assertEquals(1, events.size(), "控制组：close() 退订后参数命令不再送达事件");
    }

    @Test
    void targetIdRequiresExactlyOneAddress() {
        assertThrows(IllegalArgumentException.class, () -> new TargetId(null, null),
                "控制组：双空寻址必须被拒绝");
        assertThrows(IllegalArgumentException.class, () -> new TargetId("C:/x/a.mht", 1L),
                "控制组：双非空寻址必须被拒绝");
        assertEquals("C:/x/a.mht", TargetId.ofItem("C:/x/a.mht").path());
        assertEquals(7L, TargetId.ofGroup(7).groupNo());
    }
}
