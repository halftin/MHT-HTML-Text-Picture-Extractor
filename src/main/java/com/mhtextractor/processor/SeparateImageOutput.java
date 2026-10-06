package com.mhtextractor.processor;

import com.mhtextractor.extractor.ImageSource;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.output.ImageWriter;
import com.mhtextractor.util.FileUtils;
import com.mhtextractor.util.SanitizeUtils;
import com.mhtextractor.util.SourceKinds;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Paths;

/**
 * <b>单独输出</b>策略：每个文件一个输出目录（目录名来自标题，缺失则回退源文件名）、序号从 1 起、
 * 写完立刻逐文件归档（Q12 从 {@code ImageBatchProcessor.SeparateOutputHandler} 原样移出）。
 *
 * <p><b>归档失败</b>：{@link OutputArchive} 返回 false ⇒ 本类抛 {@code RuntimeException} ⇒
 * 主循环的异常分支把该文件记 <b>FAILED</b>（源目录与图片都保留，是 P0-2 的修复语义）。
 * 合并输出不是这样记的（它记整体 failCount）——见 {@link ImageOutputStrategy} 的对照表。</p>
 *
 * <p>配置只经 {@link OutputContext} 取（不持有 {@code AppConfig} 字段）：本类的唯一状态就是
 * "输出根目录"。这正是"不把两种输出塞进一个带布尔开关的 writer"在字段层面的体现。</p>
 */
@Slf4j
final class SeparateImageOutput implements ImageOutputStrategy {

    private final String outputFolder;

    SeparateImageOutput(String outputFolder) {
        this.outputFolder = outputFolder;
    }

    @Override
    public int saveImages(ImageSource images, String extractedTitle, OutputContext ctx) {
        final String name = ctx.fileInfo().getName();

        // 确定输出目录名：标题优先（提取期重算，P13），否则源文件名（去掉已知来源扩展名；
        // A1：口径收敛到 SourceKinds.stripKnownExtension，此前本类的正则只认 .mht/.mhtml）
        String baseName;
        if (extractedTitle != null && !extractedTitle.isEmpty()) {
            baseName = SanitizeUtils.sanitize(extractedTitle);
        } else {
            baseName = SourceKinds.stripKnownExtension(name);
        }
        String fileOutputDir = Paths.get(outputFolder, baseName).toString();
        FileUtils.ensureDirectory(fileOutputDir);

        // 保存图片
        ctx.fireProgress(0.6);
        int imageCount = images.size();
        int saved = ImageWriter.saveImagesConcurrent(images, fileOutputDir, "jpg",
                ctx.config().isEnableBatchRenaming(), "", count ->
                        ctx.fireProgress(0.6 + 0.25 * ((double) count / imageCount)));
        ctx.fireProgress(0.85);
        if (saved > 0) {
            log.info(I18n.msg("log.imagebatchprocessor.34", name, fileOutputDir, saved));
        } else {
            log.warn(I18n.msg("log.imagebatchprocessor.35", name, fileOutputDir, imageCount));
        }

        // 逐文件 ZIP/CBZ 打包（由全局配置决定）；失败则保留源目录并抛异常 → processUnified 记 FAILED
        boolean archived = OutputArchive.archiveWithCleanup(ctx.config(), fileOutputDir, outputFolder,
                baseName, 0.85, 0.05, 0.05, ctx::fireProgress);
        if (!archived) {
            throw new RuntimeException(I18n.get("extract.fileArchiveFailedKeep", "dir", fileOutputDir));
        }
        ctx.fireProgress(0.95);

        return saved;
    }

    @Override
    public void onAllFilesProcessed(com.mhtextractor.model.AppConfig config, String outputFolder,
                                    BatchProcessor.ProcessStats stats) {
        // 逐文件归档，无需批量操作
    }
}
