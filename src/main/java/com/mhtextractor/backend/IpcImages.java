package com.mhtextractor.backend;

import com.mhtextractor.app.PreviewImageData;
import com.mhtextractor.app.PreviewNamingService;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.ThumbnailData;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Encoded image metadata only: no decoder, pixels, transcoding or UI dependency. */
final class IpcImages {
    private IpcImages() { }

    static String extension(byte[] bytes) {
        String ext = PreviewNamingService.detectExtension(bytes, "");
        if (ext.isEmpty()) throw new IllegalArgumentException("Unrecognized encoded image format");
        return ext;
    }

    static String mediaType(String ext) {
        return switch (ext) {
            case "jpg" -> "image/jpeg";
            case "ico", "cur" -> "image/x-icon";
            default -> "image/" + ext;
        };
    }

    static ThumbnailData thumbnail(PreviewImageData image, int index, AppConfig params) {
        String ext = extension(image.data());
        int[] dimensions = dimensions(image.data(), ext);
        return new ThumbnailData(PreviewNamingService.imageFileName(image, ext,
                params.isEnableBatchRenaming(), index + 1, ""), dimensions[0], dimensions[1], null);
    }

    // Dimensions describe the original encoded image. Zero means that its header has no
    // dimensions supported here; the client still decodes the file and reports its own failure.
    private static int[] dimensions(byte[] b, String ext) {
        ByteBuffer big = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
        ByteBuffer little = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        if (ext.equals("png") && b.length >= 24) return positive(big.getInt(16), big.getInt(20));
        if (ext.equals("gif") && b.length >= 10) return positive(little.getShort(6) & 65535, little.getShort(8) & 65535);
        if (ext.equals("bmp") && b.length >= 26) return positive(little.getInt(18), Math.abs(little.getInt(22)));
        if ((ext.equals("ico") || ext.equals("cur")) && b.length >= 8)
            return positive(b[6] == 0 ? 256 : b[6] & 255, b[7] == 0 ? 256 : b[7] & 255);
        if (ext.equals("jpg")) {
            int i = 2;
            while (i + 3 < b.length) {
                if ((b[i++] & 255) != 255) break;
                while (i < b.length && (b[i] & 255) == 255) i++;
                if (i >= b.length) break;
                int marker = b[i++] & 255;
                if (marker == 0xd9 || marker == 0xda) break;
                if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd8)) continue;
                if (i + 2 > b.length) break;
                int length = big.getShort(i) & 65535;
                if (length < 2 || i + length > b.length) break;
                if (marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc && length >= 7)
                    return positive(big.getShort(i + 5) & 65535, big.getShort(i + 3) & 65535);
                i += length;
            }
        }
        if (ext.equals("webp") && b.length >= 30) {
            String kind = new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (kind.equals("VP8X")) return positive(1 + u24(b, 24), 1 + u24(b, 27));
            if (kind.equals("VP8 ")) return positive(little.getShort(26) & 16383, little.getShort(28) & 16383);
            if (kind.equals("VP8L") && b[20] == 0x2f) {
                int bits = little.getInt(21);
                return positive(1 + (bits & 16383), 1 + ((bits >>> 14) & 16383));
            }
        }
        return new int[]{0, 0};
    }

    private static int u24(byte[] b, int i) { return (b[i] & 255) | ((b[i + 1] & 255) << 8) | ((b[i + 2] & 255) << 16); }
    private static int[] positive(int w, int h) { return w > 0 && h > 0 ? new int[]{w, h} : new int[]{0, 0}; }
}
