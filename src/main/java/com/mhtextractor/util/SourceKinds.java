package com.mhtextractor.util;

import java.util.List;
import java.util.Locale;

/**
 * 可导入来源的扩展名口径（R6.1 / R6.6）—— <b>全项目唯一实现点</b>。
 *
 * <p>导入识别（{@code MhtImportPlanner} / {@code MhtExtractorApp.importPaths}）、解析器选择
 * （{@code core.SourceParsers}）与输出命名（{@link #stripKnownExtension}）三处必须用同一套
 * 扩展名集合，否则会出现"能导入但不认识扩展名（选不出解析器）""能提取但输出名带着扩展名"这类错位。
 * 本类的职责就是把这三处的口径收敛成一份可无头单测的纯函数。</p>
 *
 * <h2>🚩 大小写口径（两个集合<b>刻意不同</b>，详见各路 javadoc）</h2>
 * <ul>
 *   <li><b>MHT 组</b>（{@code .mht} / {@code .mhtml}）：本类出现之前，全项目的判定都是
 *       {@code name.toLowerCase().endsWith(".mht")}，即<b>本来就大小写不敏感</b>
 *       （基线 {@code MhtImportPlannerTest.upperCaseMhtmlExtensionIsMatched} 锁着这条行为）。
 *       本步<b>不动它</b> —— 用它自己的 {@code toLowerCase} 路径，与 HTML 组分开写。</li>
 *   <li><b>HTML 组</b>（{@code .html} / {@code .htm}）：本步新增，按拍板做<b>大小写不敏感</b>。</li>
 * </ul>
 *
 * <p>⚠️ 两个集合在源码里是<b>两行独立的判定</b>，<b>不是</b>一个 {@code toLowerCase} 把三/四个后缀
 * 一起放宽。这不是风格问题：一旦合并，日后想单独收紧任意一组时就会重新纠缠在一起，而
 * "MHT 组保持原样"是本步的硬要求（不得引入回归）。</p>
 */
public final class SourceKinds {

    /** MHT 组：既有格式，大小写不敏感（历史行为，本步不变更） */
    private static final String[] MHT_EXTS = {".mht", ".mhtml"};
    /** HTML 组：本步新增，大小写不敏感（用户拍板） */
    private static final String[] HTML_EXTS = {".html", ".htm"};

    private SourceKinds() {
    }

    /**
     * 是否是可导入来源（4 种扩展名，全部大小写不敏感 —— 这是<b>现状行为</b>，见类 javadoc）。
     *
     * <p>要求扩展名之前<b>至少有一个字符</b>：{@code .html} 这类"点开头、只有扩展名"的名字不算来源
     * （与 {@link #stripKnownExtension} 的 {@code dot <= 0} 判据同源，避免出现"能导入却剥不出基名"）。</p>
     *
     * @param fileName 文件名（不是路径）；null → false
     */
    public static boolean isImportable(String fileName) {
        if (fileName == null || fileName.length() < 2) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return false;
        }
        return hasAnySuffix(fileName, MHT_EXTS) || hasAnySuffix(fileName, HTML_EXTS);
    }

    /** MHT 组（.mht / .mhtml，大小写不敏感 —— 历史行为） */
    public static boolean isMht(String fileName) {
        return hasAnySuffix(fileName, MHT_EXTS);
    }

    /** HTML 组（.html / .htm，大小写不敏感 —— 本步新增） */
    public static boolean isHtml(String fileName) {
        return hasAnySuffix(fileName, HTML_EXTS);
    }

    /**
     * HTML 组的扩展名字面量（小写、含点），供"侧车同基名文件"判定
     * （{@code MhtImportPlanner.isSidecarDir} / {@code MhtExtractorApp.isInSidecarDir}）复用。
     *
     * <p>返回<b>不可变</b>副本。其余实现点一律用 {@link #isMht} / {@link #isHtml} / {@link #isImportable}
     * 等判定函数或 {@link #stripKnownExtension}，不要自行持有扩展名集合 —— 本类是来源扩展名的唯一入口。</p>
     */
    public static List<String> htmlExtensions() {
        return List.of(HTML_EXTS);
    }

    private static boolean hasAnySuffix(String fileName, String[] exts) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : exts) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 剥掉<b>已知来源扩展名</b>（R6.6：模板变量 {@code {filename}} 与预览区"输出文件名"共用）。
     *
     * <p>只剥这 4 个已知扩展名，<b>不做</b>"去掉最后一个点之后的一切"：后者会把
     * {@code output.v2} 变成 {@code output}，而该文件原本根本不可导入，输出名不应当因本步而改变
     * （既有行为零变更原则）。{@code a.b.html} → {@code a.b}、无扩展名 → 原样、点开头的
     * {@code .html} → 原样（保守：不把隐藏文件判成"只有扩展名"）。</p>
     */
    public static String stripKnownExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || !isImportable(fileName)) {
            return fileName;
        }
        return fileName.substring(0, dot);
    }
}
