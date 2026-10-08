package com.mhtextractor.app;

import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.uiport.Confirmer;
import com.mhtextractor.uiport.ConfirmRequest;
import com.mhtextractor.uiport.ConfirmToken;
import com.mhtextractor.uiport.ImportPlanVm;
import com.mhtextractor.uiport.NoticeLevel;
import com.mhtextractor.uiport.PortEvent;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 导入编排协调器（B5-1）：统一导入的<b>生命周期与界面等待</b>从 gui 层
 * {@code ImportSessionCoordinator} 整体下沉到 app 层 —— 后台扫描线程、轮次派发、
 * 配额确认往返、模型命令、撤销压栈（BC-5）与唯一收尾。
 * 导入的<b>规则</b>（地址规范化、轮次去重、组名计算、累计计数）在无头 {@link WorkspaceImportService}。
 *
 * <h2>下沉后的前端联动（与迁移前 {@code ImportUi} 十个回调逐条对应）</h2>
 * <ul>
 *   <li>会话开始 / 收尾 → {@link PortEvent.ImportStateChanged}（前端置位「导入中」并刷新开始按钮）；</li>
 *   <li>列表可见项快照 → 由模型推导（{@code allItems()} 中 {@code removed == false}），不再回调前端；</li>
 *   <li>收尾统一预解析 → {@link PortEvent.ImportFinished} 携带全量累计路径，前端判「非空 + 参数已确认」
 *       后调 {@code startPreparseFor}；</li>
 *   <li>每轮一次刷新 / 新建组名字刷新挂接 / 首行选中 → {@link PortEvent.ImportRound}；</li>
 *   <li>配额确认 → 经端口 {@link Confirmer} 确认往返（等待方仍是导入后台线程）；</li>
 *   <li>扫描异常提示 → {@link PortEvent.Notice}（键 {@code error.IMPORT_FAILED}）。</li>
 * </ul>
 *
 * <h2>线程约定（与迁移前一致，不得改）</h2>
 * <ul>
 *   <li>{@link #importPaths} 在宿主 UI 线程调用（生产 = FX 事件）；</li>
 *   <li>扫描循环跑在名为 {@code mht-import-scan} 的守护线程上；</li>
 *   <li>模型命令、列表刷新事件、收尾一律经<b>注入的派发器</b>（生产 = {@code Platform.runLater}）
 *       回到宿主线程；</li>
 *   <li>配额确认的<b>等待方是导入后台线程</b>（{@link ConfirmToken#await()}），不阻塞宿主线程 ——
 *       既有形态，别改。</li>
 * </ul>
 */
@Slf4j
public final class WorkspaceImportCoordinator {

    /** 配额确认往返的动作键（顺序即展示顺序；前端渲染按钮，后端按所选键判定继续 / 停止）。 */
    private static final String ACTION_CONTINUE = "import.quotaContinue";
    private static final String ACTION_STOP = "import.quotaStop";

    private final WorkspaceModel ws;
    private final Confirmer confirmer;
    /** 宿主派发器（生产 = {@code Platform::runLater}；测试注入延迟派发器，把任务放进队列由测试线程按序 drain） */
    private final Consumer<Runnable> dispatcher;
    /** 每轮配额（生产 = {@link MhtImportPlanner} 默认常量；测试注入小配额，用<b>真实扫描器</b>复现"每轮打满"） */
    private final int maxGroupsPerRound;
    private final int maxFilesPerRound;
    private final WorkspaceImportService service = new WorkspaceImportService();
    private final List<Consumer<PortEvent>> listeners = new CopyOnWriteArrayList<>();

    /**
     * 当前会话（即"本次导入的累计落点"）；{@code null} = 无进行中的导入。
     *
     * <p>程序化驱动一轮计划的路径（GUI 自动化直接调 {@code MainController.applyImportPlan}）
     * 会按需建一个"空地址"会话 —— 迁移前那是控制器上长期存在的 7 个字段，语义上等价于
     * "总有一个累计落点"，见 {@link #ensureSession()}。</p>
     */
    private volatile WorkspaceImportService.Session active;

    /** 导入会话代际：每次 {@link #importPaths} 自增（单线程：宿主线程），事件携带。 */
    private long generation;

    /**
     * 公开构造（生产路径）：派发走 {@code Platform.runLater}，配额用
     * {@link MhtImportPlanner} 的默认常量（与迁移前控制器私有常量同源）。
     */
    public WorkspaceImportCoordinator(WorkspaceModel ws, Confirmer confirmer, Consumer<Runnable> dispatcher) {
        this(ws, confirmer, dispatcher, MhtImportPlanner.DEFAULT_MAX_GROUPS, MhtImportPlanner.DEFAULT_MAX_FILES);
    }

    /**
     * 配额注入构造（可测试性注入缝 —— 属线程边界设计，不是测试让步）：派发器与配额均可注入。
     *
     * <p>测试注入"延迟派发器"（任务进队列、由测试线程按序 drain）模拟宿主 UI 线程被占用，从而
     * <b>确定性</b>复现"配额卡在 apply 落地前构造"的时序竞态；注入小配额则让真实扫描器每轮打满配额
     * （不必造 200 个组的目录树）。注入的配额必须与调用方打印的配额数一致
     * （见 {@link WorkspaceImportService#begin(List, String, boolean, int, int)}）。</p>
     */
    public WorkspaceImportCoordinator(WorkspaceModel ws, Confirmer confirmer, Consumer<Runnable> dispatcher,
                                      int maxGroupsPerRound, int maxFilesPerRound) {
        this.ws = Objects.requireNonNull(ws, "ws");
        this.confirmer = Objects.requireNonNull(confirmer, "confirmer");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.maxGroupsPerRound = maxGroupsPerRound;
        this.maxFilesPerRound = maxFilesPerRound;
    }

    /** 订阅导入编排事件（{@code close()} 即退订；由 {@code InProcessUiport.events()} 桥接到端口事件流）。 */
    public AutoCloseable subscribe(Consumer<PortEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void emit(PortEvent event) {
        for (Consumer<PortEvent> listener : listeners) {
            listener.accept(event);
        }
    }

    /**
     * 启动导入会话：建会话 → 发布「导入中」事件（前端置位开始按钮状态）→ 起后台扫描线程。
     *
     * <p>调用方（迁移前 MainController 不变）已完成：空检查、重复导入拦截
     * （{@code log.maincontroller.03}）、规范化与 {@code log.maincontroller.04}、BC-8 盘符根确认。</p>
     */
    public void importPaths(List<String> normalized, String sourceDesc, boolean fromDrag) {
        WorkspaceImportService.Session session =
                service.begin(normalized, sourceDesc, fromDrag, maxGroupsPerRound, maxFilesPerRound);
        active = session;
        generation++;
        emit(new PortEvent.ImportStateChanged(generation, true));

        Thread t = new Thread(() -> runRounds(session), "mht-import-scan");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 应用一个计划（一批或一轮）—— <b>宿主 UI 线程</b>；生产与自动化测试共用。
     *
     * <p>无会话时按需建一个"空地址"会话（见 {@link #ensureSession()}），语义与迁移前
     * "控制器字段一直在"等价。</p>
     */
    public void applyPlan(ImportPlanVm plan) {
        apply(ensureSession(), toPlan(plan));
    }

    private void apply(WorkspaceImportService.Session session, MhtImportPlanner.Plan plan) {
        // 本轮开始的可见项快照：与迁移前 MainController.visibleSnapshot() 同口径（可见项 = removed == false；
        // 本轮内后续批次的去重由服务用 seen 集合增量复现，见服务 javadoc）
        List<PreviewItem> visible = ws.allItems().stream().filter(i -> !i.removed).toList();
        WorkspaceImportService.RoundOutcome outcome =
                service.resolveRound(session, plan,
                        visible.stream().map(i -> i.path).toList(), visible.size());

        List<GroupItem<PreviewItem>> createdGroups = new ArrayList<>();
        int newFiles = 0;
        boolean madeListNonEmpty = false;
        for (WorkspaceImportService.ResolvedBatch rb : outcome.batches()) {
            if (rb.skipped()) {
                // RISK-1 硬约束：无新文件则该批次不建组（此日志保持 debug 级别，不升级）
                log.debug(Texts.msg("log.maincontroller.08", rb.dirPath()));
                continue;
            }
            if (rb.grouped()) {
                createdGroups.add(createGroupForImport(rb.items(), rb.folderName()));
            } else {
                ws.appendItems(rb.items());       // TopLevelBatch：平铺（数据序写入统一经模型）
            }
            service.accumulate(session, rb.items());
            newFiles += rb.items().size();
            madeListNonEmpty |= rb.wasEmpty();
        }
        service.addCreatedGroups(session, createdGroups.size());

        // BC-5：撤销压栈在<b>批次循环之外</b>（轮级）—— 一次导入的每一轮恰好压 1 条记录（不是每一批），
        // 载荷 = 当时的全量累计（含本轮）；多轮导入时每轮各 1 条、每条载荷均为全量累计，一次撤销撤整批。
        // 循环体内不得压栈。
        List<PreviewItem> accumulated = service.accumulated(session);
        if (!accumulated.isEmpty()) {
            ws.push(UndoRecords.importRecord(accumulated, createdGroups));
        }

        // 每轮一次刷新 / 新建组挂接 / 首行选中经事件交付（RISK-8，不是每批一次）
        emit(new PortEvent.ImportRound(generation, toVm(plan), newFiles, createdGroups.size(),
                madeListNonEmpty, createdGroups));
    }

    /**
     * 派发形态的一轮落地（A2）：执行 {@link #apply} 后，<b>无论成败</b>都在宿主侧完成
     * {@code applied}（载荷 = 落地后的配额计数快照）。扫描线程在它上面 join()，
     * 因此配额卡永远读到"含本轮"的计数；apply 失败时快照反映失败时的会话状态
     * （异常本身仍向宿主线程的派发方传播，与迁移前语义一致）。
     */
    private void applyRound(WorkspaceImportService.Session session, MhtImportPlanner.Plan plan,
                            CompletableFuture<QuotaCounts> applied) {
        try {
            apply(session, plan);
        } finally {
            applied.complete(new QuotaCounts(
                    service.accumulated(session).size(), service.groupCount(session)));
        }
    }

    /**
     * 建组：组名计算（规则在 app 层）→ 默认展开（BC-4）→ 成员入组 → 追加顶层。
     *
     * <p>名字刷新监听注册（F8）由前端经 {@link PortEvent.ImportRound#createdGroups()} 挂接 ——
     * 那件 GUI 事（列表单元格刷新）不随编排下沉；可复用的纯函数段（sanitize + 空名退化 + 唯一化）
     * 在 {@link WorkspaceImportService#resolveGroupName}。</p>
     *
     * <p>注意：不清 members 的条目级参数——成员均为本次新导入项，本就没有条目级参数；
     * 不要照抄手工建组的条目参数清除（那会清掉既有条目的参数，RISK-1）。</p>
     */
    private GroupItem<PreviewItem> createGroupForImport(List<PreviewItem> members, String folderName) {
        // ① 组号：全局唯一来源在模型（与手工建组共用同一计数器，避免两条路径各自 +1 产生重号）
        int groupNo = ws.nextGroupNo();
        // ②③ 组名：sanitize → 空名退化为"默认前缀 + 组号" → 唯一化（复用既有实现 F7；组名集合唯一来源在模型 R8-①）
        WorkspaceImportService.GroupName name = service.resolveGroupName(
                folderName, groupNo, Texts.get("core.groupDefault"), ws.groupNames());
        if (!name.unique().equals(name.base())) {
            log.info(Texts.msg("log.maincontroller.09", name.base(), name.unique()));
        }
        // ④ 建组（名字刷新监听由前端按 ImportRound.createdGroups 挂接 —— F8，漏了会导致改名后列表不刷新）
        GroupItem<PreviewItem> group = new GroupItem<>(groupNo, name.unique());
        group.setExpanded(true);                  // BC-4：默认展开
        // ⑤ 成员入组
        group.getMembers().addAll(members);
        // ⑥ 追加顶层末尾（导入一律追加到末尾，不与落点/既有项目交互）—— 数据序写入统一经模型
        ws.appendGroup(group);
        return group;
    }

    /** 后台线程：轮扫描 + 询问宿主确认；不经手任何界面控件 */
    private void runRounds(WorkspaceImportService.Session session) {
        try {
            while (true) {
                MhtImportPlanner.Plan round = service.nextRound(session);
                // A2：显式完成信号串起"apply 落地 → 读计数"的先后。不得用"先投递 Platform.runLater"
                // 当隐式同步契约（宿主线程被占用时 apply 可能迟迟不落地，配额卡就会读到缺了本轮的计数）。
                CompletableFuture<QuotaCounts> applied = new CompletableFuture<>();
                dispatcher.accept(() -> applyRound(session, round, applied));
                QuotaCounts counts = applied.join();   // 等待方仍是后台扫描线程，不阻塞宿主
                if (!round.hasMore()) {
                    break;
                }
                if (!round.quotaHit()) {
                    continue;                     // 防御：无配额命中却还有剩余
                }
                log.info(Texts.msg("log.maincontroller.10", maxGroupsPerRound, maxFilesPerRound));
                boolean go = awaitQuotaConfirm(counts);
                if (!go) {
                    log.info(Texts.msg("log.maincontroller.11"));
                    break;
                }
            }
        } catch (Exception ex) {
            log.error(Texts.msg("log.maincontroller.12"), ex);
            dispatcher.accept(() -> emit(new PortEvent.Notice(
                    "error.IMPORT_FAILED", Map.of(), NoticeLevel.ERROR)));
        } finally {
            dispatcher.accept(this::finishImport);   // 唯一恢复点
        }
    }

    /**
     * 配额计数<b>不可变快照</b>（A2）：在 apply 落地之后（finally 里）由宿主侧构造。
     *
     * <p>{@link #awaitQuotaConfirm} 只用本快照拼配额卡文案，扫描线程<b>不再读取会话</b>的
     * 可变累计字段 —— 会话累计只有一个写方（宿主侧的 apply），本快照把"读什么"钉死在
     * 落地完成那一刻，避免出现第二份可变计数器（也免去两个线程对同一份累计的时序竞态）。</p>
     */
    record QuotaCounts(int files, int groups) {
    }

    /** 后台线程阻塞等待配额确认（窗口内居中确认卡片经端口确认往返；等待方是导入后台线程，不冻结界面） */
    private boolean awaitQuotaConfirm(QuotaCounts counts) {
        // 文案拼装口径与迁移前 ImportSessionCoordinator.awaitQuotaConfirm 逐字一致（同键同参）
        String msg = Texts.get("import.quotaReached",
                        "groups", maxGroupsPerRound,
                        "files", maxFilesPerRound)
                + Texts.get("import.quotaContinue")
                + Texts.get("import.quotaStop",
                        "files", counts.files(),
                        "groups", counts.groups());
        ConfirmRequest request = new ConfirmRequest("import.quotaReached", Map.of(
                "groups", String.valueOf(maxGroupsPerRound), "files", String.valueOf(maxFilesPerRound),
                "accumulatedGroups", String.valueOf(counts.groups()),
                "accumulatedFiles", String.valueOf(counts.files())),
                List.of(ACTION_CONTINUE, ACTION_STOP), 0, msg);
        String chosen;
        try {
            chosen = confirmer.confirm(request).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;                         // 中断 = 停止
        }
        return ACTION_CONTINUE.equals(chosen);
    }

    /** 收尾：唯一恢复点 + 统一预解析载荷 + 汇总日志 + 清会话 + 恢复开始按钮状态（经事件交付） */
    private void finishImport() {
        try {
            WorkspaceImportService.Session session = active;
            if (session == null) {
                return;                           // 防御：无会话时无可收尾（正常路径不会发生）
            }
            List<PreviewItem> accumulated = service.accumulated(session);
            // 预解析在导入期间抑制，收尾统一跑一次（RISK-7，避免 O(n²)）—— 前端按事件载荷与
            // 「当前模式参数已确认」门触发（与迁移前 hasConfirmedParams 同语义）
            emit(new PortEvent.ImportFinished(generation,
                    accumulated.stream().map(i -> i.path).toList()));
            log.info(Texts.msg("log.maincontroller.13", accumulated.size(), service.groupCount(session),
                    session.ignoredNonMht(), session.hiddenDirs(), session.emptyDirs(),
                    session.unreadable(), session.depthLimited()));
        } finally {
            active = null;
            emit(new PortEvent.ImportStateChanged(generation, false));
        }
    }

    /**
     * 取当前会话，没有就建一个"空地址"会话。
     *
     * <p>供"程序化驱动一轮 Plan"的路径使用（GUI 自动化 {@code FeatureExercise} 直接调
     * {@code MainController.applyImportPlan}，没有经过 {@link #importPaths}）：迁移前这条路用的是控制器上
     * 长期存在的 7 个累计字段，语义等价于"总有一个累计落点"。此会话不启动线程、不置位导入标志。</p>
     */
    private WorkspaceImportService.Session ensureSession() {
        WorkspaceImportService.Session session = active;
        if (session == null) {
            session = service.begin(List.of(), "", false, maxGroupsPerRound, maxFilesPerRound);
            active = session;
        }
        return session;
    }

    // ==================== 计划 ↔ 端口视图模型换算（逐字段对照） ====================

    /** {@link ImportPlanVm} → {@link MhtImportPlanner.Plan}（文件路径还原为 File；批次形态 / 顺序 / 计数器不变）。 */
    private static MhtImportPlanner.Plan toPlan(ImportPlanVm vm) {
        List<MhtImportPlanner.Batch> batches = new ArrayList<>(vm.batches().size());
        for (ImportPlanVm.ImportBatchVm b : vm.batches()) {
            List<File> files = b.filePaths().stream().map(File::new).toList();
            if (b.grouped()) {
                batches.add(new MhtImportPlanner.GroupBatch(b.dirPath(), b.folderName(), files));
            } else {
                batches.add(new MhtImportPlanner.TopLevelBatch(b.dirPath(), files));
            }
        }
        return new MhtImportPlanner.Plan(batches, vm.ignoredNonMht(), vm.hiddenDirs(), vm.emptyDirs(),
                vm.unreadable(), vm.depthLimited(), vm.quotaHit(), vm.hasMore());
    }

    /** {@link MhtImportPlanner.Plan} → {@link ImportPlanVm}（事件载荷用；逐字段对照，不要漏）。 */
    private static ImportPlanVm toVm(MhtImportPlanner.Plan plan) {
        List<ImportPlanVm.ImportBatchVm> batches = new ArrayList<>(plan.batches().size());
        for (MhtImportPlanner.Batch b : plan.batches()) {
            boolean grouped = b instanceof MhtImportPlanner.GroupBatch;
            String folderName = b instanceof MhtImportPlanner.GroupBatch gb ? gb.folderName() : null;
            batches.add(new ImportPlanVm.ImportBatchVm(b.dirPath(), folderName, grouped,
                    b.files().stream().map(File::getPath).toList()));
        }
        return new ImportPlanVm(batches, plan.ignoredNonMht(), plan.hiddenDirs(), plan.emptyDirs(),
                plan.unreadable(), plan.depthLimited(), plan.quotaHit(), plan.hasMore());
    }
}
