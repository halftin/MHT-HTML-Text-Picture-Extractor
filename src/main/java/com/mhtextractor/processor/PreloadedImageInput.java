package com.mhtextractor.processor;

import com.mhtextractor.core.MimeParser;
import com.mhtextractor.i18n.I18n;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>高速内存（预加载）</b>输入策略：整批解析结果常驻内存，按下标查表
 * （Q12 从 {@code ImageBatchProcessor} 的 {@code PreloadedProvider} + {@code preloadAll} 原样移出）。
 *
 * <p>两种"不可用"在此有<b>不同</b>的结果（指南 §1.3-2 第 1 条要保住的差异）：</p>
 * <ul>
 *   <li>解析<b>抛异常</b> → 记入 {@code loadFailures}（下标 → 消息）⇒ {@link #provide} 抛
 *       {@code RuntimeException} ⇒ 主循环记 <b>FAILED</b>（原因 = 加载 MHT 文件失败）；</li>
 *   <li>解析成功但 <b>HTML 为空</b> → 不进表 ⇒ {@link #provide} 返回 {@code null} ⇒ 主循环记
 *       <b>SKIPPED</b>（无法解析）。</li>
 * </ul>
 *
 * <p><b>为什么 {@link #provide} 的参数是"完整文件列表下标"</b>：解析失败的文件不在表里，若按
 * "已成功条目"的位置取值，后面所有文件的图片都会被写进前一个文件的目录（P0-1 缺陷）。本类因此
 * 按 {@code fileIndex} 建表、缺项返回 {@code null}（或按失败表抛异常）。</p>
 */
@Slf4j
final class PreloadedImageInput implements ImageInputStrategy {

    /** 成功条目：只保留下标与解析结果（条目顺序不影响语义，查表才是语义） */
    private final Map<Integer, MimeParser.ParsedMht> byFileIndex = new HashMap<>();
    /** 原始下标 → 异常消息（供主循环在正确归属下记 FAILED） */
    private final Map<Integer, String> loadFailures;

    PreloadedImageInput(List<IndexedParsed> entries, Map<Integer, String> loadFailures) {
        for (IndexedParsed entry : entries) {
            byFileIndex.put(entry.fileIndex(), entry.parsed());
        }
        this.loadFailures = loadFailures;
    }

    @Override
    public MimeParser.ParsedMht provide(int index) {
        String failure = loadFailures.get(index);
        if (failure != null) {
            throw new RuntimeException(I18n.get("error.LOAD_MHT_FAILED", "reason", failure));
        }
        return byFileIndex.get(index);
    }

    /**
     * 预加载整批文件（从 {@code ImageBatchProcessor.preloadAll} 原样移出，含全部进度上报与日志）。
     *
     * <p><b>解析阶段不写 fileResult</b>：失败只记入 {@code loadFailures}，统一由主循环记 ——
     * GUI 按路径取"最后一条"结果，双记录会互相覆盖。</p>
     *
     * @param firstPath 历史遗留的占位参数（曾是"批里第一个文件的解析器实例"；R6.1 起解析器<b>逐文件</b>
     *                  按扩展名选，见 {@link ImageInputStrategy#parserFor}）
     */
    static LoadResult preload(List<BatchProcessor.MhtFileInfo> mhtFiles, String firstPath,
                              ImageFileLoader loader, CancelSignal cancelSignal,
                              ProgressSink progressSink) {
        List<IndexedParsed> parsedList = new ArrayList<>();
        Map<Integer, String> loadFailures = new HashMap<>();
        for (int i = 0; i < mhtFiles.size(); i++) {
            BatchProcessor.MhtFileInfo fileInfo = mhtFiles.get(i);
            if (cancelSignal.isCancelled()) {
                log.info(I18n.msg("log.imagebatchprocessor.10", i, mhtFiles.size()));
                break;
            }
            try {
                log.info(I18n.msg("log.imagebatchprocessor.11", fileInfo.getName()));
                final int idx = i + 1;
                final String name = fileInfo.getName();
                progressSink.fireProgress(idx, mhtFiles.size(), name, 0.05);
                MimeParser.ParsedMht parsed = loader.load(i);
                if (parsed.getHtmlContent() != null) {
                    progressSink.fireProgress(idx, mhtFiles.size(), name, 0.35);
                    parsedList.add(new IndexedParsed(parsed, i));
                    progressSink.fireProgress(idx, mhtFiles.size(), name, 0.5);
                    log.info(I18n.msg("log.imagebatchprocessor.12", name));
                } else {
                    // 不在此记录结果：统一由 processUnified 记录（GUI 按路径取最后一条，双记录会互相覆盖）
                    log.warn(I18n.msg("log.imagebatchprocessor.13", name));
                }
            } catch (Exception e) {
                log.error(I18n.msg("log.imagebatchprocessor.14", fileInfo.getName(), e.getMessage()), e);
                loadFailures.put(i, e.getMessage() == null
                        ? I18n.get("extract.unknownError") : e.getMessage());
            } finally {
                progressSink.fireProgress(i + 1, mhtFiles.size(), fileInfo.getName(), 1.0);
            }
        }
        return new LoadResult(parsedList, loadFailures);
    }

    /** 预加载条目：解析结果 + 它在完整文件列表里的下标（两者必须一起传，否则又会退化成位置取值） */
    record IndexedParsed(MimeParser.ParsedMht parsed, int fileIndex) {
    }

    /** 预加载结果：成功条目 + 解析抛异常的文件（原始下标 → 异常消息） */
    record LoadResult(List<IndexedParsed> parsedList, Map<Integer, String> loadFailures) {
    }

    /** 预加载阶段上报进度的窄回调（由处理器提供，本包不依赖 {@code BatchProcessor}） */
    @FunctionalInterface
    interface ProgressSink {
        void fireProgress(int current, int total, String fileName, double subProgress);
    }
}
