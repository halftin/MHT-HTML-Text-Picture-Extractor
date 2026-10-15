import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Creates independent fixtures, invokes the distributed wrapper and checks real process exit codes. */
public final class ScreenshotCompareSelfTest {
    private record Case(String name, String before, String after, String threshold,
                        int exit, String expected, String diff) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: java ScreenshotCompareSelfTest.java FIXTURE_ROOT WRAPPER_PATH");
            System.exit(1);
        }
        ImageIO.setUseCache(false);
        Path root = Path.of(args[0]).toAbsolutePath().resolve("run-" + UUID.randomUUID());
        Path wrapper = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(root);
        System.out.println("FIXTURES " + root);
        png(root, "baseline/shot.png", 10, 10, 0xffffffff, 0);
        png(root, "same/shot.png", 10, 10, 0xffffffff, 0);
        png(root, "one/shot.png", 10, 10, 0xffffffff, 1);
        png(root, "many/shot.png", 10, 10, 0xff000000, 0);
        png(root, "size/shot.png", 11, 10, 0xffffffff, 0);
        png(root, "alpha/shot.png", 10, 10, 0x00ffffff, 0);
        png(root, "extra/shot.png", 10, 10, 0xffffffff, 0);
        png(root, "extra/extra.png", 10, 10, 0xffffffff, 0);
        png(root, "mixed/shot.png", 10, 10, 0xffffffff, 0);
        png(root, "mixed/nested/other.png", 10, 10, 0xffffffff, 0);
        png(root, "mixed-current/shot.png", 10, 10, 0xffffffff, 0);
        png(root, "mixed-current/nested/other.png", 10, 10, 0xff000000, 0);
        png(root, "space baseline/nested space/\u622a\u56fe.PNG", 10, 10, 0xffffffff, 0);
        png(root, "space current/nested space/\u622a\u56fe.PNG", 10, 10, 0xffffffff, 0);
        Files.createDirectories(root.resolve("empty"));
        Files.createDirectories(root.resolve("corrupt"));
        Files.writeString(root.resolve("corrupt/shot.png"), "invalid PNG");
        // PNG signature with truncated data must fail during decoding too.
        Files.createDirectories(root.resolve("truncated"));
        Files.write(root.resolve("truncated/shot.png"), new byte[]{(byte)137,80,78,71,13,10,26,10});
        var cases = List.of(
                new Case("identical", "baseline", "same", "0", 0, "same=100 different=0 ratio=0", "diff-same"),
                new Case("one-reject", "baseline", "one", "0.009", 1, "same=99 different=1 ratio=0.01", "diff-one"),
                new Case("one-accept-boundary", "baseline", "one", "0.01", 0, "same=99 different=1 ratio=0.01", null),
                new Case("far-over", "baseline", "many", "0.01", 1, "same=0 different=100 ratio=1", "diff-many"),
                new Case("size-even-at-one", "baseline", "size", "1", 1,
                        "dimension-mismatch baseline=10x10 current=11x10", null),
                new Case("alpha-counts", "baseline", "alpha", "0", 1, "different=100 ratio=1", null),
                new Case("extra-current", "baseline", "extra", "1", 1, "missing=baseline", null),
                new Case("missing-current", "extra", "baseline", "1", 1, "missing=current", null),
                new Case("empty", "empty", "empty", "0", 1, "No PNG files", null),
                new Case("corrupt", "baseline", "corrupt", "1", 1, "Not a PNG", null),
                new Case("truncated", "baseline", "truncated", "1", 1, "error=", null),
                new Case("negative-threshold", "baseline", "same", "-0.1", 1, "Threshold must be", null),
                new Case("high-threshold", "baseline", "same", "1.01", 1, "Threshold must be", null),
                new Case("nan-threshold", "baseline", "same", "NaN", 1, "Threshold must be", null),
                new Case("missing-directory", "absent", "same", "0", 1, "RESULT FAIL", null),
                new Case("overlap-protection", "baseline", "one", "0", 1, "DiffDir must be separate", "baseline/diff"),
                new Case("nested-spaces-unicode", "space baseline", "space current", "0", 0,
                        "same=100 different=0 ratio=0", "diff nested"),
                new Case("each-file-not-average", "mixed", "mixed-current", "0.5", 1,
                        "files=2 passed=1 failed=1", null)
        );
        int failures = 0;
        for (Case test : cases) {
            List<String> command = new ArrayList<>(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", wrapper.toString(), "-Baseline", root.resolve(test.before()).toString(),
                    "-Current", root.resolve(test.after()).toString(), "-Threshold", test.threshold()));
            if (test.diff() != null) command.addAll(List.of("-DiffDir", root.resolve(test.diff()).toString()));
            System.out.println("COMMAND " + command.stream().map(s -> "'" + s.replace("'", "''") + "'")
                    .collect(java.util.stream.Collectors.joining(" ")));
            Path log = root.resolve(test.name() + ".log");
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
            Process process = builder.start();
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IOException("Timed out: " + test.name());
            }
            int actual = process.exitValue();
            String output = Files.readString(log, StandardCharsets.UTF_8);
            System.out.print(output);
            System.out.println("ACTUAL_EXIT=" + actual + " EXPECTED_EXIT=" + test.exit());
            boolean valid = actual == test.exit() && output.contains(test.expected());
            if (test.name().equals("one-reject")) valid &= diffIs(root.resolve("diff-one/shot.png"), 1);
            if (test.name().equals("identical")) valid &= diffIs(root.resolve("diff-same/shot.png"), 0);
            if (test.name().equals("far-over")) valid &= diffIs(root.resolve("diff-many/shot.png"), 100);
            System.out.println("ASSERT " + test.name() + " " + (valid ? "PASS" : "FAIL"));
            if (!valid) failures++;
        }
        System.out.println("SELFTEST cases=" + cases.size() + " passed=" + (cases.size() - failures)
                + " failed=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void png(Path root, String name, int width, int height, int argb, int changed) throws IOException {
        Path output = root.resolve(name);
        Files.createDirectories(output.getParent());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) image.setRGB(x, y, argb);
        }
        if (changed == 1) image.setRGB(4, 5, 0xff000000);
        if (!ImageIO.write(image, "png", output.toFile())) throw new IOException("PNG writer unavailable");
    }

    private static boolean diffIs(Path path, int expected) throws IOException {
        BufferedImage image = ImageIO.read(path.toFile());
        int magenta = 0;
        boolean valid = image.getWidth() == 10 && image.getHeight() == 10;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y) & 0xffffff;
                if (rgb == 0xff00ff) magenta++;
                else if (rgb != 0) valid = false;
            }
        }
        System.out.println("DIFF_ASSERT " + path + " magenta=" + magenta + " expected=" + expected);
        return valid && magenta == expected;
    }
}
