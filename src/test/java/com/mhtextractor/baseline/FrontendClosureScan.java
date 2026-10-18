package com.mhtextractor.baseline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Stream;

/** P-F1: compiled frontend dependencies, including anonymous and nested classes. No reflection. */
public final class FrontendClosureScan {
    private static final String ROOT = "com/mhtextractor/";
    private static final List<String> FORBIDDEN = List.of(
            "app", "core", "extractor", "processor", "output", "cache", "util", "config");
    public static final String REPAIR = "请先执行 mvnw.cmd -q -DskipTests compile 后重跑"
            + "（本闸门依赖 target/classes 字节码，不依赖源码）";

    private FrontendClosureScan() {}

    /** Stable, deduplicated TSV keys. Only the frontend owner is folded to its top-level class. */
    public record Scan(int classesScanned, SortedSet<String> violations, SortedSet<String> pending) {
        public Scan {
            violations = Collections.unmodifiableSortedSet(new TreeSet<>(violations));
            pending = Collections.unmodifiableSortedSet(new TreeSet<>(pending));
        }

        public String diagnostic() {
            return "完整发现集合（TSV，" + violations.size() + " 条，扫描 " + classesScanned + " 个 class）：\n"
                    + String.join("\n", violations) + "\npending platform（" + pending.size() + " 条）：\n"
                    + String.join("\n", pending);
        }
    }

    public static Scan scan(Path mainClasses) throws IOException {
        if (!Files.isDirectory(mainClasses)) {
            throw new IOException("未找到编译产物 " + mainClasses.toAbsolutePath() + "，" + REPAIR);
        }
        SortedSet<String> violations = new TreeSet<>();
        SortedSet<String> pending = new TreeSet<>();
        int scanned = 0;
        for (String frontend : List.of("gui", "uicore")) {
            Path directory = mainClasses.resolve(ROOT + frontend);
            // Both are established production packages: a missing package must not silently pass.
            if (!Files.isDirectory(directory)) {
                throw new IOException("未找到前端编译产物 " + directory.toAbsolutePath() + "，" + REPAIR);
            }
            List<Path> classFiles;
            try (Stream<Path> walk = Files.walk(directory)) {
                classFiles = walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".class")).sorted().toList();
            }
            if (classFiles.isEmpty()) {
                throw new IOException("前端编译产物为空 " + directory.toAbsolutePath() + "，" + REPAIR);
            }
            for (Path file : classFiles) {
                scanned++;
                String relative = mainClasses.relativize(file).toString().replace('\\', '/');
                String owner = relative.substring(ROOT.length(), relative.length() - ".class".length());
                int nested = owner.indexOf('$');
                if (nested >= 0) owner = owner.substring(0, nested);
                owner = owner.replace('/', '.');
                // Same conservative byte-to-Latin1 technique as UiportSignature: literals count too.
                String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                for (String forbidden : FORBIDDEN) collect(bytes, ROOT + forbidden + "/", owner, violations);
                collect(bytes, ROOT + "platform/", owner, pending);
            }
        }
        return new Scan(scanned, violations, pending);
    }

    private static void collect(String bytes, String prefix, String owner, SortedSet<String> result) {
        int start = 0;
        while ((start = bytes.indexOf(prefix, start)) >= 0) {
            int end = start + prefix.length();
            while (end < bytes.length() && !terminator(bytes.charAt(end))) end++;
            if (end > start + prefix.length()) {
                result.add(owner + "\t" + bytes.substring(start + ROOT.length(), end).replace('/', '.'));
            }
            start = Math.max(end, start + 1);
        }
    }

    private static boolean terminator(char c) {
        return c == ';' || c == '(' || c == ')' || c == '[' || c == '<' || c == '>'
                || c == '.' || c == ' ' || c < 0x20 || c > 0x7e;
    }

    /** Read-only diagnostic / initial freeze generation: redirect stdout to a TSV; pending stays on stderr. */
    public static void main(String[] args) throws IOException {
        Scan scan = scan(args.length == 0 ? Path.of("target/classes") : Path.of(args[0]));
        scan.violations().forEach(System.out::println);
        System.err.println("classesScanned=" + scan.classesScanned() + ", pending=" + scan.pending().size());
        scan.pending().forEach(System.err::println);
    }
}
