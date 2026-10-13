package com.mhtextractor.uiport;

import java.util.List;

public record PresentationVm(String path, String displayName, boolean parsing, boolean imageMode,
                              String status, String head, String tail, FileRef textFile,
                              List<PortEvent.ImageFile> images) {
    public PresentationVm { images = List.copyOf(images); }
}
