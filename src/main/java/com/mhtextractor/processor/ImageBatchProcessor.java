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

    private final AppConfig config;
    private ProgressCallback progressCallback;

    public ImageBatchProcessor(AppConfig config) {
        this.config = config;
    }

    /**
     * 设置进度回调
     */
    public void setProgressCallback(ProgressCallback callback) {
        this.progressCallback = callback;
    }

    /**
     * 处理多个 MHT 文件，提取图片
     *
     * @param inputFolder 输入目录
     * @param outputFolder 输出目录
     * @return 处理统计
     */
    public ProcessStats process(String inputFolder, String outputFolder) {
        return process(inputFolder, outputFolder, null);
    }

    /**
     * 处理多个 MHT 文件，提取图片（支持进度回调）
     *
     * @param inputFolder 输入目录
     * @param outputFolder 输出目录
     * @param callback 进度回调（可为 null）
     * @return 处理统计
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
            // 高速内存模式：预加载所有文件到内存
            return processHighSpeedMode(mhtFiles, outputFolder, mimeParser, stats);
        } else {
            // 流式模式：逐个处理
            if (config.isSingleOutputMode()) {
                // 合并输出模式：所有图片放到同一个目录，全局计数器编号
                return processStreamMergedMode(mhtFiles, outputFolder, mimeParser, stats);
            } else {
                // 单独输出模式：每个文件一个子目录
                return processStreamSeparateMode(mhtFiles, outputFolder, mimeParser, stats);
            }
        }
    }

    /**
     * 高速内存模式：预加载所有 MHT 到内存后处理
     */
    private ProcessStats processHighSpeedMode(List<MhtFileInfo> mhtFiles, String outputFolder,
                                              MimeParser mimeParser, ProcessStats stats) {
        log.info("高速内存模式：正在预加载所有 MHT 文件到内存...");

        List<MimeParser.ParsedMht> parsedList = new ArrayList<>();
        for (int i = 0; i < mhtFiles.size(); i++) {
            MhtFileInfo fileInfo = mhtFiles.get(i);
            if (isCancelled()) {
                log.info("用户取消处理，已加载 {} / {} 个文件", i, mhtFiles.size());
                break;
            }
            try {
                log.info("[解析] {}", fileInfo.getName());
                MimeParser.ParsedMht parsed = mimeParser.parse(fileInfo.getPath(), recursionContinueCallback);
                if (parsed.getHtmlContent() != null) {
                    parsedList.add(parsed);
                    log.info("已加载：{}", fileInfo.getName());
                } else {
                    log.warn("文件 {} 无法解析，跳过", fileInfo.getName());
                    stats.setSkipCount(stats.getSkipCount() + 1);
                }
            } catch (Exception e) {
                log.error("加载 MHT 文件 {} 失败: {}", fileInfo.getName(), e.getMessage());
                stats.setFailCount(stats.getFailCount() + 1);
            } finally {
                fireProgress(i + 1, mhtFiles.size(), fileInfo.getName());
            }
        }

        if (parsedList.isEmpty()) {
            log.warn("无法从任何 MHT 文件中提取内容");
            stats.setEndTime(System.currentTimeMillis());
            return stats;
        }

        log.info("所有文件已加载到内存（{} 个文件），开始处理...", parsedList.size());
        log.info("========================================");

        if (config.isSingleOutputMode()) {
            // 合并输出模式：所有图片放到同一个目录，全局计数器编号
            return processHighSpeedMergedMode(mhtFiles, parsedList, outputFolder, stats);
        } else {
            // 单独输出模式：每个文件独立子目录
            for (int i = 0; i < parsedList.size(); i++) {
                if (isCancelled()) {
                    log.info("用户取消处理，已处理 {} / {} 个文件", i, parsedList.size());
                    break;
                }
                try {
                    MhtFileInfo fileInfo = mhtFiles.get(i);
                    processSingleImageInternal(parsedList.get(i), fileInfo, outputFolder, mhtFiles, i, stats);
                } catch (Exception e) {
                    stats.setFailCount(stats.getFailCount() + 1);
                    log.error("[异常] 处理文件失败：{}", mhtFiles.get(i).getName(), e);
                } finally {
                    fireProgress(i + 1, parsedList.size(), mhtFiles.get(i).getName());
                }
            }
        }

        stats.setEndTime(System.currentTimeMillis());
        log.info("========================================");
        log.info("{}", stats);
        log.info("========================================");
        return stats;
    }

    /**
     * 高速内存模式 - 合并输出：全局计数器编号
     */
    private ProcessStats processHighSpeedMergedMode(List<MhtFileInfo> mhtFiles,
                                                    List<MimeParser.ParsedMht> parsedList,
                                                    String outputFolder, ProcessStats stats) {
        MimeParser mimeParser = new MimeParser();
        String folderName = determineMergedFolderName(mhtFiles, mimeParser);
        String mergedDir = Paths.get(outputFolder, folderName).toString();
        FileUtils.ensureDirectory(mergedDir);

        AtomicInteger globalIndex = new AtomicInteger(1);
        int totalImageCount = 0;

        log.info("高速合并模式：所有图片合并到目录: {}", mergedDir);
        log.info("========================================");

        for (int i = 0; i < parsedList.size(); i++) {
            if (isCancelled()) {
                log.info("用户取消处理，已处理 {} / {} 个文件", i, parsedList.size());
                break;
            }
            try {
                MhtFileInfo fileInfo = mhtFiles.get(i);
                MimeParser.ParsedMht parsed = parsedList.get(i);

                ImageExtractor imageExtractor = new ImageExtractor(parsed,
                        config.getImageNameStartMarker(), config.getImageNameEndMarker(),
                        config.getImageRegionStartMarker(), config.getImageRegionEndMarker());

                List<ImageExtractor.ImageData> images = imageExtractor.extractImages();
                if (images.isEmpty()) {
                    log.info("[跳过] 未提取到图片：{}", fileInfo.getName());
                    fireProgress(i + 1, parsedList.size(), fileInfo.getName());
                    continue;
                }

                log.info("[提取] {} 共 {} 张图片", fileInfo.getName(), images.size());

                String filePrefix = "";
                if (!config.isEnableBatchRenaming() && parsedList.size() > 1) {
                    filePrefix = String.format("f%02d_", i + 1);
                }

                int saved = ImageWriter.saveImagesWithGlobalIndex(images, mergedDir, "jpg",
                        config.isEnableBatchRenaming(), filePrefix, globalIndex);
                totalImageCount += saved;

                stats.setSuccessCount(stats.getSuccessCount() + 1);
                log.info("[成功] {} → 合并目录 ({} 张，累计 {} 张)",
                        fileInfo.getName(), saved, totalImageCount);

            } catch (Exception e) {
                stats.setFailCount(stats.getFailCount() + 1);
                log.error("[异常] 处理文件失败：{}", mhtFiles.get(i).getName(), e);
            } finally {
                fireProgress(i + 1, parsedList.size(), mhtFiles.get(i).getName());
            }
        }

        log.info("========================================");
        log.info("所有文件处理完毕，共提取 {} 张图片", totalImageCount);

        if (config.isEnableZipPackaging() || config.isEnableCbzPackaging()) {
            if (config.isEnableZipPackaging()) {
                String zipPath = Paths.get(outputFolder, folderName + ".zip").toString();
                Archiver.createZip(mergedDir, zipPath);
            }
            if (config.isEnableCbzPackaging()) {
                String cbzPath = Paths.get(outputFolder, folderName + ".cbz").toString();
                Archiver.createCbz(mergedDir, cbzPath);
            }
            FileUtils.deleteDirectory(new File(mergedDir));
            log.info("已删除临时文件夹：{}", mergedDir);
        }

        stats.setEndTime(System.currentTimeMillis());
        log.info("========================================");
        log.info("{}", stats);
        log.info("========================================");
        return stats;
    }

    /**
     * 流式模式：逐个读取和处理 MHT 文件（合并输出 - 全局计数器）
     */
    private ProcessStats processStreamMergedMode(List<MhtFileInfo> mhtFiles, String outputFolder,
                                                 MimeParser mimeParser, ProcessStats stats) {
        // 1. 确定合并输出目录名称：尝试从第一个文件提取标题
        String folderName = determineMergedFolderName(mhtFiles, mimeParser);
        String mergedDir = Paths.get(outputFolder, folderName).toString();
        FileUtils.ensureDirectory(mergedDir);

        // 2. 全局计数器，跨文件递增（001, 002, 003...）
        AtomicInteger globalIndex = new AtomicInteger(1);
        int totalImageCount = 0;

        log.info("流式合并输出模式：共 {} 个文件待处理", mhtFiles.size());
        log.info("合并输出目录：{}", mergedDir);
        log.info("========================================");

        for (int i = 0; i < mhtFiles.size(); i++) {
            MhtFileInfo fileInfo = mhtFiles.get(i);
            if (isCancelled()) {
                log.info("用户取消处理，已处理 {} / {} 个文件", i, mhtFiles.size());
                break;
            }
            log.info("----------------------------------------");
            log.info("流式合并处理文件 [{}/{}]: {}", (i + 1), mhtFiles.size(), fileInfo.getName());

            try {
                log.info("[解析] {}", fileInfo.getName());
                MimeParser.ParsedMht parsed = mimeParser.parse(fileInfo.getPath(), recursionContinueCallback);
                if (parsed.getHtmlContent() == null) {
                    log.warn("文件 {} 无法解析，跳过", fileInfo.getName());
                    stats.setSkipCount(stats.getSkipCount() + 1);
                    fireProgress(i + 1, mhtFiles.size(), fileInfo.getName());
                    continue;
                }

                ImageExtractor imageExtractor = new ImageExtractor(parsed,
                        config.getImageNameStartMarker(), config.getImageNameEndMarker(),
                        config.getImageRegionStartMarker(), config.getImageRegionEndMarker());

                List<ImageExtractor.ImageData> images = imageExtractor.extractImages();
                if (images.isEmpty()) {
                    log.info("[跳过] 未提取到图片：{}", fileInfo.getName());
                    fireProgress(i + 1, mhtFiles.size(), fileInfo.getName());
                    continue;
                }

                log.info("[提取] {} 共 {} 张图片", fileInfo.getName(), images.size());

                // 加文件索引前缀防止同名覆盖（仅在不启用批量重命名时）
                String filePrefix = "";
                if (!config.isEnableBatchRenaming() && mhtFiles.size() > 1) {
                    filePrefix = String.format("f%02d_", i + 1);
                }

                // 使用全局计数器并发保存
                int saved = ImageWriter.saveImagesWithGlobalIndex(images, mergedDir, "jpg",
                        config.isEnableBatchRenaming(), filePrefix, globalIndex);
                totalImageCount += saved;

                stats.setSuccessCount(stats.getSuccessCount() + 1);
                log.info("[成功] {} → 合并目录 ({} 张，累计 {} 张)",
                        fileInfo.getName(), saved, totalImageCount);

            } catch (Exception e) {
                stats.setFailCount(stats.getFailCount() + 1);
                log.error("[异常] 处理文件失败：{}", fileInfo.getName(), e);
            } finally {
                fireProgress(i + 1, mhtFiles.size(), fileInfo.getName());
            }
        }

        log.info("========================================");
        log.info("所有文件处理完毕，共提取 {} 张图片", totalImageCount);

        // 处理完毕后打包（ZIP/CBZ）并删除临时目录
        if (config.isEnableZipPackaging() || config.isEnableCbzPackaging()) {
            if (config.isEnableZipPackaging()) {
                String zipPath = Paths.get(outputFolder, folderName + ".zip").toString();
                Archiver.createZip(mergedDir, zipPath);
            }
            if (config.isEnableCbzPackaging()) {
                String cbzPath = Paths.get(outputFolder, folderName + ".cbz").toString();
                Archiver.createCbz(mergedDir, cbzPath);
            }
            FileUtils.deleteDirectory(new File(mergedDir));
            log.info("已删除临时文件夹：{}", mergedDir);
        }

        stats.setEndTime(System.currentTimeMillis());
        log.info("========================================");
        log.info("{}", stats);
        log.info("========================================");
        return stats;
    }

    /**
     * 流式模式：逐个读取和处理 MHT 文件（单独输出 - 每个文件独立子目录）
     */
    private ProcessStats processStreamSeparateMode(List<MhtFileInfo> mhtFiles, String outputFolder,
                                                   MimeParser mimeParser, ProcessStats stats) {
        log.info("流式单独输出模式：共 {} 个文件待处理", mhtFiles.size());
        log.info("========================================");

        for (int i = 0; i < mhtFiles.size(); i++) {
            MhtFileInfo fileInfo = mhtFiles.get(i);
            if (isCancelled()) {
                log.info("用户取消处理，已处理 {} / {} 个文件", i, mhtFiles.size());
                break;
            }
            try {
                log.info("[解析] {}", fileInfo.getName());
                MimeParser.ParsedMht parsed = mimeParser.parse(fileInfo.getPath(), recursionContinueCallback);
                processSingleImageInternal(parsed, fileInfo, outputFolder, mhtFiles, i, stats);
            } catch (Exception e) {
                stats.setFailCount(stats.getFailCount() + 1);
                log.error("[异常] 处理文件失败：{}", fileInfo.getName(), e);
            } finally {
                fireProgress(i + 1, mhtFiles.size(), fileInfo.getName());
            }
        }

        stats.setEndTime(System.currentTimeMillis());
        log.info("========================================");
        log.info("{}", stats);
        log.info("========================================");
        return stats;
    }

    /**
     * 处理单个 MHT 文件中的图片（单独输出模式 - 每个文件独立子目录）
     */
    private void processSingleImageInternal(MimeParser.ParsedMht parsed, MhtFileInfo fileInfo,
                                            String outputFolder, List<MhtFileInfo> allFiles,
                                            int fileIndex, ProcessStats stats) throws Exception {
        log.info("----------------------------------------");
        log.info("[处理] {}", fileInfo.getName());

        if (parsed.getHtmlContent() == null ||
                (parsed.getCidMap().isEmpty() && parsed.getLocationDataMap().isEmpty())) {
            stats.setSkipCount(stats.getSkipCount() + 1);
            log.info("[跳过] 无图片内容：{}", fileInfo.getName());
            return;
        }

        // 提取图片
        ImageExtractor imageExtractor = new ImageExtractor(parsed,
                config.getImageNameStartMarker(), config.getImageNameEndMarker(),
                config.getImageRegionStartMarker(), config.getImageRegionEndMarker());

        List<ImageExtractor.ImageData> images = imageExtractor.extractImages();
        if (images.isEmpty()) {
            stats.setSkipCount(stats.getSkipCount() + 1);
            log.info("[跳过] 未提取到图片：{}", fileInfo.getName());
            return;
        }

        log.info("[提取] 共 {} 张图片", images.size());

        // 单独输出模式：每个文件一个子目录
        String baseName = getOutputDirName(fileInfo.getName());
        String fileOutputDir = Paths.get(outputFolder, baseName).toString();
        FileUtils.ensureDirectory(fileOutputDir);

        // 使用多线程并发保存
        int saved = ImageWriter.saveImagesConcurrent(images, fileOutputDir, "jpg",
                config.isEnableBatchRenaming(), "");
        log.info("[成功] {} → {} ({} 张)", fileInfo.getName(), fileOutputDir, saved);

        // ZIP/CBZ 打包（支持同时创建两种格式）
        if (config.isEnableZipPackaging()) {
            String zipPath = Paths.get(outputFolder, baseName + ".zip").toString();
            Archiver.createZip(fileOutputDir, zipPath);
        }
        if (config.isEnableCbzPackaging()) {
            String cbzPath = Paths.get(outputFolder, baseName + ".cbz").toString();
            Archiver.createCbz(fileOutputDir, cbzPath);
        }

        stats.setSuccessCount(stats.getSuccessCount() + 1);
    }

    @Override
    protected boolean processSingleFile(MhtFileInfo fileInfo) {
        // 不在此使用，由 process() 方法统一处理
        return false;
    }

    /**
     * 触发进度回调
     */
    private void fireProgress(int current, int total, String fileName) {
        if (progressCallback != null) {
            try {
                progressCallback.onProgress(current, total, fileName);
            } catch (Exception e) {
                log.debug("进度回调异常：{}", e.getMessage());
            }
        }
    }

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
     * 确定合并输出目录名称
     * 尝试从第一个文件提取标题，失败则使用输入文件夹名称
     */
    private String determineMergedFolderName(List<MhtFileInfo> mhtFiles, MimeParser mimeParser) {
        String folderName;
        boolean nameExtractEnabled = config.isEnableNameExtraction();

        if (nameExtractEnabled && mhtFiles != null && !mhtFiles.isEmpty()) {
            try {
                MimeParser.ParsedMht firstParsed = mimeParser.parse(mhtFiles.get(0).getPath(), recursionContinueCallback);
                if (firstParsed.getHtmlContent() != null) {
                    ImageExtractor firstExtractor = new ImageExtractor(firstParsed,
                            config.getImageNameStartMarker(), config.getImageNameEndMarker(),
                            config.getImageRegionStartMarker(), config.getImageRegionEndMarker());
                    String extractedTitle = firstExtractor.extractTitle();
                    if (extractedTitle != null && !extractedTitle.isEmpty()) {
                        return SanitizeUtils.sanitize(extractedTitle);
                    }
                }
            } catch (Exception e) {
                log.warn("从第一个文件提取标题失败: {}", e.getMessage());
            }
            folderName = determineDefaultFolderName();
            log.warn("[警告] 未从第一个文件提取到标题，使用默认名称：{}", folderName);
        } else {
            folderName = determineDefaultFolderName();
        }
        return folderName;
    }

    /**
     * 获取默认文件夹名称（输入目录名）
     */
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
}
