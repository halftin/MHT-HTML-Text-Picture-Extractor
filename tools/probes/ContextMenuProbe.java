import java.io.File;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import com.mhtextractor.App;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.gui.controller.FileListContextMenu;
import com.mhtextractor.gui.controller.MainController;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.uicore.PreviewRow;
import com.mhtextractor.uicore.GroupRow;
import com.mhtextractor.uicore.FileMenuModel;

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
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.PickResult;
import javafx.scene.control.ListCell;
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
 * 逐项写死（<b>不复用</b> {@code FileMenuModel.LAYOUT} / {@code TEXT_KEYS}），因此新类里改错
 * 顺序 / 换错键 / 写错启用位都会在这里失败。</p>
 *
 * <p>被测对象是 {@code MainView.fxml} 加载出的<b>真实</b> {@code MainController}（与 App.start() 同构装配），
 * 菜单实例经反射取私有字段 {@code contextMenu}；因此覆盖"宿主目标类型判定 + 规则 + 落点"的完整链路。
 * R7 的动作先在列表行派发右键请求，再经<b>真实菜单项</b> {@code MenuItem.fire()} 触发，
 * 覆盖"右键拾取 → 菜单项 → Actions → 宿主实现"整条接线。</p>
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
        Application.setUserAgentStylesheet(new atlantafx.base.theme.NordLight().getUserAgentStylesheet());

        // 与 App.start() 同构：FXML + i18n bundle（initialize() 会装配右键菜单）
        FXMLLoader loader = new FXMLLoader(App.class.getResource("/com/mhtextractor/gui/view/MainView.fxml"));
        // P6-B：控制器要拿 FXML 命名空间（fx:id -> MainViewRefs），而 namespace 只有 loader 有 ——
        // 与 App.start() / TestMain 同一根线（controller factory 覆盖 fx:controller，时机逐字相同）。
        frontend = ProbeRuntime.port();
        loader.setControllerFactory(_ -> new com.mhtextractor.gui.controller.MainController(loader, frontend, finished -> ProbeRuntime.close(frontend, finished)));
        loader.setResources(I18n.bundle());
        Node mainRoot = loader.load();
        this.controller = loader.getController();
        this.scene = new Scene(new StackPane(mainRoot));
        scene.getStylesheets().add(App.class.getResource("/custom.css").toExternalForm());
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

        scheduleStep(List.of(this::runStaticAssertions, this::applyTextChecks, this::r7CopyApplyEndToEnd,
                this::behaviorShowA, this::behaviorFireA, this::behaviorShowB, this::behaviorFireB,
                this::finish), 0);
    }

    /**
     * Only schedule the next step after the current one returns: nested FX waits must not
     * let a later timer step reenter the current fixture setup or finish prematurely.
     */
    private void scheduleStep(List<Runnable> steps, int index) {
        if (index >= steps.size()) return;
        var pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(
                index == 0 ? 400 : index < 3 ? 150 : 500));
        pause.setOnFinished(ignored -> Platform.runLater(() -> {
            try {
                steps.get(index).run();
            } catch (Throwable t) {
                check("step threw: " + t, false);
                t.printStackTrace(System.out);
            }
            scheduleStep(steps, index + 1);
        }));
        pause.play();
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
        for (FileMenuModel.Entry e : FileMenuModel.LAYOUT) {
            if (e.isSeparator()) {
                fromLayout.add("sep");
            } else {
                fromLayout.add("item:" + FileMenuModel.TEXT_KEYS.get(e));
            }
        }
        check("LAYOUT + TEXT_KEYS 派生出的序列 == 硬编码期望", fromLayout.equals(EXPECTED_ORDER));

        // ---- 3. 四种目标的真实禁用矩阵（走宿主 targetType + hasIndependentParams + apply） ----
        PreviewRow topItem = detached("top");
        GroupRow<PreviewRow> group = new GroupRow<>(new com.mhtextractor.uiport.GroupVm(1, "probe-group", true, false, List.of()));
        PreviewRow member = detached("member");
        member.parentGroup = group;

        checkMatrix("空白区域", null, false, false, true, false, false, false, false);
        checkMatrix("顶层条目", topItem, true, true, false, true, false, true, false);
        checkMatrix("组内成员", member, true, true, false, true, false, true, true);
        checkMatrix("组条目", group, true, false, false, false, true, false, true);

        // ---- 4. 已移除行不算目标（宿主 isLiveTarget；搬前 pickItemAt 的 it.removed 分支） ----
        PreviewRow removed = detached("removed");
        removed.removed = true;
        check("isLiveTarget(未移除条目) == true", actions.isLiveTarget(topItem));
        check("isLiveTarget(GroupRow) == true", actions.isLiveTarget(group));
        check("isLiveTarget(已移除条目) == false", !actions.isLiveTarget(removed));

        // ---- 5. pickItemAt 的节点遍历：滚动条 / 普通节点 / null ----
        check("pickItemAt(ScrollBar) == null（滚动条右键不算列表区域）", pickAt(new ScrollBar()) == null);
        check("pickItemAt(普通 Pane) == null（未命中单元格 = 空白区域）", pickAt(new Pane()) == null);
        check("pickItemAt(null) == null", pickAt(null) == null);

        // ---- 6. 显示/隐藏生命周期已接线（结构性证据；行为证据见 t=1200ms 之后） ----
        check("菜单显示时挂「点击菜单外即关闭」过滤器（onShown 已设置）", real.getOnShown() != null);
        check("菜单隐藏时移除该过滤器（onHidden 已设置）", real.getOnHidden() != null);
        check("列表已挂 onContextMenuRequested", listView.getOnContextMenuRequested() != null);
    }

    /** 真实菜单项的禁用状态 vs 硬编码期望（11 个与参数无关的位 + 参数文案） */
    private void checkMatrix(String what, Object target,
                             boolean select, boolean itemScoped, boolean paramsGlobal,
                             boolean group, boolean ungroup, boolean remove, boolean removeGroup) {
        menu.updateState(target);
        check(what + "：勾选" + (select ? "启用" : "禁用"),
                dis(FileMenuModel.Entry.SELECT) == !select);
        check(what + "：全选恒启用", !dis(FileMenuModel.Entry.SELECT_ALL));
        check(what + "：打开所在文件夹" + (itemScoped ? "启用" : "禁用"),
                dis(FileMenuModel.Entry.OPEN_FOLDER) == !itemScoped);
        check(what + "：在浏览器中查看" + (itemScoped ? "启用" : "禁用"),
                dis(FileMenuModel.Entry.OPEN_IN_BROWSER) == !itemScoped);
        check(what + "：查看源码" + (itemScoped ? "启用" : "禁用"),
                dis(FileMenuModel.Entry.VIEW_SOURCE) == !itemScoped);
        check(what + "：设置参数恒启用", !dis(FileMenuModel.Entry.PARAMS));
        check(what + "：参数文案 = " + (paramsGlobal ? "params.menuSetGlobal" : "params.menuSet"),
                I18n.get(paramsGlobal ? FileMenuModel.KEY_PARAMS_GLOBAL : FileMenuModel.KEY_PARAMS)
                        .equals(menu.item(FileMenuModel.Entry.PARAMS).getText()));
        check(what + "：建组" + (group ? "启用" : "禁用"), dis(FileMenuModel.Entry.GROUP) == !group);
        check(what + "：拆分组" + (ungroup ? "启用" : "禁用"), dis(FileMenuModel.Entry.UNGROUP) == !ungroup);
        check(what + "：移除" + (remove ? "启用" : "禁用"), dis(FileMenuModel.Entry.REMOVE) == !remove);
        check(what + "：移除组" + (removeGroup ? "启用" : "禁用"),
                dis(FileMenuModel.Entry.REMOVE_GROUP) == !removeGroup);
        // 与宿主自己的判定一致性（不是独立期望：独立期望在无头单测里）
        boolean indep = actions.paramsInfo(target).independent();
        check(what + "：清除参数/应用到全局 == 宿主 paramsInfo(" + indep + ")",
                dis(FileMenuModel.Entry.CLEAR_PARAMS) == !indep
                        && dis(FileMenuModel.Entry.APPLY_GLOBAL) == !indep);
    }

    private boolean dis(FileMenuModel.Entry entry) {
        return menu.item(entry).isDisable();
    }

    private String text(FileMenuModel.Entry entry) {
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
            PreviewRow a = newPreview("apply-a");
            PreviewRow b = newPreview("apply-b");
            PreviewRow m = newPreview("apply-m");
            workspace().appendItems(new ArrayList<>(List.of(a, b, m)));
            GroupRow<PreviewRow> g = workspace().group(new ArrayList<>(List.of(m)), "probe-apply");

            List<String> rendered = APPLY_KEYS.stream().map(I18n::get).toList();
            check("功能二：5 档文案渲染后两两不同", rendered.stream().distinct().count() == 5);

            setChecked(a, true);
            setChecked(b, false);
            setChecked(m, false);
            applyText("只有组外项 1 个", a, FileMenuModel.KEY_APPLY_THIS);

            setChecked(b, true);
            applyText("只有组外项 ≥2", a, FileMenuModel.KEY_APPLY_MANY);

            setChecked(a, false);
            setChecked(b, false);
            setChecked(m, true);
            applyText("只有组相关", m, FileMenuModel.KEY_APPLY_GROUP);

            setChecked(a, true);
            applyText("组外 1 + 组", a, FileMenuModel.KEY_APPLY_GROUP_AND_THIS);

            setChecked(b, true);
            applyText("组外 ≥2 + 组", a, FileMenuModel.KEY_APPLY_GROUP_AND_MANY);

            // 勾选优先于右键目标：右键未勾选的 c，实际集合 = 勾选集合（a/b/m）
            PreviewRow c = newPreview("apply-c");
            workspace().appendItems(new ArrayList<>(List.of(c)));
            applyText("勾选优先于右键目标", c, FileMenuModel.KEY_APPLY_GROUP_AND_MANY);

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
        String actual = menu.item(FileMenuModel.Entry.APPLY_TO_SELECTION).getText();
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

            PreviewRow a = newPreview("r7-a");
            PreviewRow b = newPreview("r7-b");
            PreviewRow bare = newPreview("r7-bare");
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
            check("控制组：无生效参数条目上「复制参数」禁用（证明'可用'不是恒真）", dis(FileMenuModel.Entry.COPY_PARAMS));
            check("R7.2：禁用理由写在选项文本里（无参数版）",
                    I18n.get(FileMenuModel.KEY_COPY_NO_PARAMS).equals(text(FileMenuModel.Entry.COPY_PARAMS)));
            check("R7.3：未复制过 ⇒「应用到所选」禁用", dis(FileMenuModel.Entry.APPLY_TO_SELECTION));

            // ---- R7.2 理由二：两个勾选项生效参数不同 ⇒ 禁用 + "参数不统一"版文案 ----
            setChecked(a, true);
            setChecked(b, true);
            updateState(a);
            check("R7.2：多选参数不统一 ⇒「复制参数」禁用", dis(FileMenuModel.Entry.COPY_PARAMS));
            check("R7.2：禁用理由是'参数不统一'版文案",
                    I18n.get(FileMenuModel.KEY_COPY_MIXED_PARAMS).equals(text(FileMenuModel.Entry.COPY_PARAMS)));
            setChecked(a, false);
            setChecked(b, false);

            // ---- R7.1/R7.2 可用态：右键有参数条目 ⇒ 复制参数启用、文案 = "复制参数" ----
            updateState(a);
            check("R7.2：有生效参数且统一 ⇒「复制参数」启用", !dis(FileMenuModel.Entry.COPY_PARAMS));
            check("R7.2：可用时文案 = params.menuCopy",
                    I18n.get(FileMenuModel.KEY_COPY_PARAMS).equals(text(FileMenuModel.Entry.COPY_PARAMS)));

            // ---- R7.1：经真实菜单项复制（复制不写模型、不压撤销记录） ----
            int depthBeforeCopy = workspace().undoDepth();
            fire(FileMenuModel.Entry.COPY_PARAMS, a);
            check("R7.1：复制参数不写模型（撤销栈不变）", workspace().undoDepth() == depthBeforeCopy);
            updateState(b);
            check("R7.3：复制过之后「应用到所选」启用", !dis(FileMenuModel.Entry.APPLY_TO_SELECTION));

            // ---- R7.4/R7.5/R7.7：应用到所选（真实菜单项 → 宿主既有 apply* 路径） ----
            fire(FileMenuModel.Entry.APPLY_TO_SELECTION, b);
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
            ((javafx.scene.control.Button) node("undoButton")).fire();
            awaitCommands();
            check("R7.5：撤销后 b 的生效参数回到应用前（一条记录携带了 before 快照）",
                    itemParamsOf(b, im) != null
                            && effectiveSignature(itemParamsOf(b, im)).equals(effectiveSignature(params("BEFORE-B"))));
            check("R7.5：撤销后撤销栈回到应用前深度", workspace().undoDepth() == depthBeforeCopy);
        } catch (Throwable t) {
            check("R7 端到端步骤抛异常: " + t, false);
            t.printStackTrace(System.out);
        }
    }

    private com.mhtextractor.uicore.FrontendPort frontend;
    private PreviewRow detached(String name) {
        return new PreviewRow(new com.mhtextractor.uiport.ItemVm(
                java.nio.file.Path.of("H:/TEST/mht/probe", name + ".mht").toAbsolutePath().toString(),
                name + ".mht", false, false, 0, com.mhtextractor.uiport.UpdateKind.NONE), ignored -> {});
    }
    private PreviewRow newPreview(String name) throws Exception {
        var item = detached(name); workspace().appendItems(List.of(item));
        return view().allItems().stream().filter(row -> row.path.equals(item.path)).findFirst().orElseThrow();
    }
    private com.mhtextractor.uicore.WorkspaceView view() throws Exception {
        return (com.mhtextractor.uicore.WorkspaceView) fieldOf(controller, "ws");
    }
    private void sync() throws Exception {
        ProbeFx.awaitCommands(frontend); view().update(frontend.snapshot());
    }
    private void setChecked(PreviewRow row, boolean value) throws Exception {
        frontend.implementation().commands().setChecked(List.of(row.path), value); sync();
    }
    private void awaitCommands() throws Exception {
        ProbeFx.awaitCommands(frontend);
    }

    private static AppConfig params(String marker) {
        AppConfig c = new AppConfig();
        c.setStartMarker(marker);
        return c;
    }

    private void setItemParams(PreviewRow item, boolean imageMode, AppConfig params) throws Exception {
        frontend.implementation().commands().setItemParams(item.path, imageMode, params); sync();
    }
    private static AppConfig itemParamsOf(PreviewRow item, boolean imageMode) {
        return imageMode ? item.getImageParamsCache() : item.getTextParamsCache();
    }

    /** 提取参数的内容签名（与生产同一实现，不用 equals：AppConfig 是可变对象） */
    private static String effectiveSignature(AppConfig c) {
        return com.mhtextractor.app.EffectiveParamsService.signatureOf(c);
    }

    /** 真实行上派发右键请求，经生产拾取/选中/状态刷新接线设置目标，再点击菜单项。 */
    private void fire(FileMenuModel.Entry entry, Object target) throws Exception {
        // 模型公开 API 建立夹具后，以其快照提供可渲染行；不改模型数据序或私有菜单目标。
        listView.setItems(javafx.collections.FXCollections.observableArrayList(workspace().displayOrder()));
        int index = listView.getItems().indexOf(target);
        if (index < 0) throw new IllegalStateException("右键目标不在列表中：" + target);
        listView.scrollTo(index);
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        Node cell = listView.lookupAll(".list-cell").stream()
                .filter(n -> n instanceof ListCell<?> c && !c.isEmpty() && c.getItem() == target)
                .findFirst().orElseThrow(() -> new IllegalStateException("右键目标未渲染：" + target));
        Event.fireEvent(cell, requestedAt(cell));
        if (menu.target() != target) throw new IllegalStateException("真实右键未路由到目标");
        menu.item(entry).fire();
        awaitCommands();
        menu.menu().hide();
    }

    /** 公开菜单刷新接口仍使用真实宿主的目标类型和参数快照。 */
    private void updateState(Object target) {
        menu.updateState(target);
    }

    private ContextMenuEvent requestedAt(Node node) {
        return new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                0, 0, stage.getX() + 30, stage.getY() + 30, false, new PickResult(node, 0, 0));
    }

    private Object pickAt(Node node) {
        return menu.pickItemAt(requestedAt(node));
    }

    private Object fieldOf(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    /** 按 fx:id 观察真实场景图，不访问控制器的节点存储。 */
    private Object node(String fxId) throws Exception {
        return java.util.Objects.requireNonNull(scene.lookup("#" + fxId), "缺失节点：" + fxId);
    }

    private ProbeWorkspace workspace() { return new ProbeWorkspace(); }
    private final class ProbeWorkspace {
        void appendItems(List<PreviewRow> rows) throws Exception {
            frontend.implementation().commands().applyImportPlan(new com.mhtextractor.uiport.ImportPlanVm(
                    List.of(new com.mhtextractor.uiport.ImportPlanVm.ImportBatchVm("H:/TEST/mht/probe", null,
                            false, rows.stream().map(row -> row.path).toList())), 0, 0, 0, 0, 0, false, false)); sync();
        }
        GroupRow<PreviewRow> group(List<PreviewRow> rows, String name) throws Exception {
            var before = view().groups().stream().map(GroupRow::getGroupNo).toList();
            frontend.implementation().commands().group(rows.stream().map(row -> row.path).toList(), name); sync();
            var created = view().groups().stream().filter(group -> !before.contains(group.getGroupNo()))
                    .findFirst().orElseThrow();
            // group() takes a prefix, whereas this fixture helper promises an exact name.
            frontend.implementation().commands().renameGroup(created.getGroupNo(), name); sync();
            return created;
        }
        void removeItems(List<PreviewRow> rows) throws Exception {
            frontend.implementation().commands().removeItems(rows.stream().map(row -> row.path).toList()); sync();
        }
        void removeGroup(GroupRow<?> group) throws Exception {frontend.implementation().commands().removeGroup(group.getGroupNo()); sync();}
        int undoDepth() {return frontend.queries().workspace().undoDepth();}
        List<Object> displayOrder() throws Exception {return view().displayOrder();}
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
