package com.mhtextractor.uiport;

/** Target selection for the read-only parameter-state query. */
public enum TargetResolution {
    SELECTED_FIRST("selectedFirst"), EXPLICIT("explicit");

    private final String wireValue;
    TargetResolution(String wireValue) { this.wireValue = wireValue; }
    public String wireValue() { return wireValue; }

    public static TargetResolution fromWire(String value) {
        for (var resolution : values()) if (resolution.wireValue.equals(value)) return resolution;
        throw new IllegalArgumentException("Unknown targetResolution");
    }
}
