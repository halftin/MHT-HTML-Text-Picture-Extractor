package com.mhtextractor.processor;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.core.ChapterGenerator;
import com.mhtextractor.core.MimeParser;
import com.mhtextractor.extractor.TextExtractor;
import com.mhtextractor.output.FileWriter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文本批量处理器
 * 负责将多个 MHT 文件批量提取文本并写入 TXT（合并或单独模式）
 */
@Slf4j
public class TextBatchProcessor extends BatchProcessor {

    private final AppConfig config;

    public TextBatchProcessor(AppConfig config) {
        this.config = config;
    }

    /**
     * 合并模式：所有文件输出到一个 TXT（支持进度回调）
     */
    public ProcessStats processMerged(String inputFolder, String outputTxtPath, ProgressCallback callback) {
        this.progressCallback = callback;
        ProcessStats stats = initStats("合并模式", inputFolder);
        log.info("输出文件：{}", outputTxtPath);
        logConfigInfo();
        log.info("========================================");

        if (!FileWriter.createOrClearFile(outputTxtPath)) {
            stats.setEndTime(System.currentTimeMillis());
            return stats;
        }

        return processFiles(inputFolder, stats, i -> outputTxtPath);
    }

    /**
     * 单独模式：每个文件输出到独立 TXT（支持进度回调）
     */
    public ProcessStats processSeparate(String inputFolder, String outputFolder, ProgressCallback callback) {
        this.progressCallback = callback;
        ProcessStats stats = initStats("单独输出模式", inputFolder);
        log.info("输出文件夹：{}", outputFolder);
        logConfigInfo();
        log.info("========================================");

        File outDir = new File(outputFolder);
        if (!outDir.exists() || !outDir.isDirectory()) {
            log.error("输出文件夹不存在：{}", outputFolder);
            stats.setEndTime(System.currentTimeMillis());
            return stats;
        }

        return processFiles(inputFolder, stats,
                i -> Paths.get(outputFolder, getOutputDirName(i.getName()) + ".txt").toString());
    }

    /**
     * 核心处理循环（合并与单独模式的共用逻辑）
     *
     * @param outputPathResolver 根据 MhtFileInfo 计算输出路径
     */
    private ProcessStats processFiles(String inputFolder, ProcessStats stats,
                                      java.util.function.Function<MhtFileInfo, String> outputPathResolver) {
        List<MhtFileInfo> mhtFiles = collectMhtFiles(inputFolder);
        stats.setTotalFiles(mhtFiles.size());

        if (mhtFiles.isEmpty()) {
            log.warn("[警告] 未找到任何 MHT 文件");
            stats.setEndTime(System.currentTimeMillis());
            return stats;
        }

        log.info("找到 {} 个 MHT 文件", mhtFiles.size());
        log.info("文件已按修改时间排序");
        log.info("========================================");

        AtomicInteger chapterIndex = new AtomicInteger(1);
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
                    stats.incrementSkip();
                    stats.addSkipDetail(String.format("文件：%s 跳过，跳过原因：无法解析", fileInfo.getName()));
                    log.info("[跳过] 无法解析：{}", fileInfo.getName());
                    fireProgress(idx, mhtFiles.size(), fileInfo.getName(), 1.0);
                    continue;
                }

                // 提取文本
                fireProgress(idx, mhtFiles.size(), fileInfo.getName(), 0.4);
                TextExtractor textExtractor = new TextExtractor(parsed);
                String text = textExtractor.extractText(config.isAutoFixEncoding(),
                        config.getStartMarker(), config.getEndMarker(), config.isUseRegex());
                reportExtractorWarnings(fileInfo.getName(), textExtractor, stats);

                if (text == null || text.trim().isEmpty()) {
                    stats.incrementSkip();
                    stats.addSkipDetail(String.format("文件：%s 跳过，跳过原因：内容为空", fileInfo.getName()));
                    log.info("[跳过] 内容为空：{}", fileInfo.getName());
                    fireProgress(idx, mhtFiles.size(), fileInfo.getName(), 1.0);
                    continue;
                }

                // 生成章节头
                fireProgress(idx, mhtFiles.size(), fileInfo.getName(), 0.7);
                String chapterHeader = buildChapterHeaderFromParsed(parsed, fileInfo.getName(),
                        chapterIndex.get(), stats);

                if (config.isAddChapterNumber() && chapterHeader != null) {
                    chapterIndex.incrementAndGet();
                }

                // 写入文件
                fireProgress(idx, mhtFiles.size(), fileInfo.getName(), 0.9);
                String outputPath = outputPathResolver.apply(fileInfo);
                String startTemplate = config.isEnableTemplate() ? config.getStartTemplate() : "";
                String endTemplate = config.isEnableTemplate() ? config.getEndTemplate() : "";
                boolean ok = FileWriter.appendTextToFile(
                        outputPath, text, fileInfo.getName(), fileInfo.getModifiedTime(),
                        startTemplate, endTemplate, chapterHeader);

                if (ok) {
                    stats.incrementSuccess();
                } else {
                    stats.incrementFail();
                    stats.addFailDetail(String.format("文件：%s 失败，失败原因：写入文件失败", fileInfo.getName()));
                }

            } catch (Exception e) {
                stats.incrementFail();
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

    private ProcessStats initStats(String mode, String inputFolder) {
        ProcessStats stats = new ProcessStats();
        stats.setStartTime(System.currentTimeMillis());
        log.info("========================================");
        log.info("MHT 批量文本处理开始（{}）", mode);
        log.info("输入文件夹：{}", inputFolder);
        return stats;
    }

    private String buildChapterHeaderFromParsed(MimeParser.ParsedMht parsed, String fileName,
                                                int chapterIndex, ProcessStats stats) {
        String extractedTitle = null;
        boolean enableTitleExtract = config.isEnableTitleExtraction();
        String titleStart = config.getTitleStartMarker();
        String titleEnd = config.getTitleEndMarker();

        if (enableTitleExtract && titleStart != null && !titleStart.isEmpty() && parsed.getHtmlContent() != null) {
            try {
                TextExtractor extractor = new TextExtractor(parsed);
                extractedTitle = extractor.extractTitle(titleStart, titleEnd, config.isTitleUseRegex());
                if (extractedTitle != null && !extractedTitle.isEmpty()) {
                    log.info("[提取标题] {} → {}", fileName, extractedTitle);
                } else {
                    log.warn("[警告] {} 未提取到标题（标记: {} ... {}）", fileName, titleStart, titleEnd);
                    stats.incrementWarn();
                    stats.addWarnDetail(String.format("文件：%s 出现警告，警告信息：未提取到标题（标记: %s ... %s）",
                            fileName, titleStart, titleEnd));
                    extractedTitle = null;
                }
            } catch (Exception e) {
                log.debug("提取标题失败：{}", e.getMessage(), e);
            }
        }

        if (config.isAddChapterNumber()) {
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

}
