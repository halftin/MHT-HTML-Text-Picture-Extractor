package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.model.ThumbnailData;
import com.mhtextractor.uiport.PortEvent;
import com.mhtextractor.uiport.PreparseResult;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 工作区活动的唯一所有者。命令与交付在宿主串行线程执行，worker 只计算局部结果；
 * 交付前再次校验代际，取消不会让已排队的结果污染新一轮。无界面依赖。
 */
@Slf4j
public final class WorkspaceActivityService {
    private static final long LARGE_FILE_THRESHOLD = 50L * 1024 * 1024;

    public interface Context {
        List<PreviewItem> items();
        boolean textMode();
        AppConfig effectiveParams(PreviewItem item);
        AppConfig effectiveImageParams(PreviewItem item);
    }

    /** 像素解码属于前端；应用层负责加载时序和取消，解码器不写模型/控件。 */
    @FunctionalInterface
    public interface ThumbnailDecoder {
        ThumbnailData decode(PreviewImageData image, int index, AppConfig params);
    }

    @FunctionalInterface
    public interface Parser {
        SourceParseService.ItemParse parse(String path, AppConfig params, boolean textMode) throws Exception;
    }

    private final Context context;
    private final PreviewService previews;
    private final ThumbnailDecoder decoder;
    private final Executor delivery;
    private final Parser parser;
    private final List<Consumer<PortEvent>> listeners = new CopyOnWriteArrayList<>();
    private final List<Thread> previewWorkers = new CopyOnWriteArrayList<>();
    private volatile long generation;
    private volatile long previewGeneration;
    private volatile String loadingPath;
    private PreviewService.Prepared pendingRegion;

    public WorkspaceActivityService(Context context, PreviewService previews,
                                    ThumbnailDecoder decoder, Executor delivery) {
        this(context, previews, decoder, delivery, SourceParseService::parse);
    }

    public WorkspaceActivityService(Context context, PreviewService previews,
                                    ThumbnailDecoder decoder, Executor delivery, Parser parser) {
        this.context = java.util.Objects.requireNonNull(context);
        this.previews = java.util.Objects.requireNonNull(previews);
        this.decoder = java.util.Objects.requireNonNull(decoder);
        this.delivery = java.util.Objects.requireNonNull(delivery);
        this.parser = java.util.Objects.requireNonNull(parser);
    }

    public AutoCloseable subscribe(Consumer<PortEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void emit(PortEvent event) {
        for (var listener : listeners) listener.accept(event);
    }

    public long generation() { return generation; }
    public long previewGeneration() { return previewGeneration; }
    public String loadingPath() { return loadingPath; }

    public static boolean requiresLargeFileConfirmation(long bytes) {
        return bytes > LARGE_FILE_THRESHOLD;
    }

    public synchronized void cancelWorkspaceActivity() {
        generation++;
        emit(new PortEvent.WorkspaceActivityChanged(generation));
        cancelPreviewLoad();
    }

    public synchronized void cancelPreviewLoad() {
        long oldToken = previewGeneration;
        previewGeneration++;
        pendingRegion = null;
        String oldPath = loadingPath;
        loadingPath = null;
        if (oldPath != null) {
            emit(new PortEvent.PreviewLoadStateChanged(previewGeneration, oldPath, false));
            emit(new PortEvent.PreviewLoaded(oldToken, oldPath));
        }
    }

    public synchronized void startPreparse() {
        List<PreviewItem> targets = context.items().stream().filter(i -> !i.removed).toList();
        if (targets.isEmpty()) return;
        generation++;
        emit(new PortEvent.WorkspaceActivityChanged(generation));
        emit(new PortEvent.PreparseStarted(generation));
        preparse(targets);
    }

    public synchronized void startPreparseFor(List<String> paths) {
        preparse(context.items().stream().filter(i -> paths.contains(i.path)).toList());
    }

    private record ParseInput(PreviewItem item, AppConfig params, boolean textMode) {}

    private void preparse(List<PreviewItem> targets) {
        long token = generation;
        // 控件/参数读取留在命令线程，worker 不反向读取界面状态。
        List<ParseInput> inputs = new ArrayList<>();
        for (var item : targets) {
            AppConfig params = context.effectiveParams(item);
            if (params != null) inputs.add(new ParseInput(item, params, context.textMode()));
        }
        if (inputs.isEmpty()) return;
        startDaemon("preview-preparse", () -> {
            for (var input : inputs) {
                if (token != generation) return;
                PreviewItem item = input.item();
                PreparseResult result;
                try {
                    var parsed = parser.parse(item.path, input.params(), input.textMode());
                    boolean readable = parsed.kind() != SourceParseService.Kind.UNPARSEABLE;
                    result = new PreparseResult(item.path,
                            !readable ? PreviewItem.Status.FAILED : parsed.kind() == SourceParseService.Kind.EMPTY
                                    ? PreviewItem.Status.EMPTY : PreviewItem.Status.READY,
                            readable ? null : "preview.unparseable", "", parsed.text(),
                            readable && input.textMode(), parsed.title(), readable && parsed.titleAssigned());
                } catch (Exception e) {
                    result = new PreparseResult(item.path, PreviewItem.Status.FAILED,
                            "preview.parseFailed", e.getMessage(), null, false, null, false);
                }
                PreparseResult finished = result;
                if (token != generation) return;
                dispatch(() -> {
                    if (token != generation) return;
                    // titleAssigned 的逐分支口径原样保留，用户 titleOverride 永不触碰。
                    if (finished.textAssigned()) item.cachedText = finished.text();
                    if (finished.titleAssigned()) item.cachedTitle = finished.title();
                    item.status = finished.status();
                    emit(new PortEvent.PreparseFinished(token, finished));
                    if (!item.removed) refreshPreparseProgress();
                });
            }
        });
    }

    public synchronized void refreshPreparseProgress() {
        List<PreviewItem> visible = context.items().stream()
                .filter(i -> !i.removed && context.effectiveParams(i) != null).toList();
        int done = (int) visible.stream().filter(i -> i.status != PreviewItem.Status.WAITING).count();
        emit(new PortEvent.PreparseProgress(generation, done, visible.size()));
    }

    public synchronized void loadPreview(String path, boolean largeFileConfirmed) {
        loadPreview(path, largeFileConfirmed, false);
    }

    public synchronized void resolvePreviewRegion(long token, String path, boolean accepted) {
        if (token != previewGeneration || pendingRegion == null
                || !pendingRegion.mht().equals(Path.of(path))) return;
        PreviewService.Prepared pending = pendingRegion;
        pendingRegion = null;
        PreviewItem item = context.items().stream().filter(i -> i.path.equals(path)).findFirst().orElse(null);
        AppConfig current = item == null || item.removed ? null : context.effectiveImageParams(item);
        if (current == null || !current.imageExtractionFingerprint().equals(pending.cacheKey().paramFingerprint())) return;
        if (accepted) loadPreview(path, true, true);
        else emit(new PortEvent.PreviewNotice(token, path, "preview.region.declined"));
    }

    private void loadPreview(String path, boolean largeFileConfirmed, boolean acceptEmptyRegion) {
        PreviewItem item = context.items().stream().filter(i -> i.path.equals(path)).findFirst().orElse(null);
        if (item == null || item.removed) return;
        if (loadingPath != null && !path.equals(loadingPath)) cancelPreviewLoad();
        AppConfig effective = context.effectiveParams(item);
        if (effective == null) return;
        AppConfig params = effective.copyExtractionParams();
        if (item.cachedMeta != null && !params.imageExtractionFingerprint().equals(item.cachedMeta.paramFingerprint())) {
            item.cachedThumbs = null;
            item.cachedMeta = null;
        }
        if (item.cachedThumbs != null) {
            emit(new PortEvent.PreviewLoaded(previewGeneration, path));
            if (item.cachedMeta != null) previewNotices(previewGeneration, path, item.cachedMeta.warnings());
            return;
        }
        if (path.equals(loadingPath)) return;
        File file = new File(path);
        if (!largeFileConfirmed && requiresLargeFileConfirmation(file.length())) {
            emit(new PortEvent.PreviewConfirmationRequired(path, item.name, file.length() / 1024 / 1024));
            return;
        }
        final PreviewService.Prepared prepared;
        try {
            prepared = previews.prepare(Path.of(path), params);
        } catch (IOException e) {
            log.error(Texts.msg("log.maincontroller.61", path), e);
            emit(new PortEvent.PreviewLoadFailed(previewGeneration, path, true, e.getMessage()));
            return;
        }
        cancelPreviewLoad();
        long token = previewGeneration;
        loadingPath = path;
        emit(new PortEvent.PreviewLoadStateChanged(token, path, true));
        Thread worker = new Thread(() -> {
            try {
                var result = previews.ensureCachedLocked(prepared, () -> token != previewGeneration, acceptEmptyRegion);
                if (result == null) return;
                List<ThumbnailData> thumbs = new ArrayList<>();
                if (token == previewGeneration) {
                    for (int i = 0; i < result.images().size(); i++) {
                        if (token != previewGeneration) break;
                        var entry = result.images().get(i);
                        final ThumbnailData thumb;
                        try {
                            byte[] bytes = Files.readAllBytes(result.imagesDir().resolve(entry.file()));
                            thumb = decoder.decode(new PreviewImageData(entry.originalName(), entry.sourceId(), bytes), i, params);
                        } catch (IOException e) {
                            log.warn(Texts.msg("log.maincontroller.62", result.imagesDir().resolve(entry.file())), e);
                            continue;
                        }
                        thumbs.add(thumb);
                        int index = i;
                        dispatch(() -> {
                            if (token == previewGeneration)
                                emit(new PortEvent.PreviewThumbnail(token, path, index, thumb));
                        });
                    }
                }
                dispatch(() -> completePreview(token, item, result, thumbs));
            } catch (PreviewService.RegionConfirmationRequired e) {
                dispatch(() -> {
                    if (token != previewGeneration || !path.equals(loadingPath)) return;
                    finishPreview(token, path);
                    if (token != previewGeneration) return;
                    AppConfig current = context.effectiveImageParams(item);
                    if (item.removed || current == null
                            || !current.imageExtractionFingerprint().equals(prepared.cacheKey().paramFingerprint())) return;
                    pendingRegion = prepared;
                    emit(new PortEvent.PreviewRegionConfirmationRequired(token, path));
                });
            } catch (Throwable e) {
                dispatch(() -> {
                    finishPreview(token, path, new PortEvent.PreviewLoadFailed(token, path, false, e.toString()));
                });
            } finally {
                previewWorkers.remove(Thread.currentThread());
                dispatch(() -> finishPreview(token, path));
            }
        }, "preview-image-load");
        previewWorkers.add(worker);
        worker.setDaemon(true);
        worker.start();
        // A hung parser/decoder must not hold the UI in loading state indefinitely.
        java.util.concurrent.CompletableFuture.delayedExecutor(30, java.util.concurrent.TimeUnit.SECONDS)
                .execute(() -> dispatch(() -> {
                    if (token != previewGeneration || !path.equals(loadingPath)) return;
                    finishPreview(token, path, new PortEvent.PreviewLoadFailed(token, path, false,
                            Texts.get("preview.loadTimeout")));
                    if (previewGeneration == token) previewGeneration++;
                    worker.interrupt();
                }));
    }

    private void completePreview(long token, PreviewItem item, PreviewService.PreviewResult result,
                                 List<ThumbnailData> thumbs) {
        // Old completions must not delete a cache being used by a newer request.
        if (token != previewGeneration) return;
        AppConfig imageParams = context.effectiveImageParams(item);
        boolean stale = item.removed || imageParams == null
                || !imageParams.imageExtractionFingerprint().equals(result.meta().paramFingerprint());
        if (stale) {
            startCleanupDaemon("preview-cache-stale-delete", () -> {
                try {
                    previews.deleteCacheOf(result);
                } catch (IOException e) {
                    log.warn(Texts.msg("log.maincontroller.63", previews.cacheDirName(result)), e);
                }
            });
            finishPreview(token, item.path);
            return;
        }
        if (token != previewGeneration) return;
        item.cachedThumbs = thumbs;
        item.cachedMeta = result.meta();
        item.cachedImages = null;
        item.status = result.images().isEmpty() ? PreviewItem.Status.EMPTY : PreviewItem.Status.READY;
        finishPreview(token, item.path);
        previewNotices(token, item.path, result.meta().warnings());
    }

    private void previewNotices(long token, String path, List<com.mhtextractor.model.ExtractWarning> warnings) {
        for (var warning : warnings)
            if (warning.key().startsWith("extract.region.") && !warning.key().equals("extract.region.r5"))
                emit(new PortEvent.PreviewNotice(token, path, warning.key()));
    }

    private void finishPreview(long token, String path) {
        finishPreview(token, path, new PortEvent.PreviewLoaded(token, path));
    }

    /** Exactly one terminal event per owned load, including stale/null results and finally. */
    private void finishPreview(long token, String path, PortEvent terminal) {
        if (token != previewGeneration || !path.equals(loadingPath)) return;
        loadingPath = null;
        emit(new PortEvent.PreviewLoadStateChanged(token, path, false));
        emit(terminal);
    }

    /** 等待所有仍在写盘/解码的 worker，旧请求退出不得擦掉新请求的线程引用。 */
    public void awaitPreviewInFlight(long millis) {
        long end = System.nanoTime() + millis * 1_000_000;
        for (Thread worker : List.copyOf(previewWorkers)) {
            if (worker == Thread.currentThread()) continue;
            long remaining = end - System.nanoTime();
            if (remaining <= 0) return;
            try {
                worker.join(Math.max(1, remaining / 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void startDaemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    /** 清理 worker 纳入在途清单：删除落盘缓存期间可被 awaitPreviewInFlight join，
     *  关窗/测试收尾不再与外部递归删除（预览缓存退出清空 / @TempDir 清理）赛跑。 */
    private void startCleanupDaemon(String name, Runnable task) {
        Thread thread = new Thread(() -> {
            try {
                task.run();
            } finally {
                previewWorkers.remove(Thread.currentThread());
            }
        }, name);
        previewWorkers.add(thread);
        thread.setDaemon(true);
        thread.start();
    }

    private void dispatch(Runnable task) {
        delivery.execute(() -> {
            synchronized (this) {
                task.run();
            }
        });
    }
}
