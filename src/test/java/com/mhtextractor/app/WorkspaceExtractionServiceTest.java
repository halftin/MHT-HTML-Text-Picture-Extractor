package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.processor.BatchProcessor;
import com.mhtextractor.processor.PerFileOverrides;
import com.mhtextractor.util.OutputUnitPlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkspaceExtractionService} 的无头编排回归（Q3 / P2）。
 *
 * <p>用<b>桩单元执行器</b>驱动（不真跑 MHT 解析 —— 产物正确性已由 {@code ProductBaselineTest} 的
 * 32 份黄金基线覆盖）。本类只验"编排"：单元顺序、统计累计、单元失败继续、取消 partial、
 * 进度单调、递归回调透传、请求不可变。</p>
 *
 * <p><b>零 JavaFX</b>：本类不启动工具包，也不引用任何 {@code javafx.*}（由
 * {@code ArchitectureTest.headlessTestPackagesHaveNoJavaFx} 的同类口径约束）。</p>
 */
class WorkspaceExtractionServiceTest {

    @TempDir
    Path tmp;

    private static final String SESSION = "session";

    /** 桩条目视图（与 {@code OutputUnitPipelineTest} 同形，避免依赖 GUI 的 ITEM_VIEW）。 */
    private record TItem(String path, String source, boolean removed,
                         AppConfig textParams, AppConfig imageParams) {
        TItem(String path, String source) {
            this(path, source, false, null, null);
        }
    }

    private static final OutputUnitPlanner.ItemView<TItem> VIEW = new OutputUnitPlanner.ItemView<>() {
        @Override
        public String path(TItem item) {
            return item.path();
        }

        @Override
        public String sourceFolder(TItem item) {
            return item.source();
        }

        @Override
        public boolean removed(TItem item) {
            return item.removed();
        }

        @Override
        public AppConfig textItemParams(TItem item) {
            return item.textParams();
        }

        @Override
        public AppConfig imageItemParams(TItem item) {
            return item.imageParams();
        }
    };

    /**
     * 单元执行器桩：按注入的"单元 → 结果"表返回统计；可追加异常、可捕获递归回调实参与返回值、
     * 可上报进度。记录被调用的单元标签顺序。
     */
    private static final class StubProcessor implements WorkspaceExtractionService.UnitProcessor {

        private final Map<String, BatchProcessor.ProcessStats> byLabel = new HashMap<>();
        private final Map<String, RuntimeException> failures = new HashMap<>();
        private final List<String> calledLabels = new ArrayList<>();
        private Supplier<Boolean> capturedRecursionCallback;
        private Boolean capturedRecursionResult;
        private Supplier<Boolean> capturedCancelSupplier;
        private BatchProcessor.ProgressCallback capturedProgressCallback;
        private Map<String, PerFileOverrides> capturedOverrides;
        private java.util.function.Consumer<String> onProcess;

        StubProcessor returning(String label, BatchProcessor.ProcessStats stats) {
            byLabel.put(label, stats);
            return this;
        }

        /** 每个单元处理时执行（用于在"第 1 个单元之后"翻转取消标志）。 */
        StubProcessor onProcess(java.util.function.Consumer<String> action) {
            this.onProcess = action;
            return this;
        }

        StubProcessor failing(String label, String message) {
            failures.put(label, new RuntimeException(message));
            return this;
        }

        List<String> calledLabels() {
            return calledLabels;
        }

        Supplier<Boolean> capturedRecursionCallback() {
            return capturedRecursionCallback;
        }

        Boolean capturedRecursionResult() {
            return capturedRecursionResult;
        }

        Supplier<Boolean> capturedCancelSupplier() {
            return capturedCancelSupplier;
        }

        BatchProcessor.ProgressCallback capturedProgressCallback() {
            return capturedProgressCallback;
        }

        Map<String, PerFileOverrides> capturedOverrides() {
            return capturedOverrides;
        }

        @Override
        public BatchProcessor.ProcessStats process(OutputUnitPlanner.OutputUnit unit,
                                                  String sessionOutputFolder,
                                                  Map<String, PerFileOverrides> overrides,
                                                  Supplier<Boolean> cancelSupplier,
                                                  Supplier<Boolean> recursionCallback,
                                                  BatchProcessor.ProgressCallback callback) {
            String label = unit.label();
            calledLabels.add(label);
            capturedCancelSupplier = cancelSupplier;
            capturedOverrides = overrides;
            if (recursionCallback != null) {
                capturedRecursionCallback = recursionCallback;
                capturedRecursionResult = recursionCallback.get();
            }
            capturedProgressCallback = callback;
            if (onProcess != null) {
                onProcess.accept(label);
            }
            RuntimeException failure = failures.get(label);
            if (failure != null) {
                throw failure;
            }
            BatchProcessor.ProcessStats stats = byLabel.get(label);
            return stats != null ? stats : new BatchProcessor.ProcessStats();
        }
    }

    /** 记录上报过的总进度序列（验单调不回退）。 */
    private static final class RecordingSink implements WorkspaceExtractionService.ProgressSink {
        private final List<Double> values = new ArrayList<>();

        @Override
        public void update(double overallProgress) {
            values.add(overallProgress);
        }

        List<Double> values() {
            return values;
        }
    }

    private static BatchProcessor.ProcessStats stats(int success, int fail, int skip, int warn,
                                                     List<String> failDetails,
                                                     List<String> skipDetails,
                                                     List<String> warnDetails,
                                                     List<String> resultPaths) {
        BatchProcessor.ProcessStats s = new BatchProcessor.ProcessStats();
        s.setSuccessCount(success);
        s.setFailCount(fail);
        s.setSkipCount(skip);
        s.setWarnCount(warn);
        if (failDetails != null) {
            failDetails.forEach(s::addFailDetail);
        }
        if (skipDetails != null) {
            skipDetails.forEach(s::addSkipDetail);
        }
        if (warnDetails != null) {
            warnDetails.forEach(value -> s.addWarnDetail(new com.mhtextractor.model.ExtractionStats.WarningDetail(
                    "extract.fileWarned", Map.of("file", value), new com.mhtextractor.model.ExtractWarning(
                    "extract.titleFallback", Map.of("name", "fallback")))));
        }
        if (resultPaths != null) {
            for (String p : resultPaths) {
                s.addFileResult(new com.mhtextractor.processor.FileResult(p,
                        com.mhtextractor.model.ExtractOutcome.SUCCESS, null, null, null, List.of()));
            }
        }
        return s;
    }

    /** 建一个文本合并模式的输出单元（单元内文件数 = fileNames.size()）。 */
    private OutputUnitPlanner.OutputUnit unitOf(String source, String... fileNames) {
        List<Object> data = new ArrayList<>();
        for (String name : fileNames) {
            data.add(new TItem(tmp.resolve(source).resolve(name).toString(), tmp.resolve(source).toString()));
        }
        List<OutputUnitPlanner.OutputUnit> units =
                OutputUnitPlanner.plan(data, VIEW, false, new AppConfig(), null, SESSION);
        assertEquals(1, units.size(), "每个数据序应恰好规划出 1 个输出单元");
        return units.get(0);
    }

    private WorkspaceExtractionService.ExtractionRequest request(List<OutputUnitPlanner.OutputUnit> units,
                                                                Map<String, PerFileOverrides> overrides,
                                                                Supplier<Boolean> recursionCallback) {
        return new WorkspaceExtractionService.ExtractionRequest(SESSION, units, overrides,
                false, recursionCallback);
    }

    // ==================== 1. 多单元顺序 ====================

    @Test
    void unitsRunInRequestOrderAndEachExactlyOnce() {
        OutputUnitPlanner.OutputUnit u1 = unitOf("s1", "a.mht");
        OutputUnitPlanner.OutputUnit u2 = unitOf("s2", "b.mht");
        OutputUnitPlanner.OutputUnit u3 = unitOf("s3", "c.mht");
        StubProcessor stub = new StubProcessor();
        WorkspaceExtractionService service = new WorkspaceExtractionService(stub);

        service.run(request(List.of(u1, u2, u3), Map.of(), null), () -> false, v -> { });

        assertEquals(List.of(u1.label(), u2.label(), u3.label()), stub.calledLabels(),
                "单元必须严格按请求列表顺序、且各被调用一次");
    }

    // ==================== 2. 统计累计 ====================

    @Test
    void statsAccumulateAcrossUnits() {
        OutputUnitPlanner.OutputUnit u1 = unitOf("s1", "a.mht");
        OutputUnitPlanner.OutputUnit u2 = unitOf("s2", "b.mht", "c.mht");
        StubProcessor stub = new StubProcessor()
                .returning(u1.label(), stats(2, 1, 3, 4,
                        List.of("f1"), List.of("s1"), List.of("w1"), List.of("p1")))
                .returning(u2.label(), stats(5, 6, 7, 8,
                        List.of("f2", "f3"), List.of("s2"), List.of("w2"), List.of("p2", "p3")));
        WorkspaceExtractionService service = new WorkspaceExtractionService(stub);

        BatchProcessor.ProcessStats total = service
                .run(request(List.of(u1, u2), Map.of(), null), () -> false, v -> { }).stats();

        assertEquals(7, total.getSuccessCount(), "成功数应为两单元之和");
        assertEquals(7, total.getFailCount(), "失败数应为两单元之和");
        assertEquals(10, total.getSkipCount(), "跳过数应为两单元之和");
        assertEquals(12, total.getWarnCount(), "警告数应为两单元之和");
        assertEquals(List.of("f1", "f2", "f3"), total.getFailDetails(), "失败详情应按单元顺序拼接");
        assertEquals(List.of("s1", "s2"), total.getSkipDetails(), "跳过详情应按单元顺序拼接");
        assertEquals(List.of("w1", "w2"), total.getWarnDetails().stream().map(d -> d.params().get("file")).toList(), "警告详情应按单元顺序拼接");
        assertEquals(List.of("p1", "p2", "p3"), total.getFileResults().stream()
                .map(com.mhtextractor.processor.FileResult::path).toList(),
                "结构化单文件结果应按单元顺序拼接");
        assertEquals(3, total.getTotalFiles(), "总文件数 = 各单元文件数之和");
        assertTrue(total.getEndTime() >= total.getStartTime(), "结束时间不应早于开始时间");
    }

    // ==================== 3. 单元失败后继续 ====================

    @Test
    void unitFailureDoesNotStopRemainingUnits() {
        OutputUnitPlanner.OutputUnit u1 = unitOf("s1", "a.mht");
        OutputUnitPlanner.OutputUnit u2 = unitOf("s2", "b.mht");
        OutputUnitPlanner.OutputUnit u3 = unitOf("s3", "c.mht");
        OutputUnitPlanner.OutputUnit u4 = unitOf("s4", "d.mht");
        StubProcessor stub = new StubProcessor()
                .returning(u1.label(), stats(1, 0, 0, 0, null, null, null, null))
                .failing(u2.label(), "boom-单元二")
                .returning(u3.label(), stats(1, 0, 0, 0, null, null, null, null))
                .returning(u4.label(), stats(1, 0, 0, 0, null, null, null, null));
        WorkspaceExtractionService service = new WorkspaceExtractionService(stub);

        BatchProcessor.ProcessStats total = service
                .run(request(List.of(u1, u2, u3, u4), Map.of(), null), () -> false, v -> { }).stats();

        assertEquals(List.of(u1.label(), u2.label(), u3.label(), u4.label()), stub.calledLabels(),
                "中间单元抛异常后，后续单元仍必须被执行");
        assertEquals(1, total.getFailCount(), "失败数应等于抛异常单元的 fileCount()（该单元 1 个文件）");
        assertEquals(3, total.getSuccessCount(), "其余三个单元的结果仍应累加");
        assertEquals(1, total.getFailDetails().size(), "应恰好多出一条单元失败详情");
        assertTrue(total.getFailDetails().get(0).contains(u2.label()),
                "单元失败详情应含单元名，实际：" + total.getFailDetails().get(0));
        assertTrue(total.getFailDetails().get(0).contains("boom-单元二"),
                "单元失败详情应含异常消息，实际：" + total.getFailDetails().get(0));
    }

    @Test
    void unitFailureCountsEveryFileOfThatUnit() {
        OutputUnitPlanner.OutputUnit u1 = unitOf("s1", "a.mht", "b.mht", "c.mht");
        OutputUnitPlanner.OutputUnit u2 = unitOf("s2", "d.mht");
        StubProcessor stub = new StubProcessor()
                .failing(u1.label(), "boom")
                .returning(u2.label(), stats(1, 0, 0, 0, null, null, null, null));
        WorkspaceExtractionService service = new WorkspaceExtractionService(stub);

        BatchProcessor.ProcessStats total = service
                .run(request(List.of(u1, u2), Map.of(), null), () -> false, v -> { }).stats();

        assertEquals(3, total.getFailCount(), "单元失败应按该单元 fileCount() 计失败（3 个文件）");
        assertEquals(1, total.getSuccessCount(), "后续单元的结果仍应累加");
    }

    // ==================== 4. 取消 ====================

    @Test
    void cancelAfterFirstUnitKeepsPartialStatsFromThatUnit() {
        OutputUnitPlanner.OutputUnit u1 = unitOf("s1", "a.mht");
        OutputUnitPlanner.OutputUnit u2 = unitOf("s2", "b.mht");
        OutputUnitPlanner.OutputUnit u3 = unitOf("s3", "c.mht");
        java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger();
        StubProcessor stub = new StubProcessor()
                .returning(u1.label(), stats(2, 0, 0, 0, null, null, null, null))
                .returning(u2.label(), stats(9, 0, 0, 0, null, null, null, null))
                .returning(u3.label(), stats(9, 0, 0, 0, null, null, null, null));
        // 第 1 个单元处理完立刻请求取消 → 服务在第 2 轮循环开头必须 break
        stub.onProcess(label -> cancel.set(true));
        WorkspaceExtractionService service = new WorkspaceExtractionService(stub);

        WorkspaceExtractionService.ExtractionResult result = service.run(
                request(List.of(u1, u2, u3), Map.of(), null), pollCounting(cancel, polls), v -> { });

        assertEquals(List.of(u1.label()), stub.calledLabels(), "取消后不得再执行后续单元");
        assertEquals(2, result.stats().getSuccessCount(),
                "返回的 partial 统计必须含已完成单元的结果（取消路径的单一来源）");
        assertTrue(polls.get() >= 2, "取消信号应被询问多次（每轮单元一次），实际 " + polls.get());
    }

    /** 统计取消信号的询问次数（判断取消前必须查一次）。 */
    private static Supplier<Boolean> pollCounting(java.util.concurrent.atomic.AtomicBoolean cancel,
                                                 java.util.concurrent.atomic.AtomicInteger polls) {
        return () -> {
            polls.incrementAndGet();
            return cancel.get();
        };
    }

    @Test
    void cancelBeforeAnyUnitYieldsEmptyStats() {
        OutputUnitPlanner.OutputUnit u1 = unitOf("s1", "a.mht");
        StubProcessor stub = new StubProcessor()
                .returning(u1.label(), stats(5, 0, 0, 0, null, null, null, null));
        WorkspaceExtractionService service = new WorkspaceExtractionService(stub);

        BatchProcessor.ProcessStats total = service
                .run(request(List.of(u1), Map.of(), null), () -> true, v -> { }).stats();

        assertTrue(stub.calledLabels().isEmpty(), "一开始就取消 → 任何单元都不应被执行");
        assertEquals(0, total.getSuccessCount(), "没有任何单元执行时成功数应为 0");
        assertEquals(1, total.getTotalFiles(), "总文件数仍应是计划的总量");
    }

    // ==================== 5. 进度单调 ====================

    @Test
    void overallProgressIsMonotonicAcrossUnits() {
        OutputUnitPlanner.OutputUnit u1 = unitOf("s1", "a.mht", "b.mht");
        OutputUnitPlanner.OutputUnit u2 = unitOf("s2", "c.mht");
        StubProcessor stub = new StubProcessor()
                .returning(u1.label(), stats(2, 0, 0, 0, null, null, null, null))
                .returning(u2.label(), stats(1, 0, 0, 0, null, null, null, null));
        // 桩只为第 1 个单元（2 个文件）上报两次进度，驱动服务的跨单元映射；
        // 第 2 个单元的聚合器下标语义由下方 UnitProgressAggregator 直接断言覆盖。
        stub.onProcess(label -> {
            if (!u1.label().equals(label)) {
                return;
            }
            BatchProcessor.ProgressCallback cb = stub.capturedProgressCallback();
            if (cb != null) {
                cb.onProgress(1, 2, "a.mht", 0.5);
                cb.onProgress(2, 2, "b.mht", 1.0);
            }
        });        WorkspaceExtractionService service = new WorkspaceExtractionService(stub);
        RecordingSink sink = new RecordingSink();

        service.run(request(List.of(u1, u2), Map.of(), null), () -> false, sink);

        assertTrue(sink.values().size() >= 2, "单元内上报应经服务映射为总进度，实际 " + sink.values());
        double last = -1;
        for (double v : sink.values()) {
            assertTrue(v >= last, "上报的总进度不得回退：" + sink.values());
            assertTrue(v >= 0.0 && v <= 1.0, "总进度必须落在 0..1：" + v);
            last = v;
        }
        // 聚合口径：总文件数 = 2 + 1；单元 0 内第 2 个文件做完 → 2/3
        com.mhtextractor.util.UnitProgressAggregator aggregator =
                new com.mhtextractor.util.UnitProgressAggregator(List.of(2, 1));
        assertEquals(3, aggregator.totalFiles(), "总文件数 = 2 + 1");
        assertTrue(aggregator.overall(0, 1, 0.0) < aggregator.overall(0, 2, 0.0),
                "同一单元内进度必须递增");
        assertTrue(aggregator.overall(0, 2, 0.0) < aggregator.overall(1, 1, 0.0),
                "跨单元进度必须递增（不回退）");
    }

    // ==================== 6. 递归深度回调透传 ====================

    @Test
    void recursionCallbackIsPassedThroughAndItsValueReturned() {
        OutputUnitPlanner.OutputUnit u1 = unitOf("s1", "a.mht");
        StubProcessor stub = new StubProcessor()
                .returning(u1.label(), stats(1, 0, 0, 0, null, null, null, null));
        WorkspaceExtractionService service = new WorkspaceExtractionService(stub);

        service.run(request(List.of(u1), Map.of(), () -> true), () -> false, v -> { });

        assertNotNull(stub.capturedRecursionCallback(), "服务的递归深度回调必须透传给 processor");
        assertEquals(Boolean.TRUE, stub.capturedRecursionResult(),
                "processor 调用回调拿到的返回值必须与注入的决策一致");
    }

    @Test
    void cancelSupplierIsPassedThroughToProcessor() {
        OutputUnitPlanner.OutputUnit u1 = unitOf("s1", "a.mht");
        StubProcessor stub = new StubProcessor()
                .returning(u1.label(), stats(1, 0, 0, 0, null, null, null, null));
        WorkspaceExtractionService service = new WorkspaceExtractionService(stub);
        java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean(false);
        Supplier<Boolean> supplied = cancel::get;

        service.run(request(List.of(u1), Map.of(), null), supplied, v -> { });

        assertSame(supplied, stub.capturedCancelSupplier(),
                "取消信号应原样透传给 processor（不得包一层）");
    }

    @Test
    void overridesArePassedThroughToProcessor() {
        OutputUnitPlanner.OutputUnit u1 = unitOf("s1", "a.mht");
        StubProcessor stub = new StubProcessor()
                .returning(u1.label(), stats(1, 0, 0, 0, null, null, null, null));
        WorkspaceExtractionService service = new WorkspaceExtractionService(stub);
        Map<String, PerFileOverrides> overrides = new LinkedHashMap<>();
        overrides.put("x.mht", new PerFileOverrides(true));

        service.run(request(List.of(u1), overrides, null), () -> false, v -> { });

        assertEquals(overrides, stub.capturedOverrides(),
                "逐文件覆盖应原样透传给 processor");
    }

    // ==================== 7. 请求不可变 ====================

    @Test
    void requestSnapshotIsImmutableAgainstLaterListMutation() {
        OutputUnitPlanner.OutputUnit u1 = unitOf("s1", "a.mht");
        OutputUnitPlanner.OutputUnit u2 = unitOf("s2", "b.mht");
        List<OutputUnitPlanner.OutputUnit> mutable = new ArrayList<>(List.of(u1, u2));
        Map<String, PerFileOverrides> mutableOverrides = new LinkedHashMap<>();
        mutableOverrides.put("a.mht", new PerFileOverrides(true));

        WorkspaceExtractionService.ExtractionRequest request = request(mutable, mutableOverrides, null);
        mutable.clear();
        mutableOverrides.put("b.mht", new PerFileOverrides(false));

        assertEquals(2, request.units().size(), "构造后改动原列表不得影响请求快照");
        assertEquals(1, request.overrides().size(), "构造后改动原覆盖表不得影响请求快照");
        assertThrows(UnsupportedOperationException.class, () -> request.units().add(u1),
                "请求快照的单元列表必须是不可变视图");
        assertThrows(UnsupportedOperationException.class, () -> request.overrides().clear(),
                "请求快照的覆盖表必须是不可变视图");
    }

    // ==================== 补充：异常路径与空单元 ====================

    @Test
    void emptyUnitPlanReturnsZeroedStats() {
        StubProcessor stub = new StubProcessor();
        WorkspaceExtractionService service = new WorkspaceExtractionService(stub);

        BatchProcessor.ProcessStats total = service
                .run(request(List.of(), Map.of(), null), () -> false, v -> { }).stats();

        assertEquals(0, total.getTotalFiles(), "没有单元时总文件数为 0");
        assertEquals(0, total.getSuccessCount(), "没有单元时成功数为 0");
        assertTrue(stub.calledLabels().isEmpty(), "没有单元时不应调用执行器");
    }

    @Test
    void sessionOutputFolderReachesProcessor() {
        OutputUnitPlanner.OutputUnit u1 = unitOf("s1", "a.mht");
        List<String> seenFolders = new ArrayList<>();
        WorkspaceExtractionService service = new WorkspaceExtractionService(
                (unit, sessionFolder, overrides, cancel, recursion, callback) -> {
                    seenFolders.add(sessionFolder);
                    return new BatchProcessor.ProcessStats();
                });

        service.run(request(List.of(u1), Map.of(), null), () -> false, v -> { });

        assertEquals(List.of(SESSION), seenFolders, "会话输出目录必须原样传给执行器");
    }
}
