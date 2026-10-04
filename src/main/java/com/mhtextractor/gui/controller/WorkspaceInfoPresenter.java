package com.mhtextractor.gui.controller;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.processor.BatchProcessor;
import com.mhtextractor.processor.FileResult;
import com.mhtextractor.processor.ImageBatchProcessor;
import com.mhtextractor.util.ExtractOutcome;
import com.mhtextractor.util.GroupItem;
import com.mhtextractor.util.GroupStructure;
import com.mhtextractor.util.SanitizeUtils;
import com.mhtextractor.util.SourceKinds;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作区信息呈现协作者（C5）：模式摘要 / 选中信息区 / 提取结果展示从 {@code MainController}
 * <b>原样外迁</b> —— 文案键（preview.* / extract.* / list.colon / list.itemSeparator）、
 * 日志键（仍是 {@code log.maincontroller.29}，键名、实参与调用次数不变）、
 * 组条目编辑框接线（经 {@link Host#attachGroupNameEditor} 与 {@link Host#clearNameEditingGroup}）全部保持不变。
 *
 * <p>宿主能力经 {@link Host} 收窄为具名方法：本类不持有 {@code MainController} / {@code MainViewRefs} /
 * FXML namespace，也不得在数据序上直写（结果回写只改 {@link PreviewItem} 的
 * {@code extractOutcome / extractDetail} 字段）。</p>
 *
 * <p>本类含 JavaFX，属 {@code gui} 层。</p>
 */
@Slf4j
public final class WorkspaceInfoPresenter {

    /**
     * 宿主能力面（C5）：对 {@code MainController} 的全部能力需求收窄为具名方法。
     * 实现由 {@code MainController} 的匿名内部类提供（转发到其保留成员）。
     */
    public interface Host {
        Label titleInfoLabel();                          // = refs.titleInfoLabel()
        Label outputNameLabel();                         // = refs.outputNameLabel()
        HBox groupNameBox();                             // = refs.groupNameBox()
        Label badgeHeaderLabel();                        // = refs.badgeHeaderLabel()
        Label outputModeLabel();                         // = refs.outputModeLabel()
        Label extractResultBanner();                     // = refs.extractResultBanner()
        TextField inputFolderField();                    // = refs.inputFolderField()
        AppConfig effectiveParams(Object item);          // = effectiveForObject(item)
        AppConfig currentParamsOrDefaults();             // = currentParamsOrDefaults()
        boolean isTextMode();                            // = isTextMode()
        List<PreviewItem> allVisibleItems();             // = allVisibleItems()
        List<PreviewItem> allPreviewItems();             // = allPreviewItems()
        Object currentSelection();                       // = refs.fileListView().getSelectionModel().getSelectedItem()
        void refreshList();                              // = refs.fileListView().refresh()
        void attachGroupNameEditor(GroupItem<PreviewItem> group); // = attachGroupNameEditor(group)
        void clearNameEditingGroup();                    // = nameEditingGroup = null
    }

    private final Host host;

    public WorkspaceInfoPresenter(Host host) {
        this.host = host;
    }

    void updateInfoArea(Object item) {
        updateExtractBanner(item);
        // 组条目：第二行（原"输出文件："位置）变为"输出组文件名："+ 可编辑输入框（F5 6.3）
        if (item instanceof GroupItem<?> g) {
            host.titleInfoLabel().setVisible(false);
            host.titleInfoLabel().setManaged(false);
            host.outputNameLabel().setVisible(false);
            host.outputNameLabel().setManaged(false);
            host.groupNameBox().setVisible(true);
            host.groupNameBox().setManaged(true);
            host.attachGroupNameEditor(GroupStructure.asPreviewGroup(g));
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
        host.titleInfoLabel().setVisible(showTitle);
        host.titleInfoLabel().setManaged(showTitle);
        if (!(item instanceof PreviewItem it) || !confirmed) {
            host.outputNameLabel().setVisible(false);
            host.outputNameLabel().setManaged(false);
            return;
        }
        if (showTitle) {
            switch (it.status) {
                case WAITING -> host.titleInfoLabel().setText(I18n.get("preview.titleParsing"));
                case FAILED, EMPTY -> host.titleInfoLabel().setText(I18n.get("preview.titleNone"));
                case READY -> host.titleInfoLabel().setText(it.cachedTitle != null
                        ? I18n.get("preview.titlePrefix") + it.cachedTitle
                        : I18n.get("preview.titleNotFound"));
            }
        }
        host.outputNameLabel().setVisible(true);
        host.outputNameLabel().setManaged(true);
        if (host.isTextMode()) {
            if (params.isSeparateFiles()) {
                host.outputNameLabel().setText(I18n.get("preview.outputFilePrefix") + SourceKinds.stripKnownExtension(it.name) + ".txt");
            } else {
                String inputDirName = new File(host.inputFolderField().getText()).getName();
                if (inputDirName.isEmpty()) {
                    inputDirName = "mht_output";
                }
                host.outputNameLabel().setText(I18n.get("preview.outputFilePrefix") + inputDirName + ".txt"
                        + I18n.get("preview.outputFileTxtSuffix"));
            }
        } else if (params.isSingleOutputMode()) {
            String folderName = mergedFolderName(it);
            host.outputNameLabel().setText(I18n.get("preview.outputDirPrefix") + folderName
                    + I18n.get("preview.outputDirMergedNote"));
        } else {
            String dirName = separateDirName(it);
            host.outputNameLabel().setText(I18n.get("preview.outputDirPrefix") + dirName);
        }
    }

    private String separateDirName(PreviewItem item) {
        AppConfig params = host.effectiveParams(item);
        if (params != null && params.isEnableNameExtraction()) {
            if (item.status == PreviewItem.Status.WAITING) {
                return I18n.get("preview.parsing");
            }
            if (item.cachedTitle != null) {
                return SanitizeUtils.sanitize(item.cachedTitle);
            }
        }
        return SourceKinds.stripKnownExtension(item.name);
    }

    private String mergedFolderName(PreviewItem item) {
        AppConfig params = host.effectiveParams(item);
        if (params != null && params.isEnableNameExtraction()) {
            PreviewItem first = host.allVisibleItems().stream().findFirst().orElse(null);
            if (first != null) {
                if (first.status == PreviewItem.Status.WAITING) {
                    return I18n.get("preview.parsing");
                }
                if (first.cachedTitle != null) {
                    return SanitizeUtils.sanitize(first.cachedTitle);
                }
            }
        }
        return ImageBatchProcessor.determineDefaultFolderName(host.inputFolderField().getText());
    }

    /** 输出模式标签按当前选中对象的生效参数显示（组条目→该组生效参数；项目→其生效参数；无选中→全局） */
    void updateOutputModeLabel(Object sel) {
        AppConfig params = host.effectiveParams(sel);
        if (params == null) {
            params = host.currentParamsOrDefaults();
        }
        host.outputModeLabel().setText(host.isTextMode()
                ? (params.isSeparateFiles() ? I18n.get("extract.outputModeSeparate") : I18n.get("extract.outputModeMerged"))
                : (params.isSingleOutputMode() ? I18n.get("extract.outputModeMerged") : I18n.get("extract.outputModeSeparate")));
    }

    void updateBadgeHeader() {
        boolean showBadgeCol = host.isTextMode() && anyVisibleItemHasChapterNumbers();
        host.badgeHeaderLabel().setVisible(showBadgeCol);
        host.badgeHeaderLabel().setManaged(showBadgeCol);
    }

    /** 任一有效项目的生效参数开启章节序号（组参数可独立开启，列头按需显示） */
    private boolean anyVisibleItemHasChapterNumbers() {
        for (PreviewItem it : host.allVisibleItems()) {
            AppConfig eff = host.effectiveParams(it);
            if (eff != null && eff.isAddChapterNumber()) {
                return true;
            }
        }
        return false;
    }

    /** 提取结果一次性回写（拍板：提取结束/取消后统一回写，非逐文件实时更新）：
     *  按路径匹配条目写入结果字段，列表一次刷新，选中条目 banner 同步 */
    void applyExtractResults(BatchProcessor.ProcessStats stats) {
        Map<String, FileResult> byPath = new HashMap<>();
        for (FileResult r : stats.getFileResults()) {
            byPath.put(r.path(), r);
        }
        for (PreviewItem it : host.allPreviewItems()) {
            FileResult r = byPath.get(it.path);
            if (r != null) {
                it.extractOutcome = r.outcome();
                it.extractDetail = buildExtractDetail(r);
            }
        }
        host.refreshList();
        updateExtractBanner(host.currentSelection());
        log.info(I18n.msg("log.maincontroller.29", byPath.size()));
    }

    /** 结果详情拼接：失败/跳过原因 + 警告，分号分隔；无内容返回 null */
    private String buildExtractDetail(FileResult r) {
        List<String> parts = new ArrayList<>();
        if (r.message() != null && !r.message().isEmpty()) {
            parts.add(r.message());
        }
        parts.addAll(r.warnings());
        return parts.isEmpty() ? null : String.join(I18n.get("list.itemSeparator"), parts);
    }

    /** 提取结果色清除（拍板）：重新确认参数后旧结果失效，回到参数色/解析状态色 */
    void clearExtractOutcomes(List<PreviewItem> targets) {
        for (PreviewItem it : targets) {
            it.extractOutcome = ExtractOutcome.NONE;
            it.extractDetail = null;
        }
    }

    /** 预览区顶部 banner（拍板）：选中条目结果含红（失败/跳过/成功+警告）时显示具体情况；
     *  无提取结果时，解析失败（黄色）条目显示解析失败原因 */
    void updateExtractBanner(Object sel) {
        if (sel instanceof PreviewItem it && it.extractOutcome != ExtractOutcome.NONE
                && it.extractDetail != null && !it.extractDetail.isEmpty()) {
            String prefix = switch (it.extractOutcome) {
                case FAILED -> I18n.get("extract.statusFailed");
                case SKIPPED -> I18n.get("extract.statusSkipped");
                case SUCCESS_WARNED -> I18n.get("extract.statusWarned");
                default -> null;
            };
            if (prefix != null) {
                host.extractResultBanner().setText(prefix + I18n.get("list.colon") + it.extractDetail);
                host.extractResultBanner().setVisible(true);
                host.extractResultBanner().setManaged(true);
                return;
            }
        }
        if (sel instanceof PreviewItem it && it.status == PreviewItem.Status.FAILED) {
            host.extractResultBanner().setText(I18n.get("extract.parseFailed") + it.errorMsg);
            host.extractResultBanner().setVisible(true);
            host.extractResultBanner().setManaged(true);
            return;
        }
        host.extractResultBanner().setVisible(false);
        host.extractResultBanner().setManaged(false);
    }
}
