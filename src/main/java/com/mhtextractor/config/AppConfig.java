package com.mhtextractor.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 精简后的应用配置类
 * 使用 Lombok @Data 替代手写 Getter/Setter/equals/hashCode
 * 使用 @JsonProperty 保证JSON序列化兼容性
 */
@Data
public class AppConfig {

    // ==================== 通用字段 ====================
    private String inputFolder;
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
    @JsonProperty("imageMode")
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

    // ==================== 运行时状态 ====================
    private boolean openFolderAfterComplete;
    /** 开始提取前打开预览并调整窗口 */
    private boolean previewBeforeExtract;

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
