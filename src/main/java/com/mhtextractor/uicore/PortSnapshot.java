package com.mhtextractor.uicore;

import com.mhtextractor.uiport.*;
import java.util.List;
import java.util.Map;

/** One frontend read-only projection; contains no workspace model or undo records. */
public record PortSnapshot(WorkspaceVm workspace, ParameterStateVm text, ParameterStateVm image,
                           CacheStateVm cache, ParamSchemaVm textSchema, ParamSchemaVm imageSchema,
                           List<PresentationVm> presentation, Map<String,String> textBodies,
                           List<OutputUnitVm> allUnits, List<OutputUnitVm> checkedUnits) {
    public PortSnapshot {
        presentation=List.copyOf(presentation);textBodies=Map.copyOf(textBodies);
        allUnits=List.copyOf(allUnits);checkedUnits=List.copyOf(checkedUnits);
    }
    public ParameterStateVm parameters(boolean imageMode) { return imageMode ? image : text; }
}
