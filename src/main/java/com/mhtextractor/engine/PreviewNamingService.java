package com.mhtextractor.engine;

import com.mhtextractor.model.PreviewImageData;

import com.mhtextractor.extractor.ImageExtractor;
import com.mhtextractor.output.FileWriter;
import com.mhtextractor.output.ImageWriter;
import com.mhtextractor.util.SanitizeUtils;

import java.time.LocalDateTime;

/**
 * 预览渲染的命名/扩展名纯函数（P3-3b）：{@code gui.controller.PreviewRenderer} 对
 * {@code output.FileWriter} / {@code output.ImageWriter} / {@code util.SanitizeUtils} 的
 * 三处纯静态调用经本类 1:1 收口 —— 参数顺序与返回语义与底层完全一致（只搬不改），
 * gui 层不再直连 {@code output} / {@code util} 包。
 *
 * <p>本类<b>零 JavaFX、零 I18n</b>，属 {@code app} 层。</p>
 */
public final class PreviewNamingService {

    private PreviewNamingService() {
    }

    /**
     * 模板变量替换（预览窗口渲染与正式输出共用）：1:1 转发 {@link FileWriter#processTemplate}。
     */
    public static String applyTemplate(String template, String name, LocalDateTime modifiedTime) {
        return FileWriter.processTemplate(template, name, modifiedTime);
    }

    /**
     * 按魔数检测图片实际扩展名：1:1 转发 {@link SanitizeUtils#detectExtensionByMagicNumber}。
     */
    public static String detectExtension(byte[] data, String fallbackExt) {
        return SanitizeUtils.detectExtensionByMagicNumber(data, fallbackExt);
    }

    /**
     * 图片最终输出文件名（纯命名逻辑，预览窗口与写盘共用）：以 {@link PreviewImageData} 为载体
     * 转成 {@code ImageExtractor.ImageData} 后 1:1 转发 {@link ImageWriter#computeFileName}。
     */
    public static String imageFileName(PreviewImageData imageData, String fallbackExt,
                                       boolean rename, int renameIndex, String filePrefix) {
        ImageExtractor.ImageData underlying = imageData == null ? null
                : new ImageExtractor.ImageData(imageData.originalName(), imageData.sourceId(), imageData.data());
        return ImageWriter.computeFileName(underlying, fallbackExt, rename, renameIndex, filePrefix);
    }
}
