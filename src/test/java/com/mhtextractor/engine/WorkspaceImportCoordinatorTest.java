package com.mhtextractor.engine;

import com.mhtextractor.i18n.Texts;

import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.uiport.Confirmer;
import com.mhtextractor.uiport.ConfirmRequest;
import com.mhtextractor.uiport.ConfirmToken;
import com.mhtextractor.uiport.PortEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A2 —— 导入配额时序回归测试（B5-1：由 {@code gui.controller.ImportSessionCoordinatorQuotaTest} 平移而来，
 * 目标 = {@link WorkspaceImportCoordinator}）：配额卡上的"停止后保留 files/groups"必须包含刚完成的那一轮。
 *
 * <h2>复现手法（确定性，不依赖真实 FX 线程调度）</h2>
 * <p>注入<b>延迟派发器</b>（任务进队列，测试线程按序 drain）模拟"宿主 UI 线程被占用"，
 * 并注入小配额（2 组 / 3 文件）让<b>真实扫描器</b>每轮打满（不必造 200 个组的目录树）。
 * 本测试<b>绝不初始化 JavaFX toolkit、绝不调用 {@code Platform.runLater}</b>——
 * 无 FX 初始化时那会直接抛 {@code IllegalStateException}。</p>
 *
 * <p>驱动循环里"按住本轮 apply、先看下一张任务是否已经到队"是关键结构：</p>
 * <ul>
 *   <li><b>旧实现</b>（fire-and-forget）：扫描线程把 apply 排队后<b>不等落地</b>就继续，
 *       在扫描线程上读会话累计拼配额卡 —— 按住 apply 时卡上的计数缺了本轮 → 卡面断言失败；</li>
 *   <li><b>本实现</b>（显式完成信号）：扫描线程在 {@code applied} 上 join()，apply 不落地
 *       它就不发确认请求 —— 按住 apply 时确认请求必然还没到，apply 跑掉后请求才发出，
 *       卡面计数必含本轮 → 断言通过。</li>
 * </ul>
 *
 * <p>于是同一份驱动代码在旧实现上暴露错误数字、在本实现上全绿 —— 这正是"复现竞态"想要的形态。
 * 配额等待方是导入后台线程（本测试里是 {@code mht-import-scan} 线程），"宿主线程"（测试主线程）
 * 不被阻塞：若实现反过来在宿主线程等后台任务，确认卡永远跑不到 → 驱动循环超时失败。
 * 确认往返经端口 {@link Confirmer}（桩收卡、测试线程回答），与迁移前 FakeUi 的收卡/回答案一致。</p>
 *
 * <h2>夹具：A 树与轮次切分（配额 2 组 / 3 文件）</h2>
 * <pre>
 * root/  top1.mht  top2.mht      （顶层平铺批次：2 文件 0 组）
 *        d1/in.mht  d2/in.mht  d3/in.mht  d4/in.mht（各 1 组 1 文件）
 *
 * 轮 1 = [top(2 文件), d1] → 累计 3 文件 / 1 组，文件配额打满 → 卡 1
 * 轮 2 = [d2, d3]         → 累计 5 文件 / 3 组，组配额打满   → 卡 2
 * 轮 3 = [d4]             → 累计 6 文件 / 4 组，无配额命中、无剩余 → 收尾（不出卡）
 * </pre>
 */
class WorkspaceImportCoordinatorTest {

    /** 与生产默认（200/5000）同源语义，但小到能用真实目录树复现"每轮打满" */
    private static final int MAX_GROUPS = 2;
    private static final int MAX_FILES = 3;

    /** 驱动循环里"看实现是否已抢先排队下一张任务 / 确认请求是否已发出"的等待上限（本实现必然超时返回 null） */
    private static final long NEXT_TASK_PROBE_MILLIS = 2000;

    @TempDir
    Path dir;

    // ==================== 桩：事件记录 + 确认收卡 ====================

    /** 事件记录桩：与迁移前 FakeUi 的计数面一一对应（导入在途/收尾/每轮刷新/首行选中/收尾预解析载荷/扫描失败）。 */
    private static final class EventRecorder {

        volatile boolean finished;
        int started;
        int finishedCalls;
        int refreshCalls;
        int selectFirstCalls;
        int failedCalls;
        boolean refreshThrows;
        List<String> finishedPaths = List.of();

        void attach(WorkspaceImportCoordinator coordinator) {
            coordinator.subscribe(event -> {
                if (event instanceof PortEvent.ImportStateChanged s) {
                    if (s.importing()) {
                        started++;
                    }
                } else if (event instanceof PortEvent.ImportRound round) {
                    refreshCalls++;
                    if (round.madeListNonEmpty()) {
                        selectFirstCalls++;
                    }
                    if (refreshThrows) {
                        refreshThrows = false;
                        throw new IllegalStateException("refresh boom");
                    }
                } else if (event instanceof PortEvent.ImportFinished f) {
                    finishedCalls++;
                    finished = true;
                    // 收尾统一预解析的载荷（前端按「非空 + 参数已确认」门触发，见 MainController.applyImportFinished）
                    finishedPaths = f.accumulatedPaths();
                } else if (event instanceof PortEvent.Notice n && "error.IMPORT_FAILED".equals(n.key())) {
                    failedCalls++;
                }
            });
        }
    }

    /** 确认桩：扫描线程同步发出确认请求（apply 落地之后），测试线程经 {@link #awaitRequest} 取走令牌并按序回答。 */
    private static final class FakeConfirmer implements Confirmer {

        final List<String> cardMessages = new ArrayList<>();
        volatile String lastCardMessage;
        volatile ConfirmRequest lastRequest;
        private final BlockingQueue<BlockingToken> tokens = new LinkedBlockingQueue<>();

        @Override
        public ConfirmToken confirm(ConfirmRequest request) {
            lastCardMessage = render(request);
            lastRequest = request;
            cardMessages.add(lastCardMessage);
            BlockingToken token = new BlockingToken();
            tokens.add(token);
            return token;
        }

        private String render(ConfirmRequest request) {
            var p = request.namedParams();
            return Texts.get(request.key(), "groups", p.get("groups"), "files", p.get("files"))
                    + Texts.get("import.quotaContinue") + Texts.get("import.quotaStop",
                    "groups", p.get("accumulatedGroups"), "files", p.get("accumulatedFiles"));
        }

        /** 测试线程阻塞取走令牌（扫描线程尚未发出确认请求时等待 —— 超时 = 实现回归）。 */
        BlockingToken awaitRequest(long millis) throws InterruptedException {
            return tokens.poll(millis, TimeUnit.MILLISECONDS);
        }
    }

    /** 测试可控的阻塞令牌：{@link #answer}/{@link #cancel} 由测试线程调用，扫描线程在 {@link #await} 上等待。 */
    private static final class BlockingToken implements ConfirmToken {

        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile String actionKey;

        @Override
        public void answer(String actionKey) {
            this.actionKey = actionKey;
            latch.countDown();
        }

        @Override
        public void cancel() {
            actionKey = null;
            latch.countDown();
        }

        @Override
        public String await() throws InterruptedException {
            latch.await();
            return actionKey;
        }
    }

    // ==================== 工具 ====================

    private static File mht(Path parent, String name) throws Exception {
        return Files.createFile(parent.resolve(name)).toFile();
    }

    /** A 树：根目录 2 个顶层 MHT + d1..d4 各 1 个 MHT（轮次切分见类 javadoc） */
    private Path treeA() throws Exception {
        Path root = dir.resolve("tree-a");
        Files.createDirectories(root);
        mht(root, "top1.mht");
        mht(root, "top2.mht");
        for (String s : List.of("d1", "d2", "d3", "d4")) {
            Path sub = root.resolve(s);
            Files.createDirectories(sub);
            mht(sub, "in.mht");
        }
        return root;
    }

    /** 与生产 {@code WorkspaceImportCoordinator.awaitQuotaConfirm} 相同的拼装口径（同键同参），断言随 locale 走 */
    private static String quotaCard(int filesKept, int groupsKept) {
        return Texts.get("import.quotaReached", "groups", MAX_GROUPS, "files", MAX_FILES)
                + Texts.get("import.quotaContinue")
                + Texts.get("import.quotaStop", "files", filesKept, "groups", groupsKept);
    }

    private static WorkspaceImportCoordinator coordinator(WorkspaceModel ws, FakeConfirmer cards,
                                                          BlockingQueue<Runnable> q) {
        return new WorkspaceImportCoordinator(ws, cards, q::add, MAX_GROUPS, MAX_FILES);
    }

    /** 每张卡的回答决策（continue = 动作键首项 / stop = 取消） */
    private interface CardHandler {
        void handle(int index, BlockingToken token);
    }

    /**
     * 驱动导入直至收尾（测试线程扮演宿主线程）。返回 apply 落地时抛出的异常（"异常收尾"场景用）。
     *
     * <p>每一轮：先取出 apply 并<b>按住</b>，短等下一张任务（实现回归时下一轮 apply 会被抢先排队）；
     * 超时（本实现：扫描线程等在完成信号上）则先跑 apply、再等确认请求 —— 本轮触发配额时确认请求
     * 在 apply 落地后由扫描线程同步发出（不经派发器），跑到配额卡就经 {@code onCard} 回答（继续/停止）；
     * 无确认请求时（空轮 / 最后一轮无配额命中），下一张任务必须是收尾任务。</p>
     */
    private List<Throwable> drive(BlockingQueue<Runnable> q, EventRecorder ui, FakeConfirmer cards,
                                  CardHandler onCard) throws Exception {
        List<Throwable> failures = new ArrayList<>();
        int cardIndex = 0;
        while (!ui.finished) {
            Runnable applyTask = q.poll(15, TimeUnit.SECONDS);
            assertNotNull(applyTask, "扫描线程应在超时前派发本轮 apply");
            Runnable next = q.poll(NEXT_TASK_PROBE_MILLIS, TimeUnit.MILLISECONDS);
            try {
                applyTask.run();
            } catch (RuntimeException ex) {
                failures.add(ex);          // apply 在宿主侧失败：记录后继续驱动（会话收尾必须照常）
            }
            if (ui.finished) {
                continue;                  // applyTask 本身就是收尾任务：无后续任务，交由循环条件退出
            }
            BlockingToken token = cards.awaitRequest(NEXT_TASK_PROBE_MILLIS);
            if (token != null) {
                if (next != null) {
                    fail("配额卡未回答前下一张任务已被抢先排队（确认等待失效）");
                }
                onCard.handle(cardIndex++, token);
            } else {
                // 无确认请求：下一张任务必须是收尾（空轮 / 最后一轮无配额命中）
                if (next == null) {
                    next = q.poll(15, TimeUnit.SECONDS);
                }
                assertNotNull(next, "无确认请求时应有收尾任务");
                next.run();
            }
        }
        assertTrue(q.isEmpty(), "收尾是最后一次派发，队列应已排空");
        return failures;
    }

    // ==================== 用例 ====================

    /** a. 单轮达限：第一张卡上的"停止后保留数量"必须包含刚完成的一轮；确认停止后只保留已导入轮次 */
    @Test
    void firstQuotaCardCountsIncludeTheRoundThatTriggeredIt() throws Exception {
        WorkspaceModel ws = new WorkspaceModel();
        EventRecorder ui = new EventRecorder();
        FakeConfirmer cards = new FakeConfirmer();
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        WorkspaceImportCoordinator coordinator = coordinator(ws, cards, q);
        ui.attach(coordinator);
        coordinator.importPaths(List.of(treeA().toString()), "A树", false);

        List<Throwable> failures = drive(q, ui, cards, (idx, token) -> {
            assertEquals(0, idx, "只该出第一张卡（停止后不再扫下一轮）");
            // 卡面数字 = 含本轮的累计（3 文件 / 1 组），且与实际模型一致
            assertEquals(quotaCard(3, 1), cards.lastCardMessage, "卡面计数必须包含刚完成的一轮");
            assertEquals(3, ws.visibleCount(), "卡弹出时模型条目数 == 本轮累计");
            assertEquals(1, ws.groups().size(), "卡弹出时模型组数 == 本轮累计");
            token.cancel();                            // 停止
        });

        assertTrue(failures.isEmpty(), "正常路径 apply 不应抛异常：" + failures);
        assertEquals(1, ui.started);
        assertEquals(1, ui.finishedCalls, "会话收尾唯一：finishImport 只跑一次");
        assertEquals(3, ws.visibleCount(), "停止 = 保留已导入轮次（轮 2 不得再扫）");
        assertEquals(1, ws.groups().size());
        assertEquals(0, ui.failedCalls);
    }

    /** e. 整次导入一条撤销记录（单轮停止场景）：栈深 1，一次撤销撤掉整次导入 */
    @Test
    void singleRoundImportLeavesOneUndoRecordUndoingTheWholeImport() throws Exception {
        WorkspaceModel ws = new WorkspaceModel();
        EventRecorder ui = new EventRecorder();
        FakeConfirmer cards = new FakeConfirmer();
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        WorkspaceImportCoordinator coordinator = coordinator(ws, cards, q);
        ui.attach(coordinator);
        coordinator.importPaths(List.of(treeA().toString()), "A树", false);

        drive(q, ui, cards, (idx, token) -> {
            assertEquals(quotaCard(3, 1), cards.lastCardMessage);
            token.cancel();
        });

        assertEquals(1, ws.undoDepth(), "一次导入 = 每条轮恰好一条撤销记录（轮 1 含顶层与 d1 两个批次，不得压两条）");
        assertTrue(ws.undo(), "应能撤销");
        assertEquals(0, ws.visibleCount(), "一次撤销撤整批");
        assertTrue(ws.groups().isEmpty(), "撤销导入应销毁自动创建的组");
        assertFalse(ws.canUndo(), "撤销后栈应清空");
    }

    /** b. 连续多轮达限：每张卡的计数都把对应轮算进去；继续 = 只扫下一轮；最终总数正确 */
    @Test
    void eachQuotaCardOfConsecutiveRoundsCountsItsOwnRound() throws Exception {
        WorkspaceModel ws = new WorkspaceModel();
        EventRecorder ui = new EventRecorder();
        FakeConfirmer cards = new FakeConfirmer();
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        WorkspaceImportCoordinator coordinator = coordinator(ws, cards, q);
        ui.attach(coordinator);
        coordinator.importPaths(List.of(treeA().toString()), "A树", false);

        List<Throwable> failures = drive(q, ui, cards, (idx, token) -> {
            if (idx == 0) {
                assertEquals(quotaCard(3, 1), cards.lastCardMessage, "卡 1 计数 = 轮 1 累计");
                assertEquals(3, ws.visibleCount());
                assertEquals(1, ws.groups().size());
            } else if (idx == 1) {
                assertEquals(quotaCard(5, 3), cards.lastCardMessage, "卡 2 计数 = 轮 1+2 累计（继续只扫了下一轮）");
                assertEquals(5, ws.visibleCount(), "卡 2 弹出时模型只有两轮 —— 继续不得一口气扫完");
                assertEquals(3, ws.groups().size());
            } else {
                fail("轮 3 无配额命中，不应出第三张卡");
            }
            token.answer(cards.lastRequest.actionKeys().get(0));   // 继续
        });

        assertTrue(failures.isEmpty(), "正常路径 apply 不应抛异常：" + failures);
        assertEquals(2, cards.cardMessages.size());
        assertEquals(6, ws.visibleCount(), "三轮累计总数正确");
        assertEquals(4, ws.groups().size());
        assertEquals(1, ui.finishedCalls, "会话收尾唯一");
        assertEquals(6, ui.finishedPaths.size(), "收尾统一预解析载荷 = 全量累计（前端按参数已确认门触发）");

        // BC-5：多轮导入每轮各一条撤销记录，且每条载荷都是「含本轮」的全量累计
        assertEquals(3, ws.undoDepth(), "多轮导入 = 每轮恰好一条撤销记录（不是每一批）");
        assertTrue(ws.undo());
        assertEquals(0, ws.visibleCount(), "一次撤销撤掉多轮导入的全部条目");
        assertTrue(ws.groups().isEmpty());
    }

    /** c. 确认停止：保留已导入轮次，不再扫下一轮（第二轮卡上停止，轮 3 永不落地） */
    @Test
    void stopAtSecondCardKeepsImportedRoundsAndNeverScansFurther() throws Exception {
        WorkspaceModel ws = new WorkspaceModel();
        EventRecorder ui = new EventRecorder();
        FakeConfirmer cards = new FakeConfirmer();
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        WorkspaceImportCoordinator coordinator = coordinator(ws, cards, q);
        ui.attach(coordinator);
        coordinator.importPaths(List.of(treeA().toString()), "A树", false);

        List<Throwable> failures = drive(q, ui, cards, (idx, token) -> {
            if (idx == 0) {
                assertEquals(quotaCard(3, 1), cards.lastCardMessage);
                token.answer(cards.lastRequest.actionKeys().get(0));   // 继续
            } else if (idx == 1) {
                assertEquals(quotaCard(5, 3), cards.lastCardMessage);
                token.cancel();                                        // 停止
            } else {
                fail("停止后不得再出卡");
            }
        });

        assertTrue(failures.isEmpty(), "正常路径 apply 不应抛异常：" + failures);
        assertEquals(2, cards.cardMessages.size());
        assertEquals(5, ws.visibleCount(), "保留已导入的两轮（轮 3 未扫）");
        assertEquals(3, ws.groups().size());
        assertEquals(1, ui.finishedCalls, "会话收尾唯一");
        assertEquals(1, ui.started, "开始按钮状态：置位一次（ImportStateChanged(true)）");
        assertEquals(1, ui.finishedCalls, "开始按钮状态：恢复一次（收尾是唯一恢复点）");
    }

    /** d-1. 空轮次收尾：finishImport 只跑一次、开始按钮恢复、不压撤销记录 */
    @Test
    void emptyRoundFinishesExactlyOnceAndRestoresStartState() throws Exception {
        Path empty = Files.createDirectories(dir.resolve("empty"));
        WorkspaceModel ws = new WorkspaceModel();
        EventRecorder ui = new EventRecorder();
        FakeConfirmer cards = new FakeConfirmer();
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        WorkspaceImportCoordinator coordinator = coordinator(ws, cards, q);
        ui.attach(coordinator);
        coordinator.importPaths(List.of(empty.toString()), "空目录", false);

        List<Throwable> failures = drive(q, ui, cards, (idx, token) -> fail("空轮次不得出现配额卡"));

        assertTrue(failures.isEmpty(), "正常路径 apply 不应抛异常：" + failures);
        assertEquals(1, ui.started);
        assertEquals(1, ui.finishedCalls, "会话收尾唯一");
        assertEquals(0, ws.visibleCount());
        assertEquals(0, ws.undoDepth(), "空导入不得压撤销记录");
        assertEquals(1, ui.refreshCalls, "每轮一次刷新（即使空轮）");
        assertEquals(0, ui.failedCalls);
    }

    /** d-2. 异常收尾：apply 在宿主侧失败后，会话仍唯一收尾、开始按钮恢复、计数快照仍含本轮 */
    @Test
    void applyFailureOnFxSideStillFinishesExactlyOnceWithCurrentRoundCounted() throws Exception {
        WorkspaceModel ws = new WorkspaceModel();
        EventRecorder ui = new EventRecorder();
        ui.refreshThrows = true;                       // 轮 1 的 apply 在模型命令之后、刷新事件交付时抛异常
        FakeConfirmer cards = new FakeConfirmer();
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        WorkspaceImportCoordinator coordinator = coordinator(ws, cards, q);
        ui.attach(coordinator);
        coordinator.importPaths(List.of(treeA().toString()), "A树", false);

        List<Throwable> failures = drive(q, ui, cards, (idx, token) -> {
            assertEquals(0, idx);
            // 计数快照在 apply 的 finally 里构造：即使刷新失败，卡面计数仍含本轮已落地的模型命令
            assertEquals(quotaCard(3, 1), cards.lastCardMessage, "apply 失败也不得退回陈旧计数");
            assertEquals(3, ws.visibleCount());
            assertEquals(1, ws.groups().size());
            token.cancel();
        });

        assertEquals(1, failures.size(), "轮 1 的 apply 应恰好抛一次（桩的 refresh boom）");
        assertEquals("refresh boom", failures.get(0).getMessage());
        assertEquals(1, ui.finishedCalls, "apply 失败后会话仍唯一收尾");
        assertEquals(1, ui.started);
        assertEquals(3, ws.visibleCount(), "停止 = 保留已导入轮次");
        assertEquals(0, ui.failedCalls, "宿主侧 apply 失败不触发扫描线程的失败分支（既有语义）");
    }
}
