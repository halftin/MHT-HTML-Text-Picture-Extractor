package com.mhtextractor.app;

import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.GroupStructure;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P3-3b-18：{@link WorkspaceStructure} 与底层 {@link GroupStructure} 同参同返对照
 * （含非法成员校验异常与组名唯一化逐例对照），保证 1:1 收口不引入语义偏差。
 * 零 JavaFX，可在 headless 上下文执行。
 */
class WorkspaceStructureTest {

    private static PreviewItem item(String path) {
        return new PreviewItem(path, path, LocalDateTime.of(2026, 10, 6, 0, 0), "src");
    }

    @Test
    void asPreviewGroupMatchesUnderlying() {
        GroupItem<PreviewItem> g = new GroupItem<>(1, "组1");
        g.getMembers().add(item("a.mht"));
        g.getMembers().add(item("b.mht"));
        // 只做校验 + unchecked 转换，返回同一实例
        assertSame(GroupStructure.asPreviewGroup(g), WorkspaceStructure.asPreviewGroup(g));
    }

    @Test
    void asPreviewGroupRejectsNonPreviewMembers() {
        GroupItem<Object> g = new GroupItem<>(2, "混合组");
        g.getMembers().add(item("a.mht"));
        g.getMembers().add("not-a-preview");
        assertThrows(IllegalStateException.class, () -> WorkspaceStructure.asPreviewGroup(g));
    }

    @Test
    void uniqueGroupNameMatchesUnderlying() {
        List<String> existing = List.of("组1", "组1(2)", "  ");
        for (String desired : Arrays.asList("组1", "组2", "组1(2)", "", "   ", null)) {
            assertEquals(GroupStructure.uniqueGroupName(desired, existing),
                    WorkspaceStructure.uniqueGroupName(desired, existing),
                    String.valueOf(desired));
        }
        // 强断言（控制组：包装返回常量必须跑红）
        assertEquals("组1(3)", WorkspaceStructure.uniqueGroupName("组1", new ArrayList<>(existing)),
                "冲突组1 已被占两档 → 下一档 (3)");
        assertEquals("组2", WorkspaceStructure.uniqueGroupName("组2", existing), "无冲突原样返回");
    }
}
