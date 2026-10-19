package com.mhtextractor.baseline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * P0 闸门 1 扫描器（《UI可切换重构方案》§8 新增不变式 1）：uiport 包字节码零 JavaFX。
 *
 * <p><b>为什么扫字节码而不是源码</b>：与业务层零 JavaFX 断言（{@code businessPackagesHaveNoUiFrameworkDependency}）
 * 同一理由 —— 源码扫描会被通配 import 与全限定名引用骗过，字节码常量池里的 {@code javafx/}
 * 描述符是「编译后确实依赖」的铁证。</p>
 *
 * <p><b>包不存在时的语义</b>：{@code uiport} 是 P3 才建立的 UI 端口层；当前包不存在，扫描到
 * 0 个类、0 个违规 —— 这是<b>有效的空跑</b>，P0-1 的控制组已验证加一个违规类会把它跑红。</p>
 *
 * <p><b>自指安全</b>：UI 框架描述符前缀以拼接构造（见 {@link #FRAMEWORK_ROOT}），本文件源码不含
 * 该框架名小写字面量，因此不会被无头测试包的同类扫描自指误判。</p>
 */
public final class UiportSignature {

    private UiportSignature() {
    }

    /** uiport 包在 target/classes 下的相对路径。 */
    public static final String UIPORT_PACKAGE_DIR = "com/mhtextractor/uiport";

    /** UI 框架描述符前缀，拼接构造而非字面量（防无头扫描自指）。 */
    static final String FRAMEWORK_ROOT = "java" + "fx";

    /** 违规类：类名（'/' 分隔）与命中的描述符片段。 */
    public record Violation(String className, List<String> descriptors) {
    }

    /** 一次扫描结果：扫描到的类数与违规清单。 */
    public record Scan(int classesScanned, List<Violation> violations) {
    }

    /**
     * 扫描 {@code target/classes/com/mhtextractor/uiport/**} 的字节码。
     * 包不存在时视为 0 个类（通过）；编译产物缺失时报错（本闸门依赖字节码，不依赖源码）。
     */
    public static Scan scan(Path mainClasses) throws IOException {
        return scan(mainClasses, UIPORT_PACKAGE_DIR);
    }

    public static Scan scan(Path mainClasses, String packageDir) throws IOException {
        if (!Files.isDirectory(mainClasses)) {
            throw new IOException("未找到编译产物 " + mainClasses.toAbsolutePath()
                    + "，请先 mvn compile（本闸门依赖字节码，不依赖源码）");
        }
        Path uiport = mainClasses.resolve(packageDir);
        List<Path> classFiles = new ArrayList<>();
        if (Files.isDirectory(uiport)) {
            try (Stream<Path> walk = Files.walk(uiport)) {
                classFiles = walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".class"))
                        .sorted()
                        .toList();
            }
        }
        List<Violation> violations = new ArrayList<>();
        for (Path p : classFiles) {
            String rel = mainClasses.relativize(p).toString().replace('\\', '/');
            String className = rel.substring(0, rel.length() - ".class".length());
            List<String> hits = findFrameworkDescriptors(Files.readAllBytes(p));
            if (!hits.isEmpty()) {
                violations.add(new Violation(className, hits));
            }
        }
        return new Scan(classFiles.size(), violations);
    }

    /** 从 class 文件字节中提取出现的 UI 框架描述符（去重、限量输出）。 */
    private static List<String> findFrameworkDescriptors(byte[] classBytes) {
        String needle = FRAMEWORK_ROOT + "/";
        String asLatin1 = new String(classBytes, StandardCharsets.ISO_8859_1);
        List<String> hits = new ArrayList<>();
        int idx = 0;
        while ((idx = asLatin1.indexOf(needle, idx)) >= 0) {
            int end = idx;
            while (end < asLatin1.length()) {
                char c = asLatin1.charAt(end);
                if (c == ';' || c == '(' || c == ')' || c == '[' || c == '<' || c == '>'
                        || c == ' ' || c < 0x20 || c > 0x7e) {
                    break;
                }
                end++;
            }
            String desc = asLatin1.substring(idx, Math.min(end, idx + 120));
            if (desc.length() > 4 && hits.size() < 8) {
                hits.add(desc);
            }
            idx = Math.max(end, idx + 1);
        }
        return hits;
    }
}
