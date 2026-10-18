package com.mhtextractor.uiport;

import com.mhtextractor.model.AppConfig;
import java.util.List;
import java.util.Map;

/**
 * 工作区查询（最小切片）：视图模型快照。
 */
public interface WorkspaceQueries {
    /** Filesystem enumeration order, with no sorting or name normalization. */
    default List<String> listNamedConfigs() { throw new UnsupportedOperationException("Named configs are not wired"); }
    /** Loads parameter fields only; missing names and filesystem failures throw IOException. */
    default AppConfig loadNamedConfig(String name) throws java.io.IOException {
        throw new UnsupportedOperationException("Named configs are not wired");
    }

    default CacheStateVm cacheState() { throw new UnsupportedOperationException("Cache state is not wired"); }

    default ParameterStateVm parameterState(boolean imageMode, List<TargetId> fallbackTargets) {
        throw new UnsupportedOperationException("Parameter state is not wired");
    }
    default ParameterStateVm parameterState(boolean imageMode, List<TargetId> targets, TargetResolution resolution) {
        if (resolution == TargetResolution.SELECTED_FIRST) return parameterState(imageMode, targets);
        throw new UnsupportedOperationException("Explicit parameter state is not wired");
    }
    default PreStartStateVm preStartState(AppConfig runtime, boolean checkedOnly) {
        throw new UnsupportedOperationException("Pre-start state is not wired");
    }
    default List<PresentationVm> presentation(List<String> paths, AppConfig runtime) {
        throw new UnsupportedOperationException("Presentation is not wired");
    }
    default ExportSourceVm exportSource(String path) {
        throw new UnsupportedOperationException("Source export is not wired");
    }

    long activityGeneration();
    long previewGeneration();
    /** null 表示没有在途预览。 */
    String previewLoadingPath();

    /**
     * 工作区视图模型快照（一次性不可变；后续变更经 {@link PortEvents} 送达）。
     *
     * @return 快照；无任何条目时返回空集合而非 null
     */
    WorkspaceVm workspace();

    default boolean canUndo() { return workspace().canUndo(); }

    public AppConfig runtimeConfig();

    public List<OutputUnitVm> outputUnitPlan(boolean checkedOnly);

    public ParamSchemaVm paramSchema(boolean imageMode);

    /** 当前页的有序控件值；null 字符串转换为空串。 */
    default Map<String, Object> paramValues(AppConfig config, boolean imageMode) {
        throw new UnsupportedOperationException("Parameter values are not wired");
    }

    /** 收集当前页或两页值；缺失页使用默认控件值，禁用字段规则由后端执行。 */
    default AppConfig collectParams(Map<String, Object> values, boolean imageMode) {
        throw new UnsupportedOperationException("Parameter collection is not wired");
    }
}
