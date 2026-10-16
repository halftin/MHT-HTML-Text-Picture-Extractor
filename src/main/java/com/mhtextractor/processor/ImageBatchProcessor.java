package com.mhtextractor.processor;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.core.MimeParser;
import com.mhtextractor.extractor.ImageExtractor;
import com.mhtextractor.output.Archiver;
import com.mhtextractor.output.ImageWriter;
import com.mhtextractor.util.FileUtils;
import com.mhtextractor.util.SanitizeUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 图片批量处理器
 * 支持流式/高速内存模式、单独输出/合并输出、ZIP/CBZ 打包
 */
@Slf4j
public class ImageBatchProcessor extends BatchProcessor {

    private record ParsedFileEntry(MimeParser.ParsedMht parsed, int fileIndex) {}

    private final AppConfig config;

    public ImageBatchProcessor(AppConfig config) {
        this.config = config;
    }

    /**
     * 处理多个 MHT 文件，提取图片（支持进度回调）
     */
    public ProcessStats process(String inputFolder, String outputFolder, ProgressCallback callback) {
        this.progressCallback = callback;
        ProcessStats stats = new ProcessStats();
        stats.setStartTime(System.currentTimeMillis());

        log.info("========================================");
        log.info("MHT 批量图片处理开始");
        log.info("输入文件夹：{}", inputFolder);
        log.info("输出文件夹：{}", outputFolder);
        logImageConfigInfo();
        log.info("========================================");

        if (!FileUtils.ensureDirectory(outputFolder)) {
            log.error("无法创建输出目录：{}", outputFolder);
            stats.setEndTime(System.currentTimeMillis());
            return stats;
        }

        var mhtFiles = collectMhtFiles(inputFolder);
        stats.setTotalFiles(mhtFiles.size());

        if (mhtFiles.isEmpty()) {
            log.warn("[警告] 未找到任何 MHT 文件");
            stats.setEndTime(System.currentTimeMillis());
            return stats;
        }

        log.info("找到 {} 个 MHT 文件", mhtFiles.size());
        log.info("========================================");

        MimeParser mimeParser = new MimeParser();

        if (config.isHighSpeedMemoryMode()) {
            return processWithPreload(mhtFiles, outputFolder, mimeParser, stats);
        } else {
            return processStreaming(mhtFiles, outputFolder, mimeParser, stats);
        }
    }

    // ==================== 高速内存模式 ====================

    private ProcessStats processWithPreload(List<MhtFileInfo> mhtFiles, String outputFolder,
                                             MimeParser mimeParser, ProcessStats stats) {
        log.info("高速内存模式：正在预加载所有 MHT 文件到内存...");

        List<ParsedFileEntry> parsedList = preloadAll(mhtFiles, mimeParser, stats);
        if (parsedList.isEmpty()) {
            log.warn("无法从任何 MHT 文件中提取内容");
            stats.setEndTime(System.currentTimeMillis());
            return stats;
        }

        log.info("所有文件已加载到内存（{} 个文件），开始处理...", parsedList.size());
        log.info("========================================");

        ParsedMhtProvider provider = new PreloadedProvider(parsedList);
        ImageOutputHandler handler;

        if (config.isSingleOutputMode()) {
            var mh = new MergedOutputHandler();
            setupMergedOutput(outputFolder, mhtFiles, parsedList.get(0).parsed(), stats, mh);
            handler = mh;
        } else {
            handler = new SeparateOutputHandler(outputFolder);
        }
        return processUnified(mhtFiles, outputFolder, provider, handler, stats, 0.3, 0.3);
    }

    private List<ParsedFileEntry> preloadAll(List<MhtFileInfo> mhtFiles, MimeParser mimeParser,
                                              ProcessStats stats) {
        List<ParsedFileEntry> parsedList = new ArrayList<>();
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
                    log.warn("文件 {} 无法解析，跳过", name);
                    stats.incrementSkip();
                    stats.addSkipDetail(String.format("文件：%s 跳过，跳过原因：无法解析", name));
                }
            } catch (Exception e) {
                log.error("加载 MHT 文件 {} 失败: {}", fileInfo.getName(), e.getMessage(), e);
                stats.incrementFail();
                stats.addFailDetail(String.format("文件：%s 失败，失败原因：加载 MHT 文件失败: %s",
                        fileInfo.getName(), e.getMessage()));
            } finally {
                fireProgress(i + 1, mhtFiles.size(), fileInfo.getName(), 1.0);
            }
        }
        return parsedList;
    }

    // ==================== 流式模式 ====================

    private ProcessStats processStreaming(List<MhtFileInfo> mhtFiles, String outputFolder,
                                           MimeParser mimeParser, ProcessStats stats) {
        if (config.isSingleOutputMode()) {
            // 预解析第一个文件用于确定合并目录名
            MimeParser.ParsedMht firstParsed = null;
            try {
                firstParsed = mimeParser.parse(mhtFiles.get(0).getPath(), recursionContinueCallback, null, null);
            } catch (Exception e) {
                log.warn("预解析第一个文件失败: {}", e.getMessage(), e);
            }

            var handler = new MergedOutputHandler();
            setupMergedOutput(outputFolder, mhtFiles, firstParsed, stats, handler);
            var provider = new StreamingProvider(mimeParser, mhtFiles, firstParsed);
            return processUnified(mhtFiles, outputFolder, provider, handler, stats, 0.35, 0.25);
        } else {
            log.info("流式单独输出模式：共 {} 个文件待处理", mhtFiles.size());
            log.info("========================================");
            var handler = new SeparateOutputHandler(outputFolder);
            var provider = new StreamingProvider(mimeParser, mhtFiles, null);
            return processUnified(mhtFiles, outputFolder, provider, handler, stats, 0.3, 0.3);
        }
    }

    // ==================== 合并输出设置 ====================

    private void setupMergedOutput(String outputFolder, List<MhtFileInfo> mhtFiles,
                                    MimeParser.ParsedMht firstParsed, ProcessStats stats,
                                    MergedOutputHandler handler) {
        String folderName = determineMergedFolderName(firstParsed, mhtFiles, stats);
        String mergedDir = Paths.get(outputFolder, folderName).toString();
        FileUtils.ensureDirectory(mergedDir);
        handler.init(mergedDir, folderName);

        log.info("合并输出模式：所有图片合并到目录: {}", mergedDir);
        log.info("共 {} 个文件待处理", mhtFiles.size());
        log.info("========================================");
    }

    // ==================== 统一处理循环 ====================

    private ProcessStats processUnified(List<MhtFileInfo> mhtFiles, String outputFolder,
                                         ParsedMhtProvider parseProvider, ImageOutputHandler outputHandler,
                                         ProcessStats stats, double extractionBaseProgress, double extractionRange) {
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

                // 解析（或从缓存获取）
                MimeParser.ParsedMht parsed = parseProvider.provide(i);
                if (parsed == null || parsed.getHtmlContent() == null) {
                    log.warn("文件 {} 无法解析，跳过", name);
                    stats.incrementSkip();
                    stats.addSkipDetail(String.format("文件：%s 跳过，跳过原因：无法解析", name));
                    fireProgress(idx, total, name, 1.0);
                    continue;
                }

                // 快速检查：无任何图片资源
                if (parsed.getCidMap().isEmpty() && parsed.getLocationDataMap().isEmpty()) {
                    log.info("[跳过] 无图片内容：{}", name);
                    stats.incrementSkip();
                    stats.addSkipDetail(String.format("文件：%s 跳过，跳过原因：无图片内容", name));
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
                reportExtractorWarnings(name, imageExtractor, stats);

                if (images.isEmpty()) {
                    log.info("[跳过] 未提取到图片：{}", name);
                    stats.incrementSkip();
                    stats.addSkipDetail(String.format("文件：%s 跳过，跳过原因：未提取到图片", name));
                    fireProgress(idx, total, name, 1.0);
                    continue;
                }

                log.info("[提取] {} 共 {} 张图片", name, images.size());

                // 提取标题（单独模式需要用于目录命名）
                String extractedTitle = null;
                if (config.isEnableNameExtraction()) {
                    extractedTitle = imageExtractor.extractTitle();
                }

                // 保存与归档（委托给输出处理器）
                int saved = outputHandler.saveImages(images, extractedTitle, fileInfo, i, total, stats);
                if (saved > 0) {
                    totalImageCount += saved;
                }

            } catch (Exception e) {
                stats.incrementFail();
                stats.addFailDetail(String.format("文件：%s 失败，失败原因：%s", name, e.getMessage()));
                log.error("[异常] 处理文件失败：{}", name, e);
            } finally {
                fireProgress(idx, total, name, 1.0);
            }
        }

        outputHandler.afterAll(outputFolder);

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
        int saveImages(List<ImageExtractor.ImageData> images, String extractedTitle,
                       MhtFileInfo fileInfo, int fileIndex, int totalFiles, ProcessStats stats);
        void afterAll(String outputFolder);
    }

    // ==================== 解析策略实现 ====================

    private class PreloadedProvider implements ParsedMhtProvider {
        private final List<ParsedFileEntry> entries;

        PreloadedProvider(List<ParsedFileEntry> entries) {
            this.entries = entries;
        }

        @Override
        public MimeParser.ParsedMht provide(int index) {
            return entries.get(index).parsed();
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
        public int saveImages(List<ImageExtractor.ImageData> images, String extractedTitle,
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
            log.info("[成功] {} → 合并目录 ({} 张，累计 {} 张)",
                    fn, saved, globalIndex.get() - 1);
            stats.incrementSuccess();
            return saved;
        }

        @Override
        public void afterAll(String outputFolder) {
            archiveMergedOutput(mergedDir, folderName, outputFolder);
        }
    }

    private class SeparateOutputHandler implements ImageOutputHandler {
        private final String outputFolder;

        SeparateOutputHandler(String outputFolder) {
            this.outputFolder = outputFolder;
        }

        @Override
        public int saveImages(List<ImageExtractor.ImageData> images, String extractedTitle,
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
            log.info("[成功] {} → {} ({} 张)", name, fileOutputDir, saved);

            // 逐文件 ZIP/CBZ 打包
            archiveWithCleanup(fileOutputDir, outputFolder, baseName,
                    idx, totalFiles, name, 0.85, 0.05, 0.05);
            fireProgress(idx, totalFiles, name, 0.95);

            stats.incrementSuccess();
            return saved;
        }

        @Override
        public void afterAll(String outputFolder) {
            // 逐文件归档，无需批量操作
        }
    }

    // ==================== 工具方法 ====================

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

    private String determineMergedFolderName(MimeParser.ParsedMht firstParsed,
                                             List<MhtFileInfo> mhtFiles, ProcessStats stats) {
        String folderName;
        boolean nameExtractEnabled = config.isEnableNameExtraction();

        if (nameExtractEnabled && firstParsed != null && firstParsed.getHtmlContent() != null) {
            try {
                ImageExtractor firstExtractor = new ImageExtractor(firstParsed,
                        config.getImageNameStartMarker(), config.getImageNameEndMarker(),
                        config.getImageRegionStartMarker(), config.getImageRegionEndMarker());
                String extractedTitle = firstExtractor.extractTitle();
                if (extractedTitle != null && !extractedTitle.isEmpty()) {
                    return SanitizeUtils.sanitize(extractedTitle);
                }
            } catch (Exception e) {
                log.warn("从第一个文件提取标题失败: {}", e.getMessage(), e);
            }
            folderName = determineDefaultFolderName();
            stats.incrementWarn();
            stats.addWarnDetail(String.format("文件：%s 出现警告，警告信息：未从第一个文件提取到标题，使用默认名称：%s",
                    mhtFiles.get(0).getName(), folderName));
            log.warn("[警告] 未从第一个文件提取到标题，使用默认名称：{}", folderName);
        } else {
            folderName = determineDefaultFolderName();
        }
        return folderName;
    }

    private String determineDefaultFolderName() {
        String inputFolder = config.getInputFolder();
        if (inputFolder != null && !inputFolder.isEmpty()) {
            String name = new File(inputFolder).getName();
            if (name != null && !name.isEmpty()) {
                return SanitizeUtils.sanitize(name);
            }
        }
        return "All_Images";
    }

    private void archiveMergedOutput(String mergedDir, String folderName, String outputFolder) {
        archiveWithCleanup(mergedDir, outputFolder, folderName, -1, 0, null, 0, 0, 0);
    }

    private void archiveWithCleanup(String sourceDir, String outputFolder, String baseName,
                                     int fileIdx, int totalFiles, String fileName,
                                     double zipBase, double zipRange, double cbzRange) {
        if (!config.isEnableZipPackaging() && !config.isEnableCbzPackaging()) return;
        File dir = new File(sourceDir);
        int fileCount = dir.listFiles() == null ? 0 : dir.listFiles().length;
        if (config.isEnableZipPackaging()) {
            Archiver.createZip(sourceDir, Paths.get(outputFolder, baseName + ".zip").toString(), count -> {
                if (fileIdx >= 0) {
                    fireProgress(fileIdx, totalFiles, fileName,
                            zipBase + zipRange * ((double) count / Math.max(fileCount, 1)));
                } else {
                    log.debug("ZIP 打包进度：{}/{}", count, fileCount);
                }
            });
        }
        if (config.isEnableCbzPackaging()) {
            Archiver.createCbz(sourceDir, Paths.get(outputFolder, baseName + ".cbz").toString(), count -> {
                if (fileIdx >= 0) {
                    fireProgress(fileIdx, totalFiles, fileName,
                            zipBase + zipRange + cbzRange * ((double) count / Math.max(fileCount, 1)));
                } else {
                    log.debug("CBZ 打包进度：{}/{}", count, fileCount);
                }
            });
        }
        FileUtils.deleteDirectory(dir);
        log.info("已删除临时文件夹：{}", sourceDir);
    }
}
