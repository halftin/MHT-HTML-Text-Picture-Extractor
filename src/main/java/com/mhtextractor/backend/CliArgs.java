package com.mhtextractor.backend;

import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.AppConfig;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 手写 CLI 参数（P4-1）—— 不引入第三方解析库（{@code noNewThirdPartyDependencies}）。
 *
 * <p><b>选项面</b>：</p>
 * <ul>
 *   <li>模式（二选一，必填）：{@code --once}（批处理）/ {@code --serve}（JSON Lines IPC）；</li>
 *   <li>{@code --once} 参数：{@code --input <path>}（可重复，必填）、{@code --output <dir>}（必填）、
 *       {@code --extract text|images}（默认 text）、{@code --separate}、{@code --group}、
 *       {@code --template-start <s>} / {@code --template-end <e>}（给出任一即开启自定义模板）；</li>
 *   <li>{@code --config <path>}（仅 once）：UTF-8 AppConfig JSON，使用命名配置持久化属性名，
 *       如 {@code enableLimit/enableTitleExtract/legacyMemoryMode}；省略字段保留 POJO 默认值。
 *       显式 {@code --separate} 覆盖 separateFiles；显式模板开关开启 enableCustomTemplate，
 *       仅覆盖给出的 startTemplate/endTemplate（与参数顺序无关）。输入/输出、模式、locale 独立于配置。
 *       未知/被忽略的运行时字段、类型不符（不做标量转换）、布尔 null、语法错误、重复键、
 *       尾随 JSON 值及非对象根均为退出码 2；字符串允许 null。</li>
 *   <li>公共：{@code --locale <zh|en|…>}（默认跟随系统）、{@code -h/--help}。</li>
 * </ul>
 *
 * <p><b>退出码语义由入口统一交付</b>：0 = 成功；1 = 运行时失败（可读原因到 stderr）；
 * 2 = 用法错误（{@link UsageException}）。</p>
 *
 * @param mode          运行模式（{@code help == true} 时为 null）
 * @param inputs        {@code --input} 原始路径（按出现顺序）
 * @param output        {@code --output} 目录
 * @param imageMode     {@code --extract images}
 * @param separate      {@code --separate}（文本单独模式）
 * @param group         {@code --group}（提取前把全部条目并为一组）
 * @param templateStart {@code --template-start} 原文（未做换行转义；转义由 FileWriter.processTemplate 统一处理）
 * @param templateEnd   {@code --template-end} 原文
 * @param locale        业务层 locale（null = 跟随系统默认）
 * @param config        已校验的命名配置（null = 未指定 --config）
 */
public record CliArgs(Mode mode, boolean help, List<Path> inputs, Path output,
                      boolean imageMode, boolean separate, boolean group,
                      String templateStart, String templateEnd, Locale locale, AppConfig config) {

    /** 运行模式 */
    public enum Mode { ONCE, SERVE }

    /** 用法错误：可读原因随异常带出，入口以退出码 2 交付并附用法说明 */
    public static final class UsageException extends Exception {
        public UsageException(String message) {
            super(message);
        }
    }

    /** 解析命令行（缺失值 / 未知选项 / 模式与参数组合不合法 → {@link UsageException}） */
    public static CliArgs parse(String[] args) throws UsageException {
        Mode mode = null;
        boolean help = false;
        List<Path> inputs = new ArrayList<>();
        Path output = null;
        boolean imageMode = false;
        boolean separate = false;
        boolean group = false;
        String templateStart = null;
        String templateEnd = null;
        Locale locale = null;
        Path configPath = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h", "--help" -> help = true;
                case "--once" -> mode = Mode.ONCE;
                case "--serve" -> mode = Mode.SERVE;
                case "--separate" -> separate = true;
                case "--group" -> group = true;
                case "--input", "--output", "--extract", "--locale",
                        "--template-start", "--template-end", "--config" -> {
                    if (i + 1 >= args.length) {
                        throw new UsageException("missing value for " + a);
                    }
                    String v = args[++i];
                    switch (a) {
                        case "--input" -> inputs.add(Path.of(v));
                        case "--output" -> output = Path.of(v);
                        case "--config" -> configPath = Path.of(v);
                        case "--extract" -> {
                            switch (v) {
                                case "text" -> imageMode = false;
                                case "images" -> imageMode = true;
                                default -> throw new UsageException(
                                        "invalid value for --extract: " + v + " (expected text|images)");
                            }
                        }
                        case "--locale" -> {
                            locale = I18n.parseLocale(v);
                            if (locale == null) {
                                throw new UsageException("invalid value for --locale: " + v);
                            }
                        }
                        case "--template-start" -> templateStart = v;
                        case "--template-end" -> templateEnd = v;
                        default -> throw new IllegalStateException("unreachable: " + a);
                    }
                }
                default -> {
                    if (a.startsWith("--")) {
                        throw new UsageException("unknown option: " + a);
                    }
                    throw new UsageException("unexpected positional argument: " + a);
                }
            }
        }

        if (help) {
            return new CliArgs(null, true, List.of(), null, false, false, false, null, null, null, null);
        }
        if (mode == null) {
            throw new UsageException("missing mode: specify --once or --serve");
        }
        if (mode == Mode.ONCE) {
            if (inputs.isEmpty()) {
                throw new UsageException("--once requires at least one --input <path>");
            }
            if (output == null) {
                throw new UsageException("--once requires --output <dir>");
            }
        } else {
            boolean onceOnly = output != null || !inputs.isEmpty() || separate || group
                    || templateStart != null || templateEnd != null || configPath != null;
            if (onceOnly) {
                throw new UsageException(
                        "--input/--output/--separate/--group/--template-*/--config are not supported with --serve in this batch");
            }
        }
        return new CliArgs(mode, false, List.copyOf(inputs), output, imageMode, separate, group,
                templateStart, templateEnd, locale, configPath == null ? null : readConfig(configPath));
    }

    private static AppConfig readConfig(Path path) throws UsageException {
        JsonMapper json = JsonMapper.builder()
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .build();
        for (CoercionInputShape shape : List.of(CoercionInputShape.Integer,
                CoercionInputShape.Float, CoercionInputShape.Boolean)) {
            json.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
        }
        try {
            // Read UTF-8 explicitly; native image's platform charset may be GBK.
            AppConfig config = json.readValue(Files.readString(path), AppConfig.class);
            if (config == null) throw new UsageException("invalid --config " + path + ": expected a JSON object");
            return config;
        } catch (IOException e) {
            throw new UsageException("invalid --config " + path + ": " + e.getMessage());
        }
    }
}
