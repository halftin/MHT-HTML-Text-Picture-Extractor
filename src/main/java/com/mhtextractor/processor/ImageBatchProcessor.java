package com.mhtextractor.processor;

import com.mhtextractor.cache.DirectoryImageSource;
import com.mhtextractor.cache.PreviewCacheKey;
import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.cache.PreviewCacheMeta;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.core.MimeParser;
import com.mhtextractor.extractor.ImageExtractor;
import com.mhtextractor.extractor.ImageSource;
import com.mhtextractor.extractor.InMemoryImageSource;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.output.Archiver;
import com.mhtextractor.output.ImageWriter;
import com.mhtextractor.util.ExtractOutcome;
import com.mhtextractor.util.FileUtils;
import com.mhtextractor.util.SanitizeUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 图片批量处理器
 * 支持流式/高速内存模式、单独输出/合并输出、ZIP/CBZ 打包
 */
@Slf4j
public class ImageBatchProcessor extends BatchProcessor {

    private record ParsedFileEntry(MimeParser.ParsedMht parsed, int fileIndex) {}

    /** 预加载结果：解析成功的条目 + 解析抛异常的文件（原始下标 → 异常消息，供 processUnified 记 FAILED） */
    private record PreloadResult(List<ParsedFileEntry> parsedList, Map<Integer, String> loadFailures) {}

    private final AppConfig config;
    /** 预览缓存生命周期（会话 10 提取复用接线）；null = 复用关闭，行为与既有提取完全一致 */
    private final PreviewCacheLifecycle previewCache;

    public ImageBatchProcessor(AppConfig config) {
        this(config, null);
    }

    public ImageBatchProcessor(AppConfig config, PreviewCacheLifecycle previewCache) {
        this.config = config;
        this.previewCache = previewCache;
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
        log.info("MHT 批量图片处理开始");
        log.info("输入文件夹：{}", config.getInputFolder());
        log.info("输出文件夹：{}", outputFolder);
        logImageConfigInfo();
        log.info("========================================");

        if (!FileUtils.ensureDirectory(outputFolder)) {
            log.error("无法创建输出目录：{}", outputFolder);
            stats.setEndTime(System.currentTimeMillis());
            return stats;
        }

        stats.setTotalFiles(orderedFiles.size());

        if (orderedFiles.isEmpty()) {
            log.warn("[警告] 未找到任何 MHT 文件");
            stats.setEndTime(System.currentTimeMillis());
            return stats;
        }

        log.info("找到 {} 个 MHT 文件", orderedFiles.size());
        log.info("========================================");

        MimeParser mimeParser = new MimeParser();

        if (config.isHighSpeedMemoryMode()) {
            return processWithPreload(orderedFiles, outputFolder, mergedOutputName, mimeParser, stats);
        } else {
            return processStreaming(orderedFiles, outputFolder, mergedOutputName, mimeParser, stats);
        }
    }

    // ==================== 高速内存模式 ====================

    private ProcessStats processWithPreload(List<MhtFileInfo> mhtFiles, String outputFolder,
                                             String mergedOutputName, MimeParser mimeParser,
                                             ProcessStats stats) {
        log.info("高速内存模式：正在预加载所有 MHT 文件到内存...");

        PreloadResult preload = preloadAll(mhtFiles, mimeParser);
        if (preload.parsedList().isEmpty()) {
            log.warn("无法从任何 MHT 文件中提取内容（各文件结果见下方处理明细）");
        }

        log.info("所有文件已加载到内存（{} 个文件），开始处理...", preload.parsedList().size());
        log.info("========================================");

        ParsedMhtProvider provider = new PreloadedProvider(preload.parsedList(), preload.loadFailures());
        ImageOutputHandler handler;
        String mergedNameWarning = null;

        if (config.isSingleOutputMode()) {
            var mh = new MergedOutputHandler();
            // 与流式模式一致：仅当第一个文件本身解析成功才用其标题，否则回退默认目录名
            MimeParser.ParsedMht firstParsed = null;
            if (!preload.parsedList().isEmpty() && preload.parsedList().get(0).fileIndex() == 0) {
                firstParsed = preload.parsedList().get(0).parsed();
            }
            mergedNameWarning =
                    setupMergedOutput(outputFolder, mhtFiles, firstParsed, stats, mh, mergedOutputName);
            handler = mh;
        } else {
            handler = new SeparateOutputHandler(outputFolder);
        }
        return processUnified(mhtFiles, outputFolder, provider, handler, stats, 0.3, 0.3,
                mergedNameWarning);
    }

    private PreloadResult preloadAll(List<MhtFileInfo> mhtFiles, MimeParser mimeParser) {
        List<ParsedFileEntry> parsedList = new ArrayList<>();
        Map<Integer, String> loadFailures = new HashMap<>();
        for (int i = 0; i < mhtFiles.size(); i++) {
            MhtFileInfo fileInfo = mhtFiles.get(i);
            if (isCancelled()) {
                log.info("用户取消处理，已加载 {} / {} 个文件", i, mhtFiles.size());
                break;
            }
            try {
                log.info("[解析] {}", fileInfo.getName());
                final int idx = i + 1;
                final String name = fileInfo.getName();
                fireProgress(idx, mhtFiles.size(), name, 0.05);
                MimeParser.ParsedMht parsed = mimeParser.parse(fileInfo.getPath(), recursionContinueCallback,
                        stage -> fireParsingProgress(idx, mhtFiles.size(), name, stage),
                        progress -> fireProgress(idx, mhtFiles.size(), name, progress));
                if (parsed.getHtmlContent() != null) {
                    fireProgress(idx, mhtFiles.size(), name, 0.35);
                    parsedList.add(new ParsedFileEntry(parsed, i));
                    fireProgress(idx, mhtFiles.size(), name, 0.5);
                    log.info("已加载：{}", name);
                } else {
                    // 不在此记录结果：统一由 processUnified 记录（GUI 按路径取最后一条，双记录会互相覆盖）
                    log.warn("文件 {} 无法解析，跳过", name);
                }
            } catch (Exception e) {
                log.error("加载 MHT 文件 {} 失败: {}", fileInfo.getName(), e.getMessage(), e);
                loadFailures.put(i, e.getMessage() == null ? I18n.get("extract.unknownError") : e.getMessage());
            } finally {
                fireProgress(i + 1, mhtFiles.size(), fileInfo.getName(), 1.0);
            }
        }
        return new PreloadResult(parsedList, loadFailures);
    }

    // ==================== 流式模式 ====================

    private ProcessStats processStreaming(List<MhtFileInfo> mhtFiles, String outputFolder,
                                           String mergedOutputName, MimeParser mimeParser,
                                           ProcessStats stats) {
        if (config.isSingleOutputMode()) {
            // 预解析第一个文件用于确定合并目录名
            MimeParser.ParsedMht firstParsed = null;
            try {
                firstParsed = mimeParser.parse(mhtFiles.get(0).getPath(), recursionContinueCallback, null, null);
            } catch (Exception e) {
                log.warn("预解析第一个文件失败: {}", e.getMessage(), e);
            }

            var handler = new MergedOutputHandler();
            String mergedNameWarning =
                    setupMergedOutput(outputFolder, mhtFiles, firstParsed, stats, handler, mergedOutputName);
            var provider = new StreamingProvider(mimeParser, mhtFiles, firstParsed);
            return processUnified(mhtFiles, outputFolder, provider, handler, stats, 0.35, 0.25,
                    mergedNameWarning);
        } else {
            log.info("流式单独输出模式：共 {} 个文件待处理", mhtFiles.size());
            log.info("========================================");
            var handler = new SeparateOutputHandler(outputFolder);
            var provider = new StreamingProvider(mimeParser, mhtFiles, null);
            return processUnified(mhtFiles, outputFolder, provider, handler, stats, 0.3, 0.3, null);
        }
    }

    // ==================== 合并输出设置 ====================

    private String setupMergedOutput(String outputFolder, List<MhtFileInfo> mhtFiles,
                                     MimeParser.ParsedMht firstParsed, ProcessStats stats,
                                     MergedOutputHandler handler, String explicitName) {
        String folderName = explicitName;
        String nameWarning = null;
        if (folderName != null && !folderName.isEmpty()) {
            folderName = SanitizeUtils.sanitize(folderName);
            if (folderName.isEmpty()) {
                folderName = null;
            }
        }
        if (folderName == null) {
            folderName = determineMergedFolderName(firstParsed, config.isEnableNameExtraction(),
                    config.getImageNameStartMarker(), config.getImageNameEndMarker());
            if (folderName == null) {
                folderName = determineDefaultFolderName(config.getInputFolder());
                if (config.isEnableNameExtraction() && firstParsed != null && firstParsed.getHtmlContent() != null) {
                    nameWarning = I18n.get("extract.titleFallback", "name", folderName);
                    stats.incrementWarn();
                    stats.addWarnDetail(I18n.get("extract.fileWarned",
                            "file", mhtFiles.get(0).getName(), "message", nameWarning));
                    log.warn("[警告] 未从第一个文件提取到标题，使用默认名称：{}", folderName);
                }
            }
        }
        String mergedDir = Paths.get(outputFolder, folderName).toString();
        FileUtils.ensureDirectory(mergedDir);
        handler.init(mergedDir, folderName);

        log.info("合并输出模式：所有图片合并到目录: {}", mergedDir);
        log.info("共 {} 个文件待处理", mhtFiles.size());
        log.info("========================================");
        return nameWarning;
    }

    // ==================== 统一处理循环 ====================

    private ProcessStats processUnified(List<MhtFileInfo> mhtFiles, String outputFolder,
                                         ParsedMhtProvider parseProvider, ImageOutputHandler outputHandler,
                                         ProcessStats stats, double extractionBaseProgress,
                                         double extractionRange, String mergedNameWarning) {
        int total = mhtFiles.size();
        int totalImageCount = 0;

        for (int i = 0; i < total; i++) {
            MhtFileInfo fileInfo = mhtFiles.get(i);
            if (isCancelled()) {
                log.info("用户取消处理，已处理 {} / {} 个文件", i, total);
                break;
            }

            final int idx = i + 1;
            final String name = fileInfo.getName();

            try {
                log.info("----------------------------------------");
                log.info("[处理] [{}/{}] {}", idx, total, name);

                // —— 会话 10：逐文件查预览缓存（P3 按文件、非按输出单元）。
                // 命中 → 跳过解析与图片提取，直接以 DirectoryImageSource 产出；
                // 未命中 → 走原路径，行为完全不变。
                CacheHit hit = lookupPreviewCache(fileInfo);
                // 单独模式 + 名称提取：输出目录名依赖 HTML 标题（P13 缓存元数据不含标题，
                // 必须提取期重算）→ 命中时仍解析一次取标题，图片不重新提取
                boolean needsTitleParse = hit != null
                        && !config.isSingleOutputMode() && config.isEnableNameExtraction();
                MimeParser.ParsedMht parsed = null;
                if (hit == null || needsTitleParse) {
                    parsed = parseProvider.provide(i);
                }
                if (needsTitleParse && (parsed == null || parsed.getHtmlContent() == null)) {
                    // 防御：缓存存在但解析不出 HTML（文件未变时不会发生）→ 回退原路径，
                    // 与未命中路径的「无法解析」SKIPPED 保持一致
                    hit = null;
                }

                ImageSource imageSource;
                String extractedTitle = null;
                List<String> fileWarnings;

                if (hit != null) {
                    log.info("[缓存命中] {}（跳过解析与图片提取，复用 {} 张）",
                            name, hit.meta().images().size());
                    fireProgress(idx, total, name, extractionBaseProgress);
                    imageSource = new DirectoryImageSource(hit.meta(),
                            previewCache.store().dirFor(hit.key()));
                    fileWarnings = new ArrayList<>(hit.meta().warnings());
                    reportExtractorWarnings(name, () -> fileWarnings, stats);
                    if (needsTitleParse) {
                        extractedTitle = new ImageExtractor(parsed,
                                config.getImageNameStartMarker(), config.getImageNameEndMarker(),
                                null, null).extractTitle();
                    }
                } else {
                    if (parsed == null || parsed.getHtmlContent() == null) {
                        log.warn("文件 {} 无法解析，跳过", name);
                        stats.incrementSkip();
                        stats.addSkipDetail(I18n.get("extract.fileSkippedUnparseable", "file", name));
                        recordFileResult(stats, fileInfo.getPath(), ExtractOutcome.SKIPPED,
                                I18n.get("extract.reasonUnparseable"), List.of());
                        fireProgress(idx, total, name, 1.0);
                        continue;
                    }

                    // 快速检查：无任何图片资源
                    if (parsed.getCidMap().isEmpty() && parsed.getLocationDataMap().isEmpty()) {
                        log.info("[跳过] 无图片内容：{}", name);
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
                    final String fn = name;
                    var images = imageExtractor.extractImages(
                            progress -> fireProgress(fi + 1, total, fn,
                                    extractionBaseProgress + extractionRange * progress));
                    fileWarnings = new ArrayList<>(imageExtractor.getLastWarnings());
                    reportExtractorWarnings(name, imageExtractor, stats);

                    if (images.isEmpty()) {
                        log.info("[跳过] 未提取到图片：{}", name);
                        stats.incrementSkip();
                        stats.addSkipDetail(I18n.get("extract.fileSkippedNoExtracted", "file", name));
                        recordFileResult(stats, fileInfo.getPath(), ExtractOutcome.SKIPPED,
                                I18n.get("extract.reasonNoExtracted"), fileWarnings);
                        fireProgress(idx, total, name, 1.0);
                        continue;
                    }

                    log.info("[提取] {} 共 {} 张图片", name, images.size());

                    // 提取标题（单独模式需要用于目录命名）
                    if (config.isEnableNameExtraction()) {
                        extractedTitle = imageExtractor.extractTitle();
                    }
                    imageSource = new InMemoryImageSource(images);
                }

                // 保存与归档（委托给输出处理器；命中与未命中共用，命名在提取期重算 P13）
                int saved = outputHandler.saveImages(imageSource, extractedTitle, fileInfo, i, total, stats);
                if (saved > 0) {
                    totalImageCount += saved;
                    stats.incrementSuccess();
                    recordFileResult(stats, fileInfo.getPath(), ExtractOutcome.SUCCESS, null, fileWarnings);
                    // 规则 (b)：该项目提取完成（SUCCESS/SUCCESS_WARNED）→ 删其缓存目录。
                    // 命中与否都删（未命中也可能残留旧指纹目录，规则 (a) 的兜底）；
                    // 取消（未处理到该项目）或 FAILED/SKIPPED → 保留缓存以便重试（C10）。
                    // hasCachedEntries 守卫：会话缓存为空时跳过逐文件扫描
                    if (previewCache != null && previewCache.hasCachedEntries()) {
                        previewCache.onExtractionCompleted(fileInfo.getPath());
                    }
                } else {
                    String failReason = I18n.get("extract.allImagesWriteFailed", "count", imageSource.size());
                    stats.incrementFail();
                    stats.addFailDetail(I18n.get("extract.fileFailed", "file", name, "reason", failReason));
                    recordFileResult(stats, fileInfo.getPath(), ExtractOutcome.FAILED, failReason, fileWarnings);
                    log.error("[失败] {}：{}", name, failReason);
                }

            } catch (Exception e) {
                stats.incrementFail();
                stats.addFailDetail(I18n.get("extract.fileFailed", "file", name, "reason", e.getMessage()));
                recordFileResult(stats, fileInfo.getPath(), ExtractOutcome.FAILED, e.getMessage(), List.of());
                log.error("[异常] 处理文件失败：{}", name, e);
            } finally {
                fireProgress(idx, total, name, 1.0);
            }
        }

        // 合并目录命名警告（未从第一个文件提取到标题）并入该文件结果
        if (mergedNameWarning != null) {
            stats.attachWarningTo(mhtFiles.get(0).getPath(), mergedNameWarning);
        }

        outputHandler.afterAll(outputFolder, stats);

        if (config.isSingleOutputMode()) {
            log.info("========================================");
            log.info("所有文件处理完毕，共提取 {} 张图片", totalImageCount);
        }

        stats.setEndTime(System.currentTimeMillis());
        log.info("{}", stats);
        return stats;
    }

    // ==================== 策略接口 ====================

    @FunctionalInterface
    private interface ParsedMhtProvider {
        MimeParser.ParsedMht provide(int index) throws Exception;
    }

    private interface ImageOutputHandler {
        int saveImages(ImageSource images, String extractedTitle,
                       MhtFileInfo fileInfo, int fileIndex, int totalFiles, ProcessStats stats);
        void afterAll(String outputFolder, ProcessStats stats);
    }

    // ==================== 解析策略实现 ====================

    private class PreloadedProvider implements ParsedMhtProvider {
        // provide 的参数是完整文件列表下标：必须按 fileIndex 查表，不能按 entries 循环位置取
        // （解析失败的文件不在表里 → 返回 null，由 processUnified 记 SKIPPED）
        private final Map<Integer, MimeParser.ParsedMht> byFileIndex = new HashMap<>();
        private final Map<Integer, String> loadFailures;

        PreloadedProvider(List<ParsedFileEntry> entries, Map<Integer, String> loadFailures) {
            for (ParsedFileEntry entry : entries) {
                byFileIndex.put(entry.fileIndex(), entry.parsed());
            }
            this.loadFailures = loadFailures;
        }

        @Override
        public MimeParser.ParsedMht provide(int index) throws Exception {
            String failure = loadFailures.get(index);
            if (failure != null) {
                throw new RuntimeException("加载 MHT 文件失败: " + failure);
            }
            return byFileIndex.get(index);
        }
    }

    private class StreamingProvider implements ParsedMhtProvider {
        private final MimeParser parser;
        private final List<MhtFileInfo> files;
        private MimeParser.ParsedMht preParsedFirst;

        StreamingProvider(MimeParser parser, List<MhtFileInfo> files, MimeParser.ParsedMht preParsedFirst) {
            this.parser = parser;
            this.files = files;
            this.preParsedFirst = preParsedFirst;
        }

        @Override
        public MimeParser.ParsedMht provide(int index) throws Exception {
            if (index == 0 && preParsedFirst != null) {
                var result = preParsedFirst;
                preParsedFirst = null;
                return result;
            }

            MhtFileInfo fi = files.get(index);
            final int idx = index + 1;
            final String name = fi.getName();
            log.info("[解析] {}", name);
            fireProgress(idx, files.size(), name, 0.05);
            return parser.parse(fi.getPath(), recursionContinueCallback,
                    stage -> fireParsingProgress(idx, files.size(), name, stage),
                    progress -> fireProgress(idx, files.size(), name, progress));
        }
    }

    // ==================== 输出策略实现 ====================

    private class MergedOutputHandler implements ImageOutputHandler {
        private String mergedDir;
        private String folderName;
        private final AtomicInteger globalIndex = new AtomicInteger(1);

        void init(String mergedDir, String folderName) {
            this.mergedDir = mergedDir;
            this.folderName = folderName;
        }

        @Override
        public int saveImages(ImageSource images, String extractedTitle,
                               MhtFileInfo fileInfo, int fileIndex, int totalFiles, ProcessStats stats) {
            String filePrefix = "";
            if (!config.isEnableBatchRenaming() && totalFiles > 1) {
                filePrefix = String.format("f%02d_", fileIndex + 1);
            }

            final int fi = fileIndex;
            final String fn = fileInfo.getName();
            fireProgress(fi + 1, totalFiles, fn, 0.6);

            int imageCount = images.size();
            int saved = ImageWriter.saveImagesWithGlobalIndex(images, mergedDir, "jpg",
                    config.isEnableBatchRenaming(), filePrefix, globalIndex,
                    count -> {
                        double p = 0.6 + 0.3 * ((double) count / imageCount);
                        fireProgress(fi + 1, totalFiles, fn, p);
                    });

            fireProgress(fi + 1, totalFiles, fn, 0.9);
            if (saved > 0) {
                log.info("[成功] {} → 合并目录 ({} 张，累计 {} 张)",
                        fn, saved, globalIndex.get() - 1);
            } else {
                log.warn("[失败] {} → 合并目录：{} 张图片全部写入失败", fn, imageCount);
            }
            return saved;
        }

        @Override
        public void afterAll(String outputFolder, ProcessStats stats) {
            if (!archiveMergedOutput(mergedDir, folderName, outputFolder)) {
                stats.incrementFail();
                stats.addFailDetail(I18n.get("extract.archiveFailedKeep", "dir", mergedDir));
                log.error("[归档] 合并输出归档失败，图片保留在目录：{}", mergedDir);
            }
        }
    }

    private class SeparateOutputHandler implements ImageOutputHandler {
        private final String outputFolder;

        SeparateOutputHandler(String outputFolder) {
            this.outputFolder = outputFolder;
        }

        @Override
        public int saveImages(ImageSource images, String extractedTitle,
                               MhtFileInfo fileInfo, int fileIndex, int totalFiles, ProcessStats stats) {
            final int idx = fileIndex + 1;
            final String name = fileInfo.getName();

            // 确定输出目录名
            String baseName;
            if (extractedTitle != null && !extractedTitle.isEmpty()) {
                baseName = SanitizeUtils.sanitize(extractedTitle);
            } else {
                baseName = getOutputDirName(name);
            }
            String fileOutputDir = Paths.get(outputFolder, baseName).toString();
            FileUtils.ensureDirectory(fileOutputDir);

            // 保存图片
            fireProgress(idx, totalFiles, name, 0.6);
            int imageCount = images.size();
            int saved = ImageWriter.saveImagesConcurrent(images, fileOutputDir, "jpg",
                    config.isEnableBatchRenaming(), "", count -> {
                        double p = 0.6 + 0.25 * ((double) count / imageCount);
                        fireProgress(idx, totalFiles, name, p);
                    });
            fireProgress(idx, totalFiles, name, 0.85);
            if (saved > 0) {
                log.info("[成功] {} → {} ({} 张)", name, fileOutputDir, saved);
            } else {
                log.warn("[失败] {} → {}：{} 张图片全部写入失败", name, fileOutputDir, imageCount);
            }

            // 逐文件 ZIP/CBZ 打包（由全局配置决定）；失败则保留源目录并抛异常 → processUnified 记 FAILED
            if (!archiveWithCleanup(fileOutputDir, outputFolder, baseName,
                    idx, totalFiles, name, 0.85, 0.05, 0.05)) {
                throw new RuntimeException(String.format("图片归档失败，图片保留在目录：%s", fileOutputDir));
            }
            fireProgress(idx, totalFiles, name, 0.95);

            return saved;
        }

        @Override
        public void afterAll(String outputFolder, ProcessStats stats) {
            // 逐文件归档，无需批量操作
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 会话 10：逐文件查预览缓存（P3 按文件、P9 四要素命中）。
     * 指纹取单元已解析参数（本处理器 config）：OutputUnitPlanner 按「来源 + 参数内容」分桶，
     * 同一单元内各文件生效图片参数完全相同（H7 收敛），按文件再解析只会得到同一指纹；
     * 且条目级参数表不在本层可见。预览落盘时用的正是同一生效参数（effectiveForObject），
     * 因此预览指纹与提取指纹必然同源。
     *
     * <p>除 store.load 的四要素 + .complete + 图片文件存在性校验外，额外要求：
     * 元数据含 ≥1 张图、且 ≥1 张有非空来源标识（cid）。后者等价于未命中路径的
     * 「无图片内容」快速检查（cidMap/locationDataMap 非空）会放行——只有能证明
     * 未命中路径会走到提取阶段的缓存才可复用；0 图或全 data URI 的缓存按未命中处理
     * （回全量路径，结果与无缓存时完全一致）。
     *
     * @return 命中；任何不满足（含 IO 失败、meta 损坏）→ null（走原路径）
     */
    private CacheHit lookupPreviewCache(MhtFileInfo fileInfo) {
        if (previewCache == null) {
            return null;
        }
        PreviewCacheKey key;
        try {
            key = PreviewCacheKey.compute(Path.of(fileInfo.getPath()),
                    config.imageExtractionFingerprint(), PreviewCacheMeta.FORMAT_VERSION);
        } catch (IOException e) {
            log.warn("[缓存] 计算缓存键失败，按未命中处理：{}（{}）", fileInfo.getName(), e.getMessage());
            return null;
        }
        try {
            Optional<PreviewCacheMeta> meta = previewCache.store().load(key);
            if (meta.isEmpty()) {
                return null;
            }
            PreviewCacheMeta m = meta.get();
            boolean reusable = !m.images().isEmpty()
                    && m.images().stream().anyMatch(e -> e.sourceId() != null && !e.sourceId().isEmpty());
            if (!reusable) {
                log.info("[缓存] 缓存不含可复现的图片（0 张或全 data URI），按未命中处理：{}",
                        fileInfo.getName());
                return null;
            }
            return new CacheHit(key, m);
        } catch (IOException e) {
            log.warn("[缓存] 读取预览缓存失败，按未命中处理：{}（{}）", fileInfo.getName(), e.getMessage());
            return null;
        }
    }

    /** 会话 10：命中结果的携带类型（key 用于取缓存目录与规则 (b) 删除，meta 提供图片与警告） */
    private record CacheHit(PreviewCacheKey key, PreviewCacheMeta meta) {}

    private void logImageConfigInfo() {
        if (config.isEnableNameExtraction()) {
            log.info("图片名称提取：已启用");
            log.info("  起始标记：{}", config.getImageNameStartMarker());
            log.info("  结束标记：{}", config.getImageNameEndMarker());
        }
        if (config.isEnableRegionExtraction()) {
            log.info("图片区域限定：已启用");
            log.info("  起始标记：{}", config.getImageRegionStartMarker());
            log.info("  结束标记：{}", config.getImageRegionEndMarker());
        }
        if (config.isEnableBatchRenaming()) {
            log.info("批量重命名：已启用");
        }
        if (config.isEnableZipPackaging()) {
            log.info("ZIP 打包：已启用");
        }
        if (config.isEnableCbzPackaging()) {
            log.info("CBZ 打包：已启用");
        }
        if (config.isHighSpeedMemoryMode()) {
            log.info("高速内存模式：已启用");
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
            log.warn("从第一个文件提取标题失败: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * 默认合并目录名：输入目录名（sanitize），回退 All_Images
     */
    public static String determineDefaultFolderName(String inputFolder) {
        if (inputFolder != null && !inputFolder.isEmpty()) {
            String name = new File(inputFolder).getName();
            if (name != null && !name.isEmpty()) {
                return SanitizeUtils.sanitize(name);
            }
        }
        return "All_Images";
    }

    private boolean archiveMergedOutput(String mergedDir, String folderName, String outputFolder) {
        return archiveWithCleanup(mergedDir, outputFolder, folderName, -1, 0, null, 0, 0, 0);
    }

    private boolean archiveWithCleanup(String sourceDir, String outputFolder, String baseName,
                                       int fileIdx, int totalFiles, String fileName,
                                       double zipBase, double zipRange, double cbzRange) {
        if (!config.isEnableZipPackaging() && !config.isEnableCbzPackaging()) return true;
        File dir = new File(sourceDir);
        File[] children = dir.listFiles();
        int childCount = children == null ? 0 : children.length;
        if (childCount == 0) {
            FileUtils.deleteDirectory(dir);
            log.info("归档目录为空，已删除空目录：{}", sourceDir);
            return true;
        }
        boolean zipOk = true;
        boolean cbzOk = true;
        if (config.isEnableZipPackaging()) {
            zipOk = Archiver.createZip(sourceDir, Paths.get(outputFolder, baseName + ".zip").toString(), count -> {
                if (fileIdx >= 0) {
                    fireProgress(fileIdx, totalFiles, fileName,
                            zipBase + zipRange * ((double) count / Math.max(childCount, 1)));
                } else {
                    log.debug("ZIP 打包进度：{}/{}", count, childCount);
                }
            });
        }
        if (config.isEnableCbzPackaging()) {
            cbzOk = Archiver.createCbz(sourceDir, Paths.get(outputFolder, baseName + ".cbz").toString(), count -> {
                if (fileIdx >= 0) {
                    fireProgress(fileIdx, totalFiles, fileName,
                            zipBase + zipRange + cbzRange * ((double) count / Math.max(childCount, 1)));
                } else {
                    log.debug("CBZ 打包进度：{}/{}", count, childCount);
                }
            });
        }
        if (!zipOk || !cbzOk) {
            log.error("[归档] 归档失败（zip={}, cbz={}），保留源目录不删除：{}", zipOk, cbzOk, sourceDir);
            return false;
        }
        FileUtils.deleteDirectory(dir);
        log.info("已删除临时文件夹：{}", sourceDir);
        return true;
    }
}
