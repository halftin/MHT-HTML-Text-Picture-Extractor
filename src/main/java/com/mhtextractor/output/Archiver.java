package com.mhtextractor.output;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
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
            log.error("[归档] 目录不存在：{}", sourceDir);
            return false;
        }

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            log.warn("[归档] 目录为空，跳过：{}", sourceDir);
            return false;
        }

        int totalFiles = files.length;

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
                }
            }

            log.info("[归档] {} 创建成功：{} ({} 个文件)", format.toUpperCase(), outputPath, totalFiles);
            return true;

        } catch (IOException e) {
            log.error("[归档] {} 创建失败：{}", format.toUpperCase(), outputPath, e);
            return false;
        }
    }

    /**
     * 生成归档输出路径
     */
    public static String generateArchivePath(String outputFolder, String baseName, boolean isCbz) {
        String ext = isCbz ? ".cbz" : ".zip";
        return Paths.get(outputFolder, baseName + ext).toString();
    }
}
