package com.mhtextractor.util;

import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺口单测（测试轮 2026-10-05）：{@link GroupStructure#asPreviewGroup} 是 E1.3 收敛后的
 * unchecked 转换**唯一收口点**（WorkspaceModel / ListInteractionController 的旧副本已删除），
 * 此前没有直接单测，只有经由 WorkspaceModel / GroupCommand 的间接覆盖。
 *
 * <p>本测试钉死两条语义：① 合法组恒等返回（不改成员）；② 成员含非 {@link PreviewItem} 时
 * <b>立即</b>抛 {@link IllegalStateException}，消息含组名与具体成员（避免堆污染导致的
 * ClassCastException 在远离根因处爆发）。</p>
 */
class GroupStructureAsPreviewGroupTest {

    private static PreviewItem item(String name) {
        return new PreviewItem("C:/x/" + name, name, LocalDateTime.now(), "C:/x");
    }

    @Test
    void validGroupIsReturnedAsTheSameInstanceWithoutTouchingMembers() {
        GroupItem<Object> raw = new GroupItem<>(1, "组1");
        raw.getMembers().add(item("a.mht"));
        raw.getMembers().add(item("b.mht"));

        GroupItem<PreviewItem> converted = GroupStructure.asPreviewGroup(raw);

        assertSame(raw, converted, "转换必须恒等返回同一对象");
        assertEquals(2, converted.getMembers().size(), "成员不得被增删");
        assertEquals("a.mht", converted.getMembers().get(0).name);
    }

    @Test
    void emptyGroupIsAllowed() {
        GroupItem<Object> raw = new GroupItem<>(2, "空组");
        assertSame(raw, GroupStructure.asPreviewGroup(raw));
        assertTrue(raw.getMembers().isEmpty());
    }

    @Test
    void nonPreviewItemMemberThrowsImmediatelyWithGroupNameAndMember() {
        GroupItem<Object> raw = new GroupItem<>(3, "坏组");
        raw.getMembers().add(item("ok.mht"));
        raw.getMembers().add("not-a-preview-item");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> GroupStructure.asPreviewGroup(raw));
        assertTrue(ex.getMessage().contains("坏组"), "异常消息必须含组名，实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("not-a-preview-item"), "异常消息必须含具体成员，实际：" + ex.getMessage());
    }
}
