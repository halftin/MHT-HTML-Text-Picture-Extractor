package com.mhtextractor.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

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
}
