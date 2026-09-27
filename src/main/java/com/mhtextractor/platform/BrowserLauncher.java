package com.mhtextractor.platform;

import com.mhtextractor.i18n.I18n;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

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
        Path page = SourceViewPage.writeSourceViewPage(htmlFile, I18n.get("browser.sourceHtmlHead"));
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
        Optional<String> exe = BrowserDetector.detectDefaultBrowserExe();
        if (exe.isEmpty() || !BrowserDetector.isChromium(exe.get())) {
            return false;
        }
        String profile = Paths.get(System.getProperty("java.io.tmpdir"), CdpClient.CDP_PROFILE_NAME).toString();
        String renderUrl = toFileUrl(htmlFile);
        if (!CdpClient.ensureCdpInstance(exe.get(), profile, renderUrl)) {
            return false;
        }
        String viewSourceUrl = "view-source:" + renderUrl;
        if (CdpClient.openTabAndVerify(viewSourceUrl)) {
            CdpClient.closeRenderTab(renderUrl);
            log.info("已通过 CDP 打开原生源码查看器：{}", viewSourceUrl);
            return true;
        }
        log.warn("CDP 打开源码查看器失败，回退源码页");
        return false;
    }




    /** 三级启动兜底：直接起浏览器 exe → cmd start → ShellExecute（rundll32），任一成功即返回 true */
    private static boolean launch(String url) {
        Optional<String> exe = BrowserDetector.detectDefaultBrowserExe();
        if (exe.isPresent() && runExe(exe.get(), url)) {
            return true;
        }
        if (cmdStart(url)) {
            return true;
        }
        return shellExecute(url);
    }

    /** 文件路径 → file URL（toASCIIString 自动百分号编码中文路径，不手工拼 file:///） */
    private static String toFileUrl(Path file) {
        return file.toUri().toASCIIString();
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

    /**
     * ShellExecute 末位兜底：经 {@code rundll32 url.dll,FileProtocolHandler} 交给 Windows 外壳打开。
     *
     * <p><b>步骤 7 行为变更（有意）</b>：原实现用 AWT {@code Desktop.getDesktop().browse(url)}。
     * 但本项目目标是 GraalVM native EXE，而 native 对 AWT 支持有限（`CLAUDE.md` 记录过"把
     * {@code Desktop.open} 换成 {@code Runtime.exec("explorer.exe")}"的同一处决策），
     * 且 native 元数据里**没有注册任何 awt 条目** —— 保留 AWT 会让这一层在 native 下成为隐患。</p>
     *
     * <p>{@code rundll32 url.dll,FileProtocolHandler} 与 {@code Desktop.browse} 走的是
     * <b>同一个 ShellExecute 机制</b>（都是把 URL 交给系统默认协议处理器），因此语义等价，
     * 但完全不依赖 AWT。调用点、返回语义（成功 true / 失败 false 并记日志）均不变。</p>
     */
    private static boolean shellExecute(String url) {
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            log.info("已通过 ShellExecute 打开：{}", url);
            return true;
        } catch (Exception e) {
            log.warn("ShellExecute 打开失败：{}", url, e);
            return false;
        }
    }
}
