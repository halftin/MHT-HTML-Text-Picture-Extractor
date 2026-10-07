package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.ExtractionStats;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.uiport.Confirmer;
import com.mhtextractor.uiport.ConfirmRequest;
import com.mhtextractor.uiport.PortEvent;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 提取任务通道的唯一所有者（P3-B6，矩阵处置项 1）—— 把 {@code gui.ExtractionTaskCoordinator} 的
 * <b>任务代际、终态恰好一次、取消竞态、结果通道</b>整体下沉到 app 层（零 JavaFX），前端只剩事件渲染。
 *
 * <p><b>通道契约（逐字保留，这是本批核心）</b>：</p>
 * <ol>
 *   <li><b>每任务一代际</b>：{@link #generation()} 每 {@link #startExtraction} 递增一次；旧任务迟到的
 *       终态/回写/进度回调在派发目标线程上发现 {@code runId != generation} 而 <b>no-op</b>，
 *       不得污染新任务；</li>
 *   <li><b>终态恰好一次</b>：成功 / 取消 / 失败都恰好交付一次
 *       {@link PortEvent.ExtractionTerminal}（成功/失败在 worker 收尾后、取消在
 *       {@link #cancelExtraction} 的<b>取消请求时刻</b> —— 取消请求 ≠ worker 已退出，不得等 worker；
 *       取消与自然完成的裁决以 {@link RunChannel#result} 是否已完成等价
 *       {@code FutureTask.cancel} 对已完成任务 no-op 的语义）；</li>
 *   <li><b>不得把派发器当隐式同步契约</b>：配额/计数类读取必须先有显式完成信号 ——
 *       回写只经 {@link RunChannel#deliverAfterWorkerFinished}（{@code result} 完成之后、
 *       派发目标线程上、执行前复查陈旧守卫），事件本身即显式信号；</li>
 *   <li><b>取消后立即再开新任务不得互相污染</b>：新任务换新 {@link RunChannel}（新 runId），
 *       旧通道的一切迟到投递被代际守卫拦下。</li>
 * </ol>
 *
 * <p><b>线程约定</b>：{@link #startExtraction} / {@link #cancelExtraction} 在宿主串行线程（生产 = FX）
 * 调用；worker 只计算局部结果并经 {@link RunChannel#result}（{@link CompletableFuture}）与
 * {@code cancelled}/{@code failure}（volatile）向派发目标线程交付；一切事件投递经注入的派发器
 * （生产 = {@code Platform::runLater}）。</p>
 *
 * <p><b>本类零 JavaFX、零 I18n</b>（日志键 {@code log.maincontroller.31/32/33/37/39} 经 {@link Texts}
 * 逐键不变；回写与终态渲染是前端的事，本类只发事件）。</p>
 */
@Slf4j
public final class WorkspaceExtractionCoordinator {

    /**
     * 每次 {@link #startExtraction} 的结果通道与陈旧守卫（原 {@code ExtractionTaskCoordinator.RunChannel}
     * 逐字下沉，B —— 零 JavaFX，可无头单测）。
     *
     * <p><b>三个时点</b>：① 请求取消（{@link WorkspaceExtractionCoordinator#cancelExtraction} 立即置
     * {@link #cancelled} 并触发取消终态）② worker 停止并产出 partial（{@code call()} 返回前
     * {@link #result} 完成）③ 派发目标线程收到回写并投递。① ≠ ②：取消终态跑在①，此刻 partial
     * 尚不存在 —— 直接读 {@code lastExtractStats} 会拿到 null（首次运行）或上一次运行的残留。
     * 因此取消回写不读 holder，只在本通道上挂"②完成后投递一次"的回调。</p>
     *
     * <p><b>陈旧守卫</b>：新 run 一开（代际递增），旧 run 的一切迟到回调（终态与本通道挂的回写）
     * 都在派发目标线程上发现 {@code runId != generation} 而 no-op —— 取消后立即再开新任务时
     * 旧回调不得污染新任务。</p>
     */
    public static final class RunChannel {
        /** 本次 run 的序号（每 run 递增一次；旧任务回调凭它识别自己是陈旧调用） */
        public final long runId;
        /** 本次 run 的结果容器：worker 完成处理后在 worker 线程完成，<b>恰好一次</b>（异常路径完成 null） */
        public final CompletableFuture<ExtractionStats> result = new CompletableFuture<>();
        /** 到"宿主线程"的派发器（生产 = {@code Platform::runLater}；测试注入由测试线程按序 drain 的队列） */
        final Consumer<Runnable> dispatcher;
        /** 取消请求标志（worker 的取消信号轮询源；取消请求 ≠ worker 已退出） */
        volatile boolean cancelled;
        /** 在途 worker 线程（取消时打断；中断被吞时 worker 仍须完成处理并产出 partial 才算停） */
        volatile Thread worker;
        /** worker 异常（终态 FAILED 的判定源） */
        volatile Throwable failure;
        /** 本次 run 的会话输出目录（终态事件载荷，成功路径"完成后打开目录"用） */
        volatile String sessionOutputFolder;

        public RunChannel(long runId, Consumer<Runnable> dispatcher) {
            this.runId = runId;
            this.dispatcher = dispatcher;
        }

        /**
         * 终态回调的陈旧守卫：{@code currentRunId == runId} 才是当前 run。
         * 必须在派发目标线程上调用（代际只在派发目标线程上读写）。
         */
        public boolean isCurrent(long currentRunId) {
            return currentRunId == runId;
        }

        /**
         * 在 worker 完成处理后经派发器投递一次回写 —— <b>取消路径专用</b>（成功路径统计随终态事件走，避免双回写）。
         *
         * <p>注册时机是取消请求（时点①）；回写动作在 {@link #result} 完成（时点②）之后、
         * 派发目标线程（时点③）上执行，且执行前<b>复查陈旧守卫</b> —— 注册之后又开了新 run，
         * 本次回写必须 no-op。回调无论注册时 {@code result} 是否已完成都恰好执行一次
         * （{@link CompletableFuture#whenComplete} 对已完成 future 仍会执行）；
         * worker 异常路径完成 null 时回写动作不触发（无统计可回写）。</p>
         *
         * @param currentRunId 当前 run 序号的只读入口（在派发目标线程上求值）
         * @param onFx         UI 回写动作（只收非 null 统计）
         */
        public void deliverAfterWorkerFinished(LongSupplier currentRunId,
                                               Consumer<ExtractionStats> onFx) {
            result.whenComplete((stats, error) -> dispatcher.accept(() -> {
                if (currentRunId.getAsLong() != runId) {
                    return;                     // 陈旧 run 的迟到回写：no-op
                }
                if (stats != null) {
                    onFx.accept(stats);
                }
            }));
        }

        /** 取消请求（时点①）：置取消标志 + 打断 worker（中断可能被吞，取消请求 ≠ worker 已退出）。 */
        void cancel() {
            cancelled = true;
            Thread w = worker;
            if (w != null) {
                w.interrupt();
            }
        }
    }

    /**
     * 运行标记（预解析/预览回调据此让出进度条 / 状态栏）：{@code startExtraction} 里置 true，
     * 终态（成功/失败 = worker 收尾投递；取消 = {@link #cancelExtraction} 请求时刻）复位 false。
     */
    public final AtomicBoolean running = new AtomicBoolean(false);
    /**
     * 最近一次提取统计的唯一 holder：worker 在服务返回后写一次（先落 holder，再完成结果通道），
     * 经 GUI 只读投影供自动化断言。取消回写不经此 holder（见 {@link RunChannel}）。
     */
    public final AtomicReference<ExtractionStats> lastExtractStats = new AtomicReference<>();
    /** 每 run 递增的运行代数（只在派发目标线程上读写）：旧任务迟到的终态回调凭它识别自己是陈旧调用 */
    private volatile long generation;
    /** 在途通道（null = 无在途任务；取消与终态投递只在派发目标线程访问） */
    private volatile RunChannel channel;

    private final WorkspaceExtractionService service;
    private final Function<Boolean, WorkspaceExtractionService.ExtractionRequest> requests;
    private final Consumer<Runnable> dispatcher;
    private final List<Consumer<PortEvent>> listeners = new CopyOnWriteArrayList<>();

    /**
     * @param service    提取业务段（逐单元调用 processor、进度聚合、统计累计、取消传播）
     * @param requests   请求工厂（{@code checkedOnly} → 不可变请求快照；生产 =
     *                   {@link #productionRequests(ExtractionPlanService, Supplier, Confirmer)}，
     *                   在宿主串行线程上求值 —— 与迁移前 {@code startExtractionTask} 的快照时机一致；
     *                   测试注入桩工厂复现闸门/自定义单元）
     * @param dispatcher 到宿主串行线程的派发器（生产 = {@code Platform::runLater}）
     */
    public WorkspaceExtractionCoordinator(WorkspaceExtractionService service,
                                          Function<Boolean, WorkspaceExtractionService.ExtractionRequest> requests,
                                          Consumer<Runnable> dispatcher) {
        this.service = java.util.Objects.requireNonNull(service, "service");
        this.requests = java.util.Objects.requireNonNull(requests, "requests");
        this.dispatcher = java.util.Objects.requireNonNull(dispatcher, "dispatcher");
    }

    /** 端口事件订阅（事件在派发目标线程同步送达；句柄 {@code close()} 即退订）。 */
    public AutoCloseable subscribe(Consumer<PortEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void emit(PortEvent event) {
        for (Consumer<PortEvent> listener : listeners) {
            listener.accept(event);
        }
    }

    /** 运行代数（每 {@link #startExtraction} 递增一次）。 */
    public long generation() {
        return generation;
    }

    /** 在途通道（无在途任务时为 null）。 */
    public RunChannel channel() {
        return channel;
    }

    /**
     * 创建并启动提取任务（<b>必须在宿主串行线程调用</b>）：请求在调用线程上求值（快照时机与
     * 迁移前 {@code startExtractionTask} 一致），随后置运行标记、建新通道（代际递增）、启动 worker。
     * 界面锁定（开始/停止按钮、进度条归零）由前端在调用本命令前完成（渲染属前端）。
     */
    public void startExtraction(boolean checkedOnly) {
        WorkspaceExtractionService.ExtractionRequest request = requests.apply(checkedOnly);
        RunChannel ch = new RunChannel(++generation, dispatcher);
        ch.sessionOutputFolder = request.sessionOutputFolder();
        channel = ch;
        running.set(true);
        Thread worker = new Thread(() -> {
            try {
                ExtractionStats stats = WorkspaceExtractionService.statsFrom(service.run(request,
                        () -> ch.cancelled,
                        overall -> dispatcher.accept(() -> {
                            if (ch.isCurrent(generation)) {
                                emit(new PortEvent.ExtractionProgress(generation, overall));
                            }
                        })).stats());
                // 时点②：worker 停止并产出 partial —— 先落只读 holder，再完成本 run 的结果通道
                lastExtractStats.set(stats);
                ch.result.complete(stats);
            } catch (Throwable e) {
                // Throwable 而非 Exception：迁移前 JavaFX Task 框架把 Error 一视同仁路由到 onFailed
                // （终态 FAILED 恰好一次）；只捕 Exception 会让 Error 把通道晾死（result 永不完成、
                // 无终态、运行标记卡在 true），语义放窄 —— 终态恰好一次契约对 Error 同样成立。
                ch.failure = e;
                log.error(Texts.msg("log.maincontroller.37"), e);
                ch.result.complete(null);   // worker 异常：通道仍收尾（无统计可回写），终态按 FAILED 交付
            }
            dispatcher.accept(() -> landAfterWorkerFinished(ch));
        });
        ch.worker = worker;
        worker.start();
    }

    /**
     * 取消在途提取任务（<b>必须在宿主串行线程调用</b>；无在途任务或 worker 已收尾时为空操作 ——
     * 后者等价 {@code FutureTask.cancel} 对已完成任务 no-op，终态已在途）。
     *
     * <p><b>时点①</b>：取消请求 ≠ worker 已退出 —— 运行标记<b>立即</b>复位、取消终态
     * {@link PortEvent.ExtractionTerminal}（{@code CANCELLED}，stats = null）<b>此刻</b>交付一次
     * （前端据此复位停止/开始按钮）；partial 回写经 {@link RunChannel#deliverAfterWorkerFinished}
     * 在 worker 完成（时点②）后投递一次（时点③），载荷 = worker 返回的统计。</p>
     */
    public void cancelExtraction() {
        RunChannel ch = channel;
        if (ch == null || ch.result.isDone()) {
            return;                     // 无在途任务 / worker 已收尾：终态已在途，不得再交付第二次
        }
        ch.cancel();
        running.set(false);
        emit(new PortEvent.ExtractionTerminal(ch.runId, PortEvent.ExtractionEnd.CANCELLED, null,
                ch.sessionOutputFolder));
        // 取消回写：worker 完成后经派发器投递一次（陈旧守卫在派发目标线程复查）
        ch.deliverAfterWorkerFinished(this::generation,
                stats -> emit(new PortEvent.ExtractionResult(generation, stats)));
    }

    /** worker 收尾后的终态投递（派发目标线程）：陈旧守卫 → 取消 run 不重复交付终态（回写通道负责 partial）→ 成功/失败终态恰好一次。 */
    private void landAfterWorkerFinished(RunChannel ch) {
        if (!ch.isCurrent(generation)) {
            return;                     // 陈旧任务的迟到回调：no-op（取消后立即再开新任务时不得污染新任务）
        }
        if (ch.cancelled) {
            return;                     // 取消终态已在 cancelExtraction（时点①）交付；partial 回写走结果通道
        }
        running.set(false);
        if (ch.failure != null) {
            emit(new PortEvent.ExtractionTerminal(ch.runId, PortEvent.ExtractionEnd.FAILED, null,
                    ch.sessionOutputFolder));
        } else {
            emit(new PortEvent.ExtractionTerminal(ch.runId, PortEvent.ExtractionEnd.SUCCEEDED,
                    ch.result.getNow(null), ch.sessionOutputFolder));
        }
    }

    // ==================== 生产请求工厂 ====================

    /**
     * 生产请求工厂（原 {@code ExtractionTaskCoordinator.startExtractionTask} 的请求组装段逐字下沉）：
     * 运行时配置 → 时间戳会话目录（{@code log.maincontroller.31/32}）→ 功能一勾选计数
     * （{@code log.maincontroller.39}）→ 输出单元计划（{@code log.maincontroller.33}）→
     * 逐文件覆盖 → 不可变请求快照。递归深度超限确认经端口确认往返（等待方是提取后台线程，
     * 不阻塞宿主；文案与迁移前 {@code recursionDecision} 逐字一致）。
     *
     * @param plans           提取计划服务（运行时配置 + 输出单元规划 + 逐文件覆盖；上下文由宿主注入）
     * @param allVisibleItems 全部有效项目（removed == false，含组内成员；在调用线程上求值）
     * @param confirmer       确认往返实现（递归深度卡；前端渲染卡片，后端线程阻塞等待）
     */
    public static Function<Boolean, WorkspaceExtractionService.ExtractionRequest> productionRequests(
            ExtractionPlanService plans, Supplier<List<PreviewItem>> allVisibleItems, Confirmer confirmer) {
        java.util.Objects.requireNonNull(plans, "plans");
        java.util.Objects.requireNonNull(allVisibleItems, "allVisibleItems");
        java.util.Objects.requireNonNull(confirmer, "confirmer");
        return checkedOnly -> {
            AppConfig runtime = plans.runtimeConfig();
            // 生成时间戳会话目录，隔离每次提取运行
            String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern(Texts.get("params.datetimeFormat")));
            String sessionOutputFolder = new File(runtime.getOutputFolder(), timestamp).getPath();
            if (!new File(sessionOutputFolder).mkdirs()) {
                log.warn(Texts.msg("log.maincontroller.31", sessionOutputFolder));
            }
            log.info(Texts.msg("log.maincontroller.32", sessionOutputFolder));

            // 功能一：只提取选中项时的勾选计数（清空"本次不参与的可见项"的旧结果色由前端在命令前完成）
            if (checkedOnly) {
                List<PreviewItem> visible = allVisibleItems.get();
                log.info(Texts.msg("log.maincontroller.39",
                        visible.stream().filter(i -> i.selected).count(), visible.size()));
            }

            // 构建输出单元计划（宿主线程快照；计划与单元参数补充在 app.ExtractionPlanService）
            plans.setSessionOutputFolder(sessionOutputFolder);
            List<com.mhtextractor.uiport.OutputUnitVm> units = plans.outputUnitPlan(checkedOnly);
            log.info(Texts.msg("log.maincontroller.33", units.stream()
                    .map(u -> Texts.get("extract.groupFileCount",
                            "group", u.outputName() != null ? u.outputName() : Texts.get(u.labelKey()),
                            "count", u.fileCount())).toList()));
            Map<String, com.mhtextractor.processor.PerFileOverrides> overrides =
                    ExtractionPlanService.overrides(allVisibleItems.get());

            return WorkspaceExtractionService.fromPlan(sessionOutputFolder, units, overrides,
                    runtime.isImageModeEnabled(), recursionDecision(confirmer));
        };
    }

    /**
     * 递归深度超限回调：经端口确认往返（后端工作线程阻塞等待答案，卡片由前端渲染）。
     * 确定 = 继续；取消/中断 = 跳过（与迁移前 {@code Platform.runLater + future.join} 同语义）。
     */
    private static Supplier<Boolean> recursionDecision(Confirmer confirmer) {
        return () -> {
            String msg = Texts.get("alert.recursionDepthHeader") + "\n"
                    + Texts.get("alert.recursionDepthContent")
                    + Texts.get("alert.recursionDepthContinue")
                    + Texts.get("alert.recursionDepthSkip");
            ConfirmRequest request = new ConfirmRequest("alert.recursionDepthHeader", Map.of(),
                    List.of(ACTION_CONTINUE), 0, msg);
            try {
                return ACTION_CONTINUE.equals(confirmer.confirm(request).await());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;               // 中断 = 跳过
            }
        };
    }

    /** 递归深度确认卡的「继续」动作键。 */
    static final String ACTION_CONTINUE = "continue";
}
