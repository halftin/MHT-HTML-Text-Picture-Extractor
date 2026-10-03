package com.mhtextractor.processor;

import com.mhtextractor.cache.DirectoryImageSource;
import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.core.MimeParser;
import com.mhtextractor.core.SourceParser;
import com.mhtextractor.core.SourceParsers;
import com.mhtextractor.extractor.ImageExtractor;
import com.mhtextractor.extractor.ImageSource;
import com.mhtextractor.extractor.InMemoryImageSource;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.util.ExtractOutcome;
import com.mhtextractor.util.FileUtils;
import com.mhtextractor.util.SanitizeUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 图片批量处理器
 * 支持流式/高速内存模式、单独输出/合并输出、ZIP/CBZ 打包
 *
 * <p><b>Q12 结构说明</b>：本类现在只承担"对外处理器 + 流程协调器"（指南 §3"保留的主干"）：
 * 初始化统计、校验输出目录、选择预加载/流式、驱动 {@link #processUnified}、记录文件结果与结束统计。
 * 三块实现已移出为同包组件（都是 package-private 顶层类）：</p>
 * <ul>
 *   <li>{@link ImageInputStrategy}（{@link PreloadedImageInput} / {@link StreamingImageInput}）——
 *       两种输入策略不合并（内存占用与首文件解析策略不同，指南 §1.4-1）；</li>
 *   <li>{@link ImageOutputStrategy}（{@link MergedImageOutput} / {@link SeparateImageOutput}）——
 *       两种输出不合并（命名/序号/进度区间/归档失败结果不同，指南 §1.4-2）；</li>
 *   <li>{@link ExtractionPreviewCache} —— 缓存键、可复用判据与提取完成后的清理。</li>
 * </ul>
 * <p>{@code processUnified} 的<b>条件顺序</b>在本次搬迁中一字未改（缓存命中 → 标题解析 →
 * 解析结果可用性 → 无图片内容快速检查 → 提取 → 保存与归档），这是本步唯一的行为保持判据所依赖的骨架。</p>
 */
@Slf4j
public class ImageBatchProcessor extends BatchProcessor {

    private final AppConfig config;
    /** 提取期缓存策略（会话 10 提取复用接线）；构造时 previewCache 为 null = 复用关闭，行为与既有提取完全一致 */
    private final ExtractionPreviewCache previewCache;

    public ImageBatchProcessor(AppConfig config) {
        this(config, null);
    }

    public ImageBatchProcessor(AppConfig config, PreviewCacheLifecycle previewCache) {
        this.config = config;
        this.previewCache = new ExtractionPreviewCache(previewCache, config);
    }

    /**
     * 处理多个 MHT 文件，提取图片（支持进度回调）
     */
    public ProcessStats process(String inputFolder, String outputFolder, ProgressCallback callback) {
        return process(collectMhtFiles(inputFolder), outputFolder, callback);
    }

    /**
     * 处理多个 MHT 文件（按传入顺序处理，支持进度回调）
     */
    public ProcessStats process(List<MhtFileInfo> orderedFiles, String outputFolder,
                                ProgressCallback callback) {
        return process(orderedFiles, outputFolder, null, callback);
    }

    /**
     * 处理多个 MHT 文件（按传入顺序处理，支持进度回调）。
     *
     * @param mergedOutputName 合并输出模式的显式输出目录名（sanitize 后使用）；
     *                         null 时沿用现有命名逻辑（首个文件标题，回退输入目录名）
     */
    public ProcessStats process(List<MhtFileInfo> orderedFiles, String outputFolder,
                                String mergedOutputName, ProgressCallback callback) {
        this.progressCallback = callback;
        ProcessStats stats = new ProcessStats();
        stats.setStartTime(System.currentTimeMillis());

        log.info("========================================");
        log.info(I18n.msg("log.imagebatchprocessor.01"));
        log.info(I18n.msg("log.imagebatchprocessor.02", config.getInputFolder()));
        log.info(I18n.msg("log.imagebatchprocessor.03", outputFolder));
        logImageConfigInfo();
        log.info("========================================");

        if (!FileUtils.ensureDirectory(outputFolder)) {
            log.error(I18n.msg("log.imagebatchprocessor.04", outputFolder));
            stats.setEndTime(System.currentTimeMillis());
            return stats;
        }

        stats.setTotalFiles(orderedFiles.size());

        if (orderedFiles.isEmpty()) {
            log.warn(I18n.msg("log.imagebatchprocessor.05"));
            stats.setEndTime(System.currentTimeMillis());
            return stats;
        }

        log.info(I18n.msg("log.imagebatchprocessor.06", orderedFiles.size()));
        log.info("========================================");

        String mimeParser = orderedFiles.getFirst().getPath();

        if (config.isHighSpeedMemoryMode()) {
            return processWithPreload(orderedFiles, outputFolder, mergedOutputName, mimeParser, stats);
        } else {
            return processStreaming(orderedFiles, outputFolder, mergedOutputName, mimeParser, stats);
        }
    }

    // ==================== 高速内存模式 ====================

    /**
     * 高速内存模式。{@code firstPath} 只是"批里第一个文件路径"的占位（历史上这里是一个
     * {@code MimeParser} 实例）—— 解析器改为<b>逐文件</b>按扩展名选（R6.1：一次导入可以同时
     * 含 {@code .mht} 与 {@code .html}），见 {@link #parserFor}。
     */
    private ProcessStats processWithPreload(List<MhtFileInfo> mhtFiles, String outputFolder,
                                             String mergedOutputName, String firstPath,
                                             ProcessStats stats) {
        log.info(I18n.msg("log.imagebatchprocessor.07"));

        PreloadedImageInput.LoadResult preload = PreloadedImageInput.preload(mhtFiles, firstPath,
                index -> loadFile(mhtFiles, index), this::isCancelled, this::fireProgress);
        if (preload.parsedList().isEmpty()) {
            log.warn(I18n.msg("log.imagebatchprocessor.08"));
        }

        log.info(I18n.msg("log.imagebatchprocessor.09", preload.parsedList().size()));
        log.info("========================================");

        ImageInputStrategy inputs =
                new PreloadedImageInput(preload.parsedList(), preload.loadFailures());
        ImageOutputStrategy outputs;
        String mergedNameWarning = null;

        if (config.isSingleOutputMode()) {
            // 与流式模式一致：仅当第一个文件本身解析成功才用其标题，否则回退默认目录名
            MimeParser.ParsedMht firstParsed = null;
            if (!preload.parsedList().isEmpty() && preload.parsedList().getFirst().fileIndex() == 0) {
                firstParsed = preload.parsedList().getFirst().parsed();
            }
            MergedImageOutput merged = MergedImageOutput.create(config, outputFolder, mhtFiles,
                    firstParsed, stats, mergedOutputName);
            mergedNameWarning = merged.nameWarning();
            outputs = merged;
        } else {
            outputs = new SeparateImageOutput(outputFolder);
        }
        return processUnified(mhtFiles, outputFolder, inputs, outputs, stats, 0.3, 0.3,
                mergedNameWarning);
    }

    // ==================== 流式模式 ====================

    private ProcessStats processStreaming(List<MhtFileInfo> mhtFiles, String outputFolder,
                                           String mergedOutputName, String firstPath,
                                           ProcessStats stats) {
        if (config.isSingleOutputMode()) {
            // 预解析第一个文件用于确定合并目录名
            MimeParser.ParsedMht firstParsed = null;
            try {
                firstParsed = parserFor(mhtFiles.getFirst().getPath())
                        .parse(mhtFiles.getFirst().getPath(), recursionContinueCallback, null, null);
            } catch (Exception e) {
                log.warn(I18n.msg("log.imagebatchprocessor.15", e.getMessage()), e);
            }

            MergedImageOutput merged = MergedImageOutput.create(config, outputFolder, mhtFiles,
                    firstParsed, stats, mergedOutputName);
            String mergedNameWarning = merged.nameWarning();
            var inputs = new StreamingImageInput(mhtFiles, firstParsed,
                    index -> loadFile(mhtFiles, index), this::isCancelled);
            return processUnified(mhtFiles, outputFolder, inputs, merged, stats, 0.35, 0.25,
                    mergedNameWarning);
        } else {
            log.info(I18n.msg("log.imagebatchprocessor.16", mhtFiles.size()));
            log.info("========================================");
            var outputs = new SeparateImageOutput(outputFolder);
            var inputs = new StreamingImageInput(mhtFiles, null,
                    index -> loadFile(mhtFiles, index), this::isCancelled);
            return processUnified(mhtFiles, outputFolder, inputs, outputs, stats, 0.3, 0.3, null);
        }
    }

    /**
     * 解析一个文件的唯一入口（供 {@link StreamingImageInput} / {@link PreloadedImageInput} 的窄回调使用）。
     *
     * <p>进度上报（解析阶段 0.05 起点 + 阶段/字节回调）仍<b>由本类承接</b>，因此输入策略组件不需要认识
     * {@code BatchProcessor} 的进度回调（指南 §3 表格："解析阶段进度和取消仍由传入的窄回调承接"）。</p>
     *
     * <p>文件列表经参数传入（不用实例字段暂存）：输入策略在两种模式下的解析时机不同，把它记在字段上
     * 会让"当前是哪一批"变成可变共享状态，而解析完全在单线程里发生 —— 闭包捕获局部变量更安全。</p>
     */
    private MimeParser.ParsedMht loadFile(List<MhtFileInfo> mhtFiles, int index) throws Exception {
        MhtFileInfo fileInfo = mhtFiles.get(index);
        final int idx = index + 1;
        final String name = fileInfo.getName();
        log.info(I18n.msg("log.imagebatchprocessor.11", name));
        fireProgress(idx, mhtFiles.size(), name, 0.05);
        return parserFor(fileInfo.getPath()).parse(fileInfo.getPath(), recursionContinueCallback,
                stage -> fireParsingProgress(idx, mhtFiles.size(), name, stage),
                progress -> fireProgress(idx, mhtFiles.size(), name, progress));
    }

    /**
     * 逐文件按扩展名选解析器（R6.1/R6.3）：一次导入可以同时含 {@code .mht} 与 {@code .html}，
     * 因此选择必须<b>按文件</b>发生，而不是"按批里第一个文件"定一次。唯一实现点见
     * {@code core.SourceParsers#forPath}。
     *
     * <p><b>为什么是 {@code protected} 实例方法</b>（Q12）：本方法是一次"解析调用"的唯一入口，
     * 因此它也是"某文件被解析了几次"这件事<b>可被观测</b>的唯一位置。测试用同包子类覆写它来
     * 计次（见 {@code ImageExtractionMatrixTest}），从而把"流式合并模式首文件只解析一次"从
     * 一句自述变成可复算的断言；覆写只需包内可见，故不是公开 API 的一部分。</p>
     */
    protected SourceParser parserFor(String path) {
        return SourceParsers.forPath(path);
    }

    // ==================== 统一处理循环 ====================

    /**
     * 统一处理循环。
     *
     * <p>🚩 <b>条件顺序是本步的行为骨架，Q12 搬迁中一字未改</b>：① 取消检查 → ② 缓存命中查找 →
     * ③ 标题解析需求（单独模式 + 名称提取）→ ④ 命中则回退判据（解析不出 HTML）→
     * ⑤ 命中路径（DirectoryImageSource + 缓存警告）或未命中路径（无法解析 SKIPPED → 无图片内容
     * SKIPPED → 提取 → 空结果 SKIPPED）→ ⑥ 保存与归档 → ⑦ 规则 (b) 清缓存 →
     * ⑧ 末尾命名警告并入 + 输出策略收尾。</p>
     */
    private ProcessStats processUnified(List<MhtFileInfo> mhtFiles, String outputFolder,
                                         ImageInputStrategy inputs, ImageOutputStrategy outputs,
                                         ProcessStats stats, double extractionBaseProgress,
                                         double extractionRange, String mergedNameWarning) {
        int total = mhtFiles.size();
        int totalImageCount = 0;

        for (int i = 0; i < total; i++) {
            MhtFileInfo fileInfo = mhtFiles.get(i);
            if (isCancelled()) {
                log.info(I18n.msg("log.imagebatchprocessor.20", i, total));
                break;
            }

            final int idx = i + 1;
            final String name = fileInfo.getName();

            try {
                log.info("----------------------------------------");
                log.info(I18n.msg("log.imagebatchprocessor.21", idx, total, name));

                // —— 会话 10：逐文件查预览缓存（P3 按文件、非按输出单元）。
                // 命中 → 跳过解析与图片提取，直接以 DirectoryImageSource 产出；
                // 未命中 → 走原路径，行为完全不变。
                Optional<ExtractionPreviewCache.CacheHit> found = previewCache.lookup(fileInfo);
                // 单独模式 + 名称提取：输出目录名依赖 HTML 标题（P13 缓存元数据不含标题，
                // 必须提取期重算）→ 命中时仍解析一次取标题，图片不重新提取
                boolean needsTitleParse = found.isPresent()
                        && !config.isSingleOutputMode() && config.isEnableNameExtraction();
                MimeParser.ParsedMht parsed = null;
                if (found.isEmpty() || needsTitleParse) {
                    parsed = inputs.provide(i);
                }
                if (needsTitleParse && (parsed == null || parsed.getHtmlContent() == null)) {
                    // 防御：缓存存在但解析不出 HTML（文件未变时不会发生）→ 回退原路径，
                    // 与未命中路径的「无法解析」SKIPPED 保持一致
                    found = Optional.empty();
                }
                ExtractionPreviewCache.CacheHit hit = found.orElse(null);

                ImageSource imageSource;
                String extractedTitle = null;
                List<String> fileWarnings;

                if (hit != null) {
                    log.info(I18n.msg("log.imagebatchprocessor.22", name, hit.meta().images().size()));
                    fireProgress(idx, total, name, extractionBaseProgress);
                    imageSource = new DirectoryImageSource(hit.meta(),
                            previewCache.dirFor(hit.key()));
                    fileWarnings = new ArrayList<>(hit.meta().warnings());
                    reportExtractorWarnings(name, () -> fileWarnings, stats);
                    if (needsTitleParse) {
                        extractedTitle = new ImageExtractor(parsed,
                                config.getImageNameStartMarker(), config.getImageNameEndMarker(),
                                null, null).extractTitle();
                    }
                } else {
                    if (parsed == null || parsed.getHtmlContent() == null) {
                        log.warn(I18n.msg("log.imagebatchprocessor.13", name));
                        stats.incrementSkip();
                        stats.addSkipDetail(I18n.get("extract.fileSkippedUnparseable", "file", name));
                        recordFileResult(stats, fileInfo.getPath(), ExtractOutcome.SKIPPED,
                                I18n.get("extract.reasonUnparseable"), List.of());
                        fireProgress(idx, total, name, 1.0);
                        continue;
                    }

                    // 快速检查：无任何图片资源。
                    // 修复（步骤 7）：原判据只看 cidMap / locationDataMap，漏了 HTML 内嵌的 Data URI 图片
                    // （`<img src="data:...">` 既无 Content-ID 也无 Content-Location），
                    // 于是「纯 Data URI 的 MHT」在提取**之前**就被判为"无图片内容"而 SKIPPED。
                    // 现补上 Data URI 判据，只有三者皆空才真正跳过。
                    if (parsed.getCidMap().isEmpty() && parsed.getLocationDataMap().isEmpty()
                            && !ExtractionPreviewCache.hasDataUriImages(parsed.getHtmlContent())) {
                        log.info(I18n.msg("log.imagebatchprocessor.24", name));
                        stats.incrementSkip();
                        stats.addSkipDetail(I18n.get("extract.fileSkippedNoImages", "file", name));
                        recordFileResult(stats, fileInfo.getPath(), ExtractOutcome.SKIPPED,
                                I18n.get("extract.reasonNoImages"), List.of());
                        fireProgress(idx, total, name, 1.0);
                        continue;
                    }

                    // 提取图片
                    fireProgress(idx, total, name, extractionBaseProgress);
                    ImageExtractor imageExtractor = new ImageExtractor(parsed,
                            config.getImageNameStartMarker(), config.getImageNameEndMarker(),
                            config.getImageRegionStartMarker(), config.getImageRegionEndMarker());

                    final int fi = i;
                    var images = imageExtractor.extractImages(
                            progress -> fireProgress(fi + 1, total, name,
                                    extractionBaseProgress + extractionRange * progress));
                    fileWarnings = new ArrayList<>(imageExtractor.getLastWarnings());
                    reportExtractorWarnings(name, imageExtractor, stats);

                    if (images.isEmpty()) {
                        log.info(I18n.msg("log.imagebatchprocessor.25", name));
                        stats.incrementSkip();
                        stats.addSkipDetail(I18n.get("extract.fileSkippedNoExtracted", "file", name));
                        recordFileResult(stats, fileInfo.getPath(), ExtractOutcome.SKIPPED,
                                I18n.get("extract.reasonNoExtracted"), fileWarnings);
                        fireProgress(idx, total, name, 1.0);
                        continue;
                    }

                    log.info(I18n.msg("log.imagebatchprocessor.26", name, images.size()));

                    // 提取标题（单独模式需要用于目录命名）
                    if (config.isEnableNameExtraction()) {
                        extractedTitle = imageExtractor.extractTitle();
                    }
                    imageSource = new InMemoryImageSource(images);
                }

                // 保存与归档（委托给输出策略；命中与未命中共用，命名在提取期重算 P13）
                OutputContext ctx = new OutputContext(fileInfo, i, total, outputFolder, stats,
                        this, config);
                int saved = outputs.saveImages(imageSource, extractedTitle, ctx);
                if (saved > 0) {
                    totalImageCount += saved;
                    stats.incrementSuccess();
                    recordFileResult(stats, fileInfo.getPath(), ExtractOutcome.SUCCESS, null, fileWarnings);
                    // 规则 (b)：该项目提取完成（SUCCESS/SUCCESS_WARNED）→ 删其缓存目录。
                    // 命中与否都删（未命中也可能残留旧指纹目录，规则 (a) 的兜底）；
                    // 取消（未处理到该项目）或 FAILED/SKIPPED → 保留缓存以便重试（C10）。
                    previewCache.onExtractionCompleted(fileInfo.getPath());
                } else {
                    String failReason = I18n.get("extract.allImagesWriteFailed", "count", imageSource.size());
                    stats.incrementFail();
                    stats.addFailDetail(I18n.get("extract.fileFailed", "file", name, "reason", failReason));
                    recordFileResult(stats, fileInfo.getPath(), ExtractOutcome.FAILED, failReason, fileWarnings);
                    log.error(I18n.msg("log.imagebatchprocessor.27", name, failReason));
                }

            } catch (Exception e) {
                stats.incrementFail();
                stats.addFailDetail(I18n.get("extract.fileFailed", "file", name, "reason", e.getMessage()));
                recordFileResult(stats, fileInfo.getPath(), ExtractOutcome.FAILED, e.getMessage(), List.of());
                log.error(I18n.msg("log.imagebatchprocessor.28", name), e);
            } finally {
                fireProgress(idx, total, name, 1.0);
            }
        }

        // 合并目录命名警告（未从第一个文件提取到标题）并入该文件结果
        if (mergedNameWarning != null) {
            stats.attachWarningTo(mhtFiles.getFirst().getPath(), mergedNameWarning);
        }

        outputs.onAllFilesProcessed(config, outputFolder, stats);

        if (config.isSingleOutputMode()) {
            log.info("========================================");
            log.info(I18n.msg("log.imagebatchprocessor.29", totalImageCount));
        }

        stats.setEndTime(System.currentTimeMillis());
        log.info("{}", stats);
        return stats;
    }

    private void logImageConfigInfo() {
        if (config.isEnableNameExtraction()) {
            log.info(I18n.msg("log.imagebatchprocessor.39"));
            log.info(I18n.msg("log.imagebatchprocessor.40", config.getImageNameStartMarker()));
            log.info(I18n.msg("log.imagebatchprocessor.41", config.getImageNameEndMarker()));
        }
        if (config.isEnableRegionExtraction()) {
            log.info(I18n.msg("log.imagebatchprocessor.42"));
            log.info(I18n.msg("log.imagebatchprocessor.40", config.getImageRegionStartMarker()));
            log.info(I18n.msg("log.imagebatchprocessor.41", config.getImageRegionEndMarker()));
        }
        if (config.isEnableBatchRenaming()) {
            log.info(I18n.msg("log.imagebatchprocessor.45"));
        }
        if (config.isEnableZipPackaging()) {
            log.info(I18n.msg("log.imagebatchprocessor.46"));
        }
        if (config.isEnableCbzPackaging()) {
            log.info(I18n.msg("log.imagebatchprocessor.47"));
        }
        if (config.isHighSpeedMemoryMode()) {
            log.info(I18n.msg("log.imagebatchprocessor.48"));
        }
    }

    /**
     * 从第一个文件的解析结果确定合并目录名（sanitize 后返回）；提取失败返回 null
     */
    public static String determineMergedFolderName(MimeParser.ParsedMht firstParsed,
                                                   boolean nameExtractEnabled,
                                                   String nameStartMarker, String nameEndMarker) {
        if (!nameExtractEnabled || firstParsed == null || firstParsed.getHtmlContent() == null) {
            return null;
        }
        try {
            ImageExtractor firstExtractor = new ImageExtractor(firstParsed,
                    nameStartMarker, nameEndMarker, null, null);
            String extractedTitle = firstExtractor.extractTitle();
            if (extractedTitle != null && !extractedTitle.isEmpty()) {
                return SanitizeUtils.sanitize(extractedTitle);
            }
        } catch (Exception e) {
            log.warn(I18n.msg("log.imagebatchprocessor.49", e.getMessage()), e);
        }
        return null;
    }

    /**
     * 默认合并目录名：输入目录名（sanitize），回退 All_Images
     */
    public static String determineDefaultFolderName(String inputFolder) {
        if (inputFolder != null && !inputFolder.isEmpty()) {
            String name = new File(inputFolder).getName();
            if (!name.isEmpty()) {
                return SanitizeUtils.sanitize(name);
            }
        }
        return "All_Images";
    }
}
