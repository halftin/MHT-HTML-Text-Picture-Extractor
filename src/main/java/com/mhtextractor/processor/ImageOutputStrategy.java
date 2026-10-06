package com.mhtextractor.processor;

import com.mhtextractor.extractor.ImageSource;

/**
 * 图片提取的<b>输出策略</b>（Q12 / 指南 §3 "建议组件"第 2 项）：抽象"把一个文件的图片写出去"与
 * "全部文件写完后做什么"，把两种输出从 {@code ImageBatchProcessor} 里移出来。
 *
 * <p><b>为什么必须是两个实现、而不是一个"带大量布尔分支的 writer"</b>（指南 §1.4-2 明令）：
 * 合并与单独输出在<b>命名</b>、<b>序号</b>、<b>进度区间</b>、<b>归档失败结果</b>四项上全不同 ——</p>
 * <table border="1">
 *   <caption>两项输出策略的差异</caption>
 *   <tr><th></th><th>{@link MergedImageOutput}</th><th>{@link SeparateImageOutput}</th></tr>
 *   <tr><td>命名</td><td>全局序号 / {@code f%02d_} 前缀 + 原文件名</td>
 *       <td>每文件目录（标题或文件名）+ 原文件名（撞名加 {@code _1}）</td></tr>
 *   <tr><td>序号</td><td>跨文件<b>全局</b>递增</td><td>每目录从 1 起</td></tr>
 *   <tr><td>进度区间</td><td>0.6 → 0.9（存档在末尾，不报文件级进度）</td>
 *       <td>0.6 → 0.85 → 0.95（逐文件归档带进度）</td></tr>
 *   <tr><td>归档失败</td><td>记<b>整体</b> failCount +1（文件级仍 SUCCESS）</td>
 *       <td>抛出 → 该文件记 <b>FAILED</b>（源目录保留）</td></tr>
 * </table>
 * <p>这些差异若压进一个类，就会变成一个"处处 {@code if (merged)}"的开关集合，而每一处的默认分支都可能
 * 悄悄改变产物字节。本步因此保留两个类。</p>
 *
 * <p><b>两个类的构造参数里都没有"模式布尔"</b>（这也是"没有隐式缓存开关/没有布尔分支 writer"的
 * 机械判据）：{@link MergedImageOutput} 拿的是已经解析好的 {@code mergedDir}/{@code folderName}，
 * {@link SeparateImageOutput} 拿的是 {@code outputFolder}；谁都不需要 {@code boolean merged}。</p>
 */
interface ImageOutputStrategy {

    /**
     * 保存一个文件的图片。
     *
     * @return 实际写入成功数（0 = 全部写入失败，由主循环记 FAILED）
     * @throws RuntimeException 该文件级的失败（单独输出模式用它把"归档失败"记成 FAILED）
     */
    int saveImages(ImageSource images, String extractedTitle, OutputContext ctx);

    /**
     * 全部文件处理完之后（末尾归档等）。合并输出在此归档；单独输出无需批量动作。
     *
     * <p>这里没有"当前文件"上下文（循环已结束），所以只传批级所需的三样：配置、输出根、统计。
     * 合并输出需要的已解析目录名是自己的字段 —— 不要求一个 {@code OutputContext} 里塞 null 文件。</p>
     */
    void onAllFilesProcessed(com.mhtextractor.model.AppConfig config, String outputFolder,
                             BatchProcessor.ProcessStats stats);
}
