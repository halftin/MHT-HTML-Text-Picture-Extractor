package com.mhtextractor.app;

import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.processor.BatchProcessor;
import com.mhtextractor.processor.ImageBatchProcessor;
import com.mhtextractor.processor.PerFileOverrides;
import com.mhtextractor.processor.TextBatchProcessor;
import com.mhtextractor.util.OutputUnitPlanner;
import com.mhtextractor.util.UnitProgressAggregator;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 工作区提取服务（Q3 / P2）—— 把「点开始提取之后、结果回到界面之前」的<b>业务段</b>从 GUI 控制器
 * 搬进无头应用层。
 *
 * <p><b>搬进来的是什么</b>：逐输出单元调用 processor、跨单元进度聚合、统计累计（成功/失败/跳过/警告
 * + 三条详情 + 结构化单文件结果）、取消传播（{@code cancelled} 为真即跳出，已完成单元结果保留为
 * partial）、单元失败不中断整批、以及文本/图片的分派（{@link UnitProcessor}）。</p>
 *
 * <p><b>有意不搬的是什么</b>（属渲染层 / 宿主生命周期，留在
 * {@code com.mhtextractor.gui.controller.ExtractionTaskCoordinator}）：</p>
 * <ul>
 *   <li>JavaFX {@code Task} 的创建/启动/取消与 {@code progressProperty} 映射；</li>
 *   <li><b>递归深度超限确认卡</b>：{@code Platform.runLater} + {@code NotificationHost} 只能跑在 FX 线程，
 *       本类只接收一个 {@link Supplier}（{@link ExtractionRequest#recursionCallback()}）并在后台线程同步等待
 *       其返回值 —— 决策机制仍由 GUI 注入，等待语义与搬迁前一致；</li>
 *   <li>提取完成后打开输出目录、满格条三态配色、结果回写列表条目。</li>
 * </ul>
 *
 * <p><b>日志与 i18n 边界</b>：本类<b>只</b>用 {@code log.*} 键（业务层日志已国际化，随
 * {@code log.maincontroller.*} 逐键保持不变），<b>不新增</b>任何 {@code I18n.get} 界面文案，因此
 * {@code ArchitectureTest.businessLayerI18nUsageDoesNotGrow} 的基线（52）不受影响。
 * 唯一的 {@code I18n.get} 是单元失败详情键 {@code extract.unitFailed} —— 它在搬迁前就由提取业务段产出，
 * 是"写入统计详情的业务文案"而非界面文案，原样保留。</p>
 *
 * <p><b>本类零 JavaFX</b>，由 {@code ArchitectureTest.businessPackagesHaveNoUiFrameworkDependency}
 * 按字节码常量池强制。</p>
 */
@Slf4j
public final class WorkspaceExtractionService {

    /**
     * 单元执行器：一次输出单元提取的<b>接缝</b>。生产实现见 {@link #dispatch(PreviewCacheLifecycle)}；
     * 测试可注入桩，从而在不真跑 MHT 解析的前提下驱动统计累计 / 取消 / 失败继续等编排逻辑。
     */
    @FunctionalInterface
    public interface UnitProcessor {
        /**
         * @param unit              输出单元（顺序、单元内文件顺序均由调用方保证）
         * @param sessionOutputFolder 本次会话输出目录（图片单独合并模式用）
         * @param overrides         逐文件参数覆盖（文本 processor 用）
         * @param cancelSupplier    取消信号（透传给 processor）
         * @param recursionCallback 递归深度超限继续回调（后台线程同步等待其返回值）
         * @param callback          单元内进度回调（由本类映射为跨单元总进度）
         */
        BatchProcessor.ProcessStats process(OutputUnitPlanner.OutputUnit unit,
                                            String sessionOutputFolder,
                                            Map<String, PerFileOverrides> overrides,
                                            Supplier<Boolean> cancelSupplier,
                                            Supplier<Boolean> recursionCallback,
                                            BatchProcessor.ProgressCallback callback);
    }

    /** 跨单元总进度接收端（GUI 侧适配为 {@code Task.updateProgress(overall, 1.0)}）。 */
    @FunctionalInterface
    public interface ProgressSink {
        void update(double overallProgress);
    }

    /**
     * 一次提取的<b>不可变请求快照</b>（指南第 1 节第 1 条：不得把 {@code ObservableList} / 控件 /
     * 可继续变化的 {@link AppConfig} 引用交给后台任务）。
     *
     * <p>{@code units} 与 {@code overrides} 在构造时<b>防御性拷贝</b>，此后外部改动原集合不再影响本次提取。
     * 单元自身（{@link OutputUnitPlanner.OutputUnit}）在计划阶段已冻结：文件列表与输出路径都是 final，
     * 参数对象也已补齐运行时字段。</p>
     *
     * @param sessionOutputFolder 本次会话的输出目录（时间戳子目录，FX 线程上先建好）
     * @param units               输出单元快照（<b>执行顺序即列表顺序</b>）
     * @param overrides           逐文件参数覆盖（不可变视图）
     * @param imageMode           本次提取模式；仅作请求元信息记录，
     *                            <b>实际分派依据是单元自己的 {@code params.isImageModeEnabled()}</b>
     *                            （与搬迁前的 {@code processUnit} 完全一致：计划阶段已按该模式写回参数）
     * @param recursionCallback   递归深度超限确认回调（GUI 注入：FX 线程弹卡 + 本线程等待结果）
     */
    public record ExtractionRequest(String sessionOutputFolder,
                                    List<OutputUnitPlanner.OutputUnit> units,
                                    Map<String, PerFileOverrides> overrides,
                                    boolean imageMode,
                                    Supplier<Boolean> recursionCallback) {

        public ExtractionRequest {
            units = List.copyOf(units);
            overrides = Map.copyOf(overrides);
        }
    }

    /**
     * 一次提取的结果。
     *
     * @param stats 统计（取消路径下即"已完成单元"的 partial 统计）
     */
    public record ExtractionResult(BatchProcessor.ProcessStats stats) {
    }

    private final UnitProcessor unitProcessor;

    /**
     * 生产构造：文本/图片分派走 {@link #dispatch(PreviewCacheLifecycle)}。
     *
     * @param previewCacheLifecycle 预览缓存生命周期（图片提取复用缓存：命中即跳过解析与提取，并触发规则 (b)）
     */
    public WorkspaceExtractionService(PreviewCacheLifecycle previewCacheLifecycle) {
        this(dispatch(previewCacheLifecycle));
    }

    /**
     * 接缝构造：注入自定义单元执行器（测试用；生产请用
     * {@link #WorkspaceExtractionService(PreviewCacheLifecycle)}）。
     */
    WorkspaceExtractionService(UnitProcessor unitProcessor) {
        this.unitProcessor = unitProcessor;
    }

    /**
     * 执行一次提取。
     *
     * <p>行为要点（与搬迁前 {@code MainController} 内 {@code Task.call()} 逐字对应）：</p>
     * <ol>
     *   <li>单元顺序 = 请求列表顺序；单元内文件顺序 = {@code unit.getPaths()} 顺序；</li>
     *   <li>{@code cancelled.getAsBoolean()} 为真 → 打 {@code log.maincontroller.34}（实参 = 剩余单元数）
     *       并 <b>break</b>，已完成单元的结果保留在返回的 stats 里（partial）；</li>
     *   <li>单元抛异常 → 打 {@code log.maincontroller.36}、按 {@code fileCount()} 计失败、
     *       追加一条 {@code extract.unitFailed} 详情，然后<b>继续下一个单元</b>；</li>
     *   <li>跨单元进度 = {@link UnitProgressAggregator#overall(int, int, double)}，只增不减。</li>
     * </ol>
     *
     * @param request      不可变请求快照
     * @param cancelled    取消信号（GUI 侧即 {@code Task::isCancelled}）
     * @param progressSink 跨单元总进度接收端（0.0~1.0）；GUI 侧即 {@code Task.updateProgress}
     * @return 提取结果（含 partial 统计）
     */
    public ExtractionResult run(ExtractionRequest request,
                                Supplier<Boolean> cancelled,
                                ProgressSink progressSink) {
        List<OutputUnitPlanner.OutputUnit> units = request.units();
        Map<String, PerFileOverrides> overrides = request.overrides();
        Supplier<Boolean> recursionCallback = request.recursionCallback();
        try {
            UnitProgressAggregator progress = new UnitProgressAggregator(
                    units.stream().map(OutputUnitPlanner.OutputUnit::fileCount).toList());
            BatchProcessor.ProcessStats total = new BatchProcessor.ProcessStats();
            total.setStartTime(System.currentTimeMillis());
            total.setTotalFiles(progress.totalFiles());

            for (int i = 0; i < units.size(); i++) {
                if (cancelled.get()) {
                    log.info(I18n.msg("log.maincontroller.34", units.size() - i));
                    break;
                }
                OutputUnitPlanner.OutputUnit unit = units.get(i);
                log.info(I18n.msg("log.maincontroller.35", unit.label(), unit.fileCount(), unit.getOutputPath()));
                final int unitIndex = i;
                BatchProcessor.ProgressCallback progressCallback =
                        (current, _, _, subProgress) ->
                                progressSink.update(progress.overall(unitIndex, current, subProgress));
                try {
                    BatchProcessor.ProcessStats unitStats = unitProcessor.process(unit,
                            request.sessionOutputFolder(), overrides, cancelled, recursionCallback, progressCallback);
                    total.setSuccessCount(total.getSuccessCount() + unitStats.getSuccessCount());
                    total.setFailCount(total.getFailCount() + unitStats.getFailCount());
                    total.setSkipCount(total.getSkipCount() + unitStats.getSkipCount());
                    total.setWarnCount(total.getWarnCount() + unitStats.getWarnCount());
                    total.getFailDetails().addAll(unitStats.getFailDetails());
                    total.getSkipDetails().addAll(unitStats.getSkipDetails());
                    total.getWarnDetails().addAll(unitStats.getWarnDetails());
                    total.addAllFileResults(unitStats.getFileResults());
                } catch (Exception e) {
                    log.error(I18n.msg("log.maincontroller.36", unit.label()), e);
                    for (int k = 0; k < unit.fileCount(); k++) {
                        total.incrementFail();
                    }
                    total.addFailDetail(I18n.get("extract.unitFailed",
                            "unit", unit.label(), "reason", e.getMessage()));
                }
            }
            total.setEndTime(System.currentTimeMillis());
            log.info("{}", total);
            return new ExtractionResult(total);
        } catch (Exception e) {
            log.error(I18n.msg("log.maincontroller.37"), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 生产单元执行器：文本/图片分派。
     *
     * <p>与搬迁前 {@code MainController.processUnit} 逐字对应：图片模式注入
     * {@code previewCacheLifecycle}（缓存命中即跳过解析与提取）+ 单独输出模式经显式输出名指定组名 / 来源名；
     * 文本单独模式先建目录（失败打 {@code log.maincontroller.40}）再 {@code processSeparate}。</p>
     *
     * @param previewCacheLifecycle 预览缓存生命周期
     */
    static UnitProcessor dispatch(PreviewCacheLifecycle previewCacheLifecycle) {
        return (unit, sessionOutputFolder, overrides, cancelSupplier, recursionCallback, callback) -> {
            AppConfig config = unit.getParams();
            List<BatchProcessor.MhtFileInfo> files = BatchProcessor.buildMhtFileInfoList(unit.getPaths());
            if (config.isImageModeEnabled()) {
                // 会话 10：注入预览缓存生命周期 → 提取复用（命中跳过解析与提取）+ 规则 (b) 完成删除
                ImageBatchProcessor processor = new ImageBatchProcessor(config, previewCacheLifecycle);
                processor.setCancelSupplier(cancelSupplier);
                processor.setRecursionContinueCallback(recursionCallback);
                if (config.isSingleOutputMode()) {
                    return processor.process(files, sessionOutputFolder, unit.getOutputName(), callback);
                }
                return processor.process(files, unit.getOutputPath(), callback);
            } else {
                TextBatchProcessor processor = new TextBatchProcessor(config);
                processor.setCancelSupplier(cancelSupplier);
                processor.setRecursionContinueCallback(recursionCallback);
                if (config.isSeparateFiles()) {
                    File outDir = new File(unit.getOutputPath());
                    if (!outDir.exists() && !outDir.mkdirs()) {
                        log.warn(I18n.msg("log.maincontroller.40", unit.getOutputPath()));
                    }
                    return processor.processSeparate(files, unit.getOutputPath(), callback, overrides);
                }
                return processor.processMerged(files, unit.getOutputPath(), callback, overrides);
            }
        };
    }
}
