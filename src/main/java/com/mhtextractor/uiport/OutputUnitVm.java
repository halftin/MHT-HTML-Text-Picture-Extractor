package com.mhtextractor.uiport;

import com.mhtextractor.model.AppConfig;
import java.util.List;

/** 输出单元的完整可观察面；labelKey 仅在 outputName 为 null 时使用。 */
public record OutputUnitVm(String outputName, boolean group, AppConfig params,
                           List<String> paths, String outputPath, String labelKey,
                           String baseName, String extension, String finalFileName, String uniqueName) {
    public OutputUnitVm {
        paths = List.copyOf(paths);
    }

    public int fileCount() { return paths.size(); }
}
