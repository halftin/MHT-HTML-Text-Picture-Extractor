package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.OutputUnitPlanner;

import java.util.List;
import java.util.function.Consumer;

/**
 * 启动前顺序门的上下文（P1-5）：门规则的<b>查询与展示回调</b>接缝。
 *
 * <p>门规则（{@link PreStartGates}）在 app 层，只产数据与判定；卡片文案渲染仍留在 GUI 层 ——
 * 本接口的展示回调由宿主实现，把 {@code names} 等数据交给 GUI 侧渲染
 * （决策 13：端口/后端只传键+参数，不引 I18n）。<b>零 JavaFX、零 I18n</b>。</p>
 */
public interface PreStartGateContext {

    /** 数据序只读投影（未命名组门据此收集顶层组）。 */
    List<Object> dataOrder();

    /** 输出单元规划的字段视图。 */
    default OutputUnitPlanner.ItemView<PreviewItem> itemView() {
        return ExtractionPlanService.itemView(extractCheckedOnly());
    }

    /** 当前是否图片模式。 */
    boolean imageMode();

    /** 文本模式已确认参数缓存（可为 null = 未确认）。 */
    AppConfig textParamsCache();

    /** 图片模式已确认参数缓存（可为 null = 未确认）。 */
    AppConfig imageParamsCache();

    /** 输出目录框文本。 */
    String outputFolder();

    /** 是否只提取列表中被勾选的项目。 */
    boolean extractCheckedOnly();

    /** 本次会参与提取的可见项数（开关打开时 = 勾选且未移除的数量）。 */
    int visibleCheckedCount();

    /** 可见文件数。 */
    long visibleCount();

    /** 是否存在未提交的参数草稿（判定唯一来源 = GUI 侧 {@code ParamSidePanel.isDirty(Node)} 的同一接缝）。 */
    boolean draftDirty();

    /** 未命名组确认卡：文案由实现方渲染（组名按 locale 拼进确认卡），确定/取消以 {@code proceed} 交付。 */
    void confirmUnnamedGroups(List<String> names, Consumer<Boolean> proceed);

    /** 合并名可能变化的提示卡：文案由实现方渲染，确定/取消以 {@code proceed} 交付。 */
    void confirmSubsetMergedName(Consumer<Boolean> proceed);

    /** 草稿阻断信息卡（仅展示）；调用方随后自行 {@code proceed(false)}。 */
    void draftBlocksStart();
}
