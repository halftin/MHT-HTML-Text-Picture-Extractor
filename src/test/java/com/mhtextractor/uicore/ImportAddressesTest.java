package com.mhtextractor.uicore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 原规范化用例原样迁移：输入、输出与 dropped 顺序不变。 */
class ImportAddressesTest {
    @TempDir Path dir;

    // ==================== 1. 地址规范化 ====================

    @Test
    void normalizeMergesExactDuplicates() {
        ImportAddresses.NormalizeResult r =
                ImportAddresses.normalizeAddresses(List.of(dir.toString(), dir.toString()));

        assertEquals(List.of(dir.toString()), r.kept(), "完全重复的地址应只保留一份");
        assertEquals(List.of(dir.toString()), r.duplicates(), "重复地址应作为'重复'事件回传（供调用方打 .06）");
        assertTrue(r.coveredByAncestor().isEmpty(), "重复合并不得产生'被祖先覆盖'事件");
    }

    @Test
    void normalizeMergesDescendantIntoAncestor() {
        String root = dir.toString();
        String child = dir.resolve("sub").toString();

        ImportAddresses.NormalizeResult r = ImportAddresses.normalizeAddresses(List.of(root, child));

        assertEquals(List.of(root), r.kept(), "祖先+后代应只留祖先，防止重复扫描");
        assertEquals(List.of(new ImportAddresses.DroppedAddress(child, root)),
                r.coveredByAncestor(), "后代应作为'被祖先覆盖'事件回传（供调用方打 .07，实参含祖先）");
    }

    @Test
    void normalizeDropsDescendantSilentlyWhenAncestorComesLater() {
        String root = dir.toString();
        String child = dir.resolve("sub").toString();

        ImportAddresses.NormalizeResult r = ImportAddresses.normalizeAddresses(List.of(child, root));

        assertEquals(List.of(root), r.kept(), "祖先后出现时应合并为祖先并丢掉先前的后代");
        assertTrue(r.dropped().isEmpty(),
                "原实现在这条路径上没有日志（removeIf 静默移除）——不得回传事件，否则会多出用户可见日志行");
    }

    @Test
    void normalizeKeepsEventOrderOfInput() {
        String root = dir.toString();
        String child = dir.resolve("sub").toString();

        // 输入顺序：祖先 → 后代(被覆盖) → 重复祖先
        ImportAddresses.NormalizeResult r =
                ImportAddresses.normalizeAddresses(List.of(root, child, root));

        assertEquals(List.of(root), r.kept());
        assertEquals(List.of(new ImportAddresses.DroppedAddress(child, root),
                        new ImportAddresses.DroppedAddress(root, null)),
                r.dropped(),
                "事件必须按输入顺序排列（原实现是一个循环里交替打 .06/.07，顺序变了就是用户可见的行为变化）");
    }

    @Test
    void normalizeIgnoresNullBlankAndEmpty() {
        ImportAddresses.NormalizeResult r =
                ImportAddresses.normalizeAddresses(java.util.Arrays.asList(null, "", "   ", dir.toString()));

        assertEquals(List.of(dir.toString()), r.kept(), "null/空串/纯空白应被忽略");
        assertTrue(r.dropped().isEmpty(), "被忽略的空白地址不产生事件（原实现也没有日志）");
        assertTrue(ImportAddresses.normalizeAddresses(null).kept().isEmpty(), "null 列表应返回空结果而不是抛异常");
    }

    @Test
    void normalizeKeepsInvalidPathVerbatim() {
        String invalid = "C:\\bad\0addr";                 // NUL 字符 ⇒ Path.of 抛 InvalidPathException
        String good = dir.toString();

        ImportAddresses.NormalizeResult r =
                ImportAddresses.normalizeAddresses(List.of(invalid, good));

        assertEquals(List.of(invalid, good), r.kept(),
                "无效路径格式必须原样保留（由扫描器按 unreadable 计数），不得被判定为可合并");
        assertTrue(r.dropped().isEmpty(), "无效路径不产生合并事件");
        assertFalse(ImportAddresses.isDescendantOf(good, invalid), "无效路径的包含判定应为 false");
        assertFalse(ImportAddresses.isDescendantOf(invalid, good), "无效路径的包含判定应为 false");
    }

}
