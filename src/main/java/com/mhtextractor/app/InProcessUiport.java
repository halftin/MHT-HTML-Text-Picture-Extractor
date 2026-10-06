package com.mhtextractor.app;

import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uiport.OutputUnitVm;
import com.mhtextractor.uiport.ParamSchemaVm;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.uiport.Confirmer;
import com.mhtextractor.uiport.GroupVm;
import com.mhtextractor.uiport.ItemVm;
import com.mhtextractor.uiport.PortEvent;
import com.mhtextractor.uiport.PortEvents;
import com.mhtextractor.uiport.TriState;
import com.mhtextractor.uiport.UpdateKind;
import com.mhtextractor.uiport.Uiport;
import com.mhtextractor.uiport.WorkspaceCommands;
import com.mhtextractor.uiport.WorkspaceQueries;
import com.mhtextractor.uiport.WorkspaceVm;
import com.mhtextractor.util.GroupStructure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@link Uiport} 的进程内实现（P3-3a）—— 端口层的 in-process 适配器。
 *
 * <p><b>定位</b>：端口（{@code com.mhtextractor.uiport}）是前端访问工作区的<b>唯一入口</b>；
 * 本类是该端口的第一个实现，只覆盖<b>后端当前真能回答</b>的能力子集
 * （P3-3a 诚实化收敛后的切片）。随着后端能力逐步下沉（P3-3b+：勾选命令、在途活动取消、
 * 提取/导入编排与统计），本类随之扩展，前端代码无需改动。</p>
 *
 * <p><b>零 JavaFX / 零 I18n / 不依赖 gui</b>：本类只依赖 {@code model}、{@code util} 与
 * {@code uiport}（均受 {@code ArchitectureTest} 的零 JavaFX 不变式覆盖）。</p>
 *
 * <p><b>通道语义</b>：</p>
 * <ul>
 *   <li>{@link #commands()} —— 撤销 / 清场直接转发 {@link WorkspaceModel} 命令；
 *       勾选与分组命令集（B3）按路径 / 组号解析后转发（{@code setChecked/group/ungroup/
 *       renameGroup/toggleGroupExpanded}，未知路径静默跳过、未知组号 no-op）；</li>
 *   <li>{@link #queries()} —— 每次调用<b>重新组装</b>只读快照（{@link WorkspaceVm}），
 *       <b>不缓存</b>：快照必须总是当前模型状态；</li>
 *   <li>{@link #events()} —— {@link WorkspaceModel#addListener} 桥接：{@link WorkspaceChange.Kind}
 *       映射为 {@link PortEvent.WorkspaceChanged#kind()}；返回的句柄 {@code close()} 即退订（幂等）；</li>
 *   <li>{@link #confirmer()} —— 返回构造注入的 {@link Confirmer}：卡片渲染是前端的事，
 *       本类不实现确认卡片（无头测试传桩）。</li>
 * </ul>
 *
 * <p><b>快照组装口径</b>（与 GUI 的既有显示语义一致，R10）：</p>
 * <ul>
 *   <li>{@link WorkspaceVm#groups()} —— 顶层组按数据序；成员含<b>已移除</b>条目
 *       （{@code removed=true}，由前端隐藏，见 {@link ItemVm#removed()}）；</li>
 *   <li>{@link WorkspaceVm#topLevelItems()} —— 顶层条目按数据序，含已移除条目（{@code removed=true}）；</li>
 *   <li>{@link ItemVm#unitIndex()} —— 条目在所属容器内的可见 0 基序号（R10）：顶层条目按显示序中
 *       排在它前面的可见条目计数（含展开组成员），组内成员按组内可见成员计数；
 *       已移除条目为 {@code null}（未参与显示与序号）；</li>
 *   <li>{@link WorkspaceVm#selection()} —— 勾选三态从条目 {@code selected} 导出
 *       （候选 = 全部可见条目；口径见 {@link TriState}）；</li>
 *   <li>{@link ItemVm#update()} —— 恒 {@link UpdateKind#NONE}：动画类别属前端渲染决策，后端不回答。</li>
 * </ul>
 */
public final class InProcessUiport implements Uiport {

    private final WorkspaceModel model;
    private final Confirmer confirmer;
    private final ExtractionPlanService plans;
    private final WorkspaceActivityService activities;

    /**
     * @param model     工作区模型（数据序 / 分组关系 / 撤销命令的唯一所有者）
     * @param confirmer 确认往返实现（由前端注入 —— 卡片是前端的事；无头测试传桩）
     */
    public InProcessUiport(WorkspaceModel model, Confirmer confirmer) {
        this(model, confirmer, new ExtractionPlanService(new ExtractionPlanService.Context() {
            public List<Object> dataOrder() { return model.topLevel(); }
            public boolean imageMode() { return false; }
            public AppConfig textParamsCache() { return null; }
            public AppConfig imageParamsCache() { return null; }
            public String inputFolder() { return null; }
            public String outputFolder() { return ""; }
            public boolean openFolderAfterComplete() { return false; }
        }));
    }

    public InProcessUiport(WorkspaceModel model, Confirmer confirmer, ExtractionPlanService plans) {
        this(model, confirmer, plans, new WorkspaceActivityService(new WorkspaceActivityService.Context() {
            public List<PreviewItem> items() { return model.allItems(); }
            public boolean textMode() { return !plans.runtimeConfig().isImageModeEnabled(); }
            public AppConfig effectiveParams(PreviewItem item) {
                return plans.effectiveParams(item);
            }
            public AppConfig effectiveImageParams(PreviewItem item) {
                return plans.effectiveImageParams(item);
            }
        }, new PreviewService(PreviewSession.create().lifecycle()),
                (image, index, params) -> new com.mhtextractor.model.ThumbnailData(
                        PreviewNamingService.imageFileName(image, PreviewNamingService.detectExtension(image.data(), "jpg"),
                                params.isEnableBatchRenaming(), index + 1, ""), 0, 0, null),
                Runnable::run));
    }

    public InProcessUiport(WorkspaceModel model, Confirmer confirmer, WorkspaceActivityService activities) {
        this(model, confirmer, new ExtractionPlanService(new ExtractionPlanService.Context() {
            public List<Object> dataOrder() { return model.topLevel(); }
            public boolean imageMode() { return false; }
            public AppConfig textParamsCache() { return null; }
            public AppConfig imageParamsCache() { return null; }
            public String inputFolder() { return null; }
            public String outputFolder() { return ""; }
            public boolean openFolderAfterComplete() { return false; }
        }), activities);
    }

    public InProcessUiport(WorkspaceModel model, Confirmer confirmer, ExtractionPlanService plans,
                          WorkspaceActivityService activities) {
        this.model = Objects.requireNonNull(model, "model");
        this.confirmer = Objects.requireNonNull(confirmer, "confirmer");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.activities = Objects.requireNonNull(activities, "activities");
    }

    @Override
    public WorkspaceCommands commands() {
        return new WorkspaceCommands() {
            public void cancelWorkspaceActivity() { activities.cancelWorkspaceActivity(); }
            public void cancelPreviewLoad() { activities.cancelPreviewLoad(); }
            public void startPreparse() { activities.startPreparse(); }
            public void startPreparseFor(List<String> paths) { activities.startPreparseFor(paths); }
            public void refreshPreparseProgress() { activities.refreshPreparseProgress(); }
            public void loadPreview(String path, boolean confirmed) { activities.loadPreview(path, confirmed); }
            public void awaitPreviewInFlight(long millis) { activities.awaitPreviewInFlight(millis); }
            @Override
            public void undo() {
                model.undo();
            }

            @Override
            public void clearItems() {
                model.clearItems();
            }

            // ---- B3：勾选命令 + 分组命令集（处置项 6 / 5）----

            @Override
            public void setChecked(List<String> paths, boolean checked) {
                model.setChecked(resolveItems(paths), checked);
            }

            @Override
            public void group(List<String> paths, String namePrefix) {
                model.group(resolveItems(paths), namePrefix);
            }

            @Override
            public void ungroup(long groupNo) {
                model.ungroup(groupNo);
            }

            @Override
            public void renameGroup(long groupNo, String newName) {
                model.renameGroup(groupNo, newName);
            }

            @Override
            public void toggleGroupExpanded(long groupNo) {
                model.toggleGroupExpanded(groupNo);
            }
        };
    }

    @Override
    public WorkspaceQueries queries() {
        return new WorkspaceQueries() {
            public long activityGeneration() { return activities.generation(); }
            public long previewGeneration() { return activities.previewGeneration(); }
            public String previewLoadingPath() { return activities.loadingPath(); }
            public WorkspaceVm workspace() { return snapshot(); }
            public AppConfig runtimeConfig() { return plans.runtimeConfig(); }
            public List<OutputUnitVm> outputUnitPlan(boolean checkedOnly) { return plans.outputUnitPlan(checkedOnly); }
            public ParamSchemaVm paramSchema(boolean imageMode) { return ParamSchemaService.schema(imageMode); }
        };
    }

    @Override
    public PortEvents events() {
        return listener -> {
            Objects.requireNonNull(listener, "listener");
            Consumer<WorkspaceChange> bridge = change ->
                    listener.accept(new PortEvent.WorkspaceChanged(change.kind().name()));
            model.addListener(bridge);
            AutoCloseable activitySubscription = activities.subscribe(listener);
            return () -> {
                model.removeListener(bridge);
                activitySubscription.close();
            };
        };
    }

    @Override
    public Confirmer confirmer() {
        return confirmer;
    }

    // ==================== 视图模型快照（只读、每次重新组装、不缓存） ====================

    private WorkspaceVm snapshot() {
        // 显示序中每个可见条目的 0 基序号（R10：顶层条目按显示序里排在它前面的可见条目计数，
        // 含展开组成员 —— 与 GUI 的 unitIndex 同一口径）
        Map<PreviewItem, Integer> displayIndex = new IdentityHashMap<>();
        int itemCount = 0;
        for (Object o : model.displayOrder()) {
            if (o instanceof PreviewItem it) {
                displayIndex.put(it, itemCount++);
            }
        }

        List<GroupVm> groups = new ArrayList<>();
        List<ItemVm> topLevelItems = new ArrayList<>();
        for (Object o : model.topLevel()) {
            if (o instanceof GroupItem<?> raw) {
                groups.add(toGroupVm(GroupStructure.asPreviewGroup(raw)));
            } else if (o instanceof PreviewItem it) {
                topLevelItems.add(toItemVm(it, displayIndex.get(it)));
            }
        }
        return new WorkspaceVm(groups, topLevelItems, model.canUndo(), model.undoDepth(), selection());
    }

    private static GroupVm toGroupVm(GroupItem<PreviewItem> g) {
        List<ItemVm> members = new ArrayList<>();
        int visibleIndex = 0;
        for (PreviewItem m : g.getMembers()) {
            members.add(toItemVm(m, m.removed ? null : visibleIndex++));
        }
        return new GroupVm(g.getGroupNo(), g.getName(), g.isExpanded(), g.isDefaultNamed(), members);
    }

    private static ItemVm toItemVm(PreviewItem it, Integer unitIndex) {
        return new ItemVm(it.path, it.name, it.selected, it.removed, unitIndex, UpdateKind.NONE);
    }

    /** 勾选三态：候选 = 全部可见条目（顶层 + 组内，removed 除外）；口径见 {@link TriState}。 */
    private TriState selection() {
        List<PreviewItem> candidates = model.allItems().stream().filter(i -> !i.removed).toList();
        long selected = candidates.stream().filter(i -> i.selected).count();
        if (candidates.isEmpty() || selected == 0) {
            return TriState.NONE;
        }
        return selected == candidates.size() ? TriState.ALL : TriState.SOME;
    }

    /** 按路径解析条目（保持入参顺序；未知路径静默跳过）。路径是导入去重后的唯一键。 */
    private List<PreviewItem> resolveItems(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        Map<String, PreviewItem> byPath = new HashMap<>();
        for (PreviewItem it : model.allItems()) {
            byPath.putIfAbsent(it.path, it);
        }
        List<PreviewItem> out = new ArrayList<>();
        for (String p : paths) {
            PreviewItem it = p == null ? null : byPath.get(p);
            if (it != null) {
                out.add(it);
            }
        }
        return out;
    }
}
