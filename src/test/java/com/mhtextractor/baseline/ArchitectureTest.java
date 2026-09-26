package com.mhtextractor.baseline;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 架构不变量测试（会话 18 · E0.4，**宽松版**）。
 *
 * <p><b>为什么先宽松</b>：重构第一天就写"零 JavaFX 依赖"的硬断言必然全红，无法作为推进依据。
 * 本步只做一件有价值的事：<b>把现状白名单化并冻结</b> —— 白名单只减不增。
 * 这样从 E1 起每一步都能立刻看到"JavaFX 泄漏点少了一个 / 多了一个"。</p>
 *
 * <p><b>为什么扫字节码而不是源码</b>：源码扫描会被通配 import（{@code import javafx.scene.*}）与
 * 间接引用骗过；字节码常量池里的 {@code javafx/} 描述符是"编译后确实依赖"的铁证
 * （会话 17 测试方法设计 §4.4 也要求扫"编译产物或源码"，取更严的那一档）。</p>
 *
 * <p><b>归零目标（E6 收紧）</b>：</p>
 * <ul>
 *   <li>{@link #jawafxFreePackages()} 的 ALLOWED 收敛到 {@code 0} 个文件；</li>
 *   <li>{@code com.mhtextractor.app} 包出现且其依赖闭包零 javafx；</li>
 *   <li>{@link #mainControllerIsShrinking()} 的上限降到最终目标值。</li>
 * </ul>
 */
class ArchitectureTest {

    /** Maven 单测 CWD = 项目根 */
    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
    private static final Path TEST_SOURCES = Path.of("src", "test", "java");
    private static final Path MAIN_CLASSES = Path.of("target", "classes");

    /**
     * 本应"零 UI 框架依赖"的包 —— 这些是业务层。
     * 换 UI 框架时它们必须原样复用，因此任何 javafx 引用都是泄漏点。
     */
    private static final List<String> BUSINESS_PACKAGES = List.of(
            "com/mhtextractor/core",
            "com/mhtextractor/extractor",
            "com/mhtextractor/processor",
            "com/mhtextractor/output",
            "com/mhtextractor/cache",
            "com/mhtextractor/util",
            "com/mhtextractor/config",
            "com/mhtextractor/i18n",
            "com/mhtextractor/model",   // E2.1：PreviewItem / ThumbnailData 外迁至此，必须保持零 JavaFX
            "com/mhtextractor/app"      // 目标产物：E4 起建立，建立后自动纳入约束
    );

    /**
     * 现状白名单（**只减不增**）。
     *
     * <p>类名 → 计划拆除它的阶段。E1.1/E1.2 完成后本表必须清空。</p>
     *
     * <p><b>会话 18 教训</b>：这份名单最初只凭 {@code grep 'import javafx'} 得出，漏掉了
     * {@code DropResolver} 与 {@code OutputUnitPlanner} —— 它们<b>没有</b> import javafx，
     * 而是在方法签名里用了<b>全限定名</b> {@code javafx.collections.ObservableList}。
     * 这正是本测试扫字节码而非扫 import 的价值：源码 grep 会漏，常量池不会。</p>
     */
    private static final Map<String, String> JAVAFX_LEAK_ALLOWLIST = new LinkedHashMap<>();

    static {
        // 会话 18 · E1.1/E1.2 已把业务层 JavaFX 泄漏点降到 **0**，白名单清空。
        //
        // 值得记下的两点经验：
        // 1) 最初凭 `grep 'import javafx'` 只找到 2 个（GroupItem/GroupStructure），
        //    实际扫描发现 4 个 —— DropResolver 与 OutputUnitPlanner **不 import** javafx，
        //    而是在方法签名里用了全限定名 javafx.collections.ObservableList。扫字节码才抓得到。
        // 2) 那 2 个"额外"泄漏点其实是**传递性**的：它们调用 GroupItem.getMembers()，
        //    而该方法原先返回 ObservableList，于是常量池里出现了 javafx 描述符。
        //    GroupItem.getMembers() 改回 List 后，它们自动变干净 —— 即修 1 处解 3 处。
        //    这说明"按字节码依赖闭包判分层"比"按文件归属判分层"更接近真相。
    }

    /**
     * {@code MainController} 行数上限（**只减不增**）。
     *
     * <p><b>口径</b>：{@code Files.readAllLines}（= Java 的行分隔规则）。演进记录：</p>
     * <ul>
     *   <li>会话 18 起点：6111</li>
     *   <li>E1.1 接线监听器：6113；E1.3a 属性→字段：6174；E1.4 缩略图纯像素化：6252</li>
     *   <li><b>E0.6 外迁 1139 行演练/测试代码：5109</b>（净减 1143 —— 这是本阶段的目标达成点）</li>
     *   <li><b>E2.1 外迁 PreviewItem/ThumbnailData/Status 到 model 包：5002</b>（再减 107）</li>
     *   <li>E2.2-1 收口 13 处缓存规则调用到 `app` 服务：5001</li>
     *   <li><b>E2.2-2 删除 10 处冗余 imageMode 守卫：4981</b>（守卫语义移入服务，由 8 条单测锁定）</li>
     *   <li>E4-3 控制器改为逐条委托 {@code ExtractionPreconditions}（规则出 GUI 并真正被复用）：
     *       <b>4985</b>（+4）。这是**有意**的净增：换来"提取前置规则"成为 app 层具名、可单测、
     *       换 UI 可复用的资产，并由规则统一给出 WARNING/ERROR 级别（原先散落在控制器里 4 处）。
     *       注：最早的粗放写法使该块从 16 行涨到 33 行，已被改为 {@code Denial} 形态压回。</li>
     * </ul>
     *
     * <p><b>⚠️ 口径陷阱（会话 18 实测）</b>：同一文件用不同工具数出的行数<b>不一致</b> ——
     * PowerShell {@code (Get-Content f).Count} 与 Java {@code Files.readAllLines} 会给出不同结果
     * （本文件为纯 {@code LF} 换行）。**因此本常量只能与 {@code Files.readAllLines} 口径比较**。</p>
     */
    private static final int MAIN_CONTROLLER_MAX_LINES = 4985;

    /**
     * E0.6 留在 {@code MainController} 里的"测试支撑"暴露面（{@code /* test-support *​/} 标记数）上限。
     *
     * <p><b>这是什么</b>：把约 1140 行演练/测试代码外迁到 test sources 后，被驱动的成员必须从
     * {@code private} 放宽到包级，否则同包的 {@code FeatureExercise} 无法访问。本次共放宽
     * <b>86</b> 处（46 个状态/方法 + 39 个 {@code @FXML} 控件 + 1 个内部类 {@code PreviewListCell}）。
     * 这是<b>已知且有意</b>的封装让步，用它换取"生产类不再承载测试脚手架"。</p>
     *
     * <p><b>为什么单独立断言</b>：让步必须可度量、只减不增 —— 否则日后有人图省事继续放宽，
     * 会悄悄把 MainController 变成"什么都公开"的类。Layer B（无头驱动 API）落地并退役
     * {@code test.mode} 后，本常量应逐步降到 0。</p>
     *
     * <p><b>注意</b>：这些成员仍是<b>包级</b>（不是 public），包外不可见；且逐个带标记，
     * 便于检索与回收。</p>
     */
    private static final int TEST_SUPPORT_MARKER_MAX = 86;

    @Test
    void testSupportSurfaceDoesNotGrow() throws IOException {
        Path mc = MAIN_SOURCES.resolve("com/mhtextractor/gui/controller/MainController.java");
        String text = Files.readString(mc, StandardCharsets.UTF_8);
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf("/* test-support */", idx)) >= 0) {
            count++;
            idx += 1;
        }
        assertTrue(count <= TEST_SUPPORT_MARKER_MAX,
                "MainController 的测试支撑暴露面增长：" + count + " > " + TEST_SUPPORT_MARKER_MAX
                        + "。这些 private→包级 的放宽是为 E0.6 外迁测试代码而做的已知让步，只应减少；"
                        + "若确需新增，请同时更新本常量并说明为何无法用公开 API 替代。");
        System.out.println("[arch] MainController 测试支撑暴露面 " + count + " / 上限 "
                + TEST_SUPPORT_MARKER_MAX + "（Layer B 后应归零）");
    }

    /** 允许引用 JavaFX 的包（视图层/外壳） */
    private static final List<String> UI_PACKAGES = List.of(
            "com/mhtextractor/gui",
            "com/mhtextractor/logging"
    );

    /**
     * UI 框架包根名，<b>拼接构造</b>而非字面量。
     *
     * <p>原因（会话 18 实测）：字面量会让本测试类的源码自身含 {@code javafx} 子串，
     * 从而被 {@link #headlessTestPackagesHaveNoJavaFx()} 判为违规 ——
     * 该断言曾因方法名叫 {@code javafxFreePackages()} 而假失败。</p>
     */
    private static final String UI_FRAMEWORK_ROOT = "java" + "fx";

    // ==================== 1. 业务层零 JavaFX ====================

    @Test
    void businessPackagesHaveNoUiFrameworkDependency() throws IOException {
        Map<String, Set<String>> leaks = scanJavaFxRefs();

        List<String> unexpected = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : leaks.entrySet()) {
            if (!JAVAFX_LEAK_ALLOWLIST.containsKey(e.getKey())) {
                unexpected.add(e.getKey() + "  →  " + e.getValue());
            }
        }

        if (!unexpected.isEmpty()) {
            fail("业务层出现**新增** JavaFX 依赖（白名单只减不增）：\n  "
                    + String.join("\n  ", unexpected)
                    + "\n\n若这是有意的，必须在此测试的白名单中显式登记并说明拆除阶段（硬规则 R4）。");
        }

        // 反向检查：白名单里的类必须**仍然**是泄漏点 —— 否则说明它已被修好，
        // 白名单未同步清理，会掩盖后续新增泄漏（防止"白名单腐化"）
        List<String> stale = new ArrayList<>();
        for (String allowed : JAVAFX_LEAK_ALLOWLIST.keySet()) {
            if (!leaks.containsKey(allowed)) {
                stale.add(allowed + "（计划拆除阶段 " + JAVAFX_LEAK_ALLOWLIST.get(allowed) + "）");
            }
        }
        assertTrue(stale.isEmpty(),
                "以下类已不再是 JavaFX 泄漏点，请从 JAVAFX_LEAK_ALLOWLIST 移除（白名单腐化）：\n  "
                        + String.join("\n  ", stale));

        // 会话 18 · E1.1/E1.2 后业务层应**零** JavaFX 依赖。白名单已清空，
        // 这里显式断言归零（防止日后有人重新往白名单里塞条目来"绕过"）：
        // 新增任何业务层 JavaFX 依赖都会在上面的 unexpected 检查里直接失败。
        assertEquals(0, leaks.size(),
                "业务层应零 JavaFX 依赖，实际仍有泄漏点（E1 后白名单已清空，不应再出现）：\n  "
                        + leaks.keySet());

        System.out.println("[arch] 业务层 JavaFX 泄漏点 " + leaks.size() + " 个（白名单 "
                + JAVAFX_LEAK_ALLOWLIST.size() + " 个，目标 E6 归零）：" + leaks.keySet());
    }

    /**
     * 扫字节码：{@code target/classes} 下业务包内每个 .class 的常量池是否含 {@code javafx/}。
     *
     * <p>返回 类名(含包，'/' 分隔) → 命中的 javafx 描述符集合。</p>
     */
    private static Map<String, Set<String>> scanJavaFxRefs() throws IOException {
        assertTrue(Files.isDirectory(MAIN_CLASSES),
                "未找到编译产物 " + MAIN_CLASSES.toAbsolutePath()
                        + "，请先 mvn compile（本测试依赖字节码，不依赖源码）");
        Map<String, Set<String>> leaks = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(MAIN_CLASSES)) {
            List<Path> classFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .sorted()
                    .toList();
            for (Path p : classFiles) {
                String rel = MAIN_CLASSES.relativize(p).toString().replace('\\', '/');
                String className = rel.substring(0, rel.length() - ".class".length());
                if (!inPackages(className, BUSINESS_PACKAGES)) continue;
                if (inPackages(className, UI_PACKAGES)) continue;

                Set<String> hits = findJavaFxDescriptors(Files.readAllBytes(p));
                if (!hits.isEmpty()) {
                    leaks.put(className, hits);
                }
            }
        }
        return leaks;
    }

    /** 从 class 文件字节中提取出现的 UI 框架描述符（去重、限量输出） */
    private static Set<String> findJavaFxDescriptors(byte[] classBytes) {
        String needle = UI_FRAMEWORK_ROOT + "/";
        String asLatin1 = new String(classBytes, StandardCharsets.ISO_8859_1);
        Set<String> hits = new LinkedHashSet<>();
        int idx = 0;
        while ((idx = asLatin1.indexOf(needle, idx)) >= 0) {
            int end = idx;
            while (end < asLatin1.length()) {
                char c = asLatin1.charAt(end);
                if (c == ';' || c == '(' || c == ')' || c == '[' || c == '<' || c == '>'
                        || c == ' ' || c < 0x20 || c > 0x7e) {
                    break;
                }
                end++;
            }
            String desc = asLatin1.substring(idx, Math.min(end, idx + 120));
            if (desc.length() > 4 && hits.size() < 8) hits.add(desc);
            idx = Math.max(end, idx + 1);
        }
        return hits;
    }

    private static boolean inPackages(String className, List<String> packages) {
        for (String pkg : packages) {
            if (className.startsWith(pkg + "/") || className.equals(pkg)) return true;
        }
        return false;
    }

    /** 白名单内的类必须真实存在于业务包内（防止把 UI 包误当业务包约束） */
    @Test
    void allowlistOnlyTargetsBusinessPackages() throws IOException {
        for (String cls : JAVAFX_LEAK_ALLOWLIST.keySet()) {
            assertTrue(inPackages(cls, BUSINESS_PACKAGES),
                    "白名单条目不在业务包内：" + cls + "（该断言防止把视图层误纳入业务层约束）");
            assertTrue(Files.exists(MAIN_SOURCES.resolve(cls + ".java")),
                    "白名单条目源码不存在：" + cls);
        }
    }

    // ==================== 2. Controller 只减不增 ====================

    @Test
    void mainControllerIsShrinking() throws IOException {
        Path mc = MAIN_SOURCES.resolve("com/mhtextractor/gui/controller/MainController.java");
        assertTrue(Files.exists(mc), "MainController 源码不存在：" + mc);
        long lines = Files.readAllLines(mc, StandardCharsets.UTF_8).size();
        assertTrue(lines <= MAIN_CONTROLLER_MAX_LINES,
                "MainController 行数反弹：" + lines + " > 上限 " + MAIN_CONTROLLER_MAX_LINES
                        + "（本上限只减不增；若确有新增职责，说明分层退化）");
        System.out.println("[arch] MainController 行数 " + lines + " / 上限 " + MAIN_CONTROLLER_MAX_LINES);
    }

    // ==================== 3. 无头测试不得依赖 JavaFX ====================

    /**
     * {@code baseline} 与 {@code app} 测试包必须零 JavaFX <b>代码</b>引用 ——
     * 它们是"业务可无头运行"的证据载体。如果它们引用了 JavaFX，"无头可跑"就是假的。
     *
     * <p><b>为什么先剥注释</b>：本测试类自身（{@code ArchitectureTest}）的 javadoc 里必须提到
     * {@code javafx} 才能解释它在断言什么。若不做注释剥离，这条断言会把自己的说明文字当成违规 ——
     * 会话 18 首次运行就是这样假失败的。**断言"代码引用"，不是断言"出现过这个词"。**</p>
     */
    @Test
    void headlessTestPackagesHaveNoJavaFx() throws IOException {
        // 本文件是"元测试"：它断言业务层不许依赖 UI 框架，其 javadoc 必须能说出这件事，
        // 且它必须列出 Maven 坐标（org.openjfx:javafx-*）才能校验依赖白名单。
        // 跳过自身是诚实做法 —— 剩下的断言仍覆盖真正的无头载体（BaselineCases/BaselineHasher 等）。
        String self = "ArchitectureTest.java";

        List<String> offenders = new ArrayList<>();
        for (String pkg : List.of("com/mhtextractor/baseline", "com/mhtextractor/app")) {
            Path dir = TEST_SOURCES.resolve(pkg);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    if (p.getFileName().toString().equals(self)) continue;
                    String code = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                    int hit = code.indexOf(UI_FRAMEWORK_ROOT);
                    if (hit >= 0) {
                        String ctx = code.substring(Math.max(0, hit - 60), Math.min(code.length(), hit + 60))
                                .replace('\n', '|');
                        offenders.add(TEST_SOURCES.relativize(p).toString().replace('\\', '/')
                                + "\n      … " + ctx + " …");
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "无头测试包不得引用 JavaFX（注释除外）：\n  " + String.join("\n  ", offenders));
    }

    /** 极简注释剥离：块注释 → 空行占位；行注释 → 去尾。字符串字面量里的 javafx 仍会被捕获（保守）。 */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        boolean inLine = false;
        boolean inBlock = false;
        boolean inString = false;
        while (i < src.length()) {
            char ch = src.charAt(i);
            char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (inLine) {
                if (ch == '\n') {
                    inLine = false;
                    out.append(ch);
                } else {
                    out.append(' ');
                }
            } else if (inBlock) {
                if (ch == '*' && next == '/') {
                    inBlock = false;
                    out.append("  ");
                    i += 2;
                    continue;
                }
                out.append(ch == '\n' ? '\n' : ' ');
            } else if (inString) {
                out.append(ch);
                if (ch == '\\') {
                    if (i + 1 < src.length()) out.append(src.charAt(++i));
                } else if (ch == '"') {
                    inString = false;
                }
            } else if (ch == '/' && next == '/') {
                inLine = true;
                out.append("  ");
                i += 2;
                continue;
            } else if (ch == '/' && next == '*') {
                inBlock = true;
                out.append("  ");
                i += 2;
                continue;
            } else if (ch == '"') {
                inString = true;
                out.append(ch);
            } else {
                out.append(ch);
            }
            i++;
        }
        return out.toString();
    }

    // ==================== 4. 业务层不得产出 UI 文案（V3，先只做度量） ====================

    /**
     * 度量业务层对 {@code I18n} 的直接引用数（现状 46 处，见 {@code docs/重构-耦合点分类表.md} #6）。
     * E3.1 完成后降为 0（章名等"产物本地化"除外）。本步只断言"不增长"。
     */
    @Test
    void businessLayerI18nUsageDoesNotGrow() throws IOException {
        int count = 0;
        Map<String, Integer> perFile = new LinkedHashMap<>();
        for (String pkg : BUSINESS_PACKAGES) {
            if (pkg.equals("com/mhtextractor/i18n") || pkg.equals("com/mhtextractor/app")) continue;
            Path dir = MAIN_SOURCES.resolve(pkg);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    int n = countOccurrences(text, "I18n.");
                    if (n > 0) {
                        perFile.put(MAIN_SOURCES.relativize(p).toString().replace('\\', '/'), n);
                        count += n;
                    }
                }
            }
        }
        // 现状基线：以本测试自身的实测输出为准（会话 18 首次运行即打印真实值再锁定）。
        // 注：文档 §耦合点 #6 记的"46 处"口径不同（含 GuiState 与否、是否计注释未说明）；
        // 本测试的实测口径为「businessPackages 内所有 'I18n.' 字面出现次数」= 36。
        int currentBaseline = 36;
        assertTrue(count <= currentBaseline,
                "业务层 I18n 引用增长：" + count + " > " + currentBaseline
                        + "（E3.1 目标是把它们改为警告码，只减不增）");
        System.out.println("[arch] 业务层 I18n. 引用 " + count + " 处：" + perFile);
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
    }

    // ==================== 5. 禁止新增第三方依赖（R5） ====================

    /**
     * 目标产物必须能编成 GraalVM native EXE，因此不得引入新依赖。
     * 允许清单为 pom.xml 现有依赖（会话 18 快照）。
     *
     * <p>坐标里的 UI 框架名用 {@code String.join} 拼接，避免本测试类源码出现该字面量
     * （否则会被 {@link #headlessTestPackagesHaveNoJavaFx()} 的同名规则误判）。</p>
     */
    @Test
    void noNewThirdPartyDependencies() throws IOException {
        String fx = UI_FRAMEWORK_ROOT;
        Set<String> allowed = new TreeSet<>(List.of(
                String.join(":", "org.openjfx", fx + "-controls"),
                String.join(":", "org.openjfx", fx + "-fxml"),
                "org.projectlombok:lombok",
                "com.sun.mail:jakarta.mail",
                "com.fasterxml.jackson.core:jackson-databind",
                "com.fasterxml.jackson.datatype:jackson-datatype-jsr310",
                "org.jsoup:jsoup",
                "org.glavo:webp",
                "org.slf4j:slf4j-api",
                "ch.qos.logback:logback-classic",
                "io.github.mkpaz:atlantafx-base",
                "org.kordamp.ikonli:ikonli-" + fx,
                "org.kordamp.ikonli:ikonli-fontawesome5-pack",
                "org.junit.jupiter:junit-jupiter"
        ));

        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        Set<String> actual = new TreeSet<>();
        // 只解析 <dependency> 块内的 groupId/artifactId（不引入 XML 解析依赖）
        int idx = 0;
        while ((idx = pom.indexOf("<dependency>", idx)) >= 0) {
            int end = pom.indexOf("</dependency>", idx);
            if (end < 0) break;
            String block = pom.substring(idx, end);
            String g = extractTag(block, "groupId");
            String a = extractTag(block, "artifactId");
            if (g != null && a != null) actual.add(g + ":" + a);
            idx = end + 1;
        }

        Set<String> added = new TreeSet<>(actual);
        added.removeAll(allowed);
        assertTrue(added.isEmpty(),
                "检测到新增第三方依赖（违反 R5，会影响 GraalVM native 构建）：\n  "
                        + String.join("\n  ", added)
                        + "\n若确需引入，必须同时更新本测试的允许清单并说明 native 元数据影响。");

        Set<String> removed = new TreeSet<>(allowed);
        removed.removeAll(actual);
        assertTrue(removed.isEmpty(),
                "以下依赖已从 pom.xml 移除，请同步更新本测试允许清单：\n  "
                        + String.join("\n  ", removed));
    }

    private static String extractTag(String block, String tag) {
        String open = "<" + tag + ">";
        int i = block.indexOf(open);
        if (i < 0) return null;
        int j = block.indexOf("</" + tag + ">", i);
        if (j < 0) return null;
        return block.substring(i + open.length(), j).strip();
    }

    /**
     * E2.2 不变式：**GUI 层不得直接调用缓存规则方法**，必须经
     * {@code com.mhtextractor.app.WorkspaceParamService} 收口。
     *
     * <p><b>为什么值得一条断言</b>：耦合点 #11 的本质是"每条参数/移除分支自己记得调一次规则" ——
     * 这正是"分支漏调"类 bug 的温床。E2.2 把控制器里的 13 处调用收口到服务后，若日后有人图快又直接在
     * 控制器里调 {@code previewCacheLifecycle.onParamsChangedBatch(...)}，就会重新打开这个缺口。
     * 本测试把"收口"从一次性动作变成**持续约束**。</p>
     *
     * <p><b>为什么只管 GUI 层，不管业务服务</b>：本测试首版曾扫描整个 {@code com/mhtextractor}，
     * 立刻抓出 {@code processor/ImageBatchProcessor} 直接调用规则 (b)
     * （{@code previewCache.onExtractionCompleted(path)}）—— 但那是<b>合法</b>的：
     * 该处理器是业务服务，缓存生命周期是它**注入的依赖**，规则 (b)"提取完成即删缓存"本就属于它的职责，
     * 且它在无头单测中被直接驱动（{@code ImageBatchProcessorTest}）。
     * 因此真正的不变式是"<b>GUI 不得直接调规则</b>"，而不是"全项目只许一个调用者"。</p>
     */
    @Test
    void guiLayerDoesNotInvokeCacheRulesDirectly() throws IOException {
        Path guiPkg = Path.of("src", "main", "java", "com", "mhtextractor", "gui");
        List<String> ruleMethods = List.of(
                "onParamsChanged", "onParamsChangedBatch", "onItemsRemoved", "onItemRemoved",
                "onExtractionCompleted");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(guiPkg)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String rel = guiPkg.relativize(p).toString().replace('\\', '/');
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String code = line.split("//")[0];
                    for (String m : ruleMethods) {
                        if (code.contains("." + m + "(")) {
                            offenders.add(rel + "  ->  " + code.trim());
                        }
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "GUI 层不得直接调用缓存规则方法（须经 com.mhtextractor.app.WorkspaceParamService）：\n  "
                        + String.join("\n  ", offenders));
        System.out.println("[arch] GUI 层未直接调用缓存规则（已收口到 WorkspaceParamService）");
    }

    /**
     * 验收 V1 的**闭合性**断言：无头应用层不得依赖视图层/外壳。
     *
     * <p><b>为什么必须有这条</b>：{@link #businessPackagesHaveNoUiFrameworkDependency()} 只扫
     * {@code app} 包**自身**的字节码常量池。若有人在 {@code app} 里写
     * {@code import com.mhtextractor.gui.controller.MainController}，
     * 那条断言**不会**发现 —— 因为 JavaFX 描述符出现在 {@code gui} 包的类里，而 gui 包是被允许的。
     * 于是 {@code app} 就"看起来零 JavaFX"，实则通过 {@code MainController} **传递依赖**了 JavaFX，
     * 换 UI 框架时整个应用层随之失效。</p>
     *
     * <p>本断言从**源码导入**层面禁止 {@code app} 依赖 {@code gui} / {@code logging}
     * （日志基础设施 {@code logging.TextAreaAppender} 同样直接依赖 JavaFX，不得进入无头闭包）。</p>
     *
     * <p>允许的依赖方向：{@code app → {core, extractor, processor, output, cache, util, config, i18n, model}}。</p>
     */
    @Test
    void applicationLayerDoesNotDependOnViewOrShell() throws IOException {
        Path appDir = Path.of("src", "main", "java", "com", "mhtextractor", "app");
        assertTrue(Files.isDirectory(appDir), "未找到 app 包：" + appDir);

        List<String> forbidden = List.of(
                "com.mhtextractor.gui.",
                "com.mhtextractor.logging.",
                "com.mhtextractor.App");   // 外壳类自身（Application 子类）

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(appDir)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String rel = appDir.relativize(p).toString().replace('\\', '/');
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String code = line.split("//")[0].trim();
                    if (!code.startsWith("import ")) continue;
                    for (String bad : forbidden) {
                        if (code.contains(bad)) {
                            offenders.add(rel + "  ->  " + code);
                        }
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "无头应用层（app）不得依赖视图层/外壳，否则会**传递依赖 JavaFX** 而 V1 失效：\n  "
                        + String.join("\n  ", offenders));
        System.out.println("[arch] app 层未依赖 gui/logging/App（V1 闭合）");
    }

    // ==================== 6. 分层前提：基准测试必须真的在跑 ====================

    /**
     * 防空转保险：{@code ProductBaselineTest} 必须存在且基线文件数 > 0。
     * 否则"基线全绿"可能只是因为它被重命名/禁用了。
     */
    @Test
    void baselineHarnessIsPresent() {
        Path harness = TEST_SOURCES.resolve("com/mhtextractor/baseline/ProductBaselineTest.java");
        assertTrue(Files.exists(harness), "基线测试缺失：" + harness);
        Path baselines = Path.of("src", "test", "resources", "baselines");
        assertTrue(Files.isDirectory(baselines), "基线目录缺失：" + baselines);
        try (Stream<Path> list = Files.list(baselines)) {
            long n = list.filter(p -> p.getFileName().toString().endsWith(".json")).count();
            assertTrue(n > 0, "基线目录内没有任何 .json 基线文件（疑似被清空）");
            assertEquals(0, n % 2, "基线文件数应为偶数（每例 zh+en 各一份），实际 " + n);
            System.out.println("[arch] 基线文件 " + n + " 份");
        } catch (IOException e) {
            fail("读取基线目录失败：" + e.getMessage());
        }
    }
}
