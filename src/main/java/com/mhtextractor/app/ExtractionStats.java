package com.mhtextractor.app;

import com.mhtextractor.model.ExtractOutcome;
import com.mhtextractor.model.ExtractWarning;
import com.mhtextractor.processor.BatchProcessor;

import java.util.List;

/**
 * 提取统计的中性 DTO（P3-3b-22）：{@code gui} 对 {@link BatchProcessor.ProcessStats} 只读 4 个值
 * （摘要行 / 失败 / 跳过 / 警告）+ 各文件结果 —— 本类把这两块<b>原样搬运</b>成不可变快照，
 * gui 只用本 DTO，不再直连 {@code processor} 包。
 *
 * <p>数值口径、null/空处理、字段顺序逐字不变：{@link #from(BatchProcessor.ProcessStats)} 1:1 搬运
 * {@code getSummaryLine()/getFailCount()/getSkipCount()/getWarnCount()/getFileResults()}，
 * {@code files} 与每条 {@code warnings} 均做不可变拷贝，顺序与 {@code getFileResults()} 一致。</p>
 *
 * <p>本类<b>零 JavaFX、零 I18n</b>，属 {@code app} 层。</p>
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

    /**
     * 从批量处理器统计 1:1 搬运（摘要行在<b>此刻</b>按当前 locale 求值，与原调用点时机一致）。
     *
     * @param stats 批量处理统计
     * @return 不可变快照（files/warnings 均拷贝，顺序不变）
     */
    public static ExtractionStats from(BatchProcessor.ProcessStats stats) {
        List<FileOutcome> files = stats.getFileResults().stream()
                .map(r -> new FileOutcome(r.path(), r.outcome(), r.message(), List.copyOf(r.warnings())))
                .toList();
        return new ExtractionStats(stats.getSummaryLine(), stats.getFailCount(), stats.getSkipCount(),
                stats.getWarnCount(), files);
    }
}
