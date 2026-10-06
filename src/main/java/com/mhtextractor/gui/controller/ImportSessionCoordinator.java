package com.mhtextractor.gui.controller;

import com.mhtextractor.app.MhtImportPlanner;
import com.mhtextractor.app.WorkspaceImportService;
import com.mhtextractor.app.WorkspaceModel;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 导入会话协调器（Q2/P1）：承担统一导入的<b>生命周期与界面等待</b>，
 * 把导入的<b>规则</b>交给无头 {@link WorkspaceImportService}。
 *
 * <h2>分工（与迁移前的 {@code MainController} 逐条对应）</h2>
 * <table border="1">
 *   <caption>搬迁对照</caption>
 *   <tr><th>职责</th><th>迁移前</th><th>现在</th></tr>
 *   <tr><td>地址规范化 / 组名计算 / 轮次去重 / 累计计数</td>
 *       <td>{@code MainController}</td><td>{@link WorkspaceImportService}（app，可无头单测）</td></tr>
 *   <tr><td>后台扫描线程 / 轮次派发 / 配额确认等待 / 模型命令 / 刷新 / 收尾</td>
 *       <td>{@code MainController}</td><td>本类</td></tr>
 *   <tr><td>BC-8 盘符根确认展示 / 重复导入拦截 / 中文日志产出 / 开始按钮状态</td>
 *       <td>{@code MainController}</td><td>{@code MainController}（不变）</td></tr>
 * </table>
 *
 * <h2>为什么经 {@link ImportUi} 窄接口要东西</h2>
 * <p>若本类直接持有 {@code ListView} / 标签 / 控制器引用，就会变成"第二个控制器"，
 * 控件面一变就要改两处；窄接口把"我需要什么"写死成 10 个方法，实现方（控制器）自己决定
 * 用哪个控件满足它（图标、锚点都留在实现方，符合"用户可见文案与图标在 GUI 外壳"的约定）。</p>
 *
 * <h2>线程约定（与迁移前一致，不得改）</h2>
 * <ul>
 *   <li>{@link #start} 在 FX 线程调用（FXML 事件 / {@code Platform.runLater} 回调）；</li>
 *   <li>扫描循环跑在名为 {@code mht-import-scan} 的守护线程上；</li>
 *   <li>模型命令、列表刷新、收尾一律经<b>注入的派发器</b>（生产 = {@code Platform.runLater}）回到 FX 线程；</li>
 *   <li>配额确认的<b>等待方是导入后台线程</b>（{@code future.join()}），不阻塞 FX 线程 —— 既有形态，别改。</li>
 * </ul>
 */
@Slf4j
public final class ImportSessionCoordinator {

    /**
     * 本类需要控制器提供的最小面（由 {@code MainController} 实现）。
     *
     * <p>所有方法都在 <b>FX 线程</b>调用，唯一例外是 {@link #visibleSnapshot()} 的返回值只读性
     * （仍只在 FX 线程读）。</p>
     */
    public interface ImportUi {

        /** 可见项快照：路径（导入去重口径）+ 数量 */
        record VisibleSnapshot(List<String> paths, int count) {
            public VisibleSnapshot {
                paths = List.copyOf(paths);
            }
        }

        /** 会话开始：置位"导入中"并刷新开始按钮（C-1：禁用开始提取） */
        void onImportStarted(List<String> addresses, String sourceDesc, boolean fromDrag);

        /** 会话收尾：清位并刷新开始按钮（finally 路径也必须走到） */
        void onImportFinished();

        /** 列表可见项快照（去重口径<b>必须</b>是"可见项"，不得含已移除项） */
        VisibleSnapshot visibleSnapshot();

        /** 当前模式参数是否已确认（决定收尾是否统一预解析） */
        boolean hasConfirmedParams();

        /** 收尾统一预解析（RISK-7：导入期间抑制，收尾跑一次，避免 O(n²)） */
        void startPreParse(List<PreviewItem> items);

        /** 列表结构变化后的统一刷新（每轮一次，RISK-8） */
        void refreshList();

        /** 注册新建组的名字刷新监听（F8/RISK-11） */
        void attachGroupNameRefresh(GroupItem<PreviewItem> group);

        /** 列表为空时选中首行（导入首轮） */
        void selectFirstRow();

        /** 配额确认卡（FX 线程；回答经回调回传，等待方是导入后台线程） */
        void confirmQuotaReached(String message, Consumer<Boolean> answer);

        /** 扫描异常提示（FX 线程） */
        void showImportFailed();
    }

    private final WorkspaceModel ws;
    private final ImportUi ui;
    /** FX 派发器（生产 = {@code Platform::runLater}；测试注入延迟派发器，把任务放进队列由测试线程按序 drain） */
    private final Consumer<Runnable> dispatcher;
    /** 每轮配额（生产 = {@link MhtImportPlanner} 默认常量；测试注入小配额，用<b>真实扫描器</b>复现"每轮打满"） */
    private final int maxGroupsPerRound;
    private final int maxFilesPerRound;
    private final WorkspaceImportService service = new WorkspaceImportService();

    /**
     * 当前会话（即"本次导入的累计落点"）；{@code null} = 无进行中的导入。
     *
     * <p>程序化驱动一轮计划的路径（GUI 自动化直接调 {@code MainController.applyImportPlan}）
     * 会按需建一个"空地址"会话 —— 迁移前那是控制器上长期存在的 7 个字段，语义上等价于
     * "总有一个累计落点"，见 {@link #ensureSession()}。</p>
     */
    private volatile WorkspaceImportService.Session active;

    /**
     * 公开构造（生产路径，签名不变）：FX 派发走 {@code Platform.runLater}，配额用
     * {@link MhtImportPlanner} 的默认常量（与迁移前控制器私有常量同源）。
     */
    public ImportSessionCoordinator(WorkspaceModel ws, ImportUi ui) {
        this(ws, ui, Platform::runLater, MhtImportPlanner.DEFAULT_MAX_GROUPS, MhtImportPlanner.DEFAULT_MAX_FILES);
    }

    /**
     * 包级构造（可测试性注入缝 —— 属线程边界设计，不是测试让步）：派发器与配额均可注入。
     *
     * <p>测试注入"延迟派发器"（任务进队列、由测试线程按序 drain）模拟 FX 线程被占用，从而
     * <b>确定性</b>复现"配额卡在 apply 落地前构造"的时序竞态；注入小配额则让真实扫描器每轮打满配额
     * （不必造 200 个组的目录树）。注入的配额必须与调用方打印的配额数一致
     * （见 {@link WorkspaceImportService#begin(List, String, boolean, int, int)}）。</p>
     */
    ImportSessionCoordinator(WorkspaceModel ws, ImportUi ui, Consumer<Runnable> dispatcher,
                             int maxGroupsPerRound, int maxFilesPerRound) {
        this.ws = ws;
        this.ui = ui;
        this.dispatcher = dispatcher;
        this.maxGroupsPerRound = maxGroupsPerRound;
        this.maxFilesPerRound = maxFilesPerRound;
    }

    /** 地址规范化（规则在 app 层；日志由调用方按 {@link WorkspaceImportService.NormalizeResult} 逐条产出） */
    public WorkspaceImportService.NormalizeResult normalize(List<String> addresses) {
        return service.normalizeAddresses(addresses);
    }

    /**
     * 启动导入会话：建会话 → 通知 GUI 置位 → 起后台扫描线程。
     *
     * <p>调用方已完成：空检查、重复导入拦截（{@code log.maincontroller.03}）、规范化与
     * {@code log.maincontroller.04}、BC-8 盘符根确认。</p>
     */
    public void start(List<String> normalized, String sourceDesc, boolean fromDrag) {
        WorkspaceImportService.Session session =
                service.begin(normalized, sourceDesc, fromDrag, maxGroupsPerRound, maxFilesPerRound);
        active = session;
        ui.onImportStarted(session.addresses(), sourceDesc, fromDrag);

        Thread t = new Thread(() -> runRounds(session), "mht-import-scan");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 应用一个计划（一批或一轮）—— <b>FX 线程</b>；生产与自动化测试共用。
     *
     * <p>无会话时按需建一个"空地址"会话（见 {@link #ensureSession()}），语义与迁移前
     * "控制器字段一直在"等价。</p>
     */
    public void applyPlan(MhtImportPlanner.Plan plan) {
        apply(ensureSession(), plan);
    }

    private void apply(WorkspaceImportService.Session session, MhtImportPlanner.Plan plan) {
        // 本轮开始的可见项快照：本轮内后续批次的去重由服务用 seen 集合增量复现（见服务 javadoc）
        ImportUi.VisibleSnapshot snapshot = ui.visibleSnapshot();
        WorkspaceImportService.RoundOutcome outcome =
                service.resolveRound(session, plan, snapshot.paths(), snapshot.count());

        List<GroupItem<PreviewItem>> createdGroups = new ArrayList<>();
        for (WorkspaceImportService.ResolvedBatch rb : outcome.batches()) {
            if (rb.skipped()) {
                // RISK-1 硬约束：无新文件则该批次不建组（此日志保持 debug 级别，不升级）
                log.debug(I18n.msg("log.maincontroller.08", rb.dirPath()));
                continue;
            }
            if (rb.grouped()) {
                createdGroups.add(createGroupForImport(rb.items(), rb.folderName()));
            } else {
                ws.appendItems(rb.items());       // TopLevelBatch：平铺（数据序写入统一经模型）
            }
            service.accumulate(session, rb.items());

            if (rb.wasEmpty()) {
                ui.selectFirstRow();
            }
        }
        service.addCreatedGroups(session, createdGroups.size());

        // 撤销栈：一次导入 = 一条记录（BC-5；多轮导入时每轮记录均携带全量累计，一次撤销撤整批）
        List<PreviewItem> accumulated = service.accumulated(session);
        if (!accumulated.isEmpty()) {
            ws.push(com.mhtextractor.app.UndoRecords.importRecord(accumulated, createdGroups));
        }

        ui.refreshList();                         // 每轮一次（RISK-8，不是每批一次）
    }

    /**
     * 派发形态的一轮落地（A2）：执行 {@link #apply} 后，<b>无论成败</b>都在 FX 侧完成
     * {@code applied}（载荷 = 落地后的配额计数快照）。扫描线程在它上面 join()，
     * 因此配额卡永远读到"含本轮"的计数；apply 失败时快照反映失败时的会话状态
     * （异常本身仍向 FX 线程的派发方传播，与迁移前语义一致）。
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
     * 建组：组名计算（规则在 app 层）→ 注册名字刷新监听 → 追加顶层（BC-4 默认展开）。
     *
     * <p><b>为什么本方法留在 GUI 层</b>：它混合了三件 GUI 事 —— 名字刷新监听注册
     * （{@code fileListView::refresh}）、默认展开、以及"组成员只入组不入顶层"的模型命令；
     * 可复用的是其中<b>纯函数</b>那一段（sanitize + 空名退化 + 唯一化），已抽到
     * {@link WorkspaceImportService#resolveGroupName}。</p>
     *
     * <p>注意：不清 members 的条目级参数——成员均为本次新导入项，本就没有条目级参数；
     * 不要照抄手工建组的条目参数清除（那会清掉既有条目的参数，RISK-1）。</p>
     */
    private GroupItem<PreviewItem> createGroupForImport(List<PreviewItem> members, String folderName) {
        // ① 组号：全局唯一来源在模型（与手工建组共用同一计数器，避免两条路径各自 +1 产生重号）
        int groupNo = ws.nextGroupNo();
        // ②③ 组名：sanitize → 空名退化为"默认前缀 + 组号" → 唯一化（复用既有实现 F7；组名集合唯一来源在模型 R8-①）
        WorkspaceImportService.GroupName name = service.resolveGroupName(
                folderName, groupNo, I18n.get("core.groupDefault"), ws.groupNames());
        if (!name.unique().equals(name.base())) {
            log.info(I18n.msg("log.maincontroller.09", name.base(), name.unique()));
        }
        // ④ 建组 + 注册名字刷新监听（F8——漏了会导致改名后列表不刷新）
        GroupItem<PreviewItem> group = new GroupItem<>(groupNo, name.unique());
        ui.attachGroupNameRefresh(group);
        group.setExpanded(true);                  // BC-4：默认展开
        // ⑤ 成员入组
        group.getMembers().addAll(members);
        // ⑥ 追加顶层末尾（导入一律追加到末尾，不与落点/既有项目交互）—— 数据序写入统一经模型
        ws.appendGroup(group);
        return group;
    }

    /** 后台线程：轮扫描 + 询问 FX 确认；不经手 GUI 控件 */
    private void runRounds(WorkspaceImportService.Session session) {
        try {
            while (true) {
                MhtImportPlanner.Plan round = service.nextRound(session);
                // A2：显式完成信号串起"apply 落地 → 读计数"的先后。不得用"先投递 Platform.runLater"
                // 当隐式同步契约（FX 线程被占用时 apply 可能迟迟不落地，配额卡就会读到缺了本轮的计数）。
                CompletableFuture<QuotaCounts> applied = new CompletableFuture<>();
                dispatcher.accept(() -> applyRound(session, round, applied));
                QuotaCounts counts = applied.join();   // 等待方仍是后台扫描线程，不阻塞 FX
                if (!round.hasMore()) {
                    break;
                }
                if (!round.quotaHit()) {
                    continue;                     // 防御：无配额命中却还有剩余
                }
                log.info(I18n.msg("log.maincontroller.10", maxGroupsPerRound, maxFilesPerRound));
                boolean go = awaitQuotaConfirm(counts);
                if (!go) {
                    log.info(I18n.msg("log.maincontroller.11"));
                    break;
                }
            }
        } catch (Exception ex) {
            log.error(I18n.msg("log.maincontroller.12"), ex);
            dispatcher.accept(ui::showImportFailed);
        } finally {
            dispatcher.accept(this::finishImport);   // 唯一恢复点
        }
    }

    /**
     * 配额计数<b>不可变快照</b>（A2）：在 apply 落地之后（finally 里）由 FX 侧构造。
     *
     * <p>{@link #awaitQuotaConfirm} 只用本快照拼配额卡文案，扫描线程<b>不再读取会话</b>的
     * 可变累计字段 —— 会话累计只有一个写方（FX 侧的 apply），本快照把"读什么"钉死在
     * 落地完成那一刻，避免出现第二份可变计数器（也免去两个线程对同一份累计的时序竞态）。</p>
     */
    record QuotaCounts(int files, int groups) {
    }

    /** 后台线程阻塞等待 FX 线程上的配额确认（会话 19：窗口内居中确认卡片；等待方是导入后台线程，不冻结界面） */
    private boolean awaitQuotaConfirm(QuotaCounts counts) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        String msg = I18n.get("import.quotaReached",
                        "groups", maxGroupsPerRound,
                        "files", maxFilesPerRound)
                + I18n.get("import.quotaContinue")
                + I18n.get("import.quotaStop",
                        "files", counts.files(),
                        "groups", counts.groups());
        dispatcher.accept(() -> ui.confirmQuotaReached(msg, future::complete));
        return future.join();
    }

    /** 收尾：唯一恢复点 + 统一预解析 + 汇总日志 + 清会话 + 恢复开始按钮 */
    private void finishImport() {
        try {
            WorkspaceImportService.Session session = active;
            if (session == null) {
                return;                           // 防御：无会话时无可收尾（正常路径不会发生）
            }
            List<PreviewItem> accumulated = service.accumulated(session);
            // 预解析在导入期间抑制，收尾统一跑一次（RISK-7，避免 O(n²)）
            if (!accumulated.isEmpty() && ui.hasConfirmedParams()) {
                ui.startPreParse(new ArrayList<>(accumulated));
            }
            log.info(I18n.msg("log.maincontroller.13", accumulated.size(), service.groupCount(session),
                    session.ignoredNonMht(), session.hiddenDirs(), session.emptyDirs(),
                    session.unreadable(), session.depthLimited()));
        } finally {
            active = null;
            ui.onImportFinished();
        }
    }

    /**
     * 取当前会话，没有就建一个"空地址"会话。
     *
     * <p>供"程序化驱动一轮 Plan"的路径使用（GUI 自动化 {@code FeatureExercise} 直接调
     * {@code MainController.applyImportPlan}，没有经过 {@link #start}）：迁移前这条路用的是控制器上
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
}
