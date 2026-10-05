package com.mhtextractor.app;

import com.mhtextractor.i18n.ErrorCode;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.GroupItem;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 提取前置校验（会话 18 · E4-2）—— 从 {@code MainController.onStartProcessing} 抽出的**纯决策逻辑**。
 *
 * <p><b>为什么抽它</b>：这段校验原先内联在 GUI 事件处理器里（输入/输出目录、目录是否存在、
 * 列表是否为空），并与 {@code showAlert} 交织 —— 于是"到底什么条件下允许开始提取"这件事
 * <b>只能靠读 GUI 代码才能知道，也无法单测</b>。抽成纯函数后：
 * 调用方拿到 {@link ErrorCode}（结构化结果）自行决定怎么展示（弹窗/日志/退出码），
 * 业务规则与展示彻底分离。</p>
 *
 * <p><b>零 JavaFX</b>：本类只做字符串/目录/计数判断，属目标 ② 的 {@code app} 层
 * （由 {@code ArchitectureTest} 强制依赖闭包零 {@code javafx}）。</p>
 *
 * <p><b>行为等价性</b>：判定顺序与判定条件与原代码<b>逐条一致</b>：
 * 输入目录为空 → 输出目录为空 → 输入目录不存在 → 无可提取条目（区分"从未找到"与"全被移除"）。</p>
 */
public final class ExtractionPreconditions {

    /** 校验所需的事实（由调用方从自己的 UI/状态中取出，本类不读任何全局状态） */
    public record Facts(
            String inputFolder,
            String outputFolder,
            boolean inputFolderIsDirectory,
            /* 列表中的条目总数（含被移除的） */
            int totalItems,
            /* 当前可见（未被移除）的条目数 */
            int visibleItems) {
    }

    private ExtractionPreconditions() {
    }

    /**
     * 校验是否允许开始提取。
     *
     * @return {@code null} 表示<b>允许</b>；非 null 表示被拒绝及原因（调用方据此展示）
     */
    public static ErrorCode validate(Facts facts) {
        if (facts == null) {
            return ErrorCode.INPUT_DIR_INVALID;
        }
        if (isBlank(facts.inputFolder())) {
            return ErrorCode.INPUT_DIR_INVALID;
        }
        if (isBlank(facts.outputFolder())) {
            return ErrorCode.OUTPUT_DIR_INVALID;
        }
        if (!facts.inputFolderIsDirectory()) {
            return ErrorCode.INPUT_DIR_MISSING;
        }
        if (facts.visibleItems() == 0) {
            // 区分"从未导入到任何文件"与"导入了但全被移除"——原代码即如此区分
            return facts.totalItems() == 0 ? ErrorCode.NO_MHT_FOUND : ErrorCode.KEEP_AT_LEAST_ONE;
        }
        return null;
    }

    /** 便捷方法：直接从目录字符串判断（内部做一次 {@code File.isDirectory()}），便于无头调用 */
    public static ErrorCode validate(String inputFolder, String outputFolder,
                                     int totalItems, int visibleItems) {
        boolean isDir = !isBlank(inputFolder) && new File(inputFolder).isDirectory();
        return validate(new Facts(inputFolder, outputFolder, isDir, totalItems, visibleItems));
    }

    // ==================== 单条件形态（供 UI 逐条委托，保留顺序与告警级别） ====================

    /**
     * 单条件形态存在的理由：GUI 的原始写法是<b>逐条 if + 各自不同的告警级别</b>
     * （输入/输出/条目数为 WARNING，目录不存在为 ERROR），且判定<b>顺序</b>本身是语义的一部分。
     * 若 UI 直接调用聚合 {@link #validate(Facts)}，就会把顺序与级别压平 —— 那是行为变更。
     *
     * <p>因此同时提供"聚合形态"（无头调用方一次问清楚）与"单条件形态"（UI 逐条问、逐条弹）。
     * 两者共享同一批判断，不会出现两套规则漂移。</p>
     *
     * @return {@code null} = 该条件通过
     */
    public static ErrorCode blankInput(String inputFolder) {
        return isBlank(inputFolder) ? ErrorCode.INPUT_DIR_INVALID : null;
    }

    /** 见 {@link #blankInput(String)} 的说明 */
    public static ErrorCode blankOutput(String outputFolder) {
        return isBlank(outputFolder) ? ErrorCode.OUTPUT_DIR_INVALID : null;
    }

    /** 见 {@link #blankInput(String)} 的说明（调用方应先确认目录字符串非空） */
    public static ErrorCode inputNotDirectory(String inputFolder) {
        if (isBlank(inputFolder)) {
            return ErrorCode.INPUT_DIR_INVALID;
        }
        return new File(inputFolder).isDirectory() ? null : ErrorCode.INPUT_DIR_MISSING;
    }

    /** 见 {@link #blankInput(String)} 的说明 */
    public static ErrorCode noExtractableItems(int totalItems, int visibleItems) {
        if (visibleItems != 0) {
            return null;
        }
        return totalItems == 0 ? ErrorCode.NO_MHT_FOUND : ErrorCode.KEEP_AT_LEAST_ONE;
    }

    /**
     * 功能一：开启"只提取选中的列表项"但没有勾选任何可见项时的拒绝码。
     *
     * <p>与 {@link #noExtractableItems(int, int)} 分开：后者是三态语义（从未导入 / 全被移除），
     * 不能把"可见项 &gt; 0 但勾选为 0"塞进去，否则会破坏其既有三态。调用方只在开关打开时调用本方法。
     *
     * @param participating 本次会参与提取的可见项数（开关打开时 = 勾选且未移除的数量）
     * @return {@code null} = 通过；否则 {@link ErrorCode#NO_CHECKED_ITEMS}
     */
    public static ErrorCode noCheckedItems(int participating) {
        return participating == 0 ? ErrorCode.NO_CHECKED_ITEMS : null;
    }

    // ==================== 未命名组规则（步骤 3 · 功能一） ====================

    /**
     * 未命名组判定：{@link GroupItem#isDefaultNamed()} 为 true（「从未被用户重命名过」）的组。
     *
     * <p>语义来源（需求 §1.1 决策 1.1–1.12）：手工建组即默认名；用户一旦<b>提交</b>过改名，
     * 标记永久为 false（改回默认名也不再提醒）；导入自动建组恒为 false。撤销/重做恢复的组
     * 保留其当时的标记（记录与快照持组<b>实例</b>引用，标记随对象走）。</p>
     *
     * <p>本规则只产<b>组名列表</b>，文案与展示全部留在 GUI 层（本类零 I18n 引用是架构硬约束）。</p>
     *
     * @param groups 顶层组列表（调用方从数据序投影中取出）
     * @return 未命名组名列表（空 = 通过，无需提醒）
     */
    public static List<String> unnamedGroups(List<GroupItem<PreviewItem>> groups) {
        List<String> names = new ArrayList<>();
        if (groups != null) {
            for (GroupItem<PreviewItem> g : groups) {
                if (g.isDefaultNamed()) {
                    names.add(g.getName());
                }
            }
        }
        return List.copyOf(names);
    }

    /**
     * 功能一：过滤出"过滤后仍会产生单元"的顶层组（开关打开时 ≥1 个勾选且未移除的成员；
     * 开关关闭时原样返回）。未命名组提醒只针对这些组。纯函数。
     */
    public static List<GroupItem<PreviewItem>> participatingGroups(
            List<GroupItem<PreviewItem>> groups, boolean checkedOnly) {
        if (groups == null || !checkedOnly) {
            return groups == null ? List.of() : List.copyOf(groups);
        }
        List<GroupItem<PreviewItem>> out = new ArrayList<>();
        for (GroupItem<PreviewItem> g : groups) {
            for (Object m : g.getMembers()) {
                if (m instanceof PreviewItem it && !it.removed && it.selected) {
                    out.add(g);
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    // ==================== 展示级别（把"该用 WARNING 还是 ERROR"也变成规则的一部分） ====================

    /**
     * 告警级别。业务规则不仅决定"是否拒绝"，也决定"该多严重地提示" ——
     * 把它放在这里，UI 侧就只需一句 {@code showAlert(level, code.message())}，
     * 不必再散落 {@code if (code == INPUT_DIR_MISSING) ERROR else WARNING} 这类判断。
     *
     * <p>对应原 {@code MainController.onStartProcessing} 的实测行为：
     * 输入/输出目录为空、条目数为 0 → WARNING；输入目录不存在 → ERROR。</p>
     */
    public enum Level { WARNING, ERROR }

    /**
     * 按错误码给出展示级别（未知码保守取 WARNING）。
     * 功能一：{@link ErrorCode#NO_CHECKED_ITEMS} 必须 ERROR（居中卡片、须确认）。
     */
    public static Level levelOf(ErrorCode code) {
        return code == ErrorCode.INPUT_DIR_MISSING || code == ErrorCode.NO_CHECKED_ITEMS
                ? Level.ERROR : Level.WARNING;
    }

    /**
     * 断言形态：条件不满足时返回带级别的拒绝结果，满足时返回 {@code null}。
     *
     * <p>存在的意义是<b>压缩 UI 侧的样板</b>：让控制器写成
     * {@code var d = deny(blankInput(in)); if (d != null) { ... }} 一行一条，
     * 而不是 {@code (x = f()) != null} 那种容易写错、且行数翻倍的形态。</p>
     */
    public record Denial(ErrorCode code, Level level) {
    }

    public static Denial deny(ErrorCode code) {
        return code == null ? null : new Denial(code, levelOf(code));
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
