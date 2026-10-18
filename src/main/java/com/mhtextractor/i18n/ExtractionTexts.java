package com.mhtextractor.i18n;

import com.mhtextractor.model.ExtractWarning;
import com.mhtextractor.model.ExtractionStats;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Presentation of neutral extraction results, shared by GUI facade and CLI logs. */
public final class ExtractionTexts {
    private ExtractionTexts() {}

    public static String message(String key, Map<String, String> params) {
        Object[] pairs = params == null ? new Object[0] : params.entrySet().stream()
                .flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue())).toArray();
        return I18n.get(key, pairs);
    }

    public static String summaryLine(String key, Map<String, String> rawParams) {
        var params = new LinkedHashMap<>(rawParams);
        // Keep the historical String.format default-locale decimal separator and rounding.
        params.put("elapsed", String.format("%.2f", Long.parseLong(params.get("elapsed")) / 1000.0));
        return message(key, params);
    }

    public static String warningDetail(ExtractionStats.WarningDetail detail) {
        var params = new LinkedHashMap<>(detail.params());
        if (detail.warning() != null) params.put("message", I18n.render(detail.warning()));
        return message(detail.key(), params);
    }

    public static String summary(ExtractionStats stats) {
        var summary = new StringBuilder(summaryLine(stats.summaryKey(), stats.summaryParams()));
        for (var detail : stats.warnDetails()) summary.append('\n').append(warningDetail(detail));
        return summary.toString();
    }

    public static String detail(String key, Map<String, String> params, String diagnostic,
                                List<ExtractWarning> warnings) {
        List<String> parts = new ArrayList<>();
        if (key != null && !key.isEmpty()) parts.add(message(key, params));
        if (diagnostic != null && !diagnostic.isEmpty()) parts.add(diagnostic);
        for (var warning : warnings) parts.add(I18n.render(warning));
        return parts.isEmpty() ? null : String.join(I18n.get("list.itemSeparator"), parts);
    }
}
