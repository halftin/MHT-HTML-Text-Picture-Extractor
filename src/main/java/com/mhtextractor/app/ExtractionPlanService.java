package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.processor.PerFileOverrides;
import com.mhtextractor.util.OutputUnitPlanner;
import com.mhtextractor.uiport.OutputUnitVm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提取运行时配置与输出单元计划（P3-3b-3，矩阵缺口 7/8）：把
 * {@code gui.ExtractionTaskCoordinator.buildRuntimeConfig} 与 {@code startExtractionTask} 里的
 * 两段<b>纯计算</b>原样搬到 app 层（只搬不改：配置字段写入顺序、单元参数补充顺序与搬迁前逐字一致）。
 *
 * <p>零 JavaFX、零 I18n；不产任何文案（{@code log.maincontroller.33} 的日志键与实参构造留在 gui 侧）。</p>
 */
public final class ExtractionPlanService {

    /** 当前已确认参数和会话状态；由宿主提供实时读取，不缓存控件值。 */
    public interface Context {
        List<Object> dataOrder();
        boolean imageMode();
        AppConfig textParamsCache();
        AppConfig imageParamsCache();
        String inputFolder();
        String outputFolder();
        boolean openFolderAfterComplete();
    }

    private final Context context;
    private String sessionOutputFolder;

    public ExtractionPlanService(Context context) {
        this.context = java.util.Objects.requireNonNull(context);
    }

    /** 正式运行前绑定已建立的会话目录；查询本身不创建目录。 */
    public void setSessionOutputFolder(String folder) {
        sessionOutputFolder = folder;
    }

    public AppConfig runtimeConfig() {
        return runtimeConfig(context.imageMode(), context.imageParamsCache(), context.textParamsCache(),
                context.inputFolder(), context.outputFolder(), context.openFolderAfterComplete());
    }

    public List<OutputUnitVm> outputUnitPlan(boolean checkedOnly) {
        return plan(context.dataOrder(), context.imageMode(), context.textParamsCache(), context.imageParamsCache(),
                sessionOutputFolder == null ? context.outputFolder() : sessionOutputFolder,
                context.inputFolder(), context.outputFolder(), context.openFolderAfterComplete(), checkedOnly)
                .stream().map(u -> new OutputUnitVm(u.getOutputName(), u.isGroup(), u.getParams(),
                        u.getPaths(), u.getOutputPath(), u.getOutputName() == null ? "core.ungrouped" : null)).toList();
    }

    /**
     * 输出单元规划字段视图的 gui 侧返回类型别名（协变返回）：让 gui 层对视图类型的引用
     * 落在 app 层（gui 直连业务包计数只减不增，不再直连 {@code util} 的视图接口）。
     */
    public interface ItemView extends OutputUnitPlanner.ItemView<PreviewItem> {
    }

    /** 原 {@code MainController.ITEM_VIEW}（阶段 8）：路径 / 来源目录 / 移除标记 / 条目级参数；checked 用默认恒 true。 */
    private static final ItemView ITEM_VIEW = new ItemView() {
        @Override
        public String path(PreviewItem item) {
            return item.path;
        }

        @Override
        public String sourceFolder(PreviewItem item) {
            return item.sourceFolder;
        }

        @Override
        public boolean removed(PreviewItem item) {
            return item.removed;
        }

        @Override
        public AppConfig textItemParams(PreviewItem item) {
            return item.textItemParams;
        }

        @Override
        public AppConfig imageItemParams(PreviewItem item) {
            return item.imageItemParams;
        }
    };

    /**
     * 字段视图（原 {@code MainController} 的 Host 实现）：功能一（只提取选中）开关打开时把
     * "参与"判定交给 checked（条目勾选）；关闭时用默认视图（checked 恒 true）。
     */
    public static ItemView itemView(boolean checkedOnly) {
        if (!checkedOnly) {
            return ITEM_VIEW;
        }
        OutputUnitPlanner.ItemView<PreviewItem> decorated = OutputUnitPlanner.withChecked(ITEM_VIEW, it -> it.selected);
        return new ItemView() {
            @Override public String path(PreviewItem item) { return decorated.path(item); }
            @Override public String sourceFolder(PreviewItem item) { return decorated.sourceFolder(item); }
            @Override public boolean removed(PreviewItem item) { return decorated.removed(item); }
            @Override public boolean checked(PreviewItem item) { return decorated.checked(item); }
            @Override public AppConfig textItemParams(PreviewItem item) { return decorated.textItemParams(item); }
            @Override public AppConfig imageItemParams(PreviewItem item) { return decorated.imageItemParams(item); }
        };
    }

    /**
     * 以已确认参数（未确认时用默认参数）+ 主窗口 GUI 状态构建运行时提取配置
     * （原 {@code ExtractionTaskCoordinator.buildRuntimeConfig}，逐条照搬）：
     * 取当前模式参数缓存，null 则新建默认实例，随后按序补四个运行时字段。
     *
     * <p><b>注意</b>：参数缓存非 null 时就地修改该实例（搬迁前语义）。</p>
     */
    public static AppConfig runtimeConfig(boolean imageMode, AppConfig imageParams, AppConfig textParams,
                                          String inputFolder, String outputFolder, boolean openFolderAfterComplete) {
        AppConfig config = imageMode ? imageParams : textParams;
        if (config == null) {
            config = new AppConfig();
        }
        config.setInputFolder(inputFolder);
        config.setOutputFolder(outputFolder);
        config.setImageModeEnabled(imageMode);
        config.setOpenFolderAfterComplete(openFolderAfterComplete);
        return config;
    }

    /**
     * 构建输出单元计划（原 {@code ExtractionTaskCoordinator.startExtractionTask} 内的计划组装，
     * 逐条照搬）：先按数据序 + 字段视图（功能一按需过滤勾选）规划单元，再对每个单元按序补
     * 运行时字段（{@code setInputFolder → setOutputFolder → setImageModeEnabled →
     * setOpenFolderAfterComplete}）。纯计算：不产文案、不触碰控件状态。
     */
    public static List<OutputUnitPlanner.OutputUnit> plan(List<Object> dataOrder, boolean imageMode,
                                                          AppConfig textParams, AppConfig imageParams,
                                                          String sessionOutputFolder, String inputFolder,
                                                          String outputFolder, boolean openFolderAfterComplete,
                                                          boolean checkedOnly) {
        List<OutputUnitPlanner.OutputUnit> units = OutputUnitPlanner.plan(
                dataOrder, itemView(checkedOnly), imageMode, textParams, imageParams, sessionOutputFolder);
        for (OutputUnitPlanner.OutputUnit unit : units) {
            AppConfig params = unit.getParams();
            params.setInputFolder(inputFolder);
            params.setOutputFolder(outputFolder);
            params.setImageModeEnabled(imageMode);
            params.setOpenFolderAfterComplete(openFolderAfterComplete);
        }
        return units;
    }

    /**
     * 构建「每文件覆盖」映射（原 {@code ExtractionTaskCoordinator.overrides}，逐条照搬）：
     * 按传入顺序放入 {@link LinkedHashMap}，键为 {@code item.path}，值为
     * {@code chapterEnabled} / {@code titleOverride} 两字段的覆盖。
     */
    public static Map<String, PerFileOverrides> overrides(List<PreviewItem> items) {
        Map<String, PerFileOverrides> map = new LinkedHashMap<>();
        for (PreviewItem item : items) {
            // 标题覆盖 = 用户在查看器「提取标题」文本框里的编辑值（null/空白 = 用提取值）
            map.put(item.path, new PerFileOverrides(item.chapterEnabled, item.titleOverride));
        }
        return map;
    }
}
