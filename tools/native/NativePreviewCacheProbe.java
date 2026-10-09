import com.mhtextractor.cache.PreviewCacheKey;
import com.mhtextractor.cache.PreviewCacheMeta;
import com.mhtextractor.cache.PreviewCacheStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Native regression for Jackson's reflective array allocation, including empty images. */
public final class NativePreviewCacheProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[1].equals("empty") || args[1].equals("nonempty"))) {
            throw new IllegalArgumentException("Usage: NativePreviewCacheProbe <fixture-root> empty|nonempty");
        }
        Path fixture = Path.of(args[0]).toAbsolutePath().normalize();
        var key = PreviewCacheKey.compute(fixture.resolve("source.mht"), "p4-19",
                PreviewCacheMeta.FORMAT_VERSION);
        var store = new PreviewCacheStore(fixture.resolve("cache"));
        System.out.println("[probe] load=" + store.dirFor(key).resolve("meta.json"));
        var meta = store.load(key).orElseThrow(() -> new AssertionError("cache miss"));
        int expectedCount = args[1].equals("empty") ? 0 : 1;
        if (meta.images().size() != expectedCount || !meta.imageMode()) {
            throw new AssertionError("image metadata differs");
        }
        if (meta.warnings().size() != 1
                || !meta.warnings().getFirst().key().equals("extract.region.r2")
                || !meta.warnings().getFirst().args().equals(Map.of("start", "123", "end", "123"))) {
            throw new AssertionError("structured warning differs");
        }
        if (expectedCount > 0) {
            var image = meta.images().getFirst();
            if (!image.file().equals("images/0001.png") || !image.sourceId().equals("cid:p4-19")
                    || !image.originalName().equals("fixture.png") || !image.detectedExt().equals("png")
                    || !image.warnings().equals(meta.warnings())
                    || Files.size(store.imageFileFor(meta, 0)) == 0) {
                throw new AssertionError("image or its warning differs");
            }
        }
        if (!store.listKeys().equals(List.of(key))) throw new AssertionError("listKeys differs");
        System.out.println("[PASS] native load/listKeys images=" + expectedCount + " warnings=1");
        // Serialize the same shape back: the app's rebuild path writes the cache BEFORE any read.
        if (expectedCount == 0) {
            var writeRoot = Files.createTempDirectory("p4-19-native-write");
            var writeStore = new PreviewCacheStore(writeRoot);
            writeStore.write(key, meta, dir -> { });
            if (writeStore.load(key).isEmpty()) throw new AssertionError("write/load round-trip missed");
            System.out.println("[PASS] native write/load round-trip images=0");
        }
    }
}
