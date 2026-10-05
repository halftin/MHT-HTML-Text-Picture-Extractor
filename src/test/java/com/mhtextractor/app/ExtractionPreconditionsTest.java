package com.mhtextractor.app;

import com.mhtextractor.i18n.ErrorCode;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.GroupItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 提取前置校验规则测试（会话 18 · E4-2）。
 *
 * <p><b>为什么值得单测</b>：这条规则（"什么条件下允许开始提取"）此前<b>只存在于
 * {@code MainController.onStartProcessing} 的内联 if 链里</b> —— 即规则与 GUI 事件处理交织，
 * 既无法脱离 GUI 复用，也无法直接断言"哪种失败返回哪个错误码"。
 * 抽成 {@link ExtractionPreconditions} 后，规则可以被逐条钉死，且<b>零 JavaFX</b>。</p>
 *
 * <p><b>契约</b>：返回 {@code null} = 允许；否则是被拒绝的原因码。判定顺序固定为
 * 输入目录空 → 输出目录空 → 输入目录不存在 → 无可提取条目（并区分"从未导入"与"全被移除"）。</p>
 */
class ExtractionPreconditionsTest {

    @TempDir
    Path tmp;

    private Path existingDir() throws Exception {
        Path d = tmp.resolve("in");
        Files.createDirectories(d);
        return d;
    }

    private static final String OUT = "out";

    // ==================== 拒绝路径 ====================

    @Test
    void blankOrNullInputFolderIsRejected() {
        assertEquals(ErrorCode.INPUT_DIR_INVALID,
                ExtractionPreconditions.validate(new ExtractionPreconditions.Facts(
                        "", OUT, false, 0, 0)));
        assertEquals(ErrorCode.INPUT_DIR_INVALID,
                ExtractionPreconditions.validate(new ExtractionPreconditions.Facts(
                        null, OUT, false, 0, 0)));
        // 纯空白也应视为空（原实现用 trim().isEmpty()）
        assertEquals(ErrorCode.INPUT_DIR_INVALID,
                ExtractionPreconditions.validate(new ExtractionPreconditions.Facts(
                        "   ", OUT, true, 1, 1)));
    }

    @Test
    void blankOutputFolderIsRejected() {
        assertEquals(ErrorCode.OUTPUT_DIR_INVALID,
                ExtractionPreconditions.validate(new ExtractionPreconditions.Facts(
                        "in", "  ", true, 1, 1)));
        assertEquals(ErrorCode.OUTPUT_DIR_INVALID,
                ExtractionPreconditions.validate(new ExtractionPreconditions.Facts(
                        "in", null, true, 1, 1)));
    }

    @Test
    void missingInputDirectoryIsRejected() {
        // 注意：此处输入目录字符串非空、但不是一个存在的目录
        assertEquals(ErrorCode.INPUT_DIR_MISSING,
                ExtractionPreconditions.validate(new ExtractionPreconditions.Facts(
                        "no-such-dir", OUT, false, 1, 1)));
    }

    @Test
    void emptyListDistinguishesNeverImportedFromAllRemoved() {
        // 从未导入任何文件 → NO_MHT_FOUND
        assertEquals(ErrorCode.NO_MHT_FOUND,
                ExtractionPreconditions.validate(new ExtractionPreconditions.Facts(
                        "in", OUT, true, 0, 0)));
        // 导入过但全被移除 → KEEP_AT_LEAST_ONE（原实现正是这样区分的两种文案）
        assertEquals(ErrorCode.KEEP_AT_LEAST_ONE,
                ExtractionPreconditions.validate(new ExtractionPreconditions.Facts(
                        "in", OUT, true, 5, 0)));
    }

    @Test
    void nullFactsIsRejectedAsInputDirInvalid() {
        assertEquals(ErrorCode.INPUT_DIR_INVALID, ExtractionPreconditions.validate(null));
    }

    // ==================== 通过路径 ====================

    @Test
    void validFactsAreAccepted() {
        assertNull(ExtractionPreconditions.validate(new ExtractionPreconditions.Facts(
                "in", OUT, true, 3, 3)));
        // 有条目被移除但仍可见 → 允许
        assertNull(ExtractionPreconditions.validate(new ExtractionPreconditions.Facts(
                "in", OUT, true, 5, 2)));
    }

    // ==================== 判定顺序 ====================

    @Test
    void inputFolderEmptinessIsCheckedBeforeOutput() {
        // 两者都非法时，应报输入目录（证明顺序）
        assertEquals(ErrorCode.INPUT_DIR_INVALID,
                ExtractionPreconditions.validate(new ExtractionPreconditions.Facts(
                        "", "", false, 0, 0)));
    }

    @Test
    void outputEmptinessIsCheckedBeforeDirectoryExistence() {
        // 输出为空 + 输入目录不存在 → 应报输出（证明顺序）
        assertEquals(ErrorCode.OUTPUT_DIR_INVALID,
                ExtractionPreconditions.validate(new ExtractionPreconditions.Facts(
                        "no-such-dir", "", false, 0, 0)));
    }

    @Test
    void directoryExistenceIsCheckedBeforeItemCount() {
        // 输入目录不存在 + 列表为空 → 应报目录缺失（证明顺序）
        assertEquals(ErrorCode.INPUT_DIR_MISSING,
                ExtractionPreconditions.validate(new ExtractionPreconditions.Facts(
                        "no-such-dir", OUT, false, 0, 0)));
    }

    // ==================== 展示级别（E4-3：级别也属规则） ====================

    /**
     * 级别映射必须与原 {@code MainController.onStartProcessing} 的实测行为一致：
     * 输入/输出目录为空、条目数为 0 → WARNING；输入目录不存在 → ERROR。
     */
    @Test
    void levelMappingMatchesOriginalAlertLevels() {
        assertEquals(ExtractionPreconditions.Level.WARNING,
                ExtractionPreconditions.levelOf(ErrorCode.INPUT_DIR_INVALID));
        assertEquals(ExtractionPreconditions.Level.WARNING,
                ExtractionPreconditions.levelOf(ErrorCode.OUTPUT_DIR_INVALID));
        assertEquals(ExtractionPreconditions.Level.WARNING,
                ExtractionPreconditions.levelOf(ErrorCode.NO_MHT_FOUND));
        assertEquals(ExtractionPreconditions.Level.WARNING,
                ExtractionPreconditions.levelOf(ErrorCode.KEEP_AT_LEAST_ONE));
        // 唯一的 ERROR：输入目录不存在
        assertEquals(ExtractionPreconditions.Level.ERROR,
                ExtractionPreconditions.levelOf(ErrorCode.INPUT_DIR_MISSING));
    }

    @Test
    void denyWrapsCodeWithItsLevelAndPassesThroughNull() {
        assertNull(ExtractionPreconditions.deny(null), "条件通过时应返回 null");

        var d = ExtractionPreconditions.deny(ErrorCode.INPUT_DIR_MISSING);
        assertNotNull(d);
        assertEquals(ErrorCode.INPUT_DIR_MISSING, d.code());
        assertEquals(ExtractionPreconditions.Level.ERROR, d.level());

        var w = ExtractionPreconditions.deny(ErrorCode.OUTPUT_DIR_INVALID);
        assertNotNull(w);
        assertEquals(ExtractionPreconditions.Level.WARNING, w.level());
    }

    // ==================== 功能一：只提取选中项的前置校验 ====================

    @Test
    void noCheckedItemsOnlyRejectsWhenNothingParticipates() {
        assertNull(ExtractionPreconditions.noCheckedItems(3), "有勾选项 → 通过");
        assertNull(ExtractionPreconditions.noCheckedItems(1));
        assertEquals(ErrorCode.NO_CHECKED_ITEMS, ExtractionPreconditions.noCheckedItems(0),
                "一项未勾选 → 新错误码");
        // 不破坏 noExtractableItems 的三态语义
        assertEquals(ErrorCode.NO_MHT_FOUND, ExtractionPreconditions.noExtractableItems(0, 0));
        assertEquals(ErrorCode.KEEP_AT_LEAST_ONE, ExtractionPreconditions.noExtractableItems(5, 0));
        assertNull(ExtractionPreconditions.noExtractableItems(5, 3));
    }

    @Test
    void noCheckedItemsIsAnErrorLevelDenial() {
        assertEquals(ExtractionPreconditions.Level.ERROR,
                ExtractionPreconditions.levelOf(ErrorCode.NO_CHECKED_ITEMS),
                "缺勾选必须是 ERROR（居中卡片、须确认）");
        var d = ExtractionPreconditions.deny(ErrorCode.NO_CHECKED_ITEMS);
        assertNotNull(d);
        assertEquals(ExtractionPreconditions.Level.ERROR, d.level());
    }

    @Test
    void participatingGroupsKeepOnlyGroupsWithACheckedMemberWhenSwitchOn() {
        GroupItem<PreviewItem> g1 = new GroupItem<>(1, "g1");
        PreviewItem checked = item("a");
        checked.selected = true;
        g1.getMembers().add(checked);

        GroupItem<PreviewItem> g2 = new GroupItem<>(2, "g2");
        g2.getMembers().add(item("b"));   // 未勾选

        GroupItem<PreviewItem> g3 = new GroupItem<>(3, "g3");
        PreviewItem removed = item("c");
        removed.selected = true;
        removed.removed = true;
        g3.getMembers().add(removed);

        // 开关关闭 → 原样（现状）
        assertEquals(List.of(g1, g2, g3),
                ExtractionPreconditions.participatingGroups(List.of(g1, g2, g3), false));
        // 开关打开 → 只有含勾选且未移除成员的组
        assertEquals(List.of(g1),
                ExtractionPreconditions.participatingGroups(List.of(g1, g2, g3), true));
        assertEquals(List.of(), ExtractionPreconditions.participatingGroups(null, true));
    }

    private static PreviewItem item(String name) {
        return new PreviewItem("C:/pre/" + name + ".mht", name + ".mht", LocalDateTime.now(), "C:/pre");
    }

    // ==================== 便捷重载（内部自己探目录） ====================

    @Test
    void convenienceOverloadProbesTheFilesystemItself() throws Exception {
        Path real = existingDir();

        // 真实存在的目录 + 有可见条目 → 允许
        assertNull(ExtractionPreconditions.validate(
                real.toString(), OUT, 1, 1));

        // 不存在的目录 → INPUT_DIR_MISSING（由便捷重载内部探测得到）
        assertEquals(ErrorCode.INPUT_DIR_MISSING,
                ExtractionPreconditions.validate(
                        tmp.resolve("does-not-exist").toString(), OUT, 1, 1));

        // 输入为空时不应去探测文件系统，直接 INPUT_DIR_INVALID
        assertEquals(ErrorCode.INPUT_DIR_INVALID,
                ExtractionPreconditions.validate((String) null, OUT, 1, 1));
    }
}
