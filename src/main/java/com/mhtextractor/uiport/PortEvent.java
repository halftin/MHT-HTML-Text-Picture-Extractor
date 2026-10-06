package com.mhtextractor.uiport;

import java.util.Map;
import java.util.Objects;

/**
 * 端口事件（sealed）：工作区结构变更 / 运行状态 / 提醒。
 *
 * <p>文案一律「键 + 具名参数」（方案 §14 决策 13），渲染属前端；本包不得依赖任何国际化工具类。</p>
 */
public sealed interface PortEvent {

    /** 取消或全量预解析推进代际；接收方应丢弃旧代际的排队回调。 */
    record WorkspaceActivityChanged(long generation) implements PortEvent {}

    record PreparseProgress(long generation, int done, int total) implements PortEvent {}

    record PreparseStarted(long generation) implements PortEvent {}

    /** 每完成一个条目交付一次，包括解析失败；不是整批结束通知。 */
    record PreparseFinished(long generation, PreparseResult result) implements PortEvent {}

    /** generation 是独立的预览请求代际；旧任务不得终结新任务的在途状态。 */
    record PreviewLoadStateChanged(long generation, String path, boolean active) implements PortEvent {}

    record PreviewConfirmationRequired(String path, String name, long sizeMiB) implements PortEvent {}

    record PreviewLoadFailed(long generation, String path, boolean fileInfo, String detail) implements PortEvent {}

    record PreviewThumbnail(long generation, String path, int index,
                            com.mhtextractor.model.ThumbnailData thumbnail) implements PortEvent {}

    record PreviewLoaded(long generation, String path) implements PortEvent {}

    /**
     * 工作区结构化变更（数据序 / 分组 / 勾选 / 参数 / 撤销等）。
     *
     * @param kind 变更类别（类别枚举留后续批次定稿；前端据此决定刷新粒度）
     */
    record WorkspaceChanged(String kind) implements PortEvent {
        public WorkspaceChanged {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * 提取在途状态变更。
     *
     * @param running 提取是否在途
     */
    record RunningChanged(boolean running) implements PortEvent {}

    /**
     * 提醒（前端按 {@link NoticeLevel} 选择呈现形式：错误居中必点 / 警告·信息右上 toast）。
     *
     * @param key         文案键
     * @param namedParams 具名参数（防御性不可变拷贝）
     * @param level       级别
     */
    record Notice(String key, Map<String, String> namedParams, NoticeLevel level) implements PortEvent {
        public Notice {
            Objects.requireNonNull(key, "key");
            namedParams = Map.copyOf(Objects.requireNonNull(namedParams, "namedParams"));
            Objects.requireNonNull(level, "level");
        }
    }
}
