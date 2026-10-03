package com.mhtextractor.processor;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.output.Archiver;
import com.mhtextractor.util.FileUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Paths;
import java.util.function.DoubleConsumer;

/**
 * ZIP/CBZ 归档 + 源目录清理（Q12 从 {@code ImageBatchProcessor.archiveWithCleanup} 原样移出）。
 *
 * <p><b>为什么两个输出策略共用它、而不是各写一份</b>：归档动作本身（{@link Archiver}）与"失败则
 * <b>不删源目录</b>"这条纪律是同一份实现（P0-2 的修复点）。两者真正不同的只是<b>失败之后谁记结果</b>
 * 与<b>进度怎么报</b>——那是各自策略的事，通过 {@code progress} 回调传入。</p>
 *
 * <p>进度参数只在"逐文件归档"（单独输出）时有意义：那时传 {@code fileProgress != null}；
 * 合并输出的批级归档传 {@code null}，于是只打 debug 日志（与旧实现同形）。</p>
 */
@Slf4j
final class OutputArchive {

    private OutputArchive() {
    }

    /**
     * 归档 + 清理源目录。
     *
     * @param zipBase   ZIP 进度基准（仅在 {@code fileProgress != null} 时使用）
     * @param zipRange  ZIP 进度区间
     * @param cbzRange  CBZ 进度区间
     * @param zipDone   归档过程中的进度接收者（0.0~1.0 → 调用方自行映射到文件子进度）；
     *                  为 {@code null} 表示"批级归档"（只打 debug）
     * @return true = 归档成功（源目录已被删除）或本来就无需归档；false = 归档失败（源目录保留）
     */
    static boolean archiveWithCleanup(AppConfig config, String sourceDir, String outputFolder,
                                      String baseName, double zipBase, double zipRange,
                                      double cbzRange, DoubleConsumer zipDone) {
        if (!config.isEnableZipPackaging() && !config.isEnableCbzPackaging()) {
            return true;
        }
        File dir = new File(sourceDir);
        File[] children = dir.listFiles();
        int childCount = children == null ? 0 : children.length;
        if (childCount == 0) {
            FileUtils.deleteDirectory(dir);
            log.info(I18n.msg("log.imagebatchprocessor.50", sourceDir));
            return true;
        }
        boolean zipOk = true;
        boolean cbzOk = true;
        if (config.isEnableZipPackaging()) {
            zipOk = Archiver.createZip(sourceDir,
                    Paths.get(outputFolder, baseName + ".zip").toString(), count -> {
                        double ratio = (double) count / childCount;
                        if (zipDone != null) {
                            zipDone.accept(zipBase + zipRange * ratio);
                        } else {
                            log.debug(I18n.msg("log.imagebatchprocessor.51", count, childCount));
                        }
                    });
        }
        if (config.isEnableCbzPackaging()) {
            cbzOk = Archiver.createCbz(sourceDir,
                    Paths.get(outputFolder, baseName + ".cbz").toString(), count -> {
                        double ratio = (double) count / childCount;
                        if (zipDone != null) {
                            zipDone.accept(zipBase + zipRange + cbzRange * ratio);
                        } else {
                            log.debug(I18n.msg("log.imagebatchprocessor.52", count, childCount));
                        }
                    });
        }
        if (!zipOk || !cbzOk) {
            log.error(I18n.msg("log.imagebatchprocessor.53", zipOk, cbzOk, sourceDir));
            return false;
        }
        FileUtils.deleteDirectory(dir);
        log.info(I18n.msg("log.imagebatchprocessor.54", sourceDir));
        return true;
    }
}
