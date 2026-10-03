package com.mhtextractor.processor;

import com.mhtextractor.core.MimeParser;

import java.util.List;

/**
 * <b>流式</b>输入策略：按住就解析一个，<b>只可能</b>缓存首文件的预解析结果
 * （Q12 从 {@code ImageBatchProcessor} 的 {@code StreamingProvider} 原样移出）。
 *
 * <p>{@code preParsedFirst} 的来源是"合并输出模式为了确定输出目录名而先行解析的首文件"：它必须被
 * <b>原样交给第一次 {@code provide(0)}</b>，否则首文件会被解析两次（一次取标题、一次真提取）——
 * 这就是"流式合并模式首文件只解析一次"的<b>全部实现</b>，由
 * {@code ImageExtractionMatrixTest#streamingMergedParsesFirstFileExactlyOnce} 用计数型解析器替身
 * 直接锁死，并配有<b>控制组</b>（预解析失败时同一装置读数由 1 变 2）。</p>
 *
 * <p>非合并模式传 {@code null}（没有"取标题"那一步，首文件只在主循环里被解析一次）。</p>
 */
final class StreamingImageInput implements ImageInputStrategy {

    private final List<BatchProcessor.MhtFileInfo> files;
    private final ImageFileLoader loader;
    /** 首文件的预解析结果：被 {@link #provide} 消费一次后置空（"只解析一次"的关键） */
    private MimeParser.ParsedMht preParsedFirst;

    StreamingImageInput(List<BatchProcessor.MhtFileInfo> files, MimeParser.ParsedMht preParsedFirst,
                        ImageFileLoader loader, CancelSignal cancelSignal) {
        this.files = files;
        this.preParsedFirst = preParsedFirst;
        this.loader = loader;
    }

    @Override
    public MimeParser.ParsedMht provide(int index) throws Exception {
        if (index == 0 && preParsedFirst != null) {
            MimeParser.ParsedMht result = preParsedFirst;
            preParsedFirst = null;
            return result;
        }
        return loader.load(index);
    }

    /** 文件数（供调用方核对下标范围；不参与业务判断） */
    int fileCount() {
        return files.size();
    }
}
