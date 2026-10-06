package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uiport.ParamSchemaVm;
import com.mhtextractor.uiport.ParamSchemaVm.Field;
import com.mhtextractor.uiport.ParamSchemaVm.Group;
import com.mhtextractor.uiport.ParamSchemaVm.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** 参数字段映射与旧表单联动规则的唯一所有者；无反射、无文案解析。 */
public final class ParamSchemaService {
    private ParamSchemaService() { }

    private record Binding(String id, Function<AppConfig, Object> read, BiConsumer<AppConfig, Object> write) { }

    // 保留旧 applyText/applyImage 的赋值顺序（开关监听先于其子字段写入）。
    private static final List<Binding> BINDINGS = List.of(
            new Binding("separateFiles", AppConfig::isSeparateFiles, (c, v) -> c.setSeparateFiles((Boolean) v)),
            new Binding("enableContentLimit", AppConfig::isEnableContentLimit, (c, v) -> c.setEnableContentLimit((Boolean) v)),
            new Binding("startMarker", AppConfig::getStartMarker, (c, v) -> c.setStartMarker((String) v)),
            new Binding("endMarker", AppConfig::getEndMarker, (c, v) -> c.setEndMarker((String) v)),
            new Binding("useRegex", AppConfig::isUseRegex, (c, v) -> c.setUseRegex((Boolean) v)),
            new Binding("enableTemplate", AppConfig::isEnableTemplate, (c, v) -> c.setEnableTemplate((Boolean) v)),
            new Binding("startTemplate", AppConfig::getStartTemplate, (c, v) -> c.setStartTemplate((String) v)),
            new Binding("endTemplate", AppConfig::getEndTemplate, (c, v) -> c.setEndTemplate((String) v)),
            new Binding("addChapterNumber", AppConfig::isAddChapterNumber, (c, v) -> c.setAddChapterNumber((Boolean) v)),
            new Binding("enableTitleExtraction", AppConfig::isEnableTitleExtraction, (c, v) -> c.setEnableTitleExtraction((Boolean) v)),
            new Binding("titleStartMarker", AppConfig::getTitleStartMarker, (c, v) -> c.setTitleStartMarker((String) v)),
            new Binding("titleEndMarker", AppConfig::getTitleEndMarker, (c, v) -> c.setTitleEndMarker((String) v)),
            new Binding("titleUseRegex", AppConfig::isTitleUseRegex, (c, v) -> c.setTitleUseRegex((Boolean) v)),
            new Binding("autoFixEncoding", AppConfig::isAutoFixEncoding, (c, v) -> c.setAutoFixEncoding((Boolean) v)),
            new Binding("enableNameExtraction", AppConfig::isEnableNameExtraction, (c, v) -> c.setEnableNameExtraction((Boolean) v)),
            new Binding("imageNameStartMarker", AppConfig::getImageNameStartMarker, (c, v) -> c.setImageNameStartMarker((String) v)),
            new Binding("imageNameEndMarker", AppConfig::getImageNameEndMarker, (c, v) -> c.setImageNameEndMarker((String) v)),
            new Binding("enableRegionExtraction", AppConfig::isEnableRegionExtraction, (c, v) -> c.setEnableRegionExtraction((Boolean) v)),
            new Binding("imageRegionStartMarker", AppConfig::getImageRegionStartMarker, (c, v) -> c.setImageRegionStartMarker((String) v)),
            new Binding("imageRegionEndMarker", AppConfig::getImageRegionEndMarker, (c, v) -> c.setImageRegionEndMarker((String) v)),
            new Binding("enableBatchRenaming", AppConfig::isEnableBatchRenaming, (c, v) -> c.setEnableBatchRenaming((Boolean) v)),
            new Binding("enableZipPackaging", AppConfig::isEnableZipPackaging, (c, v) -> c.setEnableZipPackaging((Boolean) v)),
            new Binding("enableCbzPackaging", AppConfig::isEnableCbzPackaging, (c, v) -> c.setEnableCbzPackaging((Boolean) v)),
            new Binding("singleOutputMode", AppConfig::isSingleOutputMode, (c, v) -> c.setSingleOutputMode((Boolean) v)),
            new Binding("highSpeedMemoryMode", AppConfig::isHighSpeedMemoryMode, (c, v) -> c.setHighSpeedMemoryMode((Boolean) v)));

    private static final ParamSchemaVm TEXT = new ParamSchemaVm(false, List.of(
            new Group("textTitlePane", "param.tab.chapter", null),
            new Group("textContentLimitPane", "param.text.areaTitle", null),
            new Group("textTemplatePane", "param.text.templateTitle", "param.text.templateVars"),
            new Group("textOutputModePane", "param.text.outputMode", null)), List.of(
            new Field("addChapterNumber", Type.BOOLEAN, "param.text.addChapterNumbers", "textTitlePane", false, null, null, false, null, false),
            new Field("enableTitleExtraction", Type.BOOLEAN, "param.text.extractTitle", "textTitlePane", false, null, null, false, null, false),
            new Field("titleStartMarker", Type.TEXT, "param.text.titleStartMarker", "textTitlePane", "", "enableTitleExtraction", "", false, null, false),
            new Field("titleEndMarker", Type.TEXT, "param.text.titleEndMarker", "textTitlePane", "", "enableTitleExtraction", "", false, null, false),
            new Field("titleUseRegex", Type.BOOLEAN, "param.text.titleMarkerRegex", "textTitlePane", false, "enableTitleExtraction", false, false, null, false),
            new Field("enableContentLimit", Type.BOOLEAN, "param.text.areaEnable", "textContentLimitPane", false, null, null, false, null, false),
            new Field("startMarker", Type.TEXT, "param.text.areaStartMarker", "textContentLimitPane", "", "enableContentLimit", "", false, null, false),
            new Field("endMarker", Type.TEXT, "param.text.areaEndMarker", "textContentLimitPane", "", "enableContentLimit", "", false, null, false),
            new Field("useRegex", Type.BOOLEAN, "param.text.areaMarkerRegex", "textContentLimitPane", false, "enableContentLimit", false, false, null, false),
            new Field("enableTemplate", Type.BOOLEAN, "param.text.templateEnable", "textTemplatePane", false, null, null, false, null, false),
            new Field("startTemplate", Type.TEXT, "param.text.templateStart", "textTemplatePane", "\\n========== START ==========\\n", "enableTemplate", "\\n========== START ==========\\n", false, "\n ========== START ==========\n ", true),
            new Field("endTemplate", Type.TEXT, "param.text.templateEnd", "textTemplatePane", "\\n========== END ==========\\n", "enableTemplate", "\\n========== END ==========\\n", false, "\n ========== END ==========\n ", true),
            new Field("separateFiles", Type.BOOLEAN, "param.text.separateTxt", "textOutputModePane", false, null, null, false, null, false),
            new Field("autoFixEncoding", Type.BOOLEAN, "param.text.fixEncoding", "textOutputModePane", true, null, null, false, null, false)));

    private static final ParamSchemaVm IMAGE = new ParamSchemaVm(true, List.of(
            new Group("imageNamePane", "param.image.titleGroup", null),
            new Group("imageRegionPane", "param.image.areaTitle", null),
            new Group("imageProcessPane", "param.image.processingTitle", null),
            new Group("imageOutputModePane", "param.image.outputMode", null),
            new Group("imagePerformancePane", "param.image.performance", null)), List.of(
            new Field("enableNameExtraction", Type.BOOLEAN, "param.image.extractTitle", "imageNamePane", false, null, null, false, null, false),
            new Field("imageNameStartMarker", Type.TEXT, "param.image.titleStartMarker", "imageNamePane", "", "enableNameExtraction", "", false, null, false),
            new Field("imageNameEndMarker", Type.TEXT, "param.image.titleEndMarker", "imageNamePane", "", "enableNameExtraction", "", false, null, false),
            new Field("enableRegionExtraction", Type.BOOLEAN, "param.image.areaEnable", "imageRegionPane", false, null, null, false, null, false),
            new Field("imageRegionStartMarker", Type.TEXT, "param.image.areaStartMarker", "imageRegionPane", "", "enableRegionExtraction", "", true, null, false),
            new Field("imageRegionEndMarker", Type.TEXT, "param.image.areaEndMarker", "imageRegionPane", "", "enableRegionExtraction", "", true, null, false),
            new Field("enableBatchRenaming", Type.BOOLEAN, "param.image.rename", "imageProcessPane", false, null, null, false, null, false),
            new Field("enableZipPackaging", Type.BOOLEAN, "param.image.zip", "imageProcessPane", false, null, null, false, null, false),
            new Field("enableCbzPackaging", Type.BOOLEAN, "param.image.cbz", "imageProcessPane", false, null, null, false, null, false),
            new Field("singleOutputMode", Type.BOOLEAN, "param.image.mergeDir", "imageOutputModePane", false, null, null, false, null, false),
            new Field("highSpeedMemoryMode", Type.BOOLEAN, "param.image.highSpeed", "imagePerformancePane", false, null, null, false, null, false)));

    public static ParamSchemaVm schema(boolean imageMode) { return imageMode ? IMAGE : TEXT; }

    /** AppConfig -> 原始控件值；null 字符串变为空串，顺序沿用旧 load。 */
    public static Map<String, Object> values(AppConfig config, ParamSchemaVm schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Binding b : BINDINGS) {
            if (schema.fields().stream().noneMatch(f -> f.id().equals(b.id()))) continue;
            Object value = b.read().apply(config);
            result.put(b.id(), value == null ? "" : value);
        }
        return result;
    }

    /** 一个开关的联动：返回需要禁用并复位的字段值；启用时保留原输入。 */
    public static Map<String, Object> disabledValues(ParamSchemaVm schema, Map<String, Object> values,
                                                      String controller) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Field f : schema.fields()) {
            if (controller.equals(f.enabledBy()) && !Boolean.TRUE.equals(values.get(controller)))
                result.put(f.id(), f.disabledValue());
        }
        return result;
    }

    /** 两页控件值 -> 参数快照；仅关闭的图片区域标记收为 null，其余逐值保留。 */
    public static AppConfig collect(Map<String, Object> values) {
        AppConfig config = new AppConfig();
        for (Binding b : BINDINGS) {
            Object value = values.get(b.id());
            Field f = java.util.stream.Stream.concat(TEXT.fields().stream(), IMAGE.fields().stream())
                    .filter(field -> field.id().equals(b.id())).findFirst().orElseThrow();
            if (f.nullWhenDisabled() && !Boolean.TRUE.equals(values.get(f.enabledBy()))) value = null;
            b.write().accept(config, value);
        }
        return config;
    }
}
