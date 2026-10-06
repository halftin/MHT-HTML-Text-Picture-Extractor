package com.mhtextractor.uiport;

/**
 * 参数作用域目标寻址（B4）：条目按路径、组按组号 —— 端口层<b>不持有</b> {@code GroupItem} /
 * {@code PreviewItem} 引用（与 B3 的 {@code setChecked/group} 同一寻址口径）。二者恰一非空。
 *
 * <p>用于 {@link WorkspaceCommands#clearParams} 的回退目标与
 * {@link WorkspaceCommands#applyGlobalParams} 的目标。</p>
 *
 * @param path    条目路径（导入去重后的唯一键）；组目标时为 {@code null}
 * @param groupNo 组号（后端组号计数器全局唯一）；条目目标时为 {@code null}
 */
public record TargetId(String path, Long groupNo) {

    public TargetId {
        if ((path == null) == (groupNo == null)) {
            throw new IllegalArgumentException("path 与 groupNo 恰一非空（条目按路径、组按组号）");
        }
    }

    /** 条目目标寻址。 */
    public static TargetId ofItem(String path) {
        return new TargetId(path, null);
    }

    /** 组目标寻址。 */
    public static TargetId ofGroup(long groupNo) {
        return new TargetId(null, groupNo);
    }
}
