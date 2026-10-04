package com.mhtextractor.gui.component;

import com.mhtextractor.baseline.SourceScanUtils;
import com.mhtextractor.util.ExtractOutcome;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExtractOutcomeStyles} 的机械证据（P6-A §3.3）：
 * <b>「提取结果 → 样式/颜色」的知识只有一处</b>，且与 {@code custom.css} 的类名对得上。
 *
 * <h2>本测试要挡的两条静默失效路</h2>
 * <ol>
 *   <li><b>搬家搬漏</b>：知识"部分留在原地"时编译通过、单测全绿、只有 UI 上某个颜色不对
 *       （或某人改了其中一份、另一份没改）—— 故断言两个搬运来源里<b>一个字面量都不剩</b>。</li>
 *   <li><b>搬家搬丢</b>：把常量删了但没在新类里补上（或改了名），表现为样式静默退回默认 ——
 *       故对 {@code ExtractOutcomeStyles} 本身做<b>阳性对照</b>，且把每个值逐个钉死。</li>
 * </ol>
 *
 * <h2>为什么扫源码要"剥注释但保留字面量"</h2>
 * <p>画值本身就是<b>字符串字面量</b>（{@code "#4CAF50"}），所以不能像
 * {@code GuiNotificationsIsSemanticFacadeOnlyTest} 那样连字面量一起剥。
 * 但注释必须剥：{@code MainController} 与 {@code PreviewListCell} 里都有描述配色的中文注释，
 * 里面写着 {@code #E81123} / {@code #2E7D32}（说的正是搬到本类里的那些颜色）。
 * 不剥注释就会<b>把注释判成代码</b> —— 那会逼着后人删掉解释性注释来让测试变绿，
 * 正是本项目最该避免的倒置。</p>
 *
 * <h2>🚩 有意保留的差异（本测试把它钉住，防止"顺手统一"）</h2>
 * <p>{@code SUCCESS_WARNED} 在两种介质上<b>不同色</b>是有意的（理由见
 * {@link ExtractOutcomeStyles} 的类注释）：进度条是批次级一根条（警告黄 {@code #fff9ae}），
 * 列表是条目级红/绿分割渐变。{@link #intentionalWarnedDifferenceIsPreserved()} 同时证明
 * "该一致的两处确实一致"（成功/失败）与"该不同的那处确实不同"（警告）。</p>
 */
class ExtractOutcomeStylesTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
    private static final Path GUI_CONTROLLER = MAIN_SOURCES.resolve("com/mhtextractor/gui/controller");
    private static final Path CONTROLLER = GUI_CONTROLLER.resolve("MainController.java");
    private static final Path CELL = GUI_CONTROLLER.resolve("PreviewListCell.java");
    private static final Path STYLES = MAIN_SOURCES.resolve("com/mhtextractor/gui/component/ExtractOutcomeStyles.java");
    private static final Path CSS = Path.of("src", "main", "resources", "custom.css");

    /** 进度条 4 个活动样式类名（= {@code custom.css} 的选择器名；改名会静默丢样式）。 */
    private static final List<String> PROGRESS_STYLE_NAMES = List.of(
            "progress-preparse", "progress-extract-success",
            "progress-extract-warned", "progress-extract-failed");

    /** "提取结果 → 列表行画值"用到的字面量（搬迁前全部在 {@code PreviewListCell}）。 */
    private static final List<String> OUTCOME_PAINT_NEEDLES = List.of(
            "#4CAF50", "#7AB57C", "#E81123", "#D75661", "#2E7D32", "#68976A", "linear-gradient");

    // ==================== 1. 值本身：与搬迁前的表达式逐值差分 ====================

    /**
     * <b>差分测试</b>：把搬迁前 {@code PreviewListCell.extractOutcomePaint} /
     * {@code extractOutcomeSelectedPaint} / {@code whiteText} 里的表达式<b>原样</b>写在这里，
     * 与 {@link ExtractOutcomeStyles} 的回答逐值比较。
     *
     * <p>为什么用"重述旧表达式"而不是只钉几个常量：后者只能覆盖我想到的取值；
     * 前者对 {@code ExtractOutcome.values()} 全量遍历 ⇒ 新增枚举常量时两侧一起被检查。
     * （而且 {@code toneOf} 是穷尽 switch，新增常量会先<b>编译失败</b>，不会静默漏掉。）</p>
     */
    @Test
    void answersMatchThePreRefactorExpressionsForEveryOutcomeValue() {
        for (ExtractOutcome o : ExtractOutcome.values()) {
            assertEquals(preRefactorPaint(o), ExtractOutcomeStyles.listPaint(o), o + "：未选中画值");
            assertEquals(preRefactorSelectedPaint(o), ExtractOutcomeStyles.listPaintSelected(o), o + "：选中画值");
            assertEquals(preRefactorWhiteText(o), ExtractOutcomeStyles.usesDarkBackdrop(o), o + "：深色底判定");
        }
        // null 也必须安全（迁移前 switch 会在 null 上抛 NPE，但调用点有 != NONE 守卫；
        // 新实现显式返回"无色调"，语义更宽且不改变任何既有调用点的结果）
        assertNull(ExtractOutcomeStyles.toneOf(null));
        assertNull(ExtractOutcomeStyles.listPaint(null));
        assertNull(ExtractOutcomeStyles.listPaintSelected(null));
        assertEquals(false, ExtractOutcomeStyles.usesDarkBackdrop(null));
        System.out.println("[styles] " + ExtractOutcome.values().length + " 个 ExtractOutcome 取值与搬迁前表达式逐值一致");
    }

    /** 三态分类（唯一实现点）：FAILED 与 SKIPPED 必须同调（搬迁前的 switch 就是 {@code case FAILED, SKIPPED}）。 */
    @Test
    void toneClassification() {
        assertEquals(ExtractOutcomeStyles.Tone.SUCCESS, ExtractOutcomeStyles.toneOf(ExtractOutcome.SUCCESS));
        assertEquals(ExtractOutcomeStyles.Tone.WARNED, ExtractOutcomeStyles.toneOf(ExtractOutcome.SUCCESS_WARNED));
        assertEquals(ExtractOutcomeStyles.Tone.FAILED, ExtractOutcomeStyles.toneOf(ExtractOutcome.FAILED));
        assertEquals(ExtractOutcomeStyles.Tone.FAILED, ExtractOutcomeStyles.toneOf(ExtractOutcome.SKIPPED));
        assertNull(ExtractOutcomeStyles.toneOf(ExtractOutcome.NONE));
    }

    /** 具体字面量（防止"差分两侧一起被改错"这种自洽的错）。 */
    @Test
    void paintLiteralsArePinned() {
        assertEquals("#4CAF50", ExtractOutcomeStyles.listPaint(ExtractOutcome.SUCCESS));
        assertEquals("#E81123", ExtractOutcomeStyles.listPaint(ExtractOutcome.FAILED));
        assertEquals("#E81123", ExtractOutcomeStyles.listPaint(ExtractOutcome.SKIPPED));
        assertEquals("linear-gradient(to right, #E81123 0%, #E81123 33%, #2E7D32 33%, #2E7D32 100%)",
                ExtractOutcomeStyles.listPaint(ExtractOutcome.SUCCESS_WARNED));
        assertEquals("#7AB57C", ExtractOutcomeStyles.listPaintSelected(ExtractOutcome.SUCCESS));
        assertEquals("#D75661", ExtractOutcomeStyles.listPaintSelected(ExtractOutcome.FAILED));
        assertEquals("linear-gradient(to right, #D75661 0%, #D75661 33%, #68976A 33%, #68976A 100%)",
                ExtractOutcomeStyles.listPaintSelected(ExtractOutcome.SUCCESS_WARNED));
    }

    /** 进度条活动样式清单：4 个、顺序与搬迁前 {@code removeAll(...)} 的实参顺序一致、只读。 */
    @Test
    void progressActivityStylesAreExactlyTheFourKnownNames() {
        assertEquals(PROGRESS_STYLE_NAMES, ExtractOutcomeStyles.progressActivityStyles());
        assertEquals(ExtractOutcomeStyles.PROGRESS_PREPARSE, ExtractOutcomeStyles.progressActivityStyles().get(0));
        assertEquals(ExtractOutcomeStyles.PROGRESS_EXTRACT_SUCCESS, ExtractOutcomeStyles.progressActivityStyles().get(1));
        assertEquals(ExtractOutcomeStyles.PROGRESS_EXTRACT_WARNED, ExtractOutcomeStyles.progressActivityStyles().get(2));
        assertEquals(ExtractOutcomeStyles.PROGRESS_EXTRACT_FAILED, ExtractOutcomeStyles.progressActivityStyles().get(3));
    }

    // ==================== 2. custom.css 类名存在性（机械检查，给原始输出） ====================

    /**
     * 4 个进度条样式类名必须<b>仍然存在于 {@code custom.css}</b>，且选择器形态与
     * JavaFX 25 的 {@code .progress-bar > .bar} 结构一致（{@code custom.css} 的注释记录过：
     * 旧写法 {@code .track > .bar} 从未匹配 —— 进度条配色长期未生效的根因）。
     */
    @Test
    void progressStyleClassNamesStillExistInCustomCss() throws IOException {
        assertTrue(Files.exists(CSS), "custom.css 不存在：" + CSS);
        String css = Files.readString(CSS, StandardCharsets.UTF_8);

        Map<String, String> found = new LinkedHashMap<>();
        for (String name : PROGRESS_STYLE_NAMES) {
            String selector = ".progress-bar." + name + " > .bar";
            int at = css.indexOf(selector);
            assertTrue(at >= 0,
                    "custom.css 里已找不到选择器 `" + selector + "` —— 样式类名被改名或删除了。"
                            + "改名是**静默失效**：Java 侧编译通过、测试全绿、进度条只是退回默认色。");
            found.put(name, "line " + (countLines(css, at)) + "  " + selector);
        }
        // 每个类名都必须真的带一条 -fx-background-color（否则"选择器在、颜色没了"也算静默失效）
        for (String name : PROGRESS_STYLE_NAMES) {
            assertTrue(progressBarColor(name) != null, name + " 的选择器里没有 -fx-background-color");
        }
        System.out.println("[styles] custom.css 类名存在性检查（" + found.size() + " / "
                + PROGRESS_STYLE_NAMES.size() + " 命中）：" + found);
    }

    /**
     * 🚩 <b>有意差异必须保留</b>：进度条 WARNED 是警告黄，列表 {@code SUCCESS_WARNED} 是红/绿分割渐变。
     *
     * <p>同一个测试同时钉住两侧：<b>该一致的两处</b>（成功 {@code #4CAF50}、失败 {@code #E81123}：
     * 进度条 CSS 与列表画值同值）与<b>该不同的那处</b>（警告）。后人若"顺手统一"警告色，
     * 这条断言会失败并指出这是设计而非疏漏。</p>
     */
    @Test
    void intentionalWarnedDifferenceIsPreserved() throws IOException {
        String progressSuccess = progressBarColor("progress-extract-success");
        String progressFailed = progressBarColor("progress-extract-failed");
        String progressWarned = progressBarColor("progress-extract-warned");
        String progressPreparse = progressBarColor("progress-preparse");

        // ---- 一致的两处（真的一致，故分类已合并、颜色各自留在自己的介质里） ----
        assertEquals(ExtractOutcomeStyles.listPaint(ExtractOutcome.SUCCESS), progressSuccess,
                "成功色两侧必须一致（custom.css 注释：提取成功=绿，同开始提取按钮）");
        assertEquals(ExtractOutcomeStyles.listPaint(ExtractOutcome.FAILED), progressFailed,
                "失败色两侧必须一致（custom.css 注释：有失败/跳过=Windows 错误红）");
        assertEquals(progressFailed, ExtractOutcomeStyles.listPaint(ExtractOutcome.SKIPPED),
                "跳过与失败必须同色（进度条侧 fail+skip 也折叠成同一档）");

        // ---- 有意不同的那一处 ----
        String listWarned = ExtractOutcomeStyles.listPaint(ExtractOutcome.SUCCESS_WARNED);
        assertTrue(listWarned.startsWith("linear-gradient"),
                "列表的成功+警告必须是红/绿分割渐变（条目级：一根渐变同时表达两个事实）");
        assertTrue(!listWarned.contains(progressWarned),
                "🚩 有意差异被合并了：列表的 SUCCESS_WARNED 渐变里出现了进度条的警告色 " + progressWarned
                        + "。这两种介质上的警告色**本来就不同**，理由见 ExtractOutcomeStyles 类注释"
                        + "（进度条是批次级一根条、无法分割，故用 Windows 警告黄）。请恢复差异，"
                        + "不要为了'统一'改这里。");
        assertEquals("#FFF9AE", progressWarned, "进度条警告色（Windows 警告黄）");

        System.out.println("[styles] 两侧对账：preparse=" + progressPreparse
                + " success=" + progressSuccess + "（列表同值） failed=" + progressFailed + "（列表同值）"
                + " warned(进度条)=" + progressWarned + " vs warned(列表)=" + listWarned + " ← 有意不同");
    }

    // ==================== 3. 知识只有一处（含阳性对照） ====================

    /**
     * 「提取结果 → 样式 / 颜色」的字面量与样式类名<b>只允许出现在两处</b>：
     * {@link ExtractOutcomeStyles}（Java 侧唯一知识点）与 {@code custom.css}
     * （进度条真实颜色的唯一介质；CSS 不受本类管辖，也不可能被 Java 合并）。
     *
     * <p>搬迁的两个来源 —— {@code MainController}（进度条样式类名 + {@code setProgressBarActivity} 的
     * 清场清单）与 {@code PreviewListCell}（3 份并行的结果分类与 6 个画值常量）——
     * 必须<b>一个字面量都不剩</b>。</p>
     *
     * <p><b>阳性对照</b>（同一次扫描必须在"应有命中"的取样点上命中，否则"0 命中"什么也证明不了）：
     * {@link ExtractOutcomeStyles} 自己必须命中全部 needls；{@code custom.css} 必须命中全部 4 个类名。</p>
     */
    @Test
    void outcomeStyleKnowledgeLivesInExactlyOneJavaPlace() throws IOException {
        // ---- 断言 1：两个搬迁来源都归零 ----
        for (Path src : List.of(CONTROLLER, CELL)) {
            assertTrue(Files.exists(src), "源码不存在：" + src);
            String code = SourceScanUtils.stripComments(Files.readString(src, StandardCharsets.UTF_8));

            List<String> styleHits = hits(code, PROGRESS_STYLE_NAMES);
            assertTrue(styleHits.isEmpty(),
                    src.getFileName() + " 里仍出现进度条样式类名字面量 " + styleHits
                            + " —— 它们唯一应当在 com.mhtextractor.gui.component.ExtractOutcomeStyles。");

            List<String> paintHits = hits(code, OUTCOME_PAINT_NEEDLES);
            assertTrue(paintHits.isEmpty(),
                    src.getFileName() + " 里仍出现提取结果画值字面量 " + paintHits
                            + " —— 「结果 → 颜色」的映射唯一应当在 ExtractOutcomeStyles"
                            + "（搬迁前 PreviewListCell 里同一套三态分类写了 3 份：两个 switch + 白字判定）。");
        }

        // ---- 断言 2（阳性对照 A）：新类自己必须命中全部 needls ----
        String stylesCode = SourceScanUtils.stripComments(Files.readString(STYLES, StandardCharsets.UTF_8));
        List<String> allNeedles = new ArrayList<>(PROGRESS_STYLE_NAMES);
        allNeedles.addAll(OUTCOME_PAINT_NEEDLES);
        List<String> missing = new ArrayList<>();
        for (String needle : allNeedles) {
            if (!stylesCode.contains(needle)) {
                missing.add(needle);
            }
        }
        assertTrue(missing.isEmpty(),
                "**阳性对照失败**：ExtractOutcomeStyles 里找不到这些字面量 " + missing
                        + "（期望全部命中）—— 说明扫描本身有问题，那么上面那条『两处来源 0 命中』"
                        + "什么也证明不了。");

        // ---- 断言 3（阳性对照 B）：custom.css 必须仍然带着 4 个选择器 ----
        String css = Files.readString(CSS, StandardCharsets.UTF_8);
        for (String name : PROGRESS_STYLE_NAMES) {
            assertTrue(css.contains(".progress-bar." + name),
                    "**阳性对照失败**：custom.css 里没有 .progress-bar." + name
                            + " —— 类名扫描失去对照物。");
        }

        System.out.println("[styles] 归零证据：MainController/PreviewListCell 对这 "
                + allNeedles.size() + " 个字面量命中 0；阳性对照 ExtractOutcomeStyles 命中 "
                + allNeedles.size() + " / " + allNeedles.size()
                + "，custom.css 命中 " + PROGRESS_STYLE_NAMES.size() + " / " + PROGRESS_STYLE_NAMES.size());
    }

    // ==================== 搬迁前的表达式（差分基准，勿改） ====================

    private static final String OLD_WARNED =
            "linear-gradient(to right, #E81123 0%, #E81123 33%, #2E7D32 33%, #2E7D32 100%)";
    private static final String OLD_WARNED_SELECTED =
            "linear-gradient(to right, #D75661 0%, #D75661 33%, #68976A 33%, #68976A 100%)";

    /** 搬迁前 {@code PreviewListCell.extractOutcomePaint} 的逐字表达式。 */
    private static String preRefactorPaint(ExtractOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> "#4CAF50";
            case SUCCESS_WARNED -> OLD_WARNED;
            case FAILED, SKIPPED -> "#E81123";
            default -> null;
        };
    }

    /** 搬迁前 {@code PreviewListCell.extractOutcomeSelectedPaint} 的逐字表达式。 */
    private static String preRefactorSelectedPaint(ExtractOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> "#7AB57C";
            case SUCCESS_WARNED -> OLD_WARNED_SELECTED;
            case FAILED, SKIPPED -> "#D75661";
            default -> null;
        };
    }

    /** 搬迁前 {@code PreviewListCell} 里 {@code whiteText} 判定的第一项（第三个分类拷贝）。 */
    private static boolean preRefactorWhiteText(ExtractOutcome outcome) {
        return outcome == ExtractOutcome.FAILED
                || outcome == ExtractOutcome.SKIPPED
                || outcome == ExtractOutcome.SUCCESS_WARNED;
    }

    // ==================== 扫描实现 ====================

    /** {@code custom.css} 里某个进度条活动样式类的基底色（大写，如 {@code #4CAF50}）。 */
    private static String progressBarColor(String styleName) throws IOException {
        String css = Files.readString(CSS, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile(
                        "\\.progress-bar\\." + Pattern.quote(styleName)
                                + "\\s*>\\s*\\.bar\\s*\\{\\s*-fx-background-color:\\s*(#[0-9a-fA-F]{6})\\s*;")
                .matcher(css);
        assertTrue(m.find(), "custom.css 里找不到 .progress-bar." + styleName
                + " > .bar 的 -fx-background-color（选择器或颜色缺失 = 静默丢样式）");
        return m.group(1).toUpperCase(Locale.ROOT);
    }

    private static int countLines(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** 命中的字面量（按给定顺序，去重）。 */
    private static List<String> hits(String code, List<String> needles) {
        List<String> found = new ArrayList<>();
        for (String needle : needles) {
            if (code.contains(needle) && !found.contains(needle)) {
                found.add(needle);
            }
        }
        return found;
    }

}
