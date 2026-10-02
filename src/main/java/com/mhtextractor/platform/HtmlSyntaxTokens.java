package com.mhtextractor.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 源码的语法高亮 <b>token 模型</b>（Q8a / R8-② 从 {@link SourceViewPage} 抽出的高亮内核）。
 *
 * <p><b>为什么抽出</b>：{@code SourceViewPage} 原先把两件事捆在一起 —— ① 高亮内核（扫描源码 →
 * 分段着色）、② 渲染成给<b>外部浏览器</b>看的 HTML 字符串。而 R2 要在<b>应用内</b>做查看器，
 * 它需要的是 <b>token</b> 而不是 HTML 字符串；不先抽 token，两条渲染路径必然分叉
 * （正是 R8 要消灭的「同一语义两份独立实现」）。</p>
 *
 * <p><b>边界</b>：本类零 JavaFX、零第三方依赖、纯 Java —— 它要被 {@code gui} 与 {@code platform}
 * 共用，依赖方向只能是 {@code gui → platform}。页头文案之类的本地化决策<b>不在</b>本类。</p>
 *
 * <p><b>与迁移前的等价性</b>：本类的扫描逻辑与迁移前 {@code SourceViewPage.buildSourceViewBody}
 * 的内核<b>逐分支一致</b>（注释/doctype/标签/文本四类、三条"整尾着色"的未闭合分支、
 * 属性值的引号内 {@code >} 不算标签结束等细节）；差别只在于产出的不是 HTML 字符串而是
 * {@code List<Line>}。{@code SourceViewPage} 的渲染器据此产出的 HTML 与迁移前<b>逐字节相同</b>
 * （由 {@code .claude-work} 的字节级装置在 53 个语料用例上验证）。</p>
 *
 * <p><b>换行归一化</b>：与迁移前一致，先做 {@code \r\n} / {@code \r} → {@code \n}，行号栏宽度由
 * 归一化后的总行数决定。token 的 {@link Token#text()} 是<b>未转义</b>的原文
 * （转义由渲染器负责，见 {@link #escapeHtml(String)}）。</p>
 */
public final class HtmlSyntaxTokens {

    private HtmlSyntaxTokens() {
    }

    /** 源码中的 URL（文本段与属性值内加下划线链接样式） */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>]+");

    /** 着色类别。{@link #cssClass()} 是「类别 → CSS 类名」的<b>唯一定义处</b>。 */
    public enum Kind {
        /** 标签名、尖括号、斜杠、以及"既不是名字也不是属性"的兜底字符 */
        TAG("hl-tag"),
        /** 属性名、{@code =}、属性名前后的空白 */
        ATTR("hl-attr"),
        /** 属性值（非 URL） */
        VAL("hl-val"),
        /** 属性值且整串是 URL —— <b>一个 class 属性里两个类名</b>，不是两个 span */
        VAL_LINK("hl-val hl-link"),
        /** 注释（含 {@code <!--} 与 {@code -->}） */
        COMMENT("hl-cm"),
        /** doctype 声明 */
        DOCTYPE("hl-dt"),
        /** 正文文本 */
        TEXT(null),
        /** 正文文本里内嵌的 URL */
        LINK("hl-link");

        private final String cssClass;

        Kind(String cssClass) {
            this.cssClass = cssClass;
        }

        /** 该类别对应的 CSS 类名；{@code null} = 不包 span（纯文本）。 */
        public String cssClass() {
            return cssClass;
        }
    }

    /** 一段着色的原文。{@code text} <b>未转义</b>；不包含行尾的 {@code \n}（行尾换行由行结构表达）。 */
    public record Token(Kind kind, String text) {
    }

    /** 一行：{@code tokens} 按源码顺序排列，<b>不含</b>行尾换行符。 */
    public record Line(List<Token> tokens) {
    }

    /**
     * 唯一入口：HTML 源码 → 逐行 token（行数与「归一化后换行数 + 1」一致）。
     *
     * @param html 原始 HTML（可含 {@code \r\n} / {@code \r}）
     * @return 每一行的 token 列表；行尾换行符不进入 token
     */
    public static List<Line> tokenize(String html) {
        String normalized = normalizeNewlines(html);
        List<Token> flat = new ArrayList<>();
        int i = 0;
        int n = normalized.length();
        while (i < n) {
            char c = normalized.charAt(i);
            if (c == '<') {
                if (normalized.startsWith("<!--", i)) {
                    int end = normalized.indexOf("-->", i + 4);
                    if (end < 0) {
                        emit(flat, Kind.COMMENT, normalized.substring(i));
                        break;
                    }
                    emit(flat, Kind.COMMENT, normalized.substring(i, end + 3));
                    i = end + 3;
                    continue;
                }
                if (normalized.regionMatches(true, i, "<!doctype", 0, 9)) {
                    int end = normalized.indexOf('>', i);
                    if (end < 0) {
                        emit(flat, Kind.DOCTYPE, normalized.substring(i));
                        break;
                    }
                    emit(flat, Kind.DOCTYPE, normalized.substring(i, end + 1));
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
                    emit(flat, Kind.TAG, normalized.substring(i));
                    break;
                }
                tokenizeTag(flat, normalized.substring(i, j + 1));
                i = j + 1;
            } else {
                int next = normalized.indexOf('<', i);
                if (next < 0) {
                    tokenizeText(flat, normalized.substring(i));
                    break;
                }
                tokenizeText(flat, normalized.substring(i, next));
                i = next;
            }
        }
        return splitLines(flat, normalized);
    }

    /**
     * 高亮单个标签：{@code <} 与 {@code /} 与标签名与间隙用 {@link Kind#TAG}；
     * 属性名与 {@code =} 用 {@link Kind#ATTR}；属性值用 {@link Kind#VAL}（URL 值用 {@link Kind#VAL_LINK}）。
     *
     * <p>⚠️ 属性名起点等于终点时写单个字符为 {@code TAG} 并前进一格 —— 这段兜底与迁移前逐字一致。</p>
     */
    private static void tokenizeTag(List<Token> out, String tag) {
        int i = 1;
        if (i < tag.length() && tag.charAt(i) == '/') {
            i++;
        }
        int nameEnd = i;
        while (nameEnd < tag.length() && isNameChar(tag.charAt(nameEnd))) {
            nameEnd++;
        }
        emit(out, Kind.TAG, tag.substring(0, nameEnd));
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
                emit(out, Kind.TAG, tag.substring(runStart, nameEnd));
                continue;
            }
            int attrStart = nameEnd;
            while (nameEnd < tag.length() && isNameChar(tag.charAt(nameEnd))) {
                nameEnd++;
            }
            if (nameEnd == attrStart) {
                emit(out, Kind.TAG, String.valueOf(c));
                nameEnd++;
                continue;
            }
            emit(out, Kind.ATTR, tag.substring(attrStart, nameEnd));
            int wsStart = nameEnd;
            while (nameEnd < tag.length() && Character.isWhitespace(tag.charAt(nameEnd))) {
                nameEnd++;
            }
            emit(out, Kind.ATTR, tag.substring(wsStart, nameEnd));
            if (nameEnd < tag.length() && tag.charAt(nameEnd) == '=') {
                emit(out, Kind.ATTR, "=");
                nameEnd++;
                int ws2 = nameEnd;
                while (nameEnd < tag.length() && Character.isWhitespace(tag.charAt(nameEnd))) {
                    nameEnd++;
                }
                emit(out, Kind.ATTR, tag.substring(ws2, nameEnd));
                // 属性值（带引号或不带引号）
                if (nameEnd < tag.length() && (tag.charAt(nameEnd) == '"' || tag.charAt(nameEnd) == '\'')) {
                    char q = tag.charAt(nameEnd);
                    int valStart = nameEnd;
                    do {
                        nameEnd++;
                    } while (nameEnd < tag.length() && tag.charAt(nameEnd) != q);
                    if (nameEnd < tag.length()) {
                        nameEnd++;
                    }
                    emitValue(out, tag.substring(valStart, nameEnd));
                } else {
                    int valStart = nameEnd;
                    while (nameEnd < tag.length() && !Character.isWhitespace(tag.charAt(nameEnd))
                            && tag.charAt(nameEnd) != '>') {
                        nameEnd++;
                    }
                    emitValue(out, tag.substring(valStart, nameEnd));
                }
            }
        }
    }

    /** 纯文本段：内嵌 http(s) URL 单独成 {@link Kind#LINK}（尾随标点不入链接，见 {@link #trimUrl}） */
    private static void tokenizeText(List<Token> out, String s) {
        Matcher m = URL_PATTERN.matcher(s);
        int last = 0;
        while (m.find()) {
            String url = trimUrl(m.group());
            emit(out, Kind.TEXT, s.substring(last, m.start()));
            emit(out, Kind.LINK, url);
            // ⚠️ 用的是 trim 后的长度：尾随标点留在后面的纯文本段里（迁移前逐字一致）
            last = m.start() + url.length();
        }
        emit(out, Kind.TEXT, s.substring(last));
    }

    /** 属性值：整串是 URL 时用 {@link Kind#VAL_LINK} */
    private static void emitValue(List<Token> out, String rawValue) {
        emit(out, isUrlValue(rawValue) ? Kind.VAL_LINK : Kind.VAL, rawValue);
    }

    /** 追加一段着色。⚠️ <b>空串也要追加</b>：迁移前对零长切片照样写 span（渲染器据空 token 产
     *  {@code <span class="hl-attr"></span>}），丢掉就与迁移前不再逐字节相同。 */
    private static void emit(List<Token> out, Kind kind, String text) {
        out.add(new Token(kind, text));
    }

    /** 把扁平 token 流按 {@code \n} 切成行（token 边界与行边界一一对应），行尾换行不进 token */
    private static List<Line> splitLines(List<Token> flat, String normalized) {
        List<Line> lines = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        for (Token t : flat) {
            String text = t.text();
            int from = 0;
            int nl;
            while ((nl = text.indexOf('\n', from)) >= 0) {
                current.add(new Token(t.kind(), text.substring(from, nl)));
                lines.add(new Line(current));
                current = new ArrayList<>();
                from = nl + 1;
            }
            // 余段（可能为空）留在本行缓冲里：迁移前也是写进行缓冲、由下一次 flush 提交
            current.add(new Token(t.kind(), text.substring(from)));
        }
        // flush() 在结尾无条件再提交一行：归一化后为空串时也要有第 1 行
        lines.add(new Line(current));
        if (lines.size() != 1 + countNewlines(normalized)) {
            throw new IllegalStateException("line count mismatch: " + lines.size()
                    + " vs " + (1 + countNewlines(normalized)));
        }
        return lines;
    }

    /** 换行归一化（{@code \r\n} / {@code \r} → {@code \n}） */
    public static String normalizeNewlines(String html) {
        return html.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 归一化后的换行数 */
    public static int countNewlines(String html) {
        int count = 0;
        for (int i = 0; i < html.length(); i++) {
            if (html.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /** 标签名/属性名字符：字母数字与 {@code - : _} */
    public static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '_';
    }

    /** 去掉 URL 尾部标点（句读号在正文里不属于链接）：循环剥掉 {@code .,;:!?)]}>} */
    public static String trimUrl(String url) {
        String t = url;
        while (!t.isEmpty() && ".,;:!?)]}>".indexOf(t.charAt(t.length() - 1)) >= 0) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /** 属性值是否"整串是 URL"：剥掉首尾成对引号 → {@link #trimUrl} → {@link #URL_PATTERN} <b>整串匹配</b> */
    public static boolean isUrlValue(String rawValue) {
        String inner = rawValue;
        if (inner.length() >= 2 && ((inner.startsWith("\"") && inner.endsWith("\""))
                || (inner.startsWith("'") && inner.endsWith("'")))) {
            inner = inner.substring(1, inner.length() - 1);
        }
        return URL_PATTERN.matcher(trimUrl(inner)).matches();
    }

    /**
     * HTML 转义（{@code & < > "}）。
     *
     * <p>⚠️ <b>替换顺序不可动</b>：{@code &} 必须最先，否则 {@code &lt;} 会被二次转义成 {@code &amp;lt;}。</p>
     */
    public static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
