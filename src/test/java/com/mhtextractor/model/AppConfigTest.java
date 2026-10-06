package com.mhtextractor.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片提取参数指纹测试（需求 2 P2/P10，会话 7）：
 * 稳定性（同值同指纹）、敏感性（参与字段任一变化 → 指纹必变）、
 * 免疫性（不参与字段变化 → 指纹不变）、空值/默认值安全、格式（SHA-256 hex）。
 */
class AppConfigTest {

    /** 参与指纹的 8 个字段的变异器（每个把基准配置改一个字段） */
    private static List<Consumer<AppConfig>> fingerprintMutations() {
        List<Consumer<AppConfig>> out = new ArrayList<>();
        out.add(c -> c.setEnableNameExtraction(!c.isEnableNameExtraction()));
        out.add(c -> c.setImageNameStartMarker(c.getImageNameStartMarker() + "X"));
        out.add(c -> c.setImageNameEndMarker(c.getImageNameEndMarker() + "X"));
        out.add(c -> c.setEnableRegionExtraction(!c.isEnableRegionExtraction()));
        out.add(c -> c.setImageRegionStartMarker(c.getImageRegionStartMarker() + "X"));
        out.add(c -> c.setImageRegionEndMarker(c.getImageRegionEndMarker() + "X"));
        out.add(c -> c.setEnableBatchRenaming(!c.isEnableBatchRenaming()));
        out.add(c -> c.setSingleOutputMode(!c.isSingleOutputMode()));
        return out;
    }

    /** 不参与指纹的字段的变异器（每个把基准配置改一个字段） */
    private static List<Consumer<AppConfig>> fingerprintIrrelevantMutations() {
        List<Consumer<AppConfig>> out = new ArrayList<>();
        out.add(c -> c.setInputFolder("D:/x/y/z"));
        out.add(c -> c.setOutputFolder("D:/x/y/z"));
        out.add(c -> c.setOpenFolderAfterComplete(true));
        out.add(c -> c.setImageModeEnabled(true));
        out.add(c -> c.setEnableZipPackaging(true));
        out.add(c -> c.setEnableCbzPackaging(true));
        out.add(c -> c.setHighSpeedMemoryMode(true));
        out.add(c -> c.setSeparateFiles(true));
        // 纯文本模式字段
        out.add(c -> c.setEnableContentLimit(true));
        out.add(c -> c.setStartMarker("S"));
        out.add(c -> c.setEndMarker("E"));
        out.add(c -> c.setUseRegex(true));
        out.add(c -> c.setEnableTemplate(true));
        out.add(c -> c.setStartTemplate("ST"));
        out.add(c -> c.setEndTemplate("ET"));
        out.add(c -> c.setAddChapterNumber(true));
        out.add(c -> c.setAutoFixEncoding(false));
        out.add(c -> c.setEnableTitleExtraction(true));
        out.add(c -> c.setTitleStartMarker("TS"));
        out.add(c -> c.setTitleEndMarker("TE"));
        out.add(c -> c.setTitleUseRegex(true));
        // 废弃兼容字段（主代码零使用）
        out.add(c -> c.setNameLimitStart("LS"));
        out.add(c -> c.setNameLimitEnd("LE"));
        out.add(c -> c.setContentLimitStart("CS"));
        out.add(c -> c.setContentLimitEnd("CE"));
        return out;
    }

    /** 基准配置：8 个参与字段全部非默认值 */
    private static AppConfig base() {
        AppConfig c = new AppConfig();
        c.setEnableNameExtraction(true);
        c.setImageNameStartMarker("《");
        c.setImageNameEndMarker("》");
        c.setEnableRegionExtraction(true);
        c.setImageRegionStartMarker("<!--start-->");
        c.setImageRegionEndMarker("<!--end-->");
        c.setEnableBatchRenaming(true);
        c.setSingleOutputMode(true);
        return c;
    }

    @Test
    void fingerprintStableForSameValues() {
        assertEquals(base().imageExtractionFingerprint(), base().imageExtractionFingerprint());
        AppConfig def = new AppConfig();
        assertEquals(def.imageExtractionFingerprint(), def.imageExtractionFingerprint());
    }

    @Test
    void fingerprintChangesWhenAnyParticipatingFieldChanges() {
        String baseFp = base().imageExtractionFingerprint();
        List<Consumer<AppConfig>> mutations = fingerprintMutations();
        assertEquals(8, mutations.size(), "参与字段数变化时须同步更新本测试");
        for (int i = 0; i < mutations.size(); i++) {
            AppConfig c = base();
            mutations.get(i).accept(c);
            assertNotEquals(baseFp, c.imageExtractionFingerprint(),
                    "参与字段 #" + i + " 变化后指纹必须改变");
        }
    }

    @Test
    void fingerprintChangesWhenRulesVersionChanges() {
        AppConfig c = base();
        assertNotEquals(c.imageExtractionFingerprint(1), c.imageExtractionFingerprint(2),
                "规则版本必须参与指纹：版本不同 ⇒ 缓存键不同（否则改 AD_KEYWORDS 等规则后旧缓存仍命中）");
    }

    @Test
    void publicFingerprintUsesCurrentRulesVersion() {
        AppConfig c = base();
        // 1 = AppConfig.IMAGE_EXTRACTION_RULES_VERSION 当前值（private 常量，测试按项目惯例钉数值）
        assertEquals(c.imageExtractionFingerprint(), c.imageExtractionFingerprint(1),
                "公开指纹必须等于当前规则版本的指纹（版本常量变更时同步此钉值）");
    }

    @Test
    void fingerprintIgnoresIrrelevantFields() {
        String baseFp = base().imageExtractionFingerprint();
        List<Consumer<AppConfig>> mutations = fingerprintIrrelevantMutations();
        for (int i = 0; i < mutations.size(); i++) {
            AppConfig c = base();
            mutations.get(i).accept(c);
            assertEquals(baseFp, c.imageExtractionFingerprint(),
                    "不参与字段 #" + i + " 变化后指纹必须不变");
        }
    }

    @Test
    void fingerprintHandlesNullsAndDefaults() {
        AppConfig def = new AppConfig();
        String fp = def.imageExtractionFingerprint();
        assertEquals(fp, new AppConfig().imageExtractionFingerprint());
        // null 与空串可区分（长度前缀编码：null=-1，空串=0）
        AppConfig empty = new AppConfig();
        empty.setImageNameStartMarker("");
        assertNotEquals(fp, empty.imageExtractionFingerprint());
        AppConfig nullMarker = new AppConfig();
        nullMarker.setImageNameStartMarker(null);
        assertEquals(fp, nullMarker.imageExtractionFingerprint());
    }

    @Test
    void fingerprintIsSha256Hex() {
        String fp = base().imageExtractionFingerprint();
        assertEquals(64, fp.length());
        assertTrue(fp.matches("[0-9a-f]{64}"));
    }
}
