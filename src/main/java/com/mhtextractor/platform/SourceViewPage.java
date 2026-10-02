package com.mhtextractor.platform;

import com.mhtextractor.i18n.I18n;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
 *
 * <p><b>Q8a / R8-② 起的分工</b>：高亮内核已抽到 {@link HtmlSyntaxTokens}（产出 token），
 * 本类只剩"token → 给外部浏览器看的 HTML 字符串"的渲染。这样将来应用内查看器（{@code gui} 层）
 * 可以直接消费同一份 token，不必再写第二份扫描实现 —— 两条渲染路径不会分叉。
 * <b>本类的对外行为、{@code <style>}、文件名、日志键、可见性均未变</b>。</p>
 */

@Slf4j
final class SourceViewPage {

    private SourceViewPage() {
    }

    /** 源码视图页文件名（行号 + 高亮 + 链接下划线的源码页，CDP 原生方案的回退） */
    public static final String SOURCE_VIEW_NAME = "source-view.html";

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
                    + HtmlSyntaxPalette.styleBlock()
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
     * 生成源码视图主体：{@link HtmlSyntaxTokens#tokenize(String)} 逐行 token → 逐行渲染。
     *
     * <p>跨行的着色段（跨行注释/标签）在行边界处<b>先闭合、下一行重开</b>，
     * 保证行结构完好且换行信息零丢失 —— 与迁移前逐字节相同的行模板。
     */
    static String buildSourceViewBody(String html) {
        List<HtmlSyntaxTokens.Line> lines = HtmlSyntaxTokens.tokenize(html);
        LineRenderer r = new LineRenderer(lines.size());
        for (HtmlSyntaxTokens.Line line : lines) {
            for (HtmlSyntaxTokens.Token t : line.tokens()) {
                r.writeToken(t.kind(), t.text());
            }
            r.endLine();
        }
        return r.finish();
    }

    /**
     * 行渲染器：把 token 渲染成 {@code .row} 行；遇行尾提交当前行。
     * 行提交时若有未闭合 span（跨行标签/注释），先闭合、下一行内容起始处重开
     * —— 与迁移前 {@code LineWriter} 的行模板逐字节一致。
     */
    static final class LineRenderer {
        private final StringBuilder out = new StringBuilder();
        private final StringBuilder line = new StringBuilder();
        private final int gutterDigits;
        private int lineNo = 1;
        /** 上一行结尾仍处于"已开未闭"状态的着色类；null = 无 */
        private String openSpanAtLineStart = null;
        /** 本 token 的着色类；null = 纯文本（不包 span） */
        private String currentSpan = null;

        LineRenderer(int totalLines) {
            this.gutterDigits = String.valueOf(totalLines).length();
        }

        /** 渲染一段 token（转义后按 {@code \n} 切行提交）；{@code kind} 无 CSS 类时不包 span */
        void writeToken(HtmlSyntaxTokens.Kind kind, String text) {
            String cssClass = kind.cssClass();
            if (text.isEmpty()) {
                // 零长切片：迁移前照样写一对空 span，且不碰行缓冲（不能触发 flush）
                if (cssClass != null) {
                    line.append("<span class=\"").append(cssClass).append("\"></span>");
                }
                return;
            }
            String esc = HtmlSyntaxTokens.escapeHtml(text);
            if (cssClass != null) {
                line.append("<span class=\"").append(cssClass).append("\">");
                currentSpan = cssClass;
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
            if (cssClass != null) {
                line.append("</span>");
                currentSpan = null;
            }
        }

        /** 一行 token 结束：提交该行 */
        void endLine() {
            flush();
        }

        String finish() {
            return out.toString();
        }

        private void flush() {
            openSpanAtLineStart = currentSpan;
            if (openSpanAtLineStart != null) {
                line.append("</span>");
            }
            out.append("<div class=\"row\"><span class=\"ln\" style=\"width:")
                    .append(gutterDigits + 1).append("em\">").append(lineNo)
                    .append("</span><span class=\"lc\">").append(line).append("</span></div>");
            line.setLength(0);
            lineNo++;
            if (openSpanAtLineStart != null) {
                line.append("<span class=\"").append(openSpanAtLineStart).append("\">");
            }
        }
    }
}
