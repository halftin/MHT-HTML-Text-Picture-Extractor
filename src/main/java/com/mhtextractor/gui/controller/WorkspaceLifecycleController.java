package com.mhtextractor.gui.controller;

import com.mhtextractor.app.WorkspaceModel;
import com.mhtextractor.app.WorkspaceStructure;
import com.mhtextractor.gui.component.GuiNotifications;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.app.UndoManager;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.ListView;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作区生命周期协作者（C4）：「移除命令 + 撤销入口（结构类分支）+ 窗口关闭/退出清缓存」从
 * {@link MainController} 原样外迁；宿主能力经 {@link Host} 收窄为具名方法（控制器逐个转发到其保留成员）。
 * 文案键、日志键（log.maincontroller.46..55）、确认卡片（GuiNotifications.removeAllConfirm /
 * removeGroupConfirm / listEmpty）、撤销记录语义、缓存规则 (a)/(c) 时机、关窗守卫
 * （NotificationHost.hasPendingDecision）与非 daemon 线程规则全部保持不变。
 *
 * <p>参数类撤销分支（PARAM_SET / CLEAR_PARAMS / APPLY_GLOBAL）已外迁到
 * {@link ParamDomainController}（步骤 1），经 {@link Host#applyParamUndo} 整体委派。本类含 JavaFX，属 gui 层。</p>
 */
@Slf4j
public final class WorkspaceLifecycleController {

    /** 宿主能力面：全部经具名方法收窄，不得把 MainController / 模型 / 整个 FXML namespace 传进来 */
    public interface Host {
        // 控件 / 锚点
        ListView<Object> fileListView();
        Node anchor();
        // 数据序只读 + 显示序
        List<Object> dataOrder();
        ObservableList<Object> displayItems();
        Object currentSelection();
        void selectRow(Object row);
        void selectNearestVisible(int fromDisplayIndex);
        // 查询
        List<PreviewItem> allVisibleItems();
        int visibleCount();
        int visibleMemberCount(GroupItem<PreviewItem> group);
        boolean isAnimating();
        // 模型命令（唯一入口 WorkspaceModel）
        void removeModelItems(List<PreviewItem> targets);
        void removeModelGroup(GroupItem<PreviewItem> group);
        boolean canUndo();
        UndoManager.Record<PreviewItem> popRecord();
        void applyUndo(UndoManager.Record<PreviewItem> record);
        List<PreviewItem> applyUndoAffected();
        // 参数类撤销整体委派（分支体在 ParamDomainController）
        void applyParamUndo(UndoManager.Record<PreviewItem> record);
        // 缓存规则收口（经 WorkspaceParamService；本类不得出现规则方法名，见 ArchitectureTest 不变式）
        void cacheRulesForRemovedItems(Iterable<PreviewItem> items);
        // 逐条目 UI 内存态复位（原 clearPreviewCache(PreviewItem)）
        void clearItemPreviewState(PreviewItem item);
        // 收尾 / 刷新
        void refreshAfterListChange();
        void startPreParseFor(List<PreviewItem> items);
        boolean currentModeConfirmed();
        // 窗口生命周期
        boolean hasCachedEntries();
        void cancelWorkspaceActivity();
        void awaitPreviewInFlight(long millis);
        Path cacheRoot();
        void onShutdownClearCache();
    }

    private final Host host;

    /** 退出清空已完成：二次 close 放行（规则 d） */
    private volatile boolean shutdownCacheCleared = false;
    /** 正在清空缓存（FX 线程独占）：挡住清空期间的重复关窗请求，避免开两份清理 */
    private boolean shutdownInProgress = false;

    public WorkspaceLifecycleController(Host host) {
        this.host = host;
    }

    /** 移除组（Q3 拍板：每次必弹确认；与按钮行整组移除共用同一路径，G2 统一） */
    void onContextRemoveGroup(Object target) {
        if (host.isAnimating()) {
            return;
        }
        GroupItem<PreviewItem> group = null;
        if (target instanceof GroupItem<?> g) {
            group = WorkspaceStructure.asPreviewGroup(g);
        } else if (target instanceof PreviewItem it && it.parentGroup != null) {
            group = it.parentGroup;
        }
        if (group != null) {
            removeGroupWithConfirm(group);
        }
    }

    void onRemoveClicked() {
        if (host.isAnimating()) {
            return;
        }
        // R5：有复选框勾选时按勾选集合移除（优先于当前选中项，含组内成员）
        List<PreviewItem> targets = host.allVisibleItems().stream()
                .filter(i -> i.selected).toList();
        Object sel = host.currentSelection();
        // D7：无勾选且选中组条目 → 整组移除（组名/参数随组对象保留在撤销记录中）
        if (targets.isEmpty() && sel instanceof GroupItem<?> g) {
            // G2（拍板）：整组移除一律必弹确认（R6"仅列表将空时确认"的有意例外），按钮行与右键菜单共用
            removeGroupWithConfirm(WorkspaceStructure.asPreviewGroup(g));
            return;
        }
        if (targets.isEmpty()) {
            if (sel instanceof PreviewItem it && !it.removed) {
                targets = List.of(it);
            } else {
                return;
            }
        }
        if (targets.size() >= host.visibleCount()) {
            // R13 提醒文案：全选含折叠组成员时提示（会话 19：回调式居中确认取代 showAndWait）
            final List<PreviewItem> pending = targets;
            String msg = I18n.get("list.removeAllConfirm", "count", pending.size())
                    + collapsedGroupHint(pending);
            GuiNotifications.removeAllConfirm(host.anchor(), msg, ok -> {
                if (ok) {
                    removeSelected(pending);
                }
            });
            return;
        }
        removeSelected(targets);
    }

    /** 移除收尾（原确认框之后的同步段）：执行移除 + 日志 + "列表已空"提醒 */
    private void removeSelected(List<PreviewItem> targets) {
        removeItems(targets);
        log.info(I18n.msg("log.maincontroller.46", targets.size(), host.visibleCount()));
        if (host.visibleCount() == 0) {
            GuiNotifications.listEmpty(host.anchor());
        }
    }

    /** 整组移除确认 + 执行（G2 拍板：按钮行与右键菜单统一每次必弹确认；文案区分"列表将空"与折叠提示） */
    private void removeGroupWithConfirm(GroupItem<PreviewItem> group) {
        int memberCount = host.visibleMemberCount(group);
        String collapsedHint = group.isExpanded()
                ? ""
                : I18n.get("group.removeNoteCollapsed");
        String msg = memberCount >= host.visibleCount()
                ? I18n.get("group.removeConfirm", "count", memberCount) + collapsedHint
                : I18n.get("group.removeConfirmSimple", "count", memberCount) + collapsedHint;
        GuiNotifications.removeGroupConfirm(host.anchor(), msg, ok -> {
            if (ok) {
                removeGroup(group);
                log.info(I18n.msg("log.maincontroller.47", group.getName(), memberCount, host.visibleCount()));
                if (host.visibleCount() == 0) {
                    GuiNotifications.listEmpty(host.anchor());
                }
            }
        });
    }

    /** 隐藏式移除：结构变更（removed 标记 + 空组自动销毁 D11）与撤销记录由 {@link WorkspaceModel#removeItems} 负责；
     *  本类只做缓存规则 (c) 与选择/刷新收尾 */
    private void removeItems(List<PreviewItem> targets) {
        Object sel = host.currentSelection();
        int selIndex = host.displayItems().indexOf(sel);
        host.removeModelItems(targets);                              // 结构 + 撤销记录（模型是唯一实现）
        // 规则 (c)：从列表移除 → 删该项目缓存目录
        host.cacheRulesForRemovedItems(targets);
        // 被选中项若已被移除（含"所在组被销毁"）→ 选最近的可见项
        boolean selRemoved = sel != null
                && ((sel instanceof PreviewItem it && targets.contains(it))
                || (sel instanceof GroupItem<?> g && host.dataOrder().stream().noneMatch(o -> o == g)));
        if (selRemoved) {
            host.selectNearestVisible(selIndex);
        }
        host.refreshAfterListChange();
    }

    /** 整组移除（D7）：结构变更与撤销记录由 {@link WorkspaceModel#removeGroup} 负责；本类只做缓存规则 (c) 与选择/刷新收尾 */
    private void removeGroup(GroupItem<PreviewItem> group) {
        Object sel = host.currentSelection();
        int selIndex = host.displayItems().indexOf(sel);
        List<PreviewItem> members = new ArrayList<>(group.getMembers());
        host.removeModelGroup(group);                                // 结构 + 撤销记录（模型是唯一实现）
        // 规则 (c)：整组移除 → 删组内成员缓存目录
        host.cacheRulesForRemovedItems(members);
        if (sel == group) {
            host.selectNearestVisible(selIndex);
        }
        host.refreshAfterListChange();
    }

    /** R13 确认框文案：待移除项目含折叠组成员时提示（折叠组内项目不可见，避免用户误以为未被选中） */
    private String collapsedGroupHint(List<PreviewItem> targets) {
        List<String> parts = new ArrayList<>();
        for (Object o : host.dataOrder()) {
            if (o instanceof GroupItem<?> g) {
                GroupItem<PreviewItem> group = WorkspaceStructure.asPreviewGroup(g);
                if (!group.isExpanded()) {
                    long cnt = group.getMembers().stream()
                            .filter(m -> !m.removed && targets.contains(m)).count();
                    if (cnt > 0) {
                        parts.add(group.getName() + I18n.get("group.itemCount", "count", cnt));
                    }
                }
            }
        }
        return parts.isEmpty()
                ? ""
                : I18n.get("group.removeNoteHidden",
                        "names", String.join(I18n.get("list.nameSeparator"), parts));
    }

    void onUndoClicked() {
        if (host.isAnimating() || !host.canUndo()) {
            return;
        }
        UndoManager.Record<PreviewItem> record = host.popRecord();
        Object selectAfter = null;
        // 结构恢复类操作（REORDER/MERGE_GROUPS）：恢复后生效参数可能变化，受影响项目重新预解析
        List<PreviewItem> reparseAfter = List.of();
        if (record != null) {
            switch (record.type()) {
                case REMOVE, REMOVE_GROUP -> {
                    // 结构恢复（removed 复位 + 被销毁的空组/整组按原顶层索引插回）由模型负责
                    host.applyUndo(record);
                    for (PreviewItem it : record.items()) {
                        it.setSelected(false);      // 选中态属 UI 职责，模型不管
                    }
                    log.info(I18n.msg("log.maincontroller.48", record.items().size(), record.destroyedGroups().size()));
                }
                case IMPORT -> {
                    Object sel = host.currentSelection();
                    int selIndex = host.displayItems().indexOf(sel);
                    boolean selRemoved = sel != null && record.items().contains(sel);
                    // 撤销导入 = 这批项目被移除：先做 UI 侧标记（removed 供致空组判定），
                    // 结构摘除（从组内与顶层两处 + 致空组销毁）交由模型——原先本类手写了等价的两段
                    for (PreviewItem it : record.items()) {
                        it.removed = true;
                        it.setSelected(false);
                    }
                    // 规则 (c)：撤销导入 = 从列表移除这批项目 → 删其缓存目录
                    host.cacheRulesForRemovedItems(record.items());
                    host.applyUndo(record);
                    if (selRemoved) {
                        host.selectNearestVisible(selIndex);
                    }
                    log.info(I18n.msg("log.maincontroller.49", record.items().size(), record.groups().size()));
                }
                case GROUP -> {
                    // 撤销建组：结构恢复（组解散、成员回原位置）由模型负责
                    host.applyUndo(record);
                    record.items().stream()
                            .filter(i -> !i.removed).findFirst().ifPresent(firstVisible -> host.selectRow(firstVisible));
                    log.info(I18n.msg("log.maincontroller.50", record.group().getName()));
                }
                case REORDER, MERGE_GROUPS -> {
                    // 拖拽类：结构恢复（顶层顺序 / 组位置 / 组内顺序，组对象复用）自 Q0/P0 起由模型负责；
                    // 本类只做缓存失效与重解析收尾（缓存规则不得由 GUI 直调，见不变式）。
                    host.applyUndo(record);
                    reparseAfter = new ArrayList<>();
                    for (PreviewItem it : host.applyUndoAffected()) {
                        host.clearItemPreviewState(it);
                        reparseAfter.add(it);
                    }
                    log.info(I18n.msg("log.maincontroller.51", I18n.get(record.type() == UndoManager.RecordType.REORDER
                        ? "reorder.dragSort" : "reorder.groupMerge")));
                }
                case UNGROUP -> {
                    // F6：组重建（成员回组、组按原索引插回、条目级参数清除）由模型负责
                    host.applyUndo(record);
                    selectAfter = record.group();
                    log.info(I18n.msg("log.maincontroller.52", record.group().getName(), record.items().size()));
                }
                case PARAM_SET, CLEAR_PARAMS, APPLY_GLOBAL ->
                    // 参数类撤销分支体在 ParamDomainController（applyParamUndo），这里整体委派
                    host.applyParamUndo(record);
            }
        }
        host.refreshAfterListChange();
        if (selectAfter != null && host.displayItems().contains(selectAfter)) {
            host.selectRow(selectAfter);
        }
        // 父组指针已在 refreshAfterListChange 重建，此时按恢复后的生效参数重解析受影响项目
        if (!reparseAfter.isEmpty() && host.currentModeConfirmed()) {
            host.startPreParseFor(reparseAfter);
        }
    }

    /** 规则 (d)：关窗清空（§2.2/§2.6(6)）。有缓存 → 无按钮等待卡片 → 停后台活动 → 等在途写入（≤2s）→ 清空 → 清完自动关窗；
     *  无缓存 → 直接放行。只清 PreviewCacheStore 的 root，绝不触碰 java.io.tmpdir 整体。
     *
     *  <p><b>P7：本方法不再读 {@code test.mode}</b>。此前有一条"自动化不等卡片"的旁路分支，使生产启动代码
     *  与测试开关耦合；现在无论谁来关窗都走同一条生产路径。GUI 自动化改由测试源集的
     *  {@code gui.acceptance.TestMain} 承载，收尾用 {@code Platform.exit()+System.exit()}，
     *  不经过关窗处理器，因此那条旁路对它本来也不起作用。</p> */
    void onWindowCloseRequest(WindowEvent e) {
        if (shutdownCacheCleared) {
            return;                                    // 已清空 → 放行关闭
        }
        if (NotificationHost.hasPendingDecision(host.anchor())) {
            e.consume();                               // 有未回答的确认卡：不放行（旧模态 Alert 本就关不掉主窗）
            return;
        }
        if (shutdownInProgress) {
            e.consume();                               // 正在清空 → 继续等，重复的关窗请求一律挡住
            return;
        }
        if (!host.hasCachedEntries()) {
            return;                                    // 无缓存 → 放行（不显示卡片）
        }
        e.consume();
        shutdownInProgress = true;
        host.cancelWorkspaceActivity();                // 仍在 FX 线程调用：与旧实现同线程，保持 token 可见性语义
        Stage stage = (Stage) e.getSource();
        // 会话 19：无按钮等待卡片 —— 清空期间不可交互（persistent），清完自动收卡片并自动关窗，无需点确定
        GuiNotifications.cacheClearing(host.anchor(), host.cacheRoot(),
                this::clearPreviewCache, stage::close);
    }

    /** 关窗收尾（后台线程）：等在途写入结束 → 清空缓存（cancelWorkspaceActivity 已由调用方在 FX 线程先行调用） */
    private void clearPreviewCache() {
        host.awaitPreviewInFlight(2000);
        host.onShutdownClearCache();
        shutdownCacheCleared = true;
    }
}
