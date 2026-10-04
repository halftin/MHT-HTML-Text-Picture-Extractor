package com.mhtextractor.util;

import com.mhtextractor.i18n.I18n;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 导入规划器（导入功能改造）：把一批"地址"（文件/目录绝对路径字符串）解析为有序的导入批次列表，
 * 受每轮配额（组数/文件数）约束分轮产出，配额打满时暂停、下一轮从断点续扫（有状态游标）。
 *
 * <p>本类为纯逻辑层：零 JavaFX 依赖，可单元测试。目录递归规则见需求文档 §3.3：
 * 目录本层 MHT → 顶层平铺批次；每个直接子目录（本层有 MHT）→ 独立建组批次；
 * 隐藏目录跳过；空目录计数；不跟随符号链接；深度上限；绝对路径去重防重复。</p>
 */
public final class MhtImportPlanner {

    /** 目录递归深度上限（R-i） */
    public static final int DEFAULT_MAX_DEPTH = 32;
    /** 每轮组配额（BC-7） */
    public static final int DEFAULT_MAX_GROUPS = 200;
    /** 每轮文件配额（BC-7） */
    public static final int DEFAULT_MAX_FILES = 5000;

    /** 文件名/目录名排序比较器（拍板）：大小写不敏感为主序、大小写敏感为次序。
     *  刻意不用 Collator——它随系统区域设置变化，破坏可复现性与测试稳定性 */
    static final Comparator<File> BY_NAME =
            Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(File::getName);

    private MhtImportPlanner() {
    }

    // ==================== 结果对象 ====================

    /** 一个导入批次 = 一次列表插入动作 */
    public sealed interface Batch {
        /** 来源目录（写入 PreviewItem.sourceFolder） */
        String dirPath();

        /** 已按文件名升序、已按绝对路径去重 */
        List<File> files();
    }

    /** 顶层平铺批次：目录本层文件 / 直接拖入的文件 */
    public record TopLevelBatch(String dirPath, List<File> files) implements Batch {
        public TopLevelBatch {
            files = List.copyOf(files);
        }
    }

    /** 建组批次：一个子文件夹 → 一个组 */
    public record GroupBatch(String dirPath, String folderName, List<File> files) implements Batch {
        public GroupBatch {
            files = List.copyOf(files);
        }
    }

    /** 一轮扫描的结果。各计数器为扫描开始以来的累计值（多轮导入时随轮推进） */
    public record Plan(List<Batch> batches,
                       int ignoredNonMht, int hiddenDirs, int emptyDirs,
                       int unreadable, int depthLimited,
                       boolean quotaHit, boolean hasMore) {
        public Plan {
            batches = List.copyOf(batches);
        }

        public boolean isEmpty() {
            return batches.isEmpty();
        }

        public int fileCount() {
            return batches.stream().mapToInt(b -> b.files().size()).sum();
        }

        public int groupCount() {
            return (int) batches.stream().filter(b -> b instanceof GroupBatch).count();
        }

        /** "N 个文件 / M 个组，忽略非 MHT X 个…" */
        public String summary() {
            return I18n.get("import.summaryFiles",
                    "files", fileCount(), "groups", groupCount(),
                    "ignored", ignoredNonMht, "hidden", hiddenDirs)
                    + I18n.get("import.summaryDirs",
                    "empty", emptyDirs, "unreadable", unreadable, "tooDeep", depthLimited);
        }
    }

    // ==================== 轮式扫描器 ====================

    /**
     * 创建扫描器。仅接受地址字段（文件或目录绝对路径）。
     *
     * <p><b>线程约定</b>：返回的 Scanner 只能被创建它的线程使用；nextRound() 内不得触碰 GUI。</p>
     *
     * @param maxFilesPerRound  每轮文件配额（含）——达到即停并为本轮置 quotaHit=true
     * @param maxGroupsPerRound 每轮组配额（含）——达到即停并为本轮置 quotaHit=true
     */
    public static Scanner scanner(List<String> addresses, int maxDepth,
                                  int maxGroupsPerRound, int maxFilesPerRound) {
        return new Scanner(addresses == null ? List.of() : List.copyOf(addresses),
                maxDepth, maxGroupsPerRound, maxFilesPerRound);
    }

    /** 需要盘符根目录确认的地址（toPath().getParent() == null，对 "D:\" 与 UNC 成立） */
    public static List<String> findDriveRoots(List<String> addresses) {
        if (addresses == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String a : addresses) {
            if (a == null || a.isBlank()) {
                continue;
            }
            try {
                Path p = Path.of(a);
                if (p.getParent() == null) {
                    out.add(a);
                }
            } catch (RuntimeException e) {
                // 非法路径格式：不视为盘符根目录
            }
        }
        return out;
    }

    /** 目录扫描工作项（深度优先游标用） */
    private record DirWork(File dir, int depth) {
    }

    /**
     * 有状态、线程受限的轮式扫描游标：每次 nextRound() 产出一轮批次，
     * 配额打满时剩余工作留在内部队列，下一轮从断点继续（无需重扫）。
     */
    public static final class Scanner {

        private final List<String> addresses;
        private final int maxDepth;
        private final int maxGroupsPerRound;
        private final int maxFilesPerRound;

        /** 已完成待处理的批次（每轮产出，不是全量） */
        private final Deque<Batch> pendingBatches = new ArrayDeque<>();
        /** 待访问的目录工作项（深度优先，用 addFirst 保证子目录先于同级后续目录被展开） */
        private final Deque<DirWork> pendingDirs = new ArrayDeque<>();
        /** 文件绝对路径去重（整个导入过程内不重复产出同一文件） */
        private final Set<String> seenFiles = new HashSet<>();
        /** 目录绝对路径去重（canonical），防环 */
        private final Set<String> visitedDirs = new HashSet<>();
        private boolean initialized = false;

        private int filesThisRound;
        private int groupsThisRound;
        private int ignoredNonMht;
        private int hiddenDirs;
        private int emptyDirs;
        private int unreadable;
        private int depthLimited;

        private Scanner(List<String> addresses, int maxDepth,
                        int maxGroupsPerRound, int maxFilesPerRound) {
            this.addresses = addresses;
            this.maxDepth = maxDepth;
            this.maxGroupsPerRound = maxGroupsPerRound;
            this.maxFilesPerRound = maxFilesPerRound;
        }

        /** 扫描下一轮；返回后 hasMore() 表示是否还有剩余工作 */
        public Plan nextRound() {
            filesThisRound = 0;
            groupsThisRound = 0;
            if (!initialized && pendingBatches.isEmpty() && pendingDirs.isEmpty()) {
                init();
            }
            List<Batch> out = new ArrayList<>();
            while (true) {
                Batch batch = pendingBatches.peekFirst();
                if (batch != null) {
                    // 配额守卫：本轮已产出则不允许再超配额（首个批次例外——单批必须整体消费）
                    if (filesThisRound + batch.files().size() > maxFilesPerRound && filesThisRound > 0) {
                        break;
                    }
                    if (batch instanceof GroupBatch
                            && groupsThisRound >= maxGroupsPerRound && groupsThisRound > 0) {
                        break;
                    }
                    pendingBatches.pollFirst();
                    if (batch instanceof GroupBatch) {
                        groupsThisRound++;
                    }
                    filesThisRound += batch.files().size();
                    out.add(batch);
                } else {
                    DirWork work = pendingDirs.pollFirst();
                    if (work == null) {
                        break;
                    }
                    expandDir(work.dir(), work.depth());
                }
            }
            boolean quotaHit = filesThisRound >= maxFilesPerRound || groupsThisRound >= maxGroupsPerRound;
            boolean hasMore = !pendingBatches.isEmpty() || !pendingDirs.isEmpty();
            return new Plan(out, ignoredNonMht, hiddenDirs, emptyDirs, unreadable, depthLimited,
                    quotaHit, hasMore);
        }

        /** 首次扫描：按地址分派（MHT 文件 → 单文件顶层批次；目录 → 展开；其余计数忽略） */
        private void init() {
            initialized = true;
            for (String address : addresses) {
                if (address == null || address.isBlank()) {
                    continue;
                }
                File f = new File(address);
                if (!f.exists()) {
                    unreadable++;
                    continue;
                }
                if (f.isDirectory()) {
                    if (isSidecarDir(f)) {
                        continue;              // R6.4：侧车资源目录不是输入来源（见 isSidecarDir）
                    }
                    // 已作为其它地址的子目录展开过的目录：跳过（防多地址双重展开使计数翻倍；
                    // 其文件/批次已由祖先地址的展开产出）
                    try {
                        if (!visitedDirs.add(f.getCanonicalPath())) {
                            continue;
                        }
                    } catch (IOException e) {
                        // 无法规范化：仍尝试展开
                    }
                    // 立即展开一层（不再同时入队 DirWork：双重展开会使 hiddenDirs/emptyDirs/
                    // depthLimited 计数翻倍——去重仅挡批次，不挡计数）
                    expandDir(f, 0);
                } else if (isMht(f) && Files.isRegularFile(f.toPath())) {
                    if (seenFiles.add(f.getAbsolutePath())) {
                        String parent = f.getParent();
                        pendingBatches.addLast(new TopLevelBatch(parent == null ? "" : parent,
                                List.of(f)));
                    }
                } else {
                    ignoredNonMht++;
                }
            }
        }

        /** 展开一层目录：本层 MHT → 顶层批次；每个直接子目录（本层有 MHT）→ 建组批次；
         *  子目录工作项以 addFirst 入队保证深度优先。不触碰任何 GUI。 */
        private void expandDir(File dir, int depth) {
            File[] entries = dir.listFiles();
            if (entries == null) {
                unreadable++;
                return;
            }
            List<File> topFiles = new ArrayList<>();
            List<File> subDirs = new ArrayList<>();
            for (File f : entries) {
                if (f.isDirectory()) {
                    if (!Files.isSymbolicLink(f.toPath())) {
                        subDirs.add(f);
                    }
                } else if (isMht(f) && Files.isRegularFile(f.toPath())
                        && seenFiles.add(f.getAbsolutePath())) {
                    topFiles.add(f);
                } else if (!isMht(f)) {
                    ignoredNonMht++;   // 目录内非 MHT 常规文件同样计入（§5.6 自动化断言口径）
                }
            }
            topFiles.sort(BY_NAME);
            if (!topFiles.isEmpty()) {
                pendingBatches.addLast(new TopLevelBatch(dir.getAbsolutePath(), topFiles));
            }
            if (subDirs.isEmpty()) {
                if (entries.length == 0) {
                    emptyDirs++;
                }
                return;
            }
            subDirs.sort(BY_NAME);
            for (File s : subDirs) {
                if (isSidecarDir(s)) {
                    continue;                           // R6.4：不计 ignoredNonMht、不建组、不下探
                }
                if (s.getName().startsWith(".")) {
                    hiddenDirs++;                       // R-f：不建组、不递归
                    continue;
                }
                if (depth + 1 >= maxDepth) {
                    depthLimited++;                     // R-i：超深不再下探
                    continue;
                }
                try {
                    if (!visitedDirs.add(s.getCanonicalPath())) {
                        continue;                       // 已访问：防环/防重复
                    }
                } catch (IOException e) {
                    continue;
                }
                List<File> subFiles = collectMhtFiles(s);
                if (!subFiles.isEmpty()) {
                    pendingBatches.addLast(new GroupBatch(s.getAbsolutePath(), s.getName(), subFiles));
                }
                // else：R-c——本层无 MHT，不为 S 建组
                pendingDirs.addFirst(new DirWork(s, depth + 1));
            }
        }

        /** 目录本层 MHT 文件：去重 + 按文件名升序；列举失败计入 unreadable */
        private List<File> collectMhtFiles(File dir) {
            File[] entries = dir.listFiles();
            if (entries == null) {
                unreadable++;
                return List.of();
            }
            List<File> files = new ArrayList<>();
            for (File f : entries) {
                if (!f.isDirectory() && isMht(f) && Files.isRegularFile(f.toPath())
                        && seenFiles.add(f.getAbsolutePath())) {
                    files.add(f);
                }
            }
            files.sort(BY_NAME);
            return files;
        }
    }

    /**
     * 可导入来源判定（R6.1）—— 委托 {@link SourceKinds}，与"解析器选择"共用同一份口径。
     *
     * <p>扩展名集合：{@code .mht}/{@code .mhtml}（历史格式）+ {@code .html}/{@code .htm}（本步新增）；
     * 四者<b>均大小写不敏感</b>。⚠️ MHT 组的大小写不敏感<b>不是本步引入的</b>：改动前这里是
     * {@code f.getName().toLowerCase().endsWith(".mht")}，而基线
     * {@code MhtImportPlannerTest.upperCaseMhtmlExtensionIsMatched} 正锁着这条行为。
     * 本步只做"把既有实现点搬进 {@link SourceKinds} 并新增 HTML 组"，<b>不改 MHT 组语义</b>。</p>
     */
    private static boolean isMht(File f) {
        return SourceKinds.isImportable(f.getName());
    }

    /**
     * 侧车目录判定（R6.4）：被主 HTML 引用的资源目录不得计入
     * {@code ignoredNonMht} / 配额 / 目录深度统计 —— 否则一次"另存为（网页，完整）"
     * 就会往界面上灌几百个"忽略的非 MHT 文件"，还会把配额吃光。
     *
     * <p>判据刻意<b>窄</b>（只认"浏览器另存为"的真实形态，且要求同目录存在对应的 HTML）：
     * 目录名以 {@code _files} 结尾（大小写不敏感）<b>且</b> 父目录里存在同基名的
     * {@code .html}/{@code .htm} 文件。这样 {@code 素材_files} 这种"恰好同名"的普通目录
     * 不会被误吞（必须真的有一份同名 HTML 与之配套）。</p>
     *
     * <p>本步只做"排除"，<b>不把侧车当作输入来源</b>：它永远不会产出批次，也不参与
     * {@code visitedDirs} 去重（排除掉的目录根本不下探）。</p>
     */
    static boolean isSidecarDir(File dir) {
        String name = dir.getName();
        if (!SourceKinds.isSidecarDirName(name)) {
            return false;
        }
        String base = name.substring(0, name.length() - SourceKinds.sidecarSuffix().length());
        File parent = dir.getParentFile();
        if (parent == null) {
            return false;
        }
        for (String ext : SourceKinds.htmlExtensions()) {
            if (new File(parent, base + ext).isFile()) {
                return true;
            }
        }
        return false;
    }
}
