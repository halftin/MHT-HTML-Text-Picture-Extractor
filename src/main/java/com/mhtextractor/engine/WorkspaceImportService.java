package com.mhtextractor.engine;

import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.processor.BatchProcessor;
import com.mhtextractor.util.GroupStructure;
import com.mhtextractor.util.IncrementalImport;
import com.mhtextractor.util.SanitizeUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 导入规则服务（Q2/P1）：把"统一导入"里<b>与界面无关</b>的规则从控制器搬进无头应用层 ——
 * 地址规范化、会话累计状态、一轮计划到"要新建哪些条目/哪些组"的换算、组名计算。
 *
 * <p><b>本类零 JavaFX、零第三方依赖、不调 {@code I18n}</b>（{@code app} 层由 {@code ArchitectureTest} 强制）。
 * 因此它<b>不产出任何用户文案</b>：需要写日志/弹提醒的地方，本类只回传<b>结构化事实</b>
 * （{@link RoundOutcome}），由调用方（B5-1 起 = 同层的
 * {@link WorkspaceImportCoordinator}）用 {@code log.*} 键产出原文案。这样"规则可无头单测"与
 * "日志文案随界面语言走"两件事同时成立。</p>
 *
 * <h2>分层：本类与 {@link WorkspaceImportCoordinator} 的边界</h2>
 * <ul>
 *   <li><b>本类（app）</b>：纯规则与纯数据 —— 规范化、去重口径、条目构造、累计计数、组名计算。
 *       所有方法同步、无线程、不碰任何控件与模型结构。</li>
 *   <li><b>{@link WorkspaceImportCoordinator}（app，B5-1 起）</b>：会话生命周期 —— 后台线程、
 *       宿主派发（生产 = {@code Platform.runLater}）、配额确认往返、模型命令
 *       （{@code WorkspaceModel.appendItems/appendGroup/push}）、刷新事件与唯一收尾。</li>
 * </ul>
 *
 * <h2>为什么会话累计状态（原控制器的 7 个字段）落在这里</h2>
 * <p>原控制器用 7 个字段承载"本次导入的累计"：{@code importAccumulated}、{@code importGroupCount}
 * 与 5 个扫描计数器。它们既不是界面控件，也不是模型结构，只是<b>一轮扫描的会话状态</b>；
 * 放在 {@link Session} 里之后，{@code WorkspaceImportServiceTest} 可以完全不启动界面就验证
 * "累计值是扫描器的累计值"这一语义。</p>
 *
 * <h2>必须保持的既有语义（改动前逐条核对，见 {@code MainController} 原实现）</h2>
 * <ol>
 *   <li><b>去重口径 = 可见项</b>（{@code removed == false}），<b>不是</b>全量项。已移除项仍留在数据序里，
 *       不得视为"已导入"而整批跳过 —— 这是提交 {@code 4dd4852} 修的缺陷（"移除后再也加不回来"），
 *       调用方必须传"可见项路径"，见 {@link IncrementalImport#findNewFiles} 的 javadoc。</li>
 *   <li><b>无新文件 ⇒ 该批次不建组</b>（RISK-1）：{@link ResolvedBatch#skipped()} 为真，调用方只打 debug 日志。</li>
 *   <li><b>组成员只入组、不入顶层</b>：{@code grouped()} 的批次不得再 {@code appendItems}（否则双计）。</li>
 *   <li><b>一轮内去重是增量的</b>：同一轮里前面的批次已产出的条目，对后面的批次同样算"已存在"
 *       （原实现每批都重新读一次当前可见项）。本类用 {@code seen} 集合复现该口径。</li>
 *   <li><b>一次导入 = 一条撤销记录</b>：累计列表每轮都携带全量（最后一次 push 覆盖前几次的效果），
 *       本类只负责累计，压记录在 GUI 层。</li>
 * </ol>
 */
public final class WorkspaceImportService {

    // ==================== 会话 ====================

    /**
     * 导入会话：扫描器（每一轮从断点续扫）+ 本次导入的累计状态。
     *
     * <p><b>不持有任何线程与界面对象</b>：线程与派发在 {@link WorkspaceImportCoordinator}。
     * 扫描器只能被创建它的线程使用（见 {@link MhtImportPlanner#scanner}），故本对象跨线程时
     * 只有"累计字段"被两方读写 —— 与迁移前控制器字段的形态一致（写方是 GUI 线程，读方是导入后台线程
     * 构造配额文案与收尾汇总）。</p>
     */
    public static final class Session {

        private final List<String> addresses;
        private final String sourceDesc;
        private final boolean fromDrag;
        private final MhtImportPlanner.Scanner scanner;

        /** 本次导入累计新增的条目（收尾统一预解析，避免 O(n²)） */
        private final List<PreviewItem> accumulated = new ArrayList<>();
        /** 本次导入累计自动创建的组数（配额确认框/收尾汇总用） */
        private int groupCount;
        /** 扫描计数器：Plan 里给的是扫描器累计值，每轮原样同步（即使本轮无批次） */
        private int ignoredNonMht;
        private int hiddenDirs;
        private int emptyDirs;
        private int unreadable;
        private int depthLimited;

        private Session(List<String> addresses, String sourceDesc, boolean fromDrag,
                        MhtImportPlanner.Scanner scanner) {
            this.addresses = addresses;
            this.sourceDesc = sourceDesc;
            this.fromDrag = fromDrag;
            this.scanner = scanner;
        }

        public List<String> addresses() {
            return addresses;
        }

        public String sourceDesc() {
            return sourceDesc;
        }

        public boolean fromDrag() {
            return fromDrag;
        }

        /** 本次导入累计新增的条目（只读快照） */
        public List<PreviewItem> accumulated() {
            return List.copyOf(accumulated);
        }

        public int groupCount() {
            return groupCount;
        }

        public int ignoredNonMht() {
            return ignoredNonMht;
        }

        public int hiddenDirs() {
            return hiddenDirs;
        }

        public int emptyDirs() {
            return emptyDirs;
        }

        public int unreadable() {
            return unreadable;
        }

        public int depthLimited() {
            return depthLimited;
        }
    }

    /**
     * 开始一个导入会话（建扫描器 + 计数清零）。
     *
     * <p>配额用 {@link MhtImportPlanner} 的默认值（与迁移前控制器私有常量同源）。</p>
     */
    public Session begin(List<String> normalized, String sourceDesc, boolean fromDrag) {
        return begin(normalized, sourceDesc, fromDrag,
                MhtImportPlanner.DEFAULT_MAX_GROUPS, MhtImportPlanner.DEFAULT_MAX_FILES);
    }

    /**
     * 开始一个导入会话（配额可注入）。
     *
     * <p><b>为什么把配额做成参数</b>：生产路径恒用默认值（见上一个重载），而"每轮配额打满 ⇒
     * quotaHit/hasMore 逐轮传递"这条语义只有在配额可缩小时才能用<b>真实扫描器</b>在单测里复现
     * （否则要造 200 个组的目录树）。注入的值必须与调用方打印的配额数一致 ——
     * 生产调用方（{@link WorkspaceImportCoordinator}）打印的就是 {@link MhtImportPlanner} 的默认常量。</p>
     */
    public Session begin(List<String> normalized, String sourceDesc, boolean fromDrag,
                         int maxGroupsPerRound, int maxFilesPerRound) {
        List<String> addrs = normalized == null ? List.of() : List.copyOf(normalized);
        return new Session(addrs, sourceDesc, fromDrag, MhtImportPlanner.scanner(
                addrs, MhtImportPlanner.DEFAULT_MAX_DEPTH, maxGroupsPerRound, maxFilesPerRound));
    }

    /** 扫描下一轮（由导入后台线程调用；返回后 {@code hasMore()} 表示是否还有剩余工作） */
    public MhtImportPlanner.Plan nextRound(Session session) {
        return session.scanner.nextRound();
    }

    // ==================== 一轮计划的规则换算 ====================

    /**
     * 一个批次换算之后的结论。
     *
     * @param dirPath    来源目录（跳过日志用）
     * @param folderName 建组批次的文件夹名；非建组批次为 {@code null}
     * @param grouped    true = 该批次应建组（成员只入组、不入顶层）；false = 平铺到顶层
     * @param items      本轮要新建的条目（{@code skipped()} 为真时为空列表）
     * @param wasEmpty   应用该批次前"列表可见数为 0" ⇒ 调用方需选中首行
     */
    public record ResolvedBatch(String dirPath, String folderName, boolean grouped,
                                List<PreviewItem> items, boolean wasEmpty) {

        public ResolvedBatch {
            items = List.copyOf(items);
        }

        /** 无新文件 ⇒ 该批次不建组（RISK-1） */
        public boolean skipped() {
            return items.isEmpty();
        }

        private static ResolvedBatch skippedBatch(MhtImportPlanner.Batch batch) {
            boolean grouped = batch instanceof MhtImportPlanner.GroupBatch;
            String folderName = batch instanceof MhtImportPlanner.GroupBatch gb ? gb.folderName() : null;
            return new ResolvedBatch(batch.dirPath(), folderName, grouped, List.of(), false);
        }
    }

    /** 一轮计划换算结果（保持批次顺序） */
    public record RoundOutcome(List<ResolvedBatch> batches) {

        public RoundOutcome {
            batches = List.copyOf(batches);
        }

        /** 要真正落地的批次（有新建条目），顺序与扫描顺序一致 */
        public List<ResolvedBatch> applied() {
            return batches.stream().filter(b -> !b.skipped()).toList();
        }
    }

    /**
     * 把一轮 {@link MhtImportPlanner.Plan} 换算为"要新建哪些条目、哪些批次要建组"。
     *
     * <p><b>同时把扫描器累计计数同步进会话</b>（与原 {@code MainController.applyImportPlan} 首段一致：
     * 即使本轮无批次也要同步，收尾汇总结账用它）。</p>
     *
     * <p><b>为什么要把"可见项快照"作为参数传进来</b>：本类不持有模型，也不该知道"哪些项被移除"。
     * 调用方在<b>本轮开始时</b>取一次快照即可 —— 本轮内后续批次的去重口径已由本方法用
     * {@code seen} 集合增量复现（见类 javadoc 语义第 4 条），因此结果与原实现"每批重新读一次可见项"
     * <b>逐条等价</b>，而不再依赖 FX 线程上的实时读取。</p>
     *
     * @param existingVisiblePaths 列表<b>可见</b>项的绝对路径（<b>不得</b>含已移除项）
     * @param visibleCountAtStart  本轮开始时列表的可见项数量（判定 {@code wasEmpty}）
     */
    public RoundOutcome resolveRound(Session session, MhtImportPlanner.Plan plan,
                                     List<String> existingVisiblePaths, int visibleCountAtStart) {
        syncCounters(session, plan);

        Set<String> seen = new LinkedHashSet<>(existingVisiblePaths == null ? List.of() : existingVisiblePaths);
        List<ResolvedBatch> out = new ArrayList<>();
        boolean produced = false;
        for (MhtImportPlanner.Batch batch : plan.batches()) {
            // 去重：与列表可见项比对（复用 F4）——已移除项仍在数据序中，不得视为"已导入"而整批跳过
            List<File> fresh = IncrementalImport.findNewFiles(batch.files(), seen);
            if (fresh.isEmpty()) {
                out.add(ResolvedBatch.skippedBatch(batch));     // RISK-1 硬约束：无新文件则该批次不建组
                continue;
            }
            List<PreviewItem> items = new ArrayList<>(fresh.size());
            for (File f : fresh) {
                BatchProcessor.MhtFileInfo info = BatchProcessor.toMhtFileInfo(f);
                items.add(new PreviewItem(info.getPath(), info.getName(),
                        info.getModifiedTime(), f.getParent()));
            }
            for (PreviewItem it : items) {
                seen.add(it.path);
            }
            // wasEmpty：原实现是"应用该批次之前 visibleCount() == 0"；本轮内已产出过条目的批次必然不再是 0
            boolean wasEmpty = !produced && visibleCountAtStart == 0;
            produced = true;
            boolean grouped = batch instanceof MhtImportPlanner.GroupBatch;
            String folderName = batch instanceof MhtImportPlanner.GroupBatch gb ? gb.folderName() : null;
            out.add(new ResolvedBatch(batch.dirPath(), folderName, grouped, items, wasEmpty));
        }
        return new RoundOutcome(out);
    }

    private static void syncCounters(Session session, MhtImportPlanner.Plan plan) {
        session.ignoredNonMht = plan.ignoredNonMht();
        session.hiddenDirs = plan.hiddenDirs();
        session.emptyDirs = plan.emptyDirs();
        session.unreadable = plan.unreadable();
        session.depthLimited = plan.depthLimited();
    }

    // ==================== 累计（供 GUI 在模型命令之后调用） ====================

    /**
     * 累计本轮新建的条目。
     *
     * <p><b>为什么累计不放在调用方</b>：撤销记录的载荷是"当时的全量累计"，而配额确认文案也要读它
     * （后台线程）；把累计留在会话里，两个读取点读的是同一份状态，与迁移前读控制器字段等价。</p>
     */
    public void accumulate(Session session, List<PreviewItem> items) {
        if (items != null) {
            session.accumulated.addAll(items);
        }
    }

    /** 累计本轮自动创建的组数（原实现是"本轮建组数 += createdGroups.size()"） */
    public void addCreatedGroups(Session session, int count) {
        session.groupCount += count;
    }

    /** 本次导入累计的条目（只读快照） */
    public List<PreviewItem> accumulated(Session session) {
        return session.accumulated();
    }

    /** 本次导入累计自动创建的组数 */
    public int groupCount(Session session) {
        return session.groupCount();
    }

    // ==================== 组名（纯函数） ====================

    /**
     * 组名计算的中间结果。
     *
     * @param base   sanitize 后（必要时退化为"默认前缀 + 组号"）的原始名
     * @param unique 与既有组名唯一化之后的最终名（{@code log.maincontroller.09} 只在两者不同时打）
     */
    public record GroupName(String base, String unique) {
    }

    /**
     * 组名计算（Q11/R6 之后建组路径还会复用，故抽成纯函数）：
     * sanitize（保证"列表显示名 == 输出名"F10/BC-6）→ 空名退化为"默认前缀 + 组号"→ 与既有组名唯一化（F7）。
     *
     * <p><b>为什么默认前缀由调用方传入</b>：它是界面文案（zh {@code 组} / en {@code Group}，键
     * {@code core.groupDefault}），而本类不调 {@code I18n}。调用方传
     * {@code I18n.get("core.groupDefault")} 即可，规则本身与本类一致。</p>
     *
     * @param existingGroupNames 既有组名集合（唯一来源是 {@code WorkspaceModel.groupNames()}）
     */
    public GroupName resolveGroupName(String folderName, int groupNo, String defaultPrefix,
                                      Collection<String> existingGroupNames) {
        String base = SanitizeUtils.sanitize(folderName);
        if (base.isEmpty()) {
            base = defaultPrefix + groupNo;
        }
        String unique = GroupStructure.uniqueGroupName(base,
                existingGroupNames == null ? List.of() : existingGroupNames);
        return new GroupName(base, unique);
    }
}
