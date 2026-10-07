package com.mhtextractor.backend;

import com.mhtextractor.baseline.BaselineCases;
import com.mhtextractor.baseline.BaselineHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4-1 无头启动冒烟：在<b>物理剔除 UI 框架</b>的子进程里跑生产无头入口（{@code backend.BackendCli}），
 * 断言 {@code --once} 全链路跑通、产物与 GUI 侧黄金基线<b>逐字节一致</b>、退出码语义明确，
 * 以及 {@code --serve} 常驻语义与无头 logback（冲突 C8）闸门。
 *
 * <p><b>为什么是子进程</b>：本测试自己跑在 surefire 里（UI 框架在 classpath 上、logback 上下文可能已被
 * 其它测试初始化），进程内调用证明不了"无头启动"。子进程沿用 {@code headless-layer-b} 的同一配方
 * （{@code --limit-modules java.se} + 剔除 javafx jar），使"闭包零 UI 框架"成为运行时事实 ——
 * 任何一处触碰 UI 框架都会立刻 {@code NoClassDefFoundError}（运行时失败 ≠ 0 即红）。</p>
 *
 * <p><b>产物判定口径</b>：直接复用 GUI 侧黄金基线的同一套夹具与摘要口径
 * （{@link BaselineCases#FIXED_MTIME_EPOCH_MILLI}、{@link BaselineHasher#hashTree}），
 * 与 {@code LayerBTest} / {@code StubUiAcceptanceTest} 的比对方式逐行一致。</p>
 *
 * <p><b>测试访问面纪律</b>：本测试<b>不得</b>在字节码里引用任何生产成员（{@code backend} 主源集类一律以
 * 字符串寻址），否则会被 {@code testAccessSurfaceIsFrozen} 判为新增访问点而失败。</p>
 */
class BackendOnceSmokeTest {

    /** 生产无头入口（字符串引用；见类 javadoc 的测试访问面纪律） */
    private static final String MAIN_CLASS = "com.mhtextractor.backend.BackendCli";

    @TempDir
    Path tmp;

    private int runCounter;

    /** 子进程运行结果：退出码 + 合并后的 stdout/stderr 原文 */
    private record RunResult(int code, String output) {
    }

    // ==================== 无头子进程 ====================

    /**
     * 在无 UI 框架的子进程里运行生产入口。
     * 工作目录 = 每次运行独立的临时目录（无头 logback 的 {@code logs/backend.log} 落在这里，不污染仓库/C 盘）。
     */
    private RunResult runBackend(String... args) throws Exception {
        Path work = Files.createDirectories(tmp.resolve("run-" + (++runCounter)));
        Files.createDirectories(work.resolve("logs"));
        Path capture = work.resolve("capture.out");

        List<String> cmd = baseCommand();
        cmd.addAll(Arrays.asList(args));

        Process p = new ProcessBuilder(cmd)
                .directory(work.toFile())
                .redirectErrorStream(true)
                .redirectOutput(capture.toFile())
                .start();
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("无头子进程超时未退出");
        }
        return new RunResult(p.exitValue(), Files.readString(capture));
    }

    /**
     * 无头子进程的基础命令：显式 UTF-8 输出（本机非控制台重定向的默认编码是 GBK，会把中文组名写坏）、
     * 模块根限定 java.se（剔除 JDK 内置的 UI 框架模块） + 剔除 UI 框架 jar（同 headless-layer-b 两条件）。
     */
    private static List<String> baseCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-Dstderr.encoding=UTF-8");
        cmd.add("--limit-modules");
        cmd.add("java.se");
        cmd.add("-cp");
        cmd.add(headlessClasspath());
        cmd.add(MAIN_CLASS);
        return cmd;
    }

    /**
     * 从本进程 classpath 剔除 UI 框架 jar（四个 javafx 模块 + atlantafx + ikonli）。
     * UI 框架模块名用字符串拼接构造 —— 与 {@code LayerBTest} 同一纪律：无头测试源码不得出现其字面量。
     */
    private static String headlessClasspath() {
        String fx = "java" + "fx";
        String cp = System.getProperty("java.class.path");
        return Arrays.stream(cp.split(File.pathSeparator))
                .filter(entry -> {
                    String name = new File(entry).getName().toLowerCase(Locale.ROOT);
                    return !name.startsWith(fx + "-")
                            && !name.startsWith("atlantafx")
                            && !name.startsWith("ikonli");
                })
                .collect(Collectors.joining(File.pathSeparator));
    }

    // ==================== 夹具与基线（与 LayerBTest 同口径） ====================

    /** 按基线同一锚点复制夹具到临时输入目录（mtime 固定，保证 {datetime} 类输出确定） */
    private Path prepareInput(List<String> fixtures) throws Exception {
        Path in = tmp.resolve("in");
        Files.createDirectories(in);
        for (String fx : fixtures) {
            Path src = BaselineCases.FIXTURE_DIR.resolve(fx);
            assertTrue(Files.exists(src), "夹具缺失：" + src);
            Path dst = in.resolve(fx);
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            Files.setLastModifiedTime(dst, FileTime.fromMillis(BaselineCases.FIXED_MTIME_EPOCH_MILLI));
        }
        return in;
    }

    /** 读基线里记录的「相对路径 -> SHA-256」产物条目 */
    private List<BaselineHasher.TreeEntry> baselineProducts(String caseName, String loc) throws Exception {
        Path f = Path.of("src", "test", "resources", "baselines", caseName + "." + loc + ".json");
        assertTrue(Files.exists(f), "基线缺失：" + f);
        String json = Files.readString(f);
        int i = json.indexOf("\"products\"");
        int j = json.indexOf("\"contentDigests\"");
        String seg = json.substring(i, j);
        List<BaselineHasher.TreeEntry> out = new ArrayList<>();
        var m = Pattern.compile("\\{\"path\": \"(.*?)\", \"sha256\": \"([0-9a-f]+)\"\\}").matcher(seg);
        while (m.find()) {
            out.add(new BaselineHasher.TreeEntry(m.group(1), m.group(2), null));
        }
        return out;
    }

    private void assertProductsMatchBaseline(Path out, String caseName, String loc) throws Exception {
        List<BaselineHasher.TreeEntry> expected = baselineProducts(caseName, loc);
        assertFalse(expected.isEmpty(), "基线 " + caseName + "." + loc + " 未解析出任何产物");
        assertEquals(expected, BaselineHasher.hashTree(out),
                "无头 CLI 产物应与 GUI 侧黄金基线逐字节一致：" + caseName + "." + loc);
    }

    // ==================== 冒烟用例 ====================

    @Test
    void onceTextMergedMatchesGoldenBaseline() throws Exception {
        Path in = prepareInput(List.of("simple-text.mht"));
        Path out = tmp.resolve("out-once-text");

        RunResult r = runBackend(
                "--once", "--locale", "zh", "--group",
                "--input", in.toString(),
                "--output", out.toString(),
                "--template-start", "=== {filename} @ {date} {time} ===\\n",
                "--template-end", "\\n=== {filename} 结束 ===\\n");

        assertEquals(0, r.code(), "无头 --once 应成功退出，输出：\n" + r.output());
        assertTrue(r.output().contains("[1/1] simple-text.mht"), "应打印逐文件进度：" + r.output());
        assertTrue(r.output().contains("grouped 1 item(s)"), "应演示导入→建组链路：" + r.output());
        assertTrue(r.output().contains("done: total=1 success=1 fail=0 skip=0"), "应打印汇总：" + r.output());
        assertProductsMatchBaseline(out, "text-template-vars", "zh");

        // 无头 logback：FILE appender 应把启动日志落盘（SINK 为空实现，落盘即证明配置生效）
        Path log = tmp.resolve("run-" + runCounter).resolve("logs").resolve("backend.log");
        assertTrue(Files.exists(log), "无头 logback 应把日志落盘：" + log);
        assertTrue(Files.readString(log).contains("once mode"), "落盘日志应含 OnceRunner 启动行：" + log);
    }

    @Test
    void onceImageExtractionMatchesGoldenBaseline() throws Exception {
        Path in = prepareInput(List.of("cn-filename.mht"));
        Path out = tmp.resolve("out-once-image");

        RunResult r = runBackend("--once", "--locale", "zh",
                "--input", in.toString(),
                "--output", out.toString(),
                "--extract", "images");

        assertEquals(0, r.code(), "无头 --once（图片）应成功退出，输出：\n" + r.output());
        assertProductsMatchBaseline(out, "image-cn-filename", "zh");
    }

    @Test
    void onceWithoutInputFailsWithUsageExitCode() throws Exception {
        RunResult r = runBackend("--once", "--output", tmp.resolve("o").toString());
        assertEquals(2, r.code(), "缺 --input 应为用法错误退出码 2，输出：\n" + r.output());
        assertTrue(r.output().contains("--input"), "失败原因应可读：" + r.output());
    }

    @Test
    void onceOnEmptyDirFailsWithRuntimeExitCode() throws Exception {
        Path empty = tmp.resolve("empty");
        Files.createDirectories(empty);
        RunResult r = runBackend("--once", "--input", empty.toString(), "--output", tmp.resolve("o2").toString());
        assertEquals(1, r.code(), "无可导入来源应为运行时失败退出码 1，输出：\n" + r.output());
        assertTrue(r.output().contains("no importable sources"), "失败原因应可读：" + r.output());
    }

    @Test
    void unknownOptionFailsWithUsageExitCode() throws Exception {
        RunResult r = runBackend("--bogus");
        assertEquals(2, r.code(), "未知选项应为用法错误退出码 2，输出：\n" + r.output());
        assertTrue(r.output().contains("unknown option: --bogus"), "失败原因应可读：" + r.output());
    }

    @Test
    void serveStaysResidentUntilStdinEof() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("serve"));
        Files.createDirectories(work.resolve("logs"));
        Path capture = work.resolve("capture.out");

        List<String> cmd = baseCommand();
        cmd.add("--serve");

        Process p = new ProcessBuilder(cmd)
                .directory(work.toFile())
                .redirectErrorStream(true)
                .redirectOutput(capture.toFile())
                .start();

        // 等协议就绪事件；后续 stdin EOF 必须结束服务。
        long deadline = System.currentTimeMillis() + 30_000;
        String seen = "";
        while (System.currentTimeMillis() < deadline) {
            try {
                seen = Files.readString(capture);
            } catch (Exception ignore) {
                // 子进程尚未写完文件：下一轮再看
            }
            if (seen.contains("\"event\":\"ready\"")) {
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(seen.contains("\"event\":\"ready\""), "应打印就绪事件：" + seen);
        assertTrue(p.isAlive(), "服务模式应保持常驻（就绪后不得自行退出）");

        p.getOutputStream().close();   // stdin EOF → 服务干净退出
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("服务在 stdin EOF 后未退出");
        }
        assertEquals(0, p.exitValue(), "stdin EOF 后应以 0 退出，输出：\n" + Files.readString(capture));
    }

    @Test
    void headlessLogbackConfigMustNotReferenceUiAppenders() throws Exception {
        Path config = Path.of("src", "main", "resources", "logback-headless.xml");
        assertTrue(Files.exists(config), "无头 logback 配置缺失：" + config);
        String xml = Files.readString(config);
        assertFalse(xml.contains("com.mhtextractor.logging"),
                "无头 logback 配置不得引用 GUI 日志包（冲突 C8）");
        assertFalse(xml.contains("TextAreaAppender"),
                "无头 logback 配置不得引用 GUI TextArea appender（冲突 C8）");
    }
}
