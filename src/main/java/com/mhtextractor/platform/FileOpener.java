package com.mhtextractor.platform;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 用「系统默认程序」打开文件（零 JavaFX、零第三方依赖，native-image 友好）。
 *
 * <p><b>为什么需要它</b>：2026-09-27 实测发现，PreviewListCell → 预览区单击缩略图这条链路上，
 * 单纯的 {@code cmd /c start "" "<图片>"} 在<b>某些环境</b>里会「静默成功但什么都不发生」：
 * 调用方拿到退出码 0、无任何输出，而屏幕上没有窗口。原因是
 * <b>UAC 关闭（{@code EnableLUA=0}）的会话里所有进程都是高完整性令牌，Windows 不支持从提权调用方
 * 对 UWP/AppX 应用做「文件激活」</b>——处理器进程会被拉起来，但窗口永远不会出现，且不报错。
 * 此时连资源管理器里双击同一张图片也打不开（同一根因，已实测）。</p>
 *
 * <p><b>三级策略</b>（每级都用"退出码 + 输出是否干净"做粗校验）：</p>
 * <ol>
 *   <li>{@link Route#DEFAULT_VIEWER} —— {@code cmd /c start "" "<file>"}：交给 Windows 文件关联，
 *       语义最正确（用户装了什么默认看图器就用什么）；</li>
 *   <li>{@link Route#PHOTOS_PROTOCOL} —— {@code cmd /c start ms-photos:viewer?fileName=<urlencoded>}：
 *       <b>协议激活</b>不受上述限制（实测在 UAC 关闭的会话里仍能正常弹出「照片」窗口），
 *       作为环境受损时的兜底；</li>
 *   <li>{@link Route#FAILED} —— 两级都失败，由调用方如实报错（<b>绝不谎报"已打开"</b>）。</li>
 * </ol>
 *
 * <p>⚠️ 第 2 级把处理器写死成「照片」，只应作<b>兜底</b>而非首选：否则用户在别的机器上把默认看图器
 * 设成 IrfanView/ImageGlass 也会被忽略。因此仅在「第 1 级失败」或「环境已判定为
 * UWP 文件激活被阻断」时才使用。</p>
 */
public final class FileOpener {

    /** 打开图片实际走通的路径 */
    public enum Route {
        /** 系统默认查看器（文件关联） */
        DEFAULT_VIEWER,
        /** 兜底：「照片」应用的协议激活 */
        PHOTOS_PROTOCOL,
        /** 两条路都没成 */
        FAILED
    }

    /** 启动器最长等待：超时视为未按预期启动（例如卡在对话框上），随即杀进程 */
    private static final int LAUNCH_TIMEOUT_SECONDS = 3;

    private static final String ENABLE_LUA_KEY =
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Policies\\System";
    private static final String FILE_EXTS_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\";

    /** 「照片」应用的文件查看协议（实测可用于打开本地图片：{@code ms-photos:viewer?fileName=<urlencoded>}） */
    private static final String PHOTOS_VIEWER_URI_PREFIX = "ms-photos:viewer?fileName=";

    /** UAC 是否已关闭（null = 尚未探测）。探测结果对进程生命周期内不变，故缓存 */
    private static volatile Boolean uacDisabledCache;

    private FileOpener() {
    }

    /**
     * 打开文件：优先系统默认查看器；环境已知无法做 UWP 文件激活、或默认查看器明确失败时，
     * 改走「照片」协议兜底。
     *
     * @return 实际走通的路径；全失败返回 {@link Route#FAILED}
     */
    public static Route open(Path file) {
        if (uwpFileActivationBlocked(file)) {
            // 已知环境：第 1 级会被静默吞掉（exit=0、无输出、无窗口），直接先试协议
            if (launch(photosProtocolArgv(file))) {
                return Route.PHOTOS_PROTOCOL;
            }
        }
        if (launch(defaultViewerArgv(file))) {
            return Route.DEFAULT_VIEWER;
        }
        return launch(photosProtocolArgv(file)) ? Route.PHOTOS_PROTOCOL : Route.FAILED;
    }

    // ==================== 环境判定（UAC 关闭 + 该扩展名默认处理器是打包应用） ====================

    /**
     * 本机是否处于「UWP 文件激活被阻断」的状态：<b>UAC 已关闭</b> 且 该扩展名的默认处理器是
     * <b>打包应用</b>（ProgId 形如 {@code AppX<hash>}）。
     *
     * <p>两个条件缺一不可：UAC 关闭但默认处理器是经典 Win32 程序（如 IrfanView）时文件激活照常可用，
     * 此时不应绕开第 1 级。</p>
     */
    static boolean uwpFileActivationBlocked(Path file) {
        if (!uacDisabled()) {
            return false;
        }
        return isPackagedProgId(userChoiceProgId(extensionOf(file)));
    }

    /** 打包应用的文件处理器 ProgId 一律形如 {@code AppX<hash>}（经典 Win32 处理器不是这个形状） */
    static boolean isPackagedProgId(String progId) {
        return progId != null && progId.startsWith("AppX");
    }

    /** 小写扩展名（含点）；无扩展名返回空串 */
    static String extensionOf(Path file) {
        if (file == null || file.getFileName() == null) {
            return "";
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    static boolean uacDisabled() {
        Boolean cached = uacDisabledCache;
        if (cached == null) {
            cached = parseEnableLua(regQuery(ENABLE_LUA_KEY, "EnableLUA"));
            uacDisabledCache = cached;
        }
        return cached;
    }

    /** {@code reg query ... /v EnableLUA} 的输出 → 是否 {@code 0x0}（0 = UAC 关闭）。读不到按"未关闭"处理 */
    static boolean parseEnableLua(String regOutput) {
        if (regOutput == null) {
            return false;
        }
        for (String line : regOutput.lines().toList()) {
            int idx = line.indexOf("REG_DWORD");
            if (idx < 0) {
                continue;
            }
            String raw = line.substring(idx + "REG_DWORD".length()).trim();
            try {
                String hex = raw.startsWith("0x") || raw.startsWith("0X") ? raw.substring(2) : raw;
                return Long.parseLong(hex.trim(), 16) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /** 该扩展名在资源管理器里的用户选择（UserChoice\ProgId）；读不到返回 null */
    static String userChoiceProgId(String ext) {
        if (ext == null || ext.isEmpty()) {
            return null;
        }
        String out = regQuery(FILE_EXTS_KEY + ext + "\\UserChoice", "ProgId");
        if (out == null) {
            return null;
        }
        for (String line : out.lines().toList()) {
            int idx = line.indexOf("REG_SZ");
            if (idx >= 0) {
                String value = line.substring(idx + "REG_SZ".length()).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    /** {@code reg query <key> /v <name>}；非 0 退出/超时/异常都返回 null */
    private static String regQuery(String key, String valueName) {
        try {
            Process p = new ProcessBuilder("reg", "query", key, "/v", valueName)
                    .redirectErrorStream(true).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            byte[] bytes = p.getInputStream().readAllBytes();
            if (p.exitValue() != 0) {
                return null;
            }
            return new String(bytes, Charset.defaultCharset());
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 命令行构造（纯函数，可单测） ====================

    /** 第 1 级：{@code cmd /c start "" "<file>"}（分参数传；空标题是 start 的语法要求） */
    static String[] defaultViewerArgv(Path file) {
        return new String[]{"cmd", "/c", "start", "", absolute(file)};
    }

    /** 第 2 级：{@code cmd /c start ms-photos:viewer?fileName=<urlencoded>} */
    static String[] photosProtocolArgv(Path file) {
        return new String[]{"cmd", "/c", "start", photosViewerUri(file)};
    }

    /** 「照片」查看协议 URI：路径整体 URL 编码（空格用 %20，不用 +），供协议激活使用 */
    static String photosViewerUri(Path file) {
        String encoded = java.net.URLEncoder.encode(absolute(file), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return PHOTOS_VIEWER_URI_PREFIX + encoded;
    }

    private static String absolute(Path file) {
        return file.toAbsolutePath().normalize().toString();
    }

    // ==================== 启动 ====================

    /**
     * 启动并做粗校验：进程须在超时内退出，且<b>退出码为 0、输出为空</b>。
     *
     * <p>输出必须读掉：Java 默认给子进程 PIPE，不读的话错误信息会被静默丢弃——
     * 这正是"日志说已打开、实际什么都没发生"的由来。同时"未退出"（卡在对话框上）也判失败。</p>
     *
     * <p>⚠️ 本校验<b>抓不到</b>"UWP 文件激活被环境阻断"（那种情况 exit=0、无输出），
     * 故还需要 {@link #uwpFileActivationBlocked(Path)} 这样的环境预判。</p>
     */
    private static boolean launch(String[] argv) {
        try {
            Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            Thread drain = new Thread(() -> {
                try {
                    p.getInputStream().transferTo(sink);
                } catch (IOException ignored) {
                    // 进程被杀/管道关闭属预期
                }
            }, "file-opener-drain");
            drain.setDaemon(true);
            drain.start();
            boolean exited = p.waitFor(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0 && sink.toString(StandardCharsets.UTF_8).isBlank();
        } catch (Exception e) {
            return false;
        }
    }
}
