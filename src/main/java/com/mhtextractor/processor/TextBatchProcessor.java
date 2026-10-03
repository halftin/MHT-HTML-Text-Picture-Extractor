package com.mhtextractor.processor;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.core.ChapterGenerator;
import com.mhtextractor.core.MimeParser;
import com.mhtextractor.core.SourceParsers;
import com.mhtextractor.extractor.TextExtractor;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.output.FileWriter;
import com.mhtextractor.util.ExtractOutcome;
import com.mhtextractor.util.SanitizeUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
     * 合并模式（按传入顺序处理，支持每文件覆盖项）
     */
    public ProcessStats processMerged(List<MhtFileInfo> orderedFiles, String outputTxtPath,
                                      ProgressCallback callback, Map<String, PerFileOverrides> overrides) {
        this.progressCallback = callback;
        ProcessStats stats = initStats(I18n.get("extract.mergedMode"), config.getInputFolder());
        log.info(I18n.msg("log.textbatchprocessor.01", outputTxtPath));
        logConfigInfo();
        log.info("========================================");

        if (!FileWriter.createOrClearFile(outputTxtPath)) {
            stats.setEndTime(System.currentTimeMillis());
            return stats;
        }

        return processFiles(orderedFiles, stats, (_, _) -> outputTxtPath, overrides);
    }

    /**
     * 单独模式（按传入顺序处理，支持每文件覆盖项）
     */
    public ProcessStats processSeparate(List<MhtFileInfo> orderedFiles, String outputFolder,
                                        ProgressCallback callback, Map<String, PerFileOverrides> overrides) {
        this.progressCallback = callback;
        ProcessStats stats = initStats(I18n.get("extract.separateMode"), config.getInputFolder());
        log.info(I18n.msg("log.textbatchprocessor.02", outputFolder));
        logConfigInfo();
        log.info("========================================");

        File outDir = new File(outputFolder);
        if (!outDir.exists() || !outDir.isDirectory()) {
            log.error(I18n.msg("log.textbatchprocessor.03", outputFolder));
            stats.setEndTime(System.currentTimeMillis());
            return stats;
        }

        // 条目独立参数需求拍板：单独输出文件名——启用标题提取且提取到标题时用标题（sanitize），
        // 否则用原始 mht 文件名
        return processFiles(orderedFiles, stats, (i, parsed) -> {
            String base = getOutputDirName(i.getName());
            if (config.isEnableTitleExtraction() && parsed != null && parsed.getHtmlContent() != null
                    && config.getTitleStartMarker() != null && !config.getTitleStartMarker().isEmpty()) {
                try {
                    String title = new TextExtractor(parsed).extractTitle(
                            config.getTitleStartMarker(), config.getTitleEndMarker(), config.isTitleUseRegex());
                    if (title != null && !title.trim().isEmpty()) {
                        String s = SanitizeUtils.sanitize(title.trim());
                        if (!s.isEmpty()) {
                            base = s;
                        }
                    }
                } catch (Exception e) {
                    log.debug(I18n.msg("log.textbatchprocessor.04", e.getMessage()), e);
                }
            }
            return Paths.get(outputFolder, base + ".txt").toString();
        }, overrides);
    }

    /**
     * 核心处理循环（合并与单独模式的共用逻辑）
     *
     * @param outputPathResolver 根据 MhtFileInfo 与解析结果计算输出路径
     * @param overrides 每文件覆盖项（按文件路径索引）
     */
    private ProcessStats processFiles(List<MhtFileInfo> mhtFiles, ProcessStats stats,
                                      java.util.function.BiFunction<MhtFileInfo, MimeParser.ParsedMht, String> outputPathResolver,
                                      Map<String, PerFileOverrides> overrides) {
        stats.setTotalFiles(mhtFiles.size());

        if (mhtFiles.isEmpty()) {
            log.warn(I18n.msg("log.textbatchprocessor.05"));
            stats.setEndTime(System.currentTimeMillis());
            return stats;
        }

        log.info(I18n.msg("log.textbatchprocessor.06", mhtFiles.size()));
        log.info("========================================");

        AtomicInteger chapterIndex = new AtomicInteger(1);

        for (int i = 0; i < mhtFiles.size(); i++) {
            MhtFileInfo fileInfo = mhtFiles.get(i);
            if (isCancelled()) {
                log.info(I18n.msg("log.textbatchprocessor.07", i, mhtFiles.size()));
                break;
            }
            try {
                log.info(I18n.msg("log.textbatchprocessor.08", fileInfo.getName()));
                final int idx = i + 1;
                final String name = fileInfo.getName();
                fireProgress(idx, mhtFiles.size(), name, 0.05);
                MimeParser.ParsedMht parsed = SourceParsers.forPath(fileInfo.getPath())
                        .parse(fileInfo.getPath(), recursionContinueCallback,
                        stage -> fireParsingProgress(idx, mhtFiles.size(), name, stage),
                        progress -> fireProgress(idx, mhtFiles.size(), name, progress));
                if (parsed.getHtmlContent() == null) {
                    stats.incrementSkip();
                    stats.addSkipDetail(I18n.get("extract.fileSkippedUnparseable", "file", fileInfo.getName()));
                    recordFileResult(stats, fileInfo.getPath(), ExtractOutcome.SKIPPED,
                            I18n.get("extract.reasonUnparseable"), List.of());
                    log.info(I18n.msg("log.textbatchprocessor.09", fileInfo.getName()));
                    fireProgress(idx, mhtFiles.size(), fileInfo.getName(), 1.0);
                    continue;
                }

                // 提取文本
                fireProgress(idx, mhtFiles.size(), fileInfo.getName(), 0.4);
                TextExtractor textExtractor = new TextExtractor(parsed);
                String text = textExtractor.extractText(config.isAutoFixEncoding(),
                        config.getStartMarker(), config.getEndMarker(), config.isUseRegex());
                List<String> fileWarnings = new ArrayList<>(textExtractor.getLastWarnings());
                reportExtractorWarnings(fileInfo.getName(), textExtractor, stats);

                if (text == null || text.trim().isEmpty()) {
                    stats.incrementSkip();
                    stats.addSkipDetail(I18n.get("extract.fileSkippedEmpty", "file", fileInfo.getName()));
                    recordFileResult(stats, fileInfo.getPath(), ExtractOutcome.SKIPPED,
                            I18n.get("extract.reasonEmpty"), fileWarnings);
                    log.info(I18n.msg("log.textbatchprocessor.10", fileInfo.getName()));
                    fireProgress(idx, mhtFiles.size(), fileInfo.getName(), 1.0);
                    continue;
                }

                // 生成章节头（预览窗口可逐文件关闭；关闭的文件不输出章节头且不占序号）
                fireProgress(idx, mhtFiles.size(), fileInfo.getName(), 0.7);
                boolean addNumber = config.isAddChapterNumber();
                PerFileOverrides ov = overrides.getOrDefault(fileInfo.getPath(), PerFileOverrides.DEFAULTS);
                boolean withChapter = !addNumber || ov.chapterEnabled();
                String chapterHeader = withChapter
                        ? buildChapterHeaderFromParsed(parsed, fileInfo.getName(), chapterIndex.get(), stats, fileWarnings)
                        : null;

                if (addNumber && withChapter) {
                    chapterIndex.incrementAndGet();
                }

                // 写入文件
                fireProgress(idx, mhtFiles.size(), fileInfo.getName(), 0.9);
                String outputPath = outputPathResolver.apply(fileInfo, parsed);
                String startTemplate = config.isEnableTemplate() ? config.getStartTemplate() : "";
                String endTemplate = config.isEnableTemplate() ? config.getEndTemplate() : "";
                boolean ok = FileWriter.appendTextToFile(
                        outputPath, text, fileInfo.getName(), fileInfo.getModifiedTime(),
                        startTemplate, endTemplate, chapterHeader);

                if (ok) {
                    stats.incrementSuccess();
                    recordFileResult(stats, fileInfo.getPath(), ExtractOutcome.SUCCESS, null, fileWarnings);
                } else {
                    stats.incrementFail();
                    stats.addFailDetail(I18n.get("extract.fileFailedWrite", "file", fileInfo.getName()));
                    recordFileResult(stats, fileInfo.getPath(), ExtractOutcome.FAILED,
                            I18n.get("extract.reasonWriteFailed"), fileWarnings);
                }

            } catch (Exception e) {
                stats.incrementFail();
                stats.addFailDetail(I18n.get("extract.fileFailed", "file", fileInfo.getName(),
                        "reason", e.getMessage()));
                recordFileResult(stats, fileInfo.getPath(), ExtractOutcome.FAILED, e.getMessage(), List.of());
                log.error(I18n.msg("log.textbatchprocessor.11", fileInfo.getName()), e);
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
        log.info(I18n.msg("log.textbatchprocessor.12", mode));
        log.info(I18n.msg("log.textbatchprocessor.13", inputFolder));
        return stats;
    }

    private String buildChapterHeaderFromParsed(MimeParser.ParsedMht parsed, String fileName,
                                                int chapterIndex, ProcessStats stats,
                                                List<String> fileWarnings) {
        String extractedTitle = null;
        boolean enableTitleExtract = config.isEnableTitleExtraction();
        String titleStart = config.getTitleStartMarker();
        String titleEnd = config.getTitleEndMarker();

        if (enableTitleExtract && titleStart != null && !titleStart.isEmpty() && parsed.getHtmlContent() != null) {
            try {
                TextExtractor extractor = new TextExtractor(parsed);
                extractedTitle = extractor.extractTitle(titleStart, titleEnd, config.isTitleUseRegex());
                if (extractedTitle != null && !extractedTitle.isEmpty()) {
                    log.info(I18n.msg("log.textbatchprocessor.14", fileName, extractedTitle));
                } else {
                    log.warn(I18n.msg("log.textbatchprocessor.15", fileName, titleStart, titleEnd));
                    stats.incrementWarn();
                    stats.addWarnDetail(I18n.get("extract.fileWarnedNoTitle",
                            "file", fileName, "start", titleStart, "end", titleEnd));
                    fileWarnings.add(I18n.get("extract.noTitleMarkers",
                            "start", titleStart, "end", titleEnd));
                    extractedTitle = null;
                }
            } catch (Exception e) {
                log.debug(I18n.msg("log.textbatchprocessor.16", e.getMessage()), e);
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
            log.info(I18n.msg("log.textbatchprocessor.17"));
        }
        if (config.isEnableTitleExtraction()) {
            log.info(I18n.msg("log.textbatchprocessor.18"));
            log.info(I18n.msg("log.textbatchprocessor.19", config.getTitleStartMarker()));
            log.info(I18n.msg("log.textbatchprocessor.20", config.getTitleEndMarker()));
        }
    }

}
