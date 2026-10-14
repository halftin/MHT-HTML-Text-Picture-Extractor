package com.mhtextractor.app;

import com.mhtextractor.core.MimeParser;
import com.mhtextractor.core.SourceParsers;
import com.mhtextractor.util.HtmlExportBuilder;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 「在浏览器查看 HTML」的解析 + 导出段（P3-3b-4）：从 {@code gui.controller.SourceViewActions}
 * 的 {@code openHtmlInBrowser} 后台线程原样下沉到 app 层 —— 解析入口
 * （{@code SourceParsers.forPath(path)}）、解析参数（{@code textOnly=false}、三回调
 * {@code () -> false / null / null}）、空 HTML 判定与 {@code HtmlExportBuilder.export} 调用
 * 逐条照搬搬迁前的 :86-94（只搬不改）。
 *
 * <p>本类<b>零 JavaFX、零 I18n</b>，不产任何展示文案：解析无 HTML / 导出失败经
 * {@link Export#Unparseable()} / {@link Export#Failed(String)} 表达，
 * 文案键与日志键仍由 gui 层按原键渲染。</p>
 *
 * <p>本类属 {@code app} 层。</p>
 */
public final class SourceViewService {

    private SourceViewService() {
    }

    /** 解析 + 导出的三种结果（sealed，调用方穷举切换；对应搬迁前原代码的三种走向）。 */
    public sealed interface Export {
        /** 导出成功：html 文件路径 + 图片数与警告数（搬迁前 {@code HtmlExportBuilder.ExportResult} 的同名口径）。 */
        record Success(Path htmlFile, int imageCount, int warningCount) implements Export {
        }

        /** 解析所得内容没有 HTML（搬迁前：抛 {@code IllegalStateException(browser.exportUnparseable)}）。 */
        record Unparseable() implements Export {
        }

        /** 导出器自身报告失败（搬迁前：抛 {@code IllegalStateException(r.error())}）。 */
        record Failed(String error) implements Export {
        }
    }

    /**
     * 解析 + 导出（与搬迁前 {@code SourceViewActions} :86-94 逐字同义）：
     * {@code SourceParsers.forPath(path).parse(path, false, () -> false, null, null)}
     * → htmlContent 为 null ⇒ {@link Export.Unparseable}；否则
     * {@code HtmlExportBuilder.export(path, parsed)} → 未成功 ⇒ {@link Export.Failed(r.error())}；
     * 成功 ⇒ {@link Export.Success(r.htmlFile(), r.imageCount(), r.warnings().size())}。
     *
     * @param path 来源路径（绝对或相对；按扩展名选 MHT / HTML 解析器）
     * @throws IOException 解析阶段的 IO 异常<b>原样上抛</b>（搬迁前由调用方外层
     *         {@code catch (Exception)} 承接，异常语义不变）
     */
    public static Export export(String path) throws IOException {
        return export(path, null);
    }

    public static Export export(String path, Path root) throws IOException {
        MimeParser.ParsedMht parsed = SourceParsers.forPath(path).parse(path, false, () -> false, null, null);
        if (parsed.getHtmlContent() == null) {
            return new Export.Unparseable();
        }
        HtmlExportBuilder.ExportResult r = root == null ? HtmlExportBuilder.export(path, parsed)
                : HtmlExportBuilder.export(path, parsed, root);
        if (!r.success()) {
            return new Export.Failed(r.error());
        }
        return new Export.Success(r.htmlFile(), r.imageCount(), r.warnings().size());
    }
}
