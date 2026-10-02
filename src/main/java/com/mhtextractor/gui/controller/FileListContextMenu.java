package com.mhtextractor.gui.controller;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.mhtextractor.i18n.I18n;

import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseEvent;

/**
 * 文件列表右键菜单（Q9a / P4b-①）：<b>菜单项的唯一所有者</b> + <b>启用规则</b> + <b>动作转发</b> +
 * <b>显示/隐藏生命周期</b>。
 *
 * <p><b>这里没有业务逻辑</b>：14 个动作的<b>实现</b>（建组 / 拆组 / 移除 / 清参数 / 应用全局 /
 * 复制参数 / 应用到所选 / 打开目录 / 查看源码）仍留在 {@code MainController} —— 它们要动模型、缓存与
 * UI 状态；本类只负责"点了哪一项、右键目标是谁"，经 {@link Actions} 回调过去。这是"少一次坐标变更"的最小切法。</p>
 *
 * <p><b>依赖面刻意收窄</b>：本类只依赖 JavaFX 控件（{@code ListView} / {@code ContextMenu} /
 * {@code MenuItem} / {@code ScrollBar}）、同包视图类 {@code PreviewListCell}、i18n 与窄接口
 * {@link Actions}；<b>零模型 / 缓存 / 通知类型依赖</b> —— 目标类型判定（含"已移除项不算目标"）与
 * Q10 的"有生效参数 / 参数是否统一 / 是否已复制"都由宿主经 {@link Actions} 提供，本类只认
 * {@link TargetType} 这个四布尔快照与 {@link ParamsInfo} 这个参数判定快照。</p>
 *
 * <p><b>从 {@code MainController} 整体搬来</b>（搬前行号）：12 个 {@code ctx*} 字段、{@code buildContextMenu}、
 * {@code onContextMenuRequested}、{@code pickItemAt}、{@code updateContextMenuState}，以及
 * "点击菜单外即关闭"的 {@code MOUSE_PRESSED} 按压过滤器。行为逐条保持：文案键、项顺序与分隔符位置、
 * 启用规则、动作语义、菜单项的 {@code setDisable} 集合全部未改。Q10（R7.1）在此基础上加两项
 * （{@link Entry#COPY_PARAMS} / {@link Entry#APPLY_TO_SELECTION}）。</p>
 *
 * <p><b>可无头单测的纯逻辑</b>：{@link #LAYOUT}（恒 18 项同序：14 项 + 4 分隔符）、{@link #TEXT_KEYS}
 * （文案键）、{@link #stateFor}（启用规则纯函数）都不构造任何 JavaFX 对象；构造器只建 14 个
 * {@code MenuItem}（实测无 toolkit 也可构造），只有 {@link #install} 里的
 * {@code SeparatorMenuItem} / {@code ContextMenu} 需要 toolkit。</p>
 */
public final class FileListContextMenu {

    /**
     * 宿主回调面（窄接口）：6 个查询 + 14 个动作转发。
     *
     * <p>刻意<b>不</b>持有 {@code MainController}，也<b>不</b>认模型类型：目标类型判定返回纯快照
     * {@link TargetType}，本类据此算启用状态。</p>
     *
     * <p>14 个动作一律在<b>点击时</b>取 {@link #target()} 当前值（与搬前读 {@code contextTarget}
     * 字段的时机一致）；{@code group / ungroup / remove} 与按钮行共用实现，走宿主的"当前选中/勾选"
     * 口径，故不带目标参数。</p>
     */
    public interface Actions {
        /** 拖拽进行中不弹菜单（搬前 {@code dragActive}） */
        boolean isDragActive();

        /** 列表可见项数：空白处右键且为 0 时不出菜单（搬前 {@code visibleCount()}） */
        long visibleCount();

        /** 把右键命中的那一行设为列表选中（不改勾选、不动 Shift 锚点） */
        void selectRow(Object target);

        /** 目标类型快照（搬前 {@code updateContextMenuState} 头四行的判定；模型类型判定留在宿主） */
        TargetType targetType(Object target);

        /** 命中行是否仍可作为右键目标（搬前 {@code pickItemAt} 的"已移除项返回 null"判定） */
        boolean isLiveTarget(Object item);

        /**
         * R7.2/R7.3/R7.6：复制/应用两项的<b>参数判定快照</b>（4 个布尔一次给全）：目标是否有独立参数、
         * 是否有生效参数、生效参数是否统一、是否已复制过。
         *
         * <p>四项判定都留在宿主（"有生效参数 / 是否统一"必须走 {@code EffectiveParamsService}，
         * R7.6 明令不得各写一套），本类只做布尔组合 ⇒ 菜单类依旧零模型 / 零缓存依赖。</p>
         */
        ParamsInfo paramsInfo(Object target);

        void select(Object target);

        void selectAll(Object target);

        void openFolder(Object target);

        void openInBrowser(Object target);

        void viewSource(Object target);

        void params(Object target);

        void clearParams(Object target);

        void applyGlobal(Object target);

        /** R7.1：复制右键目标集合的生效参数快照到剪贴板 */
        void copyParams(Object target);

        /** R7.1：把剪贴板里的参数快照应用到所选（R7.4 复用确认路径的应用函数） */
        void applyToSelection(Object target);

        void group();

        void ungroup();

        void remove();

        void removeGroup(Object target);
    }

    /**
     * 右键目标类型快照：<b>与搬前 {@code updateContextMenuState} 的头四行一一对应</b>。
     *
     * <p>做成"四个布尔"而不是枚举，是为了<b>不改行为</b>：原实现允许"非空但既不是条目也不是组"的
     * 目标（四个标记全 false ⇒ 与空白区域<b>不同</b>：勾选项仍是启用的）；枚举化会把它悄悄并到
     * 某一个分支上。</p>
     */
    public record TargetType(boolean blank, boolean topItem, boolean member, boolean group) {

        /** 空白区域（{@code target == null}） */
        public static final TargetType BLANK = new TargetType(true, false, false, false);
        /** 顶层项目条目（{@code parentGroup == null}） */
        public static final TargetType TOP_ITEM = new TargetType(false, true, false, false);
        /** 组内成员（{@code parentGroup != null}） */
        public static final TargetType MEMBER = new TargetType(false, false, true, false);
        /** 组条目 */
        public static final TargetType GROUP = new TargetType(false, false, false, true);

        /** {@code topItem || member} —— 规则里反复出现的"是项目条目" */
        public boolean item() {
            return topItem || member;
        }
    }

    /**
     * 菜单条目身份。<b>恒 18 项同序</b>：14 个菜单项 + 4 个分隔符；分隔符用 {@link #isSeparator()}
     * 区分，顺序与分组边界由 {@link #LAYOUT} 唯一决定。
     *
     * <p>Q10 新增 {@link #COPY_PARAMS} / {@link #APPLY_TO_SELECTION}（R7.1）—— 紧贴既有参数三项收在
     * <b>同一组</b>（第 3 组），组内顺序 = 设置参数 / 清除参数 / 应用到全局 / 复制参数 / 应用到所选。</p>
     */
    public enum Entry {
        SELECT(false),
        SELECT_ALL(false),
        SEPARATOR_1(true),
        OPEN_IN_BROWSER(false),
        OPEN_FOLDER(false),
        VIEW_SOURCE(false),
        SEPARATOR_2(true),
        PARAMS(false),
        CLEAR_PARAMS(false),
        APPLY_GLOBAL(false),
        COPY_PARAMS(false),
        APPLY_TO_SELECTION(false),
        SEPARATOR_3(true),
        GROUP(false),
        UNGROUP(false),
        SEPARATOR_4(true),
        REMOVE(false),
        REMOVE_GROUP(false);

        private final boolean separator;

        Entry(boolean separator) {
            this.separator = separator;
        }

        public boolean isSeparator() {
            return separator;
        }
    }

    /**
     * 菜单结构（<b>唯一事实来源</b>）：菜单项创建、分隔符插入、{@code ContextMenu} 装配、
     * 单测的顺序断言全部从这里派生，因此"顺序/分隔符位置"不可能只在某一处被改动。
     *
     * <p>⚠️ 易错点（搬前 {@code MainController:1200-1209}）：{@code ctxOpenInBrowser} 在
     * {@code ctxOpenFolder} <b>之前</b> —— 不是字段声明顺序。</p>
     *
     * <p>Q10：R7.1 的两项并进第 3 组末尾（组内 5 项），故分隔符下标由 2/6/10/13 变为
     * <b>2/6/12/15</b>；分隔符个数仍为 4，其余三组的成员与相对顺序未动。</p>
     */
    public static final List<Entry> LAYOUT = List.of(
            Entry.SELECT, Entry.SELECT_ALL,
            Entry.SEPARATOR_1,
            Entry.OPEN_IN_BROWSER, Entry.OPEN_FOLDER, Entry.VIEW_SOURCE,
            Entry.SEPARATOR_2,
            Entry.PARAMS, Entry.CLEAR_PARAMS, Entry.APPLY_GLOBAL, Entry.COPY_PARAMS, Entry.APPLY_TO_SELECTION,
            Entry.SEPARATOR_3,
            Entry.GROUP, Entry.UNGROUP,
            Entry.SEPARATOR_4,
            Entry.REMOVE, Entry.REMOVE_GROUP);

    /** "设置提取参数"菜单项的静态文案键（空白区域改用 {@link #KEY_PARAMS_GLOBAL}） */
    public static final String KEY_PARAMS = "params.menuSet";

    /** "设置提取参数"在空白区域目标下的文案键（全局参数入口） */
    public static final String KEY_PARAMS_GLOBAL = "params.menuSetGlobal";

    /** 「复制参数」可用时的文案键（R7.2） */
    public static final String KEY_COPY_PARAMS = "params.menuCopy";

    /** 「复制参数」禁用时的文案键：右键目标<b>没有生效参数</b>（R7.2 理由一，禁用理由写在选项文本里） */
    public static final String KEY_COPY_NO_PARAMS = "params.menuCopyNoParams";

    /** 「复制参数」禁用时的文案键：多选目标的生效参数<b>不统一</b>（R7.2 理由二） */
    public static final String KEY_COPY_MIXED_PARAMS = "params.menuCopyMixedParams";

    /** 14 个菜单项的文案键（逐字照抄搬前 {@code buildContextMenu} 的 {@code I18n.get} 实参） */
    public static final Map<Entry, String> TEXT_KEYS = textKeys();

    private static Map<Entry, String> textKeys() {
        Map<Entry, String> keys = new EnumMap<>(Entry.class);
        keys.put(Entry.SELECT, "list.menuSelect");
        keys.put(Entry.SELECT_ALL, "list.menuSelectAll");
        keys.put(Entry.OPEN_FOLDER, "list.menuOpenFolder");
        keys.put(Entry.OPEN_IN_BROWSER, "browser.menuOpen");
        keys.put(Entry.VIEW_SOURCE, "browser.menuViewSource");
        keys.put(Entry.PARAMS, KEY_PARAMS);
        keys.put(Entry.CLEAR_PARAMS, "params.menuClear");
        keys.put(Entry.APPLY_GLOBAL, "params.menuApplyGlobal");
        keys.put(Entry.COPY_PARAMS, KEY_COPY_PARAMS);
        keys.put(Entry.APPLY_TO_SELECTION, "params.menuApplyToSelection");
        keys.put(Entry.GROUP, "group.menuMerge");
        keys.put(Entry.UNGROUP, "group.menuUngroup");
        keys.put(Entry.REMOVE, "list.menuRemove");
        keys.put(Entry.REMOVE_GROUP, "group.menuRemove");
        return Map.copyOf(keys);
    }

    /**
     * 启用状态快照（纯数据、零 JavaFX）：{@link #stateFor} 的<b>全部</b>输出。
     *
     * <p>字段语义是"启用"（与 {@code MenuItem.isDisable()} 相反），字段名即菜单项语义。
     * {@code copyParamsTextKey} 是「复制参数」这一项的<b>当前文案键</b>：R7.2 要求禁用理由写在
     * 选项文本里（不靠 tooltip），故文案键与启用位一起从 {@link #stateFor} 算出。</p>
     */
    public record State(boolean select,
                        boolean selectAll,
                        boolean openFolder,
                        boolean openInBrowser,
                        boolean viewSource,
                        boolean params,
                        boolean paramsGlobalText,
                        boolean clearParams,
                        boolean applyGlobal,
                        boolean copyParams,
                        String copyParamsTextKey,
                        boolean applyToSelection,
                        boolean group,
                        boolean ungroup,
                        boolean remove,
                        boolean removeGroup) {
    }

    /**
     * 复制/应用两项的输入快照（R7.2/R7.3/R7.6）—— 全部由宿主判定后传入，本类只做布尔组合：
     *
     * @param independent 目标生效参数来源是否为独立参数（既有 13 个启用位用；决定清除参数/应用到全局）
     * @param hasEffective 目标集合里是否有生效参数（R7.2 理由一的<b>反面</b>）
     * @param uniform     目标集合的生效参数是否统一（R7.2 理由二的<b>反面</b>）
     * @param copied      是否已复制过参数（R7.3）
     */
    public record ParamsInfo(boolean independent, boolean hasEffective, boolean uniform, boolean copied) {
    }

    /**
     * 启用规则（逐条照抄搬前的 {@code updateContextMenuState} + Q10 的 R7.2/R7.3）：目标类型 +
     * 参数判定快照 → 每一项的启用状态。纯函数、无副作用、不碰 JavaFX、不认模型类型。
     *
     * <p>Q10 的两个新位：</p>
     * <ul>
     *   <li>{@code copyParams}：可用 ⇔ {@code hasEffective && uniform}（"有生效参数"与"是否统一"
     *       的判定在宿主，必须经 {@code EffectiveParamsService}）；文案键按 R7.2 的二择一给出。</li>
     *   <li>{@code applyToSelection}：可用 ⇔ {@code copied}（R7.3）。</li>
     * </ul>
     *
     * @param type 右键目标类型快照（由宿主判定）
     * @param info 参数判定快照（由宿主判定）
     */
    public static State stateFor(TargetType type, ParamsInfo info) {
        boolean blank = type.blank();
        boolean group = type.group();
        boolean item = type.item();
        boolean copy = info.hasEffective() && info.uniform();
        String copyKey = !info.hasEffective() ? KEY_COPY_NO_PARAMS
                : (info.uniform() ? KEY_COPY_PARAMS : KEY_COPY_MIXED_PARAMS);

        return new State(
                !blank,                 // 勾选：项目条目勾选自身；组条目整组勾选
                true,                   // 全选：恒可用（成员/组 → 该组；顶层/空白 → 全列表）
                item,                   // 打开所在文件夹
                item,                   // 在浏览器中查看
                item,                   // 查看源码
                true,                   // 设置参数：恒可用
                blank,                  // 参数文案：空白区域是全局参数入口
                info.independent(),     // 清除参数
                info.independent(),     // 应用到全局：仅目标有独立参数时可用
                copy,                   // 复制参数（R7.2）
                copyKey,                // 复制参数文案键（R7.2：禁用理由写在文本里）
                info.copied(),          // 应用到所选（R7.3：未复制过 ⇒ 不可交互）
                item,                   // 建组：仅项目条目
                group,                  // 拆分组：仅组条目
                item,                   // 移除：仅项目条目（组条目走"移除组"）
                type.member() || group);// 移除组：组内成员 / 组条目
    }

    private final Actions actions;
    private final Map<Entry, MenuItem> items = new EnumMap<>(Entry.class);

    private ContextMenu menu;
    private ListView<Object> listView;

    /** 右键目标（模型条目或组条目对象；类型判定在宿主，本类只存引用）；null = 空白区域 */
    private Object target;

    /** 菜单显示期间的主窗口按压过滤：点击菜单外任意处即关闭（声明在 {@link #menu} 之后） */
    private final EventHandler<MouseEvent> clickAway = _ -> {
        if (menu.isShowing()) {
            menu.hide();
        }
    };

    public FileListContextMenu(Actions actions) {
        this.actions = actions;
        for (Entry entry : LAYOUT) {
            if (!entry.isSeparator()) {
                items.put(entry, new MenuItem(I18n.get(TEXT_KEYS.get(entry))));
            }
        }
        wireActions();
    }

    /** 14 个菜单项的动作转发（分隔符没有动作） */
    private void wireActions() {
        items.get(Entry.SELECT).setOnAction(_ -> actions.select(target));
        items.get(Entry.SELECT_ALL).setOnAction(_ -> actions.selectAll(target));
        items.get(Entry.OPEN_FOLDER).setOnAction(_ -> actions.openFolder(target));
        items.get(Entry.OPEN_IN_BROWSER).setOnAction(_ -> actions.openInBrowser(target));
        items.get(Entry.VIEW_SOURCE).setOnAction(_ -> actions.viewSource(target));
        items.get(Entry.PARAMS).setOnAction(_ -> actions.params(target));
        items.get(Entry.CLEAR_PARAMS).setOnAction(_ -> actions.clearParams(target));
        items.get(Entry.APPLY_GLOBAL).setOnAction(_ -> actions.applyGlobal(target));
        items.get(Entry.COPY_PARAMS).setOnAction(_ -> actions.copyParams(target));
        items.get(Entry.APPLY_TO_SELECTION).setOnAction(_ -> actions.applyToSelection(target));
        items.get(Entry.GROUP).setOnAction(_ -> actions.group());
        items.get(Entry.UNGROUP).setOnAction(_ -> actions.ungroup());
        items.get(Entry.REMOVE).setOnAction(_ -> actions.remove());
        items.get(Entry.REMOVE_GROUP).setOnAction(_ -> actions.removeGroup(target));
    }

    /**
     * 建菜单并挂到列表上（取代搬前的 {@code buildContextMenu} + {@code setOnContextMenuRequested}）。
     *
     * <p>项顺序与分隔符位置完全由 {@link #LAYOUT} 派生；"点击菜单外即关闭"的按压过滤器在菜单
     * <b>显示期间</b>挂在主窗口上、<b>隐藏时</b>移除（JavaFX autoHide 在本应用窗口组合下不可靠）。</p>
     */
    public void install(ListView<Object> list) {
        this.listView = list;
        List<MenuItem> menuItems = new ArrayList<>(LAYOUT.size());
        for (Entry entry : LAYOUT) {
            menuItems.add(entry.isSeparator() ? new SeparatorMenuItem() : items.get(entry));
        }
        this.menu = new ContextMenu(menuItems.toArray(MenuItem[]::new));

        menu.setOnShown(_ -> {
            if (list.getScene() != null) {
                list.getScene().getWindow()
                        .addEventFilter(MouseEvent.MOUSE_PRESSED, clickAway);
            }
        });
        menu.setOnHidden(_ -> {
            if (list.getScene() != null) {
                list.getScene().getWindow()
                        .removeEventFilter(MouseEvent.MOUSE_PRESSED, clickAway);
            }
        });

        list.setOnContextMenuRequested(this::onRequested);
    }

    /**
     * 右键菜单弹出（搬前 {@code onContextMenuRequested}）：定位目标 → 选中该行（不改勾选、不动 Shift
     * 锚点）→ 按目标类型刷新禁用状态 → 显示。拖拽中不弹菜单；列表完全为空不出菜单；
     * 动画锁定期间照常弹出，动作由宿主既有的 {@code listAnimating} 守卫拦截。
     */
    private void onRequested(ContextMenuEvent e) {
        if (actions.isDragActive()) {
            return;
        }
        target = pickItemAt(e);
        if (target == null && actions.visibleCount() == 0) {
            return;
        }
        if (target != null) {
            actions.selectRow(target);
        }
        updateState(target);
        menu.show(listView, e.getScreenX(), e.getScreenY());
        e.consume();
    }

    /** 右键位置拾取列表条目：命中节点向上找单元格；未命中 cell = 空白区域 */
    public Object pickItemAt(ContextMenuEvent e) {
        return pickFrom(e.getPickResult().getIntersectedNode());
    }

    /**
     * {@link #pickItemAt} 的节点遍历本体（搬前 {@code pickItemAt} 的循环逐字搬移）：先遇到滚动条则
     * 不算列表区域；命中单元格后交宿主判定该行是否仍可作为目标（已移除项 → null）。
     *
     * <p>拆出无事件依赖的一层，使"滚动条 / 空白处右键 → null"可以不经 {@code ContextMenuEvent}
     * 直接单测。</p>
     */
    Object pickFrom(Node node) {
        Node cur = node;
        while (cur != null) {
            if (cur instanceof ScrollBar) {
                return null;    // 滚动条右键不视为列表区域
            }
            if (cur instanceof PreviewListCell c) {
                Object item = c.getItem();
                return actions.isLiveTarget(item) ? item : null;
            }
            cur = cur.getParent();
        }
        return null;
    }

    /**
     * 菜单项禁用状态按右键目标类型刷新（搬前 {@code updateContextMenuState} 的语义：<b>只读入参</b>，
     * 不改 {@link #target} —— 因此本方法可以从演练/测试代码用任意目标调用而不影响下一次真实弹出）。
     */
    public void updateState(Object t) {
        apply(stateFor(actions.targetType(t), actions.paramsInfo(t)));
    }

    /** 把启用规则的结果逐项落到菜单项上（字段名与 {@link State} 一一对应） */
    private void apply(State state) {
        items.get(Entry.SELECT).setDisable(!state.select());
        items.get(Entry.SELECT_ALL).setDisable(!state.selectAll());
        items.get(Entry.OPEN_FOLDER).setDisable(!state.openFolder());
        items.get(Entry.OPEN_IN_BROWSER).setDisable(!state.openInBrowser());
        items.get(Entry.VIEW_SOURCE).setDisable(!state.viewSource());
        items.get(Entry.PARAMS).setDisable(!state.params());
        items.get(Entry.PARAMS).setText(I18n.get(state.paramsGlobalText() ? KEY_PARAMS_GLOBAL : KEY_PARAMS));
        items.get(Entry.CLEAR_PARAMS).setDisable(!state.clearParams());
        items.get(Entry.APPLY_GLOBAL).setDisable(!state.applyGlobal());
        items.get(Entry.COPY_PARAMS).setDisable(!state.copyParams());
        items.get(Entry.COPY_PARAMS).setText(I18n.get(state.copyParamsTextKey()));
        items.get(Entry.APPLY_TO_SELECTION).setDisable(!state.applyToSelection());
        items.get(Entry.GROUP).setDisable(!state.group());
        items.get(Entry.UNGROUP).setDisable(!state.ungroup());
        items.get(Entry.REMOVE).setDisable(!state.remove());
        items.get(Entry.REMOVE_GROUP).setDisable(!state.removeGroup());
    }

    /** 当前菜单（供"关闭前隐藏"与测试读取；{@link #install} 之前为 {@code null}） */
    public ContextMenu menu() {
        return menu;
    }

    /** 菜单项访问口（{@link Entry#isSeparator()} 的条目没有对应项，返回 {@code null}） */
    public MenuItem item(Entry entry) {
        return items.get(entry);
    }

    /** {@code ctxClearParams} 对应的那一个菜单项（宿主把它暴露给演练代码读 {@code isDisable()}） */
    public MenuItem clearParamsItem() {
        return items.get(Entry.CLEAR_PARAMS);
    }

    /** 当前右键目标（{@code null} = 空白区域）；动作回调转发的就是它 */
    public Object target() {
        return target;
    }
}
