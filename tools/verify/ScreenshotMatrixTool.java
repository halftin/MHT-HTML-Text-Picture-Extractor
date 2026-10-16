import javax.imageio.ImageIO;
import java.nio.file.*;

/** Independent PNG mutation and bounding-box measurement, JDK only. */
public final class ScreenshotMatrixTool {
    public static void main(String[] args) throws Exception {
        ImageIO.setUseCache(false);
        var a = ImageIO.read(Path.of(args[1]).toFile());
        if (args[0].equals("mutate")) {
            // Invert every RGB pixel: ratio=1, so any useful threshold (<1) must fail.
            for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++)
                a.setRGB(x, y, a.getRGB(x, y) ^ 0x00ffffff);
            Files.createDirectories(Path.of(args[2]).toAbsolutePath().getParent());
            ImageIO.write(a, "png", Path.of(args[2]).toFile());
            System.out.println("KNOWN_MUTATION inverted RGB at every pixel");
            return;
        }
        var b = ImageIO.read(Path.of(args[2]).toFile());
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight())
            throw new AssertionError("Cannot measure bbox for different dimensions");
        long count = 0;
        int minX = a.getWidth(), minY = a.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++) {
            if (a.getRGB(x, y) == b.getRGB(x, y)) continue;
            count++; minX = Math.min(minX, x); minY = Math.min(minY, y);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
        }
        System.out.println("BBOX different=" + count + " bbox=" + (count == 0 ? "none"
                : minX + "," + minY + ".." + maxX + "," + maxY));
    }
}
