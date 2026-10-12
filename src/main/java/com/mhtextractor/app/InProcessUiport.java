package com.mhtextractor.app;

import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.ParamScope;
import com.mhtextractor.uiport.ImportPlanVm;
import com.mhtextractor.uiport.OutputUnitVm;
import com.mhtextractor.uiport.ParamSchemaVm;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.uiport.TargetId;
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
 *       renameGroup/toggleGroupExpanded}，未知路径静默跳过、未知组号 no-op）；
 *       参数命令族（B4）转发 {@link WorkspaceParamCommandService}（写入 + 撤销压栈 +
 *       缓存失效收口于 app 层，前端按 {@code ParamStateChanged} 事件刷新）；
 *       导入编排（B5-1）转发 {@link WorkspaceImportCoordinator}（会话/轮次/配额确认往返/
 *       自动建组命名/撤销压栈，前端按 {@code ImportStateChanged/ImportRound/ImportFinished} 事件刷新）；
 *       提取任务通道（B6）转发 {@link WorkspaceExtractionCoordinator}（请求构建 + 任务代际 +
 *       终态恰好一次 + 取消竞态，前端按 {@code ExtractionProgress/ExtractionTerminal/
 *       ExtractionResult} 事件渲染）；</li>
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
    private final WorkspaceParamCommandService paramCommands;
    private final WorkspaceImportCoordinator imports;
    private final WorkspaceExtractionCoordinator extractions;
    private final WorkspaceParamService structureCaches;
    private final List<Consumer<PortEvent>> listeners = new ArrayList<>();
    private final List<Runnable> pendingEvents = new ArrayList<>();
    private boolean completingStructure;

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
        this(model, confirmer, plans, activities, defaultParamCommands(model));
    }

    /** B4：带参数命令服务的便捷构造（计划用默认实现 —— 与 {@link #InProcessUiport(WorkspaceModel, Confirmer, WorkspaceActivityService)} 同口径）。 */
    public InProcessUiport(WorkspaceModel model, Confirmer confirmer, WorkspaceActivityService activities,
                          WorkspaceParamCommandService paramCommands) {
        this(model, confirmer, new ExtractionPlanService(new ExtractionPlanService.Context() {
            public List<Object> dataOrder() { return model.topLevel(); }
            public boolean imageMode() { return false; }
            public AppConfig textParamsCache() { return null; }
            public AppConfig imageParamsCache() { return null; }
            public String inputFolder() { return null; }
            public String outputFolder() { return ""; }
            public boolean openFolderAfterComplete() { return false; }
        }), activities, paramCommands);
    }

    /**
     * B4：带参数命令服务的完整构造（GUI 传入共享两套全局参数缓存与剪贴板压栈的服务；
     * 无头测试用 {@link #defaultParamCommands} 的默认服务）。
     *
     * <p><b>B5-1</b>：导入编排协调器用无头默认（{@code Runnable::run} 派发器）—— 需 FX 派发
     * （{@code Platform::runLater}）的 GUI 调用方用下一重载显式传入协调器。</p>
     */
    public InProcessUiport(WorkspaceModel model, Confirmer confirmer, ExtractionPlanService plans,
                          WorkspaceActivityService activities, WorkspaceParamCommandService paramCommands) {
        this(model, confirmer, plans, activities, paramCommands,
                new WorkspaceImportCoordinator(model, confirmer, Runnable::run));
    }

    /** B5-1：带导入编排协调器的便捷构造（GUI 用：活动/参数服务 + 共享 FX 派发器与确认卡实现的协调器）。 */
    public InProcessUiport(WorkspaceModel model, Confirmer confirmer, WorkspaceActivityService activities,
                          WorkspaceParamCommandService paramCommands, WorkspaceImportCoordinator imports) {
        this(model, confirmer, new ExtractionPlanService(new ExtractionPlanService.Context() {
            public List<Object> dataOrder() { return model.topLevel(); }
            public boolean imageMode() { return false; }
            public AppConfig textParamsCache() { return null; }
            public AppConfig imageParamsCache() { return null; }
            public String inputFolder() { return null; }
            public String outputFolder() { return ""; }
            public boolean openFolderAfterComplete() { return false; }
        }), activities, paramCommands, imports);
    }

    /**
     * B5-1：带导入编排协调器的完整构造（GUI 传入共享 FX 派发器与确认卡实现的协调器；
     * 无头调用方可注入小配额/延迟派发器的协调器复现「每轮打满」）。
     */
    public InProcessUiport(WorkspaceModel model, Confirmer confirmer, ExtractionPlanService plans,
                          WorkspaceActivityService activities, WorkspaceParamCommandService paramCommands,
                          WorkspaceImportCoordinator imports) {
        this(model, confirmer, plans, activities, paramCommands, imports, null);
    }

    /**
     * B6：带提取任务通道的完整构造（GUI 用 —— 共享真实计划服务的请求工厂与 FX 派发器的
     * 协调器）。无提取通道的构造（{@code extractions = null}）下
     * {@code startExtraction/cancelExtraction} 抛 {@link UnsupportedOperationException}
     * （端口诚实化：不静默吞命令）。
     */
    public InProcessUiport(WorkspaceModel model, Confirmer confirmer, ExtractionPlanService plans,
                          WorkspaceActivityService activities, WorkspaceParamCommandService paramCommands,
                          WorkspaceImportCoordinator imports, WorkspaceExtractionCoordinator extractions) {
        this(model, confirmer, plans, activities, paramCommands, imports, extractions,
                new WorkspaceParamService(PreviewSession.create().lifecycle()));
    }

    public InProcessUiport(WorkspaceModel model, Confirmer confirmer, ExtractionPlanService plans,
                          WorkspaceActivityService activities, WorkspaceParamCommandService paramCommands,
                          WorkspaceImportCoordinator imports, WorkspaceExtractionCoordinator extractions,
                          WorkspaceParamService structureCaches) {
        this.model = Objects.requireNonNull(model, "model");
        this.confirmer = Objects.requireNonNull(confirmer, "confirmer");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.activities = Objects.requireNonNull(activities, "activities");
        this.paramCommands = Objects.requireNonNull(paramCommands, "paramCommands");
        this.imports = Objects.requireNonNull(imports, "imports");
        this.extractions = extractions;     // 可空：未装配提取通道的构造按诚实化口径拒绝命令
        this.structureCaches = Objects.requireNonNull(structureCaches);
    }

    /**
     * 默认参数命令服务（无头调用方）：自持两套全局参数缓存、直连模型的撤销压栈与
     * {@code WorkspaceParamService} 缓存失效（独立预览会话根，与 activities 的默认服务同一形态）。
     */
    static WorkspaceParamCommandService defaultParamCommands(WorkspaceModel model) {
        WorkspaceParamService cacheService = new WorkspaceParamService(PreviewSession.create().lifecycle());
        ParamClipboard clipboard = new ParamClipboard(model::push,
                target -> ParamScope.GLOBAL, (target, imageMode, params) -> { });
        return new WorkspaceParamCommandService(model, cacheService, new GlobalParameterCache(),
                clipboard::pushParamUndo);
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
            public void resolvePreviewRegion(long generation, String path, boolean accepted) {
                activities.resolvePreviewRegion(generation, path, accepted);
            }
            public void awaitPreviewInFlight(long millis) { activities.awaitPreviewInFlight(millis); }
            @Override
            public void undo() {
                model.undo();
            }

            @Override
            public void clearItems() {
                if (!model.topLevel().isEmpty()) model.clearItems();
            }

            // ---- B3：勾选命令 + 分组命令集（处置项 6 / 5）----

            @Override
            public void setChecked(List<String> paths, boolean checked) {
                var targets = resolveItems(paths);
                boolean changed = targets.stream().anyMatch(i -> i.selected != checked);
                model.setChecked(targets, checked);
                if (changed) changed("CHECKED_CHANGED");
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
                var before = groupByNo(groupNo);
                String name = before == null ? null : before.getName();
                boolean defaultNamed = before != null && before.isDefaultNamed();
                model.renameGroup(groupNo, newName);
                if (before != null && (!Objects.equals(name, before.getName()) || defaultNamed != before.isDefaultNamed())) {
                    changed("GROUP_RENAMED");
                }
            }

            @Override
            public void toggleGroupExpanded(long groupNo) {
                if (model.toggleGroupExpanded(groupNo)) changed("GROUP_EXPANDED_CHANGED");
            }

            public void removeItems(List<String> paths) {
                completeStructure(() -> {
                    var targets = resolveItems(paths).stream().filter(i -> !i.removed).distinct().toList();
                    model.removeItems(targets);
                    structureCaches.itemsRemoved(targets);
                    targets.forEach(PreviewItem::resetPreviewState);
                });
            }

            public void removeGroup(long groupNo) {
                completeStructure(() -> {
                    var group = groupByNo(groupNo);
                    if (group == null) return;
                    var members = List.copyOf(group.getMembers());
                    model.removeGroup(group);
                    structureCaches.itemsRemoved(members);
                    members.forEach(PreviewItem::resetPreviewState);
                });
            }

            public com.mhtextractor.uiport.DropVm applyDrop(TargetId dragged, TargetId target, String zone) {
                var source = resolveTarget(dragged);
                var destination = target == null ? null : resolveTarget(target);
                var region = DropResolver.Zone.valueOf(zone);
                if (target == null && region != DropResolver.Zone.BOTTOM) throw new IllegalArgumentException("End requires BOTTOM");
                if (region == DropResolver.Zone.INTO && destination instanceof PreviewItem) throw new IllegalArgumentException("INTO requires group");
                var operation = target == null
                        ? source instanceof GroupItem<?> g ? new DropResolver.MoveGroup(g, model.topLevel().size())
                        : new DropResolver.InsertTop(source, model.topLevel().size())
                        : DropResolver.resolve(model.topLevel(), source, destination, region);
                var result = new com.mhtextractor.uiport.DropVm[1];
                completeStructure(() -> {
                    var drop = model.applyResolvedDrop(operation);
                    if (drop.changed()) {
                        var reparse = new ArrayList<String>();
                        for (var item : drop.affected()) {
                            if (!item.removed && drop.affectedBefore().get(item) != item.parentGroup) {
                                if (item.parentGroup != null) {
                                    item.textItemParams = null;
                                    item.imageItemParams = null;
                                }
                                structureCaches.paramsChanged(true, item);
                                item.resetPreviewState();
                                if (plans.effectiveParams(item) != null) reparse.add(item.path);
                            }
                        }
                        if (!reparse.isEmpty()) activities.startPreparseFor(reparse);
                    }
                    result[0] = new com.mhtextractor.uiport.DropVm(drop.changed(),
                            drop.changed() ? drop.affected().stream().map(i -> i.path).toList() : List.of());
                });
                return result[0];
            }

            // ---- B4：参数命令族（处置项 3 / 4）----

            @Override
            public void setGlobalParams(boolean imageMode, AppConfig params) {
                paramCommands.setGlobalParams(imageMode, params);
            }

            @Override
            public void setGroupParams(long groupNo, boolean imageMode, AppConfig params) {
                paramCommands.setGroupParams(groupNo, imageMode, params);
            }

            @Override
            public void setItemParams(String path, boolean imageMode, AppConfig params) {
                paramCommands.setItemParams(path, imageMode, params);
            }

            @Override
            public void clearParams(List<TargetId> fallbackTargets, boolean imageMode) {
                paramCommands.clearParams(fallbackTargets, imageMode);
            }

            @Override
            public void applyGlobalParams(TargetId target, boolean imageMode) {
                paramCommands.applyGlobalParams(target, imageMode);
            }

            // ---- B5-1：导入编排 ----

            @Override
            public void importPaths(List<String> addresses, String sourceDesc, boolean fromDrag) {
                imports.importPaths(addresses, sourceDesc, fromDrag);
            }

            @Override
            public void applyImportPlan(ImportPlanVm plan) {
                imports.applyPlan(plan);
            }

            // ---- B6：提取任务通道 ----

            @Override
            public void startExtraction(boolean checkedOnly) {
                extractionChannel().startExtraction(checkedOnly);
            }

            @Override
            public void cancelExtraction() {
                extractionChannel().cancelExtraction();
            }
        };
    }

    /** B6：提取通道按诚实化口径解析（未装配 = 抛异常，不静默吞命令）。 */
    private WorkspaceExtractionCoordinator extractionChannel() {
        if (extractions == null) {
            throw new UnsupportedOperationException("Extraction channel is not wired");
        }
        return extractions;
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
            listeners.add(listener);
            Consumer<WorkspaceChange> bridge = change ->
                    deliver(listener, new PortEvent.WorkspaceChanged(change.kind().name()));
            model.addListener(bridge);
            AutoCloseable activitySubscription = activities.subscribe(listener);
            AutoCloseable paramSubscription = paramCommands.subscribe(listener);
            AutoCloseable importSubscription = imports.subscribe(listener);
            AutoCloseable extractionSubscription = extractions == null ? () -> { } : extractions.subscribe(listener);
            return () -> {
                model.removeListener(bridge);
                listeners.remove(listener);
                activitySubscription.close();
                paramSubscription.close();
                importSubscription.close();
                extractionSubscription.close();
            };
        };
    }

    @Override
    public Confirmer confirmer() {
        return confirmer;
    }

    private void changed(String kind) {
        for (var listener : List.copyOf(listeners)) deliver(listener, new PortEvent.WorkspaceChanged(kind));
    }

    private void deliver(Consumer<PortEvent> listener, PortEvent event) {
        if (completingStructure) pendingEvents.add(() -> listener.accept(event));
        else listener.accept(event);
    }

    private void completeStructure(Runnable operation) {
        completingStructure = true;
        try { operation.run(); }
        finally {
            completingStructure = false;
            var pending = List.copyOf(pendingEvents);
            pendingEvents.clear();
            pending.forEach(Runnable::run);
        }
    }

    private GroupItem<PreviewItem> groupByNo(long no) {
        return model.topLevel().stream().filter(o -> o instanceof GroupItem<?> g && g.getGroupNo() == no)
                .map(o -> GroupStructure.asPreviewGroup((GroupItem<?>) o)).findFirst().orElse(null);
    }

    private Object resolveTarget(TargetId id) {
        if (id == null) throw new IllegalArgumentException("Target required");
        if (id.groupNo() != null) {
            var group = groupByNo(id.groupNo());
            if (group == null) throw new IllegalArgumentException("Unknown group");
            return group;
        }
        return resolveItems(List.of(id.path())).stream().filter(i -> !i.removed).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown item"));
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
        List<TargetId> order = new ArrayList<>();
        for (Object o : model.topLevel()) {
            if (o instanceof GroupItem<?> raw) {
                groups.add(toGroupVm(GroupStructure.asPreviewGroup(raw)));
                order.add(TargetId.ofGroup(raw.getGroupNo()));
            } else if (o instanceof PreviewItem it) {
                topLevelItems.add(toItemVm(it, displayIndex.get(it)));
                order.add(TargetId.ofItem(it.path));
            }
        }
        return new WorkspaceVm(groups, topLevelItems, model.canUndo(), model.undoDepth(), selection(), order);
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
        return new ItemVm(it.path, it.name, it.selected, it.removed, unitIndex, UpdateKind.NONE, it.sourceFolder);
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
