package com.mhtextractor.uiport;

import java.util.List;
import java.util.Objects;

/**
 * 分组视图模型（不可变 record，自持；不引用任何业务类型）。
 *
 * @param no           组序号（工作区内唯一）
 * @param name         组名
 * @param expanded     展开状态（后端持有，影响投影与单元规划）
 * @param defaultNamed 是否默认命名（未命名组拦截门判定用）
 * @param members      成员条目（{@link List#copyOf} 防御性不可变拷贝）
 */
public record GroupVm(long no, String name, boolean expanded, boolean defaultNamed,
                      List<ItemVm> members, List<String> sourceFolders) {

    public GroupVm(long no, String name, boolean expanded, boolean defaultNamed, List<ItemVm> members) {
        this(no, name, expanded, defaultNamed, members, members.stream().filter(m -> !m.removed())
                .map(m -> m.sourceFolder() == null || m.sourceFolder().isEmpty() ? null : m.sourceFolder())
                .distinct().toList());
    }

    public GroupVm {
        Objects.requireNonNull(name, "name");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        sourceFolders = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(sourceFolders));
    }
}
