package com.mhtextractor.gui.controller;

import com.mhtextractor.app.GlobalParameterCache;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.gui.component.GuiNotifications;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.GroupItem;
import com.mhtextractor.util.GroupStructure;
import com.mhtextractor.util.ParamScope;
import com.mhtextractor.util.UndoManager;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 参数域协作者（步骤 1）：「两套已确认参数缓存 + 参数应用 + 参数撤销 + 参数变更后的 UI 派生刷新」
 * 从 {@link MainController} 原样外迁；宿主能力经 {@link Host} 收窄为具名方法（控制器逐个转发到其保留成员）。
 * 文案键、日志键（log.maincontroller.15/16/17/18/27/28/53/54/55）、PARAM_SET / CLEAR_PARAMS / APPLY_GLOBAL
 * 三分支的入栈与应用顺序、快照内容、缓存失效（规则 (a)，经 {@code app.WorkspaceParamService} 收口）时机与
 * R1.7 开始按钮派生规则全部保持不变。
 *
 * <p>两套全局参数缓存（{@code textParamsCache} / {@code imageParamsCache}）由本类独占持有
 * （P1-1：值下沉 app 层 {@link GlobalParameterCache}，本类只留包级同名委托方法；测试源集
 * AcceptanceDriver 与 UI 探针按新位置读取）；
 * FXML/菜单入口仍留在 {@code MainController} 作薄转发（onParamSettings / onApplyGlobalClicked /
 * onClearParamsClicked / 右键五项），参数剪贴板字段也仍由 {@code MainController} 持有
 * （其路由与应用函数注入本类实现）。本类含 JavaFX，属 gui 层。</p>
 */
@Slf4j
public final class ParamDomainController {

    /** 宿主能力面：全部经具名方法收窄，不得把 MainController / 模型 / 整个 FXML namespace 传进来 */
    public interface Host {
        // 控件 / 锚点
        ListView<Object> fileListView();
        Node anchor();
        Button startButton();
        // 模式
        boolean imageMode();
        boolean anyModeSelected();
        // 查询（只读）
        Object currentSelection();
        List<Object> dataOrder();
        List<PreviewItem> allVisibleItems();
        List<PreviewItem> allPreviewItems();
        AppConfig effectiveForObject(Object item);
        boolean currentModeConfirmed();
        long visibleCount();
        boolean isImporting();
        // 撤销压栈（模型唯一入口）
        void pushUndo(UndoManager.Record<PreviewItem> record);
        /** 参数类撤销压栈出口（ParamClipboard：批量应用期间只累积快照，整批一条） */
        void pushParamUndo(boolean imageMode, String path, Integer groupNo, AppConfig before);
        // 缓存失效收口（经 WorkspaceParamService；本类不得出现规则方法名，见 ArchitectureTest 不变式）
        /** 单条目（对应服务单条目重载 —— 与批量重载语义不同，勿合并） */
        void paramsChanged(boolean imageMode, PreviewItem item);
        /** 条目集合（对应服务批量重载） */
        void paramsChanged(boolean imageMode, Iterable<PreviewItem> items);
        // 逐条目 UI 内存态复位（原 clearPreviewCache(PreviewItem)）
        void clearPreviewCache(PreviewItem item);
        // 提取结果色 / 信息区 / 按钮的派生刷新
        void clearExtractOutcomes(List<PreviewItem> items);
        void updateBadgeHeader();
        void updateOutputModeLabel(Object sel);
        void updateRenameNote(Object sel);
        void updateAdjustCheck(Object sel);
        void updateInfoArea(Object sel);
        void updateContentArea(Object sel);
        void updateExtractBanner(Object sel);
        void updateListButtons();
        void recomputeBadges();
        void cancelWorkspaceActivity();
        void startPreParseFor(List<PreviewItem> items);
        void startPreParse();
        void updateProgressStatus();
    }

    private final Host host;

    /** 两套已确认提取参数缓存（P1-1 下沉）：值在 app 层 {@link GlobalParameterCache}；null 表示该模式尚未确认过参数 */
    private final GlobalParameterCache globalParams = new GlobalParameterCache();

    public ParamDomainController(Host host) {
        this.host = host;
    }

    /** 文本模式已确认参数缓存（包级委托方法，原包级字段；供 MainController / AcceptanceDriver 继续按原名读取） */
    AppConfig textParamsCache() {
        return globalParams.text();
    }

    void textParamsCache(AppConfig v) {
        globalParams.set(false, v);
    }

    /** 图片模式已确认参数缓存（包级委托方法，原包级字段；理由同 {@link #textParamsCache()}） */
    AppConfig imageParamsCache() {
        return globalParams.image();
    }

    void imageParamsCache(AppConfig v) {
        globalParams.set(true, v);
    }

    /** 选择对象 → 参数作用域目标（R7.4 复用点）："设置参数"按钮与"应用到所选"共用这一份路由 */
    ParamScope.Target paramScopeTarget(Object sel) {
        return switch (sel) {
            case GroupItem<?> g -> new ParamScope.GroupTarget(g);
            case PreviewItem it when it.parentGroup != null -> new ParamScope.GroupTarget(it.parentGroup);
            case PreviewItem it -> new ParamScope.ItemTarget<>(it, it.textItemParams, it.imageItemParams);
            case null, default -> ParamScope.GLOBAL;
        };
    }

    /** 确认路径的应用本体（R7.4 唯一实现点）：组 → 组参数；条目 → 条目参数；其余 → 全局参数 */
    void applyTargetParams(ParamScope.Target target, boolean imageMode, AppConfig params) {
        switch (target) {
            case ParamScope.GroupTarget(GroupItem<?> g) -> applyGroupParams(GroupStructure.asPreviewGroup(g), imageMode, params);
            case ParamScope.ItemTarget<?> it -> applyItemParams((PreviewItem) it.item(), imageMode, params);
            default -> {
                applyConfirmedParams(imageMode, params);
                log.info(I18n.msg("log.maincontroller.15", modeWord(imageMode)));
            }
        }
    }

    /** 打开参数设置侧边栏并应用确认结果（按钮行与右键菜单共用；空白区域菜单项强制传全局目标） */
    void openParamDialog(ParamScope.Target target) {
        boolean imageMode = host.imageMode();
        try {
            // Q6a：非模态覆盖式侧边栏取代模态窗口。面板按窗口缓存（ParamSidePanel.of），
            // 复用同一个 ParamFormPane 实例 ⇒ "收起再展开同一目标"保留草稿（R1.5）。
            ParamSidePanel panel = ParamSidePanel.of(host.anchor());
            // Q6b/R1.7：草稿变了（改字段 / 提交 / 切目标丢弃）就重算开始按钮 —— 判定仍只有
            // ParamFormPane.isDirty() 一处，这里只接线（每次 show 都重设，幂等）。
            panel.setDirtyListener(this::updateStartButtonState);
            panel.show(target, imageMode, globalParams.text(), globalParams.image(),
                    params -> applyTargetParams(target, imageMode, params));
            updateStartButtonState();   // 本次 show 可能什么都没变（同目标重开 / 取消切换）⇒ 兜底重算
        } catch (Exception e) {
            log.error(I18n.msg("log.maincontroller.16"), e);
            GuiNotifications.paramDialogOpenFailed(host.anchor());
        }
    }

    /** 写入某条目的条目级独立参数（条目独立参数需求）：不写全局，仅清缓存重解析该条目；入撤销栈（PARAM_SET） */
    void applyItemParams(PreviewItem item, boolean imageMode, AppConfig params) {
        AppConfig before = imageMode ? item.imageItemParams : item.textItemParams;
        if (imageMode) {
            item.imageItemParams = params;
        } else {
            item.textItemParams = params;
        }
        host.pushParamUndo(imageMode, item.path, null, before);
        host.clearExtractOutcomes(List.of(item));
        host.clearPreviewCache(item);
        host.paramsChanged(imageMode, item);   // 规则 (a)：参数变化 → 删该项目缓存目录
        host.startPreParseFor(List.of(item));
        log.info(I18n.msg("log.maincontroller.17", item.name, modeWord(imageMode)));
        host.fileListView().refresh();
        host.updateListButtons();
        host.updateInfoArea(item);
        host.updateExtractBanner(item);
    }

    /** 写入某模式的已确认参数缓存并刷新工作区（agent 演练/自动化测试也走此入口）；入撤销栈（PARAM_SET） */
    void applyConfirmedParams(boolean imageMode, AppConfig params) {
        AppConfig before = imageMode ? globalParams.image() : globalParams.text();
        if (imageMode) {
            globalParams.set(true, params);
        } else {
            globalParams.set(false, params);
        }
        host.pushParamUndo(imageMode, null, null, before);
        // 拍板：全局参数重新确认 → 全部条目旧提取结果色失效
        host.clearExtractOutcomes(host.allPreviewItems());
        // 规则 (a)：全局参数变化 → 全部条目缓存目录删除（保守全删：有独立参数的条目删除也只是下次预览重建，语义安全）
        host.paramsChanged(imageMode, host.allPreviewItems());
        refreshAfterParamsChanged();
        host.updateExtractBanner(host.currentSelection());
    }

    /** 写入某组的已确认参数缓存（R8）：参数作用域仅限预览解析与显示，提取管线按组参数输出属阶段 8 */
    void applyGroupParams(GroupItem<PreviewItem> group, boolean imageMode, AppConfig params) {
        AppConfig before = imageMode ? group.getImageParamsCache() : group.getTextParamsCache();
        if (imageMode) {
            group.setImageParamsCache(params);
        } else {
            group.setTextParamsCache(params);
        }
        log.info(I18n.msg("log.maincontroller.18", group.getName(), modeWord(imageMode)));
        // 入撤销栈（PARAM_SET，组参数设置可撤销）
        host.pushParamUndo(imageMode, null, group.getGroupNo(), before);
        // 拍板：组参数重新确认 → 该组成员旧提取结果色失效
        List<PreviewItem> members = group.getMembers().stream().filter(m -> !m.removed).toList();
        host.clearExtractOutcomes(members);
        // 规则 (a)：组参数变化 → 该组成员缓存目录删除
        host.paramsChanged(imageMode, members);
        refreshAfterTargetedParamsChange(members);
        host.updateListButtons();
        host.updateExtractBanner(host.currentSelection());
    }

    /** 拍板：目标生效参数集写入当前模式全局缓存，清空当前模式所有独立参数（条目级 + 组，含目标自身），
     *  全部条目继承新全局；另一模式独立参数不受影响。入撤销栈（APPLY_GLOBAL，含应用前完整参数快照） */
    void applyGlobalParamsTo(Object target) {
        AppConfig params = host.effectiveForObject(target);
        if (params == null) {
            return;
        }
        boolean imageMode = host.imageMode();
        // 撤销快照（拍板 2026-09-22）：应用前的全局参数 + 全部独立参数，撤销可完整回退
        AppConfig globalBefore = imageMode ? globalParams.image() : globalParams.text();
        Map<String, AppConfig> itemBefore = new HashMap<>();
        for (PreviewItem it : host.allPreviewItems()) {
            itemBefore.put(it.path, imageMode ? it.imageItemParams : it.textItemParams);
        }
        Map<Integer, AppConfig> groupBefore = new HashMap<>();
        for (Object o : host.dataOrder()) {
            if (o instanceof GroupItem<?> g) {
                groupBefore.put(g.getGroupNo(),
                        imageMode ? g.getImageParamsCache() : g.getTextParamsCache());
            }
        }
        if (imageMode) {
            globalParams.set(true, params);
        } else {
            globalParams.set(false, params);
        }
        for (PreviewItem it : host.allPreviewItems()) {
            if (imageMode) {
                it.imageItemParams = null;
            } else {
                it.textItemParams = null;
            }
        }
        for (Object o : host.dataOrder()) {
            if (o instanceof GroupItem<?> g) {
                if (imageMode) {
                    g.setImageParamsCache(null);
                } else {
                    g.setTextParamsCache(null);
                }
            }
        }
        host.pushUndo(com.mhtextractor.app.UndoRecords.applyGlobal(imageMode, globalBefore, itemBefore, groupBefore));
        host.clearExtractOutcomes(host.allPreviewItems());
        // 规则 (a)：全局参数写入 + 全部独立参数清空 → 全部条目生效参数变化 → 删除全部缓存目录
        host.paramsChanged(imageMode, host.allPreviewItems());
        log.info(I18n.msg("log.maincontroller.27", modeWord(imageMode)));
        refreshAfterParamsChanged();
        host.updateListButtons();
        host.updateExtractBanner(host.currentSelection());
    }

    /** 勾选集合优先 → 回退对象（与"移除"的勾选优先拍板一致）；复制/应用与清除参数共用同一"所选"口径 */
    List<Object> resolveParamTargets(Object fallback) {
        return resolveTargets(host.allVisibleItems(), fallback);
    }

    /**
     * 功能二："实际作用集合"解析的<b>纯函数</b>形态（勾选集合优先 → 回退右键目标）。
     * 与 {@link #resolveParamTargets(Object)} 共用同一实现，供无头单测直接驱动
     * "勾选优先于右键目标"这一基线，不需要构造宿主。
     *
     * @param visible  当前可见（未移除）条目
     * @param fallback 勾选为空时的回退目标（右键命中的条目/组，可为 null）
     */
    public static List<Object> resolveTargets(List<PreviewItem> visible, Object fallback) {
        List<PreviewItem> checked = visible == null ? List.of()
                : visible.stream().filter(i -> i.selected).toList();
        if (!checked.isEmpty()) {
            return new ArrayList<>(checked);
        }
        return fallback == null ? new ArrayList<>() : new ArrayList<>(List.of(fallback));
    }

    /** 功能二：实际作用集合的两个计数（组外项数 / 去重组数）——纯函数。 */
    public record TargetCounts(int topLevelCount, int distinctGroupCount) {
    }

    /**
     * 功能二：把"实际作用集合"归类为两个计数。组内成员与组条目都落到<b>整组</b>（去重）；
     * 顶层项目计入组外项；已移除条目不计。
     */
    public static TargetCounts countTargets(List<Object> targets) {
        int topLevel = 0;
        Set<GroupItem<PreviewItem>> groups = new LinkedHashSet<>();
        if (targets != null) {
            for (Object o : targets) {
                if (o instanceof PreviewItem it) {
                    if (it.parentGroup != null) {
                        groups.add(it.parentGroup);
                    } else if (!it.removed) {
                        topLevel++;
                    }
                } else if (o instanceof GroupItem<?> g) {
                    groups.add(GroupStructure.asPreviewGroup(g));
                }
            }
        }
        return new TargetCounts(topLevel, groups.size());
    }

    /**
     * 清除参数（拍板 Q1-Q4）：只清当前模式。组外条目 → 清条目级参数；组内成员/组条目 → 清整组组参数
     * （按组去重）；无参数目标自然跳过；一次操作一条 CLEAR_PARAMS 撤销记录（before 快照），一次撤销全部恢复。
     */
    void clearParamsForTargets(List<Object> rawTargets) {
        boolean imageMode = host.imageMode();
        List<PreviewItem> topItems = new ArrayList<>();
        Set<GroupItem<PreviewItem>> groups = new LinkedHashSet<>();
        for (Object o : rawTargets) {
            if (o instanceof PreviewItem it && !it.removed) {
                if (it.parentGroup != null) {
                    if ((imageMode ? it.parentGroup.getImageParamsCache()
                                   : it.parentGroup.getTextParamsCache()) != null) {
                        groups.add(it.parentGroup);
                    }
                } else if ((imageMode ? it.imageItemParams : it.textItemParams) != null) {
                    topItems.add(it);
                }
            } else if (o instanceof GroupItem<?> g) {
                GroupItem<PreviewItem> grp = GroupStructure.asPreviewGroup(g);
                if ((imageMode ? grp.getImageParamsCache() : grp.getTextParamsCache()) != null) {
                    groups.add(grp);
                }
            }
        }
        if (topItems.isEmpty() && groups.isEmpty()) {
            return;
        }
        // 撤销快照：清除前该模式参数（只收录本次实际清除的目标）
        Map<String, AppConfig> itemBefore = new HashMap<>();
        for (PreviewItem it : topItems) {
            itemBefore.put(it.path, imageMode ? it.imageItemParams : it.textItemParams);
        }
        Map<Integer, AppConfig> groupBefore = new HashMap<>();
        for (GroupItem<PreviewItem> grp : groups) {
            groupBefore.put(grp.getGroupNo(),
                    imageMode ? grp.getImageParamsCache() : grp.getTextParamsCache());
        }
        if (imageMode) {
            for (PreviewItem it : topItems) {
                it.imageItemParams = null;
            }
            for (GroupItem<PreviewItem> grp : groups) {
                grp.setImageParamsCache(null);
            }
        } else {
            for (PreviewItem it : topItems) {
                it.textItemParams = null;
            }
            for (GroupItem<PreviewItem> grp : groups) {
                grp.setTextParamsCache(null);
            }
        }
        host.pushUndo(com.mhtextractor.app.UndoRecords.clearParams(imageMode, itemBefore, groupBefore));
        // 受影响条目：清除条目参数的项目 + 清除组参数的各组全部有效成员
        List<PreviewItem> affected = new ArrayList<>(topItems);
        for (GroupItem<PreviewItem> grp : groups) {
            affected.addAll(grp.getMembers().stream().filter(m -> !m.removed).toList());
        }
        // 拍板：参数变更 → 受影响条目旧提取结果色失效
        host.clearExtractOutcomes(affected);
        // 规则 (a)：清除参数也是参数变化（生效参数回落组/全局）→ 受影响条目缓存目录删除
        host.paramsChanged(imageMode, affected);
        log.info(I18n.msg("log.maincontroller.28", topItems.size(), groups.size(), modeWord(imageMode)));
        refreshAfterTargetedParamsChange(affected);
        host.updateListButtons();
        host.updateExtractBanner(host.currentSelection());
    }

    void refreshAfterParamsChanged() {
        Object sel = host.currentSelection();
        host.updateBadgeHeader();
        host.updateOutputModeLabel(sel);
        host.updateRenameNote(sel);
        host.fileListView().refresh();
        host.updateAdjustCheck(sel);
        host.updateInfoArea(sel);
        host.updateContentArea(sel);
        if (host.currentModeConfirmed() && host.visibleCount() > 0) {
            host.startPreParse();
        }
    }

    /**
     * 局部参数变更后刷新（R8 泛化，清除参数功能复用）：受影响条目旧缓存失效、按新生效参数重解析。
     * 先取消在途后台活动（parseToken +1）作废旧代次在途解析结果；其它项目被代次打断的
     * 在途解析以同一新代次补偿续跑（只补仍处等待中的项，不重新解析已完成项，不误伤其它项目）。
     */
    private void refreshAfterTargetedParamsChange(List<PreviewItem> affected) {
        host.cancelWorkspaceActivity();
        for (PreviewItem m : affected) {
            // E1.2：清预览态的唯一实现是 PreviewItem.resetPreviewState()（此前的内联清单漏清 cachedMeta）
            m.resetPreviewState();
        }
        Object sel = host.currentSelection();
        host.updateBadgeHeader();
        host.updateOutputModeLabel(sel);
        host.updateRenameNote(sel);
        host.recomputeBadges();
        host.fileListView().refresh();
        host.updateAdjustCheck(sel);
        host.updateInfoArea(sel);
        host.updateContentArea(sel);
        host.updateProgressStatus();
        if (!affected.isEmpty()) {
            host.startPreParseFor(affected);
        }
        List<PreviewItem> interrupted = host.allVisibleItems().stream()
                .filter(i -> i.status == PreviewItem.Status.WAITING && !affected.contains(i)).toList();
        if (!interrupted.isEmpty()) {
            host.startPreParseFor(interrupted);
        }
    }

    /** 开始按钮可用性的唯一派生函数 —— 所有禁用/恢复都必须经由此函数 */
    void updateStartButtonState() {
        boolean anyMode = host.anyModeSelected();
        // R1.7（步骤 4 · 决策 2.5 语义变更）：有未提交草稿时**不再禁用**开始按钮 ——
        // 点开始时由 ExtractionTaskCoordinator 的草稿门（preStartGates 第一道）弹卡拦截
        //（sidepanel.draftBlocksStart）。草稿状态通知仍会把本方法叫回来（其余条件兜底重算）。
        host.startButton().setDisable(!anyMode || host.visibleCount() == 0 || host.isImporting());
    }

    /** 参数类撤销分支（C4）：{@link WorkspaceLifecycleController#onUndoClicked} 的 PARAM_SET / CLEAR_PARAMS /
     *  APPLY_GLOBAL 三个 case 体逐字搬入（含对 restoreParamSnapshots / refreshAfterParamsChanged /
     *  clearExtractOutcomes / paramsChanged / startPreParseFor / 两参数缓存 /
     *  updateInfoArea / updateExtractBanner / updateListButtons / modeWord 的调用）。 */
    void applyParamUndo(UndoManager.Record<PreviewItem> record) {
        switch (record.type()) {
            case PARAM_SET -> {
                // 条目独立参数需求拍板：设置参数类操作（条目/组/全局）可撤销，恢复设置前的参数
                boolean im = record.paramImageMode();
                if (!record.clearItemParamsBefore().isEmpty() || !record.clearGroupParamsBefore().isEmpty()) {
                    // Q10/R7.5 批量「应用到所选」：一条记录携带全部目标的 before 快照，
                    // 恢复与 CLEAR_PARAMS 撤销共用同一份实现（不另写一套）
                    restoreParamSnapshots(im, record.clearItemParamsBefore(), record.clearGroupParamsBefore());
                } else if (record.paramTargetPath() != null) {
                    for (PreviewItem it : host.allPreviewItems()) {
                        if (it.path.equals(record.paramTargetPath())) {
                            if (im) {
                                it.imageItemParams = record.paramBefore();
                            } else {
                                it.textItemParams = record.paramBefore();
                            }
                            host.clearExtractOutcomes(List.of(it));
                            host.clearPreviewCache(it);
                            // 规则 (a)：撤销恢复旧参数 = 生效参数变化 → 删该项目缓存目录
                            host.paramsChanged(im, it);
                            host.startPreParseFor(List.of(it));
                            break;
                        }
                    }
                } else if (record.paramTargetGroupNo() != null) {
                    for (Object o : host.dataOrder()) {
                        if (o instanceof GroupItem<?> g
                                && g.getGroupNo() == record.paramTargetGroupNo()) {
                            GroupItem<PreviewItem> grp = GroupStructure.asPreviewGroup(g);
                            if (im) {
                                grp.setImageParamsCache(record.paramBefore());
                            } else {
                                grp.setTextParamsCache(record.paramBefore());
                            }
                            List<PreviewItem> members = grp.getMembers().stream()
                                    .filter(m -> !m.removed).toList();
                            host.clearExtractOutcomes(members);
                            for (PreviewItem m : members) {
                                host.clearPreviewCache(m);
                            }
                            // 规则 (a)：撤销恢复组参数 → 成员生效参数变化 → 删其缓存目录
                            host.paramsChanged(im, members);
                            host.startPreParseFor(members);
                            break;
                        }
                    }
                } else {
                    if (im) {
                        globalParams.set(true, record.paramBefore());
                    } else {
                        globalParams.set(false, record.paramBefore());
                    }
                    host.clearExtractOutcomes(host.allPreviewItems());
                    // 规则 (a)：撤销恢复全局参数 → 全部条目生效参数可能变化 → 保守全删
                    host.paramsChanged(im, host.allPreviewItems());
                    refreshAfterParamsChanged();
                }
                host.fileListView().refresh();
                host.updateInfoArea(host.currentSelection());
                host.updateExtractBanner(host.currentSelection());
                host.updateListButtons();
                log.info(I18n.msg("log.maincontroller.53", modeWord(im)));
            }
            case CLEAR_PARAMS -> {
                // 清除参数拍板 Q4：按 before 快照一次恢复全部被清除的条目/组参数，并重走联动刷新
                boolean im = record.clearImageMode();
                Map<Integer, AppConfig> groupBefore = record.clearGroupParamsBefore();
                int restoredItems = restoreParamSnapshots(im, record.clearItemParamsBefore(), groupBefore);
                log.info(I18n.msg("log.maincontroller.54", modeWord(im), restoredItems, groupBefore.size()));
            }
            case APPLY_GLOBAL -> {
                // 条目独立参数需求拍板：恢复应用前的全局参数与全部独立参数快照
                boolean im = record.applyGlobalImageMode();
                if (im) {
                    globalParams.set(true, record.applyGlobalBefore());
                } else {
                    globalParams.set(false, record.applyGlobalBefore());
                }
                Map<String, AppConfig> itemBefore = record.applyGlobalItemParamsBefore();
                for (PreviewItem it : host.allPreviewItems()) {
                    if (itemBefore.containsKey(it.path)) {
                        AppConfig p = itemBefore.get(it.path);
                        if (im) {
                            it.imageItemParams = p;
                        } else {
                            it.textItemParams = p;
                        }
                    }
                }
                Map<Integer, AppConfig> groupBefore = record.applyGlobalGroupParamsBefore();
                for (Object o : host.dataOrder()) {
                    if (o instanceof GroupItem<?> g) {
                        if (groupBefore.containsKey(g.getGroupNo())) {
                            AppConfig p = groupBefore.get(g.getGroupNo());
                            if (im) {
                                g.setImageParamsCache(p);
                            } else {
                                g.setTextParamsCache(p);
                            }
                        }
                    }
                }
                host.clearExtractOutcomes(host.allPreviewItems());
                // 规则 (a)：撤销应用到全局 → 全部条目生效参数可能变化 → 保守全删
                host.paramsChanged(im, host.allPreviewItems());
                refreshAfterParamsChanged();
                host.updateListButtons();
                host.updateExtractBanner(host.currentSelection());
                log.info(I18n.msg("log.maincontroller.55", modeWord(im)));
            }
        }
    }

    /** 按 (path | groupNo → AppConfig) 快照恢复参数并重走联动刷新与缓存失效（清除参数 / 批量应用共用） */
    private int restoreParamSnapshots(boolean im, Map<String, AppConfig> itemBefore,
                                      Map<Integer, AppConfig> groupBefore) {
        List<PreviewItem> restoredItems = new ArrayList<>();
        for (PreviewItem it : host.allPreviewItems()) {
            if (itemBefore.containsKey(it.path)) {
                if (im) it.imageItemParams = itemBefore.get(it.path);
                else it.textItemParams = itemBefore.get(it.path);
                restoredItems.add(it);
            }
        }
        List<PreviewItem> affected = new ArrayList<>(restoredItems);
        for (Object o : host.dataOrder()) {
            if (o instanceof GroupItem<?> g) {
                GroupItem<PreviewItem> grp = GroupStructure.asPreviewGroup(g);
                if (groupBefore.containsKey(grp.getGroupNo())) {
                    if (im) grp.setImageParamsCache(groupBefore.get(grp.getGroupNo()));
                    else grp.setTextParamsCache(groupBefore.get(grp.getGroupNo()));
                    affected.addAll(grp.getMembers().stream()
                            .filter(m -> !m.removed).toList());
                }
            }
        }
        host.clearExtractOutcomes(affected);
        // 规则 (a)：撤销恢复旧参数 = 生效参数变化 → 删受影响条目缓存目录（经 WorkspaceParamService 收口）
        host.paramsChanged(im, affected);
        refreshAfterTargetedParamsChange(affected);
        host.updateListButtons();
        host.updateExtractBanner(host.currentSelection());
        return restoredItems.size();
    }

    /** 日志文案里的模式词（图片/文本 → Image/Text），供 log.* 模板的 {0} 占位符使用 */
    private static String modeWord(boolean imageMode) {
        return I18n.get(imageMode ? "mode.image" : "mode.text");
    }
}
