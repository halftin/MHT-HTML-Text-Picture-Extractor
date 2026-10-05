import atlantafx.base.theme.NordLight;
import com.mhtextractor.App;
import com.mhtextractor.gui.controller.MainController;
import com.mhtextractor.gui.controller.MainViewRefs;
import com.mhtextractor.i18n.I18n;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ObservableMap;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.stage.Stage;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P6-B 验收探针：{@code fx:id} → {@link MainViewRefs} 这条线到底通不通。
 *
 * <p>它证明三件在源码里"看起来对"但必须实测的事：</p>
 * <ol>
 *   <li><b>一个都没漏</b>：{@code MainViewRefs.all()} 的 44 项全非 null，且键集与
 *       {@code MainView.fxml} 里数出来的 {@code fx:id} 集合<b>逐名相等</b>（数量也相等）；
 *       另外把 44 个访问器逐个反射调用一遍，确认"访问器名 = fx:id = all() 里的键"
 *       （防止某条访问器写错字符串键 → 运行期 NPE 而编译通过）。</li>
 *   <li><b>反例（控制组）</b>：故意从 namespace 里拿掉一个 {@code fx:id} ⇒
 *       {@code MainViewRefs.from(...)} <b>必须抛异常并指名它</b>；
 *       类型不符也要抛。并且先跑一次"原样 namespace"证明 from(...) 不是恒抛
 *       —— 否则"必须抛异常"这条断言可能只是恒真。</li>
 *   <li><b>时机</b>：{@code refs} 是在控制器 {@code initialize()} <b>期间</b>、且是在
 *       {@code getNamespace()} 已经完整之后构造的。做法是用探针自己的
 *       {@code ProbeController extends MainController} 覆写 {@code initialize()}：
 *       在 {@code super.initialize()} <b>之前</b>抓一份 namespace 快照
 *       （FXMLLoader 反射调用的就是子类这个覆写方法 —— 源码 addAccessibleMembers 先递归父类
 *       再放子类，HashMap 里最后写入的是子类的 initialize），断言这份"入口快照"已经含全部 44 个
 *       {@code fx:id}、且此刻 {@code refs} 还是 null（证明它由 super.initialize() 构造）。</li>
 * </ol>
 *
 * <p>顺带覆盖指南要求的"FXML 装配错误只在加载/探针处暴露"：窗口起来后关键节点
 * （列表 / 按钮行 / 预览区 / 日志区 / 状态栏）都在，且 {@code refs} 拿到的就是<b>同一个实例</b>
 * （不是各拿一份副本）。</p>
 *
 * <p>跑法：{@code powershell -ExecutionPolicy Bypass -File tools\probes\run-probe.ps1 MainViewRefsProbe}。
 * 失败退出码 1；另有 90s 看门狗，探针挂住时自己 halt(1)，不堵复验流水线。</p>
 */
public class MainViewRefsProbe extends Application {

    /** 与 MainView.fxml 的 fx:id 数一致（P6-B 实测 42；标题可编辑需求新增 titleEditBox/titleField → 44）。 */
    private static final int EXPECTED_FX_IDS = 44;

    /**
     * 探针控制器：唯一职责是在 {@code super.initialize()} 之前抓 namespace 快照。
     * （覆写 public initialize()，无需 @FXML —— FXMLLoader 只按名字找无参 initialize。）
     */
    public static class ProbeController extends MainController {
        private final FXMLLoader loader;
        Map<String, Object> namespaceAtInitializeEntry;
        MainViewRefs refsBeforeSuper;
        MainViewRefs refsAfterSuper;

        public ProbeController(FXMLLoader loader) {
            super(loader);
            this.loader = loader;
        }

        @Override
        public void initialize() {
            this.namespaceAtInitializeEntry = new LinkedHashMap<>(loader.getNamespace());
            this.refsBeforeSuper = refsOf(this);
            super.initialize();
            this.refsAfterSuper = refsOf(this);
        }
    }

    private int failures = 0;
    private int checks = 0;

    @Override
    public void start(Stage stage) throws Exception {
        watchdog();
        I18n.setLocale(I18n.parseLocale(System.getProperty("probe.locale", "zh_CN")));

        Set<String> fxmlIds = fxIdsFromFxml();
        System.out.println("MainView.fxml fx:id count = " + fxmlIds.size());

        FXMLLoader loader = new FXMLLoader(App.class.getResource("/com/mhtextractor/gui/view/MainView.fxml"));
        // 与 App.start() / TestMain 逐字相同的接线（本探针就是来验这条线的）
        loader.setControllerFactory(_ -> new ProbeController(loader));
        loader.setResources(I18n.bundle());
        Parent root = loader.load();

        check("controller factory 生效（控制器是探针子类，不是 fx:controller 反射实例化的）",
                loader.getController() instanceof ProbeController);
        ProbeController pc = (ProbeController) loader.getController();

        // ---------- 3. 时机：initialize() 入口处 namespace 已完整 ----------
        Map<String, Object> entry = pc.namespaceAtInitializeEntry;
        check("initialize() 入口就拿到了 namespace 快照（非 null）", entry != null);
        if (entry != null) {
            check("initialize() 入口的 namespace 含全部 " + EXPECTED_FX_IDS + " 个 fx:id",
                    entry.keySet().containsAll(fxmlIds) && fxmlIds.size() == EXPECTED_FX_IDS);
            check("initialize() 入口的 namespace 里每个 fx:id 都已非 null",
                    fxmlIds.stream().allMatch(id -> entry.get(id) != null));
            check("initialize() 入口的 namespace 也含 location / resources 两个键",
                    entry.containsKey("location") && entry.containsKey("resources"));
        }
        check("super.initialize() 之前 refs 还是 null（证明 refs 是 initialize() 期间构造的）",
                pc.refsBeforeSuper == null);
        check("super.initialize() 之后 refs 已就绪", pc.refsAfterSuper != null);

        // ---------- 1. 一个都没漏 ----------
        MainViewRefs refs = pc.refsAfterSuper;
        check("控制器里的 refs 就是探针读到的那个（非 null）", refs != null);
        Map<String, Object> all = refs.all();
        check("all() 有 " + EXPECTED_FX_IDS + " 项（与 MainView.fxml 的 fx:id 数一致）",
                all.size() == EXPECTED_FX_IDS && all.size() == fxmlIds.size());
        check("all() 的键集与 MainView.fxml 的 fx:id 集合逐名相等",
                all.keySet().equals(fxmlIds));
        List<String> nulls = new ArrayList<>();
        for (Map.Entry<String, Object> e : all.entrySet()) {
            if (e.getValue() == null) {
                nulls.add(e.getKey());
            }
        }
        check("all() 的每一项都非 null" + (nulls.isEmpty() ? "" : " —— 为 null 的：" + nulls), nulls.isEmpty());

        // 44 个访问器逐个反射调用：名字必须是 fx:id、返回值必须非 null 且与 all() 同一实例
        Map<String, Method> accessors = new TreeMap<>();
        for (Method m : MainViewRefs.class.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0
                    || m.getReturnType() == void.class || m.getName().equals("all")
                    || m.getName().equals("from")) {
                continue;
            }
            accessors.put(m.getName(), m);
        }
        check("MainViewRefs 的访问器正好 " + EXPECTED_FX_IDS + " 个（少一个就漏一个 fx:id）",
                accessors.size() == EXPECTED_FX_IDS);
        check("访问器名集合 == MainView.fxml 的 fx:id 集合", accessors.keySet().equals(fxmlIds));
        List<String> accessorBad = new ArrayList<>();
        for (Map.Entry<String, Method> e : accessors.entrySet()) {
            Object v = e.getValue().invoke(refs);
            if (v == null || v != all.get(e.getKey())) {
                accessorBad.add(e.getKey());
            }
        }
        check("每个访问器都返回非 null，且与 all() 里同名的项是同一实例"
                + (accessorBad.isEmpty() ? "" : " —— 不一致的：" + accessorBad), accessorBad.isEmpty());

        // ToggleGroup 不是 Node：单独证明它也被正确接管（SPEC 里唯一的非 Node 项）
        ToggleGroup modes = refs.modeToggleGroup();
        check("modeToggleGroup 是 ToggleGroup 且已接到两个模式按钮",
                modes != null && modes.getToggles().size() == 2
                        && modes.getToggles().contains(refs.textModeToggle())
                        && modes.getToggles().contains(refs.imageModeToggle()));

        // ---------- 2. 反例 / 控制组：缺失即抛异常并指名 fx:id ----------
        Map<String, Object> intact = new LinkedHashMap<>(loader.getNamespace());
        boolean intactOk;
        try {
            MainViewRefs.from(intact);
            intactOk = true;
        } catch (RuntimeException ex) {
            intactOk = false;
            System.out.println("      控制组意外失败：" + ex);
        }
        check("控制组：原样 namespace 调 from(...) 不抛（证明下面的'必须抛'不是恒真）", intactOk);

        Map<String, Object> missing = new LinkedHashMap<>(loader.getNamespace());
        missing.remove("statusLabel");
        String missingMsg = expectThrow("缺失一个 fx:id（statusLabel）", missing);
        check("反例：缺 fx:id 的 namespace 必须抛异常", missingMsg != null);
        check("反例：异常消息里指名了缺失的 fx:id \"statusLabel\"（实际：" + missingMsg + "）",
                missingMsg != null && missingMsg.contains("statusLabel"));

        Map<String, Object> wrongType = new LinkedHashMap<>(loader.getNamespace());
        wrongType.put("startButton", new Label("not-a-button"));
        String wrongMsg = expectThrow("类型不符（startButton 塞 Label）", wrongType);
        check("反例：类型不符的 namespace 必须抛异常", wrongMsg != null);
        check("反例：异常消息里指名了 startButton（实际：" + wrongMsg + "）",
                wrongMsg != null && wrongMsg.contains("startButton"));

        // ---------- 窗口起来后关键节点都在（列表 / 按钮行 / 预览区 / 日志区 / 状态栏） ----------
        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());
        Scene scene = new Scene(root);
        scene.getStylesheets().add(App.class.getResource("/custom.css").toExternalForm());
        stage.setScene(scene);
        stage.setWidth(1280);
        stage.setHeight(800);
        stage.show();

        String[] keyIds = {"fileListView", "removeButton", "groupButton", "undoButton", "startButton",
            "stopButton", "paramSettingsButton", "progressBar", "statusLabel", "textPreviewArea",
            "imagePreviewBox", "logTextArea", "thumbnailPane", "inputFolderField", "groupNameField",
            "titleField"};
        List<String> missingNodes = new ArrayList<>();
        List<String> aliased = new ArrayList<>();
        for (String id : keyIds) {
            Node looked = scene.lookup("#" + id);
            if (looked == null) {
                missingNodes.add(id);
            } else if (looked != all.get(id)) {
                aliased.add(id);
            }
        }
        check("窗口起来后 16 个关键节点都能 lookup 到（列表/按钮行/预览区/日志区/状态栏/标题输入框）"
                + (missingNodes.isEmpty() ? "" : " —— 找不到的：" + missingNodes), missingNodes.isEmpty());
        check("lookup 到的关键节点与 refs 里的是同一实例（不是各拿一份副本）"
                + (aliased.isEmpty() ? "" : " —— 不一致的：" + aliased), aliased.isEmpty());

        ToggleButton text = refs.textModeToggle();
        check("模式按钮可交互（未禁用）：textModeToggle.isDisabled()==" + text.isDisabled(),
                !text.isDisabled());
        check("状态栏初始文案非空（说明 initialize 的装配序列跑到了 updateModeUi 之后）",
                refs.statusLabel().getText() != null && !refs.statusLabel().getText().isBlank());

        System.out.println(failures == 0 ? "ALL CHECKS PASSED (" + checks + ")" : failures + " CHECK(S) FAILED of " + checks);
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---------- helpers ----------

    /** 从 MainView.fxml 的资源文本里数 fx:id（探针的独立口径，不读生产代码里的常量）。 */
    private static Set<String> fxIdsFromFxml() throws Exception {
        Set<String> ids = new LinkedHashSet<>();
        try (InputStream in = App.class.getResourceAsStream("/com/mhtextractor/gui/view/MainView.fxml")) {
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("fx:id=\"([^\"]+)\"").matcher(xml);
            while (m.find()) {
                ids.add(m.group(1));
            }
        }
        return ids;
    }

    /** 反射读 {@code MainController.refs}（生产里是包级字段；探针在默认包，只能反射）。 */
    private static MainViewRefs refsOf(MainController mc) {
        try {
            Field f = MainController.class.getDeclaredField("refs");
            f.setAccessible(true);
            return (MainViewRefs) f.get(mc);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读 MainController.refs 失败", e);
        }
    }

    /** 返回异常消息；不抛则返回 null。 */
    private static String expectThrow(String what, Map<String, Object> namespace) {
        try {
            MainViewRefs.from(namespace);
            System.out.println("      " + what + " -> 没抛（不符合预期）");
            return null;
        } catch (RuntimeException ex) {
            System.out.println("      " + what + " -> " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return ex.getMessage() == null ? "" : ex.getMessage();
        }
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
    }

    /** 探针挂住时自己退出，绝不堵住复验流水线（Prompt §7.6 的硬要求）。 */
    private void watchdog() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(90_000);
            } catch (InterruptedException e) {
                return;
            }
            System.err.println("[MainViewRefsProbe] WATCHDOG: 90s 未结束，强制 exit 1");
            Runtime.getRuntime().halt(1);
        }, "mainviewrefsprobe-watchdog");
        t.setDaemon(true);
        t.start();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
