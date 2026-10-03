package com.mhtextractor.core;

import com.mhtextractor.util.SourceKinds;

import java.util.NoSuchElementException;

/**
 * 解析器选择点（R6.3 / §2.2）—— <b>纯函数，零 IO，可无头单测</b>。
 *
 * <p><b>为什么单独一个类、而不是塞进 {@code MainController} 或某个 batch processor</b>：
 * 选择逻辑是本步唯一的"分支判断"，它必须能被 {@code gui} 与 {@code processor} 两条链
 * <b>共用同一份实现</b>。放进 GUI 会让无头处理器依赖视图层（违反
 * {@code applicationLayerDoesNotDependOnViewOrShell} 的同类精神），复制到两处则会分叉。</p>
 *
 * <p>本类零第三方依赖、零文件系统访问：只看文件名的扩展名，因此可以在无头 JVM 里直接断言
 * "哪个扩展名选到哪个实现"。</p>
 */
public final class SourceParsers {

    private SourceParsers() {
    }

    /**
     * 按扩展名选择解析器。
     *
     * <p>口径与 {@link SourceKinds} 完全一致：{@code .mht}/{@code .mhtml}（大小写不敏感）→
     * {@link MimeParser}；{@code .html}/{@code .htm}（大小写不敏感）→ {@link HtmlFolderParser}。</p>
     *
     * @param path 路径或文件名（只用最后一段判扩展名）
     * @throws NoSuchElementException 扩展名不在可导入集合内。这是<b>调用方违约</b>的编程错误
     *         （导入识别已按同一套 {@link SourceKinds} 过滤，正常流程到不了这里），
     *         故用<b>开发者诊断文案</b>（ASCII，不入 i18n bundle，R6.8）直接暴露，
     *         而不是静默降级成 MHT 解析（那会产出"HTML 被当 MIME 解析"的莫名失败）。
     */
    public static SourceParser forPath(String path) {
        String name = fileName(path);
        if (SourceKinds.isHtml(name)) {
            return new HtmlFolderParser();
        }
        if (SourceKinds.isMht(name)) {
            return new MimeParser();
        }
        throw new NoSuchElementException("no SourceParser for extension: " + name
                + " (only .mht/.mhtml/.html/.htm are supported)");
    }

    /** 是否是本类能选出的可解析来源（等价于 {@link SourceKinds#isImportable}，供调用方做前置判据） */
    public static boolean isSupported(String path) {
        return SourceKinds.isImportable(fileName(path));
    }

    private static String fileName(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }
}
