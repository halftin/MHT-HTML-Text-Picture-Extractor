package com.mhtextractor.uiport;

/**
 * 工作区命令（P3-3a 最小切片起，B3 扩至勾选与分组命令集，B4 扩至参数命令族，B5-1 扩至导入编排，
 * B6 扩至提取任务通道）：
 * 撤销、清场、勾选（{@code setChecked}）、分组（{@code group/ungroup/renameGroup}）、
 * 展开状态（{@code toggleGroupExpanded}）、参数应用/清除
 * （{@code setGlobalParams/setGroupParams/setItemParams/clearParams/applyGlobalParams}）、
 * 导入编排（{@code importPaths/applyImportPlan}）与提取任务通道
 * （{@code startExtraction/cancelExtraction}）。
 *
 * <p>命令语义由后端保证（数据序 / 分组关系 / 勾选 / 展开状态 / 撤销命令的唯一所有者是后端模型）；
 * 命令生效后经 {@link PortEvents} 推送变更事件，前端据此刷新投影。B3 的勾选 / 组名 /
 * 展开写入是<b>静默</b>的（不发布事件）：它们不改变数据序/结构，投影刷新由前端
 * 在命令返回后显式重建（GUI 的抽屉动画在动画起始帧重建显示序）。B4 的参数命令
 * 是<b>事件驱动</b>的：后端完成「写入 + 撤销压栈 + 磁盘缓存失效」后发布
 * {@link PortEvent.ParamStateChanged}，前端按事件执行列表 / 参数色刷新。</p>
 *
 * <p>后台活动代际、预解析和预览加载由应用层持有。</p>
 */
public interface WorkspaceCommands {

    /** Named parameters only; overwrite and filename rules match the existing persistence service. */
    default void saveNamedConfig(String name, com.mhtextractor.model.AppConfig config) throws java.io.IOException {
        throw new UnsupportedOperationException("Named configs are not wired");
    }
    /** Missing names and filesystem failures throw IOException. No workspace event is emitted. */
    default void deleteNamedConfig(String name) throws java.io.IOException {
        throw new UnsupportedOperationException("Named configs are not wired");
    }

    default void setTitleOverride(String path, String title) { throw new UnsupportedOperationException("Title editing is not wired"); }
    default void applyParamsToTargets(boolean imageMode, com.mhtextractor.model.AppConfig params,
                                      java.util.List<TargetId> fallbackTargets) {
        throw new UnsupportedOperationException("Batch parameters are not wired");
    }

    void cancelWorkspaceActivity();
    void cancelPreviewLoad();
    void startPreparse();
    void startPreparseFor(java.util.List<String> paths);
    void refreshPreparseProgress();
    void loadPreview(String path, boolean largeFileConfirmed);
    void resolvePreviewRegion(long generation, String path, boolean accepted);
    void awaitPreviewInFlight(long millis);

    // ---- B3：勾选命令 + 分组命令集（处置项 6 / 5）----

    /**
     * 勾选写入 —— 提取输入事实的<b>唯一写入口</b>：按路径解析条目并批量写入
     * （路径是导入去重后的唯一键）。
     *
     * <p><b>静默写入</b>：不触发任何变更事件 / 滚动 / 焦点；未知路径<b>静默跳过</b>。
     * 三态（{@link TriState}）由查询 {@code workspace().selection()} 按当前勾选事实导出。</p>
     */
    void setChecked(java.util.List<String> paths, boolean checked);

    /** 建组：按路径解析条目（保持入参顺序）合并为一个新组；组名唯一化由后端保证。目标为空或均不可解析时为 no-op。 */
    void group(java.util.List<String> paths, String namePrefix);

    /** 拆分组：组从顶层移除，成员按原组内顺序插回原位置（组参数下发为成员条目级参数）；组不存在时为 no-op。 */
    void ungroup(long groupNo);

    /** 组改名：新名按后端组名集合唯一化后写入并永久退出「未命名组」提醒；组不存在时为 no-op。 */
    void renameGroup(long groupNo, String newName);

    /**
     * 折叠/展开翻转（幂等）：每调用恰好翻转一次展开标志 —— 状态由后端持有并按其当前值原子翻转，
     * 调用方不传目标值；快速连续调用（双击连点）=「翻过去再翻回来」，不会基于陈旧读数
     * 重复应用同一目标值。组不存在时为 no-op。
     */
    void toggleGroupExpanded(long groupNo);

    // ---- B4：参数命令族（处置项 3 / 4）----

    /**
     * 全局参数确认写入（{@code applyConfirmedParams} 语义）：整体替换当前模式已确认参数缓存，
     * 压一条 PARAM_SET 撤销记录（批量应用期间经剪贴板累积为整批一条），失效全部条目磁盘缓存
     * （规则 a），并发布 {@code ParamStateChanged(SET_GLOBAL, 全部条目路径)}。
     */
    void setGlobalParams(boolean imageMode, com.mhtextractor.model.AppConfig params);

    /**
     * 组参数写入（{@code applyGroupParams} 语义）：写入该组当前模式参数缓存，压 PARAM_SET
     * 撤销记录，失效该组未移除成员的磁盘缓存，并发布 {@code ParamStateChanged(SET_GROUP, 成员路径)}。
     * 组不存在时为 no-op（不发布事件、不压撤销记录）。
     */
    void setGroupParams(long groupNo, boolean imageMode, com.mhtextractor.model.AppConfig params);

    /**
     * 条目级参数写入（{@code applyItemParams} 语义）：写入该条目当前模式条目级参数，压 PARAM_SET
     * 撤销记录，失效该条目磁盘缓存，并发布 {@code ParamStateChanged(SET_ITEM, [路径])}。
     * 路径不存在时静默跳过（不发布事件、不压撤销记录）。
     */
    void setItemParams(String path, boolean imageMode, com.mhtextractor.model.AppConfig params);

    /**
     * 清除参数（{@code clearParamsForTargets} 语义）：<b>勾选优先</b> —— 后端按当前勾选事实
     * （可见条目）解析实际作用集合，勾选为空时回退到 {@code fallbackTargets}（右键命中 /
     * 当前选中的条目或组）；无参数目标自然跳过；一次操作一条 CLEAR_PARAMS 撤销记录
     * （before 快照，一次撤销全部恢复）。发布 {@code ParamStateChanged(CLEAR, 受影响条目路径)}；
     * 无任何可清除目标时为 no-op（不发布事件、不压撤销记录）。
     */
    void clearParams(java.util.List<TargetId> fallbackTargets, boolean imageMode);

    /**
     * 应用到全局（{@code applyGlobalParamsTo} 语义）：把目标（条目或组）的生效参数写入当前模式
     * 全局缓存，并清空当前模式全部独立参数（条目级 + 组，含目标自身），另一模式不受影响；
     * 压一条 APPLY_GLOBAL 撤销记录（应用前完整参数快照）。发布
     * {@code ParamStateChanged(APPLY_GLOBAL, 全部条目路径)}；目标不可解析或生效参数为空时为
     * no-op（不发布事件、不压撤销记录）。
     */
    void applyGlobalParams(TargetId target, boolean imageMode);

    /** 撤销上一条工作区命令（无可撤销时为 no-op）。 */
    void undo();

    // ---- B5-1：导入编排 ----

    /**
     * 统一导入编排（B5-1）：把导入会话的<b>生命周期与界面等待</b>整体后端化 —— 会话/轮次扫描
     * （{@code mht-import-scan} 后台守护线程）、配额确认往返（经 {@link Confirmer}）、自动建组命名、
     * 撤销压栈（<b>一次导入的每一轮恰好一条</b>，载荷 = 含本轮的全量累计，BC-5）与唯一收尾都在后端；
     * 规则底座为 {@code WorkspaceImportService}（规范化 / 去重 / 条目构造 / 累计 / 组名计算）。
     *
     * <p><b>调用方前置（与迁移前 MainController 一致，不变）</b>：地址已规范化（去重 / 祖先覆盖）、
     * 空地址与导入在途已拦截、拖拽来源盘符根确认（BC-8）已完成；本命令只负责「开始会话 → 发布
     * {@link PortEvent.ImportStateChanged}(importing=true) → 起扫描线程」。前端经
     * {@link PortEvent.ImportRound} / {@link PortEvent.ImportFinished} 事件驱动刷新。</p>
     */
    void importPaths(java.util.List<String> addresses, String sourceDesc, boolean fromDrag);

    /**
     * 分轮导入第二拍（B5-1）：程序化驱动一轮计划（{@link ImportPlanVm}）落地 —— 去重 / 条目构造 /
     * 累计 / 组名规则在 {@code WorkspaceImportService}，模型命令与撤销压栈在后端模型。
     * 无进行中的导入会话时后端按需建一个「空地址」会话（与迁移前控制器长期存在的累计字段等价），
     * 不启动线程、不置位导入标志。本轮落地后发布 {@link PortEvent.ImportRound}。
     */
    void applyImportPlan(ImportPlanVm plan);

    // ---- B6：提取任务通道（矩阵处置项 1）----

    /**
     * 启动提取任务（B6）：deny 链 / 门链 / UI 确认卡在前端（它们渲染卡片），<b>请求构建与任务通道
     * 在后端</b> —— 后端完成「运行时配置 → 会话目录 → 输出单元计划 → 不可变请求快照」并启动
     * worker；此后一切经 {@link PortEvent.ExtractionChannelEvent} 族事件驱动
     * （进度 {@code ExtractionProgress}、终态 {@code ExtractionTerminal}、取消 partial
     * {@code ExtractionResult}）。任务通道契约（每任务一代际、终态恰好一次、取消请求 ≠ worker
     * 已退出、取消后立即再开不互相污染）由后端保证。
     *
     * <p><b>调用方前置（与迁移前 {@code startExtractionTask} 一致，不变）</b>：deny 链与顺序门
     * 已放行、「只提取选中」的旧结果色清理与界面锁定（开始/停止按钮、进度条归零）已完成；
     * 本命令只在宿主串行线程（生产 = FX）调用。</p>
     *
     * @param checkedOnly 是否只提取列表中被勾选的项目（"开始提取"点击时快照）
     */
    void startExtraction(boolean checkedOnly);

    /**
     * 取消在途提取任务（B6；无在途任务或 worker 已收尾时为空操作）：取消请求时刻立即交付
     * {@code ExtractionTerminal(CANCELLED)}（前端据此复位停止/开始按钮），partial 回写经
     * {@code ExtractionResult} 在 worker 收尾后投递一次。在宿主串行线程（生产 = FX）调用。
     */
    void cancelExtraction();

    /**
     * 清空工作区数据序（组随数据序一并清空；撤销栈与组号计数器不动 —— 与后端
     * {@code WorkspaceModel#clearItems()} 的既有清场语义一致）。
     */
    void clearItems();

    /** Stable-ID drop; returns changed state and affected paths after backend postprocessing. */
    default DropVm applyDrop(TargetId dragged, TargetId target, String zone) {
        throw new UnsupportedOperationException("Drop is not wired");
    }

    default void removeItems(java.util.List<String> paths) { throw new UnsupportedOperationException("Removal is not wired"); }
    default void removeGroup(long groupNo) { throw new UnsupportedOperationException("Removal is not wired"); }
}
