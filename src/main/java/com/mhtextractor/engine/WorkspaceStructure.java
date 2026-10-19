package com.mhtextractor.engine;

import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.GroupStructure;

import java.util.Collection;

/**
 * 分组结构规则入口（P3-3b-18）：{@code gui} 对 {@code util.GroupStructure} 的全部静态调用经本类 1:1 收口 ——
 * 参数顺序、返回语义与底层完全一致（只搬不改），gui 层不再直连 {@code util.GroupStructure}。
 *
 * <p>本类<b>零 JavaFX、零 I18n</b>，属 {@code app} 层。</p>
 */
public final class WorkspaceStructure {

    private WorkspaceStructure() {
    }

    /**
     * 把 {@code GroupItem<?>} 安全转换为 {@code GroupItem<PreviewItem>}（unchecked 转换的唯一收口点，E1.3）。
     * 1:1 转发 {@link GroupStructure#asPreviewGroup}（验证失败立即抛异常，与底层一致）。
     */
    public static GroupItem<PreviewItem> asPreviewGroup(GroupItem<?> g) {
        return GroupStructure.asPreviewGroup(g);
    }

    /**
     * 组名唯一化（R7）：与现有组名冲突时自动追加序号直至唯一（组1 → 组1(2) → 组1(3)…）。
     * 1:1 转发 {@link GroupStructure#uniqueGroupName}（null / 空白名原样透传，与底层一致）。
     */
    public static String uniqueGroupName(String desired, Collection<String> existing) {
        return GroupStructure.uniqueGroupName(desired, existing);
    }
}
