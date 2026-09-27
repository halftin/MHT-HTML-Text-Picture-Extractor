package com.mhtextractor.platform;

import com.mhtextractor.i18n.I18n;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 源码视图页生成（步骤 5b 从 {@code BrowserLauncher} 拆出的第 1 个职责）。
 *
 * <p><b>职责</b>：把导出的 HTML 渲染成"仿 Edge/Chromium 原生源码查看器"的页面 ——
 * 行号栏 + 语法高亮（标签/属性/属性值/注释/doctype）+ 文本内 URL 下划线，
 * 逐行包装为 {@code .row}。换行先归一化（{@code \r\n} / {@code \r} → {@code \n}），
 * 行边界处闭合未完成的着色 span、下一行重开，保证行结构与换行信息零丢失。</p>
 *
 * <p><b>为什么独立成类</b>：它是纯字符串/IO 处理，<b>不依赖</b>浏览器探测、进程启动或 CDP 协议，
 * 是原 866 行类里边界最清晰的一块（约 250 行）。拆出后 {@code BrowserLauncher} 只保留
 * "打开浏览器"的编排。</p>
 */

@Slf4j
final class SourceViewPage {

    private SourceViewPage() {
    }

    /** 源码视图页文件名（行号 + 高亮 + 链接下划线的源码页，CDP 原生方案的回退） */
    public static final String SOURCE_VIEW_NAME = "source-view.html";

    /** 源码中的 URL（文本段与属性值内加下划线链接样式） */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>]+");

    /**
     * 源码视图页：逐行渲染（行号栏 + 语法高亮 + 链接下划线），观感贴近 Edge/Chromium
     * 原生源码查看器。换行先归一化（\r\n / \r → \n），每行一条 .row，行号右对齐灰色栏。
     *
     * @param pageHeader 页头 HTML（由<b>调用方</b>提供：这一层做的是与文件系统/字符串打交道的
     *                   基础设施工作，不应自己决定"用什么语言显示什么文案"。步骤 7 之前这里直接
     *                   去取本地化文案，把 UI 文案决策带进了 platform 层 ——
     *                   换 UI 框架 / 换语言时这一层会被迫跟着改，方向是反的。
     */
    static Path writeSourceViewPage(Path indexHtml, String pageHeader) {
        try {
            String html = Files.readString(indexHtml, StandardCharsets.UTF_8);
            String pageHtml = pageHeader
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
            log.info(I18n.msg("log.sourceviewpage.01", page));
            return page;
        } catch (IOException e) {
            log.warn(I18n.msg("log.sourceviewpage.02", indexHtml), e);
            return null;
        }
    }

    /**
     * 生成源码视图主体：整篇一次扫描（跨行标签/注释着色不断），遇 \n 提交一行；
     * 行边界处闭合未完成的着色 span，下一行重开，保证行结构完好且换行信息零丢失。
     */
    static String buildSourceViewBody(String html) {
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
    static void writeTag(LineWriter w, String tag) {
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
                    do {
                        nameEnd++;
                    } while (nameEnd < tag.length() && tag.charAt(nameEnd) != q);
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
    static String valueClass(String rawValue) {
        String inner = rawValue;
        if (inner.length() >= 2 && ((inner.startsWith("\"") && inner.endsWith("\""))
                || (inner.startsWith("'") && inner.endsWith("'")))) {
            inner = inner.substring(1, inner.length() - 1);
        }
        return URL_PATTERN.matcher(trimUrl(inner)).matches() ? "hl-val hl-link" : "hl-val";
    }

    /** 标签名/属性名字符：字母数字与 - : _ */
    static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '_';
    }

    /** 去掉 URL 尾部标点（句读号在正文里不属于链接） */
    static String trimUrl(String url) {
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
    static final class LineWriter {
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

