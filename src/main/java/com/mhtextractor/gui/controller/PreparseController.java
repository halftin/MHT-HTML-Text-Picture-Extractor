package com.mhtextractor.gui.controller;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.core.MimeParser;
import com.mhtextractor.core.SourceParsers;
import com.mhtextractor.extractor.ImageExtractor;
import com.mhtextractor.extractor.TextExtractor;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.uicore.ExtractOutcomeStyles;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 预解析编排协作者（C5）：全量 / 增量预解析、条目状态回写与预解析进度从 {@code MainController}
 * <b>原样外迁</b> —— 文案键（preview.*）、线程名（preview-preparse）、{@code setDaemon(true)}、
 * 代次语义（parseToken 仍归宿主，本类经 {@link Host} 读写）、两处 {@code token != parseToken} 复查、
 * 进度条活动类 {@code ExtractOutcomeStyles.PROGRESS_PREPARSE} 与"提取运行期间进度条/状态栏
 * 归提取任务独占"的守卫全部保持不变。
 *
 * <p>宿主能力经 {@link Host} 收窄为具名方法：本类不持有 {@code MainController} / {@code MainViewRefs} /
 * {@code WorkspaceModel} / FXML namespace，也不得在数据序上直写（条目状态回写只改
 * {@link PreviewItem} 的 {@code status / errorMsg / cachedText / cachedTitle} 字段）。</p>
 *
 * <p>本类含 JavaFX，属 {@code gui} 层。</p>
 */
@Slf4j
public final class PreparseController {

    /**
     * 宿主能力面（C5）：对 {@code MainController} 的全部能力需求收窄为具名方法。
     * 实现由 {@code MainController} 的匿名内部类提供（转发到其保留成员）。
     */
    public interface Host {
        int parseToken();                                 // = MainController.parseToken
        int nextParseToken();                             // = ++parseToken
        boolean extractionRunning();                      // = running().get()
        boolean isPreviewLoading();                       // = previewLoader.isActive()
        boolean isDragActive();                           // = listInteraction.isDragActive()
        boolean isAnimating();                            // = groupCommand.isAnimating()
        boolean isTextMode();                             // = isTextMode()
        AppConfig effectiveParams(Object item);           // = effectiveForObject(item)
        List<PreviewItem> allVisibleItems();              // = allVisibleItems()
        ProgressBar progressBar();                        // = refs.progressBar()
        Label statusLabel();                              // = refs.statusLabel()
        Object currentSelection();                        // = refs.fileListView().getSelectionModel().getSelectedItem()
        void refreshList();                               // = refs.fileListView().refresh()
        void recomputeBadges();                           // = listInteraction.recomputeBadges()
        void updateInfoArea(Object sel);                  // = infoPresenter.updateInfoArea(sel)
        void updateContentArea(Object sel);               // = MainController.updateContentArea(sel)
        void setProgressBarActivity(String styleClass);   // = setProgressBarActivity(...)
    }

    private final Host host;

    public PreparseController(Host host) {
        this.host = host;
    }

    void startPreParse() {
        List<PreviewItem> targets = host.allVisibleItems();
        if (targets.isEmpty()) {
            return;
        }
        // 提取运行期间进度条归提取任务独占（相邻修复），预解析照常跑但不动进度条
        if (!host.extractionRunning()) {
            host.progressBar().setVisible(true);
            host.progressBar().setProgress(0);
            host.setProgressBarActivity(ExtractOutcomeStyles.PROGRESS_PREPARSE);
        }
        // 快照迭代目标，避免解析期间列表被增量导入/移除修改导致并发异常
        preParseItems(new ArrayList<>(targets), host.nextParseToken());
    }

    /** 增量导入专用：仅预解析新增项目，沿用当前代次（不打断进行中的全量解析） */
    void startPreParseFor(List<PreviewItem> targets) {
        preParseItems(new ArrayList<>(targets), host.parseToken());
    }

    private void preParseItems(List<PreviewItem> targets, int token) {
        if (targets.isEmpty()) {
            return;
        }
        Thread t = new Thread(() -> {
            for (PreviewItem item : targets) {
                if (token != host.parseToken()) {
                    return;
                }
                // 每条目按生效参数解析（6.5）：组参数已设置用组参数，否则继承全局；无生效参数留待参数确认后解析
                AppConfig params = host.effectiveParams(item);
                if (params == null) {
                    continue;
                }
                try {
                    var parser = SourceParsers.forPath(item.path);
                    MimeParser.ParsedMht parsed = parser.parse(item.path, true, () -> false, null, null);   // 来源由扩展名选
                    if (parsed.getHtmlContent() == null) {
                        setItemStatus(token, item, PreviewItem.Status.FAILED, I18n.get("preview.unparseable"));
                        continue;
                    }
                    if (host.isTextMode()) {
                        TextExtractor textExtractor = new TextExtractor(parsed);
                        String text = textExtractor.extractText(params.isAutoFixEncoding(),
                                params.getStartMarker(), params.getEndMarker(), params.isUseRegex());
                        item.cachedText = text;
                        // 标题缓存：与正式提取共用同一 extractTitle 调用（从完整 HTML 提取）
                        if (params.isEnableTitleExtraction()
                                && params.getTitleStartMarker() != null
                                && !params.getTitleStartMarker().isEmpty()) {
                            try {
                                String title = textExtractor.extractTitle(params.getTitleStartMarker(),
                                        params.getTitleEndMarker(), params.isTitleUseRegex());
                                item.cachedTitle = (title != null && !title.isEmpty()) ? title : null;
                            } catch (Exception e) {
                                item.cachedTitle = null;
                            }
                        }
                        setItemStatus(token, item,
                                (text == null || text.trim().isEmpty()) ? PreviewItem.Status.EMPTY : PreviewItem.Status.READY,
                                null);
                    } else {
                        if (params.isEnableNameExtraction()) {
                            ImageExtractor imageExtractor = new ImageExtractor(parsed,
                                    params.getImageNameStartMarker(), params.getImageNameEndMarker(),
                                    null, null);
                            String title = imageExtractor.extractTitle();
                            item.cachedTitle = (title != null && !title.isEmpty()) ? title : null;
                        }
                        setItemStatus(token, item, PreviewItem.Status.READY, null);
                    }
                } catch (Exception e) {
                    setItemStatus(token, item, PreviewItem.Status.FAILED, I18n.get("preview.parseFailed") + e.getMessage());
                }
            }
        }, "preview-preparse");
        t.setDaemon(true);
        t.start();
    }

    private void setItemStatus(int token, PreviewItem item, PreviewItem.Status st, String err) {
        if (token != host.parseToken()) {
            return;
        }
        Platform.runLater(() -> {
            if (token != host.parseToken()) {
                return;
            }
            item.status = st;
            item.errorMsg = err == null ? "" : err;
            if (item.removed) {
                // 已移除项：状态已记录（撤销恢复后可见），跳过 UI 刷新
                return;
            }
            if (!host.isDragActive() && !host.isAnimating()) {
                // 拖拽/折叠动画期间暂停列表刷新，避免打乱动画画面；动画结束后统一重算
                host.refreshList();
                host.recomputeBadges();
            }
            Object sel = host.currentSelection();
            host.updateInfoArea(sel);
            if (sel == item) {
                host.updateContentArea(item);
            }
            updateProgressStatus(token);
        });
    }

    void updateProgressStatus(int token) {
        if (token != host.parseToken()) {
            return;
        }
        // 相邻修复（拍板 2026-09-24）：提取运行期间进度条/状态栏归提取任务独占，
        // 预解析完成等回调一律不更新（避免覆盖提取进度显示）
        if (host.extractionRunning()) {
            return;
        }
        if (host.isPreviewLoading()) {
            host.progressBar().setVisible(true);
            host.progressBar().setProgress(-1);
            return;
        }
        // 拍板 2026-09-24（方案A）：只统计有生效参数的条目。无参数条目由 preParseItems 跳过
        // （留待参数确认后解析），不参与进度，否则独立参数场景下进度条会卡在中间。
        List<PreviewItem> vis = host.allVisibleItems().stream()
                .filter(i -> host.effectiveParams(i) != null).toList();
        long total = vis.size();
        long done = vis.stream().filter(i -> i.status != PreviewItem.Status.WAITING).count();
        if (total == 0) {
            host.progressBar().setVisible(false);   // 全部无生效参数：不预解析，不动状态栏文案
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
        host.statusLabel().setText(I18n.get("preview.parseDone") + done + "/" + total);
    }
}
