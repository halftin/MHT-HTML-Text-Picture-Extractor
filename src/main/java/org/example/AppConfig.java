package org.example;

import java.util.Objects;


public class AppConfig {
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

    // Getters and Setters
    public String getInputFolder() { return inputFolder; }
    public void setInputFolder(String inputFolder) { this.inputFolder = inputFolder; }

    public String getOutputFolder() { return outputFolder; }
    public void setOutputFolder(String outputFolder) { this.outputFolder = outputFolder; }

    public boolean isSeparateFiles() { return separateFiles; }
    public void setSeparateFiles(boolean separateFiles) { this.separateFiles = separateFiles; }

    public boolean isEnableLimit() { return enableLimit; }
    public void setEnableLimit(boolean enableLimit) { this.enableLimit = enableLimit; }

    public String getStartMarker() { return startMarker; }
    public void setStartMarker(String startMarker) { this.startMarker = startMarker; }

    public String getEndMarker() { return endMarker; }
    public void setEndMarker(String endMarker) { this.endMarker = endMarker; }

    public boolean isUseRegex() { return useRegex; }
    public void setUseRegex(boolean useRegex) { this.useRegex = useRegex; }

    public boolean isEnableCustomTemplate() { return enableCustomTemplate; }
    public void setEnableCustomTemplate(boolean enableCustomTemplate) { this.enableCustomTemplate = enableCustomTemplate; }

    public String getStartTemplate() { return startTemplate; }
    public void setStartTemplate(String startTemplate) { this.startTemplate = startTemplate; }

    public String getEndTemplate() { return endTemplate; }
    public void setEndTemplate(String endTemplate) { this.endTemplate = endTemplate; }

    public boolean isAddChapterNumber() { return addChapterNumber; }
    public void setAddChapterNumber(boolean addChapterNumber) { this.addChapterNumber = addChapterNumber; }

    public boolean isEnableTitleExtract() { return enableTitleExtract; }
    public void setEnableTitleExtract(boolean enableTitleExtract) { this.enableTitleExtract = enableTitleExtract; }

    public String getTitleStartMarker() { return titleStartMarker; }
    public void setTitleStartMarker(String titleStartMarker) { this.titleStartMarker = titleStartMarker; }

    public String getTitleEndMarker() { return titleEndMarker; }
    public void setTitleEndMarker(String titleEndMarker) { this.titleEndMarker = titleEndMarker; }

    public boolean isTitleUseRegex() { return titleUseRegex; }
    public void setTitleUseRegex(boolean titleUseRegex) { this.titleUseRegex = titleUseRegex; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AppConfig appConfig = (AppConfig) o;
        return separateFiles == appConfig.separateFiles &&
                enableLimit == appConfig.enableLimit &&
                useRegex == appConfig.useRegex &&
                enableCustomTemplate == appConfig.enableCustomTemplate &&
                addChapterNumber == appConfig.addChapterNumber &&
                enableTitleExtract == appConfig.enableTitleExtract &&
                titleUseRegex == appConfig.titleUseRegex &&
                Objects.equals(inputFolder, appConfig.inputFolder) &&
                Objects.equals(outputFolder, appConfig.outputFolder) &&
                Objects.equals(startMarker, appConfig.startMarker) &&
                Objects.equals(endMarker, appConfig.endMarker) &&
                Objects.equals(startTemplate, appConfig.startTemplate) &&
                Objects.equals(endTemplate, appConfig.endTemplate) &&
                Objects.equals(titleStartMarker, appConfig.titleStartMarker) &&
                Objects.equals(titleEndMarker, appConfig.titleEndMarker);
    }

    @Override
    public int hashCode() {
        return Objects.hash(inputFolder, outputFolder, separateFiles, enableLimit, startMarker, endMarker, useRegex,
                enableCustomTemplate, startTemplate, endTemplate, addChapterNumber, enableTitleExtract,
                titleStartMarker, titleEndMarker, titleUseRegex);
    }
}