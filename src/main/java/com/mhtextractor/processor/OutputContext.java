package com.mhtextractor.processor;

import com.mhtextractor.model.AppConfig;

/**
 * 输出阶段的显式上下文（Q12 / 指南 §3 表格对 {@code ImageOutputStrategy} 的要求：
 * "进度和统计通过显式 context/回调传入，避免组件反向持有整个 processor"）。
 *
 * <p>传进来的 {@code owner} 是 {@code BatchProcessor} 的<b>基类视图</b>（不是
 * {@code ImageBatchProcessor}）—— 输出策略因此只能调 {@code fireProgress} 这一个包级方法，
 * <b>拿不到</b>处理器的解析、缓存、取消等能力。这是防止三种策略重新长回一个大类的关键约束：
 * 类型上就限死了可见面，而不是靠约定。</p>
 */
record OutputContext(BatchProcessor.MhtFileInfo fileInfo, int fileIndex, int totalFiles,
                     String outputFolder, BatchProcessor.ProcessStats stats,
                     BatchProcessor owner, AppConfig config) {

    /**
     * 等价的 {@code fireProgress(fileIndex+1, totalFiles, name, subProgress)}。
     *
     * <p>直接复用 {@code BatchProcessor.fireProgress}（同一处实现、同一个
     * {@code log.batchprocessor.02} 调用点），不另写一份"回调抛异常只 warn"的兜底。</p>
     */
    void fireProgress(double subProgress) {
        owner.fireProgress(fileIndex + 1, totalFiles, fileInfo.getName(), subProgress);
    }
}
