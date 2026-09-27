package com.mhtextractor.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 精简后的应用配置类
 * 使用 Lombok @Data 替代手写 Getter/Setter/equals/hashCode
 * 使用 @JsonProperty 保证JSON序列化兼容性
 *
 * <p>命名配置（config/）只保存提取参数；输入/输出目录、提取模式与"完成后打开输出文件夹"
 * 属于主界面 GUI 状态，由 logs/gui-state.json 持久化（见 {@link GuiState}），
 * 故以下字段标注 @JsonIgnore 不参与命名配置序列化（运行时仍正常使用）。</p>
 */
@Data
public class AppConfig {

    // ==================== 通用字段（GUI 状态，不随命名配置保存） ====================
    @JsonIgnore
    private String inputFolder;
    @JsonIgnore
    private String outputFolder;
    private boolean separateFiles;

    // ==================== 文本提取字段 ====================
    @JsonProperty("enableLimit")
    private boolean enableContentLimit;
    private String startMarker;
    private String endMarker;
    private boolean useRegex;

    @JsonProperty("enableCustomTemplate")
    private boolean enableTemplate;
    private String startTemplate;
    private String endTemplate;

    private boolean addChapterNumber;
    private boolean autoFixEncoding = true;

    @JsonProperty("enableTitleExtract")
    private boolean enableTitleExtraction;
    private String titleStartMarker;
    private String titleEndMarker;
    private boolean titleUseRegex;

    // ==================== 图片提取字段 ====================
    /** 提取模式属于主窗口 GUI 状态，不随命名配置保存（运行时由主窗口模式开关写入） */
    @JsonIgnore
    private boolean imageModeEnabled;

    @JsonProperty("imageNameExtractEnabled")
    private boolean enableNameExtraction;
    private String imageNameStartMarker;
    private String imageNameEndMarker;

    @JsonProperty("imageRegionExtractEnabled")
    private boolean enableRegionExtraction;
    private String imageRegionStartMarker;
    private String imageRegionEndMarker;

    @JsonProperty("imageRenameEnabled")
    private boolean enableBatchRenaming;
    @JsonProperty("imageZipEnabled")
    private boolean enableZipPackaging;
    @JsonProperty("imageCbzEnabled")
    private boolean enableCbzPackaging;

    // ==================== 图片输出模式（兼容旧版字段名） ====================
    @JsonProperty("singleOutputMode")
    private boolean singleOutputMode;

    // ==================== 处理模式 ====================
    @JsonProperty("legacyMemoryMode")
    private boolean highSpeedMemoryMode;

    // ==================== 运行时状态（GUI 状态，不随命名配置保存） ====================
    @JsonIgnore
    private boolean openFolderAfterComplete;

    // ==================== 兼容旧版废弃字段（保留反序列化兼容） ====================
    @Deprecated
    private String nameLimitStart;
    @Deprecated
    private String nameLimitEnd;
    @Deprecated
    private String contentLimitStart;
    @Deprecated
    private String contentLimitEnd;

    // ==================== 图片提取参数指纹（需求 2 P2/P10，会话 7） ====================

    /**
     * 图片提取参数指纹：只含影响「提取出哪些图片、以什么名字、什么扩展名」的字段，
     * 按固定顺序拼接后取 SHA-256（64 位十六进制）。缓存复用判定只比对本字符串（P10），
     * 不得持 AppConfig 引用或用 equals 比较（AppConfig 是 @Data 可变对象）。
     *
     * <p>参与字段（8 个）：</p>
     * <ol>
     *   <li>{@code enableNameExtraction} + {@code imageNameStartMarker} / {@code imageNameEndMarker}
     *       —— 名称区域限定，决定合并输出目录名与文件名（ImageBatchProcessor:216/310）；</li>
     *   <li>{@code enableRegionExtraction} + {@code imageRegionStartMarker} / {@code imageRegionEndMarker}
     *       —— 内容区域限定，决定提取哪些图片（ImageExtractor 区域过滤）；</li>
     *   <li>{@code enableBatchRenaming} —— 批量重命名，决定输出文件名（ImageWriter 序号命名）；</li>
     *   <li>{@code singleOutputMode} —— 合并/单独输出，决定输出目录结构。</li>
     * </ol>
     *
     * <p>不参与字段及理由：</p>
     * <ul>
     *   <li>{@code inputFolder} / {@code outputFolder} / {@code openFolderAfterComplete} / {@code imageModeEnabled}
     *       —— 运行时/GUI 状态字段，不影响图片字节（P2 明确排除）；</li>
     *   <li>{@code enableZipPackaging} / {@code enableCbzPackaging} —— 归档只改变最终容器形态，
     *       缓存内图片字节与命名不受影响（P2 明确排除）；</li>
     *   <li>{@code highSpeedMemoryMode} —— 仅切换内存策略（预加载/流式），解析与提取逻辑相同，产物一致；</li>
     *   <li>文本模式字段（{@code startMarker} 等）与废弃兼容字段（{@code nameLimitStart} 等，主代码零使用）
     *       —— 与图片提取无关。</li>
     * </ul>
     *
     * <p>广告过滤关键词为 {@code ImageExtractor.AD_KEYWORDS} 硬编码常量，AppConfig 无对应字段，
     * 无需参与指纹；若将来参数化，必须加入本方法。</p>
     *
     * <p>顺序稳定：固定字段顺序 + 长度前缀编码（String 先写长度再写内容），
     * 不依赖 Map/Set 迭代顺序；null 与空串可区分。</p>
     */
    public String imageExtractionFingerprint() {
        StringBuilder sb = new StringBuilder();
        sb.append(enableNameExtraction ? '1' : '0');
        appendFingerprintField(sb, imageNameStartMarker);
        appendFingerprintField(sb, imageNameEndMarker);
        sb.append(enableRegionExtraction ? '1' : '0');
        appendFingerprintField(sb, imageRegionStartMarker);
        appendFingerprintField(sb, imageRegionEndMarker);
        sb.append(enableBatchRenaming ? '1' : '0');
        sb.append(singleOutputMode ? '1' : '0');
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void appendFingerprintField(StringBuilder sb, String value) {
        sb.append(value == null ? -1 : value.length()).append(':').append(value == null ? "" : value);
    }
}
