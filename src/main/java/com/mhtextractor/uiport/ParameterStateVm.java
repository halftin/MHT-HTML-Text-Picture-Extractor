package com.mhtextractor.uiport;

import com.mhtextractor.model.AppConfig;
import java.util.List;

/** Value snapshots of confirmed parameters and backend-resolved targets. */
public record ParameterStateVm(boolean textConfirmed, AppConfig textGlobal,
                               boolean imageConfirmed, AppConfig imageGlobal,
                               List<TargetId> targets, int count, List<TargetState> states,
                               boolean uniform, boolean hasEffective) {
    public ParameterStateVm {
        targets = List.copyOf(targets);
        states = List.copyOf(states);
        textGlobal = textGlobal == null ? null : textGlobal.copyExtractionParams();
        imageGlobal = imageGlobal == null ? null : imageGlobal.copyExtractionParams();
    }
    public record TargetState(TargetId target, boolean independent, boolean effective,
                              String source, AppConfig config) {
        public TargetState { config = config == null ? null : config.copyExtractionParams(); }
    }
}
