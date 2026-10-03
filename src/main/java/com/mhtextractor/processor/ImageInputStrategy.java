package com.mhtextractor.processor;

import com.mhtextractor.core.MimeParser;
import com.mhtextractor.core.SourceParser;
import com.mhtextractor.core.SourceParsers;

/**
 * 图片提取的<b>输入策略</b>（Q12 / 指南 §3 "建议组件"第 1 项）：抽象"按文件下标取得
 * {@link MimeParser.ParsedMht}"这件事，把两种输入策略从 {@code ImageBatchProcessor} 里移出来。
 *
 * <p><b>为什么必须是两个实现、而不是一个"隐式缓存开关"</b>（指南 §1.4-1 明令）：预加载与流式在
 * <b>内存占用</b>与<b>首文件解析策略</b>上本就不同 ——</p>
 * <ul>
 *   <li>{@link PreloadedImageInput}：主循环<b>之前</b>把整批文件全部解析进内存（结果常驻），
 *       主循环只查表；解析失败的文件以"下标 → 异常消息"常驻，供主循环在<b>正确归属</b>下记 FAILED；</li>
 *   <li>{@link StreamingImageInput}：按住就解析一个，<b>只可能</b>缓存首文件的预解析结果
 *       （合并输出模式下"取标题"那一次解析的结果），用掉即置空 —— 这正是"首文件只解析一次"的实现点。</li>
 * </ul>
 * <p>把两者合成一个带 {@code boolean cacheAll} 的类，会让"内存里到底留了什么""某个文件会被解析几次"
 * 变成由构造参数决定的隐式行为，而这两件事恰是本模块最容易出错的地方（P0-1 的索引错位就发生在这里）。
 * 因此本步<b>有意保留两个类</b>。</p>
 *
 * <p><b>解析阶段的进度与取消仍由窄回调承接</b>（指南 §3 表格对 {@code ImageInputStrategy} 的要求）：
 * 本接口不认识 {@code BatchProcessor}、不认识进度回调、也不认识"取消"——它只拿一个
 * {@link ImageFileLoader}（处理器提供的"解析一个文件"能力，内部自行上报进度）与一个
 * {@link CancelSignal}（只用于预加载循环里提前停下）。</p>
 */
interface ImageInputStrategy {

    /**
     * 按<b>完整文件列表下标</b>取得解析结果。
     *
     * @param index 文件在传入顺序里的下标（<b>不是</b>"已成功解析条目"的下标）
     * @return 解析结果；<b>不可用</b>（该文件解析失败 / 无 HTML）时返回 {@code null}，
     *         由 {@code processUnified} 按既有语义记 SKIPPED 或 FAILED
     * @throws Exception 加载失败（预加载模式用异常携带失败原因，见 {@link PreloadedImageInput}）
     */
    MimeParser.ParsedMht provide(int index) throws Exception;

    /**
     * 逐文件按扩展名选解析器（R6.1/R6.3 的唯一实现点 {@code core.SourceParsers#forPath}）。
     *
     * <p>一次导入可以同时含 {@code .mht} 与 {@code .html}，因此选择必须<b>按文件</b>发生，
     * 而不是"按批里第一个文件"定一次（Q11/R6 刚改过这里，本步原样保留该语义）。解析器<b>不常驻</b>
     * （每次调用新建），所以本方法是纯函数式入口。</p>
     */
    default SourceParser parserFor(String path) {
        return SourceParsers.forPath(path);
    }

    /** "解析一个文件"的能力（由处理器实现）：内部完成进度上报，返回解析结果 */
    @FunctionalInterface
    interface ImageFileLoader {
        MimeParser.ParsedMht load(int index) throws Exception;
    }

    /** 取消信号（窄接口：本包只需要"是否已取消"，不引入 {@code BatchProcessor} 的其余能力） */
    @FunctionalInterface
    interface CancelSignal {
        boolean isCancelled();
    }
}
