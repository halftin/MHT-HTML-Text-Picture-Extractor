package com.mhtextractor.processor;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.core.ChapterGenerator;
import com.mhtextractor.core.MimeParser;
import com.mhtextractor.extractor.TextExtractor;
import com.mhtextractor.output.FileWriter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Paths;

/**
 * 文本批量处理器
 * 负责将多个 MHT 文件批量提取文本并写入 TXT（合并或单独模式）
 */
@Slf4j
public class TextBatchProcessor extends BatchProcessor {

    private final AppConfig config;
    private ProgressCallback progressCallback;

    public TextBatchProcessor(AppConfig config) {
        this.config = config;
    }

    /**
     * 合并模式：所有文件输出到一个 TXT（支持进度回调）
     */
    public ProcessStats processMerged(String inputFolder, String outputTxtPath, ProgressCallback callback) {
        this.progressCallback = callback;
        ProcessStats stats = new ProcessStats();
        stats.setStartTime(System.currentTimeMillis());

        log.info("========================================");
        log.info("MHT 批量文本处理开始（合并模式）");
        log.info("输入文件夹：{}", inputFolder);
        log.info("输出文件：{}", outputTxtPath);
        logConfigInfo();
        log.info("========================================");

        if (!FileWriter.createOrClearFile(outputTxtPath)) {
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
        log.info("文件已按修改时间排序");
        log.info("========================================");

        int chapterIndex = 1;
        MimeParser mimeParser = new MimeParser();

        for (int i = 0; i < mhtFiles.size(); i++) {
            MhtFileInfo fileInfo = mhtFiles.get(i);
            if (isCancelled()) {
                log.info("用户取消处理，已处理 {} / {} 个文件", i, mhtFiles.size());
                break;
            }
            try {
                // 1. 解析 MHT
                log.info("[解析] {}", fileInfo.getName());
                final int idx = i + 1;
                final String name = fileInfo.getName();
                fireProgress(idx, mhtFiles.size(), name, 0.05);
                MimeParser.ParsedMht parsed = mimeParser.parse(fileInfo.getPath(), recursionContinueCallback,
                        stage -> fireParsingProgress(idx, mhtFiles.size(), name, stage),
                        progress -> fireProgress(idx, mhtFiles.size(), name, progress));
                if (parsed.getHtmlContent() == null) {
                    stats.setSkipCount(stats.getSkipCount() + 1);
                    stats.addSkipDetail(String.format("文件：%s 跳过，跳过原因：无法解析", fileInfo.getName()));
                    log.info("[跳过] 无法解析：{}", fileInfo.getName());
                    fireProgress(i + 1, mhtFiles.size(), fileInfo.getName(), 1.0);
                    continue;
                }

                // 2. 提取文本
                fireProgress(i + 1, mhtFiles.size(), fileInfo.getName(), 0.4);
                TextExtractor textExtractor = new TextExtractor(parsed);
                String text = textExtractor.extractText(config.isAutoFixEncoding(),
                        config.getStartMarker(), config.getEndMarker(), config.isUseRegex());
                reportExtractorWarnings(fileInfo.getName(), textExtractor, stats);

                if (text == null || text.trim().isEmpty()) {
                    stats.setSkipCount(stats.getSkipCount() + 1);
                    stats.addSkipDetail(String.format("文件：%s 跳过，跳过原因：内容为空", fileInfo.getName()));
                    log.info("[跳过] 内容为空：{}", fileInfo.getName());
                    fireProgress(i + 1, mhtFiles.size(), fileInfo.getName(), 1.0);
                    continue;
                }

                // 3. 生成章节头（复用已解析的 parsed，避免重复解析）
                fireProgress(i + 1, mhtFiles.size(), fileInfo.getName(), 0.7);
                String chapterHeader = buildChapterHeaderFromParsed(parsed, fileInfo.getName(), chapterIndex,
                        config.isAddChapterNumber(), config.isEnableTitleExtraction(),
                        config.getTitleStartMarker(), config.getTitleEndMarker(),
                        config.isTitleUseRegex(), stats);

                if (config.isAddChapterNumber() && chapterHeader != null) {
                    chapterIndex++;
                }

                // 4. 写入文件
                fireProgress(i + 1, mhtFiles.size(), fileInfo.getName(), 0.9);
                String startTemplate = config.isEnableTemplate() ? config.getStartTemplate() : "";
                String endTemplate = config.isEnableTemplate() ? config.getEndTemplate() : "";
                boolean ok = FileWriter.appendTextToFile(
                        outputTxtPath, text, fileInfo.getName(), fileInfo.getModifiedTime(),
                        startTemplate, endTemplate, chapterHeader);

                if (ok) {
                    stats.setSuccessCount(stats.getSuccessCount() + 1);
                } else {
                    stats.setFailCount(stats.getFailCount() + 1);
                    stats.addFailDetail(String.format("文件：%s 失败，失败原因：写入文件失败", fileInfo.getName()));
                }

            } catch (Exception e) {
                stats.setFailCount(stats.getFailCount() + 1);
                stats.addFailDetail(String.format("文件：%s 失败，失败原因：%s", fileInfo.getName(), e.getMessage()));
                log.error("[异常] 处理文件失败：{}", fileInfo.getName(), e);
            } finally {
                fireProgress(i + 1, mhtFiles.size(), fileInfo.getName(), 1.0);
            }
        }

        stats.setEndTime(System.currentTimeMillis());
        log.info("{}", stats);

        return stats;
    }

    /**
     * 单独模式：每个文件输出到独立 TXT（支持进度回调）
     */
    public ProcessStats processSeparate(String inputFolder, String outputFolder, ProgressCallback callback) {
        this.progressCallback = callback;
        ProcessStats stats = new ProcessStats();
        stats.setStartTime(System.currentTimeMillis());

        log.info("========================================");
        log.info("MHT 批量文本处理开始（单独输出模式）");
        log.info("输入文件夹：{}", inputFolder);
        log.info("输出文件夹：{}", outputFolder);
        logConfigInfo();
        log.info("========================================");

        File outDir = new File(outputFolder);
        if (!outDir.exists() || !outDir.isDirectory()) {
            log.error("输出文件夹不存在：{}", outputFolder);
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

        int chapterIndex = 1;
        MimeParser mimeParser = new MimeParser();

        for (int i = 0; i < mhtFiles.size(); i++) {
            MhtFileInfo fileInfo = mhtFiles.get(i);
            if (isCancelled()) {
                log.info("用户取消处理，已处理 {} / {} 个文件", i, mhtFiles.size());
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
                if (parsed.getHtmlContent() == null) {
                    stats.setSkipCount(stats.getSkipCount() + 1);
                    stats.addSkipDetail(String.format("文件：%s 跳过，跳过原因：无法解析", fileInfo.getName()));
                    log.info("[跳过] 无法解析：{}", fileInfo.getName());
                    fireProgress(i + 1, mhtFiles.size(), fileInfo.getName(), 1.0);
                    continue;
                }

                fireProgress(i + 1, mhtFiles.size(), fileInfo.getName(), 0.4);
                TextExtractor textExtractor = new TextExtractor(parsed);
                String text = textExtractor.extractText(config.isAutoFixEncoding(),
                        config.getStartMarker(), config.getEndMarker(), config.isUseRegex());
                reportExtractorWarnings(fileInfo.getName(), textExtractor, stats);

                if (text == null || text.trim().isEmpty()) {
                    stats.setSkipCount(stats.getSkipCount() + 1);
                    stats.addSkipDetail(String.format("文件：%s 跳过，跳过原因：内容为空", fileInfo.getName()));
                    log.info("[跳过] 内容为空：{}", fileInfo.getName());
                    fireProgress(i + 1, mhtFiles.size(), fileInfo.getName(), 1.0);
                    continue;
                }

                fireProgress(i + 1, mhtFiles.size(), fileInfo.getName(), 0.7);
                String chapterHeader = buildChapterHeaderFromParsed(parsed, fileInfo.getName(), chapterIndex,
                        config.isAddChapterNumber(), config.isEnableTitleExtraction(),
                        config.getTitleStartMarker(), config.getTitleEndMarker(),
                        config.isTitleUseRegex(), stats);

                if (config.isAddChapterNumber() && chapterHeader != null) {
                    chapterIndex++;
                }

                // 生成输出文件名
                String baseName = getOutputDirName(fileInfo.getName());
                String outputPath = Paths.get(outputFolder, baseName + ".txt").toString();

                fireProgress(i + 1, mhtFiles.size(), fileInfo.getName(), 0.9);
                String startTemplate = config.isEnableTemplate() ? config.getStartTemplate() : "";
                String endTemplate = config.isEnableTemplate() ? config.getEndTemplate() : "";
                boolean ok = FileWriter.appendTextToFile(
                        outputPath, text, fileInfo.getName(), fileInfo.getModifiedTime(),
                        startTemplate, endTemplate, chapterHeader);

                if (ok) {
                    stats.setSuccessCount(stats.getSuccessCount() + 1);
                    log.info("[成功] {} → {}", fileInfo.getName(), outputPath);
                } else {
                    stats.setFailCount(stats.getFailCount() + 1);
                    stats.addFailDetail(String.format("文件：%s 失败，失败原因：写入文件失败", fileInfo.getName()));
                }

            } catch (Exception e) {
                stats.setFailCount(stats.getFailCount() + 1);
                stats.addFailDetail(String.format("文件：%s 失败，失败原因：%s", fileInfo.getName(), e.getMessage()));
                log.error("[异常] 处理文件失败：{}", fileInfo.getName(), e);
            } finally {
                fireProgress(i + 1, mhtFiles.size(), fileInfo.getName(), 1.0);
            }
        }

        stats.setEndTime(System.currentTimeMillis());
        log.info("{}", stats);

        return stats;
    }

    @Override
    protected boolean processSingleFile(MhtFileInfo fileInfo) {
        // 不在此使用，单独模式和合并模式各自由公开方法处理
        return false;
    }

    /**
     * 构建章节头：基于已解析的 ParsedMht，避免重复解析
     */
    private String buildChapterHeaderFromParsed(MimeParser.ParsedMht parsed, String fileName,
                                                int chapterIndex, boolean addChapterNumber,
                                                boolean enableTitleExtract,
                                                String titleStart, String titleEnd, boolean titleUseRegex,
                                                ProcessStats stats) {
        String extractedTitle = null;

        if (enableTitleExtract && titleStart != null && !titleStart.isEmpty() && parsed.getHtmlContent() != null) {
            try {
                TextExtractor extractor = new TextExtractor(parsed);
                extractedTitle = extractor.extractTitle(titleStart, titleEnd, titleUseRegex);
                if (extractedTitle != null && !extractedTitle.isEmpty()) {
                    log.info("[提取标题] {} → {}", fileName, extractedTitle);
                } else {
                    log.warn("[警告] {} 未提取到标题（标记: {} ... {}）", fileName, titleStart, titleEnd);
                    stats.setWarnCount(stats.getWarnCount() + 1);
                    stats.addWarnDetail(String.format("文件：%s 出现警告，警告信息：未提取到标题（标记: %s ... %s）",
                            fileName, titleStart, titleEnd));
                    extractedTitle = null;
                }
            } catch (Exception e) {
                log.debug("提取标题失败：{}", e.getMessage());
            }
        }

        if (addChapterNumber) {
            String numberPart = ChapterGenerator.generateChapterName(chapterIndex - 1);
            if (extractedTitle != null && !extractedTitle.isEmpty()) {
                return numberPart + " " + extractedTitle;
            }
            return numberPart;
        } else if (extractedTitle != null && !extractedTitle.isEmpty()) {
            return extractedTitle;
        }

        return null;
    }

    /**
     * 触发进度回调（带子阶段进度）
     * @param subProgress 文件内部子阶段进度 0.0~1.0
     */
    private void fireProgress(int current, int total, String fileName, double subProgress) {
        if (progressCallback != null) {
            try {
                progressCallback.onProgress(current, total, fileName, subProgress);
            } catch (Exception e) {
                log.debug("进度回调异常：{}", e.getMessage());
            }
        }
    }

    /**
     * 将解析阶段映射到 0.05~0.35 的子进度范围（文件解析阶段）
     */
    private void fireParsingProgress(int current, int total, String fileName, MimeParser.ParsingStage stage) {
        double subProgress;
        switch (stage) {
            case FILE_READ -> subProgress = 0.08;
            case HTML_EXTRACTED -> subProgress = 0.15;
            case MIME_TRAVERSED -> subProgress = 0.25;
            case IMAGES_EXTRACTED -> subProgress = 0.30;
            case COMPLETE -> subProgress = 0.35;
            default -> subProgress = 0.10;
        }
        fireProgress(current, total, fileName, subProgress);
    }

    private void logConfigInfo() {
        if (config.isAddChapterNumber()) {
            log.info("自动章节名：已启用");
        }
        if (config.isEnableTitleExtraction()) {
            log.info("隐藏标题提取：已启用");
            log.info("  起始标记：{}", config.getTitleStartMarker());
            log.info("  结束标记：{}", config.getTitleEndMarker());
        }
    }

    private void reportExtractorWarnings(String fileName, TextExtractor extractor, ProcessStats stats) {
        for (String warning : extractor.getLastWarnings()) {
            stats.setWarnCount(stats.getWarnCount() + 1);
            stats.addWarnDetail(String.format("文件：%s 出现警告，警告信息：%s", fileName, warning));
        }
    }
}
