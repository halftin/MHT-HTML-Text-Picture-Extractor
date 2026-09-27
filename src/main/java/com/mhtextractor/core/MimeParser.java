package com.mhtextractor.core;

import com.mhtextractor.util.EncodingUtils;
import com.mhtextractor.util.SanitizeUtils;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimePart;
import jakarta.mail.internet.MimeUtility;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 统一MHT文件解析器
 * 负责读取 .mht 文件，利用 JavaMail 解析 MIME 树
 * 返回结构化的 ParsedMht 对象
 */
public class MimeParser {
    private static final Logger logger = LoggerFactory.getLogger(MimeParser.class);

    /** 递归深度限制阈值 */
    private static final int RECURSION_LIMIT = 100;

    /** 每处理 N 个 part 报告一次进度 */
    private static final int PROGRESS_INTERVAL = 5;

    /** 解析阶段枚举，用于进度报告 */
    public enum ParsingStage {
        FILE_READ,
        HTML_EXTRACTED,
        MIME_TRAVERSED,
        IMAGES_EXTRACTED,
        COMPLETE
    }

    /**
     * 解析MHT文件，支持递归深度限制回调和进度报告
     *
     * @param mhtPath 文件路径
     * @param continueCallback 递归超限时回调，用于询问用户；超限子树始终被截断（继续递归有 StackOverflowError 风险），
     *                         返回 false 则终止解析
     *                         回调格式：Supplier<Boolean>
     *                         传入 null 则不询问，超限子树直接截断
     * @param progressCallback 进度报告回调，可传入 null 禁用进度报告
     */
    public ParsedMht parse(String mhtPath, Supplier<Boolean> continueCallback,
                           Consumer<ParsingStage> progressCallback) throws Exception {
        return parse(mhtPath, false, continueCallback, progressCallback, null);
    }

    /**
     * 解析MHT文件，支持递归深度限制回调和细粒度进度报告
     *
     * @param mhtPath 文件路径
     * @param continueCallback 递归超限时回调，返回 true 继续解析，false 终止解析
     * @param stageCallback 阶段进度回调（ParsingStage 枚举），可传入 null
     * @param byteProgressCallback 细粒度进度回调（0.0~1.0，覆盖 0.05~0.35 解析阶段），可传入 null
     */
    public ParsedMht parse(String mhtPath, Supplier<Boolean> continueCallback,
                           Consumer<ParsingStage> stageCallback,
                           Consumer<Double> byteProgressCallback) throws Exception {
        return parse(mhtPath, false, continueCallback, stageCallback, byteProgressCallback);
    }

    /**
     * 解析MHT文件（textOnly=true 跳过图片二进制读取，供预览预解析使用）
     */
    public ParsedMht parse(String mhtPath, boolean textOnly, Supplier<Boolean> continueCallback,
                           Consumer<ParsingStage> stageCallback,
                           Consumer<Double> byteProgressCallback) throws Exception {
        Properties props = new Properties();
        props.setProperty("mail.mime.decodetext.strict", "false");
        props.setProperty("mail.mime.charset", "UTF-8");
        Session session = Session.getInstance(props);

        // 细粒度进度须单调不减：HTML 提取(0.15)可能晚于部分循环进度(最高 0.33)，
        // 直接上报会造成进度条倒退
        Consumer<Double> monotonicByteProgress = byteProgressCallback == null ? null : new Consumer<>() {
            private double last = 0.0;

            @Override
            public void accept(Double value) {
                double v = Math.max(last, value);
                last = v;
                byteProgressCallback.accept(v);
            }
        };

        try (FileInputStream fis = new FileInputStream(mhtPath)) {
            if (stageCallback != null) stageCallback.accept(ParsingStage.FILE_READ);
            MimeMessage message = new MimeMessage(session, fis);

            // 首次快速遍历统计总 part 数，用于进度估算
            int totalParts = countTotalParts(message);
            if (totalParts < 1) totalParts = 1;

            // 原子计数器：跟踪已处理的 part 数，用于周期性报告进度
            AtomicInteger partsProcessed = new AtomicInteger(0);

            return parseMimeMessage(message, textOnly, continueCallback, stageCallback, monotonicByteProgress,
                    partsProcessed, totalParts);
        }
    }

    /**
     * 迭代统计 MIME 树 part 数，用于进度估算。
     * 显式栈实现且截断深度与 extractFromPart 一致：畸形深层嵌套不会在此处触发 StackOverflowError。
     */
    private int countTotalParts(jakarta.mail.Part root) {
        Deque<jakarta.mail.Part> parts = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        parts.push(root);
        depths.push(0);
        int count = 0;
        while (!parts.isEmpty()) {
            jakarta.mail.Part part = parts.pop();
            int depth = depths.pop();
            count++;
            try {
                if (depth < RECURSION_LIMIT && part.isMimeType("multipart/*")) {
                    Multipart mp = (Multipart) part.getContent();
                    for (int i = mp.getCount() - 1; i >= 0; i--) {
                        parts.push(mp.getBodyPart(i));
                        depths.push(depth + 1);
                    }
                }
            } catch (Exception e) {
                logger.debug("统计MIME部件总数失败：{}", e.getMessage(), e);
            }
        }
        return count;
    }

    /**
     * 从MimeMessage中提取HTML内容和CID映射
     */
    private ParsedMht parseMimeMessage(MimeMessage message, boolean textOnly,
                                       Supplier<Boolean> continueCallback,
                                       Consumer<ParsingStage> stageCallback,
                                       Consumer<Double> byteProgressCallback,
                                       AtomicInteger partsProcessed, int totalParts) throws Exception {
        ParsedMht result = new ParsedMht();
        result.setCidMap(new LinkedHashMap<>());
        result.setLocationMap(new LinkedHashMap<>());
        result.setLocationDataMap(new LinkedHashMap<>());
        result.setImageInfoList(new ArrayList<>());

        extractFromPart(message, result, 0, textOnly, continueCallback, stageCallback, byteProgressCallback,
                partsProcessed, totalParts);
        if (stageCallback != null) stageCallback.accept(ParsingStage.MIME_TRAVERSED);
        if (byteProgressCallback != null) byteProgressCallback.accept(0.35);
        return result;
    }

    /**
     * 递归遍历MIME树，提取HTML和资源
     *
     * @param part 当前MIME节点
     * @param result 解析结果
     * @param depth 当前递归深度
     * @param continueCallback 递归深度超限时是否继续的回调
     * @param stageCallback 阶段进度回调
     * @param byteProgressCallback 细粒度进度回调（0.0~1.0）
     * @param partsProcessed 已处理 part 计数器
     * @param totalParts 预估总 part 数
     */
    private void extractFromPart(jakarta.mail.Part part, ParsedMht result,
                                 int depth, boolean textOnly, Supplier<Boolean> continueCallback,
                                 Consumer<ParsingStage> stageCallback,
                                 Consumer<Double> byteProgressCallback,
                                 AtomicInteger partsProcessed, int totalParts) throws Exception {
        if (part.isMimeType("text/html")) {
            if (result.getHtmlContent() == null) {
                result.setHtmlContent(extractHtmlFromPart(part));
                if (stageCallback != null) stageCallback.accept(ParsingStage.HTML_EXTRACTED);
                if (byteProgressCallback != null) byteProgressCallback.accept(0.15);
            }
        } else if (part.isMimeType("image/*")) {
            if (!textOnly) {
                extractImagePart(part, result, stageCallback);
            }
        } else if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                int childDepth = depth + 1;
                if (childDepth > RECURSION_LIMIT) {
                    logger.warn("[递归警告] MIME树深度已达 {} 层（阈值 {}），可能为损坏文件", childDepth, RECURSION_LIMIT);
                    if (continueCallback != null) {
                        if (!continueCallback.get()) {
                            logger.warn("[递归警告] 用户取消解析，已提取 {} 个CID映射，{} 个资源",
                                    result.getCidMap().size(), result.getLocationDataMap().size());
                            continue;
                        }
                        // 用户选择继续：不再重复询问
                        continueCallback = null;
                    }
                    // 深度超限一律截断该子树（与回调无关）：继续递归有 StackOverflowError 风险（Error 不被 catch Exception 捕获）
                    continue;
                }
                extractFromPart(mp.getBodyPart(i), result, childDepth, textOnly, continueCallback,
                        stageCallback, byteProgressCallback, partsProcessed, totalParts);
                // 每处理 PROGRESS_INTERVAL 个 part 报告一次细粒度进度
                int processed = partsProcessed.incrementAndGet();
                if (byteProgressCallback != null && processed % PROGRESS_INTERVAL == 0) {
                    double progress = 0.05 + 0.28 * ((double) processed / totalParts);
                    byteProgressCallback.accept(Math.min(progress, 0.33));
                }
            }
        }
    }

    /**
     * 从MimePart中提取HTML内容
     * 支持多编码降级：当MIME头未声明charset或声明错误时，尝试多种编码并选择最优
     */
    private String extractHtmlFromPart(jakarta.mail.Part part) throws Exception {
        // 必须取原始流再交给 MimeUtility.decode 解码一次：Part.getInputStream() 已按
        // Content-Transfer-Encoding 解码过，直接再用 MimeUtility.decode 会双重解码
        // （quoted-printable 的 =3D 转义会被二次解析，如 width=3Ddevice-width 一次解码后
        // 变成 width=device-width，再解一次 =de 被当十六进制转义成乱码）
        InputStream raw;
        if (part instanceof MimeBodyPart mimeBodyPart) {
            raw = mimeBodyPart.getRawInputStream();
        } else if (part instanceof MimeMessage mimeMessage) {
            raw = mimeMessage.getRawInputStream();
        } else {
            raw = part.getInputStream();
        }
        if (raw == null) return null;
        try (InputStream is = raw) {
            String encoding = (part instanceof MimePart)
                    ? ((MimePart) part).getEncoding() : null;
            if (encoding == null) encoding = "utf-8";

            String declaredCharset = "UTF-8";
            try {
                String ct = part.getContentType();
                if (ct != null) {
                    ContentType contentType = new ContentType(ct);
                    String cs = contentType.getParameter("charset");
                    if (cs != null) declaredCharset = cs;
                }
            } catch (Exception e) {
                logger.debug("获取Content-Type charset失败：{}", e.getMessage(), e);
            }

            try (InputStream decodedIs = MimeUtility.decode(is, encoding)) {
                byte[] bytes = decodedIs.readAllBytes();

                // 多编码降级：当声明的charset与实际编码不符时，尝试常见中文编码
                String[] tryCharsets = {declaredCharset, "UTF-8", "GBK", "GB2312", "GB18030", "ISO-8859-1"};
                for (String tryCharset : tryCharsets) {
                    try {
                        String result = new String(bytes, tryCharset);
                        // 如果包含中文且没有U+FFFD替换字符，说明编码正确
                        if (EncodingUtils.containsChinese(result) && !EncodingUtils.containsGarbled(result)) {
                            logger.info("使用编码: {} 解码成功", tryCharset);
                            return result;
                        }
                    } catch (Exception e) {
                        logger.debug("编码 {} 解析失败：{}", tryCharset, e.getMessage(), e);
                    }
                }

                // 兜底：使用声明的charset
                return new String(bytes, declaredCharset);
            }
        }
    }


    /**
     * 提取图片资源，按CID和Content-Location建立映射
     */
    private void extractImagePart(jakarta.mail.Part part, ParsedMht result,
                                  Consumer<ParsingStage> progressCallback) throws Exception {
        String contentId = null;
        String contentLocation = null;
        String filename = null;

        if (part instanceof MimePart) {
            String[] cids = part.getHeader("Content-ID");
            if (cids != null && cids.length > 0) {
                contentId = cids[0];
                if (contentId.startsWith("<") && contentId.endsWith(">")) {
                    contentId = contentId.substring(1, contentId.length() - 1);
                }
            }

            String[] locations = part.getHeader("Content-Location");
            if (locations != null && locations.length > 0) {
                contentLocation = locations[0];
            }

            // 尝试多种方式获取原始文件名
            filename = getFilenameFromPart(part);
            if ((filename == null || filename.isEmpty()) && contentLocation != null) {
                // 从 Content-Location URL 中提取文件名
                filename = SanitizeUtils.extractFilenameFromUrl(contentLocation);
            }
        }

        try (InputStream is = part.getInputStream()) {
            byte[] data = is.readAllBytes();
            if (contentId != null && !contentId.isEmpty()) {
                result.getCidMap().put(contentId, data);
            }
            // 存储以 Content-Location 为 key 的图片数据
            if (contentLocation != null && !contentLocation.isEmpty()) {
                result.getLocationDataMap().put(contentLocation, data);
                if (filename != null && !filename.isEmpty()) {
                    result.getLocationMap().put(contentLocation, filename);
                }
            }
            // 记录图片信息（包含原始文件名和出现顺序）
            result.getImageInfoList().add(new ImageInfo(contentId, contentLocation, filename, data));
            if (progressCallback != null) progressCallback.accept(ParsingStage.IMAGES_EXTRACTED);
        }
    }

    /**
     * 从 MIME Part 中提取文件名
     */
    private String getFilenameFromPart(jakarta.mail.Part part) throws Exception {
        // 优先使用 Jakarta Mail 的 getFileName()：正确解析引号与后续参数（如 filename="a.png"; size=1234），
        // 并解码 RFC 2231 编码参数（filename*=UTF-8''...）
        String name = part.getFileName();
        if (name != null && !name.isEmpty()) {
            // getFileName() 不解码 RFC 2047 编码词（=?UTF-8?B?...?=），需补一层 decodeText；
            // 对无编码词的普通文件名是无害空操作
            return MimeUtility.decodeText(name);
        }

        // 兜底：畸形头部（如引号不配对）下 getFileName() 可能返回 null，手写解析尽力恢复原始文件名
        name = extractMimeParam(part.getDisposition(), "filename");
        if (name != null) return name;

        if (part instanceof MimePart) {
            name = extractMimeParam(part.getContentType(), "name");
            return name;
        }
        return null;
    }

    /**
     * 从 MIME 头部字符串中提取指定参数的值（兜底用的尽力而为解析）
     */
    private static String extractMimeParam(String source, String paramName) {
        if (source == null) return null;
        int keywordIdx = source.toLowerCase().indexOf(paramName.toLowerCase());
        if (keywordIdx == -1) return null;
        int eqIdx = source.indexOf('=', keywordIdx);
        if (eqIdx == -1 || eqIdx >= source.length() - 1) return null;
        String val = source.substring(eqIdx + 1).trim();
        if (val.startsWith("\"")) {
            int endQuote = val.indexOf('"', 1);
            if (endQuote == -1) return null;
            val = val.substring(1, endQuote);
        } else {
            // 未加引号的参数值到下一个 ';' 为止，避免把后续参数一并吞进文件名
            int semiIdx = val.indexOf(';');
            if (semiIdx != -1) {
                val = val.substring(0, semiIdx).trim();
            }
        }
        return val.isEmpty() ? null : val;
    }

    /**
     * 解析结果数据结构
     */
    @Data
    public static class ParsedMht {
        private String htmlContent;
        private Map<String, byte[]> cidMap;
        private Map<String, String> locationMap;
        private Map<String, byte[]> locationDataMap;
        /** 按 MHT 中声明顺序排列的图片信息列表 */
        private List<ImageInfo> imageInfoList;
    }

    /**
     * 图片信息（包含原始文件名和顺序）
     */
    @Data
    public static class ImageInfo {
        private String contentId;
        private String contentLocation;
        private String filename;
        private byte[] data;

        public ImageInfo(String contentId, String contentLocation, String filename, byte[] data) {
            this.contentId = contentId;
            this.contentLocation = contentLocation;
            this.filename = filename;
            this.data = data;
        }
    }
}
