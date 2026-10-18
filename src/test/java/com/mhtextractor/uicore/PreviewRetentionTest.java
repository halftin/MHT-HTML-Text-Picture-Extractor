package com.mhtextractor.uicore;

import com.mhtextractor.model.ThumbnailData;
import com.mhtextractor.uiport.FileRef;
import com.mhtextractor.uiport.ItemVm;
import com.mhtextractor.uiport.PresentationVm;
import com.mhtextractor.uiport.UpdateKind;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewRetentionTest {
    private final PreviewRetention retention = new PreviewRetention();

    private ItemVm vm(String name, FileRef manifest) {
        return new ItemVm(name, name, false, false, null, UpdateKind.NONE, null,
                null, "READY", null, null, null, "title", null, null, null, null, manifest);
    }

    private PreviewRow row(String name) {
        return new PreviewRow(vm(name, null), ignored -> {}, retention);
    }

    private void pixels(PreviewRow row, int count) {
        // Each entry represents a distinct 4 MiB decoded thumbnail allocation.
        row.cachedThumbs = new ArrayList<>();
        for (int i = 0; i < count; i++)
            row.cachedThumbs.add(new ThumbnailData("image", 1024, 1024, new int[1024 * 1024]));
        row.retainImageData();
    }

    @Test void retainsTwentyAndEvictsLeastRecentlyPreviewedOnTwentyFirst() {
        var rows = new ArrayList<PreviewRow>();
        for (int i = 0; i < 20; i++) {
            var row = row("row" + i); row.cachedThumbs = List.of(); row.retainImageData(); rows.add(row);
        }
        rows.forEach(row -> assertNotNull(row.cachedThumbs));
        rows.getFirst().retainImageData(); // Revisit A: row1 is now the oldest.
        var next = row("next"); next.cachedThumbs = List.of(); next.retainImageData();
        assertNull(rows.get(1).cachedThumbs);
        assertNotNull(rows.getFirst().cachedThumbs);
        assertNotNull(next.cachedThumbs);
    }

    @Test void allowsExactly64MiBAndEvictsWhenPixelsGrow() {
        var oldest = row("oldest"); pixels(oldest, 8);
        var latest = row("latest"); pixels(latest, 8);
        assertNotNull(oldest.cachedThumbs); assertNotNull(latest.cachedThumbs);
        latest.cachedThumbs.add(new ThumbnailData("extra", 1, 1, new int[1]));
        latest.retainImageData();
        assertNull(oldest.cachedThumbs); assertNotNull(latest.cachedThumbs);
    }

    @Test void evictionPreservesTextTitleMetaAndStatus() {
        var oldest = row("oldest"); oldest.cachedText = "text";
        oldest.cachedMeta = new PresentationVm("oldest", "oldest", false, true, "READY", "", "", null, List.of(),
                "oldest", null, null);
        var status = oldest.status;
        var meta = oldest.cachedMeta;
        pixels(oldest, 9); pixels(row("next"), 8);
        assertNull(oldest.cachedThumbs); assertNull(oldest.cachedImages);
        assertEquals("text", oldest.cachedText); assertEquals("title", oldest.cachedTitle);
        assertSame(meta, oldest.cachedMeta); assertSame(status, oldest.status);
    }

    @Test void releaseAndResetImmediatelyFreeTheirBudget() {
        var released = row("released"); pixels(released, 16); released.releaseImageData();
        var reset = row("reset"); pixels(reset, 16);
        reset.cachedText = "text"; reset.resetPreviewState();
        assertNull(reset.cachedText); assertNull(reset.cachedMeta);
        var next = row("next"); pixels(next, 16);
        assertNotNull(next.cachedThumbs);
        assertNull(released.cachedThumbs); assertNull(reset.cachedThumbs);
    }

    @Test void manifestDisappearanceFreesBudgetWithoutResurrectingPreview() {
        var manifest = new FileRef(Path.of(System.getProperty("java.io.tmpdir"), "manifest").toString(), 0, "application/json");
        var row = new PreviewRow(vm("manifest-row", manifest), ignored -> {}, retention);
        pixels(row, 16); row.update(vm(row.name, null)); row.retainImageData();
        assertNull(row.cachedThumbs); assertNull(row.cachedImages); assertNull(row.cachedMeta);
        var next = row("next"); pixels(next, 16); assertNotNull(next.cachedThumbs);
    }

    @Test void ignoresMissingPixelsAndUsesActualArrayLength() {
        var placeholders = row("placeholders");
        placeholders.cachedThumbs = new ArrayList<>(Collections.nCopies(100, null));
        placeholders.cachedThumbs.add(new ThumbnailData("failed", 0, 0, null));
        placeholders.cachedThumbs.add(new ThumbnailData("small", Integer.MAX_VALUE, Integer.MAX_VALUE, new int[1]));
        placeholders.retainImageData();
        var next = row("next"); pixels(next, 15);
        assertNotNull(placeholders.cachedThumbs); assertNotNull(next.cachedThumbs);
    }

    @Test void oversizedSinglePreviewIsReleasedAndCanBeRetainedAgain() {
        var huge = row("huge"); pixels(huge, 17);
        assertNull(huge.cachedThumbs);
        pixels(huge, 1); assertNotNull(huge.cachedThumbs);
    }

    @Test void workspacesHaveIndependentBudgets() {
        var first = row("first"); pixels(first, 16);
        var other = new PreviewRow(vm("other", null), ignored -> {}, new PreviewRetention());
        pixels(other, 16);
        assertNotNull(first.cachedThumbs); assertNotNull(other.cachedThumbs);
    }
}
