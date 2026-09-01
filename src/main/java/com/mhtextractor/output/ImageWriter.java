package com.mhtextractor.output;

import com.mhtextractor.extractor.ImageExtractor;
import com.mhtextractor.util.SanitizeUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * 图片保存工具类
 * 支持单张图片保存、批量保存到目录、多线程并发保存
 */
@Slf4j
public class ImageWriter {

    private static final int MAX_RENAME_ATTEMPTS = 1000;

    /**
     * 保存单张图片到指定目录（支持文件名前缀）
     *
     * @param imageData 图片数据
     * @param outputDir 输出目录
     * @param fallbackExt 默认扩展名
     * @param rename 是否启用批量重命名
     * @param counter 重命名计数器引用（长度为1的数组）
     * @param filePrefix 文件名前缀（用于合并模式区分不同源文件）
     * @return 保存的文件名，失败返回 null
     */
    public static String saveImage(ImageExtractor.ImageData imageData, String outputDir,
                                   String fallbackExt, boolean rename, int[] counter, String filePrefix) {
        if (imageData == null || imageData.getData() == null) return null;

        int renameIndex = rename ? counter[0]++ : 0;
        String finalFileName = computeFileName(imageData, fallbackExt, rename, renameIndex, filePrefix);

        File dest = new File(outputDir, finalFileName);

        // 处理文件名冲突（仅不重命名时）
        if (dest.exists() && !rename) {
            String ext = SanitizeUtils.detectExtensionByMagicNumber(imageData.getData(), fallbackExt);
            dest = findUniqueFileName(outputDir, finalFileName.substring(0, finalFileName.lastIndexOf('.')), ext);
            finalFileName = dest.getName();
        }

        try (FileOutputStream fos = new FileOutputStream(dest)) {
            fos.write(imageData.getData());
            fos.flush();
            log.debug("[图片] 已保存：{}", finalFileName);
            return finalFileName;
        } catch (IOException e) {
            log.error("[图片] 保存失败：{}", finalFileName, e);
            return null;
        }
    }

    /**
     * 计算图片最终输出文件名（纯命名逻辑，预览窗口与写盘共用；磁盘冲突后缀属运行时行为，不在此处理）
     *
     * @param imageData 图片数据
     * @param fallbackExt 默认扩展名
     * @param rename 是否批量重命名（序号格式 00001.ext）
     * @param renameIndex 重命名序号（1 起）
     * @param filePrefix 文件名前缀（合并模式区分不同源文件）
     */
    public static String computeFileName(ImageExtractor.ImageData imageData, String fallbackExt,
                                         boolean rename, int renameIndex, String filePrefix) {
        String ext = SanitizeUtils.detectExtensionByMagicNumber(imageData.getData(), fallbackExt);

        if (rename) {
            return String.format("%05d.%s", renameIndex, ext);
        } else if (filePrefix != null && !filePrefix.isEmpty()) {
            // 合并模式不重命名：文件前缀 + 原始文件名
            String cleanName = SanitizeUtils.sanitize(imageData.getFilename());
            cleanName = stripExtension(cleanName);
            return filePrefix + cleanName + "." + ext;
        } else {
            String cleanName = SanitizeUtils.sanitize(imageData.getFilename());
            cleanName = stripExtension(cleanName);
            if (cleanName.matches("\\d+")) {
                cleanName = "img_" + cleanName;
            }
            return cleanName + "." + ext;
        }
    }

    /**
     * 去除文件名中已有的扩展名
     */
    private static String stripExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) return "unnamed";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            String potentialExt = fileName.substring(dotIndex + 1).toLowerCase();
            // 如果已有扩展名是合法的图像格式，则去掉
            for (String validExt : new String[]{"jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico", "avif", "cur"}) {
                if (validExt.equals(potentialExt)) {
                    return fileName.substring(0, dotIndex);
                }
            }
        }
        return fileName;
    }

    /**
     * 批量保存图片到指定目录（顺序保存）
     *
     * @param images 图片列表
     * @param outputDir 输出目录
     * @param fallbackExt 默认扩展名
     * @param rename 是否启用批量重命名
     * @return 保存成功的数量
     */
    public static int saveImages(List<ImageExtractor.ImageData> images, String outputDir,
                                 String fallbackExt, boolean rename) {
        int[] counter = {0};
        int saved = 0;
        for (ImageExtractor.ImageData imageData : images) {
            String result = saveImage(imageData, outputDir, fallbackExt, rename, counter, "");
            if (result != null) saved++;
        }
        return saved;
    }

    /**
     * 顺序保存图片
     *
     * @param images 图片列表
     * @param outputDir 输出目录
     * @param fallbackExt 默认扩展名
     * @param rename 是否启用批量重命名
     * @param filePrefix 文件名前缀
     * @return 保存成功的数量
     */
    public static int saveImagesConcurrent(List<ImageExtractor.ImageData> images, String outputDir,
                                           String fallbackExt, boolean rename, String filePrefix) {
        return saveImagesConcurrent(images, outputDir, fallbackExt, rename, filePrefix, null);
    }

    /**
     * 顺序保存图片（带进度回调）
     *
     * @param images 图片列表
     * @param outputDir 输出目录
     * @param fallbackExt 默认扩展名
     * @param rename 是否启用批量重命名
     * @param filePrefix 文件名前缀
     * @param progressCallback 每保存一张图片后回调，参数为已保存数量
     * @return 保存成功的数量
     */
    public static int saveImagesConcurrent(List<ImageExtractor.ImageData> images, String outputDir,
                                           String fallbackExt, boolean rename, String filePrefix,
                                           IntConsumer progressCallback) {
        int[] counter = {0};
        int saved = 0;
        for (int i = 0; i < images.size(); i++) {
            counter[0] = i + 1;
            String result = saveImage(images.get(i), outputDir, fallbackExt, rename, counter, filePrefix);
            if (result != null) saved++;
            if (progressCallback != null) {
                progressCallback.accept(saved);
            }
        }
        return saved;
    }

    /**
     * 顺序保存图片（全局计数器版本 - 流式合并模式专用）
     *
     * @param images 图片列表
     * @param outputDir 输出目录
     * @param fallbackExt 默认扩展名
     * @param rename 是否启用批量重命名
     * @param filePrefix 文件名前缀（用于不重命名模式区分不同源文件）
     * @param globalIndex 全局计数器（跨文件递增）
     * @return 保存成功的数量
     */
    public static int saveImagesWithGlobalIndex(List<ImageExtractor.ImageData> images, String outputDir,
                                                String fallbackExt, boolean rename, String filePrefix,
                                                AtomicInteger globalIndex) {
        return saveImagesWithGlobalIndex(images, outputDir, fallbackExt, rename, filePrefix, globalIndex, null);
    }

    /**
     * 顺序保存图片（全局计数器版本 - 带进度回调）
     *
     * @param images 图片列表
     * @param outputDir 输出目录
     * @param fallbackExt 默认扩展名
     * @param rename 是否启用批量重命名
     * @param filePrefix 文件名前缀（用于不重命名模式区分不同源文件）
     * @param globalIndex 全局计数器（跨文件递增）
     * @param progressCallback 每保存一张图片后回调，参数为已保存数量
     * @return 保存成功的数量
     */
    public static int saveImagesWithGlobalIndex(List<ImageExtractor.ImageData> images, String outputDir,
                                                String fallbackExt, boolean rename, String filePrefix,
                                                AtomicInteger globalIndex, IntConsumer progressCallback) {
        int saved = 0;
        for (ImageExtractor.ImageData imageData : images) {
            int[] idx = {globalIndex.getAndIncrement()};
            String result = saveImage(imageData, outputDir, fallbackExt, rename, idx, filePrefix);
            if (result != null) saved++;
            if (progressCallback != null) {
                progressCallback.accept(saved);
            }
        }
        return saved;
    }

    /**
     * 查找不重复的文件名
     */
    private static File findUniqueFileName(String outputDir, String baseName, String ext) {
        for (int i = 1; i < MAX_RENAME_ATTEMPTS; i++) {
            File candidate = new File(outputDir, baseName + "_" + i + "." + ext);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(outputDir, baseName + "_" + System.currentTimeMillis() + "." + ext);
    }
}
