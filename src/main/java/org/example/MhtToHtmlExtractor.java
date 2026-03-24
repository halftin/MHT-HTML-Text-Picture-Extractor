package org.example;


import java.io.*;

import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimePart;
import jakarta.mail.internet.MimeUtility;
import java.io.FileInputStream;
import java.io.InputStream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MHT文件文字提取工具类（支持多种编码修复 + 内容区域限定）
 */
public class MhtToHtmlExtractor {
    private static final Logger logger = LoggerFactory.getLogger(MhtToHtmlExtractor.class);

    /**
     * 从指定路径的MHT文件中提取文字内容（支持内容区域限定）
     * @param filePath MHT文件的路径
     * @param autoFixEncoding 是否自动修复编码乱码
     * @param startMarker 起始标记（HTML标签或正则表达式）
     * @param endMarker 结束标记（HTML标签或正则表达式）
     * @param useRegex 是否使用正则表达式匹配标记
     * @return 提取到的文字内容，如果提取失败则返回null
     */
    public static String extractTextFromMht(String filePath, boolean autoFixEncoding,
                                            String startMarker, String endMarker, boolean useRegex) {
        try {
            java.util.Properties props = new java.util.Properties();
            props.setProperty("mail.mime.decodetext.strict", "false");
            // 尝试多种编码
            props.setProperty("mail.mime.charset", "UTF-8");
            props.setProperty("file.encoding", "UTF-8");

            Session session = Session.getInstance(props);

            try (InputStream is = new FileInputStream(filePath)) {
                MimeMessage message = new MimeMessage(session, is);
                String htmlContent = extractHtmlContent(message);

                if (htmlContent != null) {
                    // 从HTML中检测实际编码
                    String detectedCharset = EncodingUtils.detectCharsetFromHtml(htmlContent);
                    logger.info("检测到HTML编码: {}" , (detectedCharset != null ? detectedCharset : "未检测到"));

                    // 使用检测到的编码重新解析（如果有）
                    if (detectedCharset != null) {
                        try {
                            byte[] htmlBytes = htmlContent.getBytes("ISO-8859-1");
                            htmlContent = new String(htmlBytes, detectedCharset);
                        } catch (Exception e) {
                            // 忽略，使用原始内容
                        }
                    }

                    // 根据标记截取内容区域
                    if (startMarker != null && !startMarker.trim().isEmpty() &&
                            endMarker != null && !endMarker.trim().isEmpty()) {
                        htmlContent = extractContentByMarker(htmlContent, startMarker.trim(), endMarker.trim(), useRegex);
                    }

                    // 使用Jsoup解析HTML并提取文字
                    String text = extractTextFromHtml(htmlContent);

                    // 自动修复编码问题
                    if (autoFixEncoding && text != null) {
                        text = EncodingUtils.smartFix(text);
                    }

                    return text;
                } else {
                    logger.info("未找到HTML部分");
                }
            }
        } catch (Exception e) {
            logger.error("操作失败", e);
        }

        return null;
    }

    /**
     * 根据起始和结束标记截取HTML内容
     * @param html 原始HTML内容
     * @param startMarker 起始标记
     * @param endMarker 结束标记
     * @param useRegex 是否使用正则表达式匹配
     * @return 截取后的HTML内容，如果找不到标记则返回原始HTML
     */
    private static String extractContentByMarker(String html, String startMarker, String endMarker, boolean useRegex) {
        if (html == null || html.isEmpty()) {
            logger.info("[内容截取] HTML内容为空");
            return html;
        }

        if (startMarker == null || startMarker.isEmpty() || endMarker == null || endMarker.isEmpty()) {
            logger.info("[内容截取] 标记为空，返回完整HTML");
            return html;
        }

        try {
            String result;
            if (useRegex) {
                result = extractByRegex(html, startMarker, endMarker);
            } else {
                result = extractByString(html, startMarker, endMarker);
            }

            if (result != null) {
                logger.info("[内容截取] 成功截取内容，长度: {}" , result.length());
                return result;
            } else {
                logger.info("[内容截取] 未找到匹配标记，返回完整HTML");
                return html;
            }
        } catch (Exception e) {
            logger.error("[内容截取] 截取失败: {}" , e.getMessage());
            logger.error("操作失败", e);
            return html; // 降级处理，返回原始HTML
        }
    }

    /**
     * 使用字符串匹配方式截取内容
     * @param html 原始HTML内容
     * @param startMarker 起始标记
     * @param endMarker 结束标记
     * @return 截取后的内容，找不到返回null
     */
    private static String extractByString(String html, String startMarker, String endMarker) {
        int startIndex = html.indexOf(startMarker);
        if (startIndex == -1) {
            logger.info("[字符串匹配] 未找到起始标记: {}" , startMarker);
            return null;
        }

        // 从起始标记的末尾开始查找结束标记
        int contentStart = startIndex + startMarker.length();
        int endIndex = html.indexOf(endMarker, contentStart);

        if (endIndex == -1) {
            logger.info("[字符串匹配] 未找到结束标记: {}" , endMarker);
            // 结束标记找不到时，截取从起始标记到末尾的全部内容
            return html.substring(contentStart);
        }

        // 检查起始标记是否在结束标记之后
        if (startIndex > endIndex) {
            logger.info("[字符串匹配] 起始标记位置在结束标记之后，返回完整HTML");
            return null;
        }

        return html.substring(contentStart, endIndex);
    }

    /**
     * 使用正则表达式匹配方式截取内容
     * @param html 原始HTML内容
     * @param startMarker 起始标记（正则表达式）
     * @param endMarker 结束标记（正则表达式）
     * @return 截取后的内容，找不到返回null
     */
    private static String extractByRegex(String html, String startMarker, String endMarker) {
        try {
            // 构建正则表达式：startMarker(.*?)endMarker
            // DOTALL 模式让 . 匹配换行符
            // CASE_INSENSITIVE 忽略大小写
            String regex = Pattern.quote(startMarker) + "(.*?)" + Pattern.quote(endMarker);
            Pattern pattern = Pattern.compile(regex, Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(html);

            if (matcher.find()) {
                // group(1) 是两个标记之间的内容
                String content = matcher.group(1);
                logger.info("[正则匹配] 成功匹配，内容长度: {}" , content.length());
                return content;
            } else {
                logger.info("[正则匹配] 未找到匹配项");
                return null;
            }
        } catch (Exception e) {
            logger.error("[正则匹配] 正则表达式错误: {}" , e.getMessage());
            return null;
        }
    }

    /**
     * 从MimeMessage中提取text/html部分的内容（支持多种编码尝试）
     */
    private static String extractHtmlContent(MimeMessage message) throws MessagingException, java.io.IOException {
        if (message.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) message.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                if (part.isMimeType("multipart/*")) {
                    String nested = extractHtmlContentFromMultipart((Multipart) part.getContent());
                    if (nested != null) return nested;
                } else if (part.isMimeType("text/html")) {
                    return extractHtmlFromPart((MimePart) part);
                }
            }
        } else if (message.isMimeType("text/html")) {
            return extractHtmlFromPart(message);
        }
        return null;
    }

    /**
     * 从MimePart中提取HTML内容（支持多种编码）
     */
    private static String extractHtmlFromPart(MimePart part) throws MessagingException, java.io.IOException {
        Object content = part.getContent();
        if (content instanceof String) {
            return (String) content;
        } else if (content instanceof InputStream) {
            String encoding = part.getEncoding();
            String charset = getCharsetFromContentType(part.getContentType());

            logger.info("传输编码: {}" , encoding);
            logger.info("Content-Type charset: {}" , charset);

            try (InputStream rawIs = (InputStream) content;
                 InputStream decodedIs = MimeUtility.decode(rawIs, encoding)) {
                byte[] bytes = decodedIs.readAllBytes();

                // 尝试多种编码解析
                String[] tryCharsets = {charset, "UTF-8", "GBK", "GB2312", "ISO-8859-1"};
                for (String tryCharset : tryCharsets) {
                    try {
                        String result = new String(bytes, tryCharset);
                        // 如果包含中文且没有明显乱码，返回此结果
                        if (EncodingUtils.containsChinese(result) && !EncodingUtils.containsGarbled(result)) {
                            logger.info("使用编码: {}" , tryCharset);
                            return result;
                        }
                    } catch (Exception e) {
                        // 尝试下一个编码
                    }
                }

                // 默认使用声明的charset
                return new String(bytes, charset);
            }
        }
        return null;
    }

    // 辅助方法：从 Content-Type 字符串中提取 charset
    private static String getCharsetFromContentType(String contentType) {
        try {
            jakarta.mail.internet.ContentType ct = new jakarta.mail.internet.ContentType(contentType);
            String charset = ct.getParameter("charset");
            return (charset != null && !charset.isEmpty()) ? charset : "UTF-8";
        } catch (Exception e) {
            return "UTF-8";
        }
    }

    // 递归处理嵌套多部分
    private static String extractHtmlContentFromMultipart(Multipart multipart) throws MessagingException, java.io.IOException {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (part.isMimeType("multipart/*")) {
                String nested = extractHtmlContentFromMultipart((Multipart) part.getContent());
                if (nested != null) return nested;
            } else if (part.isMimeType("text/html")) {
                return extractHtmlFromPart((MimePart) part);
            }
        }
        return null;
    }

    /**
     * 使用Jsoup从HTML中提取所有文字
     */
    private static String extractTextFromHtml(String html) {
        if (html == null || html.trim().isEmpty()) {
            return "";
        }
        Document doc = Jsoup.parse(html);
        return doc.body() != null ? doc.body().wholeText() : "";
    }


    /**
     * 从 MHT 文件的 HTML 源码中提取隐藏的章节标题
     */
    public static String extractChapterTitle(String mhtPath, String startMarker, String endMarker, boolean useRegex) {
        try {
            // 1. 读取 MHT 中的 HTML 原始内容
            String htmlContent = getRawHtmlFromMht(mhtPath);
            if (htmlContent == null || htmlContent.isEmpty()) {
                return null;
            }

            // 2. 构建正则
            String patternStr;
            if (useRegex) {
                patternStr = startMarker + "(.*?)" + endMarker;
            } else {
                String safeStart = Pattern.quote(startMarker);
                String safeEnd = Pattern.quote(endMarker);
                patternStr = safeStart + "(.*?)" + safeEnd;
            }

            Pattern pattern = Pattern.compile(patternStr, Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(htmlContent);

            // 3. 提取并清理
            if (matcher.find()) {
                String title = matcher.group(1).trim();
                title = title.replaceAll("<[^>]*>", "").trim(); // 去除HTML标签
                title = title.replaceAll("\\s+", " ");          // 规范化空白
                if (!title.isEmpty()) {
                    return title;
                }
            }
        } catch (Exception e) {
            logger.error("[警告] 提取章节标题失败：{}" , e.getMessage());
        }
        return null;
    }

    /**
     * 【核心辅助】获取 MHT 中的原始 HTML 字符串
     * 复用 JavaMail 解析逻辑，但返回原始 HTML 而不是纯文本
     */
    private static String getRawHtmlFromMht(String mhtPath) throws Exception {
        File file = new File(mhtPath);
        java.util.Properties props = new java.util.Properties();
        props.setProperty("mail.mime.decodetext.strict", "false");
        jakarta.mail.Session session = jakarta.mail.Session.getInstance(props);

        try (FileInputStream fis = new FileInputStream(file)) {
            jakarta.mail.internet.MimeMessage message = new jakarta.mail.internet.MimeMessage(session, fis);
            return extractHtmlPart(message);
        }
    }

    /**
     * 递归查找 HTML 部分
     */
    private static String extractHtmlPart(jakarta.mail.Part part) throws Exception {
        if (part.isMimeType("text/html")) {
            // 使用 try-with-resources 确保原始流被关闭
            try (InputStream is = part.getInputStream()) {
                if (is != null) {
                    // 使用 MimeUtility 解码流（处理 base64, quoted-printable 等）
                    String encoding = null;
                    if (part instanceof jakarta.mail.internet.MimePart) {
                        encoding = ((jakarta.mail.internet.MimePart) part).getEncoding();
                    }
                    if (encoding == null) encoding = "utf-8"; // 默认


                    try (InputStream decodedIs = jakarta.mail.internet.MimeUtility.decode(is, encoding)) {
                        // 尝试获取 charset
                        String charset = "UTF-8";
                        try {
                            String ct = part.getContentType();
                            if (ct != null) {
                                jakarta.mail.internet.ContentType contentType = new jakarta.mail.internet.ContentType(ct);
                                String cs = contentType.getParameter("charset");
                                if (cs != null) charset = cs;
                            }
                        } catch (Exception e) {

                        }

                        byte[] bytes = decodedIs.readAllBytes();
                        return new String(bytes, charset);
                    }
                }
            }
        } else if (part.isMimeType("multipart/*")) {
            jakarta.mail.Multipart mp = (jakarta.mail.Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                String result = extractHtmlPart(mp.getBodyPart(i));
                if (result != null) return result;
            }
        }
        return null;
    }
}