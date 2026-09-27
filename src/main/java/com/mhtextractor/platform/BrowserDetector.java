package com.mhtextractor.platform;

import lombok.extern.slf4j.Slf4j;

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

/**
 * 系统默认浏览器的探测与识别（步骤 5b 从 {@code BrowserLauncher} 拆出的第 2 个职责）。
 *
 * <p><b>探测顺序</b>：注册表 {@code UserChoice}（最准）→ 注册表 {@code http} 协议命令 →
 * 常见安装路径（Edge/Chrome 的 x86 与 x64 位置）→ {@code where.exe} 兜底。</p>
 *
 * <p><b>为什么独立成类</b>：它是**叶子** —— 只读注册表/文件系统/环境，不依赖进程启动或 CDP 协议。
 * 拆出后 {@code BrowserLauncher} 的编排逻辑不再与 Windows 平台细节混在一起。</p>
 *
 * <p>{@code isChromium} 也归此处：它是"这个 exe 是不是 Chromium 系"的识别逻辑，
 * 与探测同属"识别浏览器"这一职责。</p>
 */

@Slf4j
final class BrowserDetector {

    private BrowserDetector() {
    }

    /** 仅 Chromium 系（Edge/Chrome/Chromium）支持 CDP /json/new 内部导航；其余浏览器走源码页回退 */
    static boolean isChromium(String exe) {
        String name = Paths.get(exe).getFileName().toString().toLowerCase(Locale.ROOT);
        return name.contains("msedge") || name.contains("chrome") || name.contains("chromium");
    }

    /** 按序探测系统默认浏览器 exe：注册表 UserChoice → http 协议命令 → 固定路径 → where.exe */
    static Optional<String> detectDefaultBrowserExe() {
        String progId = readRegistryValue(
        );
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
    private static String readRegistryValue() {
        try {
            Process p = new ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\Shell\\Associations\\UrlAssociations\\http\\UserChoice", "/v", "ProgId")
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
            log.debug("读取注册表失败：{}", "HKCU\\Software\\Microsoft\\Windows\\Shell\\Associations\\UrlAssociations\\http\\UserChoice", e);
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
    static String decodeConsole(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }

}
