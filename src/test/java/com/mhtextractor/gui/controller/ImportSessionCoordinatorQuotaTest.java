package com.mhtextractor.gui.controller;

import com.mhtextractor.app.WorkspaceModel;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A2 —— 导入配额时序回归测试（{@link ImportSessionCoordinator}）：
 * 配额卡上的"停止后保留 files/groups"必须包含刚完成的那一轮。
 *
 * <h2>复现手法（确定性，不依赖真实 FX 线程调度）</h2>
 * <p>注入<b>延迟派发器</b>（任务进队列，测试线程按序 drain）模拟"FX 线程被占用"，
 * 并注入小配额（2 组 / 3 文件）让<b>真实扫描器</b>每轮打满（不必造 200 个组的目录树）。
 * 本测试<b>绝不初始化 JavaFX toolkit、绝不调用 {@code Platform.runLater}</b>——
 * 无 FX 初始化时那会直接抛 {@code IllegalStateException}。</p>
 *
 * <p>驱动循环里"按住本轮 apply、先看下一张任务是否已经到队"是关键结构：</p>
 * <ul>
 *   <li><b>旧实现</b>（fire-and-forget）：扫描线程把 apply 排队后<b>不等落地</b>就继续，
 *       在扫描线程上读会话累计拼配额卡、再把卡排队 —— 按住 apply 时下一张任务马上就到，
 *       于是测试拿到一张"apply 落地前造好的卡"（卡面计数缺了本轮）→ 断言失败；</li>
 *   <li><b>新实现</b>（显式完成信号）：扫描线程在 {@code applied} 上 join()，apply 不落地
 *       它就不派发下一张任务 —— 按住 apply 时第二次 poll 超时返回 null，测试把 apply 跑掉后
 *       卡才被派发，卡面计数必含本轮 → 断言通过。</li>
 * </ul>
 *
 * <p>于是同一份驱动代码在旧实现上暴露错误数字、在新实现上全绿 —— 这正是"复现竞态"想要的形态。
 * 配额等待方是导入后台线程（本测试里是 {@code mht-import-scan} 线程），"FX 线程"（测试主线程）
 * 不被阻塞：若实现反过来在 FX 线程等后台任务，确认卡永远跑不到 → 驱动循环超时失败。</p>
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
class ImportSessionCoordinatorQuotaTest {

    /** 与生产默认（200/5000）同源语义，但小到能用真实目录树复现"每轮打满" */
    private static final int MAX_GROUPS = 2;
    private static final int MAX_FILES = 3;

    /** 驱动循环里"看旧实现是否已抢先排队下一张任务"的等待上限（新实现必然超时返回 null） */
    private static final long NEXT_TASK_PROBE_MILLIS = 2000;

    @TempDir
    Path dir;

    // ==================== 桩 UI ====================

    /** 只记录调用的桩：本测试无 toolkit，任何方法都不触碰 JavaFX 控件 */
    private static final class FakeUi implements ImportSessionCoordinator.ImportUi {

        final WorkspaceModel ws;
        boolean confirmedParams;
        boolean refreshThrows;
        volatile boolean finished;
        int started;
        int finishedCalls;
        int preParseCalls;
        int refreshCalls;
        int selectFirstCalls;
        int failedCalls;
        List<PreviewItem> preParseItems = List.of();
        final List<String> cardMessages = new ArrayList<>();
        boolean confirmPending;
        String lastCardMessage;
        Consumer<Boolean> pendingAnswer;

        FakeUi(WorkspaceModel ws) {
            this.ws = ws;
        }

        @Override
        public void onImportStarted(List<String> addresses, String sourceDesc, boolean fromDrag) {
            started++;
        }

        @Override
        public void onImportFinished() {
            finishedCalls++;
            finished = true;
        }

        /** 与生产 {@code MainController.visibleSnapshot()} 同口径：可见项（removed == false）的路径 */
        @Override
        public VisibleSnapshot visibleSnapshot() {
            List<String> paths = ws.allItems().stream().filter(i -> !i.removed).map(i -> i.path).toList();
            return new VisibleSnapshot(paths, paths.size());
        }

        @Override
        public boolean hasConfirmedParams() {
            return confirmedParams;
        }

        @Override
        public void startPreParse(List<PreviewItem> items) {
            preParseCalls++;
            preParseItems = List.copyOf(items);
        }

        @Override
        public void refreshList() {
            refreshCalls++;
            if (refreshThrows) {
                refreshThrows = false;
                throw new IllegalStateException("refresh boom");
            }
        }

        @Override
        public void attachGroupNameRefresh(GroupItem<PreviewItem> group) {
            // 无头测试：无列表可刷新，no-op
        }

        @Override
        public void selectFirstRow() {
            selectFirstCalls++;
        }

        @Override
        public void confirmQuotaReached(String message, Consumer<Boolean> answer) {
            cardMessages.add(message);
            lastCardMessage = message;
            pendingAnswer = answer;
            confirmPending = true;
        }

        @Override
        public void showImportFailed() {
            failedCalls++;
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

    /** 与生产 {@code awaitQuotaConfirm} 相同的拼装口径（同键同参），断言随 locale 走 */
    private static String quotaCard(int filesKept, int groupsKept) {
        return I18n.get("import.quotaReached", "groups", MAX_GROUPS, "files", MAX_FILES)
                + I18n.get("import.quotaContinue")
                + I18n.get("import.quotaStop", "files", filesKept, "groups", groupsKept);
    }

    /**
     * 驱动导入直至收尾（测试线程扮演 FX 线程）。返回 apply 落地时抛出的异常（"异常收尾"场景用）。
     *
     * <p>每一轮：先取出 apply 并<b>按住</b>，短等下一张任务（旧实现里配额卡已被抢先排队）；
     * 超时（新实现：扫描线程等在完成信号上）则先跑 apply、再取下一张任务。
     * 跑到配额卡就经 {@code onCard} 回答（继续/停止）。</p>
     */
    private List<Throwable> drive(BlockingQueue<Runnable> q, FakeUi ui, Consumer<Integer> onCard)
            throws Exception {
        List<Throwable> failures = new ArrayList<>();
        int cardIndex = 0;
        while (!ui.finished) {
            Runnable applyTask = q.poll(15, TimeUnit.SECONDS);
            assertNotNull(applyTask, "扫描线程应在超时前派发本轮 apply");
            Runnable next = q.poll(NEXT_TASK_PROBE_MILLIS, TimeUnit.MILLISECONDS);
            try {
                applyTask.run();
            } catch (RuntimeException ex) {
                failures.add(ex);          // apply 在 FX 侧失败：记录后继续驱动（会话收尾必须照常）
            }
            if (ui.finished) {
                continue;                  // applyTask 本身就是收尾任务（停止/空轮场景）：无后续任务，交由循环条件退出
            }
            if (next == null) {
                next = q.poll(15, TimeUnit.SECONDS);
                assertNotNull(next, "apply 落地后应派发配额卡或收尾");
            }
            next.run();
            if (ui.confirmPending) {
                ui.confirmPending = false;
                onCard.accept(cardIndex++);
            }
        }
        assertTrue(q.isEmpty(), "收尾是最后一次派发，队列应已排空");
        return failures;
    }

    private ImportSessionCoordinator coordinator(WorkspaceModel ws, FakeUi ui, BlockingQueue<Runnable> q) {
        return new ImportSessionCoordinator(ws, ui, q::add, MAX_GROUPS, MAX_FILES);
    }

    // ==================== 用例 ====================

    /** a. 单轮达限：第一张卡上的"停止后保留数量"必须包含刚完成的一轮；确认停止后只保留已导入轮次 */
    @Test
    void firstQuotaCardCountsIncludeTheRoundThatTriggeredIt() throws Exception {
        WorkspaceModel ws = new WorkspaceModel();
        FakeUi ui = new FakeUi(ws);
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        coordinator(ws, ui, q).start(List.of(treeA().toString()), "A树", false);

        List<Throwable> failures = drive(q, ui, idx -> {
            assertEquals(0, idx, "只该出第一张卡（停止后不再扫下一轮）");
            // 卡面数字 = 含本轮的累计（3 文件 / 1 组），且与实际模型一致
            assertEquals(quotaCard(3, 1), ui.lastCardMessage, "卡面计数必须包含刚完成的一轮");
            assertEquals(3, ws.visibleCount(), "卡弹出时模型条目数 == 本轮累计");
            assertEquals(1, ws.groups().size(), "卡弹出时模型组数 == 本轮累计");
            ui.pendingAnswer.accept(false);           // 停止
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
        FakeUi ui = new FakeUi(ws);
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        coordinator(ws, ui, q).start(List.of(treeA().toString()), "A树", false);

        drive(q, ui, idx -> {
            assertEquals(quotaCard(3, 1), ui.lastCardMessage);
            ui.pendingAnswer.accept(false);
        });

        assertEquals(1, ws.undoDepth(), "一次导入 = 一条撤销记录");
        assertTrue(ws.undo(), "应能撤销");
        assertEquals(0, ws.visibleCount(), "一次撤销撤整批");
        assertTrue(ws.groups().isEmpty(), "撤销导入应销毁自动创建的组");
        assertFalse(ws.canUndo(), "撤销后栈应清空");
    }

    /** b. 连续多轮达限：每张卡的计数都把对应轮算进去；继续 = 只扫下一轮；最终总数正确 */
    @Test
    void eachQuotaCardOfConsecutiveRoundsCountsItsOwnRound() throws Exception {
        WorkspaceModel ws = new WorkspaceModel();
        FakeUi ui = new FakeUi(ws);
        ui.confirmedParams = true;                     // 验证收尾统一预解析的载荷
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        coordinator(ws, ui, q).start(List.of(treeA().toString()), "A树", false);

        List<Throwable> failures = drive(q, ui, idx -> {
            if (idx == 0) {
                assertEquals(quotaCard(3, 1), ui.lastCardMessage, "卡 1 计数 = 轮 1 累计");
                assertEquals(3, ws.visibleCount());
                assertEquals(1, ws.groups().size());
            } else if (idx == 1) {
                assertEquals(quotaCard(5, 3), ui.lastCardMessage, "卡 2 计数 = 轮 1+2 累计（继续只扫了下一轮）");
                assertEquals(5, ws.visibleCount(), "卡 2 弹出时模型只有两轮 —— 继续不得一口气扫完");
                assertEquals(3, ws.groups().size());
            } else {
                fail("轮 3 无配额命中，不应出第三张卡");
            }
            ui.pendingAnswer.accept(true);             // 继续
        });

        assertTrue(failures.isEmpty(), "正常路径 apply 不应抛异常：" + failures);
        assertEquals(2, ui.cardMessages.size());
        assertEquals(6, ws.visibleCount(), "三轮累计总数正确");
        assertEquals(4, ws.groups().size());
        assertEquals(1, ui.finishedCalls, "会话收尾唯一");
        assertEquals(1, ui.preParseCalls, "导入期抑制预解析、收尾统一跑一次");
        assertEquals(6, ui.preParseItems.size(), "收尾预解析载荷 = 全量累计");

        // 多轮导入"一次撤销撤整批"语义：每条记录均携带全量累计，一次 undo 即清空整次导入
        assertTrue(ws.undo());
        assertEquals(0, ws.visibleCount(), "一次撤销撤掉多轮导入的全部条目");
        assertTrue(ws.groups().isEmpty());
    }

    /** c. 确认停止：保留已导入轮次，不再扫下一轮（第二轮卡上停止，轮 3 永不落地） */
    @Test
    void stopAtSecondCardKeepsImportedRoundsAndNeverScansFurther() throws Exception {
        WorkspaceModel ws = new WorkspaceModel();
        FakeUi ui = new FakeUi(ws);
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        coordinator(ws, ui, q).start(List.of(treeA().toString()), "A树", false);

        List<Throwable> failures = drive(q, ui, idx -> {
            if (idx == 0) {
                assertEquals(quotaCard(3, 1), ui.lastCardMessage);
                ui.pendingAnswer.accept(true);         // 继续
            } else if (idx == 1) {
                assertEquals(quotaCard(5, 3), ui.lastCardMessage);
                ui.pendingAnswer.accept(false);        // 停止
            } else {
                fail("停止后不得再出卡");
            }
        });

        assertTrue(failures.isEmpty(), "正常路径 apply 不应抛异常：" + failures);
        assertEquals(2, ui.cardMessages.size());
        assertEquals(5, ws.visibleCount(), "保留已导入的两轮（轮 3 未扫）");
        assertEquals(3, ws.groups().size());
        assertEquals(1, ui.finishedCalls, "会话收尾唯一");
        assertEquals(1, ui.started, "开始按钮状态：置位一次");
        assertEquals(1, ui.finishedCalls, "开始按钮状态：恢复一次（onImportFinished 是唯一恢复点）");
    }

    /** d-1. 空轮次收尾：finishImport 只跑一次、开始按钮恢复、不压撤销记录 */
    @Test
    void emptyRoundFinishesExactlyOnceAndRestoresStartState() throws Exception {
        Path empty = Files.createDirectories(dir.resolve("empty"));
        WorkspaceModel ws = new WorkspaceModel();
        FakeUi ui = new FakeUi(ws);
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        coordinator(ws, ui, q).start(List.of(empty.toString()), "空目录", false);

        List<Throwable> failures = drive(q, ui, idx -> fail("空轮次不得出现配额卡"));

        assertTrue(failures.isEmpty(), "正常路径 apply 不应抛异常：" + failures);
        assertEquals(1, ui.started);
        assertEquals(1, ui.finishedCalls, "会话收尾唯一");
        assertEquals(0, ws.visibleCount());
        assertEquals(0, ws.undoDepth(), "空导入不得压撤销记录");
        assertEquals(1, ui.refreshCalls, "每轮一次刷新（即使空轮）");
        assertEquals(0, ui.failedCalls);
    }

    /** d-2. 异常收尾：apply 在 FX 侧失败后，会话仍唯一收尾、开始按钮恢复、计数快照仍含本轮 */
    @Test
    void applyFailureOnFxSideStillFinishesExactlyOnceWithCurrentRoundCounted() throws Exception {
        WorkspaceModel ws = new WorkspaceModel();
        FakeUi ui = new FakeUi(ws);
        ui.refreshThrows = true;                       // 轮 1 的 apply 在模型命令之后、刷新时抛异常
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        coordinator(ws, ui, q).start(List.of(treeA().toString()), "A树", false);

        List<Throwable> failures = drive(q, ui, idx -> {
            assertEquals(0, idx);
            // 计数快照在 apply 的 finally 里构造：即使刷新失败，卡面计数仍含本轮已落地的模型命令
            assertEquals(quotaCard(3, 1), ui.lastCardMessage, "apply 失败也不得退回陈旧计数");
            assertEquals(3, ws.visibleCount());
            assertEquals(1, ws.groups().size());
            ui.pendingAnswer.accept(false);
        });

        assertEquals(1, failures.size(), "轮 1 的 apply 应恰好抛一次（桩 UI 的 refresh boom）");
        assertEquals("refresh boom", failures.get(0).getMessage());
        assertEquals(1, ui.finishedCalls, "apply 失败后会话仍唯一收尾");
        assertEquals(1, ui.started);
        assertEquals(3, ws.visibleCount(), "停止 = 保留已导入轮次");
        assertEquals(0, ui.failedCalls, "FX 侧 apply 失败不触发扫描线程的失败分支（既有语义）");
    }
}
