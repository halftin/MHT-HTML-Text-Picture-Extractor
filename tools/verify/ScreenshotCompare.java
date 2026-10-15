import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Standalone JDK 17+ tool; no product classes or external image plugins. */
public final class ScreenshotCompare {
    public static void main(String[] args) {
        ImageIO.setUseCache(false);
        try {
            System.exit(run(args));
        } catch (Exception error) {
            System.out.println("ERROR: " + error.getMessage());
            System.out.println("RESULT FAIL exit=1");
            System.exit(1);
        }
    }

    private static int run(String[] args) throws IOException {
        Map<String, String> options = new HashMap<>();
        Set<String> allowed = Set.of("--baseline", "--current", "--threshold", "--diff-dir");
        for (int i = 0; i < args.length; i += 2) {
            if (!allowed.contains(args[i]) || i + 1 == args.length
                    || args[i + 1].isBlank() || options.put(args[i], args[i + 1]) != null) {
                throw new IllegalArgumentException("Expected unique --baseline DIR --current DIR"
                        + " [--threshold 0..1] [--diff-dir DIR]");
            }
        }
        if (!options.containsKey("--baseline") || !options.containsKey("--current")) {
            throw new IllegalArgumentException("Both --baseline and --current are required");
        }
        BigDecimal threshold;
        try {
            threshold = new BigDecimal(options.getOrDefault("--threshold", "0"));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Threshold must be a finite decimal in 0..1");
        }
        if (threshold.signum() < 0 || threshold.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Threshold must be in 0..1");
        }
        Path baseline = directory(options.get("--baseline"));
        Path current = directory(options.get("--current"));
        Path diffDir = options.containsKey("--diff-dir")
                ? canonicalOutput(Path.of(options.get("--diff-dir"))) : null;
        if (diffDir != null && (overlaps(diffDir, baseline) || overlaps(diffDir, current))) {
            throw new IllegalArgumentException("DiffDir must be separate from both input trees");
        }
        Map<String, Path> before = images(baseline);
        Map<String, Path> after = images(current);
        Set<String> names = new TreeSet<>(before.keySet());
        names.addAll(after.keySet());
        if (names.isEmpty()) throw new IllegalArgumentException("No PNG files in either input tree");
        System.out.println("COMPARE threshold=" + threshold.toPlainString()
                + " pixels=exact-ARGB recursive=true scaling=false ignoreRegions=none");
        int passed = 0;
        int failed = 0;
        int compared = 0;
        long totalPixels = 0;
        long differentPixels = 0;
        for (String name : names) {
            if (!before.containsKey(name) || !after.containsKey(name)) {
                System.out.println("FAIL file=" + name + " same=N/A different=N/A ratio=N/A missing="
                        + (!before.containsKey(name) ? "baseline" : "current"));
                failed++;
                continue;
            }
            try {
                BufferedImage a = readPng(before.get(name));
                BufferedImage b = readPng(after.get(name));
                if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
                    System.out.println("FAIL file=" + name + " same=N/A different=N/A ratio=N/A"
                            + " dimension-mismatch baseline=" + dimensions(a) + " current=" + dimensions(b));
                    failed++;
                    continue;
                }
                long total = (long) a.getWidth() * a.getHeight();
                long different = countDifferent(a, b);
                boolean pass = BigDecimal.valueOf(different)
                        .compareTo(threshold.multiply(BigDecimal.valueOf(total))) <= 0;
                if (diffDir != null) {
                    Path output = canonicalOutput(diffDir.resolve(name));
                    if (overlaps(output, baseline) || overlaps(output, current)) {
                        throw new IOException("Diff output resolves inside an input tree: " + output);
                    }
                    writeDiff(a, b, output);
                }
                totalPixels += total;
                differentPixels += different;
                compared++;
                if (pass) passed++; else failed++;
                System.out.println((pass ? "PASS" : "FAIL") + " file=" + name
                        + " size=" + dimensions(a) + " same=" + (total - different)
                        + " different=" + different + " ratio=" + ratio(different, total));
            } catch (IOException error) {
                System.out.println("FAIL file=" + name + " same=N/A different=N/A ratio=N/A error="
                        + error.getMessage());
                failed++;
            }
        }
        System.out.println("SUMMARY files=" + names.size() + " passed=" + passed + " failed=" + failed
                + " compared=" + compared + " same=" + (totalPixels - differentPixels)
                + " different=" + differentPixels + " ratio="
                + (totalPixels == 0 ? "N/A" : ratio(differentPixels, totalPixels)));
        int exit = failed == 0 ? 0 : 1;
        System.out.println("RESULT " + (exit == 0 ? "PASS" : "FAIL") + " exit=" + exit);
        return exit;
    }

    private static Path directory(String value) throws IOException {
        Path path = Path.of(value).toRealPath();
        if (!Files.isDirectory(path)) throw new IOException("Not a directory: " + path);
        return path;
    }

    // Resolve existing ancestors as well, so an output through a junction cannot overwrite inputs.
    private static Path canonicalOutput(Path value) throws IOException {
        Path path = value.toAbsolutePath().normalize();
        if (Files.exists(path)) return path.toRealPath();
        Path parent = path.getParent();
        if (parent == null) throw new IOException("Cannot resolve DiffDir: " + path);
        return canonicalOutput(parent).resolve(path.getFileName());
    }

    private static boolean overlaps(Path a, Path b) {
        return a.startsWith(b) || b.startsWith(a);
    }

    private static Map<String, Path> images(Path root) throws IOException {
        Map<String, Path> result = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            // Do not follow directory links; ordinary files are paired by relative path.
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
                    result.put(root.relativize(path).toString().replace('\\', '/'), path);
                }
            }
        }
        return result;
    }

    private static BufferedImage readPng(Path path) throws IOException {
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        try (var input = Files.newInputStream(path)) {
            if (!java.util.Arrays.equals(signature, input.readNBytes(8))) {
                throw new IOException("Not a PNG: " + path);
            }
        }
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) throw new IOException("Cannot decode PNG: " + path);
        return image;
    }

    private static long countDifferent(BufferedImage a, BufferedImage b) {
        long different = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) different++;
            }
        }
        return different; // mutation-target: self-test must reject a constant zero here
    }

    private static void writeDiff(BufferedImage a, BufferedImage b, Path output) throws IOException {
        Files.createDirectories(output.getParent());
        BufferedImage diff = new BufferedImage(a.getWidth(), a.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                diff.setRGB(x, y, a.getRGB(x, y) == b.getRGB(x, y) ? 0x000000 : 0xff00ff);
            }
        }
        if (!ImageIO.write(diff, "png", output.toFile())) throw new IOException("Cannot write " + output);
    }

    private static String dimensions(BufferedImage image) {
        return image.getWidth() + "x" + image.getHeight();
    }

    private static String ratio(long different, long total) {
        return BigDecimal.valueOf(different).divide(BigDecimal.valueOf(total), 12, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }
}
