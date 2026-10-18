package com.mhtextractor.uicore;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.ParamSignature;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参数草稿状态机（R1.5）—— <b>无头</b>单测：本测试类不引用任何 UI 类，也不需要 JavaFX 工具包
 * （{@link ParamDraft} 是零 JavaFX 的前端纯逻辑，放 {@code uicore} 包正是为了这一点）。
 *
 * <p>钉死的语义（对应任务 §4.2 的 6 条）：</p>
 * <ol>
 *   <li>载入基线后不脏；值相同但<b>另一个对象</b>也不脏（按值比较，不是按引用）；</li>
 *   <li>改可持久化字段 → 脏；</li>
 *   <li>只改 4 个 {@code @JsonIgnore} 运行时字段 → <b>仍不脏</b>（与 {@code signatureOf} 排除集合一致）；</li>
 *   <li>{@code commit} 后不脏，且基线确实前移（旧值反而变脏）；</li>
 *   <li>「保留草稿」= 什么都不做 → 脏仍在、基线未被改写；</li>
 *   <li>改回原值 → 变回不脏。</li>
 * </ol>
 *
 * <p>第 3 条同时是"没有用 {@code AppConfig.equals} 当判定键"的证据：Lombok {@code @Data} 生成的
 * {@code equals} <b>包含</b>那 4 个字段，只要实现偷用 {@code equals}，第 3 条必红。</p>
 */
class ParamDraftTest {

    /** 一份"有内容"的样本参数（值本身不重要，只要覆盖布尔/字符串两类字段）。 */
    private static AppConfig sample() {
        AppConfig c = new AppConfig();
        c.setSeparateFiles(true);
        c.setEnableContentLimit(true);
        c.setStartMarker("START");
        c.setEndMarker("END");
        c.setImageNameStartMarker("IMG_START");
        c.setHighSpeedMemoryMode(false);
        return c;
    }

    // ==================== 1. 基线后不脏 ====================

    @Test
    void baselineThenUnchangedIsNotDirty() {
        ParamDraft draft = new ParamDraft();
        AppConfig base = sample();
        draft.baseline(base);

        assertFalse(draft.isDirty(base), "刚记完基线，同一对象不应为脏");

        // 按值比较（而不是按引用 / 按身份）：另一份内容完全相同的快照同样不脏
        assertFalse(draft.isDirty(sample()), "值相同的另一份快照不应为脏（必须按值比较）");
    }

    // ==================== 2. 改可持久化字段 → 脏 ====================

    @Test
    void changingPersistableFieldsIsDirty() {
        ParamDraft draft = new ParamDraft();
        draft.baseline(sample());

        AppConfig booleanChanged = sample();
        booleanChanged.setSeparateFiles(false);
        assertTrue(draft.isDirty(booleanChanged), "改 separateFiles 应判为脏");

        AppConfig stringChanged = sample();
        stringChanged.setStartMarker("OTHER");
        assertTrue(draft.isDirty(stringChanged), "改 startMarker 应判为脏");

        // 图片模式字段同样在签名里（本步信息栏的组内混合判定也依赖这一点）
        AppConfig imageChanged = sample();
        imageChanged.setImageNameStartMarker(null);
        assertTrue(draft.isDirty(imageChanged), "图片参数变化也应判为脏");
    }

    // ==================== 3. 运行时字段不参与脏判定 ====================

    @Test
    void runtimeOnlyFieldsDoNotMakeItDirty() {
        // 先与 signatureOf 的排除集合对账：这 4 个就是"不进签名"的字段（防日后签名口径变了而本测试还绿）
        assertEquals(List.of("inputFolder", "outputFolder", "imageModeEnabled", "openFolderAfterComplete"),
                ParamSignature.runtimeOnlyFields(),
                "signatureOf 的排除集合变了 —— 本测试的语义前提需要同步复核");

        ParamDraft draft = new ParamDraft();
        draft.baseline(sample());

        AppConfig inputFolder = sample();
        inputFolder.setInputFolder("D:\\in");
        assertFalse(draft.isDirty(inputFolder), "只改 inputFolder 不算脏");

        AppConfig outputFolder = sample();
        outputFolder.setOutputFolder("D:\\out");
        assertFalse(draft.isDirty(outputFolder), "只改 outputFolder 不算脏");

        AppConfig mode = sample();
        mode.setImageModeEnabled(true);
        assertFalse(draft.isDirty(mode), "只改 imageModeEnabled 不算脏（模式属主窗口 GUI 状态）");

        AppConfig openAfter = sample();
        openAfter.setOpenFolderAfterComplete(true);
        assertFalse(draft.isDirty(openAfter), "只改 openFolderAfterComplete 不算脏");

        // 四个一起改也仍然不脏
        AppConfig all = sample();
        all.setInputFolder("D:\\in");
        all.setOutputFolder("D:\\out");
        all.setImageModeEnabled(true);
        all.setOpenFolderAfterComplete(true);
        assertFalse(draft.isDirty(all), "四个运行时字段一起改也不算脏");
    }

    // ==================== 4. 提交 ====================

    @Test
    void commitClearsDirtyAndMovesBaseline() {
        ParamDraft draft = new ParamDraft();
        AppConfig before = sample();
        draft.baseline(before);

        AppConfig edited = sample();
        edited.setStartMarker("EDITED");
        assertTrue(draft.isDirty(edited));

        draft.commit(edited);

        assertFalse(draft.isDirty(edited), "提交后当前值即为基线，不应再脏");
        assertTrue(draft.isDirty(before), "基线已前移：旧值相对新基线应变成脏");
    }

    // ==================== 5. 保留草稿 ====================

    @Test
    void keepingDraftLeavesBaselineAndDirtyUntouched() {
        ParamDraft draft = new ParamDraft();
        AppConfig base = sample();
        draft.baseline(base);

        AppConfig edited = sample();
        edited.setSeparateFiles(false);

        // 「折叠 = 收起面板并保留草稿」：对草稿的"操作"就是什么都不做 ——
        // 不 commit（那会把未确认的编辑升级成基线）、不 baseline（那会丢掉"改过"这一事实）。
        assertTrue(draft.isDirty(edited));
        assertTrue(draft.isDirty(edited), "再问一次仍应为脏（草稿仍在，没被谁清掉）");
        assertFalse(draft.isDirty(base), "基线必须仍是载入时那份（未被悄悄改写）");
    }

    /** 反例控制组：若"保留草稿"被误实现成 commit，上面的断言会怎样 —— 这里显式证明它能抓住该错误。 */
    @Test
    void commitWouldHaveDestroyedTheDraftControlGroup() {
        ParamDraft draft = new ParamDraft();
        AppConfig base = sample();
        draft.baseline(base);

        AppConfig edited = sample();
        edited.setSeparateFiles(false);

        draft.commit(edited);   // 错误实现：折叠时提交
        assertFalse(draft.isDirty(edited), "控制组：commit 之后草稿确实消失了（证明上面的断言有判别力）");
    }

    // ==================== 6. 改回原值 ====================

    @Test
    void revertingToOriginalValueBecomesCleanAgain() {
        ParamDraft draft = new ParamDraft();
        draft.baseline(sample());

        AppConfig edited = sample();
        edited.setSeparateFiles(false);
        edited.setStartMarker("TEMP");
        assertTrue(draft.isDirty(edited), "改过就应脏");

        edited.setSeparateFiles(true);
        edited.setStartMarker("START");
        assertFalse(draft.isDirty(edited), "按值比较：改回原值即不脏");
    }

    // ==================== 边界：null（无生效参数） ====================

    @Test
    void nullSnapshotIsItsOwnValue() {
        ParamDraft draft = new ParamDraft();
        draft.baseline(null);
        assertFalse(draft.isDirty(null), "基线 null（无生效参数）与当前 null 视为相同");
        assertTrue(draft.isDirty(sample()), "从「无参数」变成「有一套参数」应判为脏");

        draft.baseline(sample());
        assertTrue(draft.isDirty(null), "从「有一套参数」变成「无参数」也应判为脏");
    }
}
