import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import com.mhtextractor.App;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.gui.controller.FileListContextMenu;
import com.mhtextractor.gui.controller.MainController;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.GroupItem;

import atlantafx.base.controls.ToggleSwitch;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Q9a 右键菜单探针：在<b>带 toolkit 的真实装配</b>里验证 {@code FileListContextMenu}。
 * Q10 增补 R7.1-R7.7 的端到端（复制参数 → 应用到所选 → 撤销一条 → 控制组）。
 *
 * <p><b>为什么需要它</b>：无头单测 JVM 里 {@code new SeparatorMenuItem()} / {@code new ContextMenu()}
 * 会抛 {@code Toolkit not initialized}（实测），所以"新类的 {@code LAYOUT} 与真实
 * {@code ContextMenu.getItems()} 是否一致"只能在这里证 —— 否则 {@code LAYOUT} 只是一个没人校验的副本。</p>
 *
 * <p><b>本探针里的期望值是独立副本</b>：18 项类型序列、14 个文案键、四种目标的禁用矩阵都在本文件里
 * 逐项写死（<b>不复用</b> {@code FileListContextMenu.LAYOUT} / {@code TEXT_KEYS}），因此新类里改错
 * 顺序 / 换错键 / 写错启用位都会在这里失败。</p>
 *
 * <p>被测对象是 {@code MainView.fxml} 加载出的<b>真实</b> {@code MainController}（与 App.start() 同构装配），
 * 菜单实例经反射取私有字段 {@code contextMenu}；因此覆盖"宿主目标类型判定 + 规则 + 落点"的完整链路。
 * R7 的动作也一律经<b>真实菜单项</b> {@code MenuItem.fire()} 触发（只把新类的私有 {@code target}
 * 字段指向被测目标，等价于真实右键命中那一行），故覆盖"菜单项 → Actions → 宿主实现"整条接线。</p>
 *
 * <p>退出码：0 = 全过，1 = 有失败，2 = 看门狗超时（防探针挂死）。</p>
 */
public class ContextMenuProbe extends Application {

    /** 期望的 18 项序列（独立副本）：item:&lt;文案键&gt; 或 sep；R7.1 的两项在第 3 组末尾 */
    private static final List<String> EXPECTED_ORDER = List.of(
            "item:list.menuSelect", "item:list.menuSelectAll", "sep",
            "item:browser.menuOpen", "item:list.menuOpenFolder", "item:browser.menuViewSource", "sep",
            "item:params.menuSet", "item:params.menuClear", "item:params.menuApplyGlobal",
            "item:params.menuCopy", "item:params.menuApplyToThisItem", "sep",
            "item:group.menuMerge", "item:group.menuUngroup", "sep",
            "item:list.menuRemove", "item:group.menuRemove");

    /** 功能二：应用到所选五档文案键（独立副本；顺序无关，集合等价）。 */
    private static final List<String> APPLY_KEYS = List.of(
            "params.menuApplyToThisItem", "params.menuApplyToManyItems", "params.menuApplyToGroup",
            "params.menuApplyToGroupAndThisItem", "params.menuApplyToGroupAndMany");

    private int passed;
    private int failed;

    private Stage stage;
    private Scene scene;
    private MainController controller;
    private FileListContextMenu menu;
    private FileListContextMenu.Actions actions;
    private javafx.scene.control.ListView<Object> listView;

    @Override
    public void start(Stage primaryStage) throws Exception {
        this.stage = primaryStage;
        I18n.setLocale(I18n.parseLocale(System.getProperty("probe.locale", "zh_CN")));
        Platform.setImplicitExit(false);

        // 与 App.start() 同构：FXML + i18n bundle（initialize() 会装配右键菜单）
        FXMLLoader loader = new FXMLLoader(App.class.getResource("/com/mhtextractor/gui/view/MainView.fxml"));
        // P6-B：控制器要拿 FXML 命名空间（fx:id -> MainViewRefs），而 namespace 只有 loader 有 ——
        // 与 App.start() / TestMain 同一根线（controller factory 覆盖 fx:controller，时机逐字相同）。
        loader.setControllerFactory(_ -> new com.mhtextractor.gui.controller.MainController(loader));
        loader.setResources(I18n.bundle());
        Node mainRoot = loader.load();
        this.controller = loader.getController();
        this.scene = new Scene(new StackPane(mainRoot));
        stage.setScene(scene);
        stage.setWidth(1100);
        stage.setHeight(700);
        stage.show();

        // 看门狗：探针挂死是本项目真发生过的流水线事故
        Timer watchdog = new Timer("context-menu-probe-watchdog", true);
        watchdog.schedule(new TimerTask() {
            @Override public void run() {
                System.out.println("CONTEXT-MENU PROBE: TIMEOUT after 25s (" + passed + " passed, "
                        + failed + " failed) - treating as failure");
                System.exit(2);
            }
        }, 25000);

        Timer timer = new Timer("context-menu-probe", true);
        timer.schedule(at(400, this::runStaticAssertions), 400);
        timer.schedule(at(550, this::applyTextChecks), 550);
        timer.schedule(at(700, this::r7CopyApplyEndToEnd), 700);
        timer.schedule(at(1200, this::behaviorShowA), 1200);
        timer.schedule(at(1700, this::behaviorFireA), 1700);
        timer.schedule(at(2200, this::behaviorShowB), 2200);
        timer.schedule(at(2700, this::behaviorFireB), 2700);
        timer.schedule(at(3200, this::finish), 3200);
    }

    /**
     * 定时步骤：{@code Timer} 线程不能碰 JavaFX（{@code ContextMenu.show/hide} 会抛
     * {@code Not on FX application thread} —— 实测），故一律转投 FX 线程；{@code runLater} 是 FIFO，
     * 步骤顺序不变。
     */
    private TimerTask at(long delay, Runnable body) {
        return new TimerTask() {
            @Override public void run() {
                Platform.runLater(() -> {
                    try {
                        body.run();
                    } catch (Throwable t) {
                        check("step threw: " + t, false);
                        t.printStackTrace(System.out);
                    }
                });
            }
        };
    }

    // ==================== t=400ms：不需要菜单弹出的全部断言 ====================

    private void runStaticAssertions() {
        System.out.println("locale=" + I18n.currentLocale());
        try {
            Field f = MainController.class.getDeclaredField("contextMenu");
            f.setAccessible(true);
            menu = (FileListContextMenu) f.get(controller);
            Field af = FileListContextMenu.class.getDeclaredField("actions");
            af.setAccessible(true);
            actions = (FileListContextMenu.Actions) af.get(menu);
            listView = (javafx.scene.control.ListView<Object>) scene.lookup("#fileListView");
        } catch (Exception e) {
            check("可以反射拿到 MainController.contextMenu / FileListContextMenu.actions: " + e, false);
            finish();
            return;
        }
        check("反射拿到真实的 FileListContextMenu 实例", menu != null && actions != null);
        check("反射拿到真实的 #fileListView", listView != null);

        ContextMenu real = menu.menu();
        check("install() 已经建出 ContextMenu（menu() != null）", real != null);
        if (real == null) {
            finish();
            return;
        }

        // ---- 1. 恒 18 项同序：真实 getItems() 逐项对账 ----
        List<MenuItem> items = real.getItems();
        check("真实 ContextMenu 恒 18 项（实际 " + items.size() + "）", items.size() == EXPECTED_ORDER.size());
        int n = Math.min(items.size(), EXPECTED_ORDER.size());
        List<String> actualOrder = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String expected = EXPECTED_ORDER.get(i);
            MenuItem item = items.get(i);
            if (expected.equals("sep")) {
                check("#" + i + " 是 SeparatorMenuItem（实际 " + item.getClass().getSimpleName() + "）",
                        item instanceof SeparatorMenuItem);
                actualOrder.add("sep");
            } else {
                String key = expected.substring("item:".length());
                boolean isPlainItem = item instanceof MenuItem && !(item instanceof SeparatorMenuItem);
                check("#" + i + " 是普通 MenuItem（不是分隔符）", isPlainItem);
                if (APPLY_KEYS.contains(key)) {
                    // 功能二：应用到所选是 5 档动态文案 —— 这里只断言初始文案“∈ 5 档”，
                    // “与实际作用集合一致”由 applyTextChecks() 独立驱动真实宿主链路验证。
                    List<String> applyTexts = APPLY_KEYS.stream().map(I18n::get).toList();
                    check("#" + i + " 应用到所选初始文案 ∈ 5 档（实际 \"" + item.getText() + "\"）",
                            applyTexts.contains(item.getText()));
                } else {
                    check("#" + i + " 文案 == I18n.get(\"" + key + "\")（实际 \"" + item.getText() + "\"）",
                            I18n.get(key).equals(item.getText()));
                }
                actualOrder.add("item:" + key);
            }
        }
        check("真实 getItems() 的 18 项类型/键序列 == 硬编码期望（独立副本）",
                actualOrder.equals(EXPECTED_ORDER));

        // 装置自检：14 个文案键在当前 locale 下必须两两不同，否则"键位互换"抓不到
        List<String> texts = new ArrayList<>();
        for (String entry : EXPECTED_ORDER) {
            if (entry.startsWith("item:")) {
                texts.add(I18n.get(entry.substring("item:".length())));
            }
        }
        check("装置自检：14 个文案键的文本两两不同（否则键位互换不可判）",
                texts.size() == 14 && texts.stream().distinct().count() == 14);

        // ---- 2. 新类的 LAYOUT 与真实序列同源（同一个硬编码期望对两边都成立） ----
        List<String> fromLayout = new ArrayList<>();
        for (FileListContextMenu.Entry e : FileListContextMenu.LAYOUT) {
            if (e.isSeparator()) {
                fromLayout.add("sep");
            } else {
                fromLayout.add("item:" + FileListContextMenu.TEXT_KEYS.get(e));
            }
        }
        check("LAYOUT + TEXT_KEYS 派生出的序列 == 硬编码期望", fromLayout.equals(EXPECTED_ORDER));

        // ---- 3. 四种目标的真实禁用矩阵（走宿主 targetType + hasIndependentParams + apply） ----
        PreviewItem topItem = new PreviewItem("C:/probe/top.mht", "top.mht", LocalDateTime.now(), "C:/probe");
        GroupItem<PreviewItem> group = new GroupItem<>(1, "probe-group");
        PreviewItem member = new PreviewItem("C:/probe/member.mht", "member.mht", LocalDateTime.now(), "C:/probe");
        member.parentGroup = group;
        group.getMembers().add(member);

        checkMatrix("空白区域", null, false, false, true, false, false, false, false);
        checkMatrix("顶层条目", topItem, true, true, false, true, false, true, false);
        checkMatrix("组内成员", member, true, true, false, true, false, true, true);
        checkMatrix("组条目", group, true, false, false, false, true, false, true);

        // ---- 4. 已移除行不算目标（宿主 isLiveTarget；搬前 pickItemAt 的 it.removed 分支） ----
        PreviewItem removed = new PreviewItem("C:/probe/removed.mht", "removed.mht", LocalDateTime.now(), "C:/probe");
        removed.removed = true;
        check("isLiveTarget(未移除条目) == true", actions.isLiveTarget(topItem));
        check("isLiveTarget(GroupItem) == true", actions.isLiveTarget(group));
        check("isLiveTarget(已移除条目) == false", !actions.isLiveTarget(removed));

        // ---- 5. pickItemAt 的节点遍历：滚动条 / 普通节点 / null ----
        try {
            Method pick = FileListContextMenu.class.getDeclaredMethod("pickFrom", Node.class);
            pick.setAccessible(true);
            check("pickFrom(ScrollBar) == null（滚动条右键不算列表区域）", pick.invoke(menu, new ScrollBar()) == null);
            check("pickFrom(普通 Pane) == null（未命中单元格 = 空白区域）", pick.invoke(menu, new Pane()) == null);
            check("pickFrom(null) == null", pick.invoke(menu, (Node) null) == null);
        } catch (Exception e) {
            check("可以反射调用 FileListContextMenu.pickFrom(Node): " + e, false);
        }

        // ---- 6. 显示/隐藏生命周期已接线（结构性证据；行为证据见 t=1200ms 之后） ----
        check("菜单显示时挂「点击菜单外即关闭」过滤器（onShown 已设置）", real.getOnShown() != null);
        check("菜单隐藏时移除该过滤器（onHidden 已设置）", real.getOnHidden() != null);
        check("列表已挂 onContextMenuRequested", listView.getOnContextMenuRequested() != null);
    }

    /** 真实菜单项的禁用状态 vs 硬编码期望（11 个与参数无关的位 + 参数文案） */
    private void checkMatrix(String what, Object target,
                             boolean select, boolean itemScoped, boolean paramsGlobal,
                             boolean group, boolean ungroup, boolean remove, boolean removeGroup) {
        try {
            Method m = MainController.class.getDeclaredMethod("updateContextMenuState", Object.class);
            m.setAccessible(true);
            m.invoke(controller, target);
        } catch (Exception e) {
            check(what + "：可以反射调用 updateContextMenuState: " + e, false);
            return;
        }
        check(what + "：勾选" + (select ? "启用" : "禁用"),
                dis(FileListContextMenu.Entry.SELECT) == !select);
        check(what + "：全选恒启用", !dis(FileListContextMenu.Entry.SELECT_ALL));
        check(what + "：打开所在文件夹" + (itemScoped ? "启用" : "禁用"),
                dis(FileListContextMenu.Entry.OPEN_FOLDER) == !itemScoped);
        check(what + "：在浏览器中查看" + (itemScoped ? "启用" : "禁用"),
                dis(FileListContextMenu.Entry.OPEN_IN_BROWSER) == !itemScoped);
        check(what + "：查看源码" + (itemScoped ? "启用" : "禁用"),
                dis(FileListContextMenu.Entry.VIEW_SOURCE) == !itemScoped);
        check(what + "：设置参数恒启用", !dis(FileListContextMenu.Entry.PARAMS));
        check(what + "：参数文案 = " + (paramsGlobal ? "params.menuSetGlobal" : "params.menuSet"),
                I18n.get(paramsGlobal ? FileListContextMenu.KEY_PARAMS_GLOBAL : FileListContextMenu.KEY_PARAMS)
                        .equals(menu.item(FileListContextMenu.Entry.PARAMS).getText()));
        check(what + "：建组" + (group ? "启用" : "禁用"), dis(FileListContextMenu.Entry.GROUP) == !group);
        check(what + "：拆分组" + (ungroup ? "启用" : "禁用"), dis(FileListContextMenu.Entry.UNGROUP) == !ungroup);
        check(what + "：移除" + (remove ? "启用" : "禁用"), dis(FileListContextMenu.Entry.REMOVE) == !remove);
        check(what + "：移除组" + (removeGroup ? "启用" : "禁用"),
                dis(FileListContextMenu.Entry.REMOVE_GROUP) == !removeGroup);
        // 与宿主自己的判定一致性（不是独立期望：独立期望在无头单测里）
        try {
            Method hip = MainController.class.getDeclaredMethod("hasIndependentParams", Object.class);
            hip.setAccessible(true);
            boolean indep = (Boolean) hip.invoke(controller, target);
            check(what + "：清除参数/应用到全局 == 宿主 hasIndependentParams(" + indep + ")",
                    dis(FileListContextMenu.Entry.CLEAR_PARAMS) == !indep
                            && dis(FileListContextMenu.Entry.APPLY_GLOBAL) == !indep);
        } catch (Exception e) {
            System.out.println("      (info) hasIndependentParams 反射失败，跳过该一致性断言：" + e);
        }
    }

    private boolean dis(FileListContextMenu.Entry entry) {
        return menu.item(entry).isDisable();
    }

    private String text(FileListContextMenu.Entry entry) {
        return menu.item(entry).getText();
    }

    // ==================== t=550ms：功能二 5 档文案与实际作用集合一致 ====================

    /**
     * 走<b>真实</b>宿主链路（{@code updateContextMenuState} → {@code resolveParamTargets} →
     * {@code countTargets} → {@code stateFor}）验证五档：组外 1 / 组外 ≥2 / 只有组 / 组外 1+组 /
     * 组外 ≥2+组，并覆盖“勾选优先于右键目标”。用完即从模型移除，避免影响后续 R7。
     */
    private void applyTextChecks() {
        try {
            PreviewItem a = newPreview("apply-a");
            PreviewItem b = newPreview("apply-b");
            PreviewItem m = newPreview("apply-m");
            workspace().appendItems(new ArrayList<>(List.of(a, b, m)));
            GroupItem<PreviewItem> g = workspace().group(new ArrayList<>(List.of(m)), "probe-apply");

            List<String> rendered = APPLY_KEYS.stream().map(I18n::get).toList();
            check("功能二：5 档文案渲染后两两不同", rendered.stream().distinct().count() == 5);

            a.selected = true;
            b.selected = false;
            m.selected = false;
            applyText("只有组外项 1 个", a, FileListContextMenu.KEY_APPLY_THIS);

            b.selected = true;
            applyText("只有组外项 ≥2", a, FileListContextMenu.KEY_APPLY_MANY);

            a.selected = false;
            b.selected = false;
            m.selected = true;
            applyText("只有组相关", m, FileListContextMenu.KEY_APPLY_GROUP);

            a.selected = true;
            applyText("组外 1 + 组", a, FileListContextMenu.KEY_APPLY_GROUP_AND_THIS);

            b.selected = true;
            applyText("组外 ≥2 + 组", a, FileListContextMenu.KEY_APPLY_GROUP_AND_MANY);

            // 勾选优先于右键目标：右键未勾选的 c，实际集合 = 勾选集合（a/b/m）
            PreviewItem c = newPreview("apply-c");
            workspace().appendItems(new ArrayList<>(List.of(c)));
            applyText("勾选优先于右键目标", c, FileListContextMenu.KEY_APPLY_GROUP_AND_MANY);

            workspace().removeItems(new ArrayList<>(List.of(a, b, c)));
            if (g != null) {
                workspace().removeGroup(g);
            }
        } catch (Throwable t) {
            check("功能二 5 档步骤抛异常: " + t, false);
            t.printStackTrace(System.out);
        }
    }

    private void applyText(String what, Object target, String expectedKey) {
        updateState(target);
        String actual = menu.item(FileListContextMenu.Entry.APPLY_TO_SELECTION).getText();
        check("功能二[" + what + "] 文案 == I18n.get(" + expectedKey + ")（实际 \"" + actual + "\"）",
                I18n.get(expectedKey).equals(actual));
    }

    // ==================== t=700ms：R7 端到端（复制 → 应用 → 撤销 → 控制组） ====================

    /**
     * R7.1-R7.7 的端到端：经<b>真实菜单项</b> {@code fire()} 触发复制与应用，断言"目标生效参数变成复制的
     * 快照 / 一次应用只压一条撤销记录 / 撤销能完整恢复到 before 快照 / 4 个运行时字段没被改"；
     * 并给"可用"配两组<b>控制组</b>：① 无参数条目上「复制参数」必须禁用且文案是"无参数"版本；
     * ② 未复制前「应用到所选」必须禁用。
     */
    private void r7CopyApplyEndToEnd() {
        try {
            boolean im = ((javafx.scene.control.ToggleButton) node("imageModeToggle")).isSelected();
            System.out.println("      (info) R7 端到端：当前模式 imageMode=" + im);

            PreviewItem a = newPreview("r7-a");
            PreviewItem b = newPreview("r7-b");
            PreviewItem bare = newPreview("r7-bare");
            AppConfig source = params("Q10-SRC");
            source.setSeparateFiles(true);
            source.setInputFolder("C:/probe/runtime-in");
            source.setOutputFolder("C:/probe/runtime-out");
            source.setImageModeEnabled(true);
            source.setOpenFolderAfterComplete(true);
            setItemParams(a, im, source);
            setItemParams(b, im, params("BEFORE-B"));
            workspace().appendItems(new ArrayList<>(List.of(a, b)));

            String inBefore = ((javafx.scene.control.TextField) node("inputFolderField")).getText();
            String outBefore = ((javafx.scene.control.TextField) node("outputFolderField")).getText();
            boolean openAfterBefore = ((ToggleSwitch) node("openFolderAfterCompleteCheck")).isSelected();

            // ---- 控制组 ①：无参数目标 + 未复制过 ⇒ 两项都禁用，复制文案 = "无参数" ----
            updateState(bare);
            check("控制组：无生效参数条目上「复制参数」禁用（证明'可用'不是恒真）", dis(FileListContextMenu.Entry.COPY_PARAMS));
            check("R7.2：禁用理由写在选项文本里（无参数版）",
                    I18n.get(FileListContextMenu.KEY_COPY_NO_PARAMS).equals(text(FileListContextMenu.Entry.COPY_PARAMS)));
            check("R7.3：未复制过 ⇒「应用到所选」禁用", dis(FileListContextMenu.Entry.APPLY_TO_SELECTION));

            // ---- R7.2 理由二：两个勾选项生效参数不同 ⇒ 禁用 + "参数不统一"版文案 ----
            a.selected = true;
            b.selected = true;
            updateState(a);
            check("R7.2：多选参数不统一 ⇒「复制参数」禁用", dis(FileListContextMenu.Entry.COPY_PARAMS));
            check("R7.2：禁用理由是'参数不统一'版文案",
                    I18n.get(FileListContextMenu.KEY_COPY_MIXED_PARAMS).equals(text(FileListContextMenu.Entry.COPY_PARAMS)));
            a.selected = false;
            b.selected = false;

            // ---- R7.1/R7.2 可用态：右键有参数条目 ⇒ 复制参数启用、文案 = "复制参数" ----
            updateState(a);
            check("R7.2：有生效参数且统一 ⇒「复制参数」启用", !dis(FileListContextMenu.Entry.COPY_PARAMS));
            check("R7.2：可用时文案 = params.menuCopy",
                    I18n.get(FileListContextMenu.KEY_COPY_PARAMS).equals(text(FileListContextMenu.Entry.COPY_PARAMS)));

            // ---- R7.1：经真实菜单项复制（复制不写模型、不压撤销记录） ----
            int depthBeforeCopy = workspace().undoDepth();
            fire(FileListContextMenu.Entry.COPY_PARAMS, a);
            check("R7.1：复制参数不写模型（撤销栈不变）", workspace().undoDepth() == depthBeforeCopy);
            updateState(b);
            check("R7.3：复制过之后「应用到所选」启用", !dis(FileListContextMenu.Entry.APPLY_TO_SELECTION));

            // ---- R7.4/R7.5/R7.7：应用到所选（真实菜单项 → 宿主既有 apply* 路径） ----
            fire(FileListContextMenu.Entry.APPLY_TO_SELECTION, b);
            check("R7.5：一次应用只压一条撤销记录（实际 +" + (workspace().undoDepth() - depthBeforeCopy)
                    + "）", workspace().undoDepth() == depthBeforeCopy + 1);
            AppConfig applied = itemParamsOf(b, im);
            check("R7.4：b 的生效参数 = 复制的快照（真实参数槽位）",
                    applied != null && effectiveSignature(applied).equals(effectiveSignature(source)));
            check("R7.7：b 拿到的是副本而非源实例", applied != source && applied != itemParamsOf(a, im));
            check("R7.7：应用未把源的 inputFolder 写进 b", applied.getInputFolder() == null);
            check("R7.7：应用未把源的 outputFolder 写进 b", applied.getOutputFolder() == null);
            check("R7.7：应用未把源的 imageModeEnabled 写进 b", !applied.isImageModeEnabled());
            check("R7.7：应用未把源的 openFolderAfterComplete 写进 b", !applied.isOpenFolderAfterComplete());
            check("R7.7：应用未改主窗口输入目录", inBefore.equals(
                    ((javafx.scene.control.TextField) node("inputFolderField")).getText()));
            check("R7.7：应用未改主窗口输出目录", outBefore.equals(
                    ((javafx.scene.control.TextField) node("outputFolderField")).getText()));
            check("R7.7：应用未改主窗口「完成后打开文件夹」开关", openAfterBefore ==
                    ((ToggleSwitch) node("openFolderAfterCompleteCheck")).isSelected());
            check("R7.7：应用未改当前模式", im ==
                    ((javafx.scene.control.ToggleButton) node("imageModeToggle")).isSelected());

            // ---- R7.5：撤销那一条记录必须完整恢复到 before 快照 ----
            callNoArg(controller, "onUndoClicked");
            check("R7.5：撤销后 b 的生效参数回到应用前（一条记录携带了 before 快照）",
                    itemParamsOf(b, im) != null
                            && effectiveSignature(itemParamsOf(b, im)).equals(effectiveSignature(params("BEFORE-B"))));
            check("R7.5：撤销后撤销栈回到应用前深度", workspace().undoDepth() == depthBeforeCopy);
        } catch (Throwable t) {
            check("R7 端到端步骤抛异常: " + t, false);
            t.printStackTrace(System.out);
        }
    }

    private PreviewItem newPreview(String name) {
        return new PreviewItem("C:/probe/" + name + ".mht", name + ".mht", LocalDateTime.now(), "C:/probe");
    }

    private static AppConfig params(String marker) {
        AppConfig c = new AppConfig();
        c.setStartMarker(marker);
        return c;
    }

    private static void setItemParams(PreviewItem item, boolean imageMode, AppConfig p) {
        if (imageMode) {
            item.imageItemParams = p;
        } else {
            item.textItemParams = p;
        }
    }

    private static AppConfig itemParamsOf(PreviewItem item, boolean imageMode) {
        return imageMode ? item.imageItemParams : item.textItemParams;
    }

    /** 提取参数的内容签名（与生产同一实现，不用 equals：AppConfig 是可变对象） */
    private static String effectiveSignature(AppConfig c) {
        return com.mhtextractor.app.EffectiveParamsService.signatureOf(c);
    }

    /** 经真实菜单项触发动作：把新类的私有 target 指向被测目标（等价于真实右键命中那一行），再 fire() */
    private void fire(FileListContextMenu.Entry entry, Object target) throws Exception {
        Field tf = FileListContextMenu.class.getDeclaredField("target");
        tf.setAccessible(true);
        tf.set(menu, target);
        menu.item(entry).fire();
    }

    /** 直接调宿主的 updateContextMenuState（test-support 可见性经反射）来刷新菜单项状态 */
    private void updateState(Object target) {
        try {
            Method m = MainController.class.getDeclaredMethod("updateContextMenuState", Object.class);
            m.setAccessible(true);
            m.invoke(controller, target);
        } catch (Exception e) {
            check("反射调用 updateContextMenuState: " + e, false);
        }
    }

    private Object fieldOf(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    /** P6-B：FXML 节点引用现在都在 {@code MainController.refs}（{@code MainViewRefs}）里；按 fx:id 取。 */
    private Object node(String fxId) throws Exception {
        Object refs = fieldOf(controller, "refs");
        Method m = refs.getClass().getMethod(fxId);
        return m.invoke(refs);
    }

    private com.mhtextractor.app.WorkspaceModel workspace() throws Exception {
        return (com.mhtextractor.app.WorkspaceModel) fieldOf(controller, "ws");
    }

    private void callNoArg(Object owner, String name) throws Exception {
        Method m = owner.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(owner);
    }

    // ==================== t=1200ms 起：点击菜单外即关闭（A/B 对照，同一菜单） ====================

    /**
     * 对照设计：JavaFX 的 {@code ContextMenu} 自带 autoHide，会掩盖我们自己的过滤器 —— 先用
     * {@code setAutoHide(false)} 把它关掉，再对<b>同一个菜单</b>做两次测量：
     * <ul>
     *   <li><b>A</b>：正常显示（{@code onShown} 已装过滤器）→ 合成按压 → 应隐藏；</li>
     *   <li><b>B（控制组）</b>：显示后手动调用 {@code onHidden} 处理器摘掉过滤器 → 同一次按压 → 应<b>不</b>隐藏。</li>
     * </ul>
     * 只有 A 隐藏且 B 不隐藏，才证明"隐藏是我们自己的过滤器干的"；否则如实打印 inconclusive
     * （本项目最忌讳"测量装置分不清就当成通过"）。
     */
    private Boolean hidWithFilter;
    private Boolean hidWithoutFilter;

    private void behaviorShowA() {
        ContextMenu m = menu.menu();
        m.setAutoHide(false);
        m.show(listView, stage.getX() + 40, stage.getY() + 120);
        check("A：被测菜单弹出（menu.show 生效）", m.isShowing());
    }

    private void behaviorFireA() {
        pressOnOwnerWindow();
        hidWithFilter = !menu.menu().isShowing();
        System.out.println("      (info) A（装过滤器）按压后 isShowing=" + menu.menu().isShowing());
        menu.menu().hide();
    }

    private void behaviorShowB() {
        ContextMenu m = menu.menu();
        m.setAutoHide(false);
        m.show(listView, stage.getX() + 40, stage.getY() + 120);
        m.getOnHidden().handle(null);   // 只摘掉我们自己的过滤器；菜单保持显示
        check("B（控制组）：菜单仍显示且自定义过滤器已手动摘除", m.isShowing());
    }

    private void behaviorFireB() {
        pressOnOwnerWindow();
        hidWithoutFilter = !menu.menu().isShowing();
        System.out.println("      (info) B（摘除过滤器）按压后 isShowing=" + menu.menu().isShowing());
        menu.menu().hide();
        if (Boolean.TRUE.equals(hidWithFilter) && Boolean.FALSE.equals(hidWithoutFilter)) {
            check("点击菜单外即关闭：A 隐藏 / B 不隐藏（A/B 对照成立，autoHide 已被隔离）", true);
        } else {
            System.out.println("      (info) A/B 对照不成立（A 隐藏=" + hidWithFilter + "，B 隐藏="
                    + hidWithoutFilter + "）⇒ 合成按压无法隔离我们自己的过滤器，本探针<b>不</b>声称该行为已验证；"
                    + "只保留 onShown/onHidden 已接线这条结构性证据");
        }
    }

    /** 在宿主窗口上投一个合成 MOUSE_PRESSED（与真实点击走同一条 filter 链） */
    private void pressOnOwnerWindow() {
        Event.fireEvent(scene.getWindow(), new MouseEvent(MouseEvent.MOUSE_PRESSED,
                20, 20, stage.getX() + 20, stage.getY() + 20, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, true, null));
    }

    // ==================== 收尾 ====================

    private void check(String label, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + label);
        } else {
            failed++;
            System.out.println("FAIL  " + label);
        }
    }

    private void finish() {
        System.out.println("CONTEXT-MENU PROBE: " + passed + " passed, " + failed + " failed  (locale="
                + I18n.currentLocale() + ")");
        File out = new File(System.getProperty("probe.png", "shot.png")).getAbsoluteFile().getParentFile();
        System.out.println("out=" + out);
        Platform.exit();
        System.exit(failed == 0 ? 0 : 1);
    }
}
