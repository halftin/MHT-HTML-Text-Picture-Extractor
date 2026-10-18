package com.mhtextractor.processor;

import com.mhtextractor.core.MimeParser;
import com.mhtextractor.extractor.WarningProvider;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.ExtractWarning;
import com.mhtextractor.model.ExtractOutcome;
import com.mhtextractor.util.FileUtils;
import com.mhtextractor.util.SourceKinds;
import lombok.Data;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import com.mhtextractor.model.ExtractionStats.WarningDetail;
import com.mhtextractor.i18n.ExtractionTexts;
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
     * -- SETTER --
     *  设置递归深度超限时是否继续的回调

     */
    @Setter
    protected volatile Supplier<Boolean> recursionContinueCallback;

    protected ProgressCallback progressCallback;

    /**
     * 设置取消检查信号
     */
    public void setCancelSupplier(Supplier<Boolean> supplier) {
        this.cancelSupplier = supplier != null ? supplier : (() -> false);
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
         * @param subProgress 文件内部子阶段进度 0.0~1.0
         */
        void onProgress(int current, int total, String fileName, double subProgress);
    }

    /**
     * 处理统计信息
     */
    @Data
    public static class ProcessStats {
        private int totalFiles = 0;
        private int successCount = 0;
        private int failCount = 0;
        private int skipCount = 0;
        private int warnCount = 0;
        private long startTime;
        private long endTime;

        private final List<String> failDetails = new ArrayList<>();
        private final List<String> skipDetails = new ArrayList<>();
        private final List<WarningDetail> warnDetails = new ArrayList<>();
        /** 结构化单文件结果（《提取结果状态需求文档.md》）：提取结束后按路径回写列表条目背景色 */
        private final List<FileResult> fileResults = new ArrayList<>();

        public void addFileResult(FileResult result) {
            fileResults.add(result);
        }

        public void addAllFileResults(List<FileResult> results) {
            fileResults.addAll(results);
        }

        /** 把警告并入指定路径的首条结果（SUCCESS 升级为 SUCCESS_WARNED）；无该路径记录则忽略 */
        public void attachWarningTo(String path, ExtractWarning warning) {
            for (int i = 0; i < fileResults.size(); i++) {
                FileResult r = fileResults.get(i);
                if (r.path().equals(path)) {
                    List<ExtractWarning> w = new ArrayList<>(r.warnings());
                    w.add(warning);
                    ExtractOutcome o = r.outcome() == ExtractOutcome.SUCCESS
                            ? ExtractOutcome.SUCCESS_WARNED : r.outcome();
                    fileResults.set(i, new FileResult(r.path(), o, r.messageKey(), r.messageParams(), r.messageDetail(), w));
                    return;
                }
            }
        }

        public void addFailDetail(String detail) {
            failDetails.add(detail);
        }

        public void addSkipDetail(String detail) {
            skipDetails.add(detail);
        }

        public void addWarnDetail(WarningDetail detail) {
            warnDetails.add(detail);
        }

        public void incrementSuccess() { successCount++; }
        public void incrementFail() { failCount++; }
        public void incrementSkip() { skipCount++; }
        public void incrementWarn() { warnCount++; }

        public Map<String, String> getSummaryParams() {
            return Map.of("total", String.valueOf(totalFiles), "success", String.valueOf(successCount),
                    "failed", String.valueOf(failCount), "skipped", String.valueOf(skipCount),
                    "warned", String.valueOf(warnCount), "elapsed", String.valueOf(endTime - startTime));
        }

        public String getSummaryLine() {
            return ExtractionTexts.summaryLine("extract.summary", getSummaryParams());
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(getSummaryLine());
            boolean hasDetails = !failDetails.isEmpty() || !skipDetails.isEmpty() || !warnDetails.isEmpty();
            if (hasDetails) {
                sb.append("\n========================================");
                for (String detail : failDetails) {
                    sb.append("\n").append(detail);
                }
                for (String detail : skipDetails) {
                    sb.append("\n").append(detail);
                }
                for (WarningDetail detail : warnDetails) {
                    sb.append("\n").append(ExtractionTexts.warningDetail(detail));
                }
            }
            return sb.toString();
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
     * 从文件构建 MhtFileInfo（修改时间读取失败回退当前时间，条目仍入列表）
     */
    public static MhtFileInfo toMhtFileInfo(File f) {
        try {
            LocalDateTime mtime = Files.getLastModifiedTime(Paths.get(f.getAbsolutePath()))
                    .toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();
            return new MhtFileInfo(f.getAbsolutePath(), f.getName(), mtime);
        } catch (Exception e) {
            log.warn(I18n.msg("log.batchprocessor.01", f.getName()), e);
            return new MhtFileInfo(f.getAbsolutePath(), f.getName(), LocalDateTime.now());
        }
    }

    /**
     * 按传入顺序构建 MhtFileInfo 列表（预览窗口拖拽排序后的最终顺序）
     */
    public static List<MhtFileInfo> buildMhtFileInfoList(List<String> paths) {
        return paths.stream()
                .map(File::new)
                .map(BatchProcessor::toMhtFileInfo)
                .toList();
    }

    /**
     * 收集并排序 MHT 文件
     */
    protected List<MhtFileInfo> collectMhtFiles(String folderPath) {
        List<File> files = FileUtils.findMhtFiles(folderPath);
        return files.stream()
                .map(BatchProcessor::toMhtFileInfo)
                .sorted(Comparator.comparing(MhtFileInfo::getModifiedTime))
                .toList();
    }

    /**
     * 获取输出目录名 = 剥掉已知来源扩展名后的基名（A1：口径收敛到 {@link SourceKinds#stripKnownExtension}，
     * 此前本类的正则只认 .mht/.mhtml，HTML 来源的 .html/.htm 会残留在输出名里）。
     */
    protected String getOutputDirName(String mhtFileName) {
        return SourceKinds.stripKnownExtension(mhtFileName);
    }

    /**
     * 触发进度回调（带子阶段进度）
     * @param subProgress 文件内部子阶段进度 0.0~1.0
     *
     * <p><b>为什么是包级可见而非 {@code protected}</b>（Q12）：输出策略组件（{@link OutputContext}）
     * 需要"与处理器完全同形"的进度上报（含回调抛异常只 warn 的兜底），而它<b>不持有</b>处理器实例。
     * 把本方法放宽到包级，输出层就能复用<b>同一处实现</b>——否则"回调抛异常只 warn"这件事会出现第二份
     * 拷贝，也会让 {@code log.batchprocessor.02} 多出一个调用点（日志行为必须逐键逐次不变）。</p>
     */
    void fireProgress(int current, int total, String fileName, double subProgress) {
        if (progressCallback != null) {
            try {
                progressCallback.onProgress(current, total, fileName, subProgress);
            } catch (Exception e) {
                log.warn(I18n.msg("log.batchprocessor.02", e.getMessage()), e);
            }
        }
    }

    /**
     * 将解析阶段映射到 0.05~0.35 的子进度范围（文件解析阶段）
     */
    protected void fireParsingProgress(int current, int total, String fileName, MimeParser.ParsingStage stage) {
        double subProgress;
        switch (stage) {
            case FILE_READ -> subProgress = 0.08;
            case HTML_EXTRACTED -> subProgress = 0.15;
            case MIME_TRAVERSED -> subProgress = 0.25;
            case IMAGES_EXTRACTED -> subProgress = 0.30;
            case COMPLETE -> subProgress = 0.35;
            default -> subProgress = 0.10;
        }
        fireProgress(current, total, fileName, subProgress);
    }

    /**
     * 收集提取器的警告信息到统计对象
     */
    protected void reportExtractorWarnings(String fileName, WarningProvider extractor, ProcessStats stats) {
        for (ExtractWarning warning : extractor.getLastWarnings()) {
            if (warning.key().startsWith("extract.region."))
                log.warn(I18n.msg("log.region.warning", fileName, I18n.render(warning)));
            stats.incrementWarn();
            stats.addWarnDetail(new WarningDetail("extract.fileWarned", Map.of("file", fileName), warning));
        }
    }

    /**
     * 记录单文件结果（《提取结果状态需求文档.md》）：警告非空时 SUCCESS 自动升级为 SUCCESS_WARNED。
     * messageKey 为失败/跳过原因键（成功时传 null）；warnings 为该文件全部警告。
     */
    protected void recordFileResult(ProcessStats stats, String path, ExtractOutcome outcome,
                                    String messageKey, List<ExtractWarning> warnings) {
        recordFileResult(stats, path, outcome, messageKey, null, null, warnings);
    }

    protected void recordFileFailure(ProcessStats stats, String path, Exception failure) {
        if (failure instanceof ResultFailure structured) {
            recordFileResult(stats, path, ExtractOutcome.FAILED, structured.key, structured.params, null, List.of());
        } else {
            recordFileResult(stats, path, ExtractOutcome.FAILED, null, null, failure.getMessage(), List.of());
        }
    }

    protected void recordFileResult(ProcessStats stats, String path, ExtractOutcome outcome,
                                    String messageKey, Map<String, String> messageParams, String messageDetail,
                                    List<ExtractWarning> warnings) {
        if (outcome == ExtractOutcome.SUCCESS && !warnings.isEmpty()) {
            outcome = ExtractOutcome.SUCCESS_WARNED;
        }
        stats.addFileResult(new FileResult(path, outcome, messageKey, messageParams, messageDetail, warnings));
    }
}
