package com.mhtextractor.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文件名清理工具（包含魔数检测）
 */
public class SanitizeUtils {

    private static final Logger logger = LoggerFactory.getLogger(SanitizeUtils.class);

    private static final String[] IMAGE_MAGIC_NUMBERS = {
            "FFD8FF",   // JPEG
            "89504E47", // PNG
            "47494638", // GIF
            "424D",     // BMP
            "52494646", // RIFF (WebP, AVIF)
            "00000100", // ICO
            "00000200", // CUR
    };

    private static final String[] IMAGE_EXTENSIONS = {
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico", "avif", "cur"
    };

    /**
     * 清理文件名中的非法字符
     */
    public static String sanitize(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "unnamed";
        }
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("_+", "_")
                .trim();
    }

    /**
     * 从文件名获取扩展名
     */
    public static String getExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "jpg";
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex != -1 && dotIndex < filename.length() - 1) {
            String ext = filename.substring(dotIndex + 1).toLowerCase();
            for (String validExt : IMAGE_EXTENSIONS) {
                if (validExt.equals(ext)) {
                    return ext;
                }
            }
        }
        return "jpg";
    }

    /**
     * 根据魔数检测实际的图片扩展名
     */
    public static String detectExtensionByMagicNumber(byte[] data, String fallbackExt) {
        if (data == null || data.length < 4) {
            return fallbackExt;
        }

        // JPEG: FF D8 FF
        if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8 && data[2] == (byte) 0xFF) {
            return "jpg";
        }
        // PNG: 89 50 4E 47
        if (data[0] == (byte) 0x89 && data[1] == (byte) 0x50
                && data[2] == (byte) 0x4E && data[3] == (byte) 0x47) {
            return "png";
        }
        // GIF: 47 49 46 38
        if (data[0] == (byte) 0x47 && data[1] == (byte) 0x49
                && data[2] == (byte) 0x46 && data[3] == (byte) 0x38) {
            return "gif";
        }
        // BMP: 42 4D
        if (data[0] == (byte) 0x42 && data[1] == (byte) 0x4D) {
            return "bmp";
        }
        // ICO: 00 00 01 00
        if (data[0] == 0 && data[1] == 0 && data[2] == 1 && data[3] == 0) {
            return "ico";
        }
        // CUR: 00 00 02 00
        if (data[0] == 0 && data[1] == 0 && data[2] == 2 && data[3] == 0) {
            return "cur";
        }
        // WebP: RIFF....WEBP
        if (data.length >= 12 && data[0] == (byte) 0x52 && data[1] == (byte) 0x49
                && data[2] == (byte) 0x46 && data[3] == (byte) 0x46
                && data[8] == (byte) 0x57 && data[9] == (byte) 0x45
                && data[10] == (byte) 0x42 && data[11] == (byte) 0x50) {
            return "webp";
        }
        // AVIF: ftypavif
        if (data.length >= 12 && data[4] == (byte) 0x66 && data[5] == (byte) 0x74
                && data[6] == (byte) 0x79 && data[7] == (byte) 0x70
                && data[8] == (byte) 0x61 && data[9] == (byte) 0x76
                && data[10] == (byte) 0x69 && data[11] == (byte) 0x66) {
            return "avif";
        }
        // SVG detection
        if ("svg".equals(fallbackExt)) {
            String startStr = new String(data, 0, Math.min(100, data.length));
            if (startStr.startsWith("<?xml") || startStr.startsWith("<svg")) {
                return "svg";
            }
            // BOM + XML
            if (data.length >= 7 && data[0] == (byte) 0xEF && data[1] == (byte) 0xBB
                    && data[2] == (byte) 0xBF) {
                String afterBom = new String(data, 3, Math.min(10, data.length - 3)).trim();
                if (afterBom.startsWith("<?xml")) {
                    return "svg";
                }
            }
            return "jpg"; // fallback
        }

        return fallbackExt;
    }

    /**
     * 解码 Data URI scheme，返回字节数组
     */
    public static byte[] decodeDataUri(String dataUri) {
        try {
            String content = dataUri.substring(5);
            int semicolonIndex = content.indexOf(';');
            if (semicolonIndex == -1) return null;
            int commaIndex = content.indexOf(',');
            if (commaIndex == -1) return null;
            String base64Data = content.substring(commaIndex + 1);
            return java.util.Base64.getDecoder().decode(base64Data.trim());
        } catch (Exception e) {
            logger.debug("Data URI解码失败：{}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从 URL 中提取文件名部分
     */
    public static String extractFilenameFromUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash != -1 && lastSlash < url.length() - 1) {
            return url.substring(lastSlash + 1);
        }
        return url;
    }

    /**
     * 从 URL 中提取扩展名（支持 Data URI）
     */
    public static String getExtensionFromUrl(String url) {
        if (url == null || url.isEmpty()) return "jpg";
        if (url.startsWith("data:")) {
            int mimeEnd = url.indexOf(';');
            if (mimeEnd > 5) {
                String mimeType = url.substring(5, mimeEnd).toLowerCase();
                return switch (mimeType) {
                    case "image/png" -> "png";
                    case "image/gif" -> "gif";
                    case "image/bmp" -> "bmp";
                    case "image/webp" -> "webp";
                    default -> "jpg";
                };
            }
            return "jpg";
        }
        String cleanUrl = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        int dotIndex = cleanUrl.lastIndexOf('.');
        if (dotIndex != -1 && dotIndex < cleanUrl.length() - 1) {
            String ext = cleanUrl.substring(dotIndex + 1).toLowerCase();
            for (String validExt : IMAGE_EXTENSIONS) {
                if (validExt.equals(ext)) return ext;
            }
        }
        return "jpg";
    }
}
