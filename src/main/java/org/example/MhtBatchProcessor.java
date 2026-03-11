package org.example;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MHT 批量处理工具类
 * 负责扫描文件夹、按时间排序、批量提取文本并写入 TXT
 */
public class MhtBatchProcessor {

    // 默认模板
    public static final String DEFAULT_START_TEMPLATE = "========== START ==========";
    public static final String DEFAULT_END_TEMPLATE = "========== END ==========";

    // 处理统计信息
    public static class ProcessStats {
        public int totalFiles = 0;
        public int successCount = 0;
        public int failCount = 0;
        public int skipCount = 0;
        public long startTime;
        public long endTime;
        public String startTemplate;
        public String endTemplate;

        @Override
        public String toString() {
            return String.format(
                    "处理完成 - 总计：%d, 成功：%d, 失败：%d, 跳过：%d, 耗时：%.2f 秒",
                    totalFiles, successCount, failCount, skipCount,
                    (endTime - startTime) / 1000.0
            );
        }
    }

    // ==================== 合并模式（所有文件输出到一个 TXT） ====================

    /**
     * 处理文件夹下所有 MHT 文件（合并模式）- 旧版本，保持兼容
     */
    public static ProcessStats processFolder(String inputFolder, String outputTxtPath) {
        return processFolder(inputFolder, outputTxtPath, true, null, null, false,
                DEFAULT_START_TEMPLATE, DEFAULT_END_TEMPLATE, false, false, null, null, false);
    }

    /**
     * 处理文件夹下所有 MHT 文件（合并模式）- 旧版本，保持兼容
     */
    public static ProcessStats processFolder(String inputFolder, String outputTxtPath,
                                             boolean autoFixEncoding) {
        return processFolder(inputFolder, outputTxtPath, autoFixEncoding, null, null, false,
                DEFAULT_START_TEMPLATE, DEFAULT_END_TEMPLATE, false, false, null, null, false);
    }

    /**
     * 处理文件夹下所有 MHT 文件（合并模式）- 支持内容区域限定
     */
    public static ProcessStats processFolder(String inputFolder, String outputTxtPath,
                                             boolean autoFixEncoding,
                                             String startMarker, String endMarker, boolean useRegex) {
        return processFolder(inputFolder, outputTxtPath, autoFixEncoding, startMarker, endMarker, useRegex,
                DEFAULT_START_TEMPLATE, DEFAULT_END_TEMPLATE, false, false, null, null, false);
    }

    /**
     * 处理文件夹下所有 MHT 文件（合并模式）- 支持内容区域限定 + 自定义开头结尾模板
     * 保持兼容，默认不添加章节名
     */
    public static ProcessStats processFolder(String inputFolder, String outputTxtPath,
                                             boolean autoFixEncoding,
                                             String startMarker, String endMarker, boolean useRegex,
                                             String startTemplate, String endTemplate) {
        return processFolder(inputFolder, outputTxtPath, autoFixEncoding, startMarker, endMarker, useRegex,
                startTemplate, endTemplate, false, false, null, null, false);
    }

    /**
     * 处理文件夹下所有 MHT 文件（合并模式）- 支持内容区域限定 + 自定义开头结尾模板 + 自动添加章节名
     * 保持兼容，默认不提取隐藏标题
     */
    public static ProcessStats processFolder(String inputFolder, String outputTxtPath,
                                             boolean autoFixEncoding,
                                             String startMarker, String endMarker, boolean useRegex,
                                             String startTemplate, String endTemplate,
                                             boolean addChapterNumber) {
        return processFolder(inputFolder, outputTxtPath, autoFixEncoding, startMarker, endMarker, useRegex,
                startTemplate, endTemplate, addChapterNumber, false, null, null, false);
    }

    /**
     * 处理文件夹下所有 MHT 文件（合并模式）- 终极完整版
     * 支持内容限定 + 自定义模板 + 自动章节名 + GUI 配置的隐藏标题提取
     *
     * @param addChapterNumber 是否添加自动序号（如“第一章”）
     * @param enableTitleExtract 是否启用隐藏标题提取
     * @param titleStartMarker 隐藏标题起始标记
     * @param titleEndMarker 隐藏标题结束标记
     * @param titleUseRegex 标题标记是否使用正则
     */
    public static ProcessStats processFolder(String inputFolder, String outputTxtPath,
                                             boolean autoFixEncoding,
                                             String startMarker, String endMarker, boolean useRegex,
                                             String startTemplate, String endTemplate,
                                             boolean addChapterNumber,
                                             boolean enableTitleExtract,
                                             String titleStartMarker,
                                             String titleEndMarker,
                                             boolean titleUseRegex) {
        ProcessStats stats = new ProcessStats();
        stats.startTime = System.currentTimeMillis();
        stats.startTemplate = startTemplate;
        stats.endTemplate = endTemplate;

        System.out.println("========================================");
        System.out.println("MHT 批量处理开始 (GUI 完整版)");
        System.out.println("输入文件夹：" + inputFolder);
        System.out.println("输出文件：" + outputTxtPath);

        if (addChapterNumber) {
            System.out.println("自动章节名：已启用");
        }
        if (enableTitleExtract) {
            System.out.println("隐藏标题提取：已启用");
            System.out.println("  起始标记：" + titleStartMarker);
            System.out.println("  结束标记：" + titleEndMarker);
        }

        System.out.println("========================================");

        if (!MhtFileWriter.createOrClearFile(outputTxtPath)) {
            return stats;
        }

        List<MhtFileInfo> mhtFiles = collectMhtFiles(inputFolder);
        stats.totalFiles = mhtFiles.size();

        if (mhtFiles.isEmpty()) {
            System.out.println("未找到任何 MHT 文件");
            stats.endTime = System.currentTimeMillis();
            return stats;
        }

        System.out.println("找到 " + mhtFiles.size() + " 个 MHT 文件");
        mhtFiles.sort(Comparator.comparing(MhtFileInfo::getModifiedTime));
        System.out.println("文件已按修改时间排序");
        System.out.println("========================================");

        int chapterIndex = 1;

        for (MhtFileInfo fileInfo : mhtFiles) {
            try {
                // 1. 提取正文
                String text = MhtToHtmlExtractor.extractTextFromMht(
                        fileInfo.getPath(), autoFixEncoding, startMarker, endMarker, useRegex);

                if (text == null || text.trim().isEmpty()) {
                    stats.skipCount++;
                    System.out.println("[跳过] 内容为空：" + fileInfo.getName());
                    continue;
                }

                // 2. [核心修改] 生成最终显示的章节头
                String finalChapterHeader = null;
                String extractedTitle = null;

                // 步骤 A: 如果需要，提取隐藏标题 (独立于序号逻辑)
                if (enableTitleExtract && titleStartMarker != null && !titleStartMarker.isEmpty()) {
                    extractedTitle = MhtToHtmlExtractor.extractChapterTitle(
                            fileInfo.getPath(), titleStartMarker, titleEndMarker, titleUseRegex);

                    if (extractedTitle != null && !extractedTitle.isEmpty()) {
                        System.out.println("[提取标题] " + fileInfo.getName() + " -> " + extractedTitle);
                    }
                }

                // 步骤 B: 如果需要，生成自动序号
                if (addChapterNumber) {
                    String numberPart = ChapterNumberGenerator.generateChapterName(chapterIndex - 1);

                    if (extractedTitle != null && !extractedTitle.isEmpty()) {
                        // 情况 1: 有序号 + 有标题 -> "第一章 眉娘浪语"
                        finalChapterHeader = numberPart + " " + extractedTitle;
                    } else {
                        // 情况 2: 有序号 + 无标题 -> "第一章"
                        finalChapterHeader = numberPart;
                    }
                    chapterIndex++; // 只有启用了序号，计数器才增加
                } else {
                    // 步骤 C: 如果没有序号，但提取到了标题
                    if (extractedTitle != null && !extractedTitle.isEmpty()) {
                        // 情况 3: 无序号 + 有标题 -> "眉娘浪语" (这就是你需要的功能!)
                        finalChapterHeader = extractedTitle;
                    }
                    // 情况 4: 无序号 + 无标题 -> finalChapterHeader 保持 null
                }

                // 3. 调用写入方法
                boolean writeSuccess = MhtFileWriter.appendTextToFile(
                        outputTxtPath, text, fileInfo.getName(), fileInfo.getModifiedTime(),
                        startTemplate, endTemplate, finalChapterHeader);

                if (writeSuccess) {
                    stats.successCount++;
                } else {
                    stats.failCount++;
                }

            } catch (Exception e) {
                stats.failCount++;
                System.err.println("[异常] 处理文件失败：" + fileInfo.getName());
                e.printStackTrace();
            }
        }

        stats.endTime = System.currentTimeMillis();
        System.out.println("========================================");
        System.out.println(stats);
        System.out.println("========================================");

        return stats;
    }

    // ==================== 单独输出模式（每个文件输出到独立 TXT） ====================

    public static ProcessStats processFolderSeparate(String inputFolder, String outputFolder, boolean autoFixEncoding) {
        return processFolderSeparate(inputFolder, outputFolder, autoFixEncoding, null, null, false,
                DEFAULT_START_TEMPLATE, DEFAULT_END_TEMPLATE);
    }

    public static ProcessStats processFolderSeparate(String inputFolder, boolean autoFixEncoding) {
        return processFolderSeparate(inputFolder, inputFolder, autoFixEncoding, null, null, false,
                DEFAULT_START_TEMPLATE, DEFAULT_END_TEMPLATE);
    }

    public static ProcessStats processFolderSeparate(String inputFolder, String outputFolder,
                                                     boolean autoFixEncoding,
                                                     String startMarker, String endMarker, boolean useRegex) {
        return processFolderSeparate(inputFolder, outputFolder, autoFixEncoding, startMarker, endMarker, useRegex,
                DEFAULT_START_TEMPLATE, DEFAULT_END_TEMPLATE);
    }

    public static ProcessStats processFolderSeparate(String inputFolder,
                                                     boolean autoFixEncoding,
                                                     String startMarker, String endMarker, boolean useRegex) {
        return processFolderSeparate(inputFolder, inputFolder, autoFixEncoding, startMarker, endMarker, useRegex,
                DEFAULT_START_TEMPLATE, DEFAULT_END_TEMPLATE);
    }

    public static ProcessStats processFolderSeparate(String inputFolder, String outputFolder,
                                                     boolean autoFixEncoding,
                                                     String startMarker, String endMarker, boolean useRegex,
                                                     String startTemplate, String endTemplate) {
        ProcessStats stats = new ProcessStats();
        stats.startTime = System.currentTimeMillis();
        stats.startTemplate = startTemplate;
        stats.endTemplate = endTemplate;

        System.out.println("========================================");
        System.out.println("MHT 批量处理开始（单独输出模式）");
        System.out.println("输入文件夹：" + inputFolder);
        System.out.println("输出文件夹：" + outputFolder);
        System.out.println("========================================");

        File outDir = new File(outputFolder);
        if (!outDir.exists() || !outDir.isDirectory()) {
            System.err.println("输出文件夹不存在或不是有效目录：" + outputFolder);
            stats.endTime = System.currentTimeMillis();
            return stats;
        }

        List<MhtFileInfo> mhtFiles = collectMhtFiles(inputFolder);
        stats.totalFiles = mhtFiles.size();

        if (mhtFiles.isEmpty()) {
            System.out.println("未找到任何 MHT 文件");
            stats.endTime = System.currentTimeMillis();
            return stats;
        }

        System.out.println("找到 " + mhtFiles.size() + " 个 MHT 文件");
        mhtFiles.sort(Comparator.comparing(MhtFileInfo::getModifiedTime));

        for (MhtFileInfo fileInfo : mhtFiles) {
            try {
                String text = MhtToHtmlExtractor.extractTextFromMht(
                        fileInfo.getPath(), autoFixEncoding, startMarker, endMarker, useRegex);

                if (text == null || text.trim().isEmpty()) {
                    stats.skipCount++;
                    System.out.println("[跳过] 内容为空：" + fileInfo.getName());
                    continue;
                }

                String mhtName = fileInfo.getName();
                String txtName = mhtName;
                if (txtName.toLowerCase().endsWith(".mht") || txtName.toLowerCase().endsWith(".mhtml")) {
                    txtName = txtName.substring(0, txtName.lastIndexOf("."));
                }
                txtName = txtName + ".txt";

                String outputPath = outputFolder + "\\" + txtName;

                boolean writeSuccess = MhtFileWriter.appendTextToFile(
                        outputPath, text, fileInfo.getName(), null,
                        startTemplate, endTemplate, null);

                if (writeSuccess) {
                    stats.successCount++;
                    System.out.println("[成功] " + fileInfo.getName() + " → " + outputPath);
                } else {
                    stats.failCount++;
                }

            } catch (Exception e) {
                stats.failCount++;
                System.err.println("[异常] 处理文件失败：" + fileInfo.getName());
                e.printStackTrace();
            }
        }

        stats.endTime = System.currentTimeMillis();
        System.out.println("========================================");
        System.out.println(stats);
        System.out.println("========================================");

        return stats;
    }

    public static ProcessStats processFolderSeparate(String inputFolder,
                                                     boolean autoFixEncoding,
                                                     String startMarker, String endMarker, boolean useRegex,
                                                     String startTemplate, String endTemplate) {
        return processFolderSeparate(inputFolder, inputFolder, autoFixEncoding, startMarker, endMarker, useRegex,
                startTemplate, endTemplate);
    }

    // ==================== 内部辅助方法 ====================

    private static List<MhtFileInfo> collectMhtFiles(String folderPath) {
        List<MhtFileInfo> files = new ArrayList<>();
        File folder = new File(folderPath);

        if (!folder.exists() || !folder.isDirectory()) {
            System.err.println("文件夹不存在或不是目录：" + folderPath);
            return files;
        }

        File[] mhtFiles = folder.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".mht") || name.toLowerCase().endsWith(".mhtml"));

        if (mhtFiles == null || mhtFiles.length == 0) {
            return files;
        }

        for (File file : mhtFiles) {
            try {
                LocalDateTime modifiedTime = getModifiedTime(file);
                files.add(new MhtFileInfo(
                        file.getAbsolutePath(),
                        file.getName(),
                        modifiedTime
                ));
            } catch (IOException e) {
                System.err.println("无法读取文件信息：" + file.getName());
            }
        }

        return files;
    }

    private static LocalDateTime getModifiedTime(File file) throws IOException {
        Path path = Paths.get(file.getAbsolutePath());
        return Files.getLastModifiedTime(path)
                .toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    public static class MhtFileInfo {
        private final String path;
        private final String name;
        private final LocalDateTime modifiedTime;

        public MhtFileInfo(String path, String name, LocalDateTime modifiedTime) {
            this.path = path;
            this.name = name;
            this.modifiedTime = modifiedTime;
        }

        public String getPath() { return path; }
        public String getName() { return name; }
        public LocalDateTime getModifiedTime() { return modifiedTime; }
    }
}