package com.mhtextractor.baseline;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.fail;

/** Exact frozen debt, with a visible ratchet. Removal requires retiring the corresponding TSV row. */
class FrontendClosureTest {
    // P-F1 initial measured counts; only lower these. P-F6: FROZEN_MAX = 0 and an empty baseline.
    private static final int FROZEN_MAX = 0;
    private static final int PENDING_MAX = 15;
    private static final Path BASELINE = Path.of("src/test/resources/frontend-closure-baseline.tsv");

    @Test
    void frontendClosureIsFrozen() throws IOException {
        FrontendClosureScan.Scan scan = FrontendClosureScan.scan(Path.of("target/classes"));
        System.out.println("FrontendClosure: classes=" + scan.classesScanned() + ", discovered="
                + scan.violations().size() + ", pending=" + scan.pending().size());
        System.out.println("pending platform（待裁决，不进入冻结文件；PENDING_MAX=" + PENDING_MAX + "）：\n"
                + String.join("\n", scan.pending()));
        List<String> errors = new ArrayList<>();
        SortedSet<String> frozen = readFrozen(errors);
        SortedSet<String> unregistered = new TreeSet<>(scan.violations());
        unregistered.removeAll(frozen);
        SortedSet<String> stale = new TreeSet<>(frozen);
        stale.removeAll(scan.violations());
        if (!unregistered.isEmpty()) {
            errors.add("发现但未登记（默认禁止新增；应去除引用。若获准加条目，必须同时审查 FROZEN_MAX）：\n"
                    + String.join("\n", unregistered));
        }
        if (!stale.isEmpty()) errors.add("登记但已不存在（陈旧条目，请删除以下原样 TSV 行）：\n" + String.join("\n", stale));
        if (frozen.size() > FROZEN_MAX) errors.add("frozen.size()=" + frozen.size() + " > FROZEN_MAX=" + FROZEN_MAX);
        if (scan.pending().size() > PENDING_MAX) errors.add("pending.size()=" + scan.pending().size() + " > PENDING_MAX=" + PENDING_MAX);
        if (!errors.isEmpty()) fail(String.join("\n\n", errors) + "\n\n" + scan.diagnostic() + "\n" + FrontendClosureScan.REPAIR);
    }

    private static SortedSet<String> readFrozen(List<String> errors) throws IOException {
        if (!Files.isRegularFile(BASELINE)) {
            throw new IOException("未找到冻结基线 " + BASELINE.toAbsolutePath() + "；" + FrontendClosureScan.REPAIR);
        }
        SortedSet<String> frozen = new TreeSet<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(BASELINE, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank() || line.startsWith("#")) continue;
            if (!line.matches("(?:gui|uicore)\\.[^\\s$]+\\t(?:app|core|extractor|processor|output|cache|util|config)\\.[^\\s]+")) {
                errors.add("冻结基线格式错误，行 " + lineNumber + ": " + line);
            } else if (!frozen.add(line)) {
                errors.add("冻结基线重复条目，行 " + lineNumber + ": " + line);
            }
        }
        return frozen;
    }
}
