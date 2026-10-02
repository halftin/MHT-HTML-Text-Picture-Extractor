package com.mhtextractor.gui.component;

import com.mhtextractor.platform.HtmlSyntaxTokens;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 查看器的查找索引（R5.5：纯 {@code java.util.regex}，<b>不新增依赖</b>）。
 *
 * <p>仓库里没有现成查找器可复用，而查看器要的是"命中位置 + 循环导航"这一件事：
 * 本类把<b>归一化后的整篇源码</b>扫一遍，产出按出现顺序排好的命中列表，每个命中记
 * {@code (行号, 行内起始列, 行内结束列)} —— 查看器的 cell 只按这三个数着色，
 * 不需要自己再扫一遍文本。</p>
 *
 * <h2>匹配口径（Q8b 的取舍，写在类里以便复核）</h2>
 * <ul>
 *   <li><b>字面量，不是正则</b>：用户按 Ctrl+F 的预期是"找这段文字"，不是"把它当模式"。
 *       所以用户输入一律经 {@link Pattern#quote(String)}；否则 {@code .} {@code *}
 *       {@code (} 之类会突然变成元字符，输入 {@code a.b} 命中 {@code axb} 这类结果没人预期得到。</li>
 *   <li><b>大小写不敏感</b>：{@link Pattern#CASE_INSENSITIVE}。查源代码时用户很少关心大小写，
 *       而"大小写敏感"会把 {@code <DIV>} 漏掉 {@code <div>} 的查找变成"明明看得见却搜不到"。</li>
 *   <li><b>空查询 = 无命中</b>（不是"命中一切"）：清空输入框应当显示 {@code 0}，而不是列出全文。</li>
 *   <li><b>绝不抛异常</b>（R5.4）：{@code Pattern.quote} 之后语法上不可能失败，
 *       但本类仍兜住 {@link PatternSyntaxException} 并退化成"无命中" ——
 *       搜索框里敲进任何字符都不该把界面打崩。</li>
 *   <li><b>不重叠</b>：与所有查找框一致（在 {@code aaa} 里找 {@code aa} 得 1 处，不是 2 处）。</li>
 * </ul>
 *
 * <p><b>为什么行号/列号由本类算</b>：{@code tokenize} 的每个 token 都<b>不跨行</b>
 * （{@link HtmlSyntaxTokens} 已保证），所以"归一化后的全文"与"逐行 token 拼起来"
 * 是同一个字符串 —— 在全文上取命中偏移，再二分回行号，与逐行扫是等价且更快的。</p>
 */
final class HtmlFindIndex {

    /** 一次命中：行下标（0 基）+ 该行内的半开区间 {@code [startCol, endCol)}。 */
    record Match(int line, int startCol, int endCol) {
    }

    /** 无命中的空索引（查询为空 / 无法编译时用它，避免各处判 null）。 */
    static final HtmlFindIndex EMPTY = new HtmlFindIndex(List.of());

    private final List<Match> matches;

    private HtmlFindIndex(List<Match> matches) {
        this.matches = Collections.unmodifiableList(matches);
    }

    /** 命中总数（R5.3 的 m）。 */
    int size() {
        return matches.size();
    }

    boolean isEmpty() {
        return matches.isEmpty();
    }

    /** 第 {@code i} 个命中；越界抛 {@link IndexOutOfBoundsException}（调用方保证 0 ≤ i < size()）。 */
    Match get(int i) {
        return matches.get(i);
    }

    /**
     * 建索引。
     *
     * @param normalizedHtml 已归一化换行的整篇源码（{@link HtmlSyntaxTokens#normalizeNewlines}）
     * @param lines          {@link HtmlSyntaxTokens#tokenize(String)} 的逐行结果（只用于校验行边界）
     * @param query          用户输入（null / 空 → {@link #EMPTY}）
     */
    static HtmlFindIndex build(String normalizedHtml, List<HtmlSyntaxTokens.Line> lines, String query) {
        if (query == null || query.isEmpty() || normalizedHtml == null || normalizedHtml.isEmpty()) {
            return EMPTY;
        }
        int[] lineStarts = lineStarts(lines, normalizedHtml);
        if (lineStarts == null) {
            return EMPTY;
        }
        List<Match> found = new ArrayList<>();
        try {
            Matcher m = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE)
                    .matcher(normalizedHtml);
            while (m.find()) {
                int from = m.start();
                int to = m.end();
                if (to > from) {
                    int line = lineOf(lineStarts, from);
                    found.add(new Match(line, from - lineStarts[line], to - lineStarts[line]));
                }
            }
        } catch (PatternSyntaxException e) {
            // R5.4：无命中不报错
            return EMPTY;
        }
        return found.isEmpty() ? EMPTY : new HtmlFindIndex(found);
    }

    /**
     * 逐行起点数组；同时校验"逐行 token 拼起来 == 归一化全文"（token 不跨行 ⇒ 必须成立）。
     *
     * <p>🚩 校验失败返回 {@code null}（= 无命中）而<b>不是</b>抛异常：这是查看器的查找功能，
     * 用户不该因为我们内部的口径漂移而看到崩溃。口径本身由
     * {@code HtmlFindIndexTest} 的等价性用例钉住。</p>
     */
    private static int[] lineStarts(List<HtmlSyntaxTokens.Line> lines, String normalizedHtml) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        int[] starts = new int[lines.size()];
        int off = 0;
        for (int i = 0; i < lines.size(); i++) {
            starts[i] = off;
            for (HtmlSyntaxTokens.Token t : lines.get(i).tokens()) {
                off += t.text().length();
            }
            if (i + 1 < lines.size()) {
                off++;   // 行尾换行（token 里不含它）
            }
        }
        return off == normalizedHtml.length() ? starts : null;
    }

    /** 二分：{@code offset} 落在哪一行（{@code starts} 严格递增）。 */
    private static int lineOf(int[] starts, int offset) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }
}
