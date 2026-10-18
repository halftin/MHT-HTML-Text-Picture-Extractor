package com.mhtextractor.processor;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.core.MimeParser;
import com.mhtextractor.extractor.ImageSource;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.ExtractWarning;
import com.mhtextractor.output.ImageWriter;
import com.mhtextractor.util.FileUtils;
import com.mhtextractor.util.SanitizeUtils;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>合并输出</b>策略：全局序号 + {@code f%02d_} 前缀 + 单目录 + 全部文件写完后统一归档
 * （Q12 从 {@code ImageBatchProcessor.MergedOutputHandler} 与 {@code setupMergedOutput} 原样移出）。
 *
 * <p><b>归档失败</b>记<b>整体</b> failCount +1，且<b>不改写</b>任何文件级结果（此时文件级写入已成功）。
 * 这与单独输出的"抛出 → 该文件 FAILED"是两种语义，见 {@link ImageOutputStrategy} 的对照表。</p>
 *
 * <p>本类的状态是"已解析好的单目录 + 全局序号"；配置只经 {@link OutputContext} 取 ——
 * 没有 {@code boolean merged} 之类的模式字段，也没有"顺手把输出根目录也存下来"这种会诱导分叉的字段。</p>
 */
@Slf4j
final class MergedImageOutput implements ImageOutputStrategy {

    private final String mergedDir;
    private final String folderName;
    /** 合并目录命名警告（未取到标题时的回退提示）：由处理器并入首文件结果 */
    private final ExtractWarning nameWarning;
    private final AtomicInteger globalIndex = new AtomicInteger(1);

    private MergedImageOutput(String mergedDir, String folderName, ExtractWarning nameWarning) {
        this.mergedDir = mergedDir;
        this.folderName = folderName;
        this.nameWarning = nameWarning;
    }

    /**
     * 装配合并输出（原 {@code ImageBatchProcessor.setupMergedOutput} 原样搬出，含全部日志、命名回退
     * 与"标题回退警告"的统计）。
     *
     * @param firstParsed 首文件的解析结果（用于取标题命名）；{@code null} 或 HTML 为空时回退默认名
     */
    static MergedImageOutput create(AppConfig config, String outputFolder,
                                    List<BatchProcessor.MhtFileInfo> mhtFiles,
                                    MimeParser.ParsedMht firstParsed,
                                    BatchProcessor.ProcessStats stats, String explicitName) {
        String folderName = explicitName;
        ExtractWarning nameWarning = null;
        if (folderName != null && !folderName.isEmpty()) {
            folderName = SanitizeUtils.sanitize(folderName);
            if (folderName.isEmpty()) {
                folderName = null;
            }
        }
        if (folderName == null) {
            folderName = ImageBatchProcessor.determineMergedFolderName(firstParsed,
                    config.isEnableNameExtraction(), config.getImageNameStartMarker(),
                    config.getImageNameEndMarker());
            if (folderName == null) {
                folderName = ImageBatchProcessor.determineDefaultFolderName(config.getInputFolder());
                if (config.isEnableNameExtraction() && firstParsed != null
                        && firstParsed.getHtmlContent() != null) {
                    nameWarning = new ExtractWarning("extract.titleFallback",
                            Map.of("name", folderName));
                    stats.incrementWarn();
                    stats.addWarnDetail(new com.mhtextractor.model.ExtractionStats.WarningDetail("extract.fileWarned",
                            Map.of("file", mhtFiles.getFirst().getName()), nameWarning));
                    log.warn(I18n.msg("log.imagebatchprocessor.17", folderName));
                }
            }
        }
        String mergedDir = Paths.get(outputFolder, folderName).toString();
        FileUtils.ensureDirectory(mergedDir);

        log.info(I18n.msg("log.imagebatchprocessor.18", mergedDir));
        log.info(I18n.msg("log.imagebatchprocessor.19", mhtFiles.size()));
        log.info("========================================");
        return new MergedImageOutput(mergedDir, folderName, nameWarning);
    }

    String mergedDir() {
        return mergedDir;
    }

    /** 合并目录命名警告（未取到标题）；无回退时为 {@code null} */
    ExtractWarning nameWarning() {
        return nameWarning;
    }

    @Override
    public int saveImages(ImageSource images, String extractedTitle, OutputContext ctx) {
        String filePrefix = "";
        if (!ctx.config().isEnableBatchRenaming() && ctx.totalFiles() > 1) {
            filePrefix = String.format("f%02d_", ctx.fileIndex() + 1);
        }

        ctx.fireProgress(0.6);

        int imageCount = images.size();
        int saved = ImageWriter.saveImagesWithGlobalIndex(images, mergedDir, "jpg",
                ctx.config().isEnableBatchRenaming(), filePrefix, globalIndex,
                count -> ctx.fireProgress(0.6 + 0.3 * ((double) count / imageCount)));

        ctx.fireProgress(0.9);
        if (saved > 0) {
            log.info(I18n.msg("log.imagebatchprocessor.31", ctx.fileInfo().getName(), saved,
                    globalIndex.get() - 1));
        } else {
            log.warn(I18n.msg("log.imagebatchprocessor.32", ctx.fileInfo().getName(), imageCount));
        }
        return saved;
    }

    @Override
    public void onAllFilesProcessed(AppConfig config, String outputFolder,
                                    BatchProcessor.ProcessStats stats) {
        // 批级归档：无文件级进度（zipDone = null ⇒ 只打 debug，与旧实现同形）
        if (!OutputArchive.archiveWithCleanup(config, mergedDir, outputFolder, folderName,
                0, 0, 0, null)) {
            stats.incrementFail();
            stats.addFailDetail(I18n.get("extract.archiveFailedKeep", "dir", mergedDir));
            log.error(I18n.msg("log.imagebatchprocessor.33", mergedDir));
        }
    }
}
