package com.mhtextractor.engine;

import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.GroupStructure;
import com.mhtextractor.util.OutputUnitPlanner;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 启动前顺序门的生产链工厂（P1-5）：三道门的<b>判定规则与顺序</b>从
 * {@code ExtractionTaskCoordinator}（gui）原样下沉到 app 层 ——
 * <b>草稿门 → 未命名组门 → 合并名门</b>，判定逐条照搬搬迁前的三个私有门类（只搬不改）。
 *
 * <p>门规则只产数据与判定（本类<b>零 JavaFX、零 I18n</b>）；卡片文案渲染经
 * {@link PreStartGateContext} 的三个展示回调留在 GUI 层（决策 13：端口/后端只传键+参数）。</p>
 */
public final class PreStartGates {

    private PreStartGates() {
    }

    /**
     * 生产顺序门链：草稿门（第一道）→ 未命名组门 → 合并名门（链尾）。顺序即优先级
     * （决策 C：草稿卡先于未命名组卡），不得变。
     */
    public static List<PreStartGate> productionChain(PreStartGateContext ctx) {
        return List.of(new DraftGate(ctx), new UnnamedGroupGate(ctx), new MergedNameGate(ctx));
    }

    /**
     * 步骤 4 · 功能二 —— 草稿门（需求 §1.2 / 决策 D / R1.7 语义变更）：启动提取前拦截
     * 「未提交的参数草稿」。
     *
     * <p>判定唯一来源是 {@link PreStartGateContext#draftDirty()}（R1.7 旧禁用条件的同一接缝）；
     * 命中时经 {@link PreStartGateContext#draftBlocksStart()} 弹提醒卡 —— 决策 D：居中、
     * 只有一个「确定」。卡片是<b>信息性阻断</b>（无确认回调），故本门在展示后即交付
     * {@code proceed(false)}：链中止、不启动；「确定」只关卡片（微决策：等价
     * {@code proceed(false)}，不展开侧边栏、不改变焦点）。</p>
     *
     * <p><b>插入约定</b>：本门必须排在 {@link UnnamedGroupGate} <b>之前</b>（决策 C：
     * 草稿卡 → 未命名组卡），由生产链注册在第一道 —— 草稿未处理时用户看到的是草稿卡，
     * 处理完草稿才会遇到未命名组卡。</p>
     */
    private static final class DraftGate implements PreStartGate {

        private final PreStartGateContext ctx;

        DraftGate(PreStartGateContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void ask(Consumer<Boolean> proceed) {
            if (!ctx.draftDirty()) {
                proceed.accept(true);
                return;
            }
            ctx.draftBlocksStart();
            proceed.accept(false);
        }
    }

    /**
     * 步骤 3 · 功能一 —— 未命名组门（需求 §1.1）：启动提取前拦截「从未被用户重命名过」的手工组。
     *
     * <p>规则 = {@link ExtractionPreconditions#participatingGroups} +
     * {@link ExtractionPreconditions#unnamedGroups}（app 纯规则）；组名列表经
     * {@link PreStartGateContext#confirmUnnamedGroups} 交给 GUI 侧渲染确认卡
     * （决策 1.6：卡片列出具体组名；决策 1.7：多组聚合一次提示）；
     * 确定 = 以默认名提取，取消 = 中止本次启动（决策 1.8：仅不提取，不做跳转/选中）。</p>
     *
     * <p><b>插入约定</b>：本门排在草稿门之后（草稿卡 → 未命名组卡），合并名门之前。</p>
     */
    private static final class UnnamedGroupGate implements PreStartGate {

        private final PreStartGateContext ctx;

        UnnamedGroupGate(PreStartGateContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void ask(Consumer<Boolean> proceed) {
            List<GroupItem<PreviewItem>> groups = new ArrayList<>();
            for (Object o : ctx.dataOrder()) {
                if (o instanceof GroupItem<?> g) {
                    groups.add(GroupStructure.asPreviewGroup(g));
                }
            }
            // 功能一：只提醒"过滤后仍会产生单元"的顶层组（开关关闭时 = 现状）
            groups = new ArrayList<>(ExtractionPreconditions.participatingGroups(groups, ctx.extractCheckedOnly()));
            // 判定唯一来源 = app 层纯规则（不再内联同名过滤）
            List<String> names = ExtractionPreconditions.unnamedGroups(groups);
            if (names.isEmpty()) {
                proceed.accept(true);
                return;
            }
            ctx.confirmUnnamedGroups(names, proceed);
        }
    }

    /**
     * 功能一 —— 合并名可能变化的提示门（链尾，决策：草稿门 → 未命名组门 → 本门）。
     *
     * <p>仅当三个条件全满足才弹：① 开关打开；② 参与项数 &lt; 可见项数（确实有未参与项）；
     * ③ 本次输出单元里存在合并单元（未分组按来源合并桶、任意组单元、图片合并单元）。
     * 确定 = 继续提取；取消 = 中止本次启动。</p>
     */
    private static final class MergedNameGate implements PreStartGate {

        private final PreStartGateContext ctx;

        MergedNameGate(PreStartGateContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void ask(Consumer<Boolean> proceed) {
            if (!ctx.extractCheckedOnly()) {
                proceed.accept(true);
                return;
            }
            if (ctx.visibleCheckedCount() >= ctx.visibleCount()) {
                proceed.accept(true);
                return;
            }
            List<OutputUnitPlanner.OutputUnit> units = OutputUnitPlanner.plan(
                    ctx.dataOrder(), ctx.itemView(), ctx.imageMode(),
                    ctx.textParamsCache(), ctx.imageParamsCache(), ctx.outputFolder());
            if (!OutputUnitPlanner.hasMergedUnit(units, ctx.imageMode())) {
                proceed.accept(true);
                return;
            }
            ctx.confirmSubsetMergedName(proceed);
        }
    }
}
