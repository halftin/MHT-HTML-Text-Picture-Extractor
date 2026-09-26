package com.mhtextractor.app;

import com.mhtextractor.i18n.ErrorCode;

import java.io.File;

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
            /** 列表中的条目总数（含被移除的） */
            int totalItems,
            /** 当前可见（未被移除）的条目数 */
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

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
