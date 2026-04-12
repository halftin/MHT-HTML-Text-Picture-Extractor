package com.mhtextractor.processor;

import com.mhtextractor.util.FileUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * 批量处理抽象基类
 * 提供公共的文件收集、排序、统计功能
 */
@Slf4j
public abstract class BatchProcessor {

    /**
     * 取消检查信号
     * 由调用方通过 setCancelSupplier 绑定 Task.isCancelled()
     */
    protected volatile Supplier<Boolean> cancelSupplier = () -> false;

    /**
     * 递归深度超限继续回调
     * 由调用方通过 setRecursionContinueCallback 绑定 GUI 弹窗逻辑
     */
    protected volatile Supplier<Boolean> recursionContinueCallback;

    /**
     * 设置取消检查信号
     */
    public void setCancelSupplier(Supplier<Boolean> supplier) {
        this.cancelSupplier = supplier != null ? supplier : (() -> false);
    }

    /**
     * 设置递归深度超限时是否继续的回调
     */
    public void setRecursionContinueCallback(Supplier<Boolean> callback) {
        this.recursionContinueCallback = callback;
    }

    /**
     * 检查是否已取消
     * @return true 表示用户请求了取消
     */
    protected boolean isCancelled() {
        return cancelSupplier.get();
    }

    /**
     * 进度回调接口
     * 用于在处理每个文件时向调用方报告进度，并支持取消检查
     */
    @FunctionalInterface
    public interface ProgressCallback {
        /**
         * 处理进度更新
         * @param current 当前已处理的文件数（从1开始）
         * @param total 总文件数
         * @param fileName 当前处理的文件名
         */
        void onProgress(int current, int total, String fileName);
    }

    /**
     * 默认模板
     */
    public static final String DEFAULT_START_TEMPLATE = "\n========== START ==========\n";
    public static final String DEFAULT_END_TEMPLATE = "\n========== END ==========\n";

    /**
     * 处理统计信息
     */
    @Data
    public static class ProcessStats {
        private int totalFiles = 0;
        private int successCount = 0;
        private int failCount = 0;
        private int skipCount = 0;
        private long startTime;
        private long endTime;

        @Override
        public String toString() {
            return String.format(
                    "处理完成 - 总计：%d, 成功：%d, 失败：%d, 跳过：%d, 耗时：%.2f 秒",
                    totalFiles, successCount, failCount, skipCount,
                    (endTime - startTime) / 1000.0
            );
        }
    }

    /**
     * MHT 文件信息
     */
    @Data
    public static class MhtFileInfo {
        private final String path;
        private final String name;
        private final LocalDateTime modifiedTime;

        public MhtFileInfo(String path, String name, LocalDateTime modifiedTime) {
            this.path = path;
            this.name = name;
            this.modifiedTime = modifiedTime;
        }
    }

    /**
     * 收集并排序 MHT 文件
     */
    protected List<MhtFileInfo> collectMhtFiles(String folderPath) {
        List<File> files = FileUtils.findMhtFiles(folderPath);
        return files.stream()
                .map(f -> {
                    try {
                        LocalDateTime mtime = Files.getLastModifiedTime(Paths.get(f.getAbsolutePath()))
                                .toInstant()
                                .atZone(ZoneId.systemDefault())
                                .toLocalDateTime();
                        return new MhtFileInfo(f.getAbsolutePath(), f.getName(), mtime);
                    } catch (Exception e) {
                        log.error("无法读取文件信息：{}", f.getName());
                        return null;
                    }
                })
                .filter(info -> info != null)
                .sorted(Comparator.comparing(MhtFileInfo::getModifiedTime))
                .toList();
    }

    /**
     * 处理单个文件（由子类实现）
     */
    protected abstract boolean processSingleFile(MhtFileInfo fileInfo);

    /**
     * 获取输出目录名
     */
    protected String getOutputDirName(String mhtFileName) {
        String base = mhtFileName.replaceAll("\\.(?i)(mht|mhtml)$", "");
        return base;
    }
}
