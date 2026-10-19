package com.mhtextractor.backend;

import com.mhtextractor.model.PreviewImageData;
import com.mhtextractor.engine.PreviewNamingService;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.ThumbnailData;

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
    private static int[] dimensions(byte[] bytes, String extension) {
        return com.mhtextractor.engine.InProcessUiport.imageDimensions(bytes, extension);
    }
}
