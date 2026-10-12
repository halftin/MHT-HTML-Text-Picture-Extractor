package com.mhtextractor.uiport;

import java.util.List;

/** Completed drop result; contains no mutable model identities. */
public record DropVm(boolean changed, List<String> affectedPaths) {
    public DropVm { affectedPaths = List.copyOf(affectedPaths); }
}
