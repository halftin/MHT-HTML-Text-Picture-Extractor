package com.mhtextractor.uicore;

import com.mhtextractor.util.ExtractOutcome;

import java.util.List;

/**
 * 「提取结果 → 样式 / 颜色」的<b>唯一知识点</b>（P6-A 余下候选）。
 *
 * <h2>搬迁前这两份知识住在哪、重复在哪</h2>
 * <ul>
 *   <li><b>进度条侧</b>（{@code MainController}）：4 个活动样式类常量声明在 :131-134，
 *       又在 {@code setProgressBarActivity} 的 {@code removeAll(...)} 里<b>把同样的 4 个名字再列一遍</b>
 *       —— 同一份"哪些是活动样式类"的知识写了两处，加第 5 个颜色时漏掉一处就会静默残留旧色。</li>
 *   <li><b>列表侧</b>（{@code PreviewListCell}）：{@code extractOutcomePaint} 与
 *       {@code extractOutcomeSelectedPaint} 是<b>两个并行的 switch</b>（同一套三态分类各写一遍），
 *       而 {@code whiteText} 判定又把 {@code FAILED / SKIPPED / SUCCESS_WARNED} <b>第三次</b>列出来。
 *       三处分类必须永远同步 —— 这正是"加一个枚举值就得改三个地方"的形态。</li>
 * </ul>
 *
 * <h2>逐项对账：两侧映射<b>语义一致</b>的部分（已合并）</h2>
 * <table border="1">
 *   <caption>tone → 两种介质的表现</caption>
 *   <tr><th>tone</th><th>{@code ExtractOutcome}</th><th>进度条样式类（颜色见 {@code custom.css}）</th>
 *       <th>列表行画值</th></tr>
 *   <tr><td>SUCCESS</td><td>{@code SUCCESS}</td><td>{@code progress-extract-success} → {@code #4CAF50}</td>
 *       <td>{@code #4CAF50}</td></tr>
 *   <tr><td>FAILED</td><td>{@code FAILED}, {@code SKIPPED}</td>
 *       <td>{@code progress-extract-failed} → {@code #E81123}</td><td>{@code #E81123}</td></tr>
 *   <tr><td>WARNED</td><td>{@code SUCCESS_WARNED}</td>
 *       <td>{@code progress-extract-warned} → {@code #fff9ae}</td>
 *       <td>{@code linear-gradient(#E81123 0-33%, #2E7D32 33-100%)}</td></tr>
 * </table>
 * <p>对账结论：<b>SUCCESS 与 FAILED 两侧同色</b>（{@code custom.css} 的注释本来就写着
 * "提取成功=绿（同开始提取按钮）"、"有失败/跳过=Windows 错误红 #E81123"）—— 这两行是真正的一致，
 * 故分类收进 {@link #toneOf}，颜色各自留在自己的介质里（进度条的颜色是 CSS，不是 Java 字面量）。</p>
 *
 * <h2>🚩 有意<b>不</b>合并的差异（后来者不要"顺手统一"）</h2>
 * <p><b>WARNED 在两种介质上必然不同色</b>，这是设计而不是疏漏：</p>
 * <ol>
 *   <li>进度条是<b>批次级</b>三态（{@code ExtractionTaskCoordinator} 按
 *       {@code fail+skip > 0 → warn > 0 → 成功} 折叠），<b>只有一根条，无法分割</b>，
 *       故用 Windows 警告黄 {@code #fff9ae} 表达"这一批有警告"。</li>
 *   <li>列表是<b>条目级</b>：{@code SUCCESS_WARNED} 用"左 1/3 红右 2/3 绿"同时表达
 *       "成功了、但有警告"（拍板 2026-09-21/25）—— 一根渐变能同时承载两个事实，
 *       进度条做不到这件事。</li>
 *   <li>另外 {@code #fff9ae} 恰好也是列表里<b>解析失败</b>行的黄（{@code PreviewListCell.FAILED_PAINT}），
 *       但那是另一个维度（<b>解析状态</b> vs <b>提取结果</b>），两处黄不同源。</li>
 * </ol>
 * <p>这条差异由 {@code ExtractOutcomeStylesTest} 机械钉住：合并 WARNED 会让该断言失败。</p>
 *
 * <h2>类名不许改</h2>
 * <p>{@link #PROGRESS_*} 的字符串是 {@code custom.css} 的<b>选择器名</b>
 * （{@code .progress-bar.progress-extract-success > .bar}），改名会<b>静默丢样式</b>
 * （编译通过、无异常、颜色退回默认）。{@code ExtractOutcomeStylesTest} 会去 {@code custom.css}
 * 里逐个核对这 4 个名字仍然存在。</p>
 *
 * <h2>为什么不放在 {@code gui.controller}</h2>
 * <p>这两个介质的使用方分居 {@code gui.controller}（控制器）与 {@code gui.controller}
 * （单元格），而知识本身<b>零 JavaFX</b>（全是字符串与枚举）。放进 {@code gui.component}
 * （与 {@code ParamTargetText} 同处的"零 JavaFX 表现层知识"位置）意味着它可以被普通 JUnit
 * 直接断言，也让"控制器仍持有多少表现层常量"这件事继续缩小。</p>
 */
public final class ExtractOutcomeStyles {

    /**
     * 提取结果的<b>色调三态</b>：两种介质共用的分类（唯一实现点是 {@link #toneOf}）。
     *
     * <p>{@code FAILED} 同时覆盖 {@code SKIPPED}（搬迁前的 switch 就是 {@code case FAILED, SKIPPED})。</p>
     */
    public enum Tone {
        /** 全部成功 */
        SUCCESS,
        /** 成功但有警告 */
        WARNED,
        /** 失败或跳过 */
        FAILED
    }

    // ==================== 进度条侧：活动样式类（名字即 custom.css 选择器，不许改） ====================

    /** 预解析中 = 浅蓝（与"独立参数条目背景"同色，见 custom.css 注释） */
    public static final String PROGRESS_PREPARSE = "progress-preparse";
    /** 提取成功 = 绿（与"开始提取按钮"同色） */
    public static final String PROGRESS_EXTRACT_SUCCESS = "progress-extract-success";
    /** 有警告 = Windows 警告黄 */
    public static final String PROGRESS_EXTRACT_WARNED = "progress-extract-warned";
    /** 有失败/跳过 = Windows 错误红 */
    public static final String PROGRESS_EXTRACT_FAILED = "progress-extract-failed";

    /**
     * 进度条<b>全部</b>活动样式类（{@code setProgressBarActivity} 的清场清单）。
     *
     * <p>顺序与搬迁前 {@code removeAll(PREPARSE, SUCCESS, WARNED, FAILED)} 的实参顺序一致
     * （对 {@code removeAll} 无影响，但保持可逐字比对）。</p>
     */
    private static final List<String> PROGRESS_ACTIVITY_STYLES = List.of(
            PROGRESS_PREPARSE, PROGRESS_EXTRACT_SUCCESS, PROGRESS_EXTRACT_WARNED, PROGRESS_EXTRACT_FAILED);

    private ExtractOutcomeStyles() {
    }

    /** 进度条活动样式类清单（只读）；{@code null} 之外的活动类一律不属于此集合。 */
    public static List<String> progressActivityStyles() {
        return PROGRESS_ACTIVITY_STYLES;
    }

    // ==================== 列表侧：行背景画值（内联样式；选中态是"基础色混 40% 浅灰"的显式变体） ====================

    /** 成功 = 标准绿（与进度条成功色同值） */
    private static final String SUCCESS_PAINT = "#4CAF50";
    /** 成功选中态 = 基础绿混 40% 浅灰 */
    private static final String SUCCESS_SELECTED_PAINT = "#7AB57C";
    /** 成功+警告 = 左 1/3 错误红、右 2/3 深绿（分割行承载两个事实） */
    private static final String WARNED_PAINT =
            "linear-gradient(to right, #E81123 0%, #E81123 33%, #2E7D32 33%, #2E7D32 100%)";
    /** 成功+警告选中态 = 上述分割行的混合灰变体 */
    private static final String WARNED_SELECTED_PAINT =
            "linear-gradient(to right, #D75661 0%, #D75661 33%, #68976A 33%, #68976A 100%)";
    /** 失败/跳过 = Windows 错误红（与进度条失败色同值；白字） */
    private static final String FAILED_PAINT = "#E81123";
    /** 失败/跳过选中态 = 基础红混 40% 浅灰 */
    private static final String FAILED_SELECTED_PAINT = "#D75661";

    /**
     * 提取结果 → 色调（<b>唯一</b>分类实现点）。
     *
     * @return {@code NONE}（无结果）→ {@code null}，表示"本维度不适用"，调用方沿用其它样式来源
     */
    public static Tone toneOf(ExtractOutcome outcome) {
        if (outcome == null) {
            return null;
        }
        return switch (outcome) {
            case SUCCESS -> Tone.SUCCESS;
            case SUCCESS_WARNED -> Tone.WARNED;
            case FAILED, SKIPPED -> Tone.FAILED;
            case NONE -> null;
        };
    }

    /**
     * 列表行背景画值（未选中）。
     *
     * @return 画值字符串；{@code NONE}（或 {@code null}）→ {@code null}（调用方本就不会走这条路径）
     */
    public static String listPaint(ExtractOutcome outcome) {
        Tone tone = toneOf(outcome);
        if (tone == null) {
            return null;
        }
        return switch (tone) {
            case SUCCESS -> SUCCESS_PAINT;
            case WARNED -> WARNED_PAINT;
            case FAILED -> FAILED_PAINT;
        };
    }

    /** 列表行背景画值（选中态）：基础色混合 40% 浅灰的显式变体（拍板 2026-09-22）。 */
    public static String listPaintSelected(ExtractOutcome outcome) {
        Tone tone = toneOf(outcome);
        if (tone == null) {
            return null;
        }
        return switch (tone) {
            case SUCCESS -> SUCCESS_SELECTED_PAINT;
            case WARNED -> WARNED_SELECTED_PAINT;
            case FAILED -> FAILED_SELECTED_PAINT;
        };
    }

    /**
     * 该结果的行背景是否为<b>深色底</b>（⇒ 行内文字须显式改白）。
     *
     * <p>搬迁前这段判定在 {@code PreviewListCell} 里把 {@code FAILED / SKIPPED / SUCCESS_WARNED}
     * 逐个列出（同一分类的第三份拷贝）；现改为"问色调"，回答与搬迁前逐值相同
     * （{@code NONE}/{@code null} → {@code false}）。</p>
     */
    public static boolean usesDarkBackdrop(ExtractOutcome outcome) {
        Tone tone = toneOf(outcome);
        return tone == Tone.WARNED || tone == Tone.FAILED;
    }
}
