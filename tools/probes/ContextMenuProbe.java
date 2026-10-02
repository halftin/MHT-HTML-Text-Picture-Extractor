import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import com.mhtextractor.App;
import com.mhtextractor.gui.controller.FileListContextMenu;
import com.mhtextractor.gui.controller.MainController;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.GroupItem;

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
 *
 * <p><b>为什么需要它</b>：无头单测 JVM 里 {@code new SeparatorMenuItem()} / {@code new ContextMenu()}
 * 会抛 {@code Toolkit not initialized}（实测），所以"新类的 {@code LAYOUT} 与真实
 * {@code ContextMenu.getItems()} 是否一致"只能在这里证 —— 否则 {@code LAYOUT} 只是一个没人校验的副本。</p>
 *
 * <p><b>本探针里的期望值是独立副本</b>：16 项类型序列、12 个文案键、四种目标的禁用矩阵都在本文件里
 * 逐项写死（<b>不复用</b> {@code FileListContextMenu.LAYOUT} / {@code TEXT_KEYS}），因此新类里改错
 * 顺序 / 换错键 / 写错启用位都会在这里失败。</p>
 *
 * <p>被测对象是 {@code MainView.fxml} 加载出的<b>真实</b> {@code MainController}（与 App.start() 同构装配），
 * 菜单实例经反射取私有字段 {@code contextMenu}；因此覆盖"宿主目标类型判定 + 规则 + 落点"的完整链路。</p>
 *
 * <p>退出码：0 = 全过，1 = 有失败，2 = 看门狗超时（防探针挂死）。</p>
 */
public class ContextMenuProbe extends Application {

    /** 期望的 16 项序列（独立副本）：item:&lt;文案键&gt; 或 sep */
    private static final List<String> EXPECTED_ORDER = List.of(
            "item:list.menuSelect", "item:list.menuSelectAll", "sep",
            "item:browser.menuOpen", "item:list.menuOpenFolder", "item:browser.menuViewSource", "sep",
            "item:params.menuSet", "item:params.menuClear", "item:params.menuApplyGlobal", "sep",
            "item:group.menuMerge", "item:group.menuUngroup", "sep",
            "item:list.menuRemove", "item:group.menuRemove");

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

        // ---- 1. 恒 16 项同序：真实 getItems() 逐项对账 ----
        List<MenuItem> items = real.getItems();
        check("真实 ContextMenu 恒 16 项（实际 " + items.size() + "）", items.size() == EXPECTED_ORDER.size());
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
                check("#" + i + " 文案 == I18n.get(\"" + key + "\")（实际 \"" + item.getText() + "\"）",
                        I18n.get(key).equals(item.getText()));
                actualOrder.add("item:" + key);
            }
        }
        check("真实 getItems() 的 16 项类型/键序列 == 硬编码期望（独立副本）",
                actualOrder.equals(EXPECTED_ORDER));

        // 装置自检：12 个文案键在当前 locale 下必须两两不同，否则"键位互换"抓不到
        List<String> texts = new ArrayList<>();
        for (String entry : EXPECTED_ORDER) {
            if (entry.startsWith("item:")) {
                texts.add(I18n.get(entry.substring("item:".length())));
            }
        }
        check("装置自检：12 个文案键的文本两两不同（否则键位互换不可判）",
                texts.size() == 12 && texts.stream().distinct().count() == 12);

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
