package com.mhtextractor.app;

import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.processor.BatchProcessor;
import com.mhtextractor.processor.ImageBatchProcessor;
import com.mhtextractor.processor.TextBatchProcessor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 无头应用门面（会话 18 · E4-1）—— 目标 ② 验收 V1/V2 的核心交付物。
 *
 * <p><b>它证明什么</b>：整个"导入 → 参数 → 提取"链路可以<b>完全不依赖 JavaFX</b>运行。
 * 本包由 {@code ArchitectureTest} 强制"依赖闭包零 {@code javafx}"，并由 {@code LayerBTest}
 * 驱动本门面跑通端到端提取，再用产物 SHA-256 与 GUI 侧的黄金基线对齐 ——
 * 即"换 UI 框架只需重写适配层"的可执行证据（验收 V1 + V2）。</p>
 *
 * <p><b>设计边界（刻意不做什么）</b>：</p>
 * <ul>
 *   <li><b>不持有工作区列表状态</b>。GUI 的条目列表是 {@code ObservableList} 且被 ListView 直接绑定
 *       （见 {@code docs/handoff/H17.md} §六之九 的取舍），搬进来会拆掉 GUI 的绑定契约。
 *       因此本门面把"待处理文件的有序列表"作为<b>输入参数</b>，而不是自己攒状态。</li>
 *   <li><b>不碰 UI 文案</b>：进度只经 {@link ProgressSink} 回调表达；文案渲染由调用方负责。</li>
 *   <li><b>不做输出单元分组</b>：分组/分桶是 {@code OutputUnitPlanner} 的职责且需要组结构。
 *       本门面按"文件顺序 = 处理顺序"直驱处理器，与 GUI 在"无分组 + 全局参数"下的行为一致
 *       （{@code LayerBTest} 以此与基线对齐来证明等价）。</li>
 * </ul>
 *
 * <p><b>线程契约</b>：所有方法同步阻塞，且不要求特定线程（可在 FX 线程外调用）。
 * 取消经 {@link CancelToken} 注入。</p>
 */
public final class MhtExtractorApp {

    /** 合并文本模式的默认输出文件名（产物命名属业务层，不随 UI 框架变化） */
    public static final String DEFAULT_MERGED_TEXT_NAME = "merged.txt";

    // ==================== 无头回调接口（零 JavaFX） ====================

    /**
     * 进度接收端。UI 侧自行适配到自己的控件（JavaFX {@code Task#updateProgress}、或其它框架的进度条）
     * —— 这正是"换 UI 只需重写适配层"的接缝。
     */
    public interface ProgressSink {
        /**
         * @param index       当前第几个文件（从 1 开始）
         * @param total       总文件数
         * @param fileName    当前文件名
         * @param subProgress 该文件内部进度 0.0~1.0
         */
        void onProgress(int index, int total, String fileName, double subProgress);
    }

    /** 取消令牌。UI 侧通常包装"用户点了停止"或任务自身取消状态。 */
    public interface CancelToken {
        boolean isCancelled();
    }

    /**
     * 一次提取的结果。
     *
     * @param stats            处理器产出的完整统计（含逐文件 {@code FileResult}）
     * @param sessionOutputDir 本次会话的输出根目录
     */
    public record ExtractResult(BatchProcessor.ProcessStats stats, Path sessionOutputDir) {
    }

    // ==================== 导入 ====================

    /**
     * 扫描给定路径（文件或目录，递归）下的所有 {@code .mht}/{@code .mhtml} 文件。
     *
     * <p>结果为<b>去重后的绝对路径，按路径排序</b>（确定性 —— 便于基线与测试比对）。
     * 目录不存在/不可读时跳过而不抛错（与 GUI 导入链的容错精神一致）。</p>
     *
     * <p><b>与 GUI 导入链的差异（已知且有意）</b>：GUI 的 {@code MhtImportPlanner} 有配额分轮、
     * 自动建组、隐藏目录跳过等策略；本方法只做"收集 + 去重 + 排序"这一最小可用子集。
     * 策略化导入属后续 E4 步骤。</p>
     */
    public List<Path> importPaths(List<String> addresses) {
        List<Path> found = new ArrayList<>();
        if (addresses == null) {
            return found;
        }
        for (String addr : addresses) {
            if (addr == null || addr.isBlank()) continue;
            Path p;
            try {
                p = Path.of(addr);
            } catch (Exception e) {
                continue;
            }
            try {
                if (Files.isRegularFile(p)) {
                    if (isMht(p)) found.add(p.toAbsolutePath().normalize());
                } else if (Files.isDirectory(p)) {
                    try (Stream<Path> walk = Files.walk(p)) {
                        walk.filter(Files::isRegularFile)
                                .filter(this::isMht)
                                .map(f -> f.toAbsolutePath().normalize())
                                .forEach(found::add);
                    }
                }
            } catch (Exception e) {
                // 不可读目录/权限问题：跳过，不中断整批导入
            }
        }
        return found.stream().distinct().sorted(Comparator.comparing(Path::toString)).toList();
    }

    /** 便捷方法：把单个目录作为输入地址 */
    public List<Path> importFolder(Path folder) {
        return importPaths(List.of(folder.toString()));
    }

    private boolean isMht(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".mht") || n.endsWith(".mhtml");
    }

    // ==================== 提取 ====================

    /**
     * 文本提取（合并或单独，由 {@code params.isSeparateFiles()} 决定）。
     *
     * @param files            待处理文件（有序；通常来自 {@link #importPaths}）
     * @param params           文本提取参数
     * @param sessionOutputDir 本次会话输出目录（由调用方创建并命名）
     * @param mergedTextName   合并模式下输出 TXT 的文件名；null/空白用 {@link #DEFAULT_MERGED_TEXT_NAME}
     * @param sink             进度接收端，可为 null
     * @param cancel           取消令牌，可为 null（null = 不可取消）
     */
    public ExtractResult extractText(List<Path> files, AppConfig params, Path sessionOutputDir,
                                     String mergedTextName, ProgressSink sink, CancelToken cancel) {
        List<BatchProcessor.MhtFileInfo> infos = toFileInfos(files);
        TextBatchProcessor p = new TextBatchProcessor(params);
        // BatchProcessor 未暴露 progressCallback 的公开 setter，故把进度经"方法已接收的参数"下传 ——
        // 这也是更干净的注入方式（不改动处理器基类的可见性）
        BatchProcessor.ProgressCallback cb = toCallback(sink);
        if (cancel != null) {
            p.setCancelSupplier(cancel::isCancelled);
        }

        BatchProcessor.ProcessStats stats;
        if (params.isSeparateFiles()) {
            stats = p.processSeparate(infos, sessionOutputDir.toString(), cb, java.util.Map.of());
        } else {
            String name = (mergedTextName == null || mergedTextName.isBlank())
                    ? DEFAULT_MERGED_TEXT_NAME : mergedTextName;
            stats = p.processMerged(infos, sessionOutputDir.resolve(name).toString(), cb,
                    java.util.Map.of());
        }
        return new ExtractResult(stats, sessionOutputDir);
    }

    /**
     * 图片提取。
     *
     * @param previewCache 预览缓存生命周期（可复用已落盘预览）；null = 不复用（与 GUI 未注入时一致）
     */
    public ExtractResult extractImages(List<Path> files, AppConfig params, Path sessionOutputDir,
                                       ProgressSink sink, CancelToken cancel,
                                       PreviewCacheLifecycle previewCache) {
        List<BatchProcessor.MhtFileInfo> infos = toFileInfos(files);
        ImageBatchProcessor p = new ImageBatchProcessor(params, previewCache);
        BatchProcessor.ProgressCallback cb = toCallback(sink);
        if (cancel != null) {
            p.setCancelSupplier(cancel::isCancelled);
        }
        // 合并模式显式传 null 输出名 = 沿用既有命名逻辑（首个文件标题，回退输入目录名）
        BatchProcessor.ProcessStats stats =
                p.process(infos, sessionOutputDir.toString(), null, cb);
        return new ExtractResult(stats, sessionOutputDir);
    }

    // ==================== 内部装配 ====================

    private BatchProcessor.ProgressCallback toCallback(ProgressSink sink) {
        return sink == null ? null : sink::onProgress;
    }

    private List<BatchProcessor.MhtFileInfo> toFileInfos(List<Path> files) {
        if (files == null) return List.of();
        List<BatchProcessor.MhtFileInfo> out = new ArrayList<>(files.size());
        for (Path f : files) {
            out.add(BatchProcessor.toMhtFileInfo(f.toFile()));
        }
        return out;
    }

    /** 便捷方法：把 {@code File} 列表转为 {@code Path} 列表（供 GUI 适配层使用） */
    public static List<Path> toPaths(List<File> files) {
        List<Path> out = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                if (f != null) out.add(f.toPath());
            }
        }
        return out;
    }
}
