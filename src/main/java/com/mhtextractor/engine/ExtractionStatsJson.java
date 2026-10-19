package com.mhtextractor.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhtextractor.model.ExtractWarning;
import com.mhtextractor.model.ExtractionStats;
import java.util.List;
import java.util.Map;

/** Explicit encoding for both in-process file references and IPC; no record reflection in native. */
public final class ExtractionStatsJson {
    private ExtractionStatsJson() {}

    public static ObjectNode encode(ObjectMapper json, ExtractionStats stats) {
        var value = json.createObjectNode().put("summaryKey", stats.summaryKey())
                .put("elapsedMillis", stats.elapsedMillis()).put("totalFiles", stats.totalFiles())
                .put("successCount", stats.successCount()).put("failCount", stats.failCount())
                .put("skipCount", stats.skipCount()).put("warnCount", stats.warnCount());
        params(value, "summaryParams", stats.summaryParams());
        var details = value.putArray("warnDetails");
        for (var detail : stats.warnDetails()) {
            var entry = details.addObject().put("key", detail.key());
            params(entry, "params", detail.params());
            if (detail.warning() == null) entry.putNull("warning");
            else entry.set("warning", warning(json, detail.warning()));
        }
        value.set("files", outcomes(json, stats.files()));
        return value;
    }

    public static ArrayNode outcomes(ObjectMapper json, List<ExtractionStats.FileOutcome> outcomes) {
        var values = json.createArrayNode();
        for (var outcome : outcomes) {
            var file = values.addObject().put("path", outcome.path()).put("outcome", outcome.outcome().name())
                    .put("messageKey", outcome.messageKey()).put("messageDetail", outcome.messageDetail());
            params(file, "messageParams", outcome.messageParams());
            file.set("warnings", warnings(json, outcome.warnings()));
        }
        return values;
    }

    public static void params(ObjectNode node, String field, Map<String, String> params) {
        if (params == null) node.putNull(field);
        else params.forEach(node.putObject(field)::put);
    }

    public static ArrayNode warnings(ObjectMapper json, List<ExtractWarning> warnings) {
        var values = json.createArrayNode();
        for (var warning : warnings) values.add(warning(json, warning));
        return values;
    }

    private static ObjectNode warning(ObjectMapper json, ExtractWarning warning) {
        var value = json.createObjectNode().put("key", warning.key());
        params(value, "args", warning.args());
        return value;
    }
}
