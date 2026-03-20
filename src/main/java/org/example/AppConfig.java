package org.example;

import java.util.Objects;

public class AppConfig {
    // 原有文本模式字段
    private String inputFolder;
    private String outputFolder;
    private boolean separateFiles;
    private boolean enableLimit;
    private String startMarker;
    private String endMarker;
    private boolean useRegex;
    private boolean enableCustomTemplate;
    private String startTemplate;
    private String endTemplate;
    private boolean addChapterNumber;
    private boolean enableTitleExtract;
    private String titleStartMarker;
    private String titleEndMarker;
    private boolean titleUseRegex;

    // 新增：当前模式标志（true=图片模式，false=文本模式）
    private boolean imageMode;

    // 新增：图片提取模式专用字段
    private boolean imageNameExtractEnabled;
    private String imageNameStartMarker;
    private String imageNameEndMarker;
    private boolean imageRegionExtractEnabled;
    private String imageRegionStartMarker;
    private String imageRegionEndMarker;
    private boolean imageZipEnabled;
    private boolean imageRenameEnabled;

    // 新增：文本提取内容限定字段（用于 ImageExtractor）
    private String nameLimitStart;
    private String nameLimitEnd;
    private String contentLimitStart;
    private String contentLimitEnd;

    // 新增：图片输出模式字段
    private boolean singleOutputMode;
    private boolean enableBatchRenaming;
    private boolean enableZipPackaging;

    // ==================== 原有 Getters and Setters ====================
    public String getInputFolder() {
        return inputFolder;
    }
    public void setInputFolder(String inputFolder) {
        this.inputFolder = inputFolder;
    }
    public String getOutputFolder() {
        return outputFolder;
    }
    public void setOutputFolder(String outputFolder) {
        this.outputFolder = outputFolder;
    }
    public boolean isSeparateFiles() {
        return separateFiles;
    }
    public void setSeparateFiles(boolean separateFiles) {
        this.separateFiles = separateFiles;
    }
    public boolean isEnableLimit() {
        return enableLimit;
    }
    public void setEnableLimit(boolean enableLimit) {
        this.enableLimit = enableLimit;
    }
    public String getStartMarker() {
        return startMarker;
    }
    public void setStartMarker(String startMarker) {
        this.startMarker = startMarker;
    }
    public String getEndMarker() {
        return endMarker;
    }
    public void setEndMarker(String endMarker) {
        this.endMarker = endMarker;
    }
    public boolean isUseRegex() {
        return useRegex;
    }
    public void setUseRegex(boolean useRegex) {
        this.useRegex = useRegex;
    }
    public boolean isEnableCustomTemplate() {
        return enableCustomTemplate;
    }
    public void setEnableCustomTemplate(boolean enableCustomTemplate) {
        this.enableCustomTemplate = enableCustomTemplate;
    }
    public String getStartTemplate() {
        return startTemplate;
    }
    public void setStartTemplate(String startTemplate) {
        this.startTemplate = startTemplate;
    }
    public String getEndTemplate() {
        return endTemplate;
    }
    public void setEndTemplate(String endTemplate) {
        this.endTemplate = endTemplate;
    }
    public boolean isAddChapterNumber() {
        return addChapterNumber;
    }
    public void setAddChapterNumber(boolean addChapterNumber) {
        this.addChapterNumber = addChapterNumber;
    }
    public boolean isEnableTitleExtract() {
        return enableTitleExtract;
    }
    public void setEnableTitleExtract(boolean enableTitleExtract) {
        this.enableTitleExtract = enableTitleExtract;
    }
    public String getTitleStartMarker() {
        return titleStartMarker;
    }
    public void setTitleStartMarker(String titleStartMarker) {
        this.titleStartMarker = titleStartMarker;
    }
    public String getTitleEndMarker() {
        return titleEndMarker;
    }
    public void setTitleEndMarker(String titleEndMarker) {
        this.titleEndMarker = titleEndMarker;
    }
    public boolean isTitleUseRegex() {
        return titleUseRegex;
    }
    public void setTitleUseRegex(boolean titleUseRegex) {
        this.titleUseRegex = titleUseRegex;
    }

    // ==================== 新增 Getters and Setters ====================
    public boolean isImageMode() {
        return imageMode;
    }
    public void setImageMode(boolean imageMode) {
        this.imageMode = imageMode;
    }
    public boolean isImageNameExtractEnabled() {
        return imageNameExtractEnabled;
    }
    public void setImageNameExtractEnabled(boolean imageNameExtractEnabled) {
        this.imageNameExtractEnabled = imageNameExtractEnabled;
    }
    public String getImageNameStartMarker() {
        return imageNameStartMarker;
    }
    public void setImageNameStartMarker(String imageNameStartMarker) {
        this.imageNameStartMarker = imageNameStartMarker;
    }
    public String getImageNameEndMarker() {
        return imageNameEndMarker;
    }
    public void setImageNameEndMarker(String imageNameEndMarker) {
        this.imageNameEndMarker = imageNameEndMarker;
    }
    public boolean isImageRegionExtractEnabled() {
        return imageRegionExtractEnabled;
    }
    public void setImageRegionExtractEnabled(boolean imageRegionExtractEnabled) {
        this.imageRegionExtractEnabled = imageRegionExtractEnabled;
    }
    public String getImageRegionStartMarker() {
        return imageRegionStartMarker;
    }
    public void setImageRegionStartMarker(String imageRegionStartMarker) {
        this.imageRegionStartMarker = imageRegionStartMarker;
    }
    public String getImageRegionEndMarker() {
        return imageRegionEndMarker;
    }
    public void setImageRegionEndMarker(String imageRegionEndMarker) {
        this.imageRegionEndMarker = imageRegionEndMarker;
    }
    public boolean isImageZipEnabled() {
        return imageZipEnabled;
    }
    public void setImageZipEnabled(boolean imageZipEnabled) {
        this.imageZipEnabled = imageZipEnabled;
    }
    public boolean isImageRenameEnabled() {
        return imageRenameEnabled;
    }
    public void setImageRenameEnabled(boolean imageRenameEnabled) {
        this.imageRenameEnabled = imageRenameEnabled;
    }

    // ==================== 文本提取内容限定字段 Getter/Setter ====================
    public String getNameLimitStart() {
        return nameLimitStart;
    }
    public void setNameLimitStart(String nameLimitStart) {
        this.nameLimitStart = nameLimitStart;
    }
    public String getNameLimitEnd() {
        return nameLimitEnd;
    }
    public void setNameLimitEnd(String nameLimitEnd) {
        this.nameLimitEnd = nameLimitEnd;
    }
    public String getContentLimitStart() {
        return contentLimitStart;
    }
    public void setContentLimitStart(String contentLimitStart) {
        this.contentLimitStart = contentLimitStart;
    }
    public String getContentLimitEnd() {
        return contentLimitEnd;
    }
    public void setContentLimitEnd(String contentLimitEnd) {
        this.contentLimitEnd = contentLimitEnd;
    }

    // ==================== 图片输出模式字段 Getter/Setter ====================
    public boolean isSingleOutputMode() {
        return singleOutputMode;
    }
    public void setSingleOutputMode(boolean singleOutputMode) {
        this.singleOutputMode = singleOutputMode;
    }
    public boolean isEnableBatchRenaming() {
        return enableBatchRenaming;
    }
    public void setEnableBatchRenaming(boolean enableBatchRenaming) {
        this.enableBatchRenaming = enableBatchRenaming;
    }
    public boolean isEnableZipPackaging() {
        return enableZipPackaging;
    }
    public void setEnableZipPackaging(boolean enableZipPackaging) {
        this.enableZipPackaging = enableZipPackaging;
    }

    // ==================== equals and hashCode ====================
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AppConfig appConfig = (AppConfig) o;
        return separateFiles == appConfig.separateFiles && enableLimit == appConfig.enableLimit && useRegex == appConfig.useRegex && enableCustomTemplate == appConfig.enableCustomTemplate && addChapterNumber == appConfig.addChapterNumber && enableTitleExtract == appConfig.enableTitleExtract && titleUseRegex == appConfig.titleUseRegex && imageMode == appConfig.imageMode && imageNameExtractEnabled == appConfig.imageNameExtractEnabled && imageRegionExtractEnabled == appConfig.imageRegionExtractEnabled && imageZipEnabled == appConfig.imageZipEnabled && imageRenameEnabled == appConfig.imageRenameEnabled && singleOutputMode == appConfig.singleOutputMode && enableBatchRenaming == appConfig.enableBatchRenaming && enableZipPackaging == appConfig.enableZipPackaging && Objects.equals(inputFolder, appConfig.inputFolder) && Objects.equals(outputFolder, appConfig.outputFolder) && Objects.equals(startMarker, appConfig.startMarker) && Objects.equals(endMarker, appConfig.endMarker) && Objects.equals(startTemplate, appConfig.startTemplate) && Objects.equals(endTemplate, appConfig.endTemplate) && Objects.equals(titleStartMarker, appConfig.titleStartMarker) && Objects.equals(titleEndMarker, appConfig.titleEndMarker) && Objects.equals(imageNameStartMarker, appConfig.imageNameStartMarker) && Objects.equals(imageNameEndMarker, appConfig.imageNameEndMarker) && Objects.equals(imageRegionStartMarker, appConfig.imageRegionStartMarker) && Objects.equals(imageRegionEndMarker, appConfig.imageRegionEndMarker) && Objects.equals(nameLimitStart, appConfig.nameLimitStart) && Objects.equals(nameLimitEnd, appConfig.nameLimitEnd) && Objects.equals(contentLimitStart, appConfig.contentLimitStart) && Objects.equals(contentLimitEnd, appConfig.contentLimitEnd);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                inputFolder, outputFolder, separateFiles, enableLimit, startMarker, endMarker, useRegex, enableCustomTemplate, startTemplate, endTemplate, addChapterNumber, enableTitleExtract, titleStartMarker, titleEndMarker, titleUseRegex, imageMode, imageNameExtractEnabled, imageNameStartMarker, imageNameEndMarker, imageRegionExtractEnabled, imageRegionStartMarker, imageRegionEndMarker, imageZipEnabled, imageRenameEnabled, singleOutputMode, enableBatchRenaming, enableZipPackaging, nameLimitStart, nameLimitEnd, contentLimitStart, contentLimitEnd
        );
    }
}
