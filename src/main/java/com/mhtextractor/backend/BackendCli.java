package com.mhtextractor.backend;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;

/**
 * 无头后端入口（P4-1 骨架）—— 与 JavaFX 启动器 {@code com.mhtextractor.App} 互不影响。
 *
 * <p><b>模式</b>：{@code --once}（导入 → 建组 → 设参 → 提取后退出）/ {@code --serve}（JSON Lines IPC）。</p>
 *
 * <p><b>退出码</b>：0 = 成功；1 = 运行时失败（可读原因到 stderr）；2 = 用法错误（附用法说明）。</p>
 *
 * <p><b>无头 logback（冲突 C8）</b>：本类<b>不持有静态 logger</b> —— 类初始化先于 {@code main} 方法体，
 * 若在此初始化 logger 会在选定无头配置前触发 logback 上下文构建（GUI 的 {@code logback.xml} 会先被自动发现）。
 * 因此"选定配置"必须是 main 的第一件事，之后再触发上下文初始化并校验
 * <b>无头进程不得装载 GUI 日志包（{@code com.mhtextractor.logging}，唯一允许引用 UI 框架的主源集日志包）的 appender</b>。
 * 校验是运行时闸门而非仅测试断言：任何把 JavaFX appender 引回无头配置的改动会在启动时以退出码 1 失败（控制组实测）。</p>
 *
 * <p><b>零 UI 框架</b>：本包由 {@code ArchitectureTest} 按字节码常量池强制。</p>
 */
public final class BackendCli {

    /** 无头 logback 配置（classpath 资源；用户显式设置 {@code logback.configurationFile} 时优先用户值） */
    private static final String HEADLESS_LOGBACK_CONFIG = "logback-headless.xml";

    /** GUI 日志包前缀：无头进程的 logback 根 appender 不得来自该包（其字节码依赖 UI 框架） */
    private static final String UI_LOGGING_PACKAGE = "com.mhtextractor.logging.";

    /** 退出码：成功 */
    static final int EXIT_OK = 0;
    /** 退出码：运行时失败（原因已打印到 stderr） */
    static final int EXIT_FAILURE = 1;
    /** 退出码：用法错误（原因 + 用法说明已打印到 stderr） */
    static final int EXIT_USAGE = 2;

    private BackendCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    private static int run(String[] args) {
        selectHeadlessLogbackConfig();
        CliArgs opts;
        try {
            opts = CliArgs.parse(args);
        } catch (CliArgs.UsageException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println(usage());
            return EXIT_USAGE;
        }
        if (opts.help()) {
            System.out.println(usage());
            return EXIT_OK;
        }
        // 触发 logback 上下文初始化（配置已在上方选定），随后校验无头进程未装载 GUI 日志包 appender（C8 运行时闸门）
        org.slf4j.LoggerFactory.getILoggerFactory();
        int guard = verifyNoUiLoggingAppenders();
        if (guard != 0) {
            return guard;
        }
        return switch (opts.mode()) {
            case ONCE -> new OnceRunner().run(opts);
            case SERVE -> new ServeRunner().run(opts);
        };
    }

    /**
     * 选定无头 logback 配置。必须在<b>任何</b> logger 工厂调用之前执行（logback 上下文只在首次使用时构建）。
     * 用户已显式设置 {@code logback.configurationFile} 时不覆盖（尊重外部注入，运行时闸门仍会校验结果）。
     */
    private static void selectHeadlessLogbackConfig() {
        if (System.getProperty("logback.configurationFile") == null) {
            System.setProperty("logback.configurationFile", HEADLESS_LOGBACK_CONFIG);
        }
    }

    /**
     * 无头 logback 闸门（冲突 C8 的运行时一侧）：根 appender 一律不得来自 GUI 日志包
     * （{@code com.mhtextractor.logging.TextAreaAppender} 的字节码引用 UI 框架，
     * 无头进程（尤其 native 后端子集）装载即崩）。
     *
     * @return {@link #EXIT_OK} 或 {@link #EXIT_FAILURE}（违反时已打印可读原因）
     */
    private static int verifyNoUiLoggingAppenders() {
        if (!(org.slf4j.LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)) {
            return EXIT_OK;   // 非 logback 上下文无从校验（本项目绑定 logback，不会走到）
        }
        for (var it = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).iteratorForAppenders();
                it.hasNext(); ) {
            Appender<ILoggingEvent> appender = it.next();
            String name = appender.getClass().getName();
            if (name.startsWith(UI_LOGGING_PACKAGE)) {
                System.err.println("fatal: headless backend must not load UI-framework logback appender: "
                        + name);
                return EXIT_FAILURE;
            }
        }
        return EXIT_OK;
    }

    /** 用法说明（--help / 用法错误时打印） */
    private static String usage() {
        return """
                usage: BackendCli (--once [once-options] | --serve)

                modes (exactly one required):
                  --once                       run import -> group -> params -> extract, then exit
                  --serve                      JSON Lines v1 over stdio (EOF on stdin exits)

                once options:
                  --input <path>               importable file or directory (repeatable, required)
                  --output <dir>               output directory (required, created if missing)
                  --extract <text|images>      extraction kind (default: text)
                  --separate                   text: one output file per input (default: merged)
                  --group                      group all imported items into one group before extraction
                  --template-start <text>      enable custom text template with this start marker
                  --template-end <text>        enable custom text template with this end marker

                common options:
                  --locale <zh|en|...>         business-layer locale for product naming (default: system)
                  -h, --help                   print this help and exit 0

                exit codes: 0 ok | 1 runtime failure (reason on stderr) | 2 usage error
                """;
    }
}
