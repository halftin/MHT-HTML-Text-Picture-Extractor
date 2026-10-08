package com.mhtextractor.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** All cases x zh/en through the real --once --config CLI, never the processor test runner. */
class BackendConfigBaselineTest {
    @Test
    void allConfigsMatchGoldenBaselines() throws Exception {
        Path root = BackendProcess.evidence("matrix");
        ObjectMapper json = new ObjectMapper();
        List<String> matrix = new ArrayList<>();
        matrix.add("case\tlocale\tstatus\tevidence");
        List<String> failures = new ArrayList<>();
        int passed = 0;
        int total = 0;
        String onlyCase = System.getProperty("mht.backend.baseline.case");
        String onlyLocale = System.getProperty("mht.backend.baseline.locale");
        for (BaselineCases.Case c : BaselineCases.all()) {
            if (onlyCase != null && !onlyCase.equals(c.name())) continue;
            for (String loc : List.of("zh", "en")) {
                if (onlyLocale != null && !onlyLocale.equals(loc)) continue;
                total++;
                Path work = Files.createDirectories(root.resolve(c.name() + "." + loc));
                String status = "FAIL";
                try {
                    Path input = work.resolve("in");
                    // CLI import sorts absolute paths; encode the case's declared processing order
                    // in parent folders, preserving every source basename, byte, mtime and sidecar.
                    int ordinal = 0;
                    for (String fixture : c.fixtures()) {
                        var part = new BaselineCases.Case(c.name(), List.of(fixture), c.config(),
                                c.imageMode(), c.mergedOutput(), c.note(), c.knownIssues());
                        BaselineCases.prepareInput(part, input.resolve(String.format("%03d", ++ordinal)));
                    }
                    var params = c.config().copyExtractionParams();
                    if (Boolean.getBoolean("mht.backend.baseline.mutate")
                            && c.name().equals("text-merged-chapter") && loc.equals("zh")) {
                        params.setAddChapterNumber(!params.isAddChapterNumber());
                    }
                    Path config = work.resolve("config.json");
                    Files.writeString(config, json.writerWithDefaultPrettyPrinter().writeValueAsString(params));
                    Path out = work.resolve("out");
                    BackendProcess.Result run = BackendProcess.run(work, List.of("--once", "--locale", loc,
                            "--extract", c.imageMode() ? "images" : "text", "--config", config.toString(),
                            "--input", input.toString(), "--output", out.toString()));

                    var expected = json.readTree(Files.readString(Path.of("src/test/resources/baselines",
                            c.name() + "." + loc + ".json")));
                    // text-boundary intentionally has 0 successes / 1 failure: exit 1 is its golden behavior.
                    int expectedExit = expected.path("stats").path("fail").asInt() > 0
                            || expected.path("stats").path("success").asInt() == 0 ? 1 : 0;
                    assertEquals(expectedExit, run.code(), "CLI exit; stderr=" + run.stderr());
                    var summary = Pattern.compile("done: total=(\\d+) success=(\\d+) fail=(\\d+) skip=(\\d+) warn=(\\d+)")
                            .matcher(run.stdout());
                    assertTrue(summary.find(), "missing CLI stats; stdout=" + run.stdout());
                    List<String> outcomes = run.stdout().lines().filter(s -> s.startsWith("result: "))
                            .map(s -> s.substring("result: ".length())).sorted(Comparator.naturalOrder()).toList();
                    var stats = new BaselineCases.StatsSummary(Integer.parseInt(summary.group(1)),
                            Integer.parseInt(summary.group(2)), Integer.parseInt(summary.group(3)),
                            Integer.parseInt(summary.group(4)), Integer.parseInt(summary.group(5)), outcomes);
                    String actual = BaselineCases.toJson(c, loc, BaselineCases.summarize(out, stats, work));
                    Files.writeString(work.resolve("actual.json"), actual);
                    Files.writeString(work.resolve("expected.json"), json.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(expected));
                    assertEquals(expected, json.readTree(actual), "golden JSON (products, archive entries, content, stats)");
                    passed++;
                    status = "PASS";
                } catch (Exception | AssertionError e) {
                    failures.add(c.name() + "/" + loc + ": " + e);
                    Files.writeString(work.resolve("failure.txt"), e.toString());
                }
                String row = c.name() + "\t" + loc + "\t" + status + "\t" + work;
                matrix.add(row);
                System.out.println("[backend-baseline] " + row);
                Files.writeString(root.resolve("matrix.tsv"), String.join("\n", matrix) + "\n");
            }
        }
        System.out.println("[backend-baseline] " + passed + "/" + total + "; evidence=" + root);
        assertTrue(total > 0, "filter selected no baselines");
        if (onlyCase == null && onlyLocale == null) assertEquals(BaselineCases.all().size() * 2, total);
        assertEquals(List.of(), failures, String.join("\n", failures));
    }
}
