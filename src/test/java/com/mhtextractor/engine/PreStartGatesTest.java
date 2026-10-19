package com.mhtextractor.engine;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.OutputUnitPlanner;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产顺序门链测试（P1-5）：{@link PreStartGates#productionChain} 的<b>顺序与判定规则</b>的可观察证据。
 *
 * <p>三道门（草稿门 → 未命名组门 → 合并名门）从 {@code ExtractionTaskCoordinator} 原样下沉到 app 层；
 * 本测试用桩上下文串行驱动生产链，断言：① 草稿门第一道且阻断即中止（后续门不被询问）；
 * ② 未命名组门先于合并名门，确定后继续；③ 合并名门取消同样中止；④ 全通过到达链尾；
 * ⑤⑥ 各门判定规则逐条（未命名组名单 = 参与组的默认名组；合并名三条件全满足才询问）。</p>
 *
 * <p>零 JavaFX：本类只碰 {@code app} + {@code util} + {@code model}，可无头执行。</p>
 */
class PreStartGatesTest {

    /** 桩条目视图（与 {@code ExtractionTaskCoordinatorGateTest} 同款）。 */
    private static final OutputUnitPlanner.ItemView<PreviewItem> VIEW = new OutputUnitPlanner.ItemView<>() {
        @Override public String path(PreviewItem item) { return item.path; }
        @Override public String sourceFolder(PreviewItem item) { return item.sourceFolder; }
        @Override public boolean removed(PreviewItem item) { return item.removed; }
        @Override public AppConfig textItemParams(PreviewItem item) { return null; }
        @Override public AppConfig imageItemParams(PreviewItem item) { return null; }
    };

    /**
     * 可配置事实 + 记录展示回调的桩上下文：展示回调先记名，再按预置答案（{@code unnamedAnswer} /
     * {@code mergedAnswer}）交付 —— 与真实异步确认卡在 UI 回调里交付 proceed 等价。
     */
    private static final class StubCtx implements PreStartGateContext {

        List<Object> dataOrder = new ArrayList<>();
        boolean imageMode;
        boolean extractCheckedOnly;
        int visibleCheckedCount;
        long visibleCount;
        boolean draftDirty;
        boolean unnamedAnswer = true;
        boolean mergedAnswer = true;

        final List<String> events = new ArrayList<>();
        List<String> unnamedNames;

        @Override public List<Object> dataOrder() { return dataOrder; }
        @Override public OutputUnitPlanner.ItemView<PreviewItem> itemView() { return VIEW; }
        @Override public boolean imageMode() { return imageMode; }
        @Override public AppConfig textParamsCache() { return null; }
        @Override public AppConfig imageParamsCache() { return null; }
        @Override public String outputFolder() { return "out"; }
        @Override public boolean extractCheckedOnly() { return extractCheckedOnly; }
        @Override public int visibleCheckedCount() { return visibleCheckedCount; }
        @Override public long visibleCount() { return visibleCount; }
        @Override public boolean draftDirty() { return draftDirty; }

        @Override
        public void confirmUnnamedGroups(List<String> names, Consumer<Boolean> proceed) {
            events.add("unnamed:" + names);
            unnamedNames = List.copyOf(names);
            proceed.accept(unnamedAnswer);
        }

        @Override
        public void confirmSubsetMergedName(Consumer<Boolean> proceed) {
            events.add("merged");
            proceed.accept(mergedAnswer);
        }

        @Override
        public void draftBlocksStart() {
            events.add("draft");
        }
    }

    private static PreviewItem item(String name, boolean selected) {
        PreviewItem it = new PreviewItem("C:/x/" + name, name, LocalDateTime.now(), "C:/x");
        it.selected = selected;
        return it;
    }

    private static GroupItem<PreviewItem> group(int no, String name) {
        return new GroupItem<>(no, name);
    }

    /**
     * 串行驱动（{@code runPreStartGates} 同款语义）：从链首问到链尾；任一门交付 false 即中止
     * （后续门不再询问）。返回是否到达链尾（全放行）。
     */
    private static boolean runSerial(List<PreStartGate> chain, StubCtx ctx) {
        boolean[] finished = {false};
        Consumer<Boolean> go = new Consumer<>() {
            int index = 0;

            @Override
            public void accept(Boolean ok) {
                if (!ok) {
                    return;
                }
                if (index >= chain.size()) {
                    finished[0] = true;
                    return;
                }
                chain.get(index++).ask(this);
            }
        };
        go.accept(true);
        return finished[0];
    }

    // ==================== 链顺序 ====================

    /** ① 草稿门必须第一道：草稿脏 + 未命名组 + 合并条件同时成立时，只弹草稿卡且链中止（后续门不被询问）。 */
    @Test
    void draftGateIsFirstAndBlocksTheChain() {
        StubCtx ctx = new StubCtx();
        ctx.draftDirty = true;
        GroupItem<PreviewItem> g = group(1, "组1");
        g.setDefaultNamed(true);
        g.getMembers().add(item("a.mht", true));
        ctx.dataOrder.add(g);
        ctx.extractCheckedOnly = true;
        ctx.visibleCheckedCount = 1;
        ctx.visibleCount = 2;

        boolean finished = runSerial(PreStartGates.productionChain(ctx), ctx);

        assertEquals(List.of("draft"), ctx.events,
                "草稿卡必须先于未命名组卡与合并名卡，且阻断后后续门不得被询问");
        assertFalse(finished, "草稿阻断不得到达链尾");
    }

    /** ② 未命名组门先于合并名门：草稿干净时先弹未命名组卡，确定后继续到合并名门。 */
    @Test
    void unnamedGatePrecedesMergedNameGateAndContinuesOnConfirm() {
        StubCtx ctx = new StubCtx();
        GroupItem<PreviewItem> g = group(1, "组1");
        g.setDefaultNamed(true);
        g.getMembers().add(item("a.mht", true));
        ctx.dataOrder.add(g);
        ctx.extractCheckedOnly = true;
        ctx.visibleCheckedCount = 1;
        ctx.visibleCount = 2;

        boolean finished = runSerial(PreStartGates.productionChain(ctx), ctx);

        assertEquals(List.of("unnamed:[组1]", "merged"), ctx.events,
                "未命名组卡必须先于合并名卡；确定后继续询问下一道门");
        assertTrue(finished, "两道门都放行应到达链尾");
    }

    /** ③ 合并名门取消同样中止：未命名组放行后，合并名卡取消 → 链中止。 */
    @Test
    void mergedNameCancelAbortsAfterUnnamedPasses() {
        StubCtx ctx = new StubCtx();
        ctx.mergedAnswer = false;
        GroupItem<PreviewItem> g = group(1, "组1");
        g.setDefaultNamed(true);
        g.getMembers().add(item("a.mht", true));
        ctx.dataOrder.add(g);
        ctx.extractCheckedOnly = true;
        ctx.visibleCheckedCount = 1;
        ctx.visibleCount = 2;

        boolean finished = runSerial(PreStartGates.productionChain(ctx), ctx);

        assertEquals(List.of("unnamed:[组1]", "merged"), ctx.events, "询问顺序不变");
        assertFalse(finished, "合并名卡取消不得到达链尾");
    }

    /** ④ 无可拦截事实时三道门全部放行（零展示回调）。 */
    @Test
    void allGatesPassWhenNothingToFlag() {
        StubCtx ctx = new StubCtx();

        boolean finished = runSerial(PreStartGates.productionChain(ctx), ctx);

        assertTrue(finished, "无草稿、无未命名组、开关关闭：全链放行");
        assertEquals(List.of(), ctx.events, "放行路径不得产生任何展示回调");
    }

    // ==================== 门规则 ====================

    /** ⑤ 未命名组门只报「过滤后仍会产生单元的顶层组」里的默认名组（开关打开时按勾选过滤）。 */
    @Test
    void unnamedGateFiltersToParticipatingDefaultNamedGroups() {
        // 开关打开：a 默认名且有勾选成员 → 报；b 默认名但成员未勾选 → 被过滤；c 已改名 → 不报
        StubCtx checked = new StubCtx();
        checked.extractCheckedOnly = true;
        checked.dataOrder.add(defaultNamedGroup(1, "组1", true));
        checked.dataOrder.add(defaultNamedGroup(2, "组2", false));
        checked.dataOrder.add(renamedGroup(3, "我的组"));
        PreStartGates.productionChain(checked).get(1).ask(ok -> { });
        assertEquals(List.of("组1"), checked.unnamedNames, "开关打开时只报参与组里的默认名组");

        // 开关关闭：participatingGroups 原样返回 → 两个默认名组都报
        StubCtx off = new StubCtx();
        off.dataOrder.add(defaultNamedGroup(1, "组1", false));
        off.dataOrder.add(defaultNamedGroup(2, "组2", true));
        off.dataOrder.add(renamedGroup(3, "我的组"));
        PreStartGates.productionChain(off).get(1).ask(ok -> { });
        assertEquals(List.of("组1", "组2"), off.unnamedNames, "开关关闭时全部默认名组都报（现状）");
    }

    /** ⑥ 合并名门三条件全满足才询问：开关打开 + 参与项数 &lt; 可见项数 + 本次存在合并单元。 */
    @Test
    void mergedNameGateAsksOnlyWhenAllThreeConditionsHold() {
        // 条件①不满足：开关关闭 → 放行
        StubCtx off = new StubCtx();
        off.extractCheckedOnly = false;
        PreStartGates.productionChain(off).get(2).ask(ok -> assertTrue(ok));
        assertEquals(List.of(), off.events, "开关关闭不得询问");

        // 条件②不满足：参与项数 = 可见项数 → 放行
        StubCtx all = new StubCtx();
        all.extractCheckedOnly = true;
        all.visibleCheckedCount = 2;
        all.visibleCount = 2;
        PreStartGates.productionChain(all).get(2).ask(ok -> assertTrue(ok));
        assertEquals(List.of(), all.events, "没有未参与项不得询问");

        // 条件③不满足：无合并单元（空数据序 → 无单元）→ 放行
        StubCtx none = new StubCtx();
        none.extractCheckedOnly = true;
        none.visibleCheckedCount = 1;
        none.visibleCount = 2;
        PreStartGates.productionChain(none).get(2).ask(ok -> assertTrue(ok));
        assertEquals(List.of(), none.events, "无合并单元不得询问");

        // 三条件全满足（数据序含组 → 组单元即合并单元）→ 询问
        StubCtx hit = new StubCtx();
        hit.extractCheckedOnly = true;
        hit.visibleCheckedCount = 1;
        hit.visibleCount = 2;
        GroupItem<PreviewItem> g = group(1, "组1");
        g.getMembers().add(item("a.mht", true));
        hit.dataOrder.add(g);
        PreStartGates.productionChain(hit).get(2).ask(ok -> { });
        assertEquals(List.of("merged"), hit.events, "三条件全满足必须询问合并名卡");
    }

    private static GroupItem<PreviewItem> defaultNamedGroup(int no, String name, boolean memberSelected) {
        GroupItem<PreviewItem> g = group(no, name);
        g.setDefaultNamed(true);
        g.getMembers().add(item("m" + no + ".mht", memberSelected));
        return g;
    }

    private static GroupItem<PreviewItem> renamedGroup(int no, String name) {
        GroupItem<PreviewItem> g = group(no, name);
        g.setDefaultNamed(false);
        g.getMembers().add(item("m" + no + ".mht", true));
        return g;
    }
}
