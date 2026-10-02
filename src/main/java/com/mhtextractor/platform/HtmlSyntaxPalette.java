package com.mhtextractor.platform;

/**
 * 源码高亮的<b>配色唯一来源</b>（Q8b / R2.4 的"两个渲染器共用"）。
 *
 * <p><b>为什么存在</b>：高亮其实有两半 —— ① 「哪段源码属于哪一类」由
 * {@link HtmlSyntaxTokens} 回答；② 「那一类长什么样」原先只写在
 * {@code SourceViewPage} 的 {@code <style>} 里（一份给外部浏览器看的 CSS）。
 * Q8b 要在<b>应用内</b>用 JavaFX 节点渲染同一个 token 流，如果颜色只写在 HTML 页的
 * {@code <style>} 里，JavaFX 那一侧就只能把 hex 抄一遍 —— 那就是 R2.4 要消灭的
 * 「同一语义两份独立实现」（改了一处、另一处悄悄过期）。</p>
 *
 * <p>本枚举因此是「类别 → CSS 类名 + 颜色」的唯一定义处：{@code SourceViewPage} 由它
 * {@linkplain #styleBlock() 生成样式块}（输出与迁移前逐字节相同，由 Q8a 的 63 个用例钉死），
 * 应用内查看器由它取 {@linkplain #color() 颜色}。</p>
 *
 * <p><b>边界</b>：零 JavaFX、零第三方依赖、纯 Java —— 与 {@link HtmlSyntaxTokens} 同包同性质，
 * 于是 {@code gui → platform} 的依赖方向不变。</p>
 *
 * <p>⚠️ 颜色值改动会同时影响外部浏览器页与应用内查看器；若改动渲染结构（而不是颜色），
 * 必须重跑 Q8a 的逐字节装置。</p>
 */
public enum HtmlSyntaxPalette {

    /** 标签名、尖括号、斜杠（原本 {@code .hl-tag}）。 */
    TAG("hl-tag", "#0000ff"),
    /** 属性名与 {@code =}（原本 {@code .hl-attr}）。 */
    ATTR("hl-attr", "#a31515"),
    /** 非 URL 的属性值（原本 {@code .hl-val}）。 */
    VALUE("hl-val", "#008000"),
    /** 注释（原本 {@code .hl-cm}，斜体）。 */
    COMMENT("hl-cm", "#808080"),
    /** doctype（原本 {@code .hl-dt}）。 */
    DOCTYPE("hl-dt", "#808080"),
    /** 文本里的 URL（原本 {@code .hl-link}，下划线）。 */
    LINK("hl-link", "#0000ee");

    /** URL 的配色（属性值里的 URL 用 {@link #VALUE} 的绿底 + 这个蓝字 + 下划线）。 */
    public static final String URL_COLOR = LINK.color;
    /** 正文文本的颜色（{@code Kind.TEXT} 没有 CSS 类，页面上继承 {@code .lc} 的黑）。 */
    public static final String TEXT_COLOR = "#000000";
    /** 行号栏的颜色（{@code SourceViewPage} 的 {@code .ln}）。 */
    public static final String GUTTER_COLOR = "#9e9e9e";

    private final String cssClass;
    private final String color;

    HtmlSyntaxPalette(String cssClass, String color) {
        this.cssClass = cssClass;
        this.color = color;
    }

    /** CSS 类名（与 {@link HtmlSyntaxTokens.Kind#cssClass()} 同一个字符串）。 */
    public String cssClass() {
        return cssClass;
    }

    /** 颜色字面量（{@code #rrggbb}）。 */
    public String color() {
        return color;
    }

    /** 是否斜体（只有注释）。 */
    public boolean italic() {
        return this == COMMENT;
    }

    /** 是否下划线（只有 URL 样式；页面上的 {@code .hl-link} 规则）。 */
    public boolean underline() {
        return this == LINK;
    }

    /** 按 CSS 类名反查（{@link HtmlSyntaxTokens.Kind#cssClass()} 里可能有空格分隔的复合类名）。 */
    public static HtmlSyntaxPalette ofCssClass(String cssClass) {
        for (HtmlSyntaxPalette p : values()) {
            if (p.cssClass.equals(cssClass)) {
                return p;
            }
        }
        return null;
    }

    /**
     * {@link HtmlSyntaxTokens.Kind} → 配色。
     *
     * <p>{@code VAL_LINK}（"整串是 URL 的属性值"）的 CSS 类名是
     * {@code "hl-val hl-link"} 两个类：底色用 {@link #VALUE}（绿），但颜色与下划线取
     * {@link #LINK} —— 与页面上两个规则叠加的结果一致（后写的 {@code .hl-link} 赢）。</p>
     */
    public static HtmlSyntaxPalette of(HtmlSyntaxTokens.Kind kind) {
        return switch (kind) {
            case TAG -> TAG;
            case ATTR -> ATTR;
            case VAL -> VALUE;
            case VAL_LINK -> LINK;
            case COMMENT -> COMMENT;
            case DOCTYPE -> DOCTYPE;
            case LINK -> LINK;
            case TEXT -> null;
        };
    }

    /**
     * 外部浏览器页的 {@code <style>} 片段（Q8a：与迁移前<b>逐字节相同</b>）。
     *
     * <p>⚠️ 规则顺序与拼接方式都是字节级契约（{@code compare-sourceview-baseline.js} 逐字节比对），
     * 不要重排、不要换行、不要把 {@code italic} 单独拆行。</p>
     */
    public static String styleBlock() {
        return ".hl-tag{color:" + TAG.color + ";}.hl-attr{color:" + ATTR.color + ";}"
                + ".hl-val{color:" + VALUE.color + ";}"
                + ".hl-cm{color:" + COMMENT.color + ";font-style:italic;}"
                + ".hl-dt{color:" + DOCTYPE.color + ";}"
                + ".hl-link{color:" + LINK.color + ";text-decoration:underline;}";
    }
}
