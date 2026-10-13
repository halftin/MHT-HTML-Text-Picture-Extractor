package com.mhtextractor.uiport;

import com.mhtextractor.model.AppConfig;
import java.util.List;

/**
 * 工作区查询（最小切片）：视图模型快照。
 */
public interface WorkspaceQueries {
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
}
