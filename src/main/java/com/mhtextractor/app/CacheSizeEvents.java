package com.mhtextractor.app;

import com.mhtextractor.engine.PreviewSession;

import com.mhtextractor.uiport.PortEvent;
import java.util.Map;

/** Shared transport-neutral payload builder; display rounding belongs to the frontend. */
public final class CacheSizeEvents {
    private CacheSizeEvents() { }

    public static PortEvent.CacheSizeAlert alert(PreviewSession session, int step, long revision) {
        return new PortEvent.CacheSizeAlert(step, session.lastObservedTotalBytes(),
                session.sizeAlertStepBytes(), session.store().root().toString(), "cache.sizeAlert",
                Map.of("dir", session.store().root().toString()), "WARN", revision);
    }
}
