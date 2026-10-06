package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.core.MimeParser;
import com.mhtextractor.core.SourceParsers;
import com.mhtextractor.extractor.ImageExtractor;
import com.mhtextractor.extractor.TextExtractor;

import java.io.IOException;

/**
 * 预解析（解析 + 取正文/标题）下沉（P3-3b-7）：{@code gui.controller.PreparseController} 每条目的
 * 解析与正文/标题抽取<b>原样外迁</b> —— 判定顺序、异常吞并、空值回落逐字不变，仅把文案产生
 * （{@code preview.unparseable} / {@code preview.parseFailed}）留在 gui 层：本类以
 * {@link Kind#UNPARSEABLE} 表达「HTML 内容缺失」，解析/抽取本身抛出的异常<b>不在此处吞并</b>，
 * 仍由 gui 的外层 {@code catch (Exception)} 统一映射为「解析失败」状态（与原来一致）。
 *
 * <p>本类<b>零 JavaFX、零 I18n</b>，属 {@code app} 层。</p>
 */
public final class SourceParseService {

    private SourceParseService() {
    }

    /** 预解析结果三态：不可解析（HTML 缺失）/ 正文为空 / 就绪。 */
    public enum Kind { UNPARSEABLE, EMPTY, READY }

    /** 单条目预解析结果：{@code text} 仅 text 模式产出（image 模式恒为 null）；{@code title} 为抽取值或 null；
     *  {@code titleAssigned} 表示原实现是否会写 {@code cachedTitle}（为 false 时调用方不得触碰旧值）。 */
    public record ItemParse(Kind kind, String text, String title, boolean titleAssigned) {
    }

    /**
     * 逐条照搬 {@code PreparseController.preParseItems} 的解析与抽取（只搬不改）：
     * 按扩展名选解析器 → {@code textOnly=true} 解析 → HTML 缺失归 {@link Kind#UNPARSEABLE} →
     * text 模式取正文（三参抽取）并按开关/标记抽取标题（异常吞并归 null）→ 空正文归
     * {@link Kind#EMPTY}；image 模式按 {@code enableNameExtraction} 开关抽取标题，恒归
     * {@link Kind#READY}。解析本身的 {@link IOException} 向调用方传播（gui 外层吞并映射为解析失败）。
     * {@code titleAssigned} 与原实现「是否写 cachedTitle」逐分支等价（P3-3b-7b 修正）。
     */
    public static ItemParse parse(String path, AppConfig params, boolean textMode) throws IOException {
        var parser = SourceParsers.forPath(path);
        MimeParser.ParsedMht parsed = parser.parse(path, true, () -> false, null, null);   // 来源由扩展名选
        if (parsed.getHtmlContent() == null) {
            return new ItemParse(Kind.UNPARSEABLE, null, null, false);
        }
        if (textMode) {
            TextExtractor textExtractor = new TextExtractor(parsed);
            String text = textExtractor.extractText(params.isAutoFixEncoding(),
                    params.getStartMarker(), params.getEndMarker(), params.isUseRegex());
            // 标题缓存：与正式提取共用同一 extractTitle 调用（从完整 HTML 提取）；
            // 条件为假时原实现不动 cachedTitle（titleAssigned=false）
            boolean titleAssigned = params.isEnableTitleExtraction()
                    && params.getTitleStartMarker() != null
                    && !params.getTitleStartMarker().isEmpty();
            String title = null;
            if (titleAssigned) {
                try {
                    title = textExtractor.extractTitle(params.getTitleStartMarker(),
                            params.getTitleEndMarker(), params.isTitleUseRegex());
                    title = (title != null && !title.isEmpty()) ? title : null;
                } catch (Exception e) {
                    title = null;
                }
            }
            return new ItemParse((text == null || text.trim().isEmpty()) ? Kind.EMPTY : Kind.READY, text, title,
                    titleAssigned);
        }
        boolean titleAssigned = params.isEnableNameExtraction();
        String title = null;
        if (titleAssigned) {
            ImageExtractor imageExtractor = new ImageExtractor(parsed,
                    params.getImageNameStartMarker(), params.getImageNameEndMarker(),
                    null, null);
            title = imageExtractor.extractTitle();
            title = (title != null && !title.isEmpty()) ? title : null;
        }
        return new ItemParse(Kind.READY, null, title, titleAssigned);
    }
}
