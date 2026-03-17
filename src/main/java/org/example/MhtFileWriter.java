package org.example;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MHT 文本文件写入工具类
 * 负责将提取的文本内容追加写入到 TXT 文件中
 */
public class MhtFileWriter {
    private static final Logger logger = LoggerFactory.getLogger(MhtFileWriter.class);

    private static final DateTimeFormatter DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 默认模板
    private static final String DEFAULT_START_TEMPLATE = "\n========== START ==========\n";
    private static final String DEFAULT_END_TEMPLATE = "\n========== END ==========\n";

    /**
     * 将文本内容追加写入到指定 TXT 文件（支持自定义模板 + 章节名）
     * @param outputTxtPath 输出 TXT 文件路径
     * @param text 要写入的文本内容
     * @param sourceFileName 来源 MHT 文件名
     * @param fileTime 来源文件的修改时间（可为 null）
     * @param startTemplate 开头模板（支持 {filename} {datetime} 变量）
     * @param endTemplate 结尾模板
     * @param chapterName 章节名（如“第一章”），若为 null 则不添加
     * @return 是否写入成功
     */
    public static boolean appendTextToFile(String outputTxtPath, String text,
                                           String sourceFileName, LocalDateTime fileTime,
                                           String startTemplate, String endTemplate,
                                           String chapterName) {
        if (text == null || text.trim().isEmpty()) {
            logger.info("[跳过] 内容为空：{}" , sourceFileName);
            return false;
        }

        // 使用默认模板（如果用户未设置）
        if (startTemplate == null || startTemplate.trim().isEmpty()) {
            startTemplate = DEFAULT_START_TEMPLATE;
        }
        if (endTemplate == null || endTemplate.trim().isEmpty()) {
            endTemplate = DEFAULT_END_TEMPLATE;
        }

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(outputTxtPath, true),
                        StandardCharsets.UTF_8))) {

            //  如果提供了章节名，先写入章节名
            if (chapterName != null && !chapterName.trim().isEmpty()) {
                writer.write(chapterName);
                writer.write("\n\n"); // 章节名后加两个换行，与下方内容分隔
            }

            // 处理开头模板中的变量替换
            String startContent = processTemplate(startTemplate, sourceFileName, fileTime);
            writer.write(startContent);

            // 写入文本内容
            writer.write(text);

            // 处理结尾模板中的变量替换
            String endContent = processTemplate(endTemplate, sourceFileName, fileTime);
            writer.write(endContent);

            logger.info("[成功] 已写入：{}{}" , sourceFileName , (chapterName != null ? " (" + chapterName + ")" : ""));
            return true;

        } catch (IOException e) {
            logger.error("[失败] 写入文件异常：{}" , sourceFileName);
            logger.error("操作失败", e);
            return false;
        }
    }

    /**
     * 处理模板中的变量替换
     * @param template 模板字符串
     * @param filename 文件名
     * @param fileTime 文件时间（可为 null）
     * @return 替换后的字符串
     */
    private static String processTemplate(String template, String filename, LocalDateTime fileTime) {
        if (template == null || template.isEmpty()) {
            return "";
        }

        String result = template;

        // 替换 {filename}
        result = result.replace("{filename}", filename != null ? filename : "");

        // 替换 {datetime}
        if (fileTime != null) {
            result = result.replace("{datetime}", fileTime.format(DATETIME_FORMATTER));
        } else {
            result = result.replace("{datetime}", "");
        }

        // 替换 {date}（仅日期）
        if (fileTime != null) {
            result = result.replace("{date}", fileTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        } else {
            result = result.replace("{date}", "");
        }

        // 替换 {time}（仅时间）
        if (fileTime != null) {
            result = result.replace("{time}", fileTime.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        } else {
            result = result.replace("{time}", "");
        }

        // 支持换行符 \n
        result = result.replace("\\n", "\n");

        return result;
    }

    /**
     * 创建或清空输出文件（在批量处理前调用）
     */
    public static boolean createOrClearFile(String outputTxtPath) {
        try {
            File file = new File(outputTxtPath);
            if (file.exists()) {
                file.delete();
            }
            file.createNewFile();
            logger.info("[初始化] 输出文件已创建：{}" , outputTxtPath);
            return true;
        } catch (IOException e) {
            logger.error("[错误] 创建输出文件失败：{}" , outputTxtPath);
            logger.error("操作失败", e);
            return false;
        }
    }
}