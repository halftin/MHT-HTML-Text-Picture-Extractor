package com.mhtextractor.platform;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2.6 的<b>机械证据</b>：主源集的字节码常量池里，WebView / 浏览器内核出现 <b>0 次</b>。
 *
 * <p><b>为什么这条必须钉死</b>：本机 Liberica Full JDK 把 {@code javafx.web} 内置成 JDK 运行时
 * 模块，所以 WebView 在开发机<b>能跑</b>；但 {@code build-native.cmd} 不抽 {@code jfxwebkit.dll}，
 * 一旦主源集引用了它，native 产物必然启动失败 —— 典型的"本机能跑、打包必炸"。
 * 光靠 review 守不住这条，所以按字节码判（{@code ArchitectureTest} 扫 JavaFX 泄漏用的也是这个口径：
 * 查常量池而不是查 import，因为全限定名引用能绕过 import 扫描）。</p>
 *
 * <p><b>为什么放在 {@code com.mhtextractor.platform} 而不是 {@code com.mhtextractor.baseline}</b>：
 * {@code ArchitectureTest.headlessTestPackagesHaveNoJavaFx} 会扫 {@code baseline} 与 {@code app}
 * 两个测试包的源码、且<b>字符串字面量也算命中</b>（它只剥注释）。本类必须把
 * {@code javafx/scene/web} 写成字面量才谈得上扫描，放进那两个包会直接触发那道闸门。
 * 放在这里不改变它的性质：它断言的是整个主源集。</p>
 *
 * <p>它断言的是"<b>代码引用</b>"而不是"出现过这个词"：本类的 javadoc 提到 WebView 不算数，
 * 只有 class 文件里真的出现描述符才算。</p>
 */
class NoWebViewDependencyTest {

    private static final Path MAIN_CLASSES = Path.of("target", "classes");

    /** 被禁的包前缀（class 文件里以 {@code /} 分隔出现）。 */
    private static final List<String> FORBIDDEN = List.of("javafx/scene/web", "com/sun/webkit");

    /**
     * 控制组用的"确实存在"的包：同一次扫描必须能在它上面找到命中，
     * 否则"0 命中"只说明扫描坏了，什么也证明不了。
     */
    private static final String CONTROL_PRESENT = "javafx/scene/text/TextFlow";

    @Test
    void mainSourcesDoNotReferenceWebViewOrABrowserKernel() throws IOException {
        List<Path> classFiles = mainClassFiles();
        assertTrue(classFiles.size() > 100,
                "扫描到的 class 文件太少（" + classFiles.size() + "）—— 先 mvn compile，"
                        + "否则这条断言是空转的");

        List<String> offenders = new ArrayList<>();
        Set<String> controlHits = new LinkedHashSet<>();
        for (Path p : classFiles) {
            byte[] bytes = Files.readAllBytes(p);
            String latin1 = new String(bytes, StandardCharsets.ISO_8859_1);
            for (String needle : FORBIDDEN) {
                int at = latin1.indexOf(needle);
                if (at >= 0) {
                    offenders.add(MAIN_CLASSES.relativize(p) + "  ->  "
                            + latin1.substring(at, Math.min(latin1.length(), at + 60)));
                }
            }
            if (latin1.contains(CONTROL_PRESENT)) {
                controlHits.add(MAIN_CLASSES.relativize(p).toString());
            }
        }

        assertFalse(controlHits.isEmpty(),
                "控制组失败：同一次扫描连 " + CONTROL_PRESENT + " 都找不到 —— "
                        + "扫描本身有问题，下面那条\"0 命中\"不能算证据");
        assertTrue(offenders.isEmpty(),
                "主源集字节码常量池里出现了 WebView / 浏览器内核（R2.6 红线，native 必炸）：\n  "
                        + String.join("\n  ", offenders));
        System.out.println("[nowebview] 扫描 " + classFiles.size() + " 个 class："
                + FORBIDDEN + " 命中 " + offenders.size() + " 处；"
                + "控制组 " + CONTROL_PRESENT + " 命中 " + controlHits.size() + " 个 class");
    }

    private static List<Path> mainClassFiles() throws IOException {
        assertTrue(Files.isDirectory(MAIN_CLASSES),
                "未找到编译产物 " + MAIN_CLASSES.toAbsolutePath() + "，请先 mvn compile");
        try (Stream<Path> walk = Files.walk(MAIN_CLASSES)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .sorted()
                    .toList();
        }
    }
}
