package com.mhtextractor.util;

import com.mhtextractor.i18n.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文件操作工具类
 */
public class FileUtils {
    private static final Logger logger = LoggerFactory.getLogger(FileUtils.class);

    /**
     * 获取目录下所有可导入来源文件（.mht/.mhtml/.html/.htm，大小写不敏感），按修改时间排序。
     * 扩展名口径唯一实现点在 {@link SourceKinds#isImportable}（A1：此前只收 MHT，漏掉 HTML）。
     */
    public static List<File> findMhtFiles(String folderPath) {
        File dir = new File(folderPath);
        if (!dir.exists() || !dir.isDirectory()) {
            logger.warn(I18n.msg("log.fileutils.01", folderPath));
            return List.of();
        }

        File[] files = dir.listFiles((d, name) -> SourceKinds.isImportable(name));
        if (files == null || files.length == 0) {
            logger.warn(I18n.msg("log.fileutils.02", folderPath));
            return List.of();
        }

        // 按修改时间排序
        return java.util.Arrays.stream(files)
                .sorted(Comparator.comparingLong(File::lastModified))
                .collect(Collectors.toList());
    }

    /**
     * 创建或清空文件
     */
    public static boolean createOrClearFile(String path) {
        try {
            File file = new File(path);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (file.exists()) {
                file.delete();
            }
            return file.createNewFile();
        } catch (IOException e) {
            logger.error(I18n.msg("log.fileutils.03", path), e);
            return false;
        }
    }

    /**
     * 确保目录存在
     */
    public static boolean ensureDirectory(String dirPath) {
        File dir = new File(dirPath);
        if (!dir.exists()) {
            return dir.mkdirs();
        }
        return dir.isDirectory();
    }

    /**
     * 创建子目录（基于父目录和子目录名）
     */
    public static String createSubDirectory(String parentDir, String subDirName) {
        Path path = Paths.get(parentDir, subDirName);
        if (ensureDirectory(path.toString())) {
            return path.toString();
        }
        return null;
    }

    /**
     * 递归删除目录及其所有内容
     */
    public static void deleteDirectory(File directory) {
        if (directory == null || !directory.exists()) return;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        directory.delete();
    }
}
