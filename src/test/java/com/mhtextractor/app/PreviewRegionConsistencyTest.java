package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.ExtractOutcome;
import com.mhtextractor.processor.BatchProcessor;
import com.mhtextractor.processor.ImageBatchProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PreviewRegionConsistencyTest {
    @TempDir Path tmp;

    @Test void previewAndExtractionMatchWithAndWithoutCache() throws Exception {
        Path file = Files.copy(Path.of("src/test/resources/fixtures/region.mht"), tmp.resolve("region.mht"));
        String[][] markers = {{"<!--START-->", "<!--END-->"}, {"bad", "bad"},
                {"<!--START-->", "bad"}, {"bad", "<!--END-->"}, {"<!--START-->", "<p>"}};
        int[] counts = {2, 4, 3, 3, 4};
        for (int i = 0; i < markers.length; i++) {
            var params = new AppConfig();
            params.setImageModeEnabled(true);
            params.setEnableBatchRenaming(true);
            params.setImageRegionStartMarker(markers[i][0]);
            params.setImageRegionEndMarker(markers[i][1]);
            var session = PreviewSession.create(tmp.resolve("cache" + i));
            var service = new PreviewService(session.lifecycle());
            var prepared = service.prepare(file, params);
            if (i == 4) {
                assertThrows(PreviewService.RegionConfirmationRequired.class,
                        () -> service.ensureCachedLocked(prepared, () -> false, false));
                assertFalse(session.lifecycle().hasCachedEntries(), "unanswered/declined preview must not cache empty result");
            }
            var preview = service.ensureCachedLocked(prepared, () -> false, true);
            assertEquals(counts[i], preview.images().size());
            if (i == 4) assertThrows(PreviewService.RegionConfirmationRequired.class,
                    () -> service.ensureCachedLocked(prepared, () -> false, false));
            List<String> previewHashes = new ArrayList<>();
            for (var image : preview.images()) previewHashes.add(hash(Files.readAllBytes(preview.imagesDir().resolve(image.file()))));
            for (boolean cached : List.of(false, true)) {
                Path out = tmp.resolve("out" + i + cached);
                var processor = cached ? new ImageBatchProcessor(params, session.lifecycle()) : new ImageBatchProcessor(params);
                var stats = processor.process(BatchProcessor.buildMhtFileInfoList(List.of(file.toString())),
                        out.toString(), "images", null);
                assertEquals(1, stats.getSuccessCount(), stats.toString());
                List<String> hashes = new ArrayList<>();
                try (var paths = Files.walk(out)) {
                    for (var p : paths.filter(Files::isRegularFile).sorted().toList()) hashes.add(hash(Files.readAllBytes(p)));
                }
                assertEquals(previewHashes, hashes, "count, bytes AND order must match");
                assertEquals(preview.meta().warnings(), stats.getFileResults().getFirst().warnings());
                if (i != 0) {
                    assertEquals(ExtractOutcome.SUCCESS_WARNED, stats.getFileResults().getFirst().outcome());
                    assertTrue(WorkspaceExtractionService.statsFrom(stats).summaryLine().contains("region.mht"));
                    assertFalse(stats.getWarnDetails().isEmpty());
                }
                System.out.println("CONSISTENCY case=" + i + " cached=" + cached + " sha256=" + hashes
                        + " outcome=" + stats.getFileResults().getFirst().outcome());
            }
        }
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
