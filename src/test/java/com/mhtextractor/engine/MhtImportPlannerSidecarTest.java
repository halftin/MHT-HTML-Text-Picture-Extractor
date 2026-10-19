package com.mhtextractor.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R6.1 导入识别（HTML 组）+ R6.4 侧车目录排除的无头测试。
 *
 * <p><b>每条"不计入"的断言都配控制组</b>：同样数量的<b>非侧车</b>文件必须计入 ——
 * 否则"不计入"可能是因为统计本来就恒为 0（假通过）。</p>
 *
 * <p>⚠️ 关于大小写：本步的拍板是"只放宽 .html/.htm"，而 {@code .mht/.mhtml} 的既有判定
 * <b>本来就是大小写不敏感</b>（见 {@code util.MhtImportPlannerTest.upperCaseMhtmlExtensionIsMatched}）。
 * 因此这里的 {@code .MHT} 断言是"<b>维持既有行为</b>"，不是"为假"。</p>
 */
class MhtImportPlannerSidecarTest {

    @TempDir
    Path dir;

    private static File write(Path parent, String name, String content) throws Exception {
        Path p = parent.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p.toFile();
    }

    private MhtImportPlanner.Scanner scanner(String... addresses) {
        return MhtImportPlanner.scanner(List.of(addresses),
                MhtImportPlanner.DEFAULT_MAX_DEPTH,
                MhtImportPlanner.DEFAULT_MAX_GROUPS,
                MhtImportPlanner.DEFAULT_MAX_FILES);
    }

    private static MhtImportPlanner.Plan run(MhtImportPlanner.Scanner sc) {
        MhtImportPlanner.Plan plan;
        do {
            plan = sc.nextRound();
        } while (plan.hasMore());
        return plan;
    }

    // ==================== R6.1：扩展名口径 ====================

    @Test
    void htmlAndHtmExtensionsAreImportableInAnyCase() throws Exception {
        for (String name : List.of("a.html", "b.htm", "c.HTML", "d.HTM", "e.Html")) {
            File f = write(dir, name, "<html></html>");
            MhtImportPlanner.Plan plan = scanner(f.getAbsolutePath()).nextRound();
            assertEquals(1, plan.fileCount(), "应被导入：" + name);
            assertEquals(0, plan.ignoredNonMht(), "不得计入忽略：" + name);
        }
    }

    @Test
    void mhtExtensionsKeepTheirExistingCaseInsensitiveBehaviour() throws Exception {
        // 控制组：既有行为（本步不得改动）
        for (String name : List.of("a.mht", "b.mhtml", "c.MHT", "d.MHTML")) {
            File f = write(dir, name, "x");
            MhtImportPlanner.Plan plan = scanner(f.getAbsolutePath()).nextRound();
            assertEquals(1, plan.fileCount(), "既有行为：应被导入：" + name);
        }
    }

    @Test
    void otherExtensionsRemainIgnored() throws Exception {
        for (String name : List.of("a.txt", "b.png", "c.htmx", "noext")) {
            File f = write(dir, name, "x");
            MhtImportPlanner.Plan plan = scanner(f.getAbsolutePath()).nextRound();
            assertTrue(plan.isEmpty(), "不得被导入：" + name);
            assertEquals(1, plan.ignoredNonMht(), "应计入忽略：" + name);
        }
    }

    // ==================== R6.4：侧车目录排除（含控制组） ====================

    @Test
    void sidecarResourcesAreNotCountedAsIgnored() throws Exception {
        write(dir, "page.html", "<html><body><img src=\"page_files/a.png\"></body></html>");
        for (int i = 0; i < 5; i++) {
            write(dir, "page_files/img" + i + ".png", "png-bytes-" + i);
        }

        MhtImportPlanner.Plan plan = run(scanner(dir.toString()));

        assertEquals(1, plan.fileCount(), "只有 HTML 本体是输入来源");
        assertEquals(0, plan.ignoredNonMht(),
                "侧车里的 5 个资源一个都不许计入 ignoredNonMht（否则一次另存为灌入几百个条目）");
        assertEquals(0, plan.groupCount(), "侧车目录不得建成组");
        assertEquals(0, plan.hiddenDirs());
        assertEquals(0, plan.depthLimited());
        assertEquals(0, plan.unreadable());
    }

    @Test
    void controlGroupNonSidecarFilesAreCounted() throws Exception {
        // 控制组：同样数量的文件放在**非侧车**目录里 → 必须计入（证明上面的 0 不是恒真）
        write(dir, "page.html", "<html></html>");
        for (int i = 0; i < 5; i++) {
            write(dir, "other/img" + i + ".png", "png-bytes-" + i);
        }

        MhtImportPlanner.Plan plan = run(scanner(dir.toString()));

        assertEquals(1, plan.fileCount(), "只有 HTML 本体");
        assertEquals(5, plan.ignoredNonMht(),
                "非侧车目录里的 5 个非 MHT 文件必须计入（控制组：证明'不计入'不是恒真）");
    }

    @Test
    void sidecarDirectoryPassedDirectlyAsAddressIsSkipped() throws Exception {
        write(dir, "page.html", "<html></html>");
        write(dir, "page_files/a.png", "x");
        write(dir, "page_files/b.png", "y");

        MhtImportPlanner.Plan plan = scanner(dir.resolve("page_files").toString()).nextRound();

        assertTrue(plan.isEmpty(), "直接把侧车目录当地址也不得产出");
        assertEquals(0, plan.ignoredNonMht(), "侧车内容同样不计入");
    }

    @Test
    void directoryEndingWithFilesButWithoutMatchingHtmlIsNotASidecar() throws Exception {
        // 窄判据：必须同目录存在同基名的 HTML，否则不算侧车（避免误吞普通目录）
        write(dir, "assets_files/a.png", "x");
        File nested = dir.resolve("assets_files").toFile();
        assertFalse(MhtImportPlanner.isSidecarDir(nested),
                "没有配套 assets.html 的 *_files 目录不构成侧车");

        MhtImportPlanner.Plan plan = run(scanner(dir.toString()));

        assertTrue(plan.isEmpty());
        assertEquals(1, plan.ignoredNonMht(),
                "普通目录里的 1 个非 MHT 文件照常计入忽略（控制组：证明上面的 0 不是恒真）");
    }

    @Test
    void isSidecarDirIsCaseInsensitiveOnTheSuffix() throws Exception {
        write(dir, "PAGE.html", "<html></html>");
        File side = dir.resolve("PAGE_FILES").toFile();
        Files.createDirectories(side.toPath());
        assertTrue(MhtImportPlanner.isSidecarDir(side), "后缀大小写不敏感");
        assertFalse(MhtImportPlanner.isSidecarDir(dir.resolve("page.html").toFile()));
        assertFalse(MhtImportPlanner.isSidecarDir(dir.resolve("_files").toFile()),
                "只有后缀没有基名不算侧车");
    }

    @Test
    void mixedMhtAndHtmlInOneDirectoryAreBothImported() throws Exception {
        write(dir, "a.mht", "x");
        write(dir, "b.html", "<html></html>");
        write(dir, "c.htm", "<html></html>");

        MhtImportPlanner.Plan plan = run(scanner(dir.toString()));

        assertEquals(3, plan.fileCount(), "一次导入可以同时含 MHT 与 HTML（R6.1）");
        assertEquals(1, plan.batches().size(), "同一层仍是一个顶层批次");
        assertEquals(0, plan.ignoredNonMht());
    }
}
