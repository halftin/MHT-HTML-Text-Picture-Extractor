package com.mhtextractor.gui.util;

import com.mhtextractor.i18n.I18n;
import lombok.extern.slf4j.Slf4j;

import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用系统默认浏览器打开本地 HTML（渲染视图 + 源码视图 + 多级兜底）。
 * 源码视图不使用 view-source: URL（Chromium 系浏览器会拦截命令行传入的 view-source: 导航，
 * 重定向到起始页），改为生成带行号 + 语法高亮 + 链接下划线的源码页后普通打开，
 * 观感尽量贴近 Edge/Chromium 原生源码查看器。
 * 浏览器 exe 探测顺序：注册表 UserChoice → 注册表 http 协议命令 → 常见安装路径 → where.exe。
 * 启动顺序：直接起 exe（可绕过提权进程的 UIPI/DDE 拦截）→ cmd start → Desktop API（native 镜像下不保证可用，只能末位）。
 */
@Slf4j
public final class BrowserLauncher {

    /** 源码视图页文件名（行号 + 高亮 + 链接下划线的源码页，CDP 原生方案的回退） */
    public static final String SOURCE_VIEW_NAME = "source-view.html";

    /** CDP 调试端口（固定，供多次源码查看复用同一调试实例） */
    private static final int CDP_PORT = 29333;
    /** 调试实例独立 profile 目录名（位于 java.io.tmpdir 下；与日常 Edge 隔离——日常实例运行时不接受调试参数） */
    private static final String CDP_PROFILE_NAME = "mht-extractor-edge-profile";

    /** 源码中的 URL（文本段与属性值内加下划线链接样式） */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>]+");

    private BrowserLauncher() {}

    /** 渲染视图：用系统默认浏览器打开本地 HTML，任一途径成功即返回 true */
    public static boolean openHtml(Path htmlFile) {
        return launch(toFileUrl(htmlFile));
    }

    /**
     * 源码视图：优先经 CDP 调试端口的内部导航打开 Edge/Chrome 原生源码查看器
     * （命令行传入的 view-source: URL 会被 Chromium 拦截重定向，内部导航不会——2026-09-24 实测）；
     * 任一环节失败则回退到自产源码页（行号 + 高亮 + 链接下划线）按渲染视图方式打开。
     */
    public static boolean openViewSource(Path htmlFile) {
        if (openViewSourceNative(htmlFile)) {
            return true;
        }
        Path page = writeSourceViewPage(htmlFile);
        if (page == null) {
            return false;
        }
        return launch(toFileUrl(page));
    }

    /**
     * CDP 原生源码查看器：确保带调试端口的独立浏览器实例存在（独立 profile，与日常实例隔离），
     * 再用 PUT /json/new 在浏览器内部导航到 view-source:file URL。
     * 端点状态机（实测）：健康 = 秒回；后台挂起（窗口被全关） = 端口监听但不应答，
     * 须结束该实例干净重启（暖 profile 重启约 0.6 秒）；端口拒绝 = 实例已退出，直接新启动。
     * 源码标签打开后自动关闭渲染标签（精简为单标签；无渲染标签则静默跳过）。
     */
    private static boolean openViewSourceNative(Path htmlFile) {
        Optional<String> exe = detectDefaultBrowserExe();
        if (exe.isEmpty() || !isChromium(exe.get())) {
            return false;
        }
        String profile = Paths.get(System.getProperty("java.io.tmpdir"), CDP_PROFILE_NAME).toString();
        String renderUrl = toFileUrl(htmlFile);
        if (!ensureCdpInstance(exe.get(), CDP_PORT, profile, renderUrl)) {
            return false;
        }
        String viewSourceUrl = "view-source:" + renderUrl;
        if (openTabAndVerify(viewSourceUrl)) {
            closeRenderTab(renderUrl);
            log.info("已通过 CDP 打开原生源码查看器：{}", viewSourceUrl);
            return true;
        }
        log.warn("CDP 打开源码查看器失败，回退源码页");
        return false;
    }

    /**
     * PUT 打开标签 + 列表验证。端点存在"处理请求但不响应"的挂起期（实测 10~20 秒，
     * 常出现在新实例首个 view-source 标签的源码查看器加载期间），以 /json/list 中标签
     * 真实存在为唯一裁决：存在即成功（响应丢失不算失败）；仅列表确认 ABSENT 才重发 PUT
     * （防重复标签）；UNKNOWN 只等待复查、绝不重发。实例已死（DEAD）立即失败回退——
     * 此时 PUT 未落地，回退不会造成双窗口。总预算约 30 秒，最终仍无法确认时按已打开
     * 处理（挂起模式下标签几乎必已创建，避免回退页双窗口），并记 warn 日志。
     */
    private static boolean openTabAndVerify(String viewSourceUrl) {
        String encoded = URLEncoder.encode(viewSourceUrl, StandardCharsets.UTF_8);
        long deadline = System.currentTimeMillis() + 15_000;
        String resp = httpRequest("127.0.0.1", CDP_PORT, "PUT", "/json/new?" + encoded);
        if (resp != null && resp.startsWith("HTTP/1.1 200")) {
            return true;   // PUT 明确成功
        }
        TabState st = checkTab(viewSourceUrl);
        if (st == TabState.EXISTS) {
            return true;   // 响应丢失但标签已打开
        }
        if (st == TabState.DEAD) {
            return false;  // 实例已退出：PUT 未落地，回退安全
        }
        if (st == TabState.ABSENT) {
            resp = httpRequest("127.0.0.1", CDP_PORT, "PUT", "/json/new?" + encoded);
            if (resp != null && resp.startsWith("HTTP/1.1 200")) {
                return true;
            }
        }
        // 慢速阶段：等待挂起期结束，只复查列表
        while (System.currentTimeMillis() < deadline + 15_000) {
            st = checkTab(viewSourceUrl);
            if (st == TabState.EXISTS) {
                return true;
            }
            if (st == TabState.DEAD) {
                return false;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        // 最终确认：列表可用且不存在 → 最后一次安全重发；仍无法确认 → 按已打开处理
        st = checkTabSlow(viewSourceUrl);
        if (st == TabState.EXISTS) {
            return true;
        }
        if (st == TabState.DEAD) {
            return false;
        }
        if (st == TabState.ABSENT) {
            resp = httpRequest("127.0.0.1", CDP_PORT, "PUT", "/json/new?" + encoded);
            if (resp != null && resp.startsWith("HTTP/1.1 200")) {
                return true;
            }
            return checkTabSlow(viewSourceUrl) == TabState.EXISTS;
        }
        log.warn("CDP 端点长时间无响应，标签状态无法确认，按已打开处理（避免回退页双窗口）");
        return true;
    }

    /** 标签查询结果：EXISTS=列表确认存在；ABSENT=列表拿到且不存在；UNKNOWN=列表不可得（端点挂起）；DEAD=实例已退出 */
    private enum TabState { EXISTS, ABSENT, UNKNOWN, DEAD }

    /** 列表查询指定 URL 的标签页状态；列表拿不到时区分挂起与实例已退出 */
    private static TabState checkTab(String url) {
        String resp = httpRequest("127.0.0.1", CDP_PORT, "GET", "/json/list");
        if (resp == null) {
            return probeVersion(CDP_PORT) == Probe.REFUSED ? TabState.DEAD : TabState.UNKNOWN;
        }
        return resp.contains("\"url\": \"" + url + "\"") ? TabState.EXISTS : TabState.ABSENT;
    }

    /** 长超时（3 秒）的列表查询，放弃前最后一搏 */
    private static TabState checkTabSlow(String url) {
        String resp = httpRequest("127.0.0.1", CDP_PORT, "GET", "/json/list", 3000);
        if (resp == null) {
            return probeVersion(CDP_PORT) == Probe.REFUSED ? TabState.DEAD : TabState.UNKNOWN;
        }
        return resp.contains("\"url\": \"" + url + "\"") ? TabState.EXISTS : TabState.ABSENT;
    }

    /** 按 URL 在列表中找到标签 target id 并关闭（找不到/失败只记 debug，不影响主流程） */
    private static void closeRenderTab(String renderUrl) {
        try {
            String list = httpRequest("127.0.0.1", CDP_PORT, "GET", "/json/list");
            if (list == null) {
                return;
            }
            int urlIdx = list.indexOf("\"url\": \"" + renderUrl + "\"");
            if (urlIdx < 0) {
                return;
            }
            int idIdx = list.lastIndexOf("\"id\": \"", urlIdx);
            if (idIdx < 0) {
                return;
            }
            int idStart = idIdx + "\"id\": \"".length();
            int idEnd = list.indexOf('"', idStart);
            if (idEnd < 0) {
                return;
            }
            httpRequest("127.0.0.1", CDP_PORT, "GET", "/json/close/" + list.substring(idStart, idEnd));
            log.info("已关闭渲染标签页（保留源码标签）");
        } catch (Exception e) {
            log.debug("关闭渲染标签页失败（忽略）：{}", e.getMessage());
        }
    }

    /** 端点探测结果 */
    private enum Probe { OK, REFUSED, HANG }

    /** 确保 CDP 调试实例可用：健康即复用；挂起则结束自己的调试实例后重启；拒绝则直接新启动 */
    private static boolean ensureCdpInstance(String exe, int port, String profile, String initialUrl) {
        Probe p = probeVersion(port);
        if (p == Probe.OK) {
            return true;
        }
        if (p == Probe.HANG) {
            // 端口被监听但不应答：占用者若为本应用的调试实例（后台挂起），结束它以便干净重启
            killListenerOnPort(port, profile);
        }
        launchCdpInstance(exe, port, profile, initialUrl);
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (probeVersion(port) == Probe.OK) {
                return true;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("CDP 调试端点 {} 未在 15 秒内就绪，回退源码页", port);
        return false;
    }

    /** 探测 /json/version：OK=200；REFUSED=端口无人监听；HANG=端口已监听但不应答 */
    private static Probe probeVersion(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 700);
            socket.setSoTimeout(1200);
            String req = "GET /json/version HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String resp = readResponse(socket.getInputStream());
            return resp != null && resp.startsWith("HTTP/1.1 200") ? Probe.OK : Probe.HANG;
        } catch (java.net.ConnectException e) {
            return Probe.REFUSED;
        } catch (IOException e) {
            return Probe.HANG;
        }
    }

    /** 启动调试实例：初始标签页直接打开渲染页（首启窗口出现时即有内容），随后 PUT 再开源码标签 */
    private static void launchCdpInstance(String exe, int port, String profile, String initialUrl) {
        try {
            new ProcessBuilder(exe,
                    "--remote-debugging-port=" + port,
                    "--user-data-dir=" + profile,
                    "--no-first-run",
                    initialUrl).start();
        } catch (IOException e) {
            log.warn("启动 CDP 调试实例失败：{}", e.getMessage());
        }
    }

    /** 结束占用端口的本应用调试实例（netstat 找 PID → wmic 校验命令行含本 profile → taskkill 进程树） */
    private static void killListenerOnPort(int port, String profile) {
        try {
            Process np = new ProcessBuilder("netstat", "-ano", "-p", "tcp")
                    .redirectErrorStream(true).start();
            if (!np.waitFor(5, TimeUnit.SECONDS)) {
                np.destroyForcibly();
                return;
            }
            String out = decodeConsole(np.getInputStream().readAllBytes());
            String pid = null;
            for (String line : out.lines().toList()) {
                if (line.contains("127.0.0.1:" + port) && line.toUpperCase(Locale.ROOT).contains("LISTENING")) {
                    String[] parts = line.trim().split("\\s+");
                    pid = parts[parts.length - 1];
                    break;
                }
            }
            if (pid == null || !pid.matches("\\d+")) {
                return;
            }
            Process wp = new ProcessBuilder("wmic", "process", "where", "ProcessId=" + pid, "get", "CommandLine")
                    .redirectErrorStream(true).start();
            if (!wp.waitFor(5, TimeUnit.SECONDS)) {
                wp.destroyForcibly();
                return;
            }
            String cmd = decodeConsole(wp.getInputStream().readAllBytes());
            if (!cmd.contains(profile)) {
                log.debug("端口 {} 的占用进程非本应用调试实例，不结束", port);
                return;
            }
            log.info("结束挂起的 CDP 调试实例（PID {}）以便重启", pid);
            new ProcessBuilder("taskkill", "/PID", pid, "/T", "/F").start();
        } catch (Exception e) {
            log.debug("结束挂起调试实例失败（忽略）：{}", e.getMessage());
        }
    }

    /** 仅 Chromium 系（Edge/Chrome/Chromium）支持 CDP /json/new 内部导航；其余浏览器走源码页回退 */
    private static boolean isChromium(String exe) {
        String name = Paths.get(exe).getFileName().toString().toLowerCase(Locale.ROOT);
        return name.contains("msedge") || name.contains("chrome") || name.contains("chromium");
    }

    /** 极简 HTTP 客户端（原生 Socket，native-image 安全）：返回"状态行\n响应体"；连接失败/超时返回 null */
    private static String httpRequest(String host, int port, String method, String path) {
        return httpRequest(host, port, method, path, 1500);
    }

    /** 同上，可指定读取超时 */
    private static String httpRequest(String host, int port, String method, String path, int readTimeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 800);
            socket.setSoTimeout(readTimeoutMs);
            String req = method + " " + path + " HTTP/1.1\r\nHost: " + host + ":" + port
                    + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return readResponse(socket.getInputStream());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 按 HTTP 语义读响应：状态行 + 头 + 按 Content-Length 读定长响应体。
     * 不能靠连接关闭判定结束——Edge DevTools 服务端响应后常保持 keep-alive，
     * 用 readAllBytes 等 EOF 会把已到达的响应拖到超时丢弃（此前大量"挂起"假象的根因）。
     */
    private static String readResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null || statusLine.isEmpty()) {
            return null;
        }
        int contentLength = -1;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                try {
                    contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
                } catch (NumberFormatException ignore) {
                    // 保留 -1，走兜底
                }
            }
        }
        StringBuilder sb = new StringBuilder(statusLine);
        if (contentLength > 0) {
            sb.append('\n').append(new String(in.readNBytes(contentLength), StandardCharsets.UTF_8));
        } else if (contentLength < 0) {
            // 无 Content-Length：读到连接关闭为止（兜底）
            sb.append('\n').append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** 读一行（\n 结尾，剥离 \r），最多 8192 字节 */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        while (bos.size() < 8192) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            if (b == '\n') {
                break;
            }
            bos.write(b);
        }
        String s = bos.toString(StandardCharsets.ISO_8859_1);
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    /** 三级启动兜底：直接起浏览器 exe → cmd start → Desktop API，任一成功即返回 true */
    private static boolean launch(String url) {
        Optional<String> exe = detectDefaultBrowserExe();
        if (exe.isPresent() && runExe(exe.get(), url)) {
            return true;
        }
        if (cmdStart(url)) {
            return true;
        }
        return desktopBrowse(url);
    }

    /** 文件路径 → file URL（toASCIIString 自动百分号编码中文路径，不手工拼 file:///） */
    private static String toFileUrl(Path file) {
        return file.toUri().toASCIIString();
    }

    /** 按序探测系统默认浏览器 exe：注册表 UserChoice → http 协议命令 → 固定路径 → where.exe */
    private static Optional<String> detectDefaultBrowserExe() {
        String progId = readRegistryValue(
                "HKCU\\Software\\Microsoft\\Windows\\Shell\\Associations\\UrlAssociations\\http\\UserChoice",
                "ProgId");
        if (progId != null && !progId.isEmpty()) {
            String exe = exeFromRegistryCommand("HKCR\\" + progId + "\\shell\\open\\command");
            if (exe != null) {
                return Optional.of(exe);
            }
        }
        String exe = exeFromRegistryCommand("HKCR\\http\\shell\\open\\command");
        if (exe != null) {
            return Optional.of(exe);
        }
        for (String candidate : candidatePaths()) {
            if (Files.isRegularFile(Path.of(candidate))) {
                return Optional.of(candidate);
            }
        }
        for (String name : List.of("msedge", "chrome", "firefox")) {
            String found = whereExe(name);
            if (found != null) {
                return Optional.of(found);
            }
        }
        log.warn("未探测到系统默认浏览器 exe");
        return Optional.empty();
    }

    /** 读取注册表命名值（REG_SZ），失败返回 null */
    private static String readRegistryValue(String key, String valueName) {
        try {
            Process p = new ProcessBuilder("reg", "query", key, "/v", valueName)
                    .redirectErrorStream(true).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) {
                return null;
            }
            byte[] bytes = p.getInputStream().readAllBytes();
            String out = decodeConsole(bytes);
            for (String line : out.lines().toList()) {
                int idx = line.indexOf("REG_SZ");
                if (idx >= 0) {
                    String value = line.substring(idx + "REG_SZ".length()).trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("读取注册表失败：{}", key, e);
        }
        return null;
    }

    /** 读取注册表默认值命令（/ve），解析出浏览器 exe 路径并校验文件存在 */
    private static String exeFromRegistryCommand(String key) {
        try {
            Process p = new ProcessBuilder("reg", "query", key, "/ve")
                    .redirectErrorStream(true).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) {
                return null;
            }
            byte[] bytes = p.getInputStream().readAllBytes();
            String out = decodeConsole(bytes);
            for (String line : out.lines().toList()) {
                int idx = line.indexOf("REG_SZ");
                if (idx < 0) {
                    continue;
                }
                String cmd = line.substring(idx + "REG_SZ".length()).trim();
                String exe = parseCommandExe(cmd);
                if (exe != null && Files.isRegularFile(Path.of(exe))) {
                    return exe;
                }
            }
        } catch (Exception e) {
            log.debug("读取注册表默认命令失败：{}", key, e);
        }
        return null;
    }

    /** 解析注册表命令行值：以引号开头取到下一个引号，否则取第一个空格前的内容 */
    private static String parseCommandExe(String cmd) {
        if (cmd == null || cmd.isEmpty()) {
            return null;
        }
        if (cmd.startsWith("\"")) {
            int end = cmd.indexOf('"', 1);
            return end > 1 ? cmd.substring(1, end) : null;
        }
        int sp = cmd.indexOf(' ');
        return sp > 0 ? cmd.substring(0, sp) : cmd;
    }

    /** 常见浏览器安装路径（存在即用） */
    private static List<String> candidatePaths() {
        List<String> paths = new ArrayList<>();
        paths.add("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe");
        paths.add("C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe");
        paths.add("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe");
        paths.add("C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe");
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isEmpty()) {
            paths.add(localAppData + "\\Google\\Chrome\\Application\\chrome.exe");
        }
        paths.add("C:\\Program Files\\Mozilla Firefox\\firefox.exe");
        return paths;
    }

    /** where.exe 查询浏览器可执行文件（取第一行并校验存在） */
    private static String whereExe(String name) {
        try {
            Process p = new ProcessBuilder("where.exe", name).redirectErrorStream(true).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) {
                return null;
            }
            byte[] bytes = p.getInputStream().readAllBytes();
            String out = decodeConsole(bytes);
            String first = out.lines().findFirst().orElse("").trim();
            return (!first.isEmpty() && Files.isRegularFile(Path.of(first))) ? first : null;
        } catch (Exception e) {
            log.debug("where.exe 查询失败：{}", name, e);
            return null;
        }
    }

    /** 控制台进程输出解码：先试 UTF-8 严格解码，失败再用 GBK（中文系统 reg.exe 输出为代码页 GBK） */
    private static String decodeConsole(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    /** 直接启动浏览器 exe（不经过 shell：可绕过提权进程下 ShellExecute/DDE 被 UIPI 拦截的问题） */
    private static boolean runExe(String exe, String url) {
        try {
            new ProcessBuilder(exe, url).start();
            log.info("已启动浏览器：{} {}", exe, url);
            return true;
        } catch (IOException e) {
            log.warn("直接启动浏览器失败：{}", exe, e);
            return false;
        }
    }

    /** cmd /c start 兜底：整条命令作为单个参数传入，URL 整体引号包裹防空格截断 */
    private static boolean cmdStart(String url) {
        try {
            new ProcessBuilder("cmd", "/c", "start \"\" \"" + url + "\"").start();
            log.info("已通过 cmd start 打开：{}", url);
            return true;
        } catch (IOException e) {
            log.warn("cmd start 打开失败：{}", url, e);
            return false;
        }
    }

    /** Desktop API 末位兜底（GraalVM native-image 下 AWT 支持有限，不保证可用） */
    private static boolean desktopBrowse(String url) {
        try {
            if (!Desktop.isDesktopSupported()) {
                log.warn("Desktop API 不可用");
                return false;
            }
            Desktop.getDesktop().browse(URI.create(url));
            log.info("已通过 Desktop 打开：{}", url);
            return true;
        } catch (Exception e) {
            log.warn("Desktop.browse 打开失败：{}", url, e);
            return false;
        }
    }

    /**
     * 源码视图页：逐行渲染（行号栏 + 语法高亮 + 链接下划线），观感贴近 Edge/Chromium
     * 原生源码查看器。换行先归一化（\r\n / \r → \n），每行一条 .row，行号右对齐灰色栏。
     */
    private static Path writeSourceViewPage(Path indexHtml) {
        try {
            String html = Files.readString(indexHtml, StandardCharsets.UTF_8);
            String pageHtml = I18n.get("browser.sourceHtmlHead")
                    + "<style>"
                    + "body{margin:0;background:#fff;}"
                    + ".row{clear:both;font-family:Consolas,\"Courier New\",monospace;font-size:13px;line-height:1.5;}"
                    + ".ln{float:left;text-align:right;color:#9e9e9e;background:#f7f7f7;"
                    + "border-right:1px solid #e5e5e5;padding-right:8px;margin-right:10px;user-select:none;}"
                    + ".lc{display:block;white-space:pre-wrap;min-height:1.5em;color:#000;}"
                    + ".hl-tag{color:#0000ff;}.hl-attr{color:#a31515;}.hl-val{color:#008000;}"
                    + ".hl-cm{color:#808080;font-style:italic;}.hl-dt{color:#808080;}"
                    + ".hl-link{color:#0000ee;text-decoration:underline;}"
                    + "</style></head><body>"
                    + buildSourceViewBody(html)
                    + "</body></html>";
            Path page = indexHtml.resolveSibling(SOURCE_VIEW_NAME);
            Files.writeString(page, pageHtml, StandardCharsets.UTF_8);
            log.info("已生成源码视图页：{}", page);
            return page;
        } catch (IOException e) {
            log.warn("生成源码视图页失败：{}", indexHtml, e);
            return null;
        }
    }

    /**
     * 生成源码视图主体：整篇一次扫描（跨行标签/注释着色不断），遇 \n 提交一行；
     * 行边界处闭合未完成的着色 span，下一行重开，保证行结构完好且换行信息零丢失。
     */
    private static String buildSourceViewBody(String html) {
        String normalized = html.replace("\r\n", "\n").replace('\r', '\n');
        int totalLines = 1 + (int) normalized.chars().filter(ch -> ch == '\n').count();
        LineWriter w = new LineWriter(totalLines);
        int i = 0;
        int n = normalized.length();
        while (i < n) {
            char c = normalized.charAt(i);
            if (c == '<') {
                if (normalized.startsWith("<!--", i)) {
                    int end = normalized.indexOf("-->", i + 4);
                    if (end < 0) {
                        w.write(normalized.substring(i), "hl-cm");
                        break;
                    }
                    w.write(normalized.substring(i, end + 3), "hl-cm");
                    i = end + 3;
                    continue;
                }
                if (normalized.regionMatches(true, i, "<!doctype", 0, 9)) {
                    int end = normalized.indexOf('>', i);
                    if (end < 0) {
                        w.write(normalized.substring(i), "hl-dt");
                        break;
                    }
                    w.write(normalized.substring(i, end + 1), "hl-dt");
                    i = end + 1;
                    continue;
                }
                // 找标签结束 >（属性值引号内的 > 不视为结束）
                int j = i + 1;
                char quote = 0;
                while (j < n) {
                    char d = normalized.charAt(j);
                    if (quote != 0) {
                        if (d == quote) {
                            quote = 0;
                        }
                    } else if (d == '"' || d == '\'') {
                        quote = d;
                    } else if (d == '>') {
                        break;
                    }
                    j++;
                }
                if (j >= n) {
                    w.write(normalized.substring(i), "hl-tag");
                    break;
                }
                writeTag(w, normalized.substring(i, j + 1));
                i = j + 1;
            } else {
                int next = normalized.indexOf('<', i);
                if (next < 0) {
                    w.writeText(normalized.substring(i));
                    break;
                }
                w.writeText(normalized.substring(i, next));
                i = next;
            }
        }
        return w.finish();
    }

    /** 高亮单个标签：< / 标签名与间隙用 tag 色；属性名与 = 用 attr 色；属性值用 val 色（URL 值叠加下划线） */
    private static void writeTag(LineWriter w, String tag) {
        int i = 1;
        if (i < tag.length() && tag.charAt(i) == '/') {
            i++;
        }
        int nameEnd = i;
        while (nameEnd < tag.length() && isNameChar(tag.charAt(nameEnd))) {
            nameEnd++;
        }
        w.write(tag.substring(0, nameEnd), "hl-tag");
        while (nameEnd < tag.length()) {
            char c = tag.charAt(nameEnd);
            if (Character.isWhitespace(c) || c == '>' || c == '/') {
                int runStart = nameEnd;
                while (nameEnd < tag.length()) {
                    char d = tag.charAt(nameEnd);
                    if (Character.isWhitespace(d) || d == '>' || d == '/') {
                        nameEnd++;
                    } else {
                        break;
                    }
                }
                w.write(tag.substring(runStart, nameEnd), "hl-tag");
                continue;
            }
            int attrStart = nameEnd;
            while (nameEnd < tag.length() && isNameChar(tag.charAt(nameEnd))) {
                nameEnd++;
            }
            if (nameEnd == attrStart) {
                w.write(String.valueOf(c), "hl-tag");
                nameEnd++;
                continue;
            }
            w.write(tag.substring(attrStart, nameEnd), "hl-attr");
            int wsStart = nameEnd;
            while (nameEnd < tag.length() && Character.isWhitespace(tag.charAt(nameEnd))) {
                nameEnd++;
            }
            w.write(tag.substring(wsStart, nameEnd), "hl-attr");
            if (nameEnd < tag.length() && tag.charAt(nameEnd) == '=') {
                w.write("=", "hl-attr");
                nameEnd++;
                int ws2 = nameEnd;
                while (nameEnd < tag.length() && Character.isWhitespace(tag.charAt(nameEnd))) {
                    nameEnd++;
                }
                w.write(tag.substring(ws2, nameEnd), "hl-attr");
                // 属性值（带引号或不带引号）
                if (nameEnd < tag.length() && (tag.charAt(nameEnd) == '"' || tag.charAt(nameEnd) == '\'')) {
                    char q = tag.charAt(nameEnd);
                    int valStart = nameEnd;
                    nameEnd++;
                    while (nameEnd < tag.length() && tag.charAt(nameEnd) != q) {
                        nameEnd++;
                    }
                    if (nameEnd < tag.length()) {
                        nameEnd++;
                    }
                    w.write(tag.substring(valStart, nameEnd), valueClass(tag.substring(valStart, nameEnd)));
                } else {
                    int valStart = nameEnd;
                    while (nameEnd < tag.length() && !Character.isWhitespace(tag.charAt(nameEnd))
                            && tag.charAt(nameEnd) != '>') {
                        nameEnd++;
                    }
                    w.write(tag.substring(valStart, nameEnd), valueClass(tag.substring(valStart, nameEnd)));
                }
            }
        }
    }

    /** 属性值是 URL 时叠加下划线链接样式 */
    private static String valueClass(String rawValue) {
        String inner = rawValue;
        if (inner.length() >= 2 && ((inner.startsWith("\"") && inner.endsWith("\""))
                || (inner.startsWith("'") && inner.endsWith("'")))) {
            inner = inner.substring(1, inner.length() - 1);
        }
        return URL_PATTERN.matcher(trimUrl(inner)).matches() ? "hl-val hl-link" : "hl-val";
    }

    /** 标签名/属性名字符：字母数字与 - : _ */
    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '_';
    }

    /** 去掉 URL 尾部标点（句读号在正文里不属于链接） */
    private static String trimUrl(String url) {
        String t = url;
        while (!t.isEmpty() && ".,;:!?)]}>".indexOf(t.charAt(t.length() - 1)) >= 0) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /**
     * 行输出器：高亮段不嵌套（单层 span 跟踪即可），遇 \n 提交当前行；
     * 行提交时若有未闭合 span（跨行标签/注释），先闭合、下一行内容起始处重开。
     */
    private static final class LineWriter {
        private final StringBuilder out = new StringBuilder();
        private final StringBuilder line = new StringBuilder();
        private final int gutterDigits;
        private int lineNo = 1;
        /** 当前行内未闭合的着色 span 类；null = 无 */
        private String openSpan = null;

        LineWriter(int totalLines) {
            this.gutterDigits = String.valueOf(totalLines).length();
        }

        /** 写入一段（转义后按 \n 切行提交）；cls 非 null 时包着色 span */
        void write(String s, String cls) {
            String esc = escape(s);
            if (cls != null) {
                line.append("<span class=\"").append(cls).append("\">");
                openSpan = cls;
            }
            int start = 0;
            for (int k = 0; k < esc.length(); k++) {
                if (esc.charAt(k) == '\n') {
                    line.append(esc, start, k);
                    flush();
                    start = k + 1;
                }
            }
            line.append(esc, start, esc.length());
            if (cls != null) {
                line.append("</span>");
                openSpan = null;
            }
        }

        /** 纯文本段：内嵌 http(s) URL 加下划线链接样式（尾随标点不入链接） */
        void writeText(String s) {
            Matcher m = URL_PATTERN.matcher(s);
            int last = 0;
            while (m.find()) {
                String url = trimUrl(m.group());
                write(s.substring(last, m.start()), null);
                write(url, "hl-link");
                last = m.start() + url.length();
            }
            write(s.substring(last), null);
        }

        String finish() {
            flush();
            return out.toString();
        }

        private void flush() {
            if (openSpan != null) {
                line.append("</span>");
            }
            out.append("<div class=\"row\"><span class=\"ln\" style=\"width:")
                    .append(gutterDigits + 1).append("em\">").append(lineNo)
                    .append("</span><span class=\"lc\">").append(line).append("</span></div>");
            line.setLength(0);
            lineNo++;
            if (openSpan != null) {
                line.append("<span class=\"").append(openSpan).append("\">");
            }
        }

        /** 转义（& < > "） */
        private static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;")
                    .replace(">", "&gt;").replace("\"", "&quot;");
        }
    }
}
