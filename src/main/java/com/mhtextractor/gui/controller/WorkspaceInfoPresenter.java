package com.mhtextractor.gui.controller;

import com.mhtextractor.app.ExtractResultService;
import com.mhtextractor.model.ExtractionStats;
import com.mhtextractor.app.OutputNameService;
import com.mhtextractor.app.Texts;
import com.mhtextractor.uicore.WorkspaceView;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uicore.PreviewRow;
import com.mhtextractor.model.ExtractOutcome;
import com.mhtextractor.uicore.GroupRow;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 工作区信息呈现协作者（C5）：模式摘要 / 选中信息区 / 提取结果展示从 {@code MainController}
 * <b>原样外迁</b> —— 文案键（preview.* / extract.* / list.colon / list.itemSeparator）、
 * 日志键（仍是 {@code log.maincontroller.29}，键名、实参与调用次数不变）、
 * 组条目编辑框接线（经 {@link Host#attachGroupNameEditor} 与 {@link Host#clearNameEditingGroup}）全部保持不变。
 *
 * <p>宿主能力经 {@link Host} 收窄为具名方法：本类不持有 {@code MainController} / {@code MainViewRefs} /
 * FXML namespace，也不得在数据序上直写（结果回写只改 {@link PreviewRow} 的
 * {@code extractOutcome / extractDetail} 字段）。</p>
 *
 * <p>本类含 JavaFX，属 {@code gui} 层。</p>
 */
@Slf4j
public final class WorkspaceInfoPresenter {

    /** GUI rendering of language-neutral port errors, in the original concatenation order. */
    static String errorText(PreviewRow row) {
        return row.errorKey == null ? "" : Texts.get(row.errorKey) + row.errorDetail;
    }

    static String detailText(String key, java.util.Map<String, String> params) {
        if (key == null) return null;
        Object[] args = params == null ? new Object[0] : params.entrySet().stream()
                .flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue())).toArray();
        return Texts.get(key, args);
    }

    /**
     * 宿主能力面（C5）：对 {@code MainController} 的全部能力需求收窄为具名方法。
     * 实现由 {@code MainController} 的匿名内部类提供（转发到其保留成员）。
     */
    public interface Host {
        Label titleInfoLabel();                          // = refs.titleInfoLabel()
        HBox titleEditBox();                             // = refs.titleEditBox()
        TextField titleField();                          // = refs.titleField()
        Label outputNameLabel();                         // = refs.outputNameLabel()
        HBox groupNameBox();                             // = refs.groupNameBox()
        Label badgeHeaderLabel();                        // = refs.badgeHeaderLabel()
        Label outputModeLabel();                         // = refs.outputModeLabel()
        Label extractResultBanner();                     // = refs.extractResultBanner()
        TextField inputFolderField();                    // = refs.inputFolderField()
        AppConfig effectiveParams(Object item);          // = effectiveForObject(item)
        AppConfig currentParamsOrDefaults();             // = currentParamsOrDefaults()
        boolean isTextMode();                            // = isTextMode()
        List<PreviewRow> allVisibleItems();             // = allVisibleItems()
        List<PreviewRow> allPreviewItems();             // = allPreviewItems()
        Object currentSelection();                       // = refs.fileListView().getSelectionModel().getSelectedItem()
        void refreshList();                              // = refs.fileListView().refresh()
        void attachGroupNameEditor(GroupRow<PreviewRow> group); // = attachGroupNameEditor(group)
        void clearNameEditingGroup();                    // = nameEditingGroup = null
        void attachTitleEditor(PreviewRow item);        // = attachTitleEditor(item)
        void clearTitleEditingItem();                    // = titleEditingItem = null
    }

    private final Host host;

    public WorkspaceInfoPresenter(Host host) {
        this.host = host;
    }

    void updateInfoArea(Object item) {
        updateExtractBanner(item);
        // 组条目：第二行（原"输出文件："位置）变为"输出组文件名："+ 可编辑输入框（F5 6.3）
        if (item instanceof GroupRow<?> g) {
            host.titleInfoLabel().setVisible(false);
            host.titleInfoLabel().setManaged(false);
            host.titleEditBox().setVisible(false);
            host.titleEditBox().setManaged(false);
            host.clearTitleEditingItem();
            host.outputNameLabel().setVisible(false);
            host.outputNameLabel().setManaged(false);
            host.groupNameBox().setVisible(true);
            host.groupNameBox().setManaged(true);
            host.attachGroupNameEditor(WorkspaceView.asPreviewGroup(g));
            return;
        }
        AppConfig params = host.effectiveParams(item);
        boolean confirmed = params != null;
        if (params == null) {
            params = new AppConfig();
        }
        host.clearNameEditingGroup();
        host.groupNameBox().setVisible(false);
        host.groupNameBox().setManaged(false);
        // 提取标题行：文本模式 + 标题提取开启且起始标记非空时显示（与正式提取判定一致，按该条目生效参数）
        boolean showTitle = confirmed && host.isTextMode() && params.isEnableTitleExtraction()
                && params.getTitleStartMarker() != null && !params.getTitleStartMarker().isEmpty();
        // 需求（提取标题可编辑）：showTitle 且条目已就绪（READY）时，把只读标题行换成可编辑文本框；
        // 解析中 / 无标题 / 非条目等其余情况保持原只读展示。
        PreviewRow readyItem = item instanceof PreviewRow pi && pi.status == PreviewRow.Status.READY
                ? pi : null;
        boolean editableTitle = showTitle && readyItem != null;
        host.titleEditBox().setVisible(editableTitle);
        host.titleEditBox().setManaged(editableTitle);
        host.titleInfoLabel().setVisible(showTitle && !editableTitle);
        host.titleInfoLabel().setManaged(showTitle && !editableTitle);
        if (!editableTitle) {
            host.clearTitleEditingItem();
        }
        if (!(item instanceof PreviewRow it) || !confirmed) {
            host.outputNameLabel().setVisible(false);
            host.outputNameLabel().setManaged(false);
            return;
        }
        if (editableTitle) {
            host.attachTitleEditor(readyItem);
        } else if (showTitle) {
            switch (it.status) {
                case WAITING -> host.titleInfoLabel().setText(Texts.get("preview.titleParsing"));
                case FAILED, EMPTY -> host.titleInfoLabel().setText(Texts.get("preview.titleNone"));
                case READY -> host.titleInfoLabel().setText(it.cachedTitle != null
                        ? Texts.get("preview.titlePrefix") + it.cachedTitle
                        : Texts.get("preview.titleNotFound"));
            }
        }
        host.outputNameLabel().setVisible(true);
        host.outputNameLabel().setManaged(true);
        if (host.isTextMode()) {
            String outputName = it.displayName == null ? Texts.get("preview.parsing") : it.displayName;
            if (params.isSeparateFiles()) {
                host.outputNameLabel().setText(Texts.get("preview.outputFilePrefix") + outputName);
            } else {
                host.outputNameLabel().setText(Texts.get("preview.outputFilePrefix") + outputName
                        + Texts.get("preview.outputFileTxtSuffix"));
            }
        } else {
            String dirName = it.displayName == null ? Texts.get("preview.parsing") : it.displayName;
            if (params.isSingleOutputMode()) {
                host.outputNameLabel().setText(Texts.get("preview.outputDirPrefix") + dirName
                        + Texts.get("preview.outputDirMergedNote"));
            } else {
                host.outputNameLabel().setText(Texts.get("preview.outputDirPrefix") + dirName);
            }
        }
    }

    /** 输出模式标签按当前选中对象的生效参数显示（组条目→该组生效参数；项目→其生效参数；无选中→全局） */
    void updateOutputModeLabel(Object sel) {
        AppConfig params = host.effectiveParams(sel);
        if (params == null) {
            params = host.currentParamsOrDefaults();
        }
        host.outputModeLabel().setText(host.isTextMode()
                ? (params.isSeparateFiles() ? Texts.get("extract.outputModeSeparate") : Texts.get("extract.outputModeMerged"))
                : (params.isSingleOutputMode() ? Texts.get("extract.outputModeMerged") : Texts.get("extract.outputModeSeparate")));
    }

    void updateBadgeHeader() {
        boolean showBadgeCol = host.isTextMode() && anyVisibleItemHasChapterNumbers();
        host.badgeHeaderLabel().setVisible(showBadgeCol);
        host.badgeHeaderLabel().setManaged(showBadgeCol);
    }

    /** 任一有效项目的生效参数开启章节序号（组参数可独立开启，列头按需显示） */
    private boolean anyVisibleItemHasChapterNumbers() {
        for (PreviewRow it : host.allVisibleItems()) {
            AppConfig eff = host.effectiveParams(it);
            if (eff != null && eff.isAddChapterNumber()) {
                return true;
            }
        }
        return false;
    }

    /** 提取结果一次性回写（拍板：提取结束/取消后统一回写，非逐文件实时更新）：
     *  按路径匹配条目写入结果字段（下沉 {@code app.ExtractResultService}），列表一次刷新，选中条目 banner 同步 */
    void applyExtractResults(ExtractionStats stats) {
        long matched = host.allPreviewItems().stream().filter(item -> item.extractOutcome != ExtractOutcome.NONE).count();
        host.refreshList();
        updateExtractBanner(host.currentSelection());
        log.info(Texts.msg("log.maincontroller.29", matched));
    }

    /** 提取结果色清除（拍板）：重新确认参数后旧结果失效，回到参数色/解析状态色 */
    void clearExtractOutcomes(List<PreviewRow> targets) {
        for (PreviewRow it : targets) {
            it.extractOutcome = ExtractOutcome.NONE;
            it.extractDetail = null;
        }
    }

    /** 预览区顶部 banner（拍板）：选中条目结果含红（失败/跳过/成功+警告）时显示具体情况；
     *  无提取结果时，解析失败（黄色）条目显示解析失败原因 */
    void updateExtractBanner(Object sel) {
        if (sel instanceof PreviewRow it && it.extractOutcome != ExtractOutcome.NONE
                && it.extractDetail != null && !it.extractDetail.isEmpty()) {
            String prefix = switch (it.extractOutcome) {
                case FAILED -> Texts.get("extract.statusFailed");
                case SKIPPED -> Texts.get("extract.statusSkipped");
                case SUCCESS_WARNED -> Texts.get("extract.statusWarned");
                default -> null;
            };
            if (prefix != null) {
                host.extractResultBanner().setText(prefix + Texts.get("list.colon") + it.extractDetail);
                host.extractResultBanner().setVisible(true);
                host.extractResultBanner().setManaged(true);
                return;
            }
        }
        if (sel instanceof PreviewRow it && it.status == PreviewRow.Status.FAILED) {
            host.extractResultBanner().setText(Texts.get("extract.parseFailed") + WorkspaceInfoPresenter.errorText(it));
            host.extractResultBanner().setVisible(true);
            host.extractResultBanner().setManaged(true);
            return;
        }
        host.extractResultBanner().setVisible(false);
        host.extractResultBanner().setManaged(false);
    }
}
