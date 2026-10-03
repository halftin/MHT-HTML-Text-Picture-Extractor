package com.mhtextractor.gui.component;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q13 / P6-A① 的<b>机械证据</b>：{@link GuiNotifications} 只能是"语义门面"（选图标 + 拼键），
 * 不得自己碰 {@link NotificationHost} 的机制。
 *
 * <h2>为什么必须有这条断言</h2>
 * <p>{@code NotificationHost} 里"先 {@code applyCss()} → 再 {@code setContent()} → 最后 {@code show()}"
 * 的顺序、以及 {@code persistent} 语义，都是<b>踩出来的</b>：顺序反了的现象是
 * {@code display=true} 但高度 0、<b>屏幕上什么都没有</b> —— 卡片"设了却看不见"。
 * 那是<b>静默失效</b>：编译通过、单测全绿、只有用户看不见提醒。</p>
 *
 * <p>而 {@code tools/probes} 的三个提醒探针（{@code NotificationAcceptance} /
 * {@code BusyCloseProbe} / {@code PendingConfirmCloseProbe}）验的是
 * <b>{@code NotificationHost} 自己的行为</b>：只要门面老实转调，它们全绿，<b>无论门面里有没有
 * 第二份 {@code ModalPane} 实现</b>（那份实现可能只是当前没走到）。所以"探针全绿"证明不了
 * 门面干净 —— 这正是本断言存在的理由（与 {@code NoWebViewDependencyTest} 同一思路）。</p>
 *
 * <h2>口径：为什么扫源码而不是扫字节码</h2>
 * <p>本项目其余架构断言（{@code ArchitectureTest} / {@code NoWebViewDependencyTest}）扫<b>字节码常量池</b>，
 * 因为那能抓到"全限定名引用绕过 import"。本断言反过来选源码，理由是
 * <b>控制组必须能在源码上成立</b>：本类的控制组是一组既有<b>源码</b>
 * （{@code NotificationHost} + {@code App}，见 {@link #CONTROL_SOURCES}），它们确实含全部 7 个符号；
 * 而 {@code NotificationHost.class} 的常量池里 {@code javafx/stage/Stage} 与
 * {@code javafx/scene/Scene} <b>可能因编译优化而不出现</b> —— 控制组会假失败。
 * 源码 + 剥注释足以覆盖本断言的威胁模型（有人手写第二份实现），且控制组 7/7 可逐符号复算。</p>
 *
 * <p><b>剥注释是必须的</b>：{@link GuiNotifications} 的 javadoc 里必须能<b>说出</b>
 * 这些禁用符号（否则读代码的人不知道红线在哪）—— 那是本步最该留给后人的知识。
 * 不断言"出现过这个词"，只断言"代码里用到" —— 与
 * {@code ArchitectureTest.headlessTestPackagesHaveNoJavaFx} 同一教训。</p>
 *
 * <h2>这个"装置"本身被两侧各验过一次</h2>
 * <p>只验一侧就会留下两条假通过的路：扫描坏了也算过（那一侧靠<b>阳性对照</b>挡），
 * 剥注释剥过头把真符号一起剥掉也算过（那一侧靠<b>反控制组</b>挡）。两条都在下面：</p>
 * <ul>
 *   <li><b>阳性对照</b>：{@link #CONTROL_SOURCES} 是既有生产源码，7 个符号<b>逐个</b>都有对照物
 *       （符号→控制源的映射在测试输出里打印出来，可复算）。</li>
 *   <li><b>反控制组</b>：{@link #commentOnlySourceIsNotReported()} ——
 *       一块"只在注释与字符串里提到这些符号"的假源码<b>必须被判为通过</b>。
 *       否则 {@link GuiNotifications} 的 javadoc 会假失败，而"删注释让测试变绿"是本项目
 *       最该避免的倒置（修装置，不是修注释）。</li>
 * </ul>
 *
 * <p><b>⚠️ 本文档里的取舍</b>：本测试<b>不</b>声称覆盖"把 {@code Scene} 当字符串反射使用"这类绕法
 * （见 {@link #stripCommentsAndLiterals} 的说明）—— 那种写法在 JavaFX 里没有实际用途，
 * 而威胁模型是"有人图快复制一份实现"，复制一定会写标识符。</p>
 */
class GuiNotificationsIsSemanticFacadeOnlyTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
    private static final Path FACADE = MAIN_SOURCES.resolve(
            "com/mhtextractor/gui/component/GuiNotifications.java");
    /**
     * 控制组：<b>确实含这些提醒机制符号</b>的既有源码，用来证明"扫描器真的会抓到命中"。
     *
     * <p>为什么是<b>两个</b>文件而不是一个：{@code NotificationHost} 自己并<b>不</b>出现
     * {@code Stage}（它只认 {@code Scene}/锚点，窗口归调用方）；而"门面不许自己拿窗口"这条
     * 在现实中确实要挡（门面拿到 {@code Stage} 就说明它开始管窗口生命周期了）。
     * 硬凑一个"只含 6 个符号的控制组"会让第 7 个符号<b>不可复算</b> ——
     * 这正是本测试第一版犯的错（控制组当场把它抓了出来，见下）。</p>
     *
     * <p>{@code App} 是窗口/接线那一侧：它 {@code extends Application}、持有 {@code Stage} 与
     * {@code Scene}，并且是 {@code NotificationHost.install(...)} 的两个调用点之一。于是 7 个符号
     * <b>每一个</b>都在某个控制源里被实际命中过。</p>
     */
    private static final List<Path> CONTROL_SOURCES = List.of(
            MAIN_SOURCES.resolve("com/mhtextractor/gui/component/NotificationHost.java"),
            MAIN_SOURCES.resolve("com/mhtextractor/App.java"));

    /**
     * 图标扫描的<b>阳性对照</b>：{@code ParamPanelActions} 是<b>Q13 范围之外</b>的提醒调用点
     * （参数面板的配置 CRUD，留着给 P6 后续切块处理），它<b>现在</b>确实带着
     * <b>5 个不同的</b>用途图标（{@code SAVE / HAND_POINTER / FILE_DOWNLOAD / TRASH_ALT / MEMORY}，
     * 共 7 处实参）。
     *
     * <p><b>为什么必须是它</b>：{@code MainController} 的图标集合与
     * {@code NotificationHost} 的图标集合<b>根本不相交</b> —— 后者只有
     * {@code INFO_CIRCLE / EXCLAMATION_TRIANGLE / EXCLAMATION_CIRCLE / SPINNER / QUESTION_CIRCLE}
     * 这 5 个<b>默认</b>图标（"按用途给图标"的 22 个用途图标全在调用点里）。
     * 所以拿 {@code NotificationHost} 当图标扫描的控制组会<b>假失败</b>：
     * 它抓得到那 5 个默认图标，却抓不到门面用的 16 个用途图标 ——
     * 这正是本测试第二版犯的错（断言当场把它拦了下来）。</p>
     *
     * <p>用它做对照组的判别力正好：同一次扫描若在它身上命中 5 个常量、
     * 在 {@code MainController} 上命中 0 个，就证明"0 命中"是<b>真的没有</b>，
     * 而不是"正则坏了"。</p>
     */
    private static final Path ICON_SCAN_CONTROL = MAIN_SOURCES.resolve(
            "com/mhtextractor/gui/controller/ParamPanelActions.java");
    private static final int ICON_SCAN_CONTROL_MIN_HITS = 5;

    /**
     * 门面里禁止出现的符号（**7 个**，与 Q13 提示词 §0 的清单一一对应）。
     *
     * <p>类/方法都带边界：{@code ModalPane} 会命中 {@code StackPane}（若只查裸词），
     * {@code install(} 会命中 {@code uninstall(}；写成 {@code ModalPane} / {@code install(} 后仍然安全 ——
     * {@code StackPane} 不含 {@code ModalPane} 子串，{@code uninstall(} 也不含 {@code install(}
     * （它含 {@code nstall(}）。</p>
     */
    private static final List<String> FORBIDDEN_IN_FACADE = List.of(
            "ModalPane",            // 提醒宿主：门面不得自己建
            "install(",             // 接线：App.start() / ParamDialogController 各一次，门面不得重复
            "setContent(",          // showModal 的三步顺序之一
            "applyCss(",            // 让皮肤就位的必须先手
            "setPersistent(",       // 遮挡层/ESC 语义
            "Stage",                // 窗口
            "Scene");               // 场景

    /** 控制组必须被抓到的符号数（= 全部）。 */
    private static final int CONTROL_MUST_BE_CAUGHT = FORBIDDEN_IN_FACADE.size();

    @Test
    void facadeSourceContainsNoneOfTheNotificationHostMechanisms() throws IOException {
        assertTrue(Files.exists(FACADE), "门面源码不存在：" + FACADE);

        String facadeCode = stripCommentsAndLiterals(Files.readString(FACADE, StandardCharsets.UTF_8));

        // ---- 断言 1：门面里一个都不许有 ----
        List<String> offenders = hits(facadeCode, FORBIDDEN_IN_FACADE);
        assertTrue(offenders.isEmpty(),
                "GuiNotifications 必须是**纯语义门面**，但它的代码里出现了提醒机制符号："
                        + offenders + "\n"
                        + "这些机制属于 NotificationHost（applyCss → setContent → show 的顺序与 persistent "
                        + "语义是踩出来的）。在门面里复制一份 = 出现第二份实现，而失效是静默的"
                        + "（现象：display=true 但高度 0、屏幕上什么都没有）。\n"
                        + "正解：门面方法体只做『挑图标 + 挑 i18n 键 + 转调 NotificationHost.xxx(...)』。");

        // ---- 断言 2（控制组）：同一段扫描必须在控制源上抓到**每一个**符号 ----
        // 逐个符号对账，而不是只看总数：总数 6/7 也可能是"两个源合起来覆盖了另外 6 个"，
        // 那样第 7 个符号依然是不可复算的。
        Map<String, String> caughtBy = new LinkedHashMap<>();
        for (Path src : CONTROL_SOURCES) {
            assertTrue(Files.exists(src), "控制组源不存在：" + src);
            String code = stripCommentsAndLiterals(Files.readString(src, StandardCharsets.UTF_8));
            for (String needle : FORBIDDEN_IN_FACADE) {
                if (code.contains(needle)) {
                    caughtBy.putIfAbsent(needle, src.getFileName().toString());
                }
            }
        }
        List<String> uncaught = new ArrayList<>();
        for (String needle : FORBIDDEN_IN_FACADE) {
            if (!caughtBy.containsKey(needle)) {
                uncaught.add(needle);
            }
        }
        assertEquals(List.of(), uncaught,
                "**控制组失败**：同一次扫描在任何控制源里都没抓到这些符号："
                        + uncaught + "（已抓到 " + caughtBy.size() + " / " + CONTROL_MUST_BE_CAUGHT + "：" + caughtBy + "）\n"
                        + "说明扫描本身有问题（或这些符号已经不在任何生产代码里了）——"
                        + "那么上面那条『门面 0 命中』什么也证明不了。\n"
                        + "正解：给这个符号换一个有鉴别力的控制源（本项目先例：NoWebViewDependencyTest 的"
                        + " javafx/scene/text/TextFlow），或在确认该机制已彻底消失后把它从清单里移除。");

        System.out.println("[facade] " + FACADE.getFileName() + " 禁用符号命中 " + offenders.size() + " / "
                + FORBIDDEN_IN_FACADE.size() + "；控制组 " + CONTROL_MUST_BE_CAUGHT + " / "
                + CONTROL_MUST_BE_CAUGHT + " 逐个抓到（符号→控制源）：" + caughtBy);
    }

    /**
     * 图标决策只有一处：{@code MainController} 里<b>不再</b>出现 {@code FontAwesomeSolid}，
     * 而 {@link GuiNotifications} 仍然实实在在地传着图标（不是把图标弄丢了）。
     *
     * <p><b>为什么"删图标"也要挡</b>：卡片退化成默认中性图标是<b>纯外观退化</b> ——
     * 三个提醒探针只断言"卡片可见 / 回调触发 / 干完自动收"，
     * <b>不会</b>因为图标丢了而失败。所以"22 处图标搬进一处"与"22 处图标被删掉"
     * 在两个方向上都必须能被机械区分。</p>
     *
     * <p><b>控制组</b>：见 {@link #ICON_SCAN_CONTROL} 的说明 —— 它与两个"零命中"的取样点
     * （{@code MainController}）与"应有命中"的取样点（门面本身）一起跑同一段提取逻辑。</p>
     */
    @Test
    void iconDecisionLivesInExactlyOnePlace() throws IOException {
        Path controller = MAIN_SOURCES.resolve("com/mhtextractor/gui/controller/MainController.java");
        String controllerCode = stripCommentsAndLiterals(Files.readString(controller, StandardCharsets.UTF_8));

        int controllerIcons = countOccurrences(controllerCode, "FontAwesomeSolid");
        assertEquals(0, controllerIcons,
                "MainController 里仍出现 FontAwesomeSolid（" + controllerIcons + " 处）。"
                        + "提醒图标必须由 GuiNotifications 选（Q13 的判据是『图标与键的决策只有一处』）；"
                        + "控制器侧只表达意图（GuiNotifications.importFailed(...) 之类）。\n"
                        + "若确有**非提醒**用途的图标，请在本测试里显式登记并说明它为什么不属于提醒。");

        String facadeCode = stripCommentsAndLiterals(Files.readString(FACADE, StandardCharsets.UTF_8));
        Set<String> facadeIcons = iconConstants(facadeCode);
        assertFalse(facadeIcons.isEmpty(),
                "门面里一个 FontAwesomeSolid 常量都没找到 —— 提醒图标丢了（卡片会退化成默认中性图标）");

        // 阳性对照：同一段提取逻辑必须在"确实带图标"的提醒调用点上抓到命中。
        assertTrue(Files.exists(ICON_SCAN_CONTROL), "图标扫描的对照源不存在：" + ICON_SCAN_CONTROL);
        Set<String> controlIcons = iconConstants(
                stripCommentsAndLiterals(Files.readString(ICON_SCAN_CONTROL, StandardCharsets.UTF_8)));
        assertTrue(controlIcons.size() >= ICON_SCAN_CONTROL_MIN_HITS,
                "**控制组失败**：同一次扫描在 " + ICON_SCAN_CONTROL.getFileName() + " 上只命中 "
                        + controlIcons.size() + " 个 FontAwesomeSolid 常量（期望 ≥ "
                        + ICON_SCAN_CONTROL_MIN_HITS + "）—— 说明扫描坏了，"
                        + "那么上面那条『MainController 0 命中』什么也证明不了。");

        // "图标搬到一处"而不是"图标被搬走"：门面必须真的还带着一大把用途图标。
        Set<String> hostDefaults = iconConstants(stripCommentsAndLiterals(
                Files.readString(CONTROL_SOURCES.get(0), StandardCharsets.UTF_8)));
        assertTrue(facadeIcons.size() > hostDefaults.size(),
                "门面里的图标常量（" + facadeIcons.size() + "）不比 NotificationHost 的默认图标（"
                        + hostDefaults.size() + "）多 —— 用途图标很可能被删成了默认图标。\n"
                        + "门面应承载『按用途给图标』的那一批（CLAUDE.md 交互提醒约定的映射表）。");

        System.out.println("[facade] MainController FontAwesomeSolid 出现 " + controllerIcons
                + " 处；门面图标 " + facadeIcons.size() + " 个：" + facadeIcons
                + "；对照源 " + ICON_SCAN_CONTROL.getFileName() + " 命中 " + controlIcons.size()
                + " 个" + controlIcons + "；NotificationHost 默认图标 " + hostDefaults);
    }

    // ==================== 扫描实现 ====================

    /** 命中的禁用符号（按 {@code FORBIDDEN} 顺序，去重）。 */
    private static List<String> hits(String code, List<String> needles) {
        List<String> found = new ArrayList<>();
        for (String needle : needles) {
            if (code.contains(needle) && !found.contains(needle)) {
                found.add(needle);
            }
        }
        return found;
    }

    /**
     * {@code FontAwesomeSolid.X} 形式的常量名（**只看常量名**，不看类型名本身）。
     *
     * <p>⚠️ 正则带前导边界 {@code (?<![\w$.])}：否则 {@code ...typeIcon(FontAwesomeSolid ...)}
     * 这种<b>方法名以 {@code Icon} 结尾</b>的声明行会被当成 {@code FontAwesomeSolid.常量}
     * ——本测试第三版实测撞到过（{@code ParamPanelActions} 里的
     * {@code private void typeIcon(FontAwesomeSolid icon, ...)}）。
     * 那种假命中在图标集合上"恰好无害"（多出来的名字根本不是常量），但它会让
     * "常量统计"这个数字失去意义，也会让控制组的期望值对不上。</p>
     */
    private static Set<String> iconConstants(String code) {
        Set<String> icons = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?<![\\w$.])FontAwesomeSolid\\.([A-Z][A-Z0-9_]*)").matcher(code);
        while (m.find()) {
            icons.add(m.group(1));
        }
        return icons;
    }

    /**
     * <b>反控制组</b>：一块"只在注释与字符串字面量里提到这些禁用符号"的假源码，
     * 经同一段剥注释逻辑后<b>必须 0 命中</b>。
     *
     * <p><b>为什么它和阳性对照一样重要</b>：只加阳性对照时，装置还剩一条假通过的路 ——
     * "剥注释剥过头"（例如把 {@code //} 当成块注释起点，或把整个方法体吃掉），
     * 那时门面的 javadoc 会假失败。而<b>让测试变绿最省事的做法是删掉那段 javadoc</b> ——
     * 那是"为了迁就装置而损失文档"，与"往生产代码里塞字符串让扫描通过"是同一种倒置。
     * 有了这条，装置的两个方向都被钉住：该抓的（{@link #CONTROL_SOURCES}）抓得到，
     * 不该抓的（本方法）不误报。</p>
     *
     * <p>样例文本刻意包含：行注释、块注释、javadoc（方法上的）、字符串字面量里的符号，
     * 以及一个<b>合法</b>方法体（只调用 {@link NotificationHost}）。</p>
     */
    @Test
    void commentOnlySourceIsNotReported() {
        String synthetic = """
                package com.mhtextractor.gui.component;

                // ModalPane / install() / setContent() / applyCss() / setPersistent() / Stage / Scene
                /*
                 * 下面这些也只出现在块注释里：ModalPane、setContent(、applyCss(、
                 * setPersistent(、Stage、Scene。
                 */
                public final class CommentOnlySample {
                    private static final String NOTE = "ModalPane install( setContent( applyCss( setPersistent( Stage Scene";

                    /**
                     * javadoc 里同样只提名字：{@code ModalPane} / {@code applyCss()} / {@code Scene}。
                     */
                    public static void doNothing(javafx.scene.Node anchor, String message) {
                        com.mhtextractor.gui.component.NotificationHost.toastInfo(anchor, message);
                    }
                }
                """;

        List<String> offenders = hits(stripCommentsAndLiterals(synthetic), FORBIDDEN_IN_FACADE);
        assertEquals(List.of(), offenders,
                "**反控制组失败**：只在注释/字符串里提到禁用符号的假源码被判成了命中 " + offenders
                        + " —— 剥注释逻辑坏了。\n"
                        + "后果会很具体：GuiNotifications 的 javadoc 必须解释『为什么不得碰 ModalPane/applyCss』，"
                        + "若那段解释会让测试变红，下一个人就会把 javadoc 删掉来让它变绿 ——"
                        + "那是『修注释而不是修装置』，本测试的存在意义正好相反。");

        // 同一次调用里反向确认剥注释没有把整个源码剥空（否则"0 命中"又是空转）。
        String stripped = stripCommentsAndLiterals(synthetic);
        assertTrue(stripped.contains("NotificationHost.toastInfo"),
                "反控制组的假源码被剥注释逻辑剥空了（代码正文也没了）—— 装置坏了");
        System.out.println("[facade] 反控制组：仅注释/字符串含 7 个禁用符号的假源码 → 命中 "
                + offenders.size() + "，且代码正文保留（" + stripped.strip().split("\n").length + " 行）");
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
    }

    /**
     * 剥掉块注释、行注释与字符串/字符字面量（保留换行，保持行号可对应）。
     *
     * <p>与 {@code ArchitectureTest.stripComments} 同一手法，但多剥了字面量：
     * 本断言查的是 {@code ModalPane} / {@code Scene} 这类<b>标识符</b>，
     * 而门面 javadoc 里必然要写出它们（否则没人知道红线在哪）；
     * 字符串字面量同理（例如 i18n 键或日志文案里出现这些词并不构成依赖）。</p>
     *
     * <p>⚠️ 已知取舍：字面量被剥掉后，"把 {@code Scene} 当字符串反射使用"这类绕法扫不到。
     * 但那种写法在 JavaFX 里没有实际用途（要真拿到 {@code Scene} 就得 import/使用该类型，
     * 那时它在代码里就是标识符），故不为此加复杂度；威胁模型是"有人图快复制一份实现"，
     * 那种复制一定会写标识符。</p>
     */
    private static String stripCommentsAndLiterals(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            char ch = src.charAt(i);
            char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (ch == '/' && next == '/') {
                while (i < src.length() && src.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (ch == '/' && next == '*') {
                out.append("  ");
                i += 2;
                while (i < src.length() && !(src.charAt(i) == '*' && i + 1 < src.length()
                        && src.charAt(i + 1) == '/')) {
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < src.length()) {
                    out.append("  ");
                    i += 2;
                }
            } else if (ch == '"' || ch == '\'') {
                char quote = ch;
                out.append(' ');
                i++;
                while (i < src.length() && src.charAt(i) != quote) {
                    if (src.charAt(i) == '\\' && i + 1 < src.length()) {
                        out.append("  ");
                        i += 2;
                        continue;
                    }
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < src.length()) {
                    out.append(' ');
                    i++;
                }
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * 防"控制组腐化"：{@link #CONTROL_SOURCES} 是那条断言的力量来源 ——
     * 若日后有人把这些机制搬走/改名，控制组会（正确地）失败，但那时应当<b>换一个对照物</b>，
     * 而不是删掉控制组。本测试把这件事写成**可复算的第三个断言**，
     * 免得"控制组失败"被当成噪声顺手删掉。
     *
     * <p>另外它显式记录第二个控制源为什么存在：{@code NotificationHost} 自己<b>不</b>出现
     * {@code Stage}（窗口归调用方）—— 这是本测试第一版把控制源只设成 {@code NotificationHost}
     * 时被当场抓出来的事实，写进断言避免日后重犯。</p>
     */
    @Test
    void controlSourcesStillCarryEveryForbiddenSymbol() throws IOException {
        Map<String, List<String>> coveredBy = new LinkedHashMap<>();
        for (Path src : CONTROL_SOURCES) {
            assertTrue(Files.exists(src), "控制组源不存在：" + src);
            String code = stripCommentsAndLiterals(Files.readString(src, StandardCharsets.UTF_8));
            for (String needle : FORBIDDEN_IN_FACADE) {
                if (code.contains(needle)) {
                    coveredBy.computeIfAbsent(needle, k -> new ArrayList<>())
                            .add(src.getFileName().toString());
                }
            }
        }
        assertEquals(new TreeSet<>(FORBIDDEN_IN_FACADE), new TreeSet<>(coveredBy.keySet()),
                "控制源已不再覆盖全部禁用符号（每个符号至少要有一个对照物，否则该符号不可复算）："
                        + coveredBy + "\n本断言的控制组失效了，必须换一个有鉴别力的对照物。");
        System.out.println("[facade] 控制组覆盖 " + coveredBy.size() + " / " + FORBIDDEN_IN_FACADE.size()
                + " 个禁用符号（符号→控制源）：" + coveredBy);
    }
}
