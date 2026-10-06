package com.mhtextractor.gui.controller;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.mhtextractor.app.ParamsInfo;
import com.mhtextractor.app.Texts;
import com.mhtextractor.uicore.FileMenuModel;

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
 * {@link FileMenuModel.TargetType} 这个四布尔快照与 {@link ParamsInfo} 这个参数判定快照。</p>
 *
 * <p><b>从 {@code MainController} 整体搬来</b>（搬前行号）：12 个 {@code ctx*} 字段、{@code buildContextMenu}、
 * {@code onContextMenuRequested}、{@code pickItemAt}、{@code updateContextMenuState}，以及
 * "点击菜单外即关闭"的 {@code MOUSE_PRESSED} 按压过滤器。行为逐条保持：文案键、项顺序与分隔符位置、
 * 启用规则、动作语义、菜单项的 {@code setDisable} 集合全部未改。Q10（R7.1）在此基础上加两项
 * （{@link FileMenuModel.Entry#COPY_PARAMS} / {@link FileMenuModel.Entry#APPLY_TO_SELECTION}）。</p>
 *
 * <p><b>可无头单测的纯逻辑</b>（P2-2 已迁入 {@link FileMenuModel}）：{@link FileMenuModel#LAYOUT}
 * （恒 18 项同序：14 项 + 4 分隔符）、{@link FileMenuModel#TEXT_KEYS}（文案键）、
 * {@link FileMenuModel#stateFor}（启用规则纯函数）都不构造任何 JavaFX 对象；构造器只建 14 个
 * {@code MenuItem}（实测无 toolkit 也可构造），只有 {@link #install} 里的
 * {@code SeparatorMenuItem} / {@code ContextMenu} 需要 toolkit。</p>
 */
public final class FileListContextMenu {

    /**
     * 宿主回调面（窄接口）：6 个查询 + 14 个动作转发。
     *
     * <p>刻意<b>不</b>持有 {@code MainController}，也<b>不</b>认模型类型：目标类型判定返回纯快照
     * {@link FileMenuModel.TargetType}，本类据此算启用状态。</p>
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
        FileMenuModel.TargetType targetType(Object target);

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

    private final Actions actions;
    private final Map<FileMenuModel.Entry, MenuItem> items = new EnumMap<>(FileMenuModel.Entry.class);

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
        for (FileMenuModel.Entry entry : FileMenuModel.LAYOUT) {
            if (!entry.isSeparator()) {
                items.put(entry, new MenuItem(Texts.get(FileMenuModel.TEXT_KEYS.get(entry))));
            }
        }
        wireActions();
    }

    /** 14 个菜单项的动作转发（分隔符没有动作） */
    private void wireActions() {
        items.get(FileMenuModel.Entry.SELECT).setOnAction(_ -> actions.select(target));
        items.get(FileMenuModel.Entry.SELECT_ALL).setOnAction(_ -> actions.selectAll(target));
        items.get(FileMenuModel.Entry.OPEN_FOLDER).setOnAction(_ -> actions.openFolder(target));
        items.get(FileMenuModel.Entry.OPEN_IN_BROWSER).setOnAction(_ -> actions.openInBrowser(target));
        items.get(FileMenuModel.Entry.VIEW_SOURCE).setOnAction(_ -> actions.viewSource(target));
        items.get(FileMenuModel.Entry.PARAMS).setOnAction(_ -> actions.params(target));
        items.get(FileMenuModel.Entry.CLEAR_PARAMS).setOnAction(_ -> actions.clearParams(target));
        items.get(FileMenuModel.Entry.APPLY_GLOBAL).setOnAction(_ -> actions.applyGlobal(target));
        items.get(FileMenuModel.Entry.COPY_PARAMS).setOnAction(_ -> actions.copyParams(target));
        items.get(FileMenuModel.Entry.APPLY_TO_SELECTION).setOnAction(_ -> actions.applyToSelection(target));
        items.get(FileMenuModel.Entry.GROUP).setOnAction(_ -> actions.group());
        items.get(FileMenuModel.Entry.UNGROUP).setOnAction(_ -> actions.ungroup());
        items.get(FileMenuModel.Entry.REMOVE).setOnAction(_ -> actions.remove());
        items.get(FileMenuModel.Entry.REMOVE_GROUP).setOnAction(_ -> actions.removeGroup(target));
    }

    /**
     * 建菜单并挂到列表上（取代搬前的 {@code buildContextMenu} + {@code setOnContextMenuRequested}）。
     *
     * <p>项顺序与分隔符位置完全由 {@link FileMenuModel#LAYOUT} 派生；"点击菜单外即关闭"的按压过滤器在菜单
     * <b>显示期间</b>挂在主窗口上、<b>隐藏时</b>移除（JavaFX autoHide 在本应用窗口组合下不可靠）。</p>
     */
    public void install(ListView<Object> list) {
        this.listView = list;
        List<MenuItem> menuItems = new ArrayList<>(FileMenuModel.LAYOUT.size());
        for (FileMenuModel.Entry entry : FileMenuModel.LAYOUT) {
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
        apply(FileMenuModel.stateFor(actions.targetType(t), actions.paramsInfo(t)));
    }

    /** 把启用规则的结果逐项落到菜单项上（字段名与 {@link FileMenuModel.State} 一一对应） */
    private void apply(FileMenuModel.State state) {
        items.get(FileMenuModel.Entry.SELECT).setDisable(!state.select());
        items.get(FileMenuModel.Entry.SELECT_ALL).setDisable(!state.selectAll());
        items.get(FileMenuModel.Entry.OPEN_FOLDER).setDisable(!state.openFolder());
        items.get(FileMenuModel.Entry.OPEN_IN_BROWSER).setDisable(!state.openInBrowser());
        items.get(FileMenuModel.Entry.VIEW_SOURCE).setDisable(!state.viewSource());
        items.get(FileMenuModel.Entry.PARAMS).setDisable(!state.params());
        items.get(FileMenuModel.Entry.PARAMS).setText(Texts.get(state.paramsGlobalText() ? FileMenuModel.KEY_PARAMS_GLOBAL : FileMenuModel.KEY_PARAMS));
        items.get(FileMenuModel.Entry.CLEAR_PARAMS).setDisable(!state.clearParams());
        items.get(FileMenuModel.Entry.APPLY_GLOBAL).setDisable(!state.applyGlobal());
        items.get(FileMenuModel.Entry.COPY_PARAMS).setDisable(!state.copyParams());
        items.get(FileMenuModel.Entry.COPY_PARAMS).setText(Texts.get(state.copyParamsTextKey()));
        items.get(FileMenuModel.Entry.APPLY_TO_SELECTION).setDisable(!state.applyToSelection());
        items.get(FileMenuModel.Entry.APPLY_TO_SELECTION).setText(Texts.get(state.applyToSelectionTextKey()));
        items.get(FileMenuModel.Entry.GROUP).setDisable(!state.group());
        items.get(FileMenuModel.Entry.UNGROUP).setDisable(!state.ungroup());
        items.get(FileMenuModel.Entry.REMOVE).setDisable(!state.remove());
        items.get(FileMenuModel.Entry.REMOVE_GROUP).setDisable(!state.removeGroup());
    }

    /** 当前菜单（供"关闭前隐藏"与测试读取；{@link #install} 之前为 {@code null}） */
    public ContextMenu menu() {
        return menu;
    }

    /** 菜单项访问口（{@link FileMenuModel.Entry#isSeparator()} 的条目没有对应项，返回 {@code null}） */
    public MenuItem item(FileMenuModel.Entry entry) {
        return items.get(entry);
    }

    /** {@code ctxClearParams} 对应的那一个菜单项（宿主把它暴露给演练代码读 {@code isDisable()}） */
    public MenuItem clearParamsItem() {
        return items.get(FileMenuModel.Entry.CLEAR_PARAMS);
    }

    /** 当前右键目标（{@code null} = 空白区域）；动作回调转发的就是它 */
    public Object target() {
        return target;
    }
}
