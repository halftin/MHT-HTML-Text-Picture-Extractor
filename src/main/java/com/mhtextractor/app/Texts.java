package com.mhtextractor.app;

import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.ExtractWarning;

import java.util.Locale;
import java.util.ResourceBundle;

/**
 * 文案门面（P3-3b-24）：P3 端口文案能力在进程内的实现 —— {@code gui} 对 {@code i18n.I18n}
 * 的全部静态调用经本门面 1:1 委托，文案键、实参、占位符语义与缺键返回 {@code !key!} 行为逐字不变，
 * gui 层不再直连 {@code i18n.I18n}，只依赖本门面。
 *
 * <p>本类<b>零 JavaFX</b>，属 {@code app} 层。</p>
 */
public final class Texts {

    private Texts() {
    }

    /**
     * 取键文案并替换具名占位符：1:1 委托 {@link I18n#get(String, Object...)}。
     *
     * @param key  bundle 键
     * @param args 交替键值对："name", value, "name2", value2…
     * @return 替换后的文案；缺键返回 {@code !key!}
     */
    public static String get(String key, Object... args) {
        return I18n.get(key, args);
    }

    /**
     * 日志文案入口：1:1 委托 {@link I18n#msg(String, Object...)}（占位符为位置索引 {@code {0}} {@code {1}} …）。
     *
     * @param key  bundle 键（约定前缀 {@code log.}）
     * @param args 按位置对应 {@code {0}} / {@code {1}} … 的值
     * @return 替换后的文案；缺键返回 {@code !key!}
     */
    public static String msg(String key, Object... args) {
        return I18n.msg(key, args);
    }

    /**
     * 渲染结构化警告（E4.1）：1:1 委托 {@link I18n#render(ExtractWarning)}。
     */
    public static String render(ExtractWarning warning) {
        return I18n.render(warning);
    }

    public static String extractionSummary(com.mhtextractor.model.ExtractionStats stats) {
        return com.mhtextractor.i18n.ExtractionTexts.summary(stats);
    }

    public static String extractDetail(String key, java.util.Map<String, String> params,
                                       String diagnostic, java.util.List<ExtractWarning> warnings) {
        return com.mhtextractor.i18n.ExtractionTexts.detail(key, params, diagnostic, warnings);
    }

    /**
     * 供 FXMLLoader 注入：1:1 委托 {@link I18n#bundle()}（{@code loader.setResources(Texts.bundle())}）。
     * 业务代码取文案请走 {@link #get}。
     */
    public static ResourceBundle bundle() {
        return I18n.bundle();
    }

    /** 当前生效的 locale：1:1 委托 {@link I18n#currentLocale()}。 */
    public static Locale currentLocale() {
        return I18n.currentLocale();
    }
}
