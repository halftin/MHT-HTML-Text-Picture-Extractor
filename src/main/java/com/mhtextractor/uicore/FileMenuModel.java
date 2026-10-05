package com.mhtextractor.uicore;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.mhtextractor.app.ParamsInfo;

/**
 * 文件列表右键菜单的<b>纯模型层</b>（Q9a / P4b-①，P2-2 从 {@code gui.controller.FileListContextMenu} 原样迁出）：
 * 目标类型快照（{@link TargetType}）、菜单条目身份（{@link Entry}）、菜单结构（{@link #LAYOUT}）、
 * 文案键（{@link #KEY_PARAMS} 等）、启用状态（{@link State}）与两条纯函数
 * （{@link #stateFor} / {@link #applyToSelectionTextKey}）。
 *
 * <p>零 JavaFX、零副作用：不构造任何 JavaFX 对象、不认模型类型 —— 参数判定快照 {@code app.ParamsInfo}
 * 由宿主算好传入。故可被普通 JUnit 直接断言；JavaFX 壳（{@code MenuItem} 装配 / 动作转发 /
 * 显示生命周期）留在 {@code gui.controller.FileListContextMenu}，经本类取数。</p>
 *
 * <p><b>只搬不改</b>：菜单项顺序 / 分隔符位置 / 文案键 / 启用位矩阵 / 「应用到所选」5 档文案规则
 * 全部逐字保留。</p>
 */
public final class FileMenuModel {

    private FileMenuModel() {
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

    /** 功能二：「应用到所选」五档文案键（按<b>实际作用集合</b>分类）。 */
    public static final String KEY_APPLY_THIS = "params.menuApplyToThisItem";

    /** 功能二：组外项 ≥2 个且无组。 */
    public static final String KEY_APPLY_MANY = "params.menuApplyToManyItems";

    /** 功能二：只有组相关（组条目 / 组内成员），任意多个组。 */
    public static final String KEY_APPLY_GROUP = "params.menuApplyToGroup";

    /** 功能二：组外项 == 1 且有组。 */
    public static final String KEY_APPLY_GROUP_AND_THIS = "params.menuApplyToGroupAndThisItem";

    /** 功能二：组外项 ≥2 且有组。 */
    public static final String KEY_APPLY_GROUP_AND_MANY = "params.menuApplyToGroupAndMany";

    /** 五档文案键（供探针"∈ 5 档"特判与文案两两不同自检使用）。 */
    public static final List<String> APPLY_TO_SELECTION_KEYS = List.of(
            KEY_APPLY_THIS, KEY_APPLY_MANY, KEY_APPLY_GROUP, KEY_APPLY_GROUP_AND_THIS, KEY_APPLY_GROUP_AND_MANY);

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
        keys.put(Entry.APPLY_TO_SELECTION, KEY_APPLY_THIS);
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
                        String applyToSelectionTextKey,
                        boolean group,
                        boolean ungroup,
                        boolean remove,
                        boolean removeGroup) {
    }

    /**
     * 功能二：按<b>实际作用集合</b>给出「应用到所选」的文案键（纯函数，5 档）。
     *
     * <p>分类只用两个计数：组外项数 {@code topLevelCount} 与去重组数 {@code distinctGroupCount}。
     * 有效集合为空（两者皆 0）时"保持现状" —— 不新增档，落在默认档 {@link #KEY_APPLY_THIS}
     * （此时菜单项通常因未复制而禁用）。</p>
     */
    public static String applyToSelectionTextKey(int topLevelCount, int distinctGroupCount) {
        if (distinctGroupCount > 0) {
            return topLevelCount >= 2 ? KEY_APPLY_GROUP_AND_MANY
                    : topLevelCount == 1 ? KEY_APPLY_GROUP_AND_THIS
                    : KEY_APPLY_GROUP;
        }
        return topLevelCount >= 2 ? KEY_APPLY_MANY : KEY_APPLY_THIS;
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
        String applyKey = applyToSelectionTextKey(info.topLevelCount(), info.distinctGroupCount());

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
                applyKey,               // 应用到所选文案键（功能二：按实际作用集合 5 档）
                item,                   // 建组：仅项目条目
                group,                  // 拆分组：仅组条目
                item,                   // 移除：仅项目条目（组条目走"移除组"）
                type.member() || group);// 移除组：组内成员 / 组条目
    }
}
