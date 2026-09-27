package com.mhtextractor.output;

import com.mhtextractor.i18n.I18n;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.IntConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 归档工具类
 * 支持 ZIP 和 CBZ 格式打包
 */
@Slf4j
public class Archiver {

    public static boolean createZip(String sourceDir, String outputZipPath, IntConsumer progressCallback) {
        return createArchive(sourceDir, outputZipPath, "zip", progressCallback);
    }

    public static boolean createCbz(String sourceDir, String outputCbzPath, IntConsumer progressCallback) {
        return createArchive(sourceDir, outputCbzPath, "cbz", progressCallback);
    }

    private static boolean createArchive(String sourceDir, String outputPath, String format,
                                         IntConsumer progressCallback) {
        File dir = new File(sourceDir);
        if (!dir.exists() || !dir.isDirectory()) {
            log.error(I18n.msg("log.archiver.01", sourceDir));
            return false;
        }

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            log.warn(I18n.msg("log.archiver.02", sourceDir));
            return false;
        }

        int totalFiles = 0;
        for (File file : files) {
            if (file.isFile()) totalFiles++;
        }
        if (totalFiles == 0) {
            log.warn(I18n.msg("log.archiver.03", sourceDir));
            return false;
        }

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputPath)))) {

            int processed = 0;
            byte[] buffer = new byte[8192];
            for (File file : files) {
                if (file.isFile()) {
                    ZipEntry entry = new ZipEntry(file.getName());
                    zos.putNextEntry(entry);
                    try (FileInputStream fis = new FileInputStream(file)) {
                        int len;
                        while ((len = fis.read(buffer)) > 0) {
                            zos.write(buffer, 0, len);
                        }
                    }
                    zos.closeEntry();
                    processed++;
                    if (progressCallback != null) {
                        progressCallback.accept(processed);
                    }
                } else {
                    log.warn(I18n.msg("log.archiver.04", file.getName()));
                }
            }

            log.info(I18n.msg("log.archiver.05", format.toUpperCase(), outputPath, totalFiles));
            return true;

        } catch (IOException e) {
            log.error(I18n.msg("log.archiver.06", format.toUpperCase(), outputPath), e);
            deletePartialArchive(outputPath);
            return false;
        }
    }

    /**
     * 删除失败时写出的半截归档，避免留下看似正常但损坏的 zip/cbz。
     * 只删除常规文件（防御 outputPath 恰好是目录时误删用户目录）。
     */
    private static void deletePartialArchive(String outputPath) {
        try {
            Path path = Paths.get(outputPath);
            if (Files.isRegularFile(path)) {
                Files.deleteIfExists(path);
                log.warn(I18n.msg("log.archiver.07", outputPath));
            }
        } catch (IOException e) {
            log.warn(I18n.msg("log.archiver.08", outputPath), e);
        }
    }

}
