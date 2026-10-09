package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.model.ParamScope;
import org.junit.jupiter.api.Test;

import com.mhtextractor.baseline.AppConfigFieldContract;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EffectiveParamsService} 测试（Q1 / R8-①）。
 *
 * <p><b>为什么需要它</b>：本服务把原先散落在 {@code MainController.effectiveForObject} /
 * {@code MainController.hasIndependentParams} / {@code PreviewListCell} 两处
 * {@code ParamScope.backgroundStatus} 的同一语义收敛为唯一实现点。收敛的前提是<b>语义逐点等价</b>：
 * 故本测试不仅测服务自身，还逐个把结果与 {@code ParamScope} 的<b>直接计算</b>对照（第 5 组），
 * 作为"收口不改语义"的证据。</p>
 *
 * <p>另有两类本步明确拍板的决定被钉死在这里：</p>
 * <ul>
 *   <li>{@code null}（无生效参数）在"是否统一"判定中<b>算一种独立取值</b> ⇒ {@code [A, null]} 为混合；</li>
 *   <li>值语义口径 = <b>字段级内容签名</b>（非 {@code AppConfig.equals}，非 {@code toString}，非反射），
 *       覆盖除 3 个纯运行时字段外的<b>全部</b> AppConfig 字段（含归档与内存模式字段）。</li>
 * </ul>
 *
 * <p><b>零 JavaFX</b>：{@code GroupItem} / {@code PreviewItem} 均已是纯 Java 模型
 * （{@code ArchitectureTest.headlessTestPackagesHaveNoJavaFx} 强制本包）。</p>
 */
class EffectiveParamsServiceTest {

    private final EffectiveParamsService service = new EffectiveParamsService();

    // ==================== 夹具 ====================

    private static GroupItem<PreviewItem> group(int no, String name) {
        return new GroupItem<>(no, name);
    }

    private static PreviewItem item(String name) {
        return new PreviewItem("C:/x/" + name, name + ".mht", LocalDateTime.now(), "C:/x");
    }

    private static AppConfig cfg() {
        return new AppConfig();
    }

    /** 文本模式上下文（全局文本参数可给，图片参数通常给 null） */
    private static EffectiveParamsService.Context text(AppConfig global) {
        return new EffectiveParamsService.Context(false, global, null);
    }

    private static EffectiveParamsService.Context image(AppConfig global) {
        return new EffectiveParamsService.Context(true, null, global);
    }

    // ==================== 1. 三层覆盖（组 > 条目 > 全局） ====================

    @Test
    void groupLayerWinsOverItemAndGlobal() {
        GroupItem<PreviewItem> g = group(1, "组1");
        AppConfig groupCfg = cfg();
        groupCfg.setStartMarker("GROUP");
        g.setTextParamsCache(groupCfg);

        AppConfig itemCfg = cfg();
        itemCfg.setStartMarker("ITEM");
        AppConfig globalCfg = cfg();
        globalCfg.setStartMarker("GLOBAL");

        PreviewItem it = item("a");
        it.parentGroup = g;
        it.textItemParams = itemCfg;

        assertSame(groupCfg, service.effectiveFor(it, text(globalCfg)),
                "组参数存在时，必须取组参数（即使条目级参数与全局参数都存在）");
    }

    @Test
    void itemLayerWinsWhenGroupUnset() {
        GroupItem<PreviewItem> g = group(1, "组1");
        AppConfig itemCfg = cfg();
        itemCfg.setStartMarker("ITEM");
        AppConfig globalCfg = cfg();
        globalCfg.setStartMarker("GLOBAL");

        PreviewItem it = item("a");
        it.parentGroup = g;
        it.textItemParams = itemCfg;

        assertSame(itemCfg, service.effectiveFor(it, text(globalCfg)),
                "组参数为 null 且条目级参数存在时，必须取条目级参数");
    }

    @Test
    void itemLayerWinsForTopLevelItemWithoutGroup() {
        AppConfig itemCfg = cfg();
        itemCfg.setStartMarker("ITEM");
        AppConfig globalCfg = cfg();
        globalCfg.setStartMarker("GLOBAL");

        PreviewItem it = item("a");
        it.textItemParams = itemCfg;

        assertSame(itemCfg, service.effectiveFor(it, text(globalCfg)),
                "顶层条目（无组）同样按 条目 > 全局 解析");
    }

    @Test
    void globalLayerUsedWhenGroupAndItemUnset() {
        AppConfig globalCfg = cfg();
        PreviewItem it = item("a");

        assertSame(globalCfg, service.effectiveFor(it, text(globalCfg)),
                "组与条目级参数都未设置时必须回退全局");
    }

    @Test
    void nullWhenNothingConfirmed() {
        assertNull(service.effectiveFor(item("a"), text(null)),
                "全局也未确认时生效参数必须为 null（无生效参数）");
        assertNull(service.effectiveFor(null, text(null)), "null 目标 → null（三层全空）");
    }

    @Test
    void unknownOrNullTargetFallsBackToGlobalLikeBefore() {
        // 迁移前 MainController.effectiveForObject 对"既非 PreviewItem 也非 GroupItem"（含 null）的输入
        // 会把 parent/itemText/itemImage 全取成 null，于是经 ParamScope 回退到全局参数。
        // 本服务保持该形状（不为未支持的输入新增分支），故此处钉住：非模型目标 → 全局参数。
        AppConfig global = cfg();
        assertSame(global, service.effectiveFor(null, text(global)),
                "null 目标沿用迁移前行为：三层全空 → 回退全局参数");
        assertSame(global, service.effectiveFor("不是参数目标", text(global)),
                "非模型目标沿用迁移前行为：三层全空 → 回退全局参数");
    }

    // ==================== 2. 图片模式与文本模式互不串味 ====================

    @Test
    void imageModeAndTextModeResolveTheirOwnCaches() {
        GroupItem<PreviewItem> g = group(1, "组1");
        AppConfig groupText = cfg();
        groupText.setStartMarker("TEXT");
        g.setTextParamsCache(groupText);
        AppConfig groupImage = cfg();
        groupImage.setStartMarker("IMAGE");
        g.setImageParamsCache(groupImage);

        PreviewItem it = item("a");
        it.parentGroup = g;

        assertSame(groupText, service.effectiveFor(it, text(cfg())), "文本模式取组文本参数");
        assertSame(groupImage, service.effectiveFor(it, image(cfg())), "图片模式取组图片参数");
    }

    @Test
    void imageModeNeverLeaksTextGlobal() {
        AppConfig textGlobal = cfg();
        textGlobal.setSingleOutputMode(true);
        // 图片模式：全局图片参数未确认 → 即使文本参数已确认，生效参数也必须为 null
        assertNull(service.effectiveFor(item("a"),
                        new EffectiveParamsService.Context(true, textGlobal, null)),
                "图片模式不得把文本全局参数当作生效参数");
    }

    @Test
    void itemLevelImageAndTextParamsDoNotCrossModes() {
        AppConfig itemText = cfg();
        itemText.setStartMarker("ITEM-TEXT");
        AppConfig itemImage = cfg();
        itemImage.setStartMarker("ITEM-IMAGE");

        PreviewItem it = item("a");
        it.textItemParams = itemText;
        it.imageItemParams = itemImage;

        assertSame(itemText, service.effectiveFor(it, text(null)), "文本模式取条目级文本参数");
        assertSame(itemImage, service.effectiveFor(it, image(null)), "图片模式取条目级图片参数");
    }

    @Test
    void imageModeResolutionMatchesParamScopeEntryPoint() {
        // 图片模式的唯一入口约束（P10）：服务的图片解析必须等于 ParamScope.effectiveImageParams 的直接结果
        GroupItem<PreviewItem> g = group(1, "组1");
        AppConfig groupImage = cfg();
        g.setImageParamsCache(groupImage);
        AppConfig globalImage = cfg();

        PreviewItem it = item("a");
        it.parentGroup = g;

        assertSame(ParamScope.effectiveImageParams(g, it.imageItemParams, globalImage),
                service.effectiveFor(it, image(globalImage)),
                "图片模式必须与 ParamScope.effectiveImageParams 解析出同一实例（缓存复用前提 P10）");
    }

    // ==================== 3. GroupItem 目标：不受条目级参数影响 ====================

    @Test
    void groupTargetIgnoresItemLevelParams() {
        GroupItem<PreviewItem> g = group(1, "组1");
        // 组自身无参数 → 组目标只能回退全局（条目级参数不在组目标的解析路径上）
        AppConfig globalCfg = cfg();
        assertSame(globalCfg, service.effectiveFor(g, text(globalCfg)),
                "组目标按 组 > 全局 解析，恒不受条目级参数影响");

        AppConfig groupCfg = cfg();
        g.setTextParamsCache(groupCfg);
        assertSame(groupCfg, service.effectiveFor(g, text(globalCfg)), "组目标取组参数");
    }

    @Test
    void groupTargetWithNoOwnParamsAndNoGlobalHasNoEffectiveParams() {
        assertNull(service.effectiveFor(group(1, "组1"), text(null)),
                "组无参数且全局未确认 → null");
    }

    // ==================== 4. 统一性 / 混合（含 null 与边界） ====================

    @Test
    void uniformWhenAllTargetsShareOneValue() {
        GroupItem<PreviewItem> g = group(1, "组1");
        AppConfig shared = cfg();
        g.setTextParamsCache(shared);
        List<Object> targets = List.of(memberOf(g, "a"), memberOf(g, "b"), memberOf(g, "c"));

        assertTrue(service.isUniform(targets, text(null)), "[A, A, A] 必须判为统一");
        assertFalse(service.hasMixedEffectiveParams(targets, text(null)), "[A, A, A] 不得判为混合");
    }

    @Test
    void mixedWhenTwoDifferentValues() {
        GroupItem<PreviewItem> g1 = group(1, "组1");
        AppConfig a = cfg();
        a.setStartMarker("A");
        g1.setTextParamsCache(a);
        GroupItem<PreviewItem> g2 = group(2, "组2");
        AppConfig b = cfg();
        b.setStartMarker("B");
        g2.setTextParamsCache(b);

        List<Object> targets = List.of(memberOf(g1, "a"), memberOf(g2, "b"));
        assertTrue(service.hasMixedEffectiveParams(targets, text(null)), "[A, B] 必须判为混合");
        assertFalse(service.isUniform(targets, text(null)), "[A, B] 不得判为统一");
    }

    @Test
    void mixedWhenOneTargetHasNoEffectiveParams() {
        // 本步拍板：null（无生效参数）算一种独立取值 ⇒ [A, null] 为混合
        GroupItem<PreviewItem> g = group(1, "组1");
        AppConfig a = cfg();
        g.setTextParamsCache(a);
        PreviewItem topLevelNoParams = item("none");

        List<Object> targets = List.of(memberOf(g, "a"), topLevelNoParams);
        assertTrue(service.hasMixedEffectiveParams(targets, text(null)),
                "拍板：null 是一种独立取值 —— [A, null] 必须判为混合（用户看到的是不统一）");
    }

    @Test
    void bothNullIsUniform() {
        List<Object> targets = List.of(item("a"), item("b"));
        assertTrue(service.isUniform(targets, text(null)), "[null, null] 只有一种取值 → 统一");
    }

    @Test
    void emptyAndSingleElementAreUniform() {
        assertTrue(service.isUniform(List.of(), text(null)), "空列表 → 统一（0 种取值）");
        assertFalse(service.hasMixedEffectiveParams(List.of(), text(null)), "空列表 → 不混合");
        assertTrue(service.isUniform(List.of(item("a")), text(null)), "单元素 → 统一（1 种取值）");
        assertFalse(service.hasMixedEffectiveParams(List.of(item("a")), text(null)), "单元素 → 不混合");
    }

    @Test
    void nullListIsNotMixedAndNotUniformByAnyParams() {
        assertFalse(service.hasMixedEffectiveParams(null, text(cfg())), "null 列表 → 不混合");
        assertTrue(service.isUniform(null, text(cfg())), "null 列表 → 统一（与空列表同口径）");
        assertFalse(service.anyHasEffectiveParams(null, text(cfg())), "null 列表 → 无生效参数");
        assertTrue(service.targetsWithEffectiveParams(null, text(cfg())).isEmpty());
    }

    @Test
    void sameValuesInDifferentInstancesAreUniform() {
        // 值语义（而非引用身份）：两组各自持有"字段内容相同"的参数 → 统一
        AppConfig a1 = cfg();
        a1.setStartMarker("SAME");
        a1.setEnableZipPackaging(true);
        GroupItem<PreviewItem> g1 = group(1, "组1");
        g1.setTextParamsCache(a1);

        AppConfig a2 = cfg();
        a2.setStartMarker("SAME");
        a2.setEnableZipPackaging(true);
        GroupItem<PreviewItem> g2 = group(2, "组2");
        g2.setTextParamsCache(a2);

        assertTrue(service.isUniform(List.of(memberOf(g1, "a"), memberOf(g2, "b")), text(null)),
                "内容相同的不同实例必须判为统一（不得按引用身份比较）");
    }

    @Test
    void identicalGlobalsResolveToUniformForItemsWithoutOwnParams() {
        // 迁移前 MainController.anyVisibleItemHasEffectiveParams 的等价形态：一组无独立参数条目 + 已确认全局
        AppConfig global = cfg();
        List<Object> targets = List.of(item("a"), item("b"));
        assertTrue(service.isUniform(targets, text(global)), "共同回退同一全局参数 → 统一");
        assertTrue(service.anyHasEffectiveParams(targets, text(global)));
    }

    @Test
    void differsOnlyByArchiveOrMemoryFieldIsMixed() {
        // §3.2 拍板：归档与内存模式字段也算"用户设置不一样" ⇒ 参与统一性判定
        GroupItem<PreviewItem> g1 = group(1, "组1");
        AppConfig zip = cfg();
        zip.setEnableZipPackaging(true);
        g1.setTextParamsCache(zip);

        GroupItem<PreviewItem> g2 = group(2, "组2");
        AppConfig noZip = cfg();
        g2.setTextParamsCache(noZip);

        assertTrue(service.hasMixedEffectiveParams(List.of(memberOf(g1, "a"), memberOf(g2, "b")),
                        text(null)),
                "仅归档开关不同也必须判为混合（用户感知的是设置不一样）");
    }

    // ==================== 5. hasIndependentParams 与 ParamScope 直接计算等价（收口不改语义） ====================

    @Test
    void hasIndependentParamsMatchesParamScopeForGroupTarget() {
        for (boolean groupHasOwn : new boolean[]{false, true}) {
            for (boolean imageMode : new boolean[]{false, true}) {
                GroupItem<PreviewItem> g = group(1, "组1");
                AppConfig global = cfg();
                if (groupHasOwn) {
                    if (imageMode) {
                        g.setImageParamsCache(cfg());
                    } else {
                        g.setTextParamsCache(cfg());
                    }
                }

                boolean expected = ParamScope.backgroundStatus(g, null, null, imageMode,
                        global, global) == ParamScope.Status.GROUP;
                assertEquals(expected, service.hasIndependentParams(g, new EffectiveParamsService.Context(
                                imageMode, global, global)),
                        "组目标：hasIndependentParams 必须等于 ParamScope 直接计算（groupHasOwn="
                                + groupHasOwn + ", imageMode=" + imageMode + "）");
            }
        }
    }

    @Test
    void hasIndependentParamsMatchesParamScopeForItemTargets() {
        // 三类条目：有组参数 / 有条目级参数 / 只有全局（顶层条目）
        GroupItem<PreviewItem> gWithOwn = group(1, "组1");
        gWithOwn.setTextParamsCache(cfg());
        GroupItem<PreviewItem> gBare = group(2, "组2");

        List<PreviewItem> cases = new ArrayList<>();
        cases.add(memberOf(gWithOwn, "a"));
        PreviewItem itemWithOwn = memberOf(gBare, "b");
        itemWithOwn.textItemParams = cfg();
        cases.add(itemWithOwn);
        cases.add(item("c"));

        AppConfig global = cfg();
        for (PreviewItem it : cases) {
            for (boolean imageMode : new boolean[]{false, true}) {
                boolean expected = ParamScope.backgroundStatus(it.parentGroup, it.textItemParams,
                        it.imageItemParams, imageMode, global, imageMode ? global : null)
                        == ParamScope.Status.GROUP;
                assertEquals(expected, service.hasIndependentParams(it,
                                new EffectiveParamsService.Context(imageMode, global, imageMode ? global : null)),
                        "条目目标：hasIndependentParams 必须等于 ParamScope 直接计算（item=" + it.name
                                + ", imageMode=" + imageMode + "）");
            }
        }
    }

    @Test
    void hasIndependentParamsFalseForNonTargets() {
        assertFalse(service.hasIndependentParams(null, text(cfg())), "null 目标 → 无独立参数");
        assertFalse(service.hasIndependentParams("不是目标", text(cfg())), "其他类型 → 无独立参数");
    }

    @Test
    void effectiveSourceOfIsTheSinglePointBehindHasIndependentParams() {
        GroupItem<PreviewItem> g = group(1, "组1");
        assertEquals(ParamScope.Status.NONE, service.effectiveSourceOf(g, text(null)),
                "组无参数且全局未确认 → NONE");
        g.setTextParamsCache(cfg());
        assertEquals(ParamScope.Status.GROUP, service.effectiveSourceOf(g, text(null)),
                "组有参数 → GROUP（独立参数）");
        assertTrue(service.hasIndependentParams(g, text(null)),
                "hasIndependentParams 必须是 effectiveSourceOf == GROUP 的同一实现点");
    }

    // ==================== 6. anyHasEffectiveParams / targetsWithEffectiveParams ====================

    @Test
    void anyHasEffectiveParamsFalseWhenAllNull() {
        assertFalse(service.anyHasEffectiveParams(List.of(item("a"), item("b")), text(null)),
                "全部无生效参数 → false");
        assertFalse(service.anyHasEffectiveParams(List.of(), text(cfg())), "空列表 → false");
    }

    @Test
    void anyHasEffectiveParamsTrueWhenAnyNonNull() {
        GroupItem<PreviewItem> g = group(1, "组1");
        g.setTextParamsCache(cfg());
        List<Object> targets = List.of(item("a"), memberOf(g, "b"));
        assertTrue(service.anyHasEffectiveParams(targets, text(null)), "任一非 null → true");
        assertEquals(List.of(targets.get(1)), service.targetsWithEffectiveParams(targets, text(null)),
                "targetsWithEffectiveParams 必须保持输入顺序且只含非 null 目标");
    }

    // ==================== 7. 值语义口径：字段级签名完整覆盖 ====================

    /** AppConfig 的 4 个纯运行时/GUI 状态字段不参与"参数是否统一"判定（其余全部参与） */
    private static final List<String> RUNTIME_ONLY =
            List.of("inputFolder", "outputFolder", "imageModeEnabled", "openFolderAfterComplete");

    @Test
    void signatureCoversEveryAppConfigFieldExceptRuntimeOnly() throws Exception {
        AppConfigFieldContract.assertMatchesDeclaredFields();
        List<String> missed = new ArrayList<>();
        int covered = 0;
        for (var f : AppConfigFieldContract.FIELDS) {
            String name = f.name();
            if (RUNTIME_ONLY.contains(name)) {
                continue;
            }
            AppConfig baseline = cfg();
            AppConfig mutated = cfg();
            f.write().accept(mutated, f.differentValue(baseline));
            if (EffectiveParamsService.signatureOf(baseline)
                    .equals(EffectiveParamsService.signatureOf(mutated))) {
                missed.add(name);
            }
            covered++;
        }
        // 自检：字段数必须与实现一致（漏改签名的字段会出现在 missed 里）
        assertEquals(RUNTIME_ONLY, EffectiveParamsService.runtimeOnlyFields(),
                "服务声明的运行时专用字段必须与本测试的排除清单一致");
        assertTrue(covered >= 23, "应覆盖 AppConfig 的绝大多数字段，实测 " + covered);
        assertTrue(missed.isEmpty(),
                "以下 AppConfig 字段未参与值语义签名（新增参数必须同步 EffectiveParamsService.signatureOf）：" + missed);
    }

    @Test
    void runtimeOnlyFieldsDoNotAffectSignature() throws Exception {
        AppConfig a = cfg();
        AppConfig b = cfg();
        b.setInputFolder("D:/in");
        b.setOutputFolder("D:/out");
        b.setOpenFolderAfterComplete(true);
        // imageModeEnabled 为 @JsonIgnore 的主窗口 GUI 状态（AppConfig javadoc 明写"由主窗口模式开关写入"）
        b.setImageModeEnabled(true);
        assertEquals(EffectiveParamsService.signatureOf(a), EffectiveParamsService.signatureOf(b),
                "输入/输出目录、模式开关与「完成后打开」属 GUI 运行时状态，不得影响参数统一性判定");
    }

    @Test
    void signatureDistinguishesNullFromEmptyString() throws Exception {
        AppConfig nullMarker = cfg();
        AppConfig emptyMarker = cfg();
        emptyMarker.setStartMarker("");
        assertNotEquals(EffectiveParamsService.signatureOf(nullMarker),
                EffectiveParamsService.signatureOf(emptyMarker),
                "长度前缀编码必须区分 null 与空串（迁移前 ParamScope 判定不涉及此点，但值语义需要）");
    }

    @Test
    void noParamsSentinelNeverEqualsARealSignature() {
        AppConfig c = cfg();
        assertNotEquals(EffectiveParamsService.NO_PARAMS, EffectiveParamsService.signatureOf(c),
                "null（无生效参数）的标记不得与任何真实配置签名相同");
        assertNotEquals(EffectiveParamsService.NO_PARAMS,
                EffectiveParamsService.signatureOf(new AppConfig()),
                "全默认配置同样是「有生效参数」，其签名不得等于 NO_PARAMS");
    }

    // ==================== 8. allGroupNames 收口：模型侧是唯一一份 ====================

    @Test
    void groupNamesIsTheSingleSourceAndUnique() {
        WorkspaceModel ws = new WorkspaceModel();
        // 组必须由"已在工作区内的条目"构成（WorkspaceModel.group 对不在本工作区的目标为 no-op）
        PreviewItem a = item("a");
        PreviewItem b = item("b");
        ws.addItems(List.of(a, b));
        // 同一个前缀：模型必须用"顶层组名集合"唯一化（组 → 组1、组2），不得产生重名
        ws.group(List.of(a), "组");
        GroupItem<PreviewItem> g2 = ws.group(List.of(b), "组");

        List<String> names = new ArrayList<>(ws.groupNames());
        assertEquals(List.of("组1", "组2"), names,
                "模型侧 groupNames 必须唯一化并保持顶层顺序（同一前缀 → 组1、组2）");
        assertEquals(2, names.size(), "同名组不得在集合里出现两次");
        assertTrue(ws.groupNames().contains(g2.getName()),
                "第二个组的实际名字必须落在模型侧这一份组名集合里（唯一化的唯一来源）");
    }

    @Test
    void groupNamesIsEmptyForEmptyModel() {
        assertTrue(new WorkspaceModel().groupNames().isEmpty(), "空模型 → 无组名");
    }

    /** 造一个属于某组的成员条目（同时挂回组的成员表，模拟真实数据序的 1:1 关系） */
    private static PreviewItem memberOf(GroupItem<PreviewItem> g, String name) {
        PreviewItem it = item(name);
        it.parentGroup = g;
        g.getMembers().add(it);
        return it;
    }
}
