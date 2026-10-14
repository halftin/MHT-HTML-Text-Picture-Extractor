package com.mhtextractor.uicore;

import com.mhtextractor.app.EffectiveParamsService;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.model.ParamScope;

/**
 * 「参数目标信息栏」的<b>纯文案层</b>（R1.4）：把「目标 + 条目显示名 + 组内是否混合」拼成一行文本。
 *
 * <h2>为什么把它从控件里剥出来（本步的可测性要求）</h2>
 * <p>控件（{@link ParamTargetBar}）是 {@code HBox}，无头测试<b>构造不了</b>它（需要 JavaFX 工具包）。
 * 若把拼文案写在控件里，"信息栏显示对不对"就只能靠人眼看图。故这里只留两个<b>静态纯函数</b>：
 * {@link #forTarget} 与 {@link #isGroupMixed}，两者零 JavaFX 依赖 ⇒ 可被普通 JUnit 直接断言
 * （本类也因此能进无头依赖闭包）。控件只剩"把字符串塞进 Label"这一件事。</p>
 *
 * <h2>括号为什么不硬编码</h2>
 * <p>「组（组名）」的括号<b>复用</b>既有的 {@code dialog.parenOpen} / {@code dialog.parenClose}
 * （标题里组名的括号就是这一对）——
 * 既不在 Java 里写死全角括号（那会让 en_US 也显示中文括号），也不新增一对同义键。
 * 括号由 Java 拼成后作为 {@code {name}} 实参交给 {@code paramTarget.group/item} 模板，
 * 于是"中文紧贴括号、英文括号前留空格"这种<b>与语言相关的排版</b>留在 bundle 里
 * （{@code 组{name}} vs {@code Group {name}}），Java 侧一个标点都不写。</p>
 *
 * <h2>条目显示名从哪来</h2>
 * <p>{@link ParamScope} 刻意<b>不感知</b> {@code model.PreviewItem}（它用泛型 {@code T} 接调用方条目类型），
 * 所以"文件名"不能在 {@code util} 层解析。这里采用两段式：调用方可以传现成的显示名
 * （{@code itemDisplayName}，优先）；没传时由本层按 {@code instanceof PreviewItem} 解析 ——
 * {@code gui} 层依赖 {@code model} 是允许的方向（{@code gui → app → 业务包/model}）。
 * 这样 {@code ParamFormPane} 只需转发 target，不必自己知道条目类型。</p>
 */
public final class ParamTargetText {
    public static String forViewTarget(com.mhtextractor.uicore.ParamScope.Target target, String name, boolean mixed) {
        String base=switch(target){
            case com.mhtextractor.uicore.ParamScope.GroupTarget g -> I18n.get("paramTarget.group","name",paren(g.group().getName()));
            case com.mhtextractor.uicore.ParamScope.ItemTarget<?> i -> I18n.get("paramTarget.item","name",paren(name!=null?name:i.item() instanceof ParamTargetView view?view.getName():""));
            case null, default -> I18n.get("paramTarget.global");
        };
        return mixed?base+" "+I18n.get("paramTarget.groupMixed"):base;
    }

    /** 无状态服务（{@link EffectiveParamsService} 不持有可变状态，可安全共享）。 */
    private static final EffectiveParamsService EFFECTIVE = new EffectiveParamsService();

    private ParamTargetText() {
    }

    /**
     * 信息栏文本：`全局` ／ `组（组名）` ／ `条目（文件名）`；组内参数不统一时追加一句提示。
     *
     * @param target          参数目标（{@code null} 视为全局，便于降级路径不炸）
     * @param itemDisplayName 条目显示名；{@code null} / 空白时由条目自身解析（见类注释）
     * @param groupMixed      组内生效参数是否混合（R1.4 可选增强项，由 {@link #isGroupMixed} 判定）
     */
    public static String forTarget(ParamScope.Target target, String itemDisplayName, boolean groupMixed) {
        String base = switch (target) {
            case ParamScope.GroupTarget gt ->
                    I18n.get("paramTarget.group", "name", paren(gt.group().getName()));
            case ParamScope.ItemTarget<?> it ->
                    I18n.get("paramTarget.item", "name", paren(displayName(it.item(), itemDisplayName)));
            case ParamScope.GlobalTarget ignored -> I18n.get("paramTarget.global");
            case null -> I18n.get("paramTarget.global");
        };
        // 分隔符只用一个半角空格 + 文案自带的前导分隔符（见 paramTarget.groupMixed 的值），
        // 这样 Java 里不出现任何用户可见标点（空格不算标点）。
        return groupMixed ? base + " " + I18n.get("paramTarget.groupMixed") : base;
    }

    /**
     * 组目标的组内参数是否<b>不统一</b>（R1.4 增强项）。
     *
     * <p>判定本身<b>不在这里新写</b>：交给 {@link EffectiveParamsService#hasMixedEffectiveParams}
     * （Q1 交付的唯一实现点）。本方法只做"仅组目标才判"的路由，故 R1.4 的提示与列表里其他
     * "混合/独立参数"判定永远同源，不会各自演化。</p>
     *
     * <p>边界（沿用服务口径）：非组目标 → {@code false}；空组/单成员组 → {@code false}；
     * {@code null}（无生效参数）算作一种独立取值。</p>
     */
    public static boolean isGroupMixed(ParamScope.Target target, EffectiveParamsService.Context ctx) {
        return target instanceof ParamScope.GroupTarget gt
                && EFFECTIVE.hasMixedEffectiveParams(gt.group().getMembers(), ctx);
    }

    /** 括号对（复用既有键）：{@code zh}=（）、{@code en}=()。 */
    private static String paren(String name) {
        return I18n.get("dialog.parenOpen") + (name == null ? "" : name) + I18n.get("dialog.parenClose");
    }

    /** 条目显示名：调用方给的优先；否则按条目类型解析；都没有 → 空串（不抛、不打印对象 ref）。 */
    private static String displayName(Object item, String provided) {
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        if (item instanceof PreviewItem p) {
            if (p.name != null && !p.name.isBlank()) {
                return p.name;
            }
            return baseName(p.path);
        }
        return item == null ? "" : String.valueOf(item);
    }

    /** 取路径末段（纯字符串切分：不用 {@code Path.of} —— 非法路径字符会抛 {@code InvalidPathException}）。 */
    private static String baseName(String path) {
        if (path == null) {
            return "";
        }
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return cut >= 0 && cut + 1 < path.length() ? path.substring(cut + 1) : path;
    }
}
