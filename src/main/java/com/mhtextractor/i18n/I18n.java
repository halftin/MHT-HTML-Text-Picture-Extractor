package com.mhtextractor.i18n;

import com.mhtextractor.model.ExtractWarning;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 国际化唯一访问入口（禁止业务代码直接 {@link ResourceBundle#getBundle}）。
 *
 * <p><b>占位符语法</b>：具名 {@code {name}}（{@code \w+}），不用 {@code MessageFormat} 的数字索引
 * （翻译时易错位、单引号转义陷阱，见架构设计 §3.1）。</p>
 *
 * <p><b>参数形式</b>：{@link #get(String, Object...)} 的 varargs 为交替的键值对，
 * 即 {@code I18n.get("extract.groupFileCount", "group", "组1", "count", 3)}。
 * 键非 {@code String} 的项被忽略；参数个数为奇数时记 log.warn 并忽略最后一个键。</p>
 *
 * <p><b>缺键策略</b>（架构设计 §3.2）：返回 {@code !key!} 标记并 log.error，不返回 null、不原样返回键名。</p>
 *
 * <p><b>占位符缺参</b>：不静默——保留 {@code {name}} 原样并 log.warn。</p>
 *
 * <p><b>locale 语义</b>：{@link #setLocale(Locale)} 传 null 表示跟随系统默认；目标 locale 的 bundle
 * 不存在时回退 zh_CN（zh_CN 值与既有中文文案一致，保证老用户行为不变）。</p>
 */
@Slf4j
public final class I18n {

    private static final String BASE_NAME = "i18n/messages";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private static volatile ResourceBundle bundle = load(Locale.getDefault());
    private static volatile Locale current = Locale.getDefault();

    /**
     * {@link #msg} 的重入护栏。
     *
     * <p><b>为什么需要</b>：{@code I18n} 自身的诊断日志（如 {@code log.i18n.01} 缺键）
     * 也走 {@link #msg}。若该日志键本身缺失，{@code msg → get → log.error → msg} 会无限递归。
     * 置位期间再次进入直接返回键名，递归被截断为"日志打印键名"这一无害退化。</p>
     */
    private static final ThreadLocal<Boolean> IN_MSG = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private I18n() {
    }

    /**
     * 日志文案入口：占位符为<b>位置索引</b> {@code {0}} {@code {1}} …（{@code log.*} 调用点专用）。
     *
     * <p>与 {@link #get(String, Object...)} 的具名占位符同一渲染引擎（{@code \w+} 本就覆盖数字），
     * 只是把 varargs 由"交替键值对"改写为"按位置取值"，让日志调用点保持
     * {@code log.info(I18n.msg("log.x", a, b))} 的简短形态。</p>
     *
     * @param key  bundle 键（约定前缀 {@code log.}）
     * @param args 按位置对应 {@code {0}} / {@code {1}} … 的值
     * @return 替换后的文案；缺键返回 {@code !key!}
     */
    public static String msg(String key, Object... args) {
        if (Boolean.TRUE.equals(IN_MSG.get())) {
            return key;
        }
        IN_MSG.set(Boolean.TRUE);
        try {
            if (args.length == 0) {
                return get(key);
            }
            Object[] pairs = new Object[args.length * 2];
            for (int i = 0; i < args.length; i++) {
                pairs[i * 2] = String.valueOf(i);
                // 必须先 String.valueOf：get() 用 "值为 null" 表示"占位符缺参"并保留 {n} 原样，
                // 而日志实参为 null 是常态（如 config.getInputFolder() 未设置）。
                // logback 的 {} 遇到 null 打印 "null"，此处保持同一语义 ——
                // 否则会退化成把模板里的 {0} 原样打进日志。
                pairs[i * 2 + 1] = String.valueOf(args[i]);
            }
            return get(key, pairs);
        } finally {
            IN_MSG.set(Boolean.FALSE);
        }
    }

    /**
     * 取键文案并替换具名占位符。
     *
     * @param key  bundle 键
     * @param args 交替键值对："name", value, "name2", value2…
     * @return 替换后的文案；缺键返回 {@code !key!}
     */
    public static String get(String key, Object... args) {
        String template;
        try {
            template = bundle.getString(key);
        } catch (MissingResourceException e) {
            log.error(I18n.msg("log.i18n.01", key));
            return "!" + key + "!";
        }
        return render(key, template, args);
    }

    /**
     * 渲染结构化警告（E4.1）：把 {@link ExtractWarning} 的具名参数摊平成
     * {@link #get(String, Object...)} 的交替键值对形式。占位符按名匹配，
     * Map 迭代顺序无关。缺键行为与 {@link #get} 一致（返回 {@code !key!} + log.error）。
     */
    public static String render(ExtractWarning warning) {
        Map<String, String> args = warning.args();
        Object[] flat = new Object[args.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> e : args.entrySet()) {
            flat[i++] = e.getKey();
            flat[i++] = e.getValue();
        }
        return get(warning.key(), flat);
    }

    /**
     * 切换语言。传 null 跟随系统默认（{@link Locale#getDefault()}）。
     * 应在启动时、任何界面构建之前调用（FXML 的 %key 解析发生在 FXMLLoader.load 期间）。
     */
    public static void setLocale(Locale locale) {
        Locale target = locale != null ? locale : Locale.getDefault();
        current = target;
        bundle = load(target);
    }

    /** 当前生效的 locale。 */
    public static Locale currentLocale() {
        return current;
    }

    /**
     * 供 FXMLLoader 注入（会话 15 FXML 外置用）：{@code loader.setResources(I18n.bundle())}。
     * 业务代码取文案请走 {@link #get}。
     */
    public static ResourceBundle bundle() {
        return bundle;
    }

    /**
     * 把持久化字符串解析为 Locale。支持 {@code "zh_CN"} / {@code "en_US"}（也容忍 {@code "zh-CN"} 连字符）。
     * 空白或无法解析返回 null（调用方按"跟随系统"处理）。
     */
    public static Locale parseLocale(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String[] parts = s.trim().replace('-', '_').split("_");
        if (parts.length == 0) {
            return null;
        }
        if (parts.length >= 2 && !parts[1].isBlank()) {
            return new Locale(parts[0], parts[1]);
        }
        if (!parts[0].isBlank()) {
            return new Locale(parts[0]);
        }
        return null;
    }

    private static ResourceBundle load(Locale locale) {
        try {
            return ResourceBundle.getBundle(BASE_NAME, locale);
        } catch (MissingResourceException e) {
            log.warn(I18n.msg("log.i18n.02", locale));
            return ResourceBundle.getBundle(BASE_NAME, Locale.SIMPLIFIED_CHINESE);
        }
    }

    private static String render(String key, String template, Object... args) {
        if (args.length == 0 || template.indexOf('{') < 0) {
            return template;
        }
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (args[i] instanceof String name) {
                values.put(name, args[i + 1]);
            }
        }
        if ((args.length & 1) == 1) {
            log.warn(I18n.msg("log.i18n.03", key, args[args.length - 1]));
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        List<String> missing = null;
        while (m.find()) {
            String name = m.group(1);
            Object value = values.get(name);
            if (value != null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(value)));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement("{" + name + "}"));
                if (missing == null) {
                    missing = new ArrayList<>();
                }
                missing.add(name);
            }
        }
        m.appendTail(sb);
        if (missing != null) {
            log.warn(I18n.msg("log.i18n.04", key, missing));
        }
        return sb.toString();
    }
}
