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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /** P4-3 / 第 17 条：仅扫描显式加载的后端子集，不裁剪 JavaFX 版存量元数据。 */
    @Test
    void headlessNativeMetadataHasNoJavaFxEntries() throws IOException {
        Path subset = Path.of("tools", "native", "headless");
        assertTrue(Files.isRegularFile(subset.resolve("reachability-metadata.json")),
                "后端子集元数据缺失：" + subset);
        var forbidden = java.util.regex.Pattern.compile("javafx[./\\\\]",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        List<String> violations = new ArrayList<>();
        int count = 0;
        try (Stream<Path> walk = Files.walk(subset)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".json") && !name.endsWith(".properties")) continue;
                String text = Files.readString(p, StandardCharsets.UTF_8);
                // JSON 先解析再扫描：同样拦住 \\u 转义隐藏的类型名/资源路径。
                if (name.endsWith(".json")) {
                    text = new com.fasterxml.jackson.databind.ObjectMapper().readTree(text).toString();
                }
                count++;
                if (forbidden.matcher(text).find()) violations.add(subset.relativize(p).toString());
            }
        }
        assertTrue(violations.isEmpty(), "后端 native 子集含 JavaFX 条目：" + violations);
        System.out.println("[arch] headless native metadata: " + count + " files / 0 JavaFX entries"
                + " (decoded JSON + properties text, javafx/ and javafx.)");
    }

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
            "com/mhtextractor/app",     // 目标产物：E4 起建立，建立后自动纳入约束
            "com/mhtextractor/engine",  // P-F9：预览缓存与提取执行，必须保持零 UI 框架依赖
            "com/mhtextractor/platform", // 步骤 5：与操作系统交互的基础设施（BrowserLauncher 迁出 gui.util）
            "com/mhtextractor/uicore",   // P2-1：ui-core = 框架无关的前端共用逻辑，同样零 JavaFX
            "com/mhtextractor/backend"   // P4-1：无头后端 CLI 骨架（--once / --serve），同样零 JavaFX
    );

    /**
     * I18n 基线扫描范围（P2-2b 修订，2026-10-06）：{@link #BUSINESS_PACKAGES} 剔除 {@code uicore} 与
     * {@code uiport}。
     *
     * <p><b>口径</b>：这两个包（uiport 为将来的端口层）是<b>前端共用逻辑 / 端口</b>，不是业务层；
     * 本测试的 I18n 基线拦的是"业务层拼用户数据文案"（方案 §14 决策 13 也只约束<b>端口层</b>不得引用
     * I18n，与本文档口径一致），前端共用逻辑不该计入。P2-2 把 {@code ParamTargetText} 等用 I18n 的类
     * 迁入 uicore 后，若仍按 {@link #BUSINESS_PACKAGES} 遍历，计数会 45 → 52 恰好顶到基线（属口径误算）。
     * 零 JavaFX 扫描<b>仍包含</b>它们（见 {@link #BUSINESS_PACKAGES}）。</p>
     */
    private static final List<String> I18N_COUNTED_PACKAGES = BUSINESS_PACKAGES.stream()
            .filter(p -> !p.contains("uicore") && !p.contains("uiport"))
            .toList();

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
     *   <li><b>步骤 2 把预览缓存业务逻辑搬入 {@code app.PreviewService}：4935</b>（净减 50 —— 首次回落）。
     *       删掉 {@code loadOrWritePreviewCache}（52 行），{@code onPreviewEffect} 改为调服务并把代次
     *       检查经 {@code Supplier<Boolean>} 传给服务（+2），故净减 50。搬迁前是"零余量"状态，
     *       此后本上限随之收紧到 4935，继续保持只减不增。</li>
     *   <li><b>步骤 3 把 430 行内部类 {@code PreviewListCell} 外迁为顶层类：4435</b>（净减 500）。
     *       单元格（430 行）+ 私有接口 {@code CellIndicator}（7）+ 纯几何的 {@code dropZone}（13，
     *       移入 {@code DropResolver.zoneFor} 并获得单测）+ 只被单元格使用的 28 个表现层常量与
     *       {@code rowStyle}（37）搬出；控制器只留下 {@code createPreviewCell()} 工厂与一处
     *       {@code PreviewListCell} 引用。</li>
     *   <li><b>2026-09-27 落地一批"预先存在的未提交清理"（死代码删除 + Java 25 匿名变量 + Lombok
     *       访问器）：4443</b>（+8，**不是新增职责**）。该批改动在提交前已于工作区完成，并通过
     *       GUI 自动化 zh 30/0 与 395/396 单测；其 +8 全部来自该批改动**自己引入**的 5 行 Lombok
     *       {@code @Getter/@Setter} 注解与 2 行 import（另 1 行为方法签名调整），
     *       同期它还删除了 {@code showAlertNonModal} 等真死代码。当时为让构建可复现而把上限同步到
     *       实测值，并在 javadoc 中显式标注为**临时**让步。</li>
     *   <li><b>同日晚，「WorkspaceModel 接入生产」阶段 1b/2 的减行把它收了回来：4396 → 上限回到
     *       4435</b>。即那次临时让步只在"清理提交"这一个提交内存在，随后被真实的结构性减行
     *      （模型共享数据序后删除重复的只读遍历、移除路径与拆分组逻辑）抵消并留出余量，
     *      因此**上限已恢复到清理前的 4435，不再是 4443**。</li>
     *   <li><b>2026-09-27 阶段 2/3 完成：4334 → 上限 4370</b>。首次**刻意不顶格**，留 36 行余量，
     *       理由见下方"零余量的代价"。</li>
     * </ul>
     *
     * <p><b>2026-09-27 阶段 2/3 完成后再收紧，并首次刻意留出余量：4334 → 上限 4370</b>。
     * 本会话实测到"零余量"的真实代价：一次常规清理（净 +8 行）直接把构建打红，
     * 而它的功能正确性已被 GUI 30/0 与 395/396 单测证明 —— 护栏没有拦住任何缺陷，
     * 只拦住了一次改进。故本次**不顶格**：留 36 行余量，上限仍比清理前的 4435 低 65 行，
     * "只减不增"的方向不变，但不再要求"先删后加"这种为迁就常量而扭曲的工作顺序。
     * 若日后确需上调，请先确认那不是"职责增长"—— 那才是本条断言真正要拦的东西。</p>
     *
     * <p><b>⚠️ 口径陷阱（2026-09-27 查明根因）</b>：同一文件用不同工具数出的行数<b>不一致</b> ——
     * 根因是 PowerShell 5.1 的 Get-Content 不带 -Encoding 时按系统 ANSI(GBK) 读取 UTF-8 无 BOM 文件，
     * DBCS 解码回退会吞掉"以非 ASCII 字符结尾那一行"的换行符。同类场景实测：
     * 默认 Get-Content = 4277 行，-Encoding UTF8 = Files.readAllLines = 4443 行。
     * 因此本常量只能与 Files.readAllLines 口径比较，且数行/读源码一律显式指定 UTF-8。</p>
     *
     * <p><b>演进记录（F，2026-10-03）：4370 → 2240</b>。C2–E2 把列表交互、分组命令、
     * 移除/撤销/窗口生命周期、预解析与信息展示、参数面板、预览渲染/加载、提取任务编排
     * 相继外迁为包级协作者后，实测行数从 4052 降至 <b>2215</b>；本上限收紧为
     * "当前实测 + 25 行余量"（"只减不增"方向不变，余量仅容后续小修）。</p>
     */
    private static final int MAIN_CONTROLLER_MAX_LINES = 2240;

    /**
     * 留在 {@code MainController} 里的"测试支撑"暴露面（{@code /* test-support *​/} 标记数）上限 ——
     * <b>P7 起为 0</b>：生产控制器不再为测试开放任何状态面。
     *
     * <p><b>这是什么</b>：E0.6 把约 1140 行演练/测试代码外迁到 test sources 后，被驱动的成员必须从
     * {@code private} 放宽到包级，否则同包的 {@code FeatureExercise} 无法访问。当时共放宽
     * <b>86</b> 处（46 个状态/方法 + 39 个 {@code @FXML} 控件 + 1 个内部类 {@code PreviewListCell}），
     * 每处带一个 {@code /* test-support *​/} 标记，供本断言"只减不增"地度量。
     * 那是"用已知且可度量的封装让步，换取生产类不再承载测试脚手架"的有意交易。</p>
     *
     * <p><b>为什么归零</b>：让步的终局是"不再需要让步"。P7 把 44 处剩余标记全部摘除，做法不是
     * 改成 {@code public}（那只会让状态面更宽），而是：</p>
     * <ol>
     *   <li><b>真正需要跨类协作的成员留在包级</b>（{@code ws} / {@code items} / {@code ITEM_VIEW} /
     *       {@code performDrop} / {@code effectiveForObject}）—— 它们是
     *       {@code WorkspaceFxAdapter} / {@code PreviewListCell} 这类<b>同包生产协作类</b>要用的，
     *       属于"为生产放宽"而不是"为测试放宽"，所以不再带标记（标记的语义边界见下）。</li>
     *   <li><b>其余 39 处收回 {@code private}</b>（{@code textParamsCache} / {@code parseToken} /
     *       {@code running()} / {@code extractStats()} / {@code allVisibleItems()} … 全部可变状态与查询）。
     *       测试侧改为经测试源集的驱动类访问 —— 该通道在 C3b 收敛为
     *       {@code com.mhtextractor.gui.controller.AcceptanceDriver} 的<b>同包直访（零反射）</b> ——
     *       代价落在测试代码里，生产类一行也不为测试让步。</li>
     * </ol>
     *
     * <p><b>因此本断言现在的含义</b>：{@code MainController} 源码里<b>不允许出现该标记</b>。
     * 它不再度量"曝光了多少"，而是直接禁止"再开一扇为测试的门"。要把测试支撑面挪到别的 GUI 类，
     * 这道闸门不会拦（它只扫 {@code MainController} 这一个文件）—— 见下方"覆盖范围的边界"。</p>
     *
     * <p><b>演进记录</b>：</p>
     * <ul>
     *   <li>E0.6 起点：<b>86</b>。</li>
     *   <li><b>步骤 1 阶段 A（2026-09-26）：86 → 64</b>。逐个核对 {@code FeatureExercise} 的引用后，
     *       有 <b>22 个 {@code @FXML} 控件字段从未被它引用</b>（全仓库其余源文件也零引用），
     *       因此改回 {@code private} 并摘除标记。这些字段仍由 {@code FXMLLoader} 注入 ——
     *       对 private 字段同样有效，GUI 自动化 30/30 未变。</li>
     *   <li><b>步骤 1 阶段 B（2026-09-26）：64 → 62</b>。用"哑而稳"的审计脚本
     *       （{@code .claude-work/s1b-audit.js}：逐条取标记后的成员名，再数 {@code FeatureExercise} 里的
     *       {@code mc.<名>} 引用）复核全部 64 条，发现 <b>2 条标记已失效</b>：
     *       {@code logTextArea}（类外零引用）与 {@code wrapper}（{@code PreviewListCell} 的私有字段）。
     *       同一轮还发现 {@code modeToggleGroup} 的标记已失效（它在前一批已改为 {@code private}）。</li>
     *   <li><b>步骤 1 阶段 C（2026-09-26）：62 → 46</b>。把 {@code FeatureExercise} 里
     *       {@code mc.<字段>} 的直接引用（125 处）改为 {@code Node.lookup("#fx:id")}，于是
     *       <b>16 个 @FXML 字段</b>不再需要包级可见性，全部收回 {@code private} 并摘除标记。
     *       为什么 lookup 可行（实测）：42 个 {@code fx:id} 中 41 个可解析，唯一例外
     *       {@code modeToggleGroup} 是 {@code ToggleGroup} 而<b>非</b> {@code Node}（它也不在测试中使用）；
     *       且 {@code lookup} 对 {@code visible=false} / {@code managed=false} / {@code disabled=true}
     *       的节点同样有效。</li>
     *   <li><b>步骤 3（2026-09-26）：46 → 44</b>（下降），但有 12 个成员被<b>有意放宽为包级且不加标记</b>
     *       —— 见下方"标记的语义边界"。</li>
     *   <li><b>P7（2026-10-02）：44 → 0</b>。见上"为什么归零"。同轮把 {@code PreviewListCell} 里
     *       唯一一处同类标记（{@code statusLabel}）也摘除（该字段类外零引用，本就该是 {@code private}）——
     *       它从来不在本断言的管辖范围内，见下。</li>
     * </ul>
     *
     * <p><b>⚠️ 标记的语义边界（步骤 3 明确）</b>：{@code /* test-support *​/} 标记的是
     * <b>"为测试夹具放宽的封装让步"</b>。步骤 3 把 430 行的 {@code PreviewListCell} 外迁为顶层类后，
     * 它需要 {@code MainController} 的 12 个成员（{@code draggedObject} / {@code dropHandled} /
     * {@code dragActive} / {@code listAnimating} / {@code lastDragOverCell} / {@code imageModeToggle} /
     * {@code onCellPressed} / {@code onCellDoubleClicked} / {@code onGroupCheckToggled} /
     * {@code isTextMode} / {@code unitIndex} / {@code cancelDrag}）。这些是
     * <b>生产类之间的同包协作</b>，<b>不是测试脚手架</b>，故<b>刻意不加标记</b> ——
     * 否则会把"为测试放宽"与"为同包生产类放宽"混成一回事。P7 沿用同一口径：{@code ws} / {@code items} /
     * {@code ITEM_VIEW} / {@code performDrop} / {@code effectiveForObject} 五个成员保留包级且不带标记。</p>
     *
     * <p><b>⚠️ 覆盖范围的边界</b>：本计数只覆盖 {@code MainController} 一个文件
     * （实现就是对该文件文本做 {@code indexOf} 计数）。类内新增同名标记会被拦住；但把测试支撑面
     * 挪到别的 GUI 类则<b>不会被拦</b> —— 谁要把暴露面搬家，请另立断言。P7 起
     * {@code MainController} 的测试访问通道只有一条：测试源集的
     * {@code com.mhtextractor.gui.controller.AcceptanceDriver}（<b>同包直访，无反射</b>），
     * 它住在 {@code src/test/java}，不进生产 jar、不进 native 镜像。
     * F 阶段（2026-10-03）由 {@link #reflectionAgainstMainControllerIsZero()} 固化该形态。</p>
     *
     * <p><b>审计脚本自身的教训</b>：第一版审计脚本用"取行内最后一个标识符"来判定字段名，把
     * {@code final Foo bar = new Foo();} 解析成了类型名 {@code Foo} —— 产生了一批<b>假阳性</b>
     * （把实际被引用的成员报成"未被引用"）。改用<b>行首锚定的类型+名字</b>正则后 0 条 UNPARSED。
     * 这与 {@code CLAUDE.md} 记录的"正则漏掉分号结尾的枚举常量"是同一类错误。</p>
     */
    private static final int TEST_SUPPORT_MARKER_MAX = 0;

    // ==================== P0 三条 UI 可切换耦合闸门（方案 §8 新增不变式 1–3） ====================
    // 三条基线均「只减不增，P1–P3 逐阶段下调，P3 目标 0」—— 它们是 UI 可切换重构的耦合闸门：
    // 换 UI 框架时 uiport 端口层必须零 JavaFX，gui 层不得继续持有业务状态、不得直连业务包。

    /** 闸门 1：uiport 包 JavaFX 违规类数上限 —— 恒为 0（P3 建立 uiport 后必须零 JavaFX，否则换 UI 框架失效）。 */
    private static final int UIPORT_JAVAFX_VIOLATION_MAX = 0;

    /**
     * 闸门 2：gui 层业务状态持有/构造点上限（只减不增，P1–P3 逐阶段下调，P3 目标 0）。
     * 基线 0 = (a) AppConfig/Map&lt;String,AppConfig&gt; 字段 0（P1-3 参数剪贴板下沉 app 层
     * {@code ParamClipboard}，-2）+ (b) 预览缓存构造 0（P1-2 下沉 app 层 {@code PreviewCacheFactory}）
     * + (c) 参数剪贴板构造 0（P1-3 构造下沉 app 层 {@code ParamClipboardFactory}，-1）
     * + (d) 撤销载荷构造 0（口径：{@code new UndoManager.Record(...)}（含 {@code <>}/泛型实参）；
     * 逐条清单见 {@link GuiCoupling#scanBusinessState}）。
     */
    private static final int GUI_BUSINESS_STATE_MAX = 0;

    /**
     * 闸门 3：gui 层直连业务包 distinct (包, 类型) 数上限（只减不增，P1–P3 逐阶段下调，P3 目标 0）。
     * 基线 34（P1-2 预览缓存构造下沉 app 层 {@code PreviewCacheFactory}，-1）；
     * P3-3b 产物命名规则下沉 app 层 {@code OutputNameService}，-2（SourceKinds / ImageBatchProcessor
     * 仅剩 {@code WorkspaceInfoPresenter} 引用，清单见 {@link GuiCoupling#distinctBusinessTypes}）；
     * P3-3b-2 预览缓存算键与写锁下沉 app 层 {@code PreviewService}，-2（PreviewCacheKey / SourceFingerprint）；
     * P3-3b-3 提取计划下沉 app 层 {@code ExtractionPlanService}，-1；
     * P3-3b-4 查看源码导出下沉 app 层 {@code SourceViewService}，-1（HtmlExportBuilder 退出 gui）；
     * P3-3b-5 会话状态持久化下沉 app 层 {@code SessionStateService}，-2（GuiState / GuiStateManager 退出 gui）；
     * P3-3b-6 预览渲染命名/扩展名下沉 app 层 {@code PreviewNamingService}，-3（FileWriter / ImageWriter / SanitizeUtils 退出 gui）；
     * P3-3b-8 命名配置与像素打包下沉 app 层 {@code NamedConfigService} / {@code PreviewPixelService}，
     * -2（ConfigManager / ThumbnailPixels 退出 gui）；
     * P3-3b-12 列表选择规则下沉 app 层 {@code SelectionRules}，-1（SelectionState 退出 gui）；
     * P3-3b-13 提取结果回写下沉 app 层 {@code ExtractResultService}，-1（FileResult 退出 gui）；
     * P3-3b-14 提取结果枚举迁入 model 包 {@code ExtractOutcome}，-1（ExtractOutcome 退出 gui）；
     * P3-3b-16 预览会话缓存生命周期经 {@code app.PreviewSession} 门面收口，
     * -1（PreviewCacheLifecycle 退出 gui）；
     * P3-3b-18 分组结构规则经 {@code app.WorkspaceStructure} 收口，-1（GroupStructure 退出 gui）；
     * P3-3b-21 {@code DropResolver} 与 {@code UndoManager} 迁入 app 包，-2（两者退出 gui 直连）；
     * P3-3b-22 提取统计经 app 层中性 DTO {@code ExtractionStats} 收口，-1（BatchProcessor 退出 gui 直连）；
     * P3-3b-24 文案能力经 app 层门面 {@code Texts} 收口，-1（I18n 退出 gui 直连）；
     * P3-3b-25 {@code AppConfig} 迁入 model 包，-1（AppConfig 退出 gui 直连，归零）。
     */
    private static final int GUI_DIRECT_BUSINESS_TYPES_MAX = 0;

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
                "MainController 又出现了测试支撑暴露面：" + count + " > " + TEST_SUPPORT_MARKER_MAX
                        + "。P7 起生产控制器不再为测试开放任何状态面（上限已归零）：测试要读的状态/控件"
                        + "应经测试源集的 com.mhtextractor.gui.controller.AcceptanceDriver（同包直访，无反射）访问，"
                        + "或做成真正的公开行为（如 MainViewActions），不得再把 private 放宽成包级并打标记。");
        System.out.println("[arch] MainController 测试支撑暴露面 " + count + " / 上限 "
                + TEST_SUPPORT_MARKER_MAX + "（P7 起归零）");
    }

    /**
     * 「测试侧不得反射 MainController」固化断言（F 阶段收口；C3b 曾把驱动反射归零，D-3 要求固化为不变式）。
     *
     * <p><b>背景</b>：P7 起生产控制器不再为测试开放状态面（见 {@link #testSupportSurfaceDoesNotGrow()}）。
     * 测试要读生产状态的正路只有两条 —— 真正的公开行为（如 {@code WorkspaceModel} API）与
     * 测试源集同包直访的 {@code gui.controller.AcceptanceDriver}（GUI 自动化驱动，无反射）。
     * 本断言把"对 {@code MainController} 私有成员 / 私有内部记录的反射 = 0"固化为不变式。</p>
     *
     * <p><b>三段口径</b>：</p>
     * <ol>
     *   <li><b>驱动零反射</b>：{@code AcceptanceDriver} 内不得出现任何反射原语 ——
     *       同包直访（而非反射）正是它取代旧 {@code MainViewTestBridge} 的原因。</li>
     *   <li><b>MainController 目标归零</b>：任何测试文件（剥注释后）若<b>同时</b>出现反射原语与
     *       {@code MainController} 代码引用，即视为对 MainController 的反射 —— 违规。
     *       文件级近似是刻意的：跨行构造目标（先取 {@code MainController.class} 再反射）也逃不掉，
     *       且 C3b 后实测所有含反射原语的文件剥注释后均无 {@code MainController} 代码引用。</li>
     *   <li><b>反射使用面冻结</b>：全部测试源码中，含反射原语的文件必须落在下面的白名单内
     *       （当前 4 个文件、10 处，针对 {@code AppConfig} / JavaFX 类加载探测 /
     *       WorkspaceModelTest 读取私有撤销栈，均与 MainController 无关）。
     *       新文件要引入反射必须显式扩白名单 —— 扩之前先想清楚是否真的需要、目标是不是 MainController。</li>
     * </ol>
     *
     * <p><b>自指豁免</b>：本测试类自身被扫描时必然命中（反射原语是它的<b>度量工具</b>，以字符串字面量
     * 存在于代码中；{@code MainController} 亦出现在其错误消息里）—— 与
     * {@link #headlessTestPackagesHaveNoJavaFx()} 曾因 javadoc 提 {@code javafx} 而假失败是同一类陷阱，
     * 故扫描显式跳过本文件。</p>
     */
    @Test
    void reflectionAgainstMainControllerIsZero() throws IOException {
        Path testRoot = Path.of("src", "test", "java");
        Path driver = testRoot.resolve("com/mhtextractor/gui/controller/AcceptanceDriver.java");
        assertTrue(Files.exists(driver), "GUI 自动化驱动缺失：" + driver);

        List<String> primitives = List.of(
                "getDeclaredField", "getDeclaredMethod", "getDeclaredFields", "getDeclaredMethods",
                "getDeclaredConstructor", "setAccessible", "Class.forName");
        Set<String> allowlist = Set.of(
                "com/mhtextractor/config/AppConfigCopyTest.java",
                "com/mhtextractor/app/EffectiveParamsServiceTest.java",
                "com/mhtextractor/app/LayerBTest.java",
                "com/mhtextractor/app/WorkspaceModelTest.java");

        String driverCode = SourceScanUtils.stripComments(Files.readString(driver, StandardCharsets.UTF_8));
        List<String> driverHits = primitives.stream().filter(driverCode::contains).toList();
        assertTrue(driverHits.isEmpty(),
                "GUI 自动化驱动 AcceptanceDriver 出现反射原语：" + driverHits
                        + " —— 驱动必须同包直访（P7 起生产控制器不再为测试开放状态面）");

        List<String> mcReflectors = new ArrayList<>();
        List<String> newReflectionUsers = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(testRoot)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String rel = testRoot.relativize(p).toString().replace('\\', '/');
                if (rel.endsWith("baseline/ArchitectureTest.java")) {
                    continue;   // 自指豁免：本文件的字符串字面量就是度量工具（见 javadoc）
                }
                String code = SourceScanUtils.stripComments(Files.readString(p, StandardCharsets.UTF_8));
                List<String> used = primitives.stream().filter(code::contains).toList();
                if (used.isEmpty()) {
                    continue;
                }
                if (code.contains("MainController")) {
                    mcReflectors.add(rel + "  ->  " + used);
                }
                if (!allowlist.contains(rel)) {
                    newReflectionUsers.add(rel + "  ->  " + used);
                }
            }
        }

        assertTrue(mcReflectors.isEmpty(),
                "测试源码对 MainController 的反射必须为 0（含私有成员 / 私有内部记录）：\n  "
                        + String.join("\n  ", mcReflectors));
        assertTrue(newReflectionUsers.isEmpty(),
                "测试源码新增反射使用面（白名单外文件）：\n  " + String.join("\n  ", newReflectionUsers)
                        + "\n 反射使用面已冻结在 4 个文件（AppConfigCopyTest / EffectiveParamsServiceTest / LayerBTest / WorkspaceModelTest，"
                        + "针对 AppConfig / JavaFX 类加载探测 / WorkspaceModel 私有撤销栈读取）。确需扩白名单时，先确认目标不是 MainController。");
        System.out.println("[arch] 测试侧反射：MainController 目标 0 / 驱动原语 0 / 白名单外文件 0");
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
        assertTrue(BUSINESS_PACKAGES.contains("com/mhtextractor/engine"),
                "engine 必须纳入业务包字节码扫描，不能通过删扫描范围绕过不变式");
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
        for (String pkg : List.of("com/mhtextractor/baseline", "com/mhtextractor/app", "com/mhtextractor/engine")) {
            Path dir = TEST_SOURCES.resolve(pkg);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    if (p.getFileName().toString().equals(self)) continue;
                    String code = SourceScanUtils.stripComments(Files.readString(p, StandardCharsets.UTF_8));
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

    // ==================== 4. 业务层不得产出 UI 文案（V3，先只做度量） ====================

    /**
     * 度量业务层对 {@code I18n} 的直接引用数（现状 46 处，见 {@code docs/重构-耦合点分类表.md} #6）。
     * E3.1 完成后降为 0（章名等"产物本地化"除外）。本步只断言"不增长"。
     *
     * <p><b>口径（2026-09-27 修订）</b>：只统计<b>非日志</b>引用 —— 先把 {@code log.*} /
     * {@code logger.*} 调用整段（含括号内全部实参）替换为空白，再数 {@code I18n.}。
     * 理由是"日志文案"与"业务数据文案"是两类东西：前者必须随界面语言走（用户就在界面的日志区里读它），
     * 不存在"改成码值、由表现层渲染"的替代路径；后者才是本断言真正要拦的。</p>
     */
    @Test
    void businessLayerI18nUsageDoesNotGrow() throws IOException {
        int count = 0;
        Map<String, Integer> perFile = new LinkedHashMap<>();
        for (String pkg : I18N_COUNTED_PACKAGES) {
            if (pkg.equals("com/mhtextractor/i18n") || pkg.equals("com/mhtextractor/app")) continue;
            Path dir = MAIN_SOURCES.resolve(pkg);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    String text = stripLogCalls(Files.readString(p, StandardCharsets.UTF_8));
                    int n = SourceScanUtils.countOccurrences(text, "I18n.");
                    if (n > 0) {
                        perFile.put(MAIN_SOURCES.relativize(p).toString().replace('\\', '/'), n);
                        count += n;
                    }
                }
            }
        }
        // 现状基线：以本测试自身的实测输出为准（会话 18 首次运行即打印真实值再锁定）。
        // 注：文档 §耦合点 #6 记的"46 处"口径不同（含 GuiState 与否、是否计注释未说明）；
        // 本测试的实测口径为「businessPackages 内所有 'I18n.' 字面出现次数」。
        //
        // 演进记录（步骤 5，2026-09-26）：36 → 37。**这不是新增耦合，而是口径扩大暴露出来的既有引用** ——
        // 步骤 5 把 BrowserLauncher 从 gui.util 迁入 com.mhtextractor.platform，并把该包加入本测试扫描的
        // BUSINESS_PACKAGES，于是它那处既有的 I18n.get("browser.sourceHtmlHead")（源码页的页头文案）
        // 首次被计入。实测：迁移前 36，迁移后 37，差值恰为该 1 处。
        //
        // ⚠️ 该处引用在架构上是**可疑的**（基础设施层不应产出面向用户的本地化文案），已登记为待清理项，
        // 见 docs/handoff/H25.md §五。彻底修法是让调用方（GUI）把文案作为参数传入。本例**有意保留原行为**，
        // 不顺手改（与步骤 5 的"包迁移"解耦，避免一次改动同时改变行为）。
        //
        // 演进记录（2026-09-27，日志与剩余文案国际化）：37 → 52。同一次改动里做了两件必须区分的事：
        //   ① **口径修订**：把 log/logger 调用整段剥掉再计数（见 stripLogCalls）。此前口径把日志文案
        //      也算进"业务层产文案"，而日志与业务数据文案是两类东西 —— 日志必须随界面语言走，
        //      没有"改成码值由表现层渲染"的替代路径。修订前的 207 处里有 155 处属日志。
        //   ② **+15 处真实新增**（修订后口径下的净增）：章节序号（ChapterNamingStrategy 5 处数字/前后缀键
        //      + WesternChapterNamingStrategy 1 处）、未分组单元标签（core.ungrouped）、
        //      处理汇总行（extract.summary）、导入汇总（import.summaryFiles/Dirs）、
        //      文本模式名（extract.mergedMode/separateMode）、图片归档失败与 MHT 加载失败
        //      （extract.fileArchiveFailedKeep / error.LOAD_MHT_FAILED）。
        //      它们此前是硬编码中文（正是"英文界面显示中文"的来源），属**修 bug**而非新增耦合。
        // 结论：本断言的意图（业务层不得继续"拼"用户数据文案）保持不变；只减不增的方向从 52 继续。
        //
        // 演进记录（2026-10-06，P2-2b 口径修正）：扫描范围剔除 uicore / uiport —— 它们是前端共用逻辑
        // （将来还有端口层），不是业务层。P2-2 把 ParamTargetText 等用 I18n 的类迁入 uicore 后
        // 计数 45 → 52 恰好顶到基线，属口径误算；修正后回落 **45**，基线维持 52 只减不增。
        int currentBaseline = 52;
        assertTrue(count <= currentBaseline,
                "业务层 I18n 引用增长：" + count + " > " + currentBaseline
                        + "（E3.1 目标是把它们改为警告码，只减不增）");
        System.out.println("[arch] 业务层 I18n. 引用 " + count + " 处：" + perFile);
    }

    /**
     * 把 {@code log.*} / {@code logger.*} 调用<b>整段</b>（含括号内全部实参）替换为空白，保留换行。
     *
     * <p>用括号深度扫描而不是正则：日志实参里常嵌着 {@code I18n.get(...)} / {@code String.format(...)}，
     * 正则要么漏切（留下一个 {@code I18n.}）要么切过头（吃掉后面的正常代码）。</p>
     */
    private static String stripLogCalls(String src) {
        StringBuilder out = new StringBuilder(src);
        int i = 0;
        while (i < src.length()) {
            int dot = src.indexOf('.', i);
            if (dot < 0) {
                break;
            }
            int start = -1;
            for (String recv : new String[]{"logger", "log"}) {
                int s = dot - recv.length();
                if (s >= 0 && src.startsWith(recv, s)
                        && (s == 0 || !Character.isJavaIdentifierPart(src.charAt(s - 1)))) {
                    start = s;
                    break;
                }
            }
            if (start < 0) {
                i = dot + 1;
                continue;
            }
            int open = src.indexOf('(', dot);
            if (open < 0) {
                i = dot + 1;
                continue;
            }
            String level = src.substring(dot + 1, open).trim();
            if (!List.of("trace", "debug", "info", "warn", "error").contains(level)) {
                i = dot + 1;
                continue;
            }
            int depth = 0;
            int j = open;
            boolean inStr = false;
            while (j < src.length()) {
                char c = src.charAt(j);
                if (inStr) {
                    if (c == '\\') {
                        j += 2;
                        continue;
                    }
                    if (c == '"') {
                        inStr = false;
                    }
                } else if (c == '"') {
                    inStr = true;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        j++;
                        break;
                    }
                }
                j++;
            }
            for (int k = start; k < Math.min(j, out.length()); k++) {
                if (out.charAt(k) != '\n') {
                    out.setCharAt(k, ' ');
                }
            }
            i = j;
        }
        return out.toString();
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
     *
     * <p><b>为什么 {@code startupCleanup} 不是第七条具名规则</b>：它是规则 (d)（{@code onShutdown}
     * "退出清空"）在<b>会话启动侧</b>的另一半 —— 清的是上一次会话未走完退出路径而留下的残留目录
     * （P7/P11 崩溃恢复：无条件删 {@code .tmp} 写入中目录与缺 {@code .complete} 的孤儿目录）；
     * 入口是会话生命周期接线（{@code MainViewInstaller.installPreviewCacheHooks}，与关窗清空处理器同处挂接），
     * 而六条规则由参数/移除/提取完成这类业务事件触发；因此它不进 {@code ruleMethods} 断言，
     * 也不需要 {@code WorkspaceParamService} 收口。</p>
     */
    @Test
    void guiLayerDoesNotInvokeCacheRulesDirectly() throws IOException {
        Path guiPkg = Path.of("src", "main", "java", "com", "mhtextractor", "gui");
        List<String> ruleMethods = List.of(
                "onParamsChanged", "onParamsChangedBatch", "onItemsRemoved", "onItemRemoved",
                "onExtractionCompleted", "onShutdown");

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
     * 「WorkspaceModel 接入生产」的**固化断言**：GUI 不得直接改动 workspace 数据序。
     *
     * <p><b>为什么必须有它</b>：迁移初期数据序列表是 GUI 与 {@code WorkspaceModel} <b>共享的同一个对象</b>
     * （见 {@code docs/handoff/H33.md} §二）。因此 GUI 里任何一处 {@code items.add(...)} 都会绕过
     * 模型的撤销记录与结构维护 —— 不会报错，只会**静默分叉**。收口工作把 GUI 的写入点降到了
     * <b>0</b>；本断言把那次的成果变成持续约束，防止日后有人图快又写回去。</p>
     *
     * <p><b>允许的形态</b>：读（{@code for (Object o : items)} / {@code items.indexOf(x)}）与
     * 模型转发（{@code ws.xxx(...)}）。禁止的是在数据序上做增删改调用。</p>
     *
     * <p><b>⚠️ 本断言的边界</b>：它只保证"GUI 里没有<b>直接</b>写数据序"。因此本方法还有<b>第二段
     * 断言</b>：GUI 不得把模型的数据序交给 {@code DropResolver} / {@code GroupStructure} 去改
     * （"间接写"）。</p>
     *
     * <p><b>间接写的历史（逐条记录）</b>：</p>
     * <ul>
     *   <li><b>接入生产时实测 2 处</b>，都在<b>有意不迁移</b>的拖拽路径（REORDER / MERGE_GROUPS）上：
     *       {@code DropResolver.apply(items, ...)}（正向改结构）与
     *       {@code GroupStructure.restoreStructure(items, ...)}（拖拽类撤销）。
     *       当时的理由是"它们是结构操作、与模型写法同构，且拖拽记录本就由 GUI 自己压自己用"，
     *       并把这个数字打印出来以便日后新增时能被看见（决策依据见 {@code docs/handoff/H33.md} §六.2）。</li>
     *   <li><b>Q0/P0 归零（2026-10-02）：2 处 → 0 处。</b>理由是该边界并不成立 ——
     *       间接写与直写一样绕过模型的撤销记录与结构维护，<b>不报错、只静默分叉</b>；
     *       且 {@code mainControllerIsShrinking} 只剩 9 行余量，后续任何"先加壳"的提交都会立刻打红。
     *       修法：正向走 {@code WorkspaceModel.applyResolvedDrop(op)}（内部完成
     *       快照 → {@code DropResolver.apply} → 重建父组指针 → 压记录，并把"受影响条目 + 其操作前父组"
     *       与合并载荷回传），撤销走 {@code WorkspaceModel.applyUndo(record)} 新增的
     *       {@code REORDER / MERGE_GROUPS} 分支（{@code GroupStructure.restoreStructure} +
     *       重建父组指针，受影响条目经 {@code applyUndoAffected()} 取）。
     *       {@code DropResolver.apply} 的签名与语义<b>未变</b>（{@code DropResolverTest} 的 35 条断言依赖它），
     *       它只是不再被 GUI 直接调用。</li>
     *   <li><b>Q4/P3 把第二段从"度量 + 打印"升级为硬断言（2026-10-02）</b>。理由：那一轮把
     *       "模型与 GUI 共享同一个可变列表"改成了"模型唯一拥有业务数据、JavaFX 列表是它的投影"
     *       （{@code WorkspaceModel} 删掉共享构造、改为私有存储；GUI 侧显示序由
     *       {@code gui.WorkspaceFxAdapter} 独占维护，数据序入口 {@code items} 变成
     *       {@code WorkspaceModel.topLevelView()} 的<b>只读</b>包装，唯一写操作 {@code clear()}
     *       转发为模型命令）。共享对象既然不存在了，"把数据序交给 util 层去改"这条路必须被
     *       <b>持续挡住</b>而不是只被打印出来 —— 度量在 0 处时升级为断言零成本，
     *       而一旦有人写回第 1 处，打印是拦不住的。口径同时扩展：除 {@code items} 外，
     *       也覆盖"取模型集合再转手"的常见写法（{@code topLevelView()} / {@code topLevel()} /
     *       {@code allItems()} / {@code displayOrder()} 作为这些 util 调用的第一个实参）。</li>
     * </ul>
     *
     * <p><b>口径说明</b>：只认小写 {@code items.} 且要求前一个字符不是标识符字符 ——
     * 否则 {@code displayItems.setAll(...)}（显示序，由 {@code WorkspaceFxAdapter} 维护）与
     * {@code topItems.add(...)} 会被误判。间接写只认"把数据序集合<b>作为第一个实参</b>传给
     * {@code DropResolver.apply} / {@code GroupStructure.restoreStructure|restoreGroup|dissolveGroup}"。</p>
     */
    @Test
    void guiDoesNotMutateDataOrderDirectly() throws IOException {
        Path guiPkg = Path.of("src", "main", "java", "com", "mhtextractor", "gui");
        List<String> mutators = List.of("add", "addAll", "remove", "removeAll", "removeIf",
                "set", "setAll", "clear", "retainAll", "sort", "replaceAll");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(guiPkg)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String rel = guiPkg.relativize(p).toString().replace('\\', '/');
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String code = line.split("//")[0];
                    for (String m : mutators) {
                        String needle = "items." + m + "(";
                        int at = code.indexOf(needle);
                        if (at < 0) continue;
                        if (at > 0 && Character.isJavaIdentifierPart(code.charAt(at - 1))) continue;
                        offenders.add(rel + "  ->  " + code.trim());
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "GUI 层不得直接改动 workspace 数据序（须经 com.mhtextractor.app.WorkspaceModel）：\n  "
                        + String.join("\n  ", offenders)
                        + "\n数据序由模型独占（Q4/P3 起不再共享同一个可变列表），直写会绕过撤销记录与结构维护而静默分叉。");

        // 第二段（Q4/P3 起为硬断言）：禁止"把模型的数据序交给 util 层去改"（间接写）。
        // 当前应为 0 处：Q0/P0 已把拖拽正向与拖拽撤销两条路径收进 WorkspaceModel，
        // Q4/P3 又把数据序入口变成只读投影（见本方法 javadoc 的"间接写的历史"）。
        List<String> passthroughApis = List.of(
                "DropResolver.apply(", "GroupStructure.restoreStructure(",
                "GroupStructure.restoreGroup(", "GroupStructure.dissolveGroup(");
        List<String> dataOrderSources = List.of(
                "items", "topLevelView()", "topLevel()", "allItems()", "displayOrder()");
        List<String> passthrough = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(guiPkg)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String rel = guiPkg.relativize(p).toString().replace('\\', '/');
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String code = line.split("//")[0];
                    for (String api : passthroughApis) {
                        int at = code.indexOf(api);
                        if (at < 0) continue;
                        String arg = code.substring(at + api.length()).stripLeading();
                        for (String src : dataOrderSources) {
                            if (arg.startsWith(src)) {
                                passthrough.add(rel + " -> " + api + src + "…）");
                            }
                        }
                    }
                }
            }
        }

        assertTrue(passthrough.isEmpty(),
                "GUI 层不得把模型的数据序交给 util 层方法改写（间接写与直写同样绕过模型）：\n  "
                        + String.join("\n  ", passthrough)
                        + "\n正解：走 WorkspaceModel 的语义命令（applyResolvedDrop / applyUndo 等），"
                        + "它们内部完成快照、结构修改、父组指针重建与撤销记录。");
        System.out.println("[arch] GUI 未直接改动数据序；间接写（Q4/P3 起为断言）"
                + passthrough.size() + " 处：" + passthrough);
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
        List<String> forbidden = List.of(
                "com.mhtextractor.gui.",
                "com.mhtextractor.logging.",
                "com.mhtextractor.App");   // 外壳类自身（Application 子类）

        List<String> offenders = new ArrayList<>();
        for (String pkg : List.of("app", "engine")) {
            Path appDir = MAIN_SOURCES.resolve("com/mhtextractor/" + pkg);
            assertTrue(Files.isDirectory(appDir), "未找到无头包：" + appDir);
            try (Stream<Path> walk = Files.walk(appDir)) {
                for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    String rel = pkg + "/" + appDir.relativize(p).toString().replace('\\', '/');
                    String codeWithoutComments = SourceScanUtils.stripComments(Files.readString(p, StandardCharsets.UTF_8));
                    assertFalse(java.util.regex.Pattern.compile("com\\.mhtextractor\\.uicore\\.(GuiState(?:Manager)?|SessionStateService)\\b")
                                    .matcher(codeWithoutComments).find(),
                            pkg + " must not depend on frontend session persistence: " + rel);
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
        }

        assertTrue(offenders.isEmpty(),
                "无头应用层（app/engine）不得依赖视图层/外壳，否则会**传递依赖 JavaFX** 而 V1 失效：\n  "
                        + String.join("\n  ", offenders));
        System.out.println("[arch] app/engine 层未依赖 gui/logging/App（V1 闭合）");
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

    // ==================== 7. 测试访问面冻结（D2 · 第 13 条不变式） ====================

    /** 测试访问面清单条目数上限：2026-10-05 S2（E2.1/E2.2）一次性上调至 124，此后只减不增；
     *  2026-10-06 P2-1 删除 5 条 HtmlFindIndex（迁入 uicore 并放宽 public，public 成员不入冻结面）→ 119；
     *  2026-10-06 P3-B2 活动编排下沉：删除 {@code MainController.parseToken} 与
     *  {@code PreviewLoader.imageLoadActive}（随类/字段消失）→ 116；
     *  2026-10-07 P4-1 顺手下调：P3-B5 删 2 条、P3-B6 删 6 条后清单已降至 108，上限 116 → 108（只减不增）；
     *  2026-10-09 V1 首批：删除可等价迁移的 20 个反射点，保留无公开观察路径的 9 个 → 88。 */
    private static final int TEST_ACCESS_SURFACE_MAX = 88;

    /**
     * 「测试访问面冻结」不变式（D2）：三条断言合取 + 驱动文件完整性守卫，失败信息分区列出。
     * <ul>
     *   <li>① 只防新增：发现集合 ⊆ 清单（键均为 通道+类/文件+成员 三列拼法，逐字节一致）；</li>
     *   <li>② 防清单膨胀：清单条目数 ≤ {@value #TEST_ACCESS_SURFACE_MAX}（只减不增）；</li>
     *   <li>③ 防陈旧/腐化：清单中未被发现的条目数 == 0；</li>
     *   <li>④ 驱动文件完整性：{@code guiTestSourceClasses()} 与
     *       {@code TestAccessSurface.DRIVER_CLASSES} 双向相等（新同包驱动不登记即失败）。</li>
     * </ul>
     */
    @Test
    void testAccessSurfaceIsFrozen() throws IOException {
        Set<String> manifest = TestAccessSurface.loadManifest();
        assertNotNull(manifest, "测试访问面清单缺失/读不到：" + TestAccessSurface.MANIFEST_PATH
                + "\n  请运行：java -cp \"target/classes;target/test-classes\" "
                + "com.mhtextractor.baseline.TestAccessSurface --write 重新生成");

        Set<String> discovered = TestAccessSurface.discover();

        TreeSet<String> added = new TreeSet<>(discovered);
        added.removeAll(manifest);
        TreeSet<String> stale = new TreeSet<>(manifest);
        stale.removeAll(discovered);

        Set<String> srcDrivers = TestAccessSurface.guiTestSourceClasses();
        Set<String> declared = new TreeSet<>(TestAccessSurface.DRIVER_CLASSES);
        TreeSet<String> unregistered = new TreeSet<>(srcDrivers);
        unregistered.removeAll(declared);
        TreeSet<String> phantom = new TreeSet<>(declared);
        phantom.removeAll(srcDrivers);

        assertTrue(added.isEmpty(),
                "清单外的新增访问点（发现集合 ⊄ 清单）：\n  " + joinKeys(added)
                        + "\n  处置：撤销该访问点，或显式登记进清单并同步下调 TEST_ACCESS_SURFACE_MAX（只减不增）");
        assertTrue(manifest.size() <= TEST_ACCESS_SURFACE_MAX,
                "清单条目数 " + manifest.size() + " 超过上限 " + TEST_ACCESS_SURFACE_MAX
                        + "（只减不增；收紧后同步下调）");
        assertTrue(stale.isEmpty(),
                "清单里已不存在的条目（陈旧/腐化；防「删一条、加一条」）：\n  " + joinKeys(stale));
        assertTrue(unregistered.isEmpty(),
                "src/test/java 新增了未登记的 gui 驱动测试类（DRIVER_CLASSES 完整性守卫）：\n  "
                        + String.join("\n  ", unregistered)
                        + "\n  处置：登记进 TestAccessSurface.DRIVER_CLASSES");
        assertTrue(phantom.isEmpty(),
                "TestAccessSurface.DRIVER_CLASSES 登记了 src/test/java 中不存在的驱动类：\n  "
                        + String.join("\n  ", phantom));

        System.out.println("[arch] 测试访问面：清单 " + manifest.size() + " / 发现 " + discovered.size()
                + " / 新增 " + added.size() + " / 陈旧 " + stale.size() + " / 驱动 " + srcDrivers.size());
    }

    // ==================== 8. P0 三条 UI 可切换耦合闸门（方案 §8 新增不变式 1–3） ====================

    /**
     * 闸门 1（方案 §8 新增不变式 1）：{@code uiport} 包字节码零 JavaFX。
     *
     * <p>扫 {@code target/classes/com/mhtextractor/uiport/**​/*.class}（主源集字节码）的常量池
     * {@code javafx/} 描述符，命中即该<b>类</b>违规；断言违规类数 = 0。{@code uiport} 是 P3 才建立的
     * UI 端口层（当前包不存在 → 0 个类，空跑通过）；P0-1 控制组已验证加一个违规类会把它跑红。</p>
     */
    @Test
    void uiportHasNoJavaFxDependency() throws IOException {
        UiportSignature.Scan scan = UiportSignature.scan(MAIN_CLASSES);
        List<String> violators = new ArrayList<>();
        for (UiportSignature.Violation v : scan.violations()) {
            violators.add(v.className() + "  →  " + v.descriptors());
        }
        assertTrue(scan.violations().size() <= UIPORT_JAVAFX_VIOLATION_MAX,
                "uiport 包出现 JavaFX 依赖（它是 UI 可切换重构的端口层，必须零 JavaFX）：\n  "
                        + String.join("\n  ", violators));
        System.out.println("[arch] uiport JavaFX 闸门：扫描了 " + scan.classesScanned() + " 个类 / 违规 "
                + scan.violations().size() + " 个（" + violators + "）");
    }

    /**
     * 闸门 2（方案 §8 新增不变式 2）：gui 层业务状态持有/构造点度量。
     *
     * <p>四类点：字段（AppConfig / Map&lt;String,AppConfig&gt;，带作用域判断，不含局部变量与参数）、
     * 预览缓存构造、参数剪贴板构造、撤销载荷构造；总数 ≤ {@value #GUI_BUSINESS_STATE_MAX}（只减不增）。
     * 业务状态必须下沉到 app/uiport，P1–P3 逐阶段下调，P3 目标 0。</p>
     */
    @Test
    void guiBusinessStateDoesNotGrow() throws IOException {
        GuiCoupling.StateScan scan = GuiCoupling.scanBusinessState(GuiCoupling.GUI_SOURCES);
        List<String> lines = new ArrayList<>();
        for (GuiCoupling.StatePoint p : scan.points()) {
            lines.add(p.relPath() + ":" + p.line() + " " + p.text());
        }
        assertTrue(scan.total() <= GUI_BUSINESS_STATE_MAX,
                "gui 层业务状态持有/构造点增长：" + scan.total() + " > " + GUI_BUSINESS_STATE_MAX
                        + "（a-字段 " + scan.fields() + " / b-缓存构造 " + scan.cacheConstructors()
                        + " / c-剪贴板构造 " + scan.clipboardConstructors()
                        + " / d-撤销载荷构造 " + scan.undoRecordConstructors() + "）：\n  "
                        + String.join("\n  ", lines)
                        + "\n业务状态必须下沉到 app/uiport（只减不增，P1–P3 逐阶段下调，P3 目标 0）。");
        System.out.println("[arch] gui 业务状态点 " + scan.total() + " / 上限 " + GUI_BUSINESS_STATE_MAX
                + "（a=" + scan.fields() + " b=" + scan.cacheConstructors()
                + " c=" + scan.clipboardConstructors() + " d=" + scan.undoRecordConstructors() + "）");
        for (String line : lines) {
            System.out.println("[arch]   " + line);
        }
    }

    /**
     * 闸门 3（方案 §8 新增不变式 3）：gui 层直连业务包计数。
     *
     * <p>口径：gui 主源集<b>去除注释与字符串字面量</b>后，匹配
     * {@code com.mhtextractor.(core|extractor|processor|output|util|cache|config).<类型名>}（i18n 为共享叶子层），
     * 取 (包, 类型) 的 distinct 集合；断言 ≤ {@value #GUI_DIRECT_BUSINESS_TYPES_MAX}（只减不增，
     * P1–P3 逐阶段下调，P3 目标 0）。import 语句条数仅作<b>非门禁</b>诊断打印。</p>
     */
    @Test
    void guiDirectBusinessPackageUsageDoesNotGrow() throws IOException {
        try (Stream<Path> walk = Files.walk(GuiCoupling.GUI_SOURCES)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                String code = SourceScanUtils.stripComments(Files.readString(p, StandardCharsets.UTF_8));
                assertFalse(code.contains("NamedConfigService") || code.contains("ConfigManager"),
                        "GUI named configs must use the port: " + p);
            }
        }
        Set<String> types = GuiCoupling.distinctBusinessTypes(GuiCoupling.GUI_SOURCES);
        assertTrue(types.size() <= GUI_DIRECT_BUSINESS_TYPES_MAX,
                "gui 直连业务包 distinct 类型数增长：" + types.size() + " > " + GUI_DIRECT_BUSINESS_TYPES_MAX
                        + "\n  " + String.join("\n  ", types)
                        + "\nGUI 对业务层的依赖必须经 uiport 端口收口（只减不增，P1–P3 逐阶段下调，P3 目标 0）。");
        int imports = GuiCoupling.businessImportCount(GuiCoupling.GUI_SOURCES);
        System.out.println("[arch] gui 直连业务类型 " + types.size() + " / 上限 " + GUI_DIRECT_BUSINESS_TYPES_MAX
                + "；业务 import 条数（诊断，非门禁）" + imports);
    }

    /** 发现键（通道\t类/文件\t成员）转可读行（制表符转空格）。 */
    private static String joinKeys(Set<String> keys) {
        return String.join("\n  ", keys.stream().map(k -> k.replace('\t', ' ')).toList());
    }
}
