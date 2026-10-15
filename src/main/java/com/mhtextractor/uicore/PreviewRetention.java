package com.mhtextractor.uicore;

import java.util.LinkedHashMap;

/** Frontend-thread confined LRU of decoded previews, shared by one workspace's stable rows. */
public final class PreviewRetention {
    private static final int MAX_ITEMS = 20;
    private static final long MAX_PIXEL_BYTES = 64L * 1024 * 1024;
    private final LinkedHashMap<PreviewRow, Long> recent = new LinkedHashMap<>();
    private long pixelBytes;

    void retain(PreviewRow row) {
        forget(row);
        if (row.cachedThumbs == null) return;
        long bytes = 0;
        for (var thumb : row.cachedThumbs) {
            if (thumb != null && thumb.pixels() != null)
                bytes += (long) thumb.pixels().length * Integer.BYTES;
        }
        recent.put(row, bytes);
        pixelBytes += bytes;
        while (recent.size() > MAX_ITEMS || pixelBytes > MAX_PIXEL_BYTES) {
            recent.keySet().iterator().next().releaseImageData();
        }
    }

    void forget(PreviewRow row) {
        Long bytes = recent.remove(row);
        if (bytes != null) pixelBytes -= bytes;
    }
}
