package com.mhtextractor.output;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 归档工具类
 * 支持 ZIP 和 CBZ 格式打包
 */
@Slf4j
public class Archiver {

    /**
     * 将目录打包为 ZIP 文件
     *
     * @param sourceDir 要打包的目录
     * @param outputZipPath 输出的 ZIP 文件路径
     * @return 是否成功
     */
    public static boolean createZip(String sourceDir, String outputZipPath) {
        return createArchive(sourceDir, outputZipPath, "zip");
    }

    /**
     * 将目录打包为 CBZ 文件（实质是 ZIP，扩展名不同）
     *
     * @param sourceDir 要打包的目录
     * @param outputCbzPath 输出的 CBZ 文件路径
     * @return 是否成功
     */
    public static boolean createCbz(String sourceDir, String outputCbzPath) {
        return createArchive(sourceDir, outputCbzPath, "cbz");
    }

    private static boolean createArchive(String sourceDir, String outputPath, String format) {
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

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputPath)))) {

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
                }
            }

            log.info("[归档] {} 创建成功：{} ({} 个文件)", format.toUpperCase(), outputPath, files.length);
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
