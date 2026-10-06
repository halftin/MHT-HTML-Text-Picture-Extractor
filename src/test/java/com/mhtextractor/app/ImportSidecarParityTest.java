package com.mhtextractor.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A1：两个侧车判定实现 —— {@code MhtImportPlanner.isSidecarDir}（目录扫描路径）与
 * {@code MhtExtractorApp.isInSidecarDir}（祖先判定路径）—— 必须对同一棵树给出同义的来源集合。
 * 两者已共用 {@code SourceKinds.htmlExtensions()}，这里锁"同义"这条行为。
 */
class ImportSidecarParityTest {

    @TempDir
    Path dir;

    private static void write(Path parent, String name) throws Exception {
        Path p = parent.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
    }

    private static Set<String> plannerSources(Path root) {
        MhtImportPlanner.Scanner sc = MhtImportPlanner.scanner(List.of(root.toString()),
                MhtImportPlanner.DEFAULT_MAX_DEPTH, MhtImportPlanner.DEFAULT_MAX_GROUPS,
                MhtImportPlanner.DEFAULT_MAX_FILES);
        Set<String> found = new TreeSet<>();
        MhtImportPlanner.Plan plan;
        do {
            plan = sc.nextRound();
            for (MhtImportPlanner.Batch b : plan.batches()) {
                for (File f : b.files()) {
                    found.add(root.relativize(f.toPath()).toString().replace('\\', '/'));
                }
            }
        } while (plan.hasMore());
        return found;
    }

    private static Set<String> appSources(Path root) {
        MhtExtractorApp app = new MhtExtractorApp();
        Set<String> found = new TreeSet<>();
        for (Path p : app.importFolder(root)) {
            found.add(root.relativize(p).toString().replace('\\', '/'));
        }
        return found;
    }

    @Test
    void bothSidecarImplementationsAgreeOnSources() throws Exception {
        write(dir, "page.html");
        write(dir, "page_files/a.png");
        write(dir, "page_files/b.txt");
        write(dir, "page_files/extra.html");    // 侧车内的 HTML 也是可导入扩展名：两侧都必须排除
        write(dir, "other.htm");
        // 大写来源用独立基名 UPPER（不配 *_files 侧车）：
        // 本机 temp 目录启用了按目录大小写敏感，同目录放 page.html 的大小写孪生
        // 会在目录枚举里被折叠（平台怪癖，与 A1 无关），故大写断言不用 PAGE 基名；
        // 大写扩展名的可导入性已由 SourceKindsTest / MhtImportPlannerSidecarTest 单文件用例锁定
        write(dir, "UPPER.HTML");
        write(dir, "plain.mht");
        write(dir, "nohtml_files/x.png");       // 无配套 HTML 的 *_files：不是侧车，但 x.png 也不是来源
        write(dir, "output.v2");
        write(dir, ".html");
        write(dir, "sub/deep.html");

        Set<String> byPlanner = plannerSources(dir);
        Set<String> byApp = appSources(dir);

        Set<String> expected = Set.of("page.html", "other.htm", "UPPER.HTML", "plain.mht", "sub/deep.html");
        assertEquals(expected, byApp, "app.importFolder 的来源集合");
        assertEquals(byApp, byPlanner, "A1：两个侧车判定实现必须同义（扩展名集合同源）");
    }
}
