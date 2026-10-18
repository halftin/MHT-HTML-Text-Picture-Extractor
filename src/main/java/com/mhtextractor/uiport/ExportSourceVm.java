package com.mhtextractor.uiport;

/** Source export diagnostics; session binding is supplied by the IPC response. */
public record ExportSourceVm(String status, FileRef htmlFile, int imageCount, int warningCount,
                             String detailKey, java.util.Map<String, String> detailParams) {
    public ExportSourceVm { detailParams = detailParams == null ? null : java.util.Map.copyOf(detailParams); }
}
