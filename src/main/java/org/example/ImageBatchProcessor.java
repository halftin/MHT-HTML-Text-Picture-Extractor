package org.example;


import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.apache.hc.core5.util.Timeout;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 图片批量处理器，负责处理多个MHT文件的图片提取和输出
 * 支持单独输出模式（每个文件生成独立文件夹/ZIP）和合并输出模式（所有文件生成单个文件夹/ZIP）
 */
public class ImageBatchProcessor {
    private static final Logger logger = LoggerFactory.getLogger(ImageBatchProcessor.class);
    private final List<String> htmlContents;
    private final AppConfig config;
    private final String outputDir;
    private final String inputFolderName;
    private final List<String> mhtFileNames;

    // HTTP 连接池（静态单例，全局复用）
    private static final CloseableHttpClient httpClient;
    private static final ExecutorService executor;

    static {
        // 初始化连接池
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(50);
        connectionManager.setDefaultMaxPerRoute(10);

        // 配置请求参数
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(5))
                .setResponseTimeout(Timeout.ofSeconds(10))
                .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                .build();

        // 创建带连接池的 HttpClient
        httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .build();

        // 创建线程池（10 个并发线程）
        executor = Executors.newFixedThreadPool(10);
    }

    // 广告关键词列表（用于过滤）
    private static final List<String> AD_KEYWORDS = Arrays.asList(
            "ad", "banner", "advert", "ads", "advertisement",
            "promo", "sponsored", "popup", "click", "banner-ad"
    );

    /**
     * 构造函数
     * @param htmlContents HTML 内容列表（每个 MHT 文件对应一个 HTML 字符串）
     * @param config 应用配置
     * @param outputDir 输出目录路径
     * @param inputFolderName 输入文件夹名称（用于默认命名）
     * @param mhtFileNames MHT 文件名列表（不含扩展名，用于单独输出模式的默认命名）
     */
    public ImageBatchProcessor(List<String> htmlContents, AppConfig config, String outputDir,
                               String inputFolderName, List<String> mhtFileNames) {
        this.htmlContents = htmlContents;
        this.config = config;
        this.outputDir = outputDir;
        this.inputFolderName = inputFolderName;
        this.mhtFileNames = mhtFileNames;
    }

    /**
     * 执行图片提取和输出
     */
    public void execute() throws IOException {
        // 确保输出目录存在
        Files.createDirectories(Paths.get(outputDir));

        if (config.isSingleOutputMode()) {
            // 单独输出模式：为每个 MHT 文件生成独立输出
            for (int i = 0; i < htmlContents.size(); i++) {
                String mhtFileName = (mhtFileNames != null && i < mhtFileNames.size())
                        ? mhtFileNames.get(i) : null;
                processSingleFile(htmlContents.get(i), i, mhtFileName);
            }
        } else {
            // 合并输出模式：生成单个输出文件夹/ZIP
            processMergedFiles();
        }
    }

    /**
     * 处理单个 MHT 文件（单独输出模式）
     * @param htmlContent HTML 内容
     * @param index 文件索引（用于生成默认标题）
     * @param mhtFileName MHT 文件名（不含扩展名）
     */
    private void processSingleFile(String htmlContent, int index, String mhtFileName) throws IOException {

        // 创建提取器
        ImageExtractor extractor = new ImageExtractor(
                htmlContent,
                config.getNameLimitStart(),
                config.getNameLimitEnd(),
                config.getContentLimitStart(),
                config.getContentLimitEnd()
        );

        // 提取图片
        List<String> images = extractor.extractImages();
        if (images.isEmpty()) {
            logger.info("警告：文件 {} 未找到图片，跳过", (index + 1));
            return;
        }

        // 确定文件夹/ZIP 名称
        String folderName;
        boolean nameExtractEnabled = config.isImageNameExtractEnabled();

        if (nameExtractEnabled) {
            // 勾选了"启用名称提取限定"：从 HTML 中提取标题作为名称
            String extractedTitle = extractor.extractTitle();
            if (extractedTitle != null && !extractedTitle.isEmpty()) {
                folderName = sanitizeFileName(extractedTitle);
            } else {
                // 提取失败时回退到 MHT 文件名
                folderName = (mhtFileName != null && !mhtFileName.isEmpty())
                        ? sanitizeFileName(mhtFileName) : "Image_" + (index + 1);
                logger.info("文件 {} 未提取到标题，使用默认名称：{}", (index + 1), folderName);
            }
        } else {
            // 未勾选"启用名称提取限定"：使用 MHT 文件名作为名称
            folderName = (mhtFileName != null && !mhtFileName.isEmpty())
                    ? sanitizeFileName(mhtFileName) : "Image_" + (index + 1);
        }

        // 创建输出目录
        String outputFolder = Paths.get(outputDir, folderName).toString();
        Files.createDirectories(Paths.get(outputFolder));

        // 保存图片
        saveImages(images, outputFolder, config.isEnableBatchRenaming());

        // 如果启用 ZIP 打包，打包后删除文件夹
        if (config.isEnableZipPackaging()) {
            createZipArchive(outputFolder);
            deleteDirectory(new File(outputFolder));
            logger.info("已删除临时文件夹：{}", outputFolder);
        }
    }

    /**
     * 处理所有 MHT 文件（合并输出模式）
     */
    private void processMergedFiles() throws IOException {
        if (htmlContents.isEmpty()) {
            throw new IOException("没有 MHT 文件可处理");
        }

        // 确定合并输出的文件夹/ZIP 名称
        String folderName;
        boolean nameExtractEnabled = config.isImageNameExtractEnabled();

        if (nameExtractEnabled) {
            // 勾选了"启用名称提取限定"：从第一个文件的 HTML 中提取标题作为名称
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
                // 提取失败时回退到输入文件夹名称
                folderName = (inputFolderName != null && !inputFolderName.isEmpty())
                        ? sanitizeFileName(inputFolderName) : "All_Images";
                logger.info("未从第一个文件提取到标题，使用默认名称：{}", folderName);
            }
        } else {
            // 未勾选"启用名称提取限定"：使用输入文件夹名称
            folderName = (inputFolderName != null && !inputFolderName.isEmpty())
                    ? sanitizeFileName(inputFolderName) : "All_Images";
        }

        // 提取所有图片
        List<String> allImages = new ArrayList<>();
        for (String html : htmlContents) {
            ImageExtractor extractor = new ImageExtractor(
                    html,
                    config.getNameLimitStart(),
                    config.getNameLimitEnd(),
                    config.getContentLimitStart(),
                    config.getContentLimitEnd()
            );
            allImages.addAll(extractor.extractImages());
        }

        if (allImages.isEmpty()) {
            logger.info("警告：未找到任何图片，跳过合并输出");
            return;
        }

        // 创建输出目录
        String outputFolder = Paths.get(outputDir, folderName).toString();
        Files.createDirectories(Paths.get(outputFolder));

        // 保存图片
        saveImages(allImages, outputFolder, config.isEnableBatchRenaming());

        // 如果启用 ZIP 打包，打包后删除文件夹
        if (config.isEnableZipPackaging()) {
            createZipArchive(outputFolder);
            deleteDirectory(new File(outputFolder));
            logger.info("已删除临时文件夹：{}", outputFolder);
        }
    }

    /**
     * 保存图片到指定目录（多线程并发版本）
     * @param images 图片 URL 列表
     * @param outputFolder 输出目录
     * @param enableBatchRenaming 是否启用批量重命名
     */
    private void saveImages(List<String> images, String outputFolder, boolean enableBatchRenaming) throws IOException {
        CountDownLatch latch = new CountDownLatch(images.size());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);

        logger.info("开始并发下载，线程数：10");
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

    /**
     * 判断是否为广告图片
     * @param imageUrl 图片 URL
     * @return true 如果是广告图片
     */
    private boolean isAdImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return false;
        }

        String lowerUrl = imageUrl.toLowerCase();
        return AD_KEYWORDS.stream().anyMatch(lowerUrl::contains);
    }

    /**
     * 下载图片或解码 Data URI，返回原始字节数组
     * 支持：
     * 1. Data URI scheme (data:image/jpeg;base64,...)
     * 2. 相对路径或 file:// 协议
     * 3. HTTP/HTTPS 链接
     */
    private byte[] downloadOrDecodeImageData(String imageUrl) throws IOException {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return null;
        }

        // 1. 处理 Data URI scheme (base64 编码的图片)
        if (imageUrl.startsWith("data:")) {
            return decodeDataUriToBytes(imageUrl);
        }

        // 2. 跳过 cid: 开头的内部资源引用（这些已经在 MHT 解析时处理过了）
        if (imageUrl.startsWith("cid:") || imageUrl.startsWith("file:")) {
            logger.info("跳过内部资源引用：{}", imageUrl);
            return null;
        }

        // 3. 尝试作为普通路径处理（相对路径或绝对路径）
        try {
            // 如果是本地文件路径，直接读取
            File imageFile = new File(imageUrl);
            if (imageFile.exists() && imageFile.isFile()) {
                return java.nio.file.Files.readAllBytes(imageFile.toPath());
            }

            // 否则尝试作为 URL 下载
            return downloadImageToBytes(imageUrl);
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
            // 移除 "data:" 前缀
            String content = dataUri.substring(5);

            // 找到分号位置，分离 MIME 类型和 base64 数据
            int semicolonIndex = content.indexOf(';');
            if (semicolonIndex == -1) {
                logger.error("无效的 Data URI 格式：{}", dataUri);
                return null;
            }

            // 获取 base64 部分（跳过 "base64," 前缀）
            int commaIndex = content.indexOf(',');
            if (commaIndex == -1) {
                logger.error("无效的 Data URI 格式：{}", dataUri);
                return null;
            }

            String base64Data = content.substring(commaIndex + 1);

            // 解码 base64
            return Base64.getDecoder().decode(base64Data.trim());
        } catch (Exception e) {
            logger.error("解码 Data URI 失败：{} - {}", dataUri, e.getMessage());
            return null;
        }
    }

    /**
     * 使用 HttpClient 下载图片并返回字节数组
     */
    private byte[] downloadImageToBytes(String imageUrl) throws IOException {
        HttpGet request = new HttpGet(imageUrl);

        // 设置请求头，模拟浏览器
        request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        request.setHeader("Accept", "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
        request.setHeader("Referer", "https://wnacg.com/");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int statusCode = response.getCode();

            if (statusCode != 200) {
                return null;
            }

            byte[] imageBytes = EntityUtils.toByteArray(response.getEntity());

            if (imageBytes == null || imageBytes.length == 0) {
                return null;
            }

            String contentStart = new String(imageBytes, 0, Math.min(100, imageBytes.length));
            if (contentStart.trim().toLowerCase().startsWith("<!doctype") ||
                    contentStart.trim().toLowerCase().startsWith("<html")) {
                return null;
            }

            return imageBytes;

        } catch (Exception e) {
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

        // 处理 Data URI
        if (url.startsWith("data:")) {
            String mimeType = url.substring(5, url.indexOf(';'));
            switch (mimeType.toLowerCase()) {
                case "image/png": return "png";
                case "image/gif": return "gif";
                case "image/bmp": return "bmp";
                case "image/webp": return "webp";
                default: return "jpg";
            }
        }

        String ext = "jpg"; // 默认扩展名
        // 去掉查询参数
        String cleanUrl = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        int dotIndex = cleanUrl.lastIndexOf('.');
        if (dotIndex != -1 && dotIndex < cleanUrl.length() - 1) {
            ext = cleanUrl.substring(dotIndex + 1).toLowerCase();
        }
        // 验证扩展名（防止无效扩展名）
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

        // 处理 Data URI
        if (url.startsWith("data:")) {
            return "image";
        }

        // 去掉查询参数
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
        // 移除Windows/Linux非法字符
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("_+", "_")
                .trim();
    }

    /**
     * 创建ZIP压缩文件
     */
    private void createZipArchive(String folderPath) throws IOException {
        String zipPath = folderPath + ".zip";
        try (FileOutputStream fos = new FileOutputStream(zipPath);
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
        logger.info("已创建ZIP文件: {}", zipPath);
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
            }
            if (executor != null) {
                executor.shutdown();
            }
        } catch (IOException e) {
            // 忽略异常
        }
    }
}