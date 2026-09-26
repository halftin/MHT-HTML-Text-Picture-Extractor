package com.mhtextractor.model;

/**
 * 缩略图数据（会话 18 · E1.4 引入，E2.1 随 {@link PreviewItem} 迁入 model 包）。
 *
 * <p><b>纯数据，零 JavaFX 依赖</b>。原先缩略图直接持有 {@code javafx.scene.image.Image}，
 * 使模型层无法脱离 UI 框架（验收 V4）。改为持有解码缩放后的 ARGB 像素后，
 * "模型 → JavaFX Image" 的转换被收口到渲染层。</p>
 *
 * <p><b>为什么存 {@code int[]} 原始像素而不是原图字节或 PNG 字节</b>：</p>
 * <ul>
 *   <li>存<b>原图字节</b>会让内存随图片尺寸线性增长 —— 直接违反需求 2 的 C2
 *       （原实现正是靠"只留 150px 缩略图、丢弃原图字节"满足 C2 的）；</li>
 *   <li>存 <b>PNG 字节</b>需要图像编码器（{@code ImageIO} 属 {@code java.desktop}/AWT），
 *       与本项目"为 native 镜像尽量避开 AWT"的取向冲突；</li>
 *   <li>存<b>缩放后的 ARGB 像素</b>无需任何编码器，且每张上限约 150×150×4 ≈ 88 KB
 *       （缩放边界 150），整个工作集不到 1 MB —— 与原 {@code Image} 对象量级相同，
 *       故 <b>C2 的内存口径不变</b>。</li>
 * </ul>
 *
 * <p><b>null 语义</b>：{@code pixels == null} 表示"无法加载"，渲染层据此显示占位文案
 * （与原 {@code image == null} 完全一致）。</p>
 *
 * @param coreName 不含 {@code f%02d_} 前缀的文件名（重排时按新位置重算前缀）
 * @param width    像素宽（{@code pixels == null} 时为 0）
 * @param height   像素高（{@code pixels == null} 时为 0）
 * @param pixels   行优先连续 ARGB 像素（stride == width）；null = 无法加载
 */
public record ThumbnailData(String coreName, int width, int height, int[] pixels) {
}
