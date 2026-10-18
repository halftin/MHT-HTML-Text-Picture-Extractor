package com.mhtextractor.app;

import com.mhtextractor.model.ExtractWarning;
import com.mhtextractor.model.ExtractionStats;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.processor.BatchProcessor;
import com.mhtextractor.processor.FileResult;
import com.mhtextractor.model.ExtractOutcome;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ExtractResultService} 回写口径：按 path 匹配、诊断原样保留、警告保持结构与顺序、
 * 返回去重后的结果路径数。回写不进行渲染，可在无头上下文执行。
 *
 * <p>P3-3b-22 起回写入参为 {@link ExtractionStats}：夹具仍按生产路径先建
 * {@code BatchProcessor.ProcessStats} 再经 {@link WorkspaceExtractionService#statsFrom} 搬运，顺带覆盖搬运口径。</p>
 */
class ExtractResultServiceTest {

    private static PreviewItem item(String path) {
        return new PreviewItem(path, "n", LocalDateTime.now(), "src");
    }

    private static FileResult result(String path, ExtractOutcome outcome, String message,
                                     ExtractWarning... warnings) {
        return new FileResult(path, outcome, null, null, message, List.of(warnings));
    }

    @Test
    void matchesByPathAndWritesOutcomeWithNeutralDetails() {
        BatchProcessor.ProcessStats stats = new BatchProcessor.ProcessStats();
        stats.addFileResult(result("C:\\a.mht", ExtractOutcome.FAILED, "原因",
                new ExtractWarning("w.key", Map.of("name", "x"))));
        List<PreviewItem> items = List.of(item("C:\\a.mht"), item("C:\\b.mht"));

        int matched = ExtractResultService.apply(WorkspaceExtractionService.statsFrom(stats), items);

        assertEquals(1, matched, "命中数 = byPath.size()（与 log.maincontroller.29 同口径）");
        assertEquals(ExtractOutcome.FAILED, items.get(0).extractOutcome);
        assertEquals("原因", items.get(0).extractMessageDetail);
        assertEquals(List.of(new ExtractWarning("w.key", Map.of("name", "x"))), items.get(0).extractWarnings);
        assertEquals(ExtractOutcome.NONE, items.get(1).extractOutcome, "未命中条目不动");
        assertNull(items.get(1).extractMessageDetail);
    }

    @Test
    void emptyAndNullDiagnosticsArePreserved() {
        BatchProcessor.ProcessStats stats = new BatchProcessor.ProcessStats();
        stats.addFileResult(result("C:\\a.mht", ExtractOutcome.SKIPPED, ""));
        stats.addFileResult(result("C:\\b.mht", ExtractOutcome.SUCCESS, null));
        List<PreviewItem> items = List.of(item("C:\\a.mht"), item("C:\\b.mht"));

        int matched = ExtractResultService.apply(WorkspaceExtractionService.statsFrom(stats), items);

        assertEquals(2, matched);
        assertEquals(ExtractOutcome.SKIPPED, items.get(0).extractOutcome);
        assertEquals("", items.get(0).extractMessageDetail, "原始诊断不渲染、不归一化");
        assertEquals(ExtractOutcome.SUCCESS, items.get(1).extractOutcome);
        assertNull(items.get(1).extractMessageDetail);
    }

    @Test
    void warningsRemainStructuredAndOrdered() {
        BatchProcessor.ProcessStats stats = new BatchProcessor.ProcessStats();
        stats.addFileResult(result("C:\\a.mht", ExtractOutcome.SUCCESS_WARNED, null,
                new ExtractWarning("w1", Map.of()), new ExtractWarning("w2", Map.of())));
        List<PreviewItem> items = List.of(item("C:\\a.mht"));

        int matched = ExtractResultService.apply(WorkspaceExtractionService.statsFrom(stats), items);

        assertEquals(1, matched);
        assertEquals(List.of("w1", "w2"), items.get(0).extractWarnings.stream().map(ExtractWarning::key).toList());
    }

    @Test
    void duplicatePathsKeepLastResultLikeMapPut() {
        BatchProcessor.ProcessStats stats = new BatchProcessor.ProcessStats();
        stats.addFileResult(result("C:\\a.mht", ExtractOutcome.SUCCESS, "first"));
        stats.addFileResult(result("C:\\a.mht", ExtractOutcome.FAILED, "second"));
        List<PreviewItem> items = List.of(item("C:\\a.mht"));

        int matched = ExtractResultService.apply(WorkspaceExtractionService.statsFrom(stats), items);

        assertEquals(1, matched, "byPath.size() 按去重后的 key 数");
        assertEquals(ExtractOutcome.FAILED, items.get(0).extractOutcome);
        assertEquals("second", items.get(0).extractMessageDetail, "同路径后写覆盖（Map.put 语义）");
    }
}
