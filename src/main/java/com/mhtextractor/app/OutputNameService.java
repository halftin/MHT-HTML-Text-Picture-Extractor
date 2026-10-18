package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.OutputName;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.processor.ImageBatchProcessor;
import com.mhtextractor.util.SanitizeUtils;
import com.mhtextractor.util.SourceKinds;

import java.io.File;

/**
 * 产物命名规则（P3-3b）：文本模式输出文件名 / 图片模式输出目录名的<b>名字本体</b>计算，
 * 从 {@code gui.controller.WorkspaceInfoPresenter} 原样下沉到 app 层 ——
 * 判定与回落顺序逐条照搬搬迁前的 {@code updateInfoArea} 命名段与
 * {@code separateDirName} / {@code mergedFolderName}（只搬不改）。
 *
 * <p>本类只产名字本体，<b>不产任何展示文案</b>：解析中状态经 {@link Name#parsing()} 表达
 * （此时 {@link Name#value()} 为 null），由 gui 层用 {@code preview.parsing} 键渲染；
 * 前后缀（preview.* 键）与最终显示字符串仍由 gui 层拼装。</p>
 *
 * <p>本类<b>零 JavaFX、零 I18n</b>，属 {@code app} 层。</p>
 */
public final class OutputNameService {

    private OutputNameService() {
    }

    /**
     * 文本模式输出文件名本体（不含 {@code preview.outputFilePrefix} 前缀与
     * {@code preview.outputFileTxtSuffix} 后缀）：
     * separate 的 baseName 为 {@code SourceKinds.stripKnownExtension(item.name)}；
     * merged 的 baseName 为输入目录名（空则 {@code mht_output}），extension 均为 {@code txt}。
     */
    public static OutputName textOutputName(PreviewItem item, AppConfig params, String inputFolder) {
        if (params.isSeparateFiles()) {
            return new OutputName(SourceKinds.stripKnownExtension(item.name), "txt");
        }
        String inputDirName = new File(inputFolder).getName();
        if (inputDirName.isEmpty()) {
            inputDirName = "mht_output";
        }
        return new OutputName(inputDirName, "txt");
    }

    /**
     * 图片模式输出目录名本体。singleOutput（合并）用首条可见项 {@code firstVisible} 的标题，
     * 回落 {@link ImageBatchProcessor#determineDefaultFolderName}；否则（单独）用 {@code item} 的标题，
     * 回落 {@link SourceKinds#stripKnownExtension}（与搬迁前 {@code mergedFolderName} /
     * {@code separateDirName} 逐条一致：标题提取开启且状态 WAITING → 解析中；cachedTitle 非空 → sanitize）。
     */
    public static Name imageOutputName(PreviewItem item, AppConfig params, PreviewItem firstVisible, String inputFolder) {
        if (params.isSingleOutputMode()) {
            if (params != null && params.isEnableNameExtraction() && firstVisible != null) {
                if (firstVisible.status == PreviewItem.Status.WAITING) {
                    return new Name(new OutputName(null, null), true);
                }
                if (firstVisible.cachedTitle != null) {
                    return new Name(new OutputName(SanitizeUtils.sanitize(firstVisible.cachedTitle), null), false);
                }
            }
            return new Name(new OutputName(ImageBatchProcessor.determineDefaultFolderName(inputFolder), null), false);
        }
        if (params != null && params.isEnableNameExtraction()) {
            if (item.status == PreviewItem.Status.WAITING) {
                return new Name(new OutputName(null, null), true);
            }
            if (item.cachedTitle != null) {
                return new Name(new OutputName(SanitizeUtils.sanitize(item.cachedTitle), null), false);
            }
        }
        return new Name(new OutputName(SourceKinds.stripKnownExtension(item.name), null), false);
    }

    /**
     * 图片模式输出目录名本体的计算产物：{@code parsing} = true 表示「解析中」，
     * 此时 {@code value} 为 null —— 展示文案（{@code preview.parsing}）由 gui 层渲染，
     * app 不产文案。
     */
    public record Name(OutputName name, boolean parsing) {
        /** 旧 GUI 显示口径保持不变；结构化事实直接取 name。 */
        public String value() { return name.directory() ? name.baseName() : name.finalFileName(); }
    }
}
