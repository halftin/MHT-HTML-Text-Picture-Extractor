package com.mhtextractor.uiport;

import java.util.Objects;

/**
 * 列表条目视图模型（不可变 record，自持；不引用任何业务类型）。
 *
 * @param id        条目 id = 文件路径（工作区内的唯一标识）
 * @param name      显示名（文件名）
 * @param checked   勾选状态（提取输入事实；复选框展示语义属前端）
 * @param removed   移除标记（隐藏式移除：不实际从列表删除，仅隐藏并排除在序号/提取名单外）
 * @param unitIndex 输出单元序号（null = 未参与输出单元规划，如已移除条目）
 * @param update    列表动画类别（{@link UpdateKind}）
 */
public record ItemVm(String id, String name, boolean checked, boolean removed,
                     Integer unitIndex, UpdateKind update, String sourceFolder) {

    public ItemVm(String id, String name, boolean checked, boolean removed,
                  Integer unitIndex, UpdateKind update) {
        this(id, name, checked, removed, unitIndex, update, null);
    }

    public ItemVm {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(update, "update");
    }
}
