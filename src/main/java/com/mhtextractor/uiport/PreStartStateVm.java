package com.mhtextractor.uiport;

import java.util.List;
import java.util.Map;

public record PreStartStateVm(Denial denial, boolean textConfirmed, boolean imageConfirmed,
                              List<String> defaultNamedGroups, boolean subsetMergedName) {
    public PreStartStateVm { defaultNamedGroups = List.copyOf(defaultNamedGroups); }
    public record Denial(String key, String level, Map<String, String> namedParams) {
        public Denial { namedParams = Map.copyOf(namedParams); }
    }
}
