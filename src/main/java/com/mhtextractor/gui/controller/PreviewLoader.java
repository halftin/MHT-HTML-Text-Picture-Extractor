package com.mhtextractor.gui.controller;

import com.mhtextractor.app.Texts;
import com.mhtextractor.gui.component.GuiNotifications;
import com.mhtextractor.model.PreviewItem;
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
    public boolean isLoading(PreviewItem item) { return item.path.equals(port.queries().previewLoadingPath()); }
    public void awaitInFlight(long millis) { port.commands().awaitPreviewInFlight(millis); }

    private void load(boolean largeFileOk) {
        if (host.currentSelection() instanceof PreviewItem item)
            port.commands().loadPreview(item.path, largeFileOk);
    }

    private void accept(PortEvent event) {
        if (event instanceof PortEvent.PreviewConfirmationRequired confirm) {
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
                    if (host.currentSelection() instanceof PreviewItem item)
                        host.statusLabel().setText(Texts.get("preview.loadingImage", "name", item.name));
                }
            } else {
                host.previewEffectButton().setDisable(false);
                host.updateProgressStatus(port.queries().activityGeneration());
            }
        } else if (event instanceof PortEvent.PreviewThumbnail thumb) {
            if (current(thumb.generation(), thumb.path()))
                host.renderer().appendThumb((PreviewItem) host.currentSelection(), thumb.thumbnail(), thumb.index());
        } else if (event instanceof PortEvent.PreviewLoaded loaded) {
            if (!current(loaded.generation(), loaded.path())) return;
            PreviewItem item = (PreviewItem) host.currentSelection();
            if (item.status == PreviewItem.Status.EMPTY) host.refreshList();
            host.renderer().renderThumbnails(item);
        } else if (event instanceof PortEvent.PreviewLoadFailed failed) {
            if (!current(failed.generation(), failed.path())) return;
            if (failed.fileInfo()) {
                GuiNotifications.readFileInfoFailed(host.statusLabel());
            } else {
                host.imageHintLabel().setText(Texts.get("preview.parseFailed") + failed.detail());
                host.imageHintLabel().setVisible(true);
                host.previewEffectButton().setDisable(false);
            }
        }
    }

    private boolean current(long generation, String path) {
        return generation == port.queries().previewGeneration()
                && host.currentSelection() instanceof PreviewItem item && item.path.equals(path);
    }
}
