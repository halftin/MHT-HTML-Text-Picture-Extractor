package com.mhtextractor.util;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 增量导入（F3）：输入目录作为导入源，以绝对路径为唯一键去重，
 * 新文件按扫描顺序追加到列表末尾，已有项目保持不变。
 */
public final class IncrementalImport {

    private IncrementalImport() {
    }

    /**
     * 从目录扫描结果中筛选出列表中尚不存在的新文件（唯一键 = 绝对路径）。
     *
     * @param scanned       目录扫描结果（如 {@link FileUtils#findMhtFiles} 的输出）
     * @param existingPaths 列表中已有项目的绝对路径
     * @return 需要追加到列表末尾的新文件（保持扫描顺序）；无新文件时返回空列表
     */
    public static List<File> findNewFiles(List<File> scanned, Collection<String> existingPaths) {
        Set<String> existing = new HashSet<>(existingPaths);
        List<File> newFiles = new ArrayList<>();
        for (File f : scanned) {
            if (!existing.contains(f.getAbsolutePath())) {
                newFiles.add(f);
            }
        }
        return newFiles;
    }
}
