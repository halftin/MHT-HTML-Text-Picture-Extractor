package com.mhtextractor.gui.controller;

import com.mhtextractor.model.ItemStatus;

import com.mhtextractor.i18n.Texts;
import com.mhtextractor.uicore.WorkspaceView;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uicore.GroupRow;
import com.mhtextractor.uicore.ParamScope;
import com.mhtextractor.uicore.PreviewRow;
import com.mhtextractor.uiport.PortEvent;
import com.mhtextractor.uiport.PortEvent.ParamChangeKind;
import com.mhtextractor.uiport.TargetId;
import com.mhtextractor.uiport.Uiport;
import com.mhtextractor.gui.component.GuiNotifications;
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
 *
 * <p><b>B4（参数命令族）</b>：参数应用 / 清除 / 应用到全局的「写入 + 撤销压栈 + 缓存失效」语义已下沉
 * {@code app.WorkspaceParamCommandService}（端口命令 {@code setGlobalParams/setGroupParams/setItemParams/
 * clearParams/applyGlobalParams} 同源）。本类只剩三件事：① 两套全局参数缓存实例（app 层命令经
 * {@link #globalCache()} 读写同一实例）；② 应用入口的薄转发（含 {@code log.maincontroller.15/17/18/27}
 * 四条日志，键与实参不变）；③ 按 {@link PortEvent.ParamStateChanged} 事件执行列表刷新与参数色刷新链
 * （{@link #handleParamState} —— 原各应用方法的 UI 派生刷新段逐字保留，仅触发方式改为事件驱动）。
 * 参数类撤销（端口 C19）与 {@code log.maincontroller.53/54/55} 保持不变。</p>
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
        List<PreviewRow> allVisibleItems();
        List<PreviewRow> allPreviewItems();
        AppConfig effectiveForObject(Object item);
        boolean currentModeConfirmed();
        long visibleCount();
        boolean isImporting();
        // 缓存失效收口（经 WorkspaceParamService；本类不得出现规则方法名，见 ArchitectureTest 不变式）
        /** 单条目（对应服务单条目重载 —— 与批量重载语义不同，勿合并） */

        /** 条目集合（对应服务批量重载） */

        // 逐条目 UI 内存态复位（原 clearPreviewCache(PreviewRow)）
        void clearPreviewCache(PreviewRow item);
        // 提取结果色 / 信息区 / 按钮的派生刷新
        void clearExtractOutcomes(List<PreviewRow> items);
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
        void startPreParseFor(List<PreviewRow> items);
        void startPreParse();
        void updateProgressStatus();
    }

    private final Host host;

    /** 端口（B4）：参数应用 / 清除命令的唯一入口；参数状态事件（ParamStateChanged）的订阅源。 */
    private final Uiport port;

    /** 两套已确认提取参数缓存（P1-1 下沉）：值在 app 层 {@link GlobalParameterCache}；null 表示该模式尚未确认过参数 */


    public ParamDomainController(Host host, Uiport port) {
        this.host = host;
        this.port = port;
        port.events().subscribe(this::acceptPortEvent);
    }

    /** 两套已确认参数缓存实例（B4：app 层参数命令经此读写同一实例；值仍由本类独占持有） */


    /** 文本模式已确认参数缓存（包级委托方法，原包级字段；供 MainController / AcceptanceDriver 继续按原名读取） */
    AppConfig textParamsCache() {return port.queries().parameterState(false,List.of(),com.mhtextractor.uiport.TargetResolution.EXPLICIT).textGlobal();}

    void textParamsCache(AppConfig value) {port.commands().setGlobalParams(false,value);}

    /** 图片模式已确认参数缓存（包级委托方法，原包级字段；理由同 {@link #textParamsCache()}） */
    AppConfig imageParamsCache() {return port.queries().parameterState(true,List.of(),com.mhtextractor.uiport.TargetResolution.EXPLICIT).imageGlobal();}

    void imageParamsCache(AppConfig value) {port.commands().setGlobalParams(true,value);}

    /** 选择对象 → 参数作用域目标（R7.4 复用点）："设置参数"按钮与"应用到所选"共用这一份路由 */
    ParamScope.Target paramScopeTarget(Object sel) {
        return switch (sel) {
            case GroupRow<?> g -> new ParamScope.GroupTarget(g);
            case PreviewRow it when it.parentGroup != null -> new ParamScope.GroupTarget(it.parentGroup);
            case PreviewRow it -> new ParamScope.ItemTarget<>(it, it.getTextParamsCache(), it.getImageParamsCache());
            case null, default -> ParamScope.GLOBAL;
        };
    }

    /** 确认路径的应用本体（R7.4 唯一实现点）：组 → 组参数；条目 → 条目参数；其余 → 全局参数。
     *  B4 起为薄转发：写入 / 撤销 / 缓存失效在 app 层（端口命令），UI 刷新按 ParamStateChanged 事件做。 */
    void applyTargetParams(ParamScope.Target target, boolean imageMode, AppConfig params) {
        switch (target) {
            case ParamScope.GroupTarget(GroupRow<?> g) -> applyGroupParams(WorkspaceView.asPreviewGroup(g), imageMode, params);
            case ParamScope.ItemTarget<?> it -> applyItemParams((PreviewRow) it.item(), imageMode, params);
            default -> {
                applyConfirmedParams(imageMode, params);
                log.info(Texts.msg("log.maincontroller.15", modeWord(imageMode)));
            }
        }
    }

    /** 打开参数设置侧边栏并应用确认结果（按钮行与右键菜单共用；空白区域菜单项强制传全局目标） */
    void openParamDialog(ParamScope.Target target) {
        boolean imageMode = host.imageMode();
        try {
            // Q6a：非模态覆盖式侧边栏取代模态窗口。面板按窗口缓存（ParamSidePanel.of），
            // 复用同一个 ParamFormPane 实例 ⇒ "收起再展开同一目标"保留草稿（R1.5）。
            ParamSidePanel panel = ParamSidePanel.of(host.anchor(),port);
            // Q6b/R1.7：草稿变了（改字段 / 提交 / 切目标丢弃）就重算开始按钮 —— 判定仍只有
            // ParamFormPane.isDirty() 一处，这里只接线（每次 show 都重设，幂等）。
            panel.setDirtyListener(this::updateStartButtonState);
            panel.show(target, imageMode, textParamsCache(), imageParamsCache(),
                    params -> applyTargetParams(target, imageMode, params));
            updateStartButtonState();   // 本次 show 可能什么都没变（同目标重开 / 取消切换）⇒ 兜底重算
        } catch (Exception e) {
            log.error(Texts.msg("log.maincontroller.16"), e);
            GuiNotifications.paramDialogOpenFailed(host.anchor());
        }
    }

    /** 写入某条目的条目级独立参数（B4 起薄转发：写入/撤销/缓存失效在 app 层；日志键与实参不变） */
    void applyItemParams(PreviewRow item, boolean imageMode, AppConfig params) {
        port.commands().setItemParams(item.path, imageMode, params);
        log.info(Texts.msg("log.maincontroller.17", item.name, modeWord(imageMode)));
    }

    /** 写入某模式的已确认参数缓存（B4 起薄转发；agent 演练/自动化测试也走此入口） */
    void applyConfirmedParams(boolean imageMode, AppConfig params) {
        port.commands().setGlobalParams(imageMode, params);
    }

    /** 写入某组的已确认参数缓存（B4 起薄转发；日志键与实参不变） */
    void applyGroupParams(GroupRow<PreviewRow> group, boolean imageMode, AppConfig params) {
        log.info(Texts.msg("log.maincontroller.18", group.getName(), modeWord(imageMode)));
        port.commands().setGroupParams(group.getGroupNo(), imageMode, params);
    }

    /** 应用到全局（B4 起薄转发）：入口守卫保留（无生效参数 → 不动、不打日志）；
     *  写入 + 完整快照撤销 + 缓存失效在 app 层，UI 刷新按事件做。 */
    void applyGlobalParamsTo(Object target) {
        AppConfig params = host.effectiveForObject(target);
        if (params == null) {
            return;
        }
        boolean imageMode = host.imageMode();
        port.commands().applyGlobalParams(targetIds(target).get(0), imageMode);
        log.info(Texts.msg("log.maincontroller.27", modeWord(imageMode)));
    }

    /** 勾选集合优先 → 回退对象（与"移除"的勾选优先拍板一致）；复制/应用与清除参数共用同一"所选"口径。
     *  解析实现在 app 层 {@link ParamTargets}（B4 下沉；后端清除参数命令内部也用同一份）。 */
    List<Object> resolveParamTargets(Object fallback) {
        var ids=port.queries().parameterState(host.imageMode(),targetIds(fallback)).targets();
        var rows=new java.util.HashMap<TargetId,Object>();
        for(var item:host.allPreviewItems())rows.put(TargetId.ofItem(item.path),item);
        for(var row:host.dataOrder())if(row instanceof GroupRow<?> group)rows.put(TargetId.ofGroup(group.getGroupNo()),group);
        return ids.stream().map(rows::get).filter(java.util.Objects::nonNull).toList();
    }

    /** 清除参数（B4 起薄转发）：勾选优先解析由端口命令在 app 层执行（{@link ParamTargets#resolve}），
     *  前端只传回退目标（右键命中 / 当前选中，可为 null）；单条 CLEAR_PARAMS 撤销与 log.28 均在 app 层。 */
    void clearParamsFor(Object fallback) {
        port.commands().clearParams(targetIds(fallback), host.imageMode());
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
        Set<GroupRow<PreviewRow>> groups = new LinkedHashSet<>();
        if (targets != null) {
            for (Object o : targets) {
                if (o instanceof PreviewRow it) {
                    if (it.parentGroup != null) {
                        groups.add(it.parentGroup);
                    } else if (!it.removed) {
                        topLevel++;
                    }
                } else if (o instanceof GroupRow<?> g) {
                    groups.add(WorkspaceView.asPreviewGroup(g));
                }
            }
        }
        return new TargetCounts(topLevel, groups.size());
    }

    // ==================== B4：参数状态事件的 UI 派生刷新（原各应用方法的刷新段，逐字保留） ====================

    /** 端口事件入口（只关心参数状态变更；其余事件由 PreparseController / PreviewLoader 等各自订阅方处理） */
    private void acceptPortEvent(PortEvent event) {
        if (event instanceof PortEvent.ParamStateChanged psc) {
            handleParamState(psc.kind(), itemsByPath(psc.affectedPaths()));
        }
    }

    /** 按事件类别执行对应的刷新链（与各原应用方法的 UI 派生刷新段逐字一致，顺序不变）。 */
    private void handleParamState(ParamChangeKind kind, List<PreviewRow> affected) {
        switch (kind) {
            case SET_GLOBAL -> {
                // 拍板：全局参数重新确认 → 全部条目旧提取结果色失效
                host.clearExtractOutcomes(host.allPreviewItems());
                refreshAfterParamsChanged();
                host.updateExtractBanner(host.currentSelection());
            }
            case SET_ITEM -> {
                PreviewRow item = affected.get(0);
                host.clearExtractOutcomes(List.of(item));
                host.clearPreviewCache(item);
                host.startPreParseFor(List.of(item));
                host.fileListView().refresh();
                host.updateListButtons();
                host.updateInfoArea(item);
                host.updateExtractBanner(item);
            }
            case SET_GROUP -> {
                // 拍板：组参数重新确认 → 该组成员旧提取结果色失效
                host.clearExtractOutcomes(affected);
                refreshAfterTargetedParamsChange(affected);
                host.updateListButtons();
                host.updateExtractBanner(host.currentSelection());
            }
            case APPLY_GLOBAL -> {
                host.clearExtractOutcomes(host.allPreviewItems());
                refreshAfterParamsChanged();
                host.updateListButtons();
                host.updateExtractBanner(host.currentSelection());
            }
            case CLEAR -> {
                // 拍板：参数变更 → 受影响条目旧提取结果色失效
                host.clearExtractOutcomes(affected);
                refreshAfterTargetedParamsChange(affected);
                host.updateListButtons();
                host.updateExtractBanner(host.currentSelection());
            }
        }
    }

    /** 事件载荷（路径）→ 条目对象（按导入去重后的唯一键；全部条目含已移除）。 */
    private List<PreviewRow> itemsByPath(List<String> paths) {
        Map<String, PreviewRow> byPath = new HashMap<>();
        for (PreviewRow it : host.allPreviewItems()) {
            byPath.putIfAbsent(it.path, it);
        }
        List<PreviewRow> out = new ArrayList<>();
        for (String p : paths) {
            PreviewRow it = p == null ? null : byPath.get(p);
            if (it != null) {
                out.add(it);
            }
        }
        return out;
    }

    /** 列表行对象 → 端口目标寻址（条目按路径、组按组号）；null / 其他类型 → 空列表（回退为 null）。 */
    private static List<TargetId> targetIds(Object row) {
        if (row instanceof PreviewRow it) {
            return List.of(TargetId.ofItem(it.path));
        }
        if (row instanceof GroupRow<?> g) {
            return List.of(TargetId.ofGroup(g.getGroupNo()));
        }
        return List.of();
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
     * 先取消在途后台活动（活动代际 +1）作废旧代次在途解析结果；其它项目被代次打断的
     * 在途解析以同一新代次补偿续跑（只补仍处等待中的项，不重新解析已完成项，不误伤其它项目）。
     */
    private void refreshAfterTargetedParamsChange(List<PreviewRow> affected) {
        host.cancelWorkspaceActivity();
        for (PreviewRow m : affected) {
            // E1.2：清预览态的唯一实现是 PreviewRow.resetPreviewState()（此前的内联清单漏清 cachedMeta）
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
        List<PreviewRow> interrupted = host.allVisibleItems().stream()
                .filter(i -> i.status == ItemStatus.WAITING && !affected.contains(i)).toList();
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


    /** 按 (path | groupNo → AppConfig) 快照恢复参数并重走联动刷新与缓存失效（清除参数 / 批量应用共用） */


    /** 日志文案里的模式词（图片/文本 → Image/Text），供 log.* 模板的 {0} 占位符使用 */
    private static String modeWord(boolean imageMode) {
        return Texts.get(imageMode ? "mode.image" : "mode.text");
    }
}
