package com.mhtextractor.gui.controller;

import com.mhtextractor.app.PreviewImageData;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.gui.component.GuiNotifications;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.model.ThumbnailData;
import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 预览按需加载编排协作者（C1c）—— 承载「查看预览效果」的整条编排，从 {@code MainController}
 * 原样外迁：原 {@code onPreviewEffect() / onPreviewEffect(boolean)} 的主体搬到本类
 * {@link #load(boolean)}；对宿主的全部能力需求收窄为 {@link Host}。渲染一律经
 * {@code host.renderer()} 调 {@link PreviewRenderer}（C1b 外迁的渲染唯一实现点），本类不复制渲染逻辑。
 *
 * <p>文案键（{@code preview.largeFileConfirmHeader/Body}、{@code preview.imageLoading}、
 * {@code preview.loadingImage}、{@code preview.parseFailed}）、日志键与调用次数
 * （{@code log.maincontroller.61/62/63}）、线程名（{@code preview-image-load}、
 * {@code preview-cache-stale-delete}）、代次/陈旧语义（{@code requestToken}）、每项目写锁、
 * 大文件阈值确认（含「重入自己」）、提取运行期间不抢进度条/状态栏的守卫、以及终态回调
 * {@code updateProgressStatus(parseToken)} 全部保持不变。</p>
 *
 * <p>本类含 JavaFX，属 {@code gui} 层；业务侧不得反向依赖它。</p>
 */
@Slf4j
public final class PreviewLoader {

    /** 超大文件完整解析确认阈值（字节） */
    private static final long LARGE_FILE_THRESHOLD = 50L * 1024 * 1024;

    boolean imageLoadActive = false;
    private PreviewItem loadingItem = null;
    /** 在途预览加载线程（规则 d 退出清空时等待其结束用；仅 FX 线程写） */
    private volatile Thread previewLoadThread = null;

    /** 宿主能力窄接口：本类不持有任何控件字段，全部经此接口驱动宿主。 */
    public interface Host {
        /** 列表选择代次（搬迁前是 {@code MainController.requestToken}）。 */
        int requestToken();

        /** 工作区后台活动代次（搬迁前是 {@code MainController.parseToken}；终态回调 {@code updateProgressStatus(parseToken)} 用）。 */
        int parseToken();

        /** 当前列表选中项（搬迁前是 {@code currentSelection()}）。 */
        Object currentSelection();

        /** 条目生效参数（搬迁前是 {@code effectiveForObject(Object)}）。 */
        AppConfig effectiveParams(Object item);

        /** 与模式无关的图片参数（搬迁前是 {@code ParamScope.effectiveImageParams(item.parentGroup, item.imageItemParams, imageParamsCache)}，陈旧判定专用）。 */
        AppConfig effectiveImageParams(PreviewItem item);

        /** 预览服务（搬迁前是 {@code previewService} 字段）。 */
        com.mhtextractor.app.PreviewService previewService();

        /** 预览渲染协作者（搬迁前是 {@code previewRenderer} 字段；渲染唯一实现点）。 */
        PreviewRenderer renderer();

        /** 进度条（搬迁前是 {@code refs.progressBar()}）。 */
        javafx.scene.control.ProgressBar progressBar();

        /** 状态栏标签（搬迁前是 {@code refs.statusLabel()}；兼提醒锚点）。 */
        javafx.scene.control.Label statusLabel();

        /** 图片区提示标签（搬迁前是 {@code refs.imageHintLabel()}）。 */
        javafx.scene.control.Label imageHintLabel();

        /** 按需加载按钮（搬迁前是 {@code refs.previewEffectButton()}）。 */
        javafx.scene.control.Button previewEffectButton();

        /** 提取是否运行中（搬迁前是 {@code running().get()}）。 */
        boolean extractionRunning();

        /** 进度条活动样式切换（搬迁前是 {@code setProgressBarActivity(...)}）。 */
        void setProgressBarActivity(String styleClass);

        /** 进度/状态栏刷新（搬迁前是 {@code updateProgressStatus(token)}）。 */
        void updateProgressStatus(int token);

        /** 列表刷新（搬迁前是 {@code refs.fileListView().refresh()}）。 */
        void refreshList();
    }

    private final Host host;

    public PreviewLoader(Host host) {
        this.host = host;
    }

    /** 等于原 {@code onPreviewEffect()}：按需加载当前选中条目。 */
    public void load() {
        load(false);
    }

    /** 复位加载中的两个标记（与原调用点逐字一致；不动在途线程 {@code previewLoadThread}）。 */
    public void cancel() {
        imageLoadActive = false;
        loadingItem = null;
    }

    /** 是否有加载进行中（原 {@code updateProgressStatus} 的读点）。 */
    public boolean isActive() {
        return imageLoadActive;
    }

    /** 该条目是否正在加载（原 {@code updateContentArea} 的读点）。 */
    public boolean isLoading(PreviewItem item) {
        return imageLoadActive && loadingItem == item;
    }

    /** 等在途加载线程结束（原 {@code clearPreviewCache} 的整段）。 */
    public void awaitInFlight(long millis) {
        Thread t = previewLoadThread;
        if (t != null && t.isAlive() && t != Thread.currentThread()) {
            try {
                t.join(millis);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 图片按需加载主体。{@code largeFileOk=true} = 用户已确认大文件（大文件确认改成窗口内卡片后，
     *  用"重入自己"表达"确认后继续"，好过把后面几十行加载逻辑塞进 lambda）。 */
    private void load(boolean largeFileOk) {
        Object sel = host.currentSelection();
        if (!(sel instanceof PreviewItem item)) {
            return;
        }
        final AppConfig params = host.effectiveParams(item);
        if (params == null) {
            return;
        }
        if (item.cachedThumbs != null) {
            host.renderer().renderThumbnails(item);
            return;
        }
        if (imageLoadActive && loadingItem == item) {
            return;
        }

        File f = new File(item.path);
        if (!largeFileOk && f.length() > LARGE_FILE_THRESHOLD) {
            String msg = I18n.get("preview.largeFileConfirmHeader") + "\n"
                    + item.name + I18n.get("preview.largeFileConfirmBody",
                            "size", f.length() / 1024 / 1024);
            GuiNotifications.largeFileConfirm(host.statusLabel(), msg, ok -> {
                if (ok) {
                    load(true);
                }
            });
            return;
        }

        // 键在 FX 线程算好（搬迁前口径：失败即日志 + 提醒并返回，不进入加载态）；
        // Prepared 承载键与锁语义，gui 不引用任何 cache 类型（P3-3b-2b）。
        final com.mhtextractor.app.PreviewService.Prepared prepared;
        try {
            prepared = host.previewService().prepare(Path.of(item.path), params);
        } catch (IOException e) {
            log.error(I18n.msg("log.maincontroller.61", item.path), e);
            GuiNotifications.readFileInfoFailed(host.statusLabel());
            return;
        }

        final int token = host.requestToken();
        imageLoadActive = true;
        loadingItem = item;
        host.imageHintLabel().setText(I18n.get("preview.imageLoading"));
        host.previewEffectButton().setDisable(true);
        // 相邻修复：提取运行期间进度条/状态栏归提取任务独占，图片加载不动它们
        if (!host.extractionRunning()) {
            host.progressBar().setVisible(true);
            host.progressBar().setProgress(-1);
            host.setProgressBarActivity(null);   // 预览加载 = 默认色（完成后回落预解析显示）
            host.statusLabel().setText(I18n.get("preview.loadingImage", "name", item.name));
        }

        Thread t = new Thread(() -> {
            try {
                // 每项目写锁（§2.6(6)）：同项目「查缓存→提取→落盘」串行，不同项目并行。
                // 缓存业务逻辑已搬入 com.mhtextractor.app.PreviewService（步骤 2），控制器只做 UI。
                // 键已在 FX 线程经 prepare() 算好（P3-3b-2b），此处只持键加锁取缓存。
                final com.mhtextractor.app.PreviewService.PreviewResult pr =
                        host.previewService().ensureCachedLocked(prepared, () -> token != host.requestToken());
                if (token != host.requestToken()) {
                    return;   // 代次失效：放弃上屏（缓存保留，重选时命中复用）
                }
                // C2：缩略图从磁盘真实图片逐张解码，内存只留缩略图 + meta，不驻留全尺寸字节。
                // 元数据（coreName）由无头服务给出；像素解码仍在此处（JavaFX 缩放解码 + PixelReader）。
                List<ThumbnailData> thumbs = new ArrayList<>();
                for (int i = 0; i < pr.images().size(); i++) {
                    if (token != host.requestToken()) {
                        return;
                    }
                    var entry = pr.images().get(i);
                    final ThumbnailData thumb;
                    try {
                        byte[] data = Files.readAllBytes(pr.imagesDir().resolve(entry.file()));
                        thumb = host.renderer().decodeThumb(new PreviewImageData(
                                entry.originalName(), entry.sourceId(), data), i, params);
                    } catch (IOException e) {
                        log.warn(I18n.msg("log.maincontroller.62", pr.imagesDir().resolve(entry.file())), e);
                        continue;
                    }
                    thumbs.add(thumb);
                    final int idx = i;
                    Platform.runLater(() -> {
                        if (token != host.requestToken()
                                || host.currentSelection() != item) {
                            return;
                        }
                        host.renderer().appendThumb(item, thumb, idx);
                    });
                }

                Platform.runLater(() -> {
                    previewLoadThread = null;
                    // 落盘期间参数/条目可能已变化（参数弹窗确认、移除等）：真正失效
                    // （条目已移除或生效图片参数指纹与写盘时不符）时删除刚落盘的目录，避免残留。
                    // 注意用与模式无关的图片参数解析（切模式不影响图片缓存有效性）
                    AppConfig imageParamsNow = host.effectiveImageParams(item);
                    boolean stale = item.removed || imageParamsNow == null
                            || !imageParamsNow.imageExtractionFingerprint()
                                    .equals(pr.meta().paramFingerprint());
                    if (stale) {
                        Thread cleanup = new Thread(() -> {
                            try {
                                // 键由服务经 meta 反推（与写盘时的 key 同一个）
                                host.previewService().deleteCacheOf(pr);
                            } catch (IOException e) {
                                // 目录名经服务入口取：= 写盘键的 64 位目录名（与搬迁前 cacheKey.toDirName() 逐字一致）
                                log.warn(I18n.msg("log.maincontroller.63",
                                        host.previewService().cacheDirName(pr)), e);
                            }
                        }, "preview-cache-stale-delete");
                        cleanup.setDaemon(true);
                        cleanup.start();
                        imageLoadActive = false;
                        loadingItem = null;
                        host.previewEffectButton().setDisable(false);
                        host.updateProgressStatus(host.parseToken());
                        return;
                    }
                    if (token != host.requestToken()
                            || host.currentSelection() != item) {
                        // 既有语义：选中/代次变化时放弃上屏；缓存保留（与选中无关），重选时命中复用
                        return;
                    }
                    imageLoadActive = false;
                    loadingItem = null;
                    item.cachedThumbs = thumbs;
                    item.cachedMeta = pr.meta();
                    item.cachedImages = null;
                    if (pr.images().isEmpty()) {
                        // 全量解析确认无图片 → 空内容标记
                        item.status = PreviewItem.Status.EMPTY;
                        host.refreshList();
                    }
                    host.renderer().renderThumbnails(item);
                    host.updateProgressStatus(host.parseToken());
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    previewLoadThread = null;
                    if (token != host.requestToken()
                            || host.currentSelection() != item) {
                        return;
                    }
                    imageLoadActive = false;
                    loadingItem = null;
                    host.imageHintLabel().setText(I18n.get("preview.parseFailed") + e.getMessage());
                    host.imageHintLabel().setVisible(true);
                    host.previewEffectButton().setDisable(false);
                    host.updateProgressStatus(host.parseToken());
                });
            }
        }, "preview-image-load");
        previewLoadThread = t;
        t.setDaemon(true);
        t.start();
    }
}
