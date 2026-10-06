package com.mhtextractor.uiport;

import com.mhtextractor.model.AppConfig;
import java.util.List;

/**
 * 工作区查询（最小切片）：视图模型快照。
 */
public interface WorkspaceQueries {

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

    public AppConfig runtimeConfig();

    public List<OutputUnitVm> outputUnitPlan(boolean checkedOnly);

    public ParamSchemaVm paramSchema(boolean imageMode);
}
