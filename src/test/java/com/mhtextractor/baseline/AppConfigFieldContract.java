package com.mhtextractor.baseline;

import com.mhtextractor.model.AppConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 显式公开 getter/setter 驱动与编译后实例字段表互锁。
 * 新字段、改名或改类型必须先登记，再通过逐字段签名/复制行为测试；静态及合成字段除外。
 */
public final class AppConfigFieldContract {
    private AppConfigFieldContract() { }

    public record FieldCase(String name, String descriptor,
                            Function<AppConfig, Object> read, BiConsumer<AppConfig, Object> write) {
        public Object differentValue(AppConfig defaults) {
            return descriptor.equals("Z") ? !((Boolean) read.apply(defaults)) : "f:" + name;
        }

    }

    public static final List<FieldCase> FIELDS = List.of(
            bool("separateFiles", AppConfig::isSeparateFiles, AppConfig::setSeparateFiles),
            bool("enableContentLimit", AppConfig::isEnableContentLimit, AppConfig::setEnableContentLimit),
            bool("useRegex", AppConfig::isUseRegex, AppConfig::setUseRegex),
            bool("enableTemplate", AppConfig::isEnableTemplate, AppConfig::setEnableTemplate),
            bool("addChapterNumber", AppConfig::isAddChapterNumber, AppConfig::setAddChapterNumber),
            bool("autoFixEncoding", AppConfig::isAutoFixEncoding, AppConfig::setAutoFixEncoding),
            bool("enableTitleExtraction", AppConfig::isEnableTitleExtraction, AppConfig::setEnableTitleExtraction),
            bool("titleUseRegex", AppConfig::isTitleUseRegex, AppConfig::setTitleUseRegex),
            bool("imageModeEnabled", AppConfig::isImageModeEnabled, AppConfig::setImageModeEnabled),
            bool("enableNameExtraction", AppConfig::isEnableNameExtraction, AppConfig::setEnableNameExtraction),
            bool("enableRegionExtraction", AppConfig::isEnableRegionExtraction, AppConfig::setEnableRegionExtraction),
            bool("enableBatchRenaming", AppConfig::isEnableBatchRenaming, AppConfig::setEnableBatchRenaming),
            bool("enableZipPackaging", AppConfig::isEnableZipPackaging, AppConfig::setEnableZipPackaging),
            bool("enableCbzPackaging", AppConfig::isEnableCbzPackaging, AppConfig::setEnableCbzPackaging),
            bool("singleOutputMode", AppConfig::isSingleOutputMode, AppConfig::setSingleOutputMode),
            bool("highSpeedMemoryMode", AppConfig::isHighSpeedMemoryMode, AppConfig::setHighSpeedMemoryMode),
            bool("openFolderAfterComplete", AppConfig::isOpenFolderAfterComplete, AppConfig::setOpenFolderAfterComplete),
            string("inputFolder", AppConfig::getInputFolder, AppConfig::setInputFolder),
            string("outputFolder", AppConfig::getOutputFolder, AppConfig::setOutputFolder),
            string("startMarker", AppConfig::getStartMarker, AppConfig::setStartMarker),
            string("endMarker", AppConfig::getEndMarker, AppConfig::setEndMarker),
            string("startTemplate", AppConfig::getStartTemplate, AppConfig::setStartTemplate),
            string("endTemplate", AppConfig::getEndTemplate, AppConfig::setEndTemplate),
            string("titleStartMarker", AppConfig::getTitleStartMarker, AppConfig::setTitleStartMarker),
            string("titleEndMarker", AppConfig::getTitleEndMarker, AppConfig::setTitleEndMarker),
            string("imageNameStartMarker", AppConfig::getImageNameStartMarker, AppConfig::setImageNameStartMarker),
            string("imageNameEndMarker", AppConfig::getImageNameEndMarker, AppConfig::setImageNameEndMarker),
            string("imageRegionStartMarker", AppConfig::getImageRegionStartMarker, AppConfig::setImageRegionStartMarker),
            string("imageRegionEndMarker", AppConfig::getImageRegionEndMarker, AppConfig::setImageRegionEndMarker),
            string("nameLimitStart", AppConfig::getNameLimitStart, AppConfig::setNameLimitStart),
            string("nameLimitEnd", AppConfig::getNameLimitEnd, AppConfig::setNameLimitEnd),
            string("contentLimitStart", AppConfig::getContentLimitStart, AppConfig::setContentLimitStart),
            string("contentLimitEnd", AppConfig::getContentLimitEnd, AppConfig::setContentLimitEnd));

    private static FieldCase bool(String name, Function<AppConfig, Boolean> read,
                                  BiConsumer<AppConfig, Boolean> write) {
        return new FieldCase(name, "Z", c -> read.apply(c), (c, v) -> write.accept(c, (Boolean) v));
    }

    private static FieldCase string(String name, Function<AppConfig, String> read,
                                    BiConsumer<AppConfig, String> write) {
        return new FieldCase(name, "Ljava/lang/String;", c -> read.apply(c),
                (c, v) -> write.accept(c, (String) v));
    }

    /** 复用既有 class 解析器，直接比对声明字段名及类型；不加载类或枚举运行时成员。 */
    public static void assertMatchesDeclaredFields() throws IOException {
        var parsed = TestAccessSurface.parseClassFile(Files.readAllBytes(
                Path.of("target/classes/com/mhtextractor/model/AppConfig.class")));
        Map<String, String> actual = new TreeMap<>();
        for (var f : parsed.fields) {
            if ((f.flags() & (0x0008 | 0x1000)) == 0) {
                actual.put(f.name(), f.desc());
            }
        }
        Map<String, String> expected = new TreeMap<>();
        for (var f : FIELDS) {
            if (expected.put(f.name(), f.descriptor()) != null) {
                throw new AssertionError("重复字段登记：" + f.name());
            }
        }
        assertEquals(expected, actual,
                "AppConfig 声明字段集变化：请同步公开字段驱动、值语义签名及深拷贝");
    }
}
