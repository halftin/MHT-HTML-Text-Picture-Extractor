package com.mhtextractor.i18n;

import com.mhtextractor.baseline.SourceScanUtils;
import com.mhtextractor.core.ChapterGenerator;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.util.OutputUnitPlanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 会话 16 验收测试（I5 + I9，纯逻辑零 JavaFX）：
 * <ul>
 *   <li>I5 产物随语言：en locale 下章节名 {@code Chapter N}、默认组名 {@code Group N}、
 *       默认输出名 {@code Output Files}；zh locale 下保持中文原样。</li>
 *   <li>I9 缺键可发现：遍历活跃 UI 键（{@code I18n.get("…")} 字面量 + FXML {@code %key} +
 *       {@link ErrorCode} 键），断言 zh/en 两种 locale 下渲染结果均不含 {@code !key!} 形态。</li>
 * </ul>
 */
class I18nAcceptanceTest {

    private static final Pattern BANG = Pattern.compile("^![A-Za-z0-9_.]+!$");
    /** 两个入口都要扫：{@code I18n.get}（具名占位符）与 {@code I18n.msg}（日志位置占位符） */
    private static final Pattern GET_KEY = Pattern.compile("I18n\\.(?:get|msg)\\(\\s*\"([^\"]+)\"");
    private static final Pattern FXML_KEY = Pattern.compile("%([a-zA-Z][a-zA-Z0-9_.]*)");

    @AfterEach
    void resetLocale() {
        I18n.setLocale(null);
    }

    // ==================== I5：产物随语言 ====================

    @Test
    void chapterNameFollowsLocale() {
        I18n.setLocale(new Locale("en", "US"));
        assertEquals("Chapter 1", ChapterGenerator.generateChapterName(0));
        assertEquals("Chapter 12", ChapterGenerator.generateChapterName(11));
        I18n.setLocale(new Locale("zh", "CN"));
        assertEquals("第一章", ChapterGenerator.generateChapterName(0));
        assertEquals("第十二章", ChapterGenerator.generateChapterName(11));
    }

    @Test
    void defaultNamesFollowLocale() {
        I18n.setLocale(new Locale("en", "US"));
        assertEquals("Group1", I18n.get("core.groupDefault") + 1);
        assertEquals("Output Files", I18n.get("core.defaultOutputName"));
        I18n.setLocale(new Locale("zh", "CN"));
        assertEquals("组1", I18n.get("core.groupDefault") + 1);
        assertEquals("输出文件", I18n.get("core.defaultOutputName"));
    }

    @Test
    void plannerBlankGroupNameFallsBackToEnglishGroup() {
        I18n.setLocale(new Locale("en", "US"));
        GroupItem<Item> g = new GroupItem<>(5, "   ");
        g.getMembers().add(new Item("x\\1.mht", "C:\\src"));
        List<OutputUnitPlanner.OutputUnit> units =
                OutputUnitPlanner.plan(new ArrayList<>(List.of(g)), VIEW, false, null, null, "out");
        assertEquals(1, units.size());
        assertEquals("Group5", units.get(0).getOutputName());
        assertEquals(new File("out", "Group5.txt").getPath(), units.get(0).getOutputPath());
    }

    private record Item(String path, String source) {
    }

    private static final OutputUnitPlanner.ItemView<Item> VIEW = new OutputUnitPlanner.ItemView<>() {
        @Override
        public String path(Item item) {
            return item.path();
        }

        @Override
        public String sourceFolder(Item item) {
            return item.source();
        }

        @Override
        public boolean removed(Item item) {
            return false;
        }

        @Override
        public com.mhtextractor.config.AppConfig textItemParams(Item item) {
            return null;
        }

        @Override
        public com.mhtextractor.config.AppConfig imageItemParams(Item item) {
            return null;
        }
    };

    // ==================== I9：缺键可发现 ====================

    /**
     * 活跃 UI 键 = 主代码中 {@code I18n.get("…")} 字面量 + 两个 FXML 的 {@code %key} +
     * ErrorCode 的 {@code error.*} 键。两种 locale 下渲染均不得出现 {@code !key!} 形态。
     */
    @Test
    void activeUiKeysRenderWithoutBangMarkerInBothLocales() throws IOException {
        Path root = projectRoot();
        assumeTrue(root != null, "未找到项目根（pom.xml）——跳过源码扫描");

        Set<String> keys = new LinkedHashSet<>();
        try (var stream = Files.walk(root.resolve("src/main/java"))) {
            for (Path p : stream.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".java")).toList()) {
                // 先剥注释：javadoc 里的示例（如 I18n.msg("log.x", a, b)）不是"活跃键"，
                // 若不剥，示例会被当成真实键去查表并报缺键 —— 这正是本断言引入 I18n.msg 后第一次运行踩到的假失败。
                String src = SourceScanUtils.stripComments(Files.readString(p, StandardCharsets.UTF_8));
                Matcher m = GET_KEY.matcher(src);
                while (m.find()) {
                    keys.add(m.group(1));
                }
            }
        }
        try (var stream = Files.walk(root.resolve("src/main/resources"))) {
            for (Path p : stream.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".fxml")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                Matcher m = FXML_KEY.matcher(src);
                while (m.find()) {
                    keys.add(m.group(1));
                }
            }
        }
        for (ErrorCode code : ErrorCode.values()) {
            keys.add(code.key());
        }

        assertFalse(keys.isEmpty(), "应至少扫到若干活跃键");
        int failures = 0;
        for (String key : keys) {
            I18n.setLocale(new Locale("zh", "CN"));
            String zh = I18n.get(key);
            I18n.setLocale(new Locale("en", "US"));
            String en = I18n.get(key);
            if (BANG.matcher(zh).matches() || BANG.matcher(en).matches()
                    || zh.isBlank() || en.isBlank()) {
                failures++;
                System.err.println("[I9] 键 " + key + " 渲染异常：zh=" + zh + " en=" + en);
            }
        }
        assertEquals(0, failures, failures + " 个活跃键在两 locale 下渲染异常（缺键/空值）");
    }

    /** 从 user.dir 向上找 pom.xml，确定项目根（Surefire 以 basedir 为工作目录）。 */    private static Path projectRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
