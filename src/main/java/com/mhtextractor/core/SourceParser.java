package com.mhtextractor.core;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 来源解析器抽象（R6.3）：把"一个可导入来源解析成 {@link MimeParser.ParsedMht}"这件事收成一个接口。
 *
 * <p><b>为什么值得一个接口</b>：本步新增 HTML / HTML+侧车文件夹的导入能力，若各调用点直接
 * {@code new MimeParser()} 就只能解析 MHT。收成接口后，调用方（{@code app} / {@code processor} /
 * {@code gui}）只依赖"解析器"，"按扩展名选实现"这件事只有一个实现点
 * （{@link SourceParsers#forPath(String)}），这正是 R8「同一语义只有一个实现点」的一项。</p>
 *
 * <p><b>🚨 关键约束（R6.2 不建第二条管线）</b>：本接口的返回类型刻意复用
 * {@link MimeParser.ParsedMht}，<b>不引入任何新 DTO</b>。于是 {@code extractor} / {@code processor} /
 * {@code output} 三层的既有代码<b>一行都不用改</b>——它们本来就只认这个类型，
 * HTML 走的也是同一条提取/预览/缓存/P5 策略链路。</p>
 *
 * <p><b>为什么多一个 {@code textOnly}</b>：预览预解析只取 HTML 文本（{@code MainController.preParseItems}
 * 与 {@code PreviewService} 都用 {@code textOnly=true} 的语义），图片链则要字节。
 * 该参数是<b>同一语义</b>的一部分，不是 MHT 专有，故进入接口而不是留在实现里。</p>
 *
 * <p><b>异常口径</b>：{@code IOException}（读不到文件 / 格式无法解析）。递归深度回调的语义仍是
 * {@link MimeParser} 专有（HTML 没有 MIME 树），接口只保留跨实现都有意义的那部分。</p>
 */
public interface SourceParser {

    /**
     * 解析来源。
     *
     * @param path                   绝对路径或相对路径字符串（与既有 {@code MimeParser} 口径一致）
     * @param textOnly               true = 跳过图片二进制读取，只取 HTML 文本（预览预解析用）
     * @param continueCallback       递归深度超限时是否继续的回调，可为 null（HTML 实现忽略此参数）
     * @param stageCallback          阶段进度回调（{@link MimeParser.ParsingStage}），可为 null
     * @param byteProgressCallback   细粒度进度回调（0.0~1.0），可为 null
     * @throws IOException 文件不可读或格式无法解析
     */
    MimeParser.ParsedMht parse(String path, boolean textOnly, Supplier<Boolean> continueCallback,
                               Consumer<MimeParser.ParsingStage> stageCallback,
                               Consumer<Double> byteProgressCallback) throws IOException;

    /**
     * 便捷重载：{@code textOnly=false} 且不报告细粒度进度 —— 提取链（文本 / 图片批量处理器）的默认用法。
     *
     * <p>放在接口上而不是各调用点：{@code app}/{@code processor}/{@code gui} 三层共用同一份语义，
     * 各写一遍 lambda 实参只会在参数顺序上分叉。{@code continueCallback} 对 HTML 实现无意义
     * （没有 MIME 树），但保留在签名里是为了让"MHT 与 HTML 对调用方同形"这件事成立。</p>
     */
    default MimeParser.ParsedMht parse(String path, Supplier<Boolean> continueCallback,
                                       Consumer<MimeParser.ParsingStage> stageCallback,
                                       Consumer<Double> byteProgressCallback) throws IOException {
        return parse(path, false, continueCallback, stageCallback, byteProgressCallback);
    }
}
