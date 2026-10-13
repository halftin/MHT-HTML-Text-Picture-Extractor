package com.mhtextractor.uiport;

/** Source export diagnostics; session binding is supplied by the IPC response. */
public record ExportSourceVm(String status, FileRef htmlFile, int imageCount, int warningCount, String detail) {}
