package com.mhtextractor.output;

import com.mhtextractor.util.FileUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 文本写入工具类
 * 支持模板变量替换、章节名、UTF-8 编码写入
 */
@Slf4j
public class FileWriter {

    private static final DateTimeFormatter DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 将文本内容追加写入文件（支持自定义模板 + 章节名）
     */
    public static boolean appendTextToFile(String outputPath, String text,
                                           String sourceFileName, LocalDateTime fileTime,
                                           String startTemplate, String endTemplate,
                                           String chapterName) {
        if (text == null || text.trim().isEmpty()) {
            log.info("[跳过] 内容为空：{}", sourceFileName);
            return false;
        }

        if (startTemplate == null) startTemplate = "";
        if (endTemplate == null) endTemplate = "";

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(outputPath, true), StandardCharsets.UTF_8))) {

            if (chapterName != null && !chapterName.trim().isEmpty()) {
                writer.write(chapterName);
                writer.write("\n\n");
            }

            String startContent = processTemplate(startTemplate, sourceFileName, fileTime);
            writer.write(startContent);
            writer.write(text);
            String endContent = processTemplate(endTemplate, sourceFileName, fileTime);
            writer.write(endContent);

            log.info("[成功] 已写入：{}{}", sourceFileName,
                    chapterName != null ? " (" + chapterName + ")" : "");
            return true;

        } catch (IOException e) {
            log.error("[失败] 写入文件异常：{}", sourceFileName, e);
            return false;
        }
    }

    /**
     * 创建或清空输出文件（委托给 FileUtils）
     */
    public static boolean createOrClearFile(String outputPath) {
        boolean ok = FileUtils.createOrClearFile(outputPath);
        if (ok) {
            log.info("[初始化] 输出文件已创建：{}", outputPath);
        }
        return ok;
    }

    /**
     * 处理模板中的变量替换
     */
    private static String processTemplate(String template, String filename, LocalDateTime fileTime) {
        if (template == null || template.isEmpty()) return "";

        String result = template;
        result = result.replace("{filename}", filename != null ? filename : "");

        if (fileTime != null) {
            result = result.replace("{datetime}", fileTime.format(DATETIME_FORMATTER));
            result = result.replace("{date}", fileTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            result = result.replace("{time}", fileTime.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        } else {
            result = result.replace("{datetime}", "");
            result = result.replace("{date}", "");
            result = result.replace("{time}", "");
        }

        result = result.replace("\\n", "\n");
        return result;
    }
}
