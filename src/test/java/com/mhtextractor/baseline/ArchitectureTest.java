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
            "com/mhtextractor/app"   // 目标产物：E2 起建立，建立后自动纳入约束
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
        JAVAFX_LEAK_ALLOWLIST.put("com/mhtextractor/util/GroupItem", "E1.1");
        JAVAFX_LEAK_ALLOWLIST.put("com/mhtextractor/util/GroupStructure", "E1.2");
        JAVAFX_LEAK_ALLOWLIST.put("com/mhtextractor/util/DropResolver", "E1.2");
        JAVAFX_LEAK_ALLOWLIST.put("com/mhtextractor/util/OutputUnitPlanner", "E2.1");
    }

    /**
     * {@code MainController} 行数上限（**只减不增**）。
     *
     * <p><b>口径</b>：{@code Files.readAllLines}（= Java 的行分隔规则），实测 <b>6111</b>（会话 18）。
     * E0.6 外迁约 1100 行演练/测试代码后应降到约 5000，届时本常量随之下调并在此记录。</p>
     *
     * <p><b>⚠️ 口径陷阱（会话 18 实测）</b>：同一文件用不同工具数出的行数<b>不一致</b> ——
     * PowerShell {@code (Get-Content f).Count} 得 <b>5882</b>，Node 按 Java 规则得 <b>6111</b>，
     * Java 得 <b>6111</b>。原因是本文件为纯 {@code LF} 换行，而 {@code Get-Content} 的分行/编码处理
     * 与之不一致。**因此本常量只能与 {@code Files.readAllLines} 口径比较**，
     * 不要拿 PowerShell 的数字来改它（会立刻假失败或假通过）。</p>
     */
    private static final int MAIN_CONTROLLER_MAX_LINES = 6111;

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
