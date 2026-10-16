import javafx.scene.image.Image;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/** Minimal lossless RGBA PNG output, shared by JVM/native; no AWT/ImageIO. */
final class ProbePng {
    static void write(Image image, Path file) throws IOException {
        int width = (int)image.getWidth(), height = (int)image.getHeight();
        var raw = new ByteArrayOutputStream();
        try (var zip = new DeflaterOutputStream(raw)) {
            for (int y = 0; y < height; y++) {
                zip.write(0);
                for (int x = 0; x < width; x++) {
                    int argb = image.getPixelReader().getArgb(x, y);
                    zip.write(argb >>> 16 & 255); zip.write(argb >>> 8 & 255);
                    zip.write(argb & 255); zip.write(argb >>> 24 & 255);
                }
            }
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (var out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeLong(0x89504e470d0a1a0aL);
            var header = new ByteArrayOutputStream();
            var data = new DataOutputStream(header);
            data.writeInt(width); data.writeInt(height);
            data.write(new byte[] {8, 6, 0, 0, 0});
            chunk(out, "IHDR", header.toByteArray());
            chunk(out, "IDAT", raw.toByteArray()); chunk(out, "IEND", new byte[0]);
        }
    }
    private static void chunk(DataOutputStream out, String name, byte[] data) throws IOException {
        byte[] type = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var crc = new CRC32(); crc.update(type); crc.update(data);
        out.writeInt(data.length); out.write(type); out.write(data); out.writeInt((int)crc.getValue());
    }
}
