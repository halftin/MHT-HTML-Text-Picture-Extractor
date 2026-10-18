package com.mhtextractor.app;

import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.uiport.PortEvent;
import com.mhtextractor.uiport.PortEvent.ParamChangeKind;
import com.mhtextractor.uiport.TargetId;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 工作区参数命令服务（B4，处置项 3 / 4）—— 参数应用 / 清除 / 应用到全局的<b>唯一实现点</b>。
 *
 * <p><b>解决什么问题</b>：参数域的五条写路径原先由 {@code gui.controller.ParamDomainController} 的
 * {@code applyConfirmedParams / applyGroupParams / applyItemParams / applyGlobalParamsTo /
 * clearParamsForTargets} 实现（GUI 直写条目 / 组 / 全局参数 + 撤销压栈 + 缓存失效）。本类把
 * 「写入 + 撤销压栈 + 磁盘缓存失效（规则 a）」这三段语义<b>逐字</b>搬入 app 层，端口命令
 * （{@code uiport.WorkspaceCommands} 的 B4 成员）与本类同源转发；前端（GUI）不再持有参数写入逻辑，
 * 只按 {@link PortEvent.ParamStateChanged} 事件执行列表刷新与参数色刷新。</p>
 *
 * <p><b>语义逐字保留</b>：全局 / 组 / 条目三层覆盖、{@code imageMode} 分文本 / 图片参数、撤销载荷
 * （PARAM_SET 经 {@link ParamClipboard#pushParamUndo}（批量应用累积为整批一条），CLEAR_PARAMS 经
 * {@link UndoRecords#clearParams}，APPLY_GLOBAL 经 {@link UndoRecords#applyGlobal}）、缓存失效
 * （经 {@link WorkspaceParamService} 收口，GUI 不得直调缓存删除规则）、勾选优先口径
 * （{@link ParamTargets#resolve}）、以及 {@code log.maincontroller.28} 的日志键与实参 —— 全部与搬移前一致。</p>
 *
 * <p><b>事件契约</b>：每条<b>成功</b>命令（确实改变了参数状态）在完成后同步发布一次
 * {@link PortEvent.ParamStateChanged}；no-op（组不存在 / 路径不存在 / 无有效目标 / 生效参数为空）
 * 不发布、不压撤销记录。订阅见 {@link #subscribe}（与 {@code WorkspaceActivityService} 同一形态）。</p>
 *
 * <p><b>零 JavaFX</b>：仅依赖 model / i18n（日志键）与 uiport 的事件类型，由
 * {@code ArchitectureTest} 的零 JavaFX 不变式覆盖。</p>
 */
@Slf4j
public final class WorkspaceParamCommandService {

    /** 宿主上下文：模型 / 撤销 / 缓存失效 / 两套全局参数缓存的具名出口（GUI 匿名类逐个转发）。 */
    public interface Context {
        /** 全部条目（含组内成员、含已移除），不可变快照。 */
        List<PreviewItem> allItems();

        /** 顶层数据序（含组条目），不可变快照。 */
        List<Object> dataOrder();

        /** 撤销压栈（模型唯一入口 {@code WorkspaceModel.push}）。 */
        void pushUndo(UndoManager.Record<PreviewItem> record);

        /**
         * PARAM_SET 撤销压栈唯一出口（{@code ParamClipboard.pushParamUndo}）：
         * 批量应用期间只累积快照、整批一条。
         */
        void pushParamUndo(boolean imageMode, String path, Integer groupNo, AppConfig before);

        /** 缓存失效收口（规则 a；{@code imageMode == false} 时为 no-op，守卫在服务内）。 */
        WorkspaceParamService cacheService();

        /** 两套全局「已确认参数」缓存（写入 = 整体替换引用；null = 该模式尚未确认）。 */
        GlobalParameterCache globalParams();
    }

    /** PARAM_SET 撤销压栈出口的函数形态（与 {@link ParamClipboard#pushParamUndo} 同签名）。 */
    @FunctionalInterface
    public interface ParamUndoPusher {
        void push(boolean imageMode, String path, Integer groupNo, AppConfig before);
    }

    private final Context context;
    private final List<Consumer<PortEvent>> listeners = new CopyOnWriteArrayList<>();

    public WorkspaceParamCommandService(Context context) {
        this.context = java.util.Objects.requireNonNull(context, "context");
    }

    /**
     * 模型直连便捷构造（B4）：撤销压栈 = {@code model.push}、数据序 / 全条目 = {@code model} 快照。
     * 供 GUI 与无头调用方少写一个 Context（六个出口都是模型 / 服务直连的常见形态）。
     */
    public WorkspaceParamCommandService(WorkspaceModel model, WorkspaceParamService cacheService,
                                        GlobalParameterCache globalParams, ParamUndoPusher pushParamUndo) {
        this(new Context() {
            public List<PreviewItem> allItems() { return model.allItems(); }
            public List<Object> dataOrder() { return model.topLevel(); }
            public void pushUndo(UndoManager.Record<PreviewItem> record) { model.push(record); }
            public void pushParamUndo(boolean imageMode, String path, Integer groupNo, AppConfig before) {
                pushParamUndo.push(imageMode, path, groupNo, before);
            }
            public WorkspaceParamService cacheService() { return cacheService; }
            public GlobalParameterCache globalParams() { return globalParams; }
        });
    }

    /** 订阅参数状态变更（句柄 {@code close()} 即退订，幂等）。 */
    public AutoCloseable subscribe(Consumer<PortEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void emit(PortEvent event) {
        for (Consumer<PortEvent> listener : listeners) {
            listener.accept(event);
        }
    }

    /**
     * U2：只恢复已由模型弹出的参数记录，不压栈、不发第二个完成事件。
     * 模型在真实值、派生态及规则 (a) 磁盘失效全部完成后发布 UNDO_APPLIED。
     */
    List<PreviewItem> restoreUndo(UndoManager.Record<PreviewItem> record) {
        boolean imageMode;
        List<PreviewItem> affected;
        switch (record.type()) {
            case PARAM_SET -> {
                imageMode = record.paramImageMode();
                if (!record.clearItemParamsBefore().isEmpty() || !record.clearGroupParamsBefore().isEmpty()) {
                    affected = restoreParamSnapshots(imageMode, record.clearItemParamsBefore(), record.clearGroupParamsBefore());
                } else if (record.paramTargetPath() != null) {
                    Map<String, AppConfig> before = new HashMap<>();
                    before.put(record.paramTargetPath(), record.paramBefore());
                    affected = restoreParamSnapshots(imageMode, before, Map.of());
                } else if (record.paramTargetGroupNo() != null) {
                    Map<Integer, AppConfig> before = new HashMap<>();
                    before.put(record.paramTargetGroupNo(), record.paramBefore());
                    affected = restoreParamSnapshots(imageMode, Map.of(), before);
                } else {
                    context.globalParams().set(imageMode, record.paramBefore());
                    affected = context.allItems();
                }
            }
            case CLEAR_PARAMS -> {
                imageMode = record.clearImageMode();
                affected = restoreParamSnapshots(imageMode, record.clearItemParamsBefore(), record.clearGroupParamsBefore());
            }
            case APPLY_GLOBAL -> {
                imageMode = record.applyGlobalImageMode();
                context.globalParams().set(imageMode, record.applyGlobalBefore());
                restoreParamSnapshots(imageMode, record.applyGlobalItemParamsBefore(), record.applyGlobalGroupParamsBefore());
                affected = context.allItems();
            }
            default -> throw new IllegalArgumentException("Parameter undo record required");
        }
        affected = List.copyOf(new LinkedHashSet<>(affected));
        for (PreviewItem item : affected) {
            item.resetPreviewState();
            item.extractOutcome = com.mhtextractor.model.ExtractOutcome.NONE;
            item.extractMessageKey = null;
            item.extractMessageParams = null;
            item.extractMessageDetail = null;
            item.extractWarnings = java.util.List.of();
        }
        context.cacheService().paramsChanged(imageMode, affected);
        return affected;
    }

    /** null 值也是 before：恢复未确认状态；按目标失效，组成员排除 removed。 */
    private List<PreviewItem> restoreParamSnapshots(boolean imageMode, Map<String, AppConfig> itemBefore,
                                                   Map<Integer, AppConfig> groupBefore) {
        List<PreviewItem> affected = new ArrayList<>();
        for (PreviewItem item : context.allItems()) {
            if (itemBefore.containsKey(item.path)) {
                if (imageMode) item.imageItemParams = itemBefore.get(item.path);
                else item.textItemParams = itemBefore.get(item.path);
                affected.add(item);
            }
        }
        for (Object target : context.dataOrder()) {
            if (target instanceof GroupItem<?> group && groupBefore.containsKey(group.getGroupNo())) {
                if (imageMode) group.setImageParamsCache(groupBefore.get(group.getGroupNo()));
                else group.setTextParamsCache(groupBefore.get(group.getGroupNo()));
                affected.addAll(WorkspaceStructure.asPreviewGroup(group).getMembers().stream().filter(i -> !i.removed).toList());
            }
        }
        return affected;
    }

    // ==================== B4-1：参数应用三命令（原 applyConfirmedParams / applyGroupParams / applyItemParams） ====================

    /**
     * 全局参数确认写入：写入当前模式已确认参数缓存并刷新工作区；入撤销栈（PARAM_SET）。
     * 原 {@code ParamDomainController.applyConfirmedParams} 的参数应用语义逐字搬入（UI 派生刷新由前端按事件做）。
     */
    public void setGlobalParams(boolean imageMode, AppConfig params) {
        GlobalParameterCache global = context.globalParams();
        AppConfig before = imageMode ? global.image() : global.text();
        if (imageMode) {
            global.set(true, params);
        } else {
            global.set(false, params);
        }
        context.pushParamUndo(imageMode, null, null, before);
        List<PreviewItem> all = context.allItems();
        // 规则 (a)：全局参数变化 → 全部条目缓存目录删除（保守全删：有独立参数的条目删除也只是下次预览重建，语义安全）
        context.cacheService().paramsChanged(imageMode, all);
        emit(new PortEvent.ParamStateChanged(ParamChangeKind.SET_GLOBAL, imageMode, pathsOf(all)));
    }

    /**
     * 组参数写入（组号寻址；组不存在时为 no-op）：入撤销栈（PARAM_SET，组参数设置可撤销）。
     * 原 {@code ParamDomainController.applyGroupParams} 的参数应用语义逐字搬入。
     */
    public void setGroupParams(long groupNo, boolean imageMode, AppConfig params) {
        GroupItem<PreviewItem> group = groupByNo(groupNo);
        if (group == null) {
            return;   // 组不存在 → 纯 no-op
        }
        AppConfig before = imageMode ? group.getImageParamsCache() : group.getTextParamsCache();
        if (imageMode) {
            group.setImageParamsCache(params);
        } else {
            group.setTextParamsCache(params);
        }
        context.pushParamUndo(imageMode, null, group.getGroupNo(), before);
        List<PreviewItem> members = group.getMembers().stream().filter(m -> !m.removed).toList();
        // 规则 (a)：组参数变化 → 该组成员缓存目录删除
        context.cacheService().paramsChanged(imageMode, members);
        emit(new PortEvent.ParamStateChanged(ParamChangeKind.SET_GROUP, imageMode, pathsOf(members)));
    }

    /**
     * 条目级参数写入（路径寻址；路径不存在时静默跳过）：不写全局，仅清缓存重解析该条目；
     * 入撤销栈（PARAM_SET）。原 {@code ParamDomainController.applyItemParams} 的参数应用语义逐字搬入。
     */
    public void setItemParams(String path, boolean imageMode, AppConfig params) {
        PreviewItem item = findByPath(path);
        if (item == null) {
            return;   // 路径不存在 → 静默跳过
        }
        AppConfig before = imageMode ? item.imageItemParams : item.textItemParams;
        if (imageMode) {
            item.imageItemParams = params;
        } else {
            item.textItemParams = params;
        }
        context.pushParamUndo(imageMode, item.path, null, before);
        // 规则 (a)：参数变化 → 删该项目缓存目录（单条目重载 —— 与批量重载语义不同，勿合并）
        context.cacheService().paramsChanged(imageMode, item);
        emit(new PortEvent.ParamStateChanged(ParamChangeKind.SET_ITEM, imageMode, List.of(item.path)));
    }

    // ==================== B4-2：清除参数 / 应用到全局（原 clearParamsForTargets / applyGlobalParamsTo） ====================

    /**
     * 清除参数：只清当前模式。勾选优先（{@link ParamTargets#resolve}，勾选集合优先 → 回退
     * {@code fallbackTargets}）；组外条目 → 清条目级参数；组内成员/组条目 → 清整组组参数（按组去重）；
     * 无参数目标自然跳过；一次操作一条 CLEAR_PARAMS 撤销记录（before 快照），一次撤销全部恢复。
     * 原 {@code ParamDomainController.clearParamsForTargets} 逐字搬入（{@code log.maincontroller.28} 一并）。
     */
    public void clearParams(List<TargetId> fallbackTargets, boolean imageMode) {
        List<PreviewItem> visible = context.allItems().stream().filter(i -> !i.removed).toList();
        List<Object> rawTargets = ParamTargets.resolve(visible, resolveFallback(fallbackTargets));
        clearParamsForTargets(rawTargets, imageMode);
    }

    /**
     * 应用到全局：目标生效参数集写入当前模式全局缓存，清空当前模式所有独立参数（条目级 + 组，
     * 含目标自身），全部条目继承新全局；另一模式独立参数不受影响。入撤销栈（APPLY_GLOBAL，
     * 含应用前完整参数快照）。原 {@code ParamDomainController.applyGlobalParamsTo} 逐字搬入
     * （生效参数解析经 {@link EffectiveParamsService}，与 GUI 的 {@code effectiveForObject} 同源）。
     */
    public void applyGlobalParams(TargetId target, boolean imageMode) {
        Object resolved = resolveTarget(target);
        if (resolved == null) {
            return;   // 目标不可解析 → 纯 no-op
        }
        GlobalParameterCache global = context.globalParams();
        AppConfig params = EFFECTIVE.effectiveFor(resolved,
                new EffectiveParamsService.Context(imageMode, global.text(), global.image()));
        if (params == null) {
            return;   // 目标无生效参数（与 GUI 的入口守卫同义）→ 纯 no-op
        }
        // 撤销快照（拍板 2026-09-22）：应用前的全局参数 + 全部独立参数，撤销可完整回退
        AppConfig globalBefore = imageMode ? global.image() : global.text();
        Map<String, AppConfig> itemBefore = new HashMap<>();
        for (PreviewItem it : context.allItems()) {
            itemBefore.put(it.path, imageMode ? it.imageItemParams : it.textItemParams);
        }
        Map<Integer, AppConfig> groupBefore = new HashMap<>();
        for (Object o : context.dataOrder()) {
            if (o instanceof GroupItem<?> g) {
                groupBefore.put(g.getGroupNo(),
                        imageMode ? g.getImageParamsCache() : g.getTextParamsCache());
            }
        }
        if (imageMode) {
            global.set(true, params);
        } else {
            global.set(false, params);
        }
        for (PreviewItem it : context.allItems()) {
            if (imageMode) {
                it.imageItemParams = null;
            } else {
                it.textItemParams = null;
            }
        }
        for (Object o : context.dataOrder()) {
            if (o instanceof GroupItem<?> g) {
                if (imageMode) {
                    g.setImageParamsCache(null);
                } else {
                    g.setTextParamsCache(null);
                }
            }
        }
        context.pushUndo(UndoRecords.applyGlobal(imageMode, globalBefore, itemBefore, groupBefore));
        List<PreviewItem> all = context.allItems();
        // 规则 (a)：全局参数写入 + 全部独立参数清空 → 全部条目生效参数变化 → 删除全部缓存目录
        context.cacheService().paramsChanged(imageMode, all);
        emit(new PortEvent.ParamStateChanged(ParamChangeKind.APPLY_GLOBAL, imageMode, pathsOf(all)));
    }

    /** 清除参数本体（原 {@code ParamDomainController.clearParamsForTargets} 逐字搬入，含日志键与实参顺序）。 */
    private void clearParamsForTargets(List<Object> rawTargets, boolean imageMode) {
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
                GroupItem<PreviewItem> grp = WorkspaceStructure.asPreviewGroup(g);
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
        context.pushUndo(UndoRecords.clearParams(imageMode, itemBefore, groupBefore));
        // 受影响条目：清除条目参数的项目 + 清除组参数的各组全部有效成员
        List<PreviewItem> affected = new ArrayList<>(topItems);
        for (GroupItem<PreviewItem> grp : groups) {
            affected.addAll(grp.getMembers().stream().filter(m -> !m.removed).toList());
        }
        // 规则 (a)：清除参数也是参数变化（生效参数回落组/全局）→ 受影响条目缓存目录删除
        context.cacheService().paramsChanged(imageMode, affected);
        log.info(I18n.msg("log.maincontroller.28", topItems.size(), groups.size(), modeWord(imageMode)));
        emit(new PortEvent.ParamStateChanged(ParamChangeKind.CLEAR, imageMode, pathsOf(affected)));
    }

    // ==================== 内部工具 ====================

    /** 生效参数解析（与 GUI {@code effectiveForObject} 同源的唯一实现 {@link EffectiveParamsService}，无状态可共享）。 */
    private static final EffectiveParamsService EFFECTIVE = new EffectiveParamsService();

    private PreviewItem findByPath(String path) {
        if (path == null) {
            return null;
        }
        PreviewItem hidden=null;
        for (PreviewItem it : context.allItems()) {
            if (path.equals(it.path)) {
                if(!it.removed)return it;
                if(hidden==null)hidden=it;
            }
        }
        return hidden;
    }

    private GroupItem<PreviewItem> groupByNo(long groupNo) {
        for (Object o : context.dataOrder()) {
            if (o instanceof GroupItem<?> raw) {
                GroupItem<PreviewItem> g = WorkspaceStructure.asPreviewGroup(raw);
                if (g.getGroupNo() == groupNo) {
                    return g;
                }
            }
        }
        return null;
    }

    /** {@link TargetId} → 工作区对象（条目按路径、组按组号）；不可解析返回 {@code null}。 */
    private Object resolveTarget(TargetId id) {
        if (id == null) {
            return null;
        }
        if (id.path() != null) {
            return findByPath(id.path());
        }
        return groupByNo(id.groupNo());
    }

    /** 回退目标列表 → 第一个可解析的单个目标（GUI 的回退恒为单个右键命中 / 当前选中，可为 null）。 */
    private Object resolveFallback(List<TargetId> targets) {
        if (targets == null) {
            return null;
        }
        for (TargetId id : targets) {
            Object o = resolveTarget(id);
            if (o != null) {
                return o;
            }
        }
        return null;
    }

    private static List<String> pathsOf(List<PreviewItem> items) {
        List<String> out = new ArrayList<>();
        for (PreviewItem it : items) {
            if (it != null && it.path != null) {
                out.add(it.path);
            }
        }
        return List.copyOf(out);
    }

    /** 日志文案里的模式词（图片/文本 → Image/Text），供 log.* 模板的 {0} 占位符使用 */
    private static String modeWord(boolean imageMode) {
        return I18n.get(imageMode ? "mode.image" : "mode.text");
    }
}
