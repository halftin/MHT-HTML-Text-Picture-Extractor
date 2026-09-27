package com.mhtextractor.platform;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * CDP（Chrome DevTools Protocol）客户端 + 调试实例的进程/端口管理
 * （步骤 5b 从 {@code BrowserLauncher} 拆出的第 3 个职责）。
 *
 * <p><b>为什么需要 CDP</b>：Chromium 系浏览器会拦截命令行传入的 {@code view-source:} 导航
 * （重定向到起始页），但在浏览器<b>内部</b>导航不会 —— 于是本类维护一个带固定调试端口、
 * 独立 profile 的浏览器实例，用 HTTP 端点在该实例内打开 {@code view-source:} 标签。</p>
 *
 * <p><b>端点状态机（实测）</b>：健康 = 秒回；后台挂起（窗口被关） = 端口监听但不应答，
 * 须结束该实例干净重启（暖 profile 重启约 0.6 秒）；端口拒绝 = 实例已退出，直接新启动。</p>
 *
 * <p><b>为什么独立成类</b>：它是"与浏览器调试协议/进程打交道"的一整块，
 * 与"用什么 exe 打开"（{@link BrowserDetector}）、"源码页长什么样"（{@link SourceViewPage}）
 * 无关。拆出后 {@link BrowserLauncher} 只剩编排。</p>
 */

@Slf4j
final class CdpClient {

    private CdpClient() {
    }

    /** CDP 调试端口（固定，供多次源码查看复用同一调试实例） */
    static final int CDP_PORT = 29333;
    /** 调试实例独立 profile 目录名（位于 java.io.tmpdir 下；与日常 Edge 隔离——日常实例运行时不接受调试参数） */
    static final String CDP_PROFILE_NAME = "mht-extractor-edge-profile";

    /**
     * PUT 打开标签 + 列表验证。端点存在"处理请求但不响应"的挂起期（实测 10~20 秒，
     * 常出现在新实例首个 view-source 标签的源码查看器加载期间），以 /json/list 中标签
     * 真实存在为唯一裁决：存在即成功（响应丢失不算失败）；仅列表确认 ABSENT 才重发 PUT
     * （防重复标签）；UNKNOWN 只等待复查、绝不重发。实例已死（DEAD）立即失败回退——
     * 此时 PUT 未落地，回退不会造成双窗口。总预算约 30 秒，最终仍无法确认时按已打开
     * 处理（挂起模式下标签几乎必已创建，避免回退页双窗口），并记 warn 日志。
     */
    static boolean openTabAndVerify(String viewSourceUrl) {
        String encoded = URLEncoder.encode(viewSourceUrl, StandardCharsets.UTF_8);
        long deadline = System.currentTimeMillis() + 15_000;
        String resp = httpRequest("PUT", "/json/new?" + encoded);
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
            resp = httpRequest("PUT", "/json/new?" + encoded);
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
            resp = httpRequest("PUT", "/json/new?" + encoded);
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
        String resp = httpRequest("GET", "/json/list");
        if (resp == null) {
            return probeVersion() == Probe.REFUSED ? TabState.DEAD : TabState.UNKNOWN;
        }
        return resp.contains("\"url\": \"" + url + "\"") ? TabState.EXISTS : TabState.ABSENT;
    }

    /** 长超时（3 秒）的列表查询，放弃前最后一搏 */
    private static TabState checkTabSlow(String url) {
        String resp = httpRequest("GET", "/json/list", 3000);
        if (resp == null) {
            return probeVersion() == Probe.REFUSED ? TabState.DEAD : TabState.UNKNOWN;
        }
        return resp.contains("\"url\": \"" + url + "\"") ? TabState.EXISTS : TabState.ABSENT;
    }

    /** 按 URL 在列表中找到标签 target id 并关闭（找不到/失败只记 debug，不影响主流程） */
    static void closeRenderTab(String renderUrl) {
        try {
            String list = httpRequest("GET", "/json/list");
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
            httpRequest("GET", "/json/close/" + list.substring(idStart, idEnd));
            log.info("已关闭渲染标签页（保留源码标签）");
        } catch (Exception e) {
            log.debug("关闭渲染标签页失败（忽略）：{}", e.getMessage());
        }
    }

    /** 端点探测结果 */
    private enum Probe { OK, REFUSED, HANG }

    /** 确保 CDP 调试实例可用：健康即复用；挂起则结束自己的调试实例后重启；拒绝则直接新启动 */
    static boolean ensureCdpInstance(String exe, String profile, String initialUrl) {
        Probe p = probeVersion();
        if (p == Probe.OK) {
            return true;
        }
        if (p == Probe.HANG) {
            // 端口被监听但不应答：占用者若为本应用的调试实例（后台挂起），结束它以便干净重启
            killListenerOnPort(profile);
        }
        launchCdpInstance(exe, profile, initialUrl);
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (probeVersion() == Probe.OK) {
                return true;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("CDP 调试端点 {} 未在 15 秒内就绪，回退源码页", CdpClient.CDP_PORT);
        return false;
    }

    /** 探测 /json/version：OK=200；REFUSED=端口无人监听；HANG=端口已监听但不应答 */
    private static Probe probeVersion() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", CdpClient.CDP_PORT), 700);
            socket.setSoTimeout(1200);
            String req = "GET /json/version HTTP/1.1\r\nHost: 127.0.0.1:" + CdpClient.CDP_PORT
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
    private static void launchCdpInstance(String exe, String profile, String initialUrl) {
        try {
            new ProcessBuilder(exe,
                    "--remote-debugging-port=" + CdpClient.CDP_PORT,
                    "--user-data-dir=" + profile,
                    "--no-first-run",
                    initialUrl).start();
        } catch (IOException e) {
            log.warn("启动 CDP 调试实例失败：{}", e.getMessage());
        }
    }

    /** 结束占用端口的本应用调试实例（netstat 找 PID → wmic 校验命令行含本 profile → taskkill 进程树） */
    private static void killListenerOnPort(String profile) {
        try {
            Process np = new ProcessBuilder("netstat", "-ano", "-p", "tcp")
                    .redirectErrorStream(true).start();
            if (!np.waitFor(5, TimeUnit.SECONDS)) {
                np.destroyForcibly();
                return;
            }
            String out = BrowserDetector.decodeConsole(np.getInputStream().readAllBytes());
            String pid = null;
            for (String line : out.lines().toList()) {
                if (line.contains("127.0.0.1:" + CdpClient.CDP_PORT) && line.toUpperCase(Locale.ROOT).contains("LISTENING")) {
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
            String cmd = BrowserDetector.decodeConsole(wp.getInputStream().readAllBytes());
            if (!cmd.contains(profile)) {
                log.debug("端口 {} 的占用进程非本应用调试实例，不结束", CdpClient.CDP_PORT);
                return;
            }
            log.info("结束挂起的 CDP 调试实例（PID {}）以便重启", pid);
            new ProcessBuilder("taskkill", "/PID", pid, "/T", "/F").start();
        } catch (Exception e) {
            log.debug("结束挂起调试实例失败（忽略）：{}", e.getMessage());
        }
    }

    /** 极简 HTTP 客户端（原生 Socket，native-image 安全）：返回"状态行\n响应体"；连接失败/超时返回 null */
    private static String httpRequest(String method, String path) {
        return httpRequest(method, path, 1500);
    }

    /** 同上，可指定读取超时 */
    private static String httpRequest(String method, String path, int readTimeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", CdpClient.CDP_PORT), 800);
            socket.setSoTimeout(readTimeoutMs);
            String req = method + " " + path + " HTTP/1.1\r\nHost: " + "127.0.0.1" + ":" + CdpClient.CDP_PORT
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
}
