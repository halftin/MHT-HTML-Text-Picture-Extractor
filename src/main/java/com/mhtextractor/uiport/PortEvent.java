package com.mhtextractor.uiport;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 端口事件（sealed）：工作区结构变更 / 运行状态 / 提醒。
 *
 * <p>文案一律「键 + 具名参数」（方案 §14 决策 13），渲染属前端；本包不得依赖任何国际化工具类。</p>
 */
public sealed interface PortEvent {

    /** Cache measurements are bytes, never rounded GiB; revision is session-local. */
    record CacheSizeAlert(int step, long actualBytes, long stepBytes, String root,
                          String key, Map<String, String> namedParams, String level, long revision)
            implements PortEvent {
        public CacheSizeAlert { namedParams = Map.copyOf(namedParams); }
    }

    /** Backend diagnostics retain the bundle key and named (including numeric) placeholders. */
    record BackendLog(String level, String key, Map<String, String> namedParams,
                      long timestamp, long revision) implements PortEvent {
        public BackendLog { namedParams = Map.copyOf(namedParams); }
    }

    /**
     * 参数状态变更类别（B4）：后端参数命令完成「写入 + 撤销压栈 + 磁盘缓存失效（规则 a）」后
     * 按此类别发布 {@link ParamStateChanged}；前端据此决定列表刷新与参数色刷新的粒度。
     */
    enum ParamChangeKind {
        /** 全局已确认参数写入（{@code applyConfirmedParams} 语义）：全部条目受影响 */
        SET_GLOBAL,
        /** One batch of backend-resolved targets, with one undo record. */
        SET_TARGETS,
        /** 组参数写入（{@code applyGroupParams} 语义）：该组未移除成员受影响 */
        SET_GROUP,
        /** 条目级参数写入（{@code applyItemParams} 语义）：该条目受影响 */
        SET_ITEM,
        /** 目标生效参数应用到全局（{@code applyGlobalParamsTo} 语义）：全部条目受影响 */
        APPLY_GLOBAL,
        /** 清除参数（{@code clearParamsForTargets} 语义，单条撤销）：被清除目标及组内成员受影响 */
        CLEAR
    }

    /**
     * 参数状态变更（B4）：后端已完成参数写入、撤销压栈与磁盘缓存失效；
     * 前端按 {@link #kind()} 执行对应的列表刷新与参数色刷新链（事件在命令线程同步送达）。
     *
     * @param kind          变更类别
     * @param imageMode     本次变更作用的模式（true = 图片，false = 文本）
     * @param affectedPaths 受影响条目路径（SET_GLOBAL / APPLY_GLOBAL = 全部条目含已移除；
     *                      其余 = 实际受影响条目；不可变副本）
     */
    record ParamStateChanged(ParamChangeKind kind, boolean imageMode, List<String> affectedPaths)
            implements PortEvent {
        public ParamStateChanged {
            Objects.requireNonNull(kind, "kind");
            affectedPaths = List.copyOf(Objects.requireNonNull(affectedPaths, "affectedPaths"));
        }
    }

    /** 取消或全量预解析推进代际；接收方应丢弃旧代际的排队回调。 */
    record WorkspaceActivityChanged(long generation) implements PortEvent {}

    record PreparseProgress(long generation, int done, int total) implements PortEvent {}

    // ---- B5-1：导入编排事件 ----

    /**
     * 导入在途状态变更（B5-1）：{@code importPaths} 建会话后置 true（前端置位「导入中」并禁用开始提取）；
     * 收尾唯一恢复点置 false。generation = 导入会话代际（每次 {@code importPaths} 自增）。
     */
    record ImportStateChanged(long generation, boolean importing) implements PortEvent {}

    /**
     * 一轮导入落地（B5-1）：后端已完成本轮全部模型命令（建组/平铺/追加）与撤销压栈
     * （<b>每轮恰好一条</b>，BC-5），前端据此执行列表全量刷新（每轮一次，RISK-8）、
     * 新建组的名字刷新挂接（F8/RISK-11）与「首轮列表由空转非空」的首行选中。
     *
     * @param generation       导入会话代际
     * @param plan             本轮计划（端口视图模型；含配额/剩余标志）
     * @param newFiles         本轮实际落地的新条目数（已去重；跳过的批次不计）
     * @param newGroups        本轮自动新建的组数
     * @param madeListNonEmpty 本轮首个非跳过批次应用前列表为空（导入首轮选中首行的判定）
     * @param createdGroups    本轮新建的组（模型对象，按落地顺序；前端挂接名字刷新监听）
     * @param groupSnapshots   IPC 新建组的不可变 VM；IPC 不重造模型身份，createdGroups 留空
     */
    record ImportRound(long generation, ImportPlanVm plan, int newFiles, int newGroups,
                       boolean madeListNonEmpty,
                       List<com.mhtextractor.model.GroupItem<com.mhtextractor.model.PreviewItem>> createdGroups,
                       @JsonInclude(JsonInclude.Include.NON_EMPTY) List<GroupVm> groupSnapshots)
            implements PortEvent {
        public ImportRound(long generation, ImportPlanVm plan, int newFiles, int newGroups,
                           boolean madeListNonEmpty,
                           List<com.mhtextractor.model.GroupItem<com.mhtextractor.model.PreviewItem>> createdGroups) {
            this(generation, plan, newFiles, newGroups, madeListNonEmpty, createdGroups, List.of());
        }
        public ImportRound {
            createdGroups = List.copyOf(Objects.requireNonNull(createdGroups, "createdGroups"));
            groupSnapshots = List.copyOf(groupSnapshots);
        }
    }

    /**
     * 导入收尾（B5-1）：载荷 = 本次导入全量累计条目的绝对路径（RISK-7：导入期间抑制、
     * 收尾跑一次，避免 O(n²)）。前端判「非空 + 当前模式参数已确认」后调
     * {@code commands().startPreparseFor(paths)} —— 与迁移前 {@code hasConfirmedParams} 门同语义。
     */
    record ImportFinished(long generation, List<String> accumulatedPaths) implements PortEvent {
        public ImportFinished {
            accumulatedPaths = List.copyOf(Objects.requireNonNull(accumulatedPaths, "accumulatedPaths"));
        }
    }

    record PreparseStarted(long generation) implements PortEvent {}

    /** 每完成一个条目交付一次，包括解析失败；不是整批结束通知。 */
    record PreparseFinished(long generation, PreparseResult result) implements PortEvent {}

    /** generation 是独立的预览请求代际；旧任务不得终结新任务的在途状态。 */
    record PreviewLoadStateChanged(long generation, String path, boolean active) implements PortEvent {}

    record PreviewConfirmationRequired(String path, String name, long sizeMiB) implements PortEvent {}

    record PreviewLoadFailed(long generation, String path, boolean fileInfo, String detail) implements PortEvent {}

    /** imageFile != null selects the IPC encoded-file representation; local pixels remain frontend-owned. */
    record PreviewThumbnail(long generation, String path, int index,
                            com.mhtextractor.model.ThumbnailData thumbnail,
                            @JsonInclude(JsonInclude.Include.NON_NULL) ImageFile imageFile) implements PortEvent {
        public PreviewThumbnail(long generation, String path, int index, com.mhtextractor.model.ThumbnailData thumbnail) {
            this(generation, path, index, thumbnail, null);
        }
    }

    /** Encoded original cache image, with its real format. The frontend decodes/scales it locally. */
    record ImageFile(String path, long bytes, String mediaType, String extension, String coreName,
                     int width, int height, int index,
                     @JsonInclude(JsonInclude.Include.NON_NULL) String displayName,
                     @JsonInclude(JsonInclude.Include.NON_NULL) Dimensions dimensions,
                     @JsonInclude(JsonInclude.Include.NON_NULL) String imagePath) {
        public record Dimensions(int width, int height) {}
        public ImageFile(String path, long bytes, String mediaType, String extension, String coreName,
                         int width, int height, int index, String displayName) {
            this(path, bytes, mediaType, extension, coreName, width, height, index, displayName,
                    new Dimensions(width, height), path);
        }
        public ImageFile(String path, long bytes, String mediaType, String extension, String coreName,
                         int width, int height, int index) {
            this(path, bytes, mediaType, extension, coreName, width, height, index, null, null, null);
        }
    }

    record PreviewPayload(String text, String html, List<ImageFile> images) {
        public PreviewPayload { images = List.copyOf(images); }
    }

    /** Terminal/repaint signal, also emitted on cancellation or stale completion.
     * A null thumbnail cache means return to the lazy prompt, never reenter loading. */
    record PreviewLoaded(long generation, String path,
                         @JsonInclude(JsonInclude.Include.NON_NULL) PreviewPayload payload) implements PortEvent {
        public PreviewLoaded(long generation, String path) { this(generation, path, null); }
    }

    record PreviewRegionConfirmationRequired(long generation, String path) implements PortEvent {}

    record PreviewNotice(long generation, String path, String key,
                         @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String,String> namedParams) implements PortEvent {
        public PreviewNotice(long generation, String path, String key) { this(generation,path,key,Map.of()); }
        public PreviewNotice { namedParams = Map.copyOf(namedParams); }
    }

    // ---- B6：提取任务通道（矩阵处置项 1）----

    /**
     * 提取任务通道事件族（B6）：任务代际 / 终态恰好一次 / 取消竞态 / 结果通道的端口契约 ——
     * {@link ExtractionProgress} / {@link ExtractionTerminal} / {@link ExtractionResult}
     * 都携带递增的 run 代际；旧任务迟到的投递由后端代际守卫拦下（不得污染新任务）。
     */
    non-sealed interface ExtractionChannelEvent extends PortEvent {
    }

    /**
     * 跨单元总进度（0.0~1.0，只增不减；后端 worker 的进度回调经派发器投递）。
     *
     * @param generation run 代际
     * @param overall    总进度
     */
    record ExtractionProgress(long generation, double overall) implements ExtractionChannelEvent {
    }

    /**
     * 提取终态（B6）：<b>恰好一次</b> —— 成功 / 失败在 worker 收尾后交付；取消在
     * {@code cancelExtraction()} 的<b>取消请求时刻</b>交付（取消请求 ≠ worker 已退出）。
     * 前端据此复位停止/开始按钮并渲染结果。
     *
     * @param generation          run 代际
     * @param end                 终态类别
     * @param stats               统计快照（仅 {@link ExtractionEnd#SUCCEEDED} 非 null；
     *                             取消路径的 partial 经 {@link ExtractionResult} 单独交付）
     * @param sessionOutputFolder 本次会话输出目录（成功路径"完成后打开输出目录"用）
     */
    record ExtractionTerminal(long generation, ExtractionEnd end,
                              com.mhtextractor.model.ExtractionStats stats,
                              String sessionOutputFolder) implements ExtractionChannelEvent {
    }

    /**
     * 取消路径的 partial 回写（B6）：worker 完成处理（取消请求之后）恰好投递一次，
     * 载荷 = worker 返回的统计；成功路径统计随 {@link ExtractionTerminal} 走，不重复投递。
     *
     * @param generation run 代际
     * @param stats      已处理单元的 partial 统计
     * @param sessionOutputFolder 本 run 请求冻结的会话输出目录
     */
    record ExtractionResult(long generation,
                            com.mhtextractor.model.ExtractionStats stats,
                            String sessionOutputFolder) implements ExtractionChannelEvent {
        public ExtractionResult(long generation, com.mhtextractor.model.ExtractionStats stats) {
            this(generation, stats, null);
        }
    }

    /** 提取终态类别（B6）：成功 / 失败 / 取消，每任务恰好交付其一。 */
    enum ExtractionEnd {
        /** worker 正常收尾（统计随终态事件交付） */
        SUCCEEDED,
        /** worker 异常收尾（无统计；后端已打 {@code log.maincontroller.37}，前端渲染错误卡片） */
        FAILED,
        /** 取消请求时刻交付（无统计；partial 经 {@link ExtractionResult} 在 worker 收尾后投递） */
        CANCELLED
    }

    /**
     * 工作区结构化变更（数据序 / 分组 / 勾选 / 参数 / 撤销等）。
     *
     * @param kind 变更类别（类别枚举留后续批次定稿；前端据此决定刷新粒度）
     */
    record WorkspaceChanged(String kind,
                            @JsonInclude(JsonInclude.Include.NON_NULL) WorkspaceVm snapshot,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String undoKind,
                            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<TargetId> affected,
                            @JsonInclude(JsonInclude.Include.NON_NULL) TargetId suggestedSelection) implements PortEvent {
        public WorkspaceChanged(String kind, WorkspaceVm snapshot) { this(kind, snapshot, null, List.of(), null); }
        public WorkspaceChanged(String kind) { this(kind, null); }
        public WorkspaceChanged {
            Objects.requireNonNull(kind, "kind");
            affected = List.copyOf(affected);
        }
    }

    /**
     * 提取在途状态变更。
     *
     * @param running 提取是否在途
     * @param generation run 代际；false 不表示 worker 已退出
     */
    record RunningChanged(boolean running, long generation) implements PortEvent {
        public RunningChanged(boolean running) { this(running, 0); }
    }

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
