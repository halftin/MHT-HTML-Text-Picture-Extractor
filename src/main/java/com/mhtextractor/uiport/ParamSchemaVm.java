package com.mhtextractor.uiport;

import java.util.List;

/** 语言中立的表单描述。列表顺序即展示顺序；值只包含 Boolean / String。 */
public record ParamSchemaVm(boolean imageMode, List<Group> groups, List<Field> fields) {
    public ParamSchemaVm {
        groups = List.copyOf(groups);
        fields = List.copyOf(fields);
    }

    public enum Type { BOOLEAN, TEXT }

    public record Group(String id, String labelKey, String noteKey) { }

    /**
     * enabledBy=null 表示始终启用；否则关闭时恢复 disabledValue。
     * nullWhenDisabled 只影响收集值（图片区域标记）；模板回车插入字面量 \\n。
     * 旧表单接受任意标记（包括不完整正则），不附加合法性限制。
     */
    public record Field(String id, Type type, String labelKey, String group,
                        Object defaultValue, String enabledBy, Object disabledValue,
                        boolean nullWhenDisabled, String promptText, boolean templateEnter) { }
}
