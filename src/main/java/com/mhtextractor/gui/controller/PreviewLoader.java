package com.mhtextractor.gui.controller;

import com.mhtextractor.app.Texts;
import com.mhtextractor.gui.component.GuiNotifications;
import com.mhtextractor.uicore.PreviewRow;
import com.mhtextractor.uiport.PortEvent;
import com.mhtextractor.uiport.Uiport;

/** 预览加载的前端呈现。阈值、缓存、worker 与在途状态由端口后端持有。 */
public final class PreviewLoader {
    public interface Host {
        Object currentSelection();
        PreviewRenderer renderer();
        javafx.scene.control.ProgressBar progressBar();
        javafx.scene.control.Label statusLabel();
        javafx.scene.control.Label imageHintLabel();
        javafx.scene.control.Button previewEffectButton();
        boolean extractionRunning();
        void setProgressBarActivity(String styleClass);
        void updateProgressStatus(long token);
        void refreshList();
        void updateContentArea(Object selection);
    }

    private final Host host;
    private final Uiport port;

    public PreviewLoader(Host host, Uiport port) {
        this.host = host;
        this.port = port;
        port.events().subscribe(this::accept);
    }

    public void load() { load(false); }
    public void cancel() { port.commands().cancelPreviewLoad(); }
    public boolean isActive() { return port.queries().previewLoadingPath() != null; }
    public boolean isLoading(PreviewRow item) { return item.path.equals(port.queries().previewLoadingPath()); }
    public void awaitInFlight(long millis) { port.commands().awaitPreviewInFlight(millis); }

    private void load(boolean largeFileOk) {
        if (host.currentSelection() instanceof PreviewRow item)
            port.commands().loadPreview(item.path, largeFileOk);
    }

    private void accept(PortEvent event) {
        if (event instanceof PortEvent.PreviewNotice notice) {
            if (current(notice.generation(), notice.path()))
                com.mhtextractor.gui.component.NotificationHost.toastWarning(host.statusLabel(),
                        Texts.get(notice.key()), org.kordamp.ikonli.fontawesome5.FontAwesomeSolid.IMAGE);
        } else if (event instanceof PortEvent.PreviewRegionConfirmationRequired confirm) {
            if (!current(confirm.generation(), confirm.path())) return;
            com.mhtextractor.gui.component.NotificationHost.confirm(host.statusLabel(),
                    Texts.get("preview.region.confirm"), org.kordamp.ikonli.fontawesome5.FontAwesomeSolid.IMAGE,
                    ok -> port.commands().resolvePreviewRegion(confirm.generation(), confirm.path(),
                            ok && current(confirm.generation(), confirm.path())));
        } else if (event instanceof PortEvent.PreviewConfirmationRequired confirm) {
            String msg = Texts.get("preview.largeFileConfirmHeader") + "\n"
                    + confirm.name() + Texts.get("preview.largeFileConfirmBody", "size", confirm.sizeMiB());
            GuiNotifications.largeFileConfirm(host.statusLabel(), msg, ok -> {
                if (ok) load(true); // 沿用确认后对当前选择重入的语义。
            });
        } else if (event instanceof PortEvent.PreviewLoadStateChanged state) {
            if (state.generation() != port.queries().previewGeneration()) return;
            if (state.active()) {
                host.imageHintLabel().setText(Texts.get("preview.imageLoading"));
                host.previewEffectButton().setDisable(true);
                if (!host.extractionRunning()) {
                    host.progressBar().setVisible(true);
                    host.progressBar().setProgress(-1);
                    host.setProgressBarActivity(null);
                    if (host.currentSelection() instanceof PreviewRow item)
                        host.statusLabel().setText(Texts.get("preview.loadingImage", "name", item.name));
                }
            } else {
                host.previewEffectButton().setDisable(false);
                host.updateContentArea(host.currentSelection());
                host.updateProgressStatus(port.queries().activityGeneration());
            }
        } else if (event instanceof PortEvent.PreviewThumbnail thumb) {
            if (current(thumb.generation(), thumb.path())) {
                PreviewRow item=(PreviewRow)host.currentSelection();
                var decoded=thumb.imageFile()==null?thumb.thumbnail():host.renderer().decodeFile(thumb.imageFile());
                if(item.cachedThumbs==null)item.cachedThumbs=new java.util.ArrayList<>();
                else if(!(item.cachedThumbs instanceof java.util.ArrayList<?>))item.cachedThumbs=new java.util.ArrayList<>(item.cachedThumbs);
                while(item.cachedThumbs.size()<=thumb.index())item.cachedThumbs.add(null);
                item.cachedThumbs.set(thumb.index(),decoded);
                host.renderer().appendThumb(item,decoded,thumb.index(),thumb.imageFile());
                item.retainImageData();
            }
        } else if (event instanceof PortEvent.PreviewLoaded loaded) {
            if (!current(loaded.generation(), loaded.path())) return;
            PreviewRow item = (PreviewRow) host.currentSelection();
            if(item.cachedThumbs==null)item.cachedThumbs=java.util.List.of();
            item.retainImageData();
            if (item.status == PreviewRow.Status.EMPTY) host.refreshList();
            host.updateContentArea(item);
        } else if (event instanceof PortEvent.PreviewLoadFailed failed) {
            if (!current(failed.generation(), failed.path())) return;
            if (failed.fileInfo()) {
                GuiNotifications.readFileInfoFailed(host.statusLabel());
            } else {
                String detail = failed.detailKey() == null ? failed.detail() : Texts.get(failed.detailKey());
                host.imageHintLabel().setText(Texts.get("preview.parseFailed") + detail);
                host.imageHintLabel().setVisible(true);
                host.previewEffectButton().setDisable(false);
                // 失败也不再静默：右上角 toast（与区域 R2/R3/R4 的提醒同一入口）。
                com.mhtextractor.gui.component.NotificationHost.toastWarning(host.statusLabel(),
                        Texts.get("preview.parseFailed") + detail,
                        org.kordamp.ikonli.fontawesome5.FontAwesomeSolid.EXCLAMATION_TRIANGLE);
            }
        }
    }

    private boolean current(long generation, String path) {
        return generation == port.queries().previewGeneration()
                && host.currentSelection() instanceof PreviewRow item && item.path.equals(path);
    }
}
