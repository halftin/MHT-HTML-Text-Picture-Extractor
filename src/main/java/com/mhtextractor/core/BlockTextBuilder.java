package com.mhtextractor.core;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** DOM boundaries become paragraphs; inline whitespace never creates a paragraph. */
public final class BlockTextBuilder {
    private static final Set<String> BLOCKS = Set.of("p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6",
            "tr", "blockquote", "section", "article", "ul", "ol", "table", "main", "header");
    private static final String DROP = "script,style,noscript,head,nav,aside,footer,form,button,iframe,svg";
    private static final String CONTAINERS = "#chaptercontent,#content,.read-content,.chapter-content,article,.content";
    // Only short, complete blocks are eligible. Long narrative paragraphs are never keyword-filtered.
    private static final Pattern DECORATION = Pattern.compile(
            "^(?:(?:本站|站点)?网址|https?://|www\\.|(?:请)?记住本站|本章节未完|字体设置|Copyright|声明|上一章|下一章|返回目录|翻页)",
            Pattern.CASE_INSENSITIVE);
    // A whole boilerplate template, restricted to a recognized content container. This is not a
    // keyword rule: a narrative mentioning a URL (even a short one) must not match this template.
    private static final Pattern SITE_NOTICE = Pattern.compile(
            "^我们马上记住本站网址[,，]www\\.[a-zA-Z0-9.-]+[,，]若被浏/览/器/转/码[,，]可退出转/码继续阅读[,，]感谢支持[.。]$");

    private BlockTextBuilder() {}

    public static String build(Element body) {
        Element clean = body.clone();
        clean.select(DROP).remove();
        List<Element> containers = List.of();
        for (String selector : CONTAINERS.split(",")) {
            containers = clean.select(selector);
            if (!containers.isEmpty()) break;
        }
        var builder = new Builder(!containers.isEmpty());
        if (containers.isEmpty()) builder.visit(clean);
        else for (Element container : containers) {
            if (container.parents().stream().noneMatch(containers::contains)) builder.visit(container);
        }
        builder.flush();
        return String.join("\n\n", builder.paragraphs);
    }

    private static final class Builder {
        private final List<String> paragraphs = new ArrayList<>();
        private final StringBuilder inline = new StringBuilder();
        private final boolean contentContainer;

        private Builder(boolean contentContainer) { this.contentContainer = contentContainer; }

        private void visit(Node node) {
            if (node instanceof TextNode text) {
                inline.append(text.getWholeText().replaceAll("(?U)\\s+", " "));
            } else if (node instanceof Element element) {
                String tag = element.normalName();
                if (tag.equals("br")) {
                    trimSpace();
                    inline.append('\n');
                } else if (tag.equals("pre")) {
                    flush();
                    String pre = element.wholeText().replace("\r\n", "\n").replace('\r', '\n');
                    if (!pre.isBlank()) paragraphs.add(pre);
                } else {
                    boolean block = BLOCKS.contains(tag);
                    if (block) flush();
                    for (Node child : element.childNodes()) visit(child);
                    if (block) flush();
                }
            }
        }

        private void trimSpace() {
            while (!inline.isEmpty() && inline.charAt(inline.length() - 1) == ' ') inline.setLength(inline.length() - 1);
        }

        private void flush() {
            String value = inline.toString().replaceAll(" +", " ").replaceAll(" *\n *", "\n").strip();
            inline.setLength(0);
            if (contentContainer && SITE_NOTICE.matcher(value).matches()) return;
            if (!value.isEmpty() && !(value.codePointCount(0, value.length()) <= 40
                    && DECORATION.matcher(value).find())) paragraphs.add(value);
        }
    }
}
