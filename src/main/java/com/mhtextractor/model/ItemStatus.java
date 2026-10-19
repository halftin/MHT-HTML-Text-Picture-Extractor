package com.mhtextractor.model;

/** Preview state shared by engine data and frontend projections; wire names remain stable. */
public enum ItemStatus {
    WAITING, READY, EMPTY, FAILED
}
