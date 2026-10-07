package com.mhtextractor.model;

import java.util.List;

/**
 * 提取统计的中性 DTO（P3-3b-22 起为 gui 对 {@code processor.BatchProcessor.ProcessStats} 的只读快照；
 * P3-B6 随提取任务通道契约迁入 {@code model} —— 端口事件
 * {@code uiport.PortEvent.ExtractionTerminal} / {@code ExtractionResult} 的载荷类型，app 与 gui 共用）：
 * 摘要行 / 失败 / 跳过 / 警告 + 各文件结果。
 *
 * <p>数值口径、null/空处理、字段顺序逐字不变；{@code files} 与每条 {@code warnings} 均做不可变拷贝，
 * 顺序与原 {@code getFileResults()} 一致。</p>
 *
 * <p>本类<b>零 JavaFX、零 I18n</b>，属 {@code model} 层。从批量处理器统计的 1:1 搬运工厂
 * （原 {@code app.ExtractionStats.from}）留在 {@code app.WorkspaceExtractionService#statsFrom}
 * （工厂依赖 {@code processor}，model 不得反向依赖业务包）。</p>
 */
public record ExtractionStats(String summaryLine, int failCount, int skipCount, int warnCount,
                              List<FileOutcome> files) {

    /**
     * 单文件结果快照（{@code processor.FileResult} 的中性映射）：
     * 绝对路径 + 结果类型 + 详情 + 警告列表。
     */
    public record FileOutcome(String path, ExtractOutcome outcome, String message,
                              List<ExtractWarning> warnings) {
    }
}
