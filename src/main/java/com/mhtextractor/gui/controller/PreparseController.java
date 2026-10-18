package com.mhtextractor.gui.controller;

import com.mhtextractor.app.Texts;
import com.mhtextractor.uicore.PreviewRow;
import com.mhtextractor.uicore.ExtractOutcomeStyles;
import com.mhtextractor.uiport.PortEvent;
import com.mhtextractor.uiport.Uiport;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;

import java.util.List;

/** 预解析事件的前端呈现：文案、视图模型和拖拽/提取期间的刷新守卫。 */
public final class PreparseController {
    public interface Host {
        boolean extractionRunning();
        boolean isPreviewLoading();
        boolean isDragActive();
        boolean isAnimating();
        List<PreviewRow> allItems();
        ProgressBar progressBar();
        Label statusLabel();
        Object currentSelection();
        void refreshList();
        void recomputeBadges();
        void updateInfoArea(Object sel);
        void updateContentArea(Object sel);
        void setProgressBarActivity(String styleClass);
    }

    private final Host host;
    private final Uiport port;

    public PreparseController(Host host, Uiport port) {
        this.host = host;
        this.port = port;
        port.events().subscribe(this::accept);
    }

    public long generation() { return port.queries().activityGeneration(); }

    void startPreParse() { port.commands().startPreparse(); }

    void startPreParseFor(List<PreviewRow> targets) {
        port.commands().startPreparseFor(targets.stream().map(i -> i.path).toList());
    }

    private void accept(PortEvent event) {
        if (event instanceof PortEvent.PreparseStarted started) {
            if (started.generation() != generation() || host.extractionRunning()) return;
            host.progressBar().setVisible(true);
            host.progressBar().setProgress(0);
            host.setProgressBarActivity(ExtractOutcomeStyles.PROGRESS_PREPARSE);
        } else if (event instanceof PortEvent.PreparseFinished finished) {
            if (finished.generation() != generation()) return;
            var result = finished.result();
            // 模型状态已由应用层交付；这里只拼接本地化文案并刷新当前投影。
            PreviewRow item = host.allItems().stream()
                    .filter(i -> i.path.equals(result.path())).findFirst().orElse(null);
            if (item == null) return;
            item.errorKey = result.errorKey();
            item.errorDetail = result.errorDetail();
            if (item.removed) return;
            if (!host.isDragActive() && !host.isAnimating()) {
                host.refreshList();
                host.recomputeBadges();
            }
            Object sel = host.currentSelection();
            host.updateInfoArea(sel);
            if (sel == item) host.updateContentArea(item);
        } else if (event instanceof PortEvent.PreparseProgress progress) {
            if (progress.generation() == generation()) renderProgress(progress.done(), progress.total());
        }
    }

    void updateProgressStatus(long token) {
        if (token == generation()) port.commands().refreshPreparseProgress();
    }

    private void renderProgress(int done, int total) {
        if (host.extractionRunning()) return;
        if (host.isPreviewLoading()) {
            host.progressBar().setVisible(true);
            host.progressBar().setProgress(-1);
            return;
        }
        if (total == 0) {
            host.progressBar().setVisible(false);
            host.setProgressBarActivity(null);
            return;
        }
        if (done >= total) {
            host.progressBar().setVisible(false);
            host.setProgressBarActivity(null);
        } else {
            host.progressBar().setVisible(true);
            host.progressBar().setProgress((double) done / total);
            host.setProgressBarActivity(ExtractOutcomeStyles.PROGRESS_PREPARSE);
        }
        host.statusLabel().setText(Texts.get("preview.parseDone") + done + "/" + total);
    }
}
