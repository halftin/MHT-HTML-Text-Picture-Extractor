package com.mhtextractor.app;

import com.mhtextractor.model.ExtractWarning;
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
 * P3-3b-13：{@link ExtractResultService} 回写口径 —— 按 path 匹配、message 空跳过、
 * warnings 逐个渲染拼接、无内容 → null、返回命中数（{@code byPath.size()} 口径）。
 * 零 JavaFX、零 I18n（渲染与分隔符经入参注入），可在 headless 上下文执行。
 */
class ExtractResultServiceTest {

    private static PreviewItem item(String path) {
        return new PreviewItem(path, "n", LocalDateTime.now(), "src");
    }

    private static FileResult result(String path, ExtractOutcome outcome, String message,
                                     ExtractWarning... warnings) {
        return new FileResult(path, outcome, message, List.of(warnings));
    }

    @Test
    void matchesByPathAndWritesOutcomeWithRenderedDetail() {
        BatchProcessor.ProcessStats stats = new BatchProcessor.ProcessStats();
        stats.addFileResult(result("C:\\a.mht", ExtractOutcome.FAILED, "原因",
                new ExtractWarning("w.key", Map.of("name", "x"))));
        List<PreviewItem> items = List.of(item("C:\\a.mht"), item("C:\\b.mht"));

        int matched = ExtractResultService.apply(stats, items,
                w -> "[" + w.key() + "]", ";");

        assertEquals(1, matched, "命中数 = byPath.size()（与 log.maincontroller.29 同口径）");
        assertEquals(ExtractOutcome.FAILED, items.get(0).extractOutcome);
        assertEquals("原因;[w.key]", items.get(0).extractDetail, "message + 渲染后的警告，分隔符连接");
        assertEquals(ExtractOutcome.NONE, items.get(1).extractOutcome, "未命中条目不动");
        assertNull(items.get(1).extractDetail);
    }

    @Test
    void emptyMessageIsSkippedAndNoContentYieldsNull() {
        BatchProcessor.ProcessStats stats = new BatchProcessor.ProcessStats();
        stats.addFileResult(result("C:\\a.mht", ExtractOutcome.SKIPPED, ""));
        stats.addFileResult(result("C:\\b.mht", ExtractOutcome.SUCCESS, null));
        List<PreviewItem> items = List.of(item("C:\\a.mht"), item("C:\\b.mht"));

        int matched = ExtractResultService.apply(stats, items, w -> "!", " | ");

        assertEquals(2, matched);
        assertEquals(ExtractOutcome.SKIPPED, items.get(0).extractOutcome);
        assertNull(items.get(0).extractDetail, "message 为空且无警告 → null");
        assertEquals(ExtractOutcome.SUCCESS, items.get(1).extractOutcome);
        assertNull(items.get(1).extractDetail, "message null 且无警告 → null");
    }

    @Test
    void warningsAreRenderedOneByOneInOrder() {
        BatchProcessor.ProcessStats stats = new BatchProcessor.ProcessStats();
        stats.addFileResult(result("C:\\a.mht", ExtractOutcome.SUCCESS_WARNED, null,
                new ExtractWarning("w1", Map.of()), new ExtractWarning("w2", Map.of())));
        List<PreviewItem> items = List.of(item("C:\\a.mht"));

        int matched = ExtractResultService.apply(stats, items, w -> "R(" + w.key() + ")", "、");

        assertEquals(1, matched);
        assertEquals("R(w1)、R(w2)", items.get(0).extractDetail, "警告逐个经 renderWarning 渲染后按序连接");
    }

    @Test
    void duplicatePathsKeepLastResultLikeMapPut() {
        BatchProcessor.ProcessStats stats = new BatchProcessor.ProcessStats();
        stats.addFileResult(result("C:\\a.mht", ExtractOutcome.SUCCESS, "first"));
        stats.addFileResult(result("C:\\a.mht", ExtractOutcome.FAILED, "second"));
        List<PreviewItem> items = List.of(item("C:\\a.mht"));

        int matched = ExtractResultService.apply(stats, items, w -> "!", ";");

        assertEquals(1, matched, "byPath.size() 按去重后的 key 数");
        assertEquals(ExtractOutcome.FAILED, items.get(0).extractOutcome);
        assertEquals("second", items.get(0).extractDetail, "同路径后写覆盖（Map.put 语义）");
    }
}
