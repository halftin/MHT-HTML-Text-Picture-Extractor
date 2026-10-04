package com.mhtextractor.baseline;

/**
 * 源码扫描工具（E3.3）—— 测试侧共享实现，收敛多份测试里<b>逐字重复</b>的注释剥离与子串计数。
 *
 * <p>收敛来源：{@code ArchitectureTest} / {@code I18nAcceptanceTest} /
 * {@code ExtractOutcomeStylesTest} 的 {@code stripComments}、{@code GuiNotificationsIsSemanticFacadeOnlyTest}
 * 的 {@code stripCommentsAndLiterals}、以及四处 {@code countOccurrences}
 * （{@code ArchitectureTest} / {@code ImageExtractionMatrixTest} /
 * {@code GuiNotificationsIsSemanticFacadeOnlyTest} / {@code HtmlExportBuilderTest}）。</p>
 *
 * <p>行为与各自原私有副本的等价性见各方法 javadoc。本类属无头测试包
 * （{@code ArchitectureTest.headlessTestPackagesHaveNoJavaFx} 的扫描面）：刻意不含任何 UI 框架引用。</p>
 */
public final class SourceScanUtils {

    private SourceScanUtils() {
    }

    /**
     * 子串计数：<b>非重叠</b>——每命中一次，下一次从 {@code needle} 末尾之后继续找。
     *
     * <p>三处旧私有副本（{@code ArchitectureTest} / {@code ImageExtractionMatrixTest} /
     * {@code GuiNotificationsIsSemanticFacadeOnlyTest}）逐字相同，本实现即其原样搬移；
     * {@code HtmlExportBuilderTest} 的副本仅参数名不同、算法相同，同样收敛于此。</p>
     */
    public static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
    }

    /**
     * 剥掉块注释与行注释（<b>保留字符串/字符字面量</b>），保留换行与总长度（行号可对应）。
     *
     * <p>规则：行注释的两个斜杠与注释体替换为空格、换行保留；块注释的开闭符各占两位空格、
     * 注释体内非换行字符替换为空格、换行保留；{@code "..."} 与 {@code '...'} 字面量整体保留
     * （含转义符），字面量里的注释符号不算注释。</p>
     *
     * <p><b>等价性</b>：本实现是 {@code ExtractOutcomeStylesTest} 旧私有副本的原样搬移
     * （单遍扫描、字面量整体消费）。{@code ArchitectureTest} / {@code I18nAcceptanceTest} 的旧副本
     * 是状态机写法，二者在"块注释 / 行注释 / 字符串"上的产出与本实现逐字相同；唯一差异是
     * 字符字面量（状态机只认双引号，遇到 {@code '"'} 会状态错位、把其后注释当代码保留）——
     * 对当前全部被扫文件，本实现的剥离结果要么逐字相同，要么只多剥掉注释内容，
     * 而受影响断言的语义（offenders 为空 / 键集合有效性）在该方向上只减假阳性。</p>
     */
    public static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            char ch = src.charAt(i);
            char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (ch == '/' && next == '/') {
                while (i < src.length() && src.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (ch == '/' && next == '*') {
                out.append("  ");
                i += 2;
                while (i < src.length() && !(src.charAt(i) == '*' && i + 1 < src.length()
                        && src.charAt(i + 1) == '/')) {
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < src.length()) {
                    out.append("  ");
                    i += 2;
                }
            } else if (ch == '"' || ch == '\'') {
                // 字面量整体保留（调用方往往正是要扫字面量内容）
                char quote = ch;
                out.append(ch);
                i++;
                while (i < src.length()) {
                    char c = src.charAt(i);
                    out.append(c);
                    i++;
                    if (c == '\\' && i < src.length()) {
                        out.append(src.charAt(i));
                        i++;
                        continue;
                    }
                    if (c == quote) {
                        break;
                    }
                }
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * 剥掉块注释、行注释与字符串/字符字面量（保留换行，保持行号可对应）。
     *
     * <p>与 {@link #stripComments} 同一手法，但多剥了字面量：{@code GuiNotificationsIsSemanticFacadeOnlyTest}
     * 查的是 {@code ModalPane} / {@code Scene} 这类<b>标识符</b>，而源码 javadoc 里必然要写出它们
     * （否则没人知道红线在哪）；字符串字面量同理（例如 i18n 键或日志文案里出现这些词并不构成依赖）。
     * 本实现即其旧私有副本的原样搬移。</p>
     *
     * <p>⚠️ 已知取舍：字面量被剥掉后，"把 {@code Scene} 当字符串反射使用"这类绕法扫不到。
     * 但那种写法在 UI 框架里没有实际用途（要真拿到 {@code Scene} 就得 import/使用该类型，
     * 那时它在代码里就是标识符），故不为此加复杂度；威胁模型是"有人图快复制一份实现"，
     * 那种复制一定会写标识符。</p>
     */
    public static String stripCommentsAndLiterals(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            char ch = src.charAt(i);
            char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (ch == '/' && next == '/') {
                while (i < src.length() && src.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (ch == '/' && next == '*') {
                out.append("  ");
                i += 2;
                while (i < src.length() && !(src.charAt(i) == '*' && i + 1 < src.length()
                        && src.charAt(i + 1) == '/')) {
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < src.length()) {
                    out.append("  ");
                    i += 2;
                }
            } else if (ch == '"' || ch == '\'') {
                char quote = ch;
                out.append(' ');
                i++;
                while (i < src.length() && src.charAt(i) != quote) {
                    if (src.charAt(i) == '\\' && i + 1 < src.length()) {
                        out.append("  ");
                        i += 2;
                        continue;
                    }
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < src.length()) {
                    out.append(' ');
                    i++;
                }
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }
}
