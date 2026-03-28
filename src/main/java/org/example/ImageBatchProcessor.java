package org.example;


import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimePart;
import jakarta.mail.internet.MimeUtility;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.apache.hc.core5.util.Timeout;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 图片批量处理器，负责处理多个MHT文件的图片提取和输出
 * 支持两种处理模式：
 * 1. 流式处理模式（默认）：逐个读取MHT文件，处理后释放资源，节省内存
 * 2. 高速处理模式：预先加载所有文件到内存，速度更快但内存占用大
 * 每种模式下均支持：
 * - 单独输出模式（每个文件生成独立文件夹/ZIP/CBZ）
 * - 合并输出模式（所有文件生成单个文件夹/ZIP/CBZ）
 */
public class ImageBatchProcessor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ImageBatchProcessor.class);
    private final List<String> htmlContents;
    private final AppConfig config;
    private final String outputDir;
    private final String inputFolderName;
    private final List<String> mhtFileNames;
    private final List<String> mhtFilePaths;

    // HTTP 连接池（静态单例，全局复用）
    private static final CloseableHttpClient httpClient;
    private static final ExecutorService executor;
    // 线程池大小（根据 CPU 核心数动态调整）
    private static final int THREAD_POOL_SIZE;
    static {
        // 初始化连接池
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(50);
        connectionManager.setDefaultMaxPerRoute(10);

        // 配置请求参数
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                .setResponseTimeout(Timeout.ofSeconds(10))
                .build();

        // 创建带连接池的 HttpClient
        httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .build();

        // 根据 CPU 核心数动态调整线程池大小
        int cpuCores = Runtime.getRuntime().availableProcessors();
        THREAD_POOL_SIZE = Math.max(cpuCores * 2, 4);
        executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    }


    // 广告关键词列表（用于过滤）
    private static final List<String> AD_KEYWORDS = Arrays.asList(
            "ad", "banner", "advert", "ads", "advertisement",
            "promo", "sponsored", "popup", "click", "banner-ad"
    );

    /**
     * 构造函数
     * @param htmlContents HTML 内容列表（每个 MHT文件对应一个 HTML 字符串）
     *                     在流式模式下可为空列表
     * @param config 应用配置
     * @param outputDir 输出目录路径
     * @param inputFolderName 输入文件夹名称（用于默认命名）
     * @param mhtFileNames MHT文件名列表（不含扩展名，用于单独输出模式的默认命名）
     */
    public ImageBatchProcessor(List<String> htmlContents, AppConfig config, String outputDir,
                               String inputFolderName, List<String> mhtFileNames) {
        this.htmlContents = htmlContents;
        this.config = config;
        this.outputDir = outputDir;
        this.inputFolderName = inputFolderName;
        this.mhtFileNames = mhtFileNames;
        this.mhtFilePaths = new ArrayList<>();
    }

    /**
     * 设置 MHT文件的完整路径列表（用于直接读取 MIME 结构）
     * @param mhtFilePaths MHT文件路径列表
     */
    public void setMhtFilePaths(List<String> mhtFilePaths) {
        this.mhtFilePaths.clear();
        this.mhtFilePaths.addAll(mhtFilePaths);
    }

    /**
     * 执行图片提取和输出（入口方法）
     * 根据 config.isLegacyMemoryMode() 判断使用哪种处理模式
     */
    public void execute() throws IOException {
        // 确保输出目录存在
        Files.createDirectories(Paths.get(outputDir));

        if (config.isLegacyMemoryMode()) {
            // 高速处理模式：使用预加载的 htmlContents
            logger.info("使用高速处理模式（内存模式）");
            executeLegacyMode();
        } else {
            // 流式处理模式：逐个读取MHT文件
            logger.info("使用流式处理模式（节省内存）");
            executeStreamMode();
        }
    }

    // ==================== 高速处理模式切换 ====================

    /**
     * 模式切换入口：使用预加载到内存的 htmlContents
     */
    private void executeLegacyMode() throws IOException {
        if (config.isSingleOutputMode()) {
            // 单独输出模式：为每个 MHT文件生成独立输出
            for (int i = 0; i < htmlContents.size(); i++) {
                String mhtFileName = (mhtFileNames != null && i < mhtFileNames.size())
                        ? mhtFileNames.get(i) : null;
                String mhtFilePath = (i < mhtFilePaths.size())
                        ? mhtFilePaths.get(i) : null;
                processSingleFile(htmlContents.get(i), i, mhtFileName, mhtFilePath);
            }
        } else {
            // 合并输出模式：生成单个输出文件夹/ZIP/CBZ
            processMergedFiles();
        }
    }

    // ==================== 流式处理模式 ====================

    /**
     * 流式处理模式入口：逐个读取MHT文件，处理后释放资源
     */
    private void executeStreamMode() throws IOException {
        if (mhtFilePaths.isEmpty()) {
            throw new IOException("流式模式下 MHT 文件路径列表不能为空");
        }

        if (config.isSingleOutputMode()) {
            executeStreamSingleMode();
        } else {
            executeStreamMergedMode();
        }
    }

    /**
     * 流式处理 - 单独输出模式
     * 逐个读取 MHT 文件，解析并生成图片到对应子文件夹，
     * 处理完单个文件后立即打包（如需要）并释放资源
     */
    private void executeStreamSingleMode() throws IOException {
        logger.info("流式单独输出模式：共 {} 个文件待处理", mhtFilePaths.size());

        for (int i = 0; i < mhtFilePaths.size(); i++) {
            String mhtFilePath = mhtFilePaths.get(i);
            String mhtFileName = (mhtFileNames != null && i < mhtFileNames.size())
                    ? mhtFileNames.get(i) : null;

            logger.info("========================================");
            logger.info("流式处理文件 [{}/{}]: {}", (i + 1), mhtFilePaths.size(),
                    mhtFileName != null ? mhtFileName : mhtFilePath);

            // Step 1: 从MHT文件中提取HTML内容
            String htmlContent = null;
            try {
                htmlContent = extractHtmlFromMhtFile(mhtFilePath);
            } catch (Exception e) {
                logger.error("读取 MHT 文件失败: {} - {}", mhtFilePath, e.getMessage());
                continue;
            }

            if (htmlContent == null || htmlContent.isEmpty()) {
                logger.warn("文件 {} 的 HTML 内容为空，跳过", mhtFilePath);
                continue;
            }

            // Step 2: 使用提取的HTML处理单个文件（复用原有逻辑）
            try {
                processSingleFile(htmlContent, i, mhtFileName, mhtFilePath);
            } catch (Exception e) {
                logger.error("处理文件 {} 失败: {}", mhtFilePath, e.getMessage());
            }

            // Step 3: 释放HTML内容引用，帮助GC回收
            htmlContent = null;
            logger.info("文件 [{}/{}] 处理完毕，资源已释放", (i + 1), mhtFilePaths.size());
        }

        logger.info("========================================");
        logger.info("流式单独输出模式处理完毕，共处理 {} 个文件", mhtFilePaths.size());
    }

    /**
     * 流式处理 - 合并输出模式
     * 逐个读取 MHT 文件，提取图片直接保存到公共输出文件夹。
     * 维护全局计数器用于重命名。所有文件处理完毕后统一打包。
     */
    private void executeStreamMergedMode() throws IOException {
        logger.info("流式合并输出模式：共 {} 个文件待处理", mhtFilePaths.size());

        // 确定合并输出的文件夹名称
        String folderName = determineMergedFolderName();

        // 创建输出目录
        String outputFolder = Paths.get(outputDir, folderName).toString();
        Files.createDirectories(Paths.get(outputFolder));

        // 全局计数器，初始化为1
        AtomicInteger globalIndex = new AtomicInteger(1);
        int totalImageCount = 0;

        // 检查是否启用了区域限定
        boolean hasRegionLimit = config.getContentLimitStart() != null &&
                config.getContentLimitEnd() != null &&
                !config.getContentLimitStart().isEmpty() &&
                !config.getContentLimitEnd().isEmpty();

        for (int i = 0; i < mhtFilePaths.size(); i++) {
            String mhtFilePath = mhtFilePaths.get(i);
            String mhtFileName = (mhtFileNames != null && i < mhtFileNames.size())
                    ? mhtFileNames.get(i) : null;

            logger.info("========================================");
            logger.info("流式合并处理文件 [{}/{}]: {}", (i + 1), mhtFilePaths.size(),
                    mhtFileName != null ? mhtFileName : mhtFilePath);

            // Step 1: 从MHT文件中提取HTML内容
            String htmlContent = null;
            try {
                htmlContent = extractHtmlFromMhtFile(mhtFilePath);
            } catch (Exception e) {
                logger.error("读取 MHT 文件失败: {} - {}", mhtFilePath, e.getMessage());
                continue;
            }

            if (htmlContent == null || htmlContent.isEmpty()) {
                logger.warn("文件 {} 的 HTML 内容为空，跳过", mhtFilePath);
                continue;
            }

            // Step 2: 创建提取器并提取图片
            ImageExtractor extractor = new ImageExtractor(
                    htmlContent,
                    config.getNameLimitStart(),
                    config.getNameLimitEnd(),
                    config.getContentLimitStart(),
                    config.getContentLimitEnd()
            );

            List<ImageExtractor.ImageData> imageDataList;
            if (hasRegionLimit) {
                imageDataList = extractor.extractImagesFromMhtWithRegionFilter(mhtFilePath);
            } else {
                imageDataList = extractor.extractImagesFromMht(mhtFilePath);
            }

            if (imageDataList.isEmpty()) {
                logger.warn("[警告] 文件 {} 未找到图片，跳过", (i + 1));
                htmlContent = null;
                continue;
            }

            // Step 3: 为合并模式添加文件索引前缀（防止同名覆盖）
            if (!config.isEnableBatchRenaming() && mhtFilePaths.size() > 1) {
                final int fileIndex = i;
                for (ImageExtractor.ImageData data : imageDataList) {
                    if (data.filename != null && !data.filename.isEmpty()) {
                        data.filename = String.format("f%02d_", fileIndex + 1) + data.filename;
                    }
                }
            }

            // Step 4: 保存图片到公共输出文件夹（使用全局计数器）
            int imagesSaved = saveImagesFromDataWithGlobalIndex(
                    imageDataList, outputFolder, config.isEnableBatchRenaming(),
                    extractor.getCidImageMap(), folderName, globalIndex);

            totalImageCount += imagesSaved;

            // Step 5: 释放资源
            htmlContent = null;
            imageDataList = null;
            extractor = null;

            logger.info("文件 [{}/{}] 处理完毕，本次提取 {} 张图片，累计 {} 张",
                    (i + 1), mhtFilePaths.size(), imagesSaved, totalImageCount);
        }

        logger.info("========================================");
        logger.info("所有文件处理完毕，共提取 {} 张图片", totalImageCount);

        // 所有文件处理完毕后，统一打包
        boolean needZip = config.isEnableZipPackaging();
        boolean needCbz = config.isEnableCbzPackaging();

        if (needZip || needCbz) {
            createArchive(outputFolder, needZip, needCbz);
            deleteDirectory(new File(outputFolder));
            logger.info("已删除临时文件夹：{}", outputFolder);
        }
    }

    /**
     * 确定合并输出模式下的文件夹名称
     * 尝试从第一个文件提取标题，失败则使用输入文件夹名称
     */
    private String determineMergedFolderName() {
        String folderName;
        boolean nameExtractEnabled = config.isImageNameExtractEnabled();

        if (nameExtractEnabled && mhtFilePaths != null && !mhtFilePaths.isEmpty()) {
            // 从第一个文件提取标题
            try {
                String firstHtml = extractHtmlFromMhtFile(mhtFilePaths.get(0));
                if (firstHtml != null && !firstHtml.isEmpty()) {
                    ImageExtractor firstExtractor = new ImageExtractor(
                            firstHtml,
                            config.getNameLimitStart(),
                            config.getNameLimitEnd(),
                            config.getContentLimitStart(),
                            config.getContentLimitEnd()
                    );
                    String extractedTitle = firstExtractor.extractTitle();
                    if (extractedTitle != null && !extractedTitle.isEmpty()) {
                        return sanitizeFileName(extractedTitle);
                    }
                }
            } catch (Exception e) {
                logger.warn("从第一个文件提取标题失败: {}", e.getMessage());
            }
            // 提取失败时回退
            folderName = (inputFolderName != null && !inputFolderName.isEmpty())
                    ? sanitizeFileName(inputFolderName) : "All_Images";
            logger.warn("[警告] 未从第一个文件提取到标题，使用默认名称：{}", folderName);
        } else {
            folderName = (inputFolderName != null && !inputFolderName.isEmpty())
                    ? sanitizeFileName(inputFolderName) : "All_Images";
        }
        return folderName;
    }

    /**
     * 从 MHT 文件中提取 HTML 内容（流式模式使用）
     * @param mhtPath MHT 文件路径
     * @return HTML 内容字符串
     */
    private String extractHtmlFromMhtFile(String mhtPath) throws Exception {
        Properties props = new Properties();
        props.setProperty("mail.mime.decodetext.strict", "false");
        props.setProperty("mail.mime.charset", "UTF-8");

        Session session = Session.getInstance(props);

        try (FileInputStream fis = new FileInputStream(mhtPath)) {
            MimeMessage message = new MimeMessage(session, fis);
            return getRawHtmlFromMimeMessage(message);
        }
    }

    /**
     * 从 MimeMessage 中递归提取 HTML 部分
     */
    private String getRawHtmlFromMimeMessage(jakarta.mail.Part part) throws Exception {
        if (part.isMimeType("text/html")) {
            try (InputStream is = part.getInputStream()) {
                if (is != null) {
                    String encoding = null;
                    if (part instanceof MimePart) {
                        encoding = ((MimePart) part).getEncoding();
                    }
                    if (encoding == null) encoding = "utf-8";

                    try (InputStream decodedIs = MimeUtility.decode(is, encoding)) {
                        String charset = "UTF-8";
                        try {
                            String ct = part.getContentType();
                            if (ct != null) {
                                ContentType contentType = new ContentType(ct);
                                String cs = contentType.getParameter("charset");
                                if (cs != null) charset = cs;
                            }
                        } catch (Exception e) {
                            logger.debug("从 Part 获取 Charset 失败：{}", e.getMessage());
                        }

                        byte[] bytes = decodedIs.readAllBytes();
                        return new String(bytes, charset);
                    }
                }
            }
        } else if (part.isMimeType("multipart/*")) {
            jakarta.mail.Multipart mp = (jakarta.mail.Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                String result = getRawHtmlFromMimeMessage(mp.getBodyPart(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    /**
     * 保存图片并使用全局计数器（流式合并模式专用）
     * @param imageDataList 图片数据列表
     * @param outputFolder 输出目录
     * @param enableBatchRenaming 是否启用批量重命名
     * @param cidMap CID 到图片数据的映射
     * @param fileName 日志显示的文件名
     * @param globalIndex 全局计数器（跨文件递增）
     * @return 成功保存的图片数量
     */
    private int saveImagesFromDataWithGlobalIndex(List<ImageExtractor.ImageData> imageDataList,
                                                  String outputFolder, boolean enableBatchRenaming,
                                                  Map<String, byte[]> cidMap, String fileName,
                                                  AtomicInteger globalIndex) throws IOException {
        CountDownLatch latch = new CountDownLatch(imageDataList.size());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);
        logger.info("========================================");
        logger.info("文件：{} 开始并发保存，图片数：{}", fileName, imageDataList.size());
        long startTime = System.currentTimeMillis();

        // 预先为每张图片分配全局序号（保证顺序正确）
        int[] assignedIndices = new int[imageDataList.size()];
        for (int i = 0; i < imageDataList.size(); i++) {
            assignedIndices[i] = globalIndex.getAndIncrement();
        }

        for (int i = 0; i < imageDataList.size(); i++) {
            final int localIndex = i;
            final int globalIdx = assignedIndices[i];
            final ImageExtractor.ImageData imageData = imageDataList.get(i);

            executor.submit(() -> {
                try {
                    byte[] imageDataBytes;
                    String imageFileName;
                    String ext;

                    if (imageData.contentId != null && cidMap.containsKey(imageData.contentId)) {
                        imageDataBytes = cidMap.get(imageData.contentId);
                        imageFileName = imageData.filename;
                        ext = getExtensionFromFilename(imageFileName);
                    } else if (imageData.imageData != null) {
                        imageDataBytes = imageData.imageData;
                        imageFileName = imageData.filename;
                        ext = getExtensionFromFilename(imageFileName);
                        ext = detectActualExtension(imageDataBytes, ext);
                    } else {
                        logger.info("[跳过] 无效的图片数据");
                        skippedCount.incrementAndGet();
                        return;
                    }

                    if (imageDataBytes == null || imageDataBytes.length == 0) {
                        logger.info("[跳过] 图片数据为空");
                        skippedCount.incrementAndGet();
                        return;
                    }

                    // 生成文件名：使用全局计数器
                    String finalFileName;
                    if (enableBatchRenaming) {
                        finalFileName = String.format("%03d.%s", globalIdx, ext);
                    } else {
                        finalFileName = generateNonRenamedFileName(imageData, imageFileName, ext, localIndex);
                    }

                    File outputFile = new File(outputFolder, finalFileName);
                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        fos.write(imageDataBytes);
                    }

                    successCount.incrementAndGet();
                    logger.info(String.format("[进度 %d/%d] 已保存：%s (%.2f KB)",
                            successCount.get() + skippedCount.get(), imageDataList.size(),
                            finalFileName, imageDataBytes.length / 1024.0));

                } catch (Exception e) {
                    logger.error("[失败] 保存图片异常 - {}", e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
            long endTime = System.currentTimeMillis();
            long totalTime = endTime - startTime;

            logger.info("\n========================================");
            logger.info("保存完成统计：");
            logger.info("  本批图片数：{}", imageDataList.size());
            logger.info("  成功：{}", successCount.get());
            logger.info("  跳过：{}", skippedCount.get());
            logger.info("  耗时：{} 秒", (totalTime / 1000.0));
            logger.info("========================================");

        } catch (InterruptedException e) {
            logger.error("保存被中断");
        }

        return successCount.get();
    }

    // ==================== 高速模式方法 ====================

    /**
     * 处理单个 MHT文件（单独输出模式）
     * @param htmlContent HTML 内容
     * @param index 文件索引（用于生成默认标题）
     * @param mhtFileName MHT文件名（不含扩展名）
     * @param mhtFilePath MHT文件完整路径
     */
    private void processSingleFile(String htmlContent, int index, String mhtFileName, String mhtFilePath) throws IOException {
        // 创建提取器
        ImageExtractor extractor = new ImageExtractor(
                htmlContent,
                config.getNameLimitStart(),
                config.getNameLimitEnd(),
                config.getContentLimitStart(),
                config.getContentLimitEnd()
        );

        // 从 MHT 的 MIME 结构中提取所有图片
        List<ImageExtractor.ImageData> imageDataList;
        if (mhtFilePath != null && !mhtFilePath.isEmpty()) {
            // 检查是否启用了区域限定
            boolean hasRegionLimit = config.getContentLimitStart() != null &&
                    config.getContentLimitEnd() != null &&
                    !config.getContentLimitStart().isEmpty() &&
                    !config.getContentLimitEnd().isEmpty();

            if (hasRegionLimit) {
                imageDataList = extractor.extractImagesFromMhtWithRegionFilter(mhtFilePath);
            } else {
                imageDataList = extractor.extractImagesFromMht(mhtFilePath);
            }
        } else {
            List<String> imageUrls = extractor.extractImages();
            imageDataList = new ArrayList<>();
            for (String url : imageUrls) {
                imageDataList.add(new ImageExtractor.ImageData(null, null, null));
            }
        }

        if (imageDataList.isEmpty()) {
            logger.warn("[警告] 文件 {} 未找到图片，跳过", (index + 1));
            return;
        }

        // 确定文件夹/ZIP/CBZ 名称
        String folderName;
        boolean nameExtractEnabled = config.isImageNameExtractEnabled();

        if (nameExtractEnabled) {
            String extractedTitle = extractor.extractTitle();
            if (extractedTitle != null && !extractedTitle.isEmpty()) {
                folderName = sanitizeFileName(extractedTitle);
            } else {
                folderName = (mhtFileName != null && !mhtFileName.isEmpty())
                        ? sanitizeFileName(mhtFileName) : "Image_" + (index + 1);
                logger.warn("[警告]文件 {} 未提取到标题，使用默认名称：{}", (index + 1), folderName);
            }
        } else {
            folderName = (mhtFileName != null && !mhtFileName.isEmpty())
                    ? sanitizeFileName(mhtFileName) : "Image_" + (index + 1);
        }

        // 创建输出目录
        String outputFolder = Paths.get(outputDir, folderName).toString();
        Files.createDirectories(Paths.get(outputFolder));

        // 保存图片
        saveImagesFromData(imageDataList, outputFolder, config.isEnableBatchRenaming(), extractor.getCidImageMap(), folderName);

        // 判断是否需要打包
        boolean needZip = config.isEnableZipPackaging();
        boolean needCbz = config.isEnableCbzPackaging();

        if (needZip || needCbz) {
            createArchive(outputFolder, needZip, needCbz);
            deleteDirectory(new File(outputFolder));
            logger.info("已删除临时文件夹：{}", outputFolder);
        }
    }

    /**
     * 处理所有 MHT文件（合并输出模式 - 高速模式）
     */
    private void processMergedFiles() throws IOException {
        if (htmlContents.isEmpty()) {
            throw new IOException("没有 MHT文件可处理");
        }

        // 确定合并输出的文件夹/ZIP/CBZ 名称
        String folderName;
        boolean nameExtractEnabled = config.isImageNameExtractEnabled();

        if (nameExtractEnabled) {
            ImageExtractor firstExtractor = new ImageExtractor(
                    htmlContents.get(0),
                    config.getNameLimitStart(),
                    config.getNameLimitEnd(),
                    config.getContentLimitStart(),
                    config.getContentLimitEnd()
            );
            String extractedTitle = firstExtractor.extractTitle();
            if (extractedTitle != null && !extractedTitle.isEmpty()) {
                folderName = sanitizeFileName(extractedTitle);
            } else {
                folderName = (inputFolderName != null && !inputFolderName.isEmpty())
                        ? sanitizeFileName(inputFolderName) : "All_Images";
                logger.warn("[警告] 未从第一个文件提取到标题，使用默认名称：{}", folderName);
            }
        } else {
            folderName = (inputFolderName != null && !inputFolderName.isEmpty())
                    ? sanitizeFileName(inputFolderName) : "All_Images";
        }

        // 从所有 MHT 文件的 MIME 结构中提取图片
        List<ImageExtractor.ImageData> allImageData = new ArrayList<>();
        Map<String, byte[]> globalCidMap = new HashMap<>();
        boolean hasRegionLimit = config.getContentLimitStart() != null &&
                config.getContentLimitEnd() != null &&
                !config.getContentLimitStart().isEmpty() &&
                !config.getContentLimitEnd().isEmpty();

        for (int i = 0; i < htmlContents.size(); i++) {
            String mhtFilePath = (mhtFilePaths != null && i < mhtFilePaths.size())
                    ? mhtFilePaths.get(i) : null;

            if (mhtFilePath != null && !mhtFilePath.isEmpty()) {
                ImageExtractor extractor = new ImageExtractor(
                        htmlContents.get(i),
                        config.getNameLimitStart(),
                        config.getNameLimitEnd(),
                        config.getContentLimitStart(),
                        config.getContentLimitEnd()
                );

                List<ImageExtractor.ImageData> imageDataList;
                if (hasRegionLimit) {
                    imageDataList = extractor.extractImagesFromMhtWithRegionFilter(mhtFilePath);
                } else {
                    imageDataList = extractor.extractImagesFromMht(mhtFilePath);
                }

                final int fileIndex = i;
                for (ImageExtractor.ImageData data : imageDataList) {
                    String prefixedFilename = data.filename;
                    if (prefixedFilename != null && !prefixedFilename.isEmpty() && htmlContents.size() > 1) {
                        prefixedFilename = String.format("f%02d_", fileIndex + 1) + prefixedFilename;
                    }
                    allImageData.add(new ImageExtractor.ImageData(prefixedFilename, data.contentId, data.imageData));
                }
                globalCidMap.putAll(extractor.getCidImageMap());
            }
        }

        if (allImageData.isEmpty()) {
            logger.warn("[警告] 未找到任何图片，跳过合并输出");
            return;
        }

        // 创建输出目录
        String outputFolder = Paths.get(outputDir, folderName).toString();
        Files.createDirectories(Paths.get(outputFolder));

        // 保存图片
        saveImagesFromData(allImageData, outputFolder, config.isEnableBatchRenaming(), globalCidMap, folderName);

        // 判断是否需要打包
        boolean needZip = config.isEnableZipPackaging();
        boolean needCbz = config.isEnableCbzPackaging();

        if (needZip || needCbz) {
            createArchive(outputFolder, needZip, needCbz);
            deleteDirectory(new File(outputFolder));
            logger.info("已删除临时文件夹：{}", outputFolder);
        }
    }

    // ==================== 图片保存方法 ====================

    /**
     * 从 ImageData 列表保存图片（支持 CID 引用）
     */
    private void saveImagesFromData(List<ImageExtractor.ImageData> imageDataList,
                                    String outputFolder, boolean enableBatchRenaming,
                                    Map<String, byte[]> cidMap, String fileName) throws IOException {
        CountDownLatch latch = new CountDownLatch(imageDataList.size());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);
        logger.info("========================================");
        logger.info("文件：{} 开始并发保存，图片数：{}", fileName, imageDataList.size());
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < imageDataList.size(); i++) {
            final int index = i;
            final ImageExtractor.ImageData imageData = imageDataList.get(i);

            executor.submit(() -> {
                try {
                    byte[] imageDataBytes;
                    String imageFileName;
                    String ext;

                    // 如果有 CID，从 cidMap 中获取实际数据
                    if (imageData.contentId != null && cidMap.containsKey(imageData.contentId)) {
                        imageDataBytes = cidMap.get(imageData.contentId);
                        imageFileName = imageData.filename;
                        ext = getExtensionFromFilename(imageFileName);
                        System.out.println("[DEBUG] 从 CID 获取 - 原始文件名：" + fileName + ", CID: " + imageData.contentId + ", 扩展名：" + ext);
                    } else if (imageData.imageData != null) {
                        imageDataBytes = imageData.imageData;
                        imageFileName = imageData.filename;
                        ext = getExtensionFromFilename(imageFileName);
                        ext = detectActualExtension(imageDataBytes, ext);

                        System.out.println("[DEBUG] 从 imageData 获取 - 原始文件名：" + imageFileName + ", 扩展名：" + ext + ", 数据大小：" + imageDataBytes.length);
                    } else {
                        logger.info("[跳过] 无效的图片数据");
                        skippedCount.incrementAndGet();
                        return;
                    }

                    if (imageDataBytes == null || imageDataBytes.length == 0) {
                        logger.info("[跳过] 图片数据为空");
                        skippedCount.incrementAndGet();
                        return;
                    }

                    // 生成文件名
                    String finalFileName;
                    if (enableBatchRenaming) {
                        finalFileName = String.format("%03d.%s", index + 1, ext);
                    } else {
                        finalFileName = generateNonRenamedFileName(imageData, imageFileName, ext, index);
                    }

                    File outputFile = new File(outputFolder, finalFileName);
                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        fos.write(imageDataBytes);
                    }

                    successCount.incrementAndGet();
                    logger.info(String.format("[进度 %d/%d] 已保存：%s (%.2f KB)",
                            successCount.get() + skippedCount.get(), imageDataList.size(),
                            finalFileName, imageDataBytes.length / 1024.0));

                } catch (Exception e) {
                    logger.error("[失败] 保存图片异常 - {}", e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();

            long endTime = System.currentTimeMillis();
            long totalTime = endTime - startTime;

            logger.info("\n========================================");
            logger.info("下载完成统计：");
            logger.info("  总图片数：{}", imageDataList.size());
            logger.info("  成功：{}", successCount.get());
            logger.info("  跳过：{}", skippedCount.get());
            logger.info("  总耗时：{} 秒", (totalTime / 1000.0));
            logger.info("  平均速度：{} 张/秒", String.format("%.2f", imageDataList.size() / (totalTime / 1000.0)));
            logger.info("========================================");

        } catch (InterruptedException e) {
            logger.error("下载被中断");
        }
    }

    /**
     * 根据魔数检测实际的图片扩展名
     */
    private String detectActualExtension(byte[] imageDataBytes, String ext) {
        if (imageDataBytes == null || imageDataBytes.length < 4) {
            return ext;
        }

        // 尝试从实际数据的魔数来确定真实格式
        if (ext.equals("jpg") || ext.equals("jpeg")) {
            // PNG: 89 50 4E 47
            if (imageDataBytes[0] == (byte)0x89 &&
                    imageDataBytes[1] == (byte)0x50 &&
                    imageDataBytes[2] == (byte)0x4E &&
                    imageDataBytes[3] == (byte)0x47) {
                return "png";
            }
            // GIF: 47 49 46 38
            else if (imageDataBytes[0] == (byte)0x47 &&
                    imageDataBytes[1] == (byte)0x49 &&
                    imageDataBytes[2] == (byte)0x46 &&
                    imageDataBytes[3] == (byte)0x38) {
                return "gif";
            }
            // WebP: RIFF....WEBP
            else if (imageDataBytes.length >= 12 &&
                    imageDataBytes[0] == (byte)0x52 &&
                    imageDataBytes[1] == (byte)0x49 &&
                    imageDataBytes[2] == (byte)0x46 &&
                    imageDataBytes[3] == (byte)0x46 &&
                    imageDataBytes[8] == (byte)0x57 &&
                    imageDataBytes[9] == (byte)0x45 &&
                    imageDataBytes[10] == (byte)0x42 &&
                    imageDataBytes[11] == (byte)0x50) {
                return "webp";
            }
            // BMP: 42 4D
            else if (imageDataBytes[0] == (byte)0x42 &&
                    imageDataBytes[1] == (byte)0x4D) {
                return "bmp";
            }
            // JPEG: FF D8 FF
            else if (imageDataBytes[0] == (byte)0xFF &&
                    imageDataBytes[1] == (byte)0xD8 &&
                    imageDataBytes[2] == (byte)0xFF) {
                return "jpg";
            }
        }
        // SVG
        else if (ext.equals("svg")) {
            boolean isSvg = false;
            String startStr = new String(imageDataBytes, 0, Math.min(100, imageDataBytes.length));
            if (startStr.startsWith("<?xml") || startStr.startsWith("<svg")) {
                isSvg = true;
            }
            else if (imageDataBytes.length >= 7 &&
                    imageDataBytes[0] == (byte)0xEF &&
                    imageDataBytes[1] == (byte)0xBB &&
                    imageDataBytes[2] == (byte)0xBF &&
                    new String(imageDataBytes, 3, Math.min(10, imageDataBytes.length - 3)).trim().startsWith("<?xml")) {
                isSvg = true;
            }
            if (!isSvg) {
                return "jpg";
            }
        }
        // ICO
        else if (ext.equals("ico")) {
            if (imageDataBytes[0] == (byte)0x00 &&
                    imageDataBytes[1] == (byte)0x00 &&
                    imageDataBytes[2] == (byte)0x01 &&
                    imageDataBytes[3] == (byte)0x00) {
                return "ico";
            }
            else if (imageDataBytes[0] == (byte)0x00 &&
                    imageDataBytes[1] == (byte)0x00 &&
                    imageDataBytes[2] == (byte)0x02 &&
                    imageDataBytes[3] == (byte)0x00) {
                return "cur";
            }
            else {
                return "jpg";
            }
        }
        // AVIF
        else if (ext.equals("avif")) {
            if (imageDataBytes.length >= 12 &&
                    imageDataBytes[4] == (byte)0x66 &&
                    imageDataBytes[5] == (byte)0x74 &&
                    imageDataBytes[6] == (byte)0x79 &&
                    imageDataBytes[7] == (byte)0x70 &&
                    ((imageDataBytes[8] == (byte)0x61 &&
                            imageDataBytes[9] == (byte)0x76 &&
                            imageDataBytes[10] == (byte)0x69 &&
                            imageDataBytes[11] == (byte)0x66) ||
                            (imageDataBytes[8] == (byte)0x61 &&
                                    imageDataBytes[9] == (byte)0x76 &&
                                    imageDataBytes[10] == (byte)0x69 &&
                                    imageDataBytes[11] == (byte)0x73))) {
                return "avif";
            }
            else {
                return "jpg";
            }
        }

        return ext;
    }

    /**
     * 生成非重命名模式下的文件名（公共方法，避免代码重复）
     */
    private String generateNonRenamedFileName(ImageExtractor.ImageData imageData,
                                              String imageFileName, String ext, int index) {
        if (imageData.contentId != null && !imageData.contentId.isEmpty()) {
            String cidName = sanitizeFileName(imageData.contentId.replace("<", "").replace(">", ""));
            String finalFileName = cidName + "." + ext;
            System.out.println("[DEBUG] 使用 CID 作为文件名：" + finalFileName);
            return finalFileName;
        } else if (imageFileName != null && !imageFileName.isEmpty()) {
            String baseName = sanitizeFileName(imageFileName);
            int dotIndex = baseName.lastIndexOf('.');
            if (dotIndex != -1 && dotIndex < baseName.length() - 1) {
                String existingExt = baseName.substring(dotIndex + 1).toLowerCase();
                if (Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico", "avif").contains(existingExt)) {
                    baseName = baseName.substring(0, dotIndex);
                    System.out.println("[DEBUG] 去除 MIME 文件名的原有扩展名：" + imageFileName + " -> " + baseName);
                }
            }
            String finalFileName = baseName + "." + ext;
            System.out.println("[DEBUG] 使用 MIME 文件名：" + finalFileName);
            return finalFileName;
        } else {
            String finalFileName = "image_" + index + "." + ext;
            System.out.println("[DEBUG] 使用默认序号名：" + finalFileName);
            return finalFileName;
        }
    }

    /**
     * 保存图片到指定目录（多线程并发版本）
     */
    private void saveImages(List<String> images, String outputFolder, boolean enableBatchRenaming) throws IOException {
        CountDownLatch latch = new CountDownLatch(images.size());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);
        logger.info("========================================");
        logger.info("开始并发下载，线程数：{}", THREAD_POOL_SIZE);
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < images.size(); i++) {
            final int index = i;
            final String imageUrl = images.get(i);

            executor.submit(() -> {
                try {
                    byte[] imageData = downloadOrDecodeImageData(imageUrl);

                    if (imageData == null || imageData.length == 0) {
                        logger.info("[跳过] {}", imageUrl);
                        skippedCount.incrementAndGet();
                        return;
                    }

                    String ext = getExtensionFromUrl(imageUrl);
                    String fileName = enableBatchRenaming ?
                            String.format("%03d.%s", index + 1, ext) :
                            sanitizeFileName(getFileNameFromUrl(imageUrl)) + "." + ext;

                    File outputFile = new File(outputFolder, fileName);
                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        fos.write(imageData);
                    }

                    successCount.incrementAndGet();
                    logger.info(String.format("[进度 %d/%d] 已保存：%s (%.2f KB)",
                            successCount.get() + skippedCount.get(), images.size(),
                            fileName, imageData.length / 1024.0));

                } catch (Exception e) {
                    logger.error("[失败] {} - {}", imageUrl, e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();

            long endTime = System.currentTimeMillis();
            long totalTime = endTime - startTime;

            logger.info("\n========================================");
            logger.info("下载完成统计：");
            logger.info("  总图片数：{}", images.size());
            logger.info("  成功：{}", successCount.get());
            logger.info("  跳过：{}", skippedCount.get());
            logger.info("  总耗时：{} 秒", (totalTime / 1000.0));
            logger.info("  平均速度：{} 张/秒", String.format("%.2f", images.size() / (totalTime / 1000.0)));
            logger.info("========================================");

        } catch (InterruptedException e) {
            logger.error("下载被中断");
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 从文件名获取扩展名
     */
    private String getExtensionFromFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "jpg";
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex != -1 && dotIndex < filename.length() - 1) {
            String ext = filename.substring(dotIndex + 1).toLowerCase();
            if (Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico","avif").contains(ext)) {
                return ext;
            }
        }
        return "jpg";
    }

    /**
     * 判断是否为广告图片
     */
    private boolean isAdImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return false;
        }
        String lowerUrl = imageUrl.toLowerCase();
        return AD_KEYWORDS.stream().anyMatch(lowerUrl::contains);
    }

    /**
     * 解码 Data URI，返回原始字节数组
     */
    private byte[] downloadOrDecodeImageData(String imageUrl) throws IOException {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return null;
        }

        if (imageUrl.startsWith("data:")) {
            return decodeDataUriToBytes(imageUrl);
        }

        if (imageUrl.startsWith("cid:") || imageUrl.startsWith("file:")) {
            logger.info("跳过内部资源引用：{}", imageUrl);
            return null;
        }

        try {
            File imageFile = new File(imageUrl);
            if (imageFile.exists() && imageFile.isFile()) {
                return java.nio.file.Files.readAllBytes(imageFile.toPath());
            }
            logger.warn("[安全限制] 不支持网络图片下载，跳过：{}", imageUrl);
            return null;
        } catch (Exception e) {
            logger.error("处理图片失败：{} - {}", imageUrl, e.getMessage());
            return null;
        }
    }

    /**
     * 解码 Data URI scheme 图片并返回字节数组
     */
    private byte[] decodeDataUriToBytes(String dataUri) throws IOException {
        try {
            String content = dataUri.substring(5);
            int semicolonIndex = content.indexOf(';');
            if (semicolonIndex == -1) {
                logger.error("无效的 Data URI 格式：{}", dataUri);
                return null;
            }
            int commaIndex = content.indexOf(',');
            if (commaIndex == -1) {
                logger.error("无效的 Data URI 格式：{}", dataUri);
                return null;
            }
            String base64Data = content.substring(commaIndex + 1);
            return Base64.getDecoder().decode(base64Data.trim());
        } catch (Exception e) {
            logger.error("解码 Data URI 失败：{} - {}", dataUri, e.getMessage());
            return null;
        }
    }

    /**
     * 获取URL中的文件扩展名
     */
    private String getExtensionFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "jpg";
        }

        if (url.startsWith("data:")) {
            String mimeType = url.substring(5, url.indexOf(';'));
            return switch (mimeType.toLowerCase()) {
                case "image/png" -> "png";
                case "image/gif" -> "gif";
                case "image/bmp" -> "bmp";
                case "image/webp" -> "webp";
                default -> "jpg";
            };
        }

        String ext = "jpg";
        String cleanUrl = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        int dotIndex = cleanUrl.lastIndexOf('.');
        if (dotIndex != -1 && dotIndex < cleanUrl.length() - 1) {
            ext = cleanUrl.substring(dotIndex + 1).toLowerCase();
        }
        if (!Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp").contains(ext)) {
            ext = "jpg";
        }
        return ext;
    }

    /**
     * 从URL中获取文件名（不含扩展名）
     */
    private String getFileNameFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "image";
        }
        if (url.startsWith("data:")) {
            return "image";
        }
        String cleanUrl = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        String fileName = cleanUrl.substring(cleanUrl.lastIndexOf('/') + 1);
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex != -1) {
            fileName = fileName.substring(0, dotIndex);
        }
        return fileName.isEmpty() ? "image" : fileName;
    }

    /**
     * 清理文件名（移除非法字符）
     */
    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "unnamed";
        }
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("_+", "_")
                .trim();
    }

    /**
     * 根据配置创建归档文件（ZIP 和/或 CBZ）
     */
    private void createArchive(String folderPath, boolean needZip, boolean needCbz) throws IOException {
        if (needCbz && needZip) {
            createZipArchive(folderPath, ".cbz");
            createZipArchive(folderPath, ".zip");
        } else if (needCbz) {
            createZipArchive(folderPath, ".cbz");
        } else if (needZip) {
            createZipArchive(folderPath, ".zip");
        }
    }

    /**
     * 创建ZIP/CBZ压缩文件
     */
    private void createZipArchive(String folderPath, String extension) throws IOException {
        String archivePath = folderPath + extension;
        try (FileOutputStream fos = new FileOutputStream(archivePath);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            File folder = new File(folderPath);
            File[] files = folder.listFiles();
            if (files == null) return;

            for (File file : files) {
                if (file.isDirectory()) continue;

                try (FileInputStream fis = new FileInputStream(file)) {
                    ZipEntry zipEntry = new ZipEntry(file.getName());
                    zos.putNextEntry(zipEntry);

                    byte[] bytes = new byte[1024];
                    int length;
                    while ((length = fis.read(bytes)) >= 0) {
                        zos.write(bytes, 0, length);
                    }
                    zos.closeEntry();
                }
            }
        }
        logger.info("已创建{}文件: {}", extension.toUpperCase().substring(1), archivePath);
    }

    /**
     * 递归删除目录及其所有内容
     */
    private void deleteDirectory(File directory) {
        if (directory == null || !directory.exists()) return;

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        directory.delete();
    }

    /**
     * 关闭 HTTP 连接池和线程池（程序退出时调用）
     */
    public static void shutdownResources() {
        try {
            if (httpClient != null) {
                httpClient.close();
                logger.info("HTTP 连接池已关闭");
            }
            if (executor != null && !executor.isShutdown()) {
                executor.shutdown();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    logger.info("线程池已强制关闭");
                } else {
                    logger.info("线程池已正常关闭");
                }
            }
        } catch (IOException | InterruptedException e) {
            logger.error("关闭资源失败", e);
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        shutdownResources();
    }
}