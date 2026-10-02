package com.mhtextractor.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;

import com.mhtextractor.app.EffectiveParamsService;

import org.junit.jupiter.api.Test;

/**
 * {@link AppConfig#copyExtractionParams()} 的字段级守护（Q10 / R7.7）。
 *
 * <p><b>为什么需要它</b>：深拷贝助手是"逐字段显式赋值"实现的（不用反射/序列化，保 native 镜像零元数据
 * 负担），因此<b>最容易出的错是漏字段或误带运行时字段</b>。本用例用反射把 {@code AppConfig} 的<b>每一个</b>
 * 字段都填成一个独特值，再逐字段核对副本：</p>
 * <ul>
 *   <li>提取参数字段（= 全部字段 − {@link EffectiveParamsService#runtimeOnlyFields()}）必须逐字段复制到；</li>
 *   <li>4 个 {@code @JsonIgnore} 运行时/GUI 字段（{@code inputFolder} / {@code outputFolder} /
 *       {@code imageModeEnabled} / {@code openFolderAfterComplete}）<b>必须保持初值</b>（R7.7：
 *       应用复制的参数不得覆盖用户当前目录/模式）。</li>
 * </ul>
 *
 * <p>排除集合只从 {@code EffectiveParamsService.runtimeOnlyFields()} 取（不在这里再抄一份），
 * 于是"签名口径"、"深拷贝口径"与"运行时字段清单"三者互相钉住。</p>
 */
class AppConfigCopyTest {

    @Test
    void copyIsANewInstanceWithEqualExtractionSignature() {
        AppConfig src = new AppConfig();
        src.setSeparateFiles(true);
        src.setStartMarker("S");
        AppConfig copy = src.copyExtractionParams();

        assertNotSame(src, copy, "必须是新实例（@Data 可变对象，共享会串味）");
        assertEquals(EffectiveParamsService.signatureOf(src), EffectiveParamsService.signatureOf(copy),
                "提取参数必须值相等（按服务的字段级签名比，不用 equals）");
        assertTrue(copy.isSeparateFiles(), "布尔提取参数必须被复制");
    }

    @Test
    void mutatingEitherSideDoesNotAffectTheOther() {
        AppConfig src = new AppConfig();
        src.setStartMarker("SRC");
        src.setEnableTitleExtraction(true);
        AppConfig copy = src.copyExtractionParams();

        copy.setStartMarker("COPY");
        copy.setEnableTitleExtraction(false);
        assertEquals("SRC", src.getStartMarker(), "改副本不得影响源");
        assertTrue(src.isEnableTitleExtraction(), "改副本不得影响源（布尔）");

        src.setStartMarker("SRC2");
        assertEquals("COPY", copy.getStartMarker(), "改源不得影响副本");
    }

    /**
     * 逐字段对账：每个字段都填独特值 → 副本必须"提取参数全中、运行时字段全不中"。
     * 漏一个提取参数字段（或误带一个运行时字段）都会在这里变红。
     */
    @Test
    void copyCoversEveryExtractionFieldAndNeverCarriesRuntimeFields() throws Exception {
        List<String> runtimeOnly = EffectiveParamsService.runtimeOnlyFields();
        assertEquals(List.of("inputFolder", "outputFolder", "imageModeEnabled", "openFolderAfterComplete"),
                runtimeOnly, "运行时字段清单是 R7.7 口径的唯一来源");

        AppConfig src = new AppConfig();
        Field[] fields = AppConfig.class.getDeclaredFields();
        assertTrue(fields.length > 25, "装置自检：AppConfig 字段数应远多于 4（实际 " + fields.length + "）");
        for (Field f : fields) {
            f.setAccessible(true);
            if (f.getType() == boolean.class) {
                f.setBoolean(src, true);
            } else if (f.getType() == String.class) {
                f.set(src, "f:" + f.getName());
            } else {
                throw new IllegalStateException("AppConfig 出现未预期的字段类型：" + f);
            }
        }

        AppConfig copy = src.copyExtractionParams();
        int extractionFields = 0;
        for (Field f : fields) {
            f.setAccessible(true);
            if (runtimeOnly.contains(f.getName())) {
                if (f.getType() == boolean.class) {
                    assertFalse(f.getBoolean(copy), "运行时字段不得被复制：" + f.getName());
                } else {
                    assertNull(f.get(copy), "运行时字段不得被复制：" + f.getName());
                }
            } else {
                extractionFields++;
                if (f.getType() == boolean.class) {
                    assertTrue(f.getBoolean(copy), "提取参数字段必须被复制：" + f.getName());
                } else {
                    assertEquals("f:" + f.getName(), f.get(copy), "提取参数字段必须被复制：" + f.getName());
                }
            }
        }
        assertEquals(fields.length - runtimeOnly.size(), extractionFields,
                "提取参数字段数 = 全部字段 − 运行时字段");
        // 再按服务的签名口径复算一次（字段级签名覆盖的正是"全部字段 − 运行时字段"）
        assertEquals(EffectiveParamsService.signatureOf(src), EffectiveParamsService.signatureOf(copy),
                "全字段填满后，副本与源的提取参数签名仍必须相同");
    }
}
