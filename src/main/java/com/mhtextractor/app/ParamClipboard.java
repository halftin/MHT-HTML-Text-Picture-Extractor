package com.mhtextractor.app;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.ParamScope;
import com.mhtextractor.util.UndoManager;

/**
 * 参数剪贴板（Q10 / R7.1–R7.7）：右键「复制参数」与「应用到所选」之间的<b>唯一</b>状态载体，
 * 并把一次批量应用收成<b>一条</b> {@code PARAM_SET} 撤销记录。
 *
 * <p><b>这里没有第二套应用逻辑</b>：真正的写入（含缓存失效与撤销压栈）仍是宿主既有的
 * {@code applyItemParams} / {@code applyGroupParams} / {@code applyConfirmedParams} —— 也就是参数配置
 * 界面点「确认」后的那条路径。本类只做三件事：</p>
 * <ol>
 *   <li><b>存快照</b>：只存<b>可持久化提取参数</b>的值快照（{@link AppConfig#copyExtractionParams()}）；
 *       4 个 {@code @JsonIgnore} 运行时/GUI 字段不参与复制、应用时也不覆盖（R7.7）；</li>
 *   <li><b>逐目标复用同一份应用函数</b>：作用域路由与应用本体都由宿主注入（{@link Function} +
 *       {@link ApplyFn}），与本类无关 —— 故"应用到所选"与"点确认"走的是同一份实现（R7.4）；</li>
 *   <li><b>把撤销压栈收成一条</b>：被复用函数本是"每个目标压一条"，批量期间其压栈经
 *       {@link #pushParamUndo} 只累积各目标的 <i>before</i> 快照，整批结束压一条（R7.5）；
 *       单目标应用（点确认 / 右键目标只有一个）粒度不变，仍是一条。</li>
 * </ol>
 *
 * <p><b>纯数据 + 纯编排、零 JavaFX、零模型写入</b>：写入全在宿主，故本类可无头单测。</p>
 */
public final class ParamClipboard {

    /** 应用函数签名：宿主既有的"确认路径应用本体"（{@code applyTargetParams}） */
    @FunctionalInterface
    public interface ApplyFn {
        void apply(ParamScope.Target target, boolean imageMode, AppConfig params);
    }

    /** 作用域路由（选择对象 → 参数作用域目标）：与"设置参数"按钮同一份实现 */
    private final Function<Object, ParamScope.Target> scopeOf;

    /** 应用本体（复用确认路径） */
    private final ApplyFn apply;

    /** 撤销压栈出口（宿主的 {@code WorkspaceModel::push}） */
    private final Consumer<UndoManager.Record<PreviewItem>> undoPush;

    /** 已复制的提取参数快照；{@code null} = 尚未复制过（R7.3） */
    private AppConfig params;

    /** 批量应用标记：非 {@code null} 时处于一次批量应用中（值为该次的模式位） */
    private Boolean batchImageMode;

    /** 批量期间累积的条目级 before 快照（path → 参数） */
    private Map<String, AppConfig> batchItemBefore;

    /** 批量期间累积的组级 before 快照（groupNo → 参数） */
    private Map<Integer, AppConfig> batchGroupBefore;

    public ParamClipboard(Consumer<UndoManager.Record<PreviewItem>> undoPush,
                          Function<Object, ParamScope.Target> scopeOf, ApplyFn apply) {
        this.undoPush = undoPush;
        this.scopeOf = scopeOf;
        this.apply = apply;
    }

    // ==================== 剪贴板状态（R7.3/R7.7） ====================

    /** 是否尚未复制过（「应用到所选」的前置；清空后回到禁用） */
    public boolean isEmpty() {
        return params == null;
    }

    /** 复制一份值快照（源为 {@code null} 视为"无参数、不复制"—— 该情形由 R7.2 的菜单项禁用把关） */
    public void copy(AppConfig source) {
        params = source == null ? null : source.copyExtractionParams();
    }

    /** 取出一份<b>新的</b>快照副本（未复制过 → {@code null}）：每个目标拿到独立实例，避免就地修改串味 */
    public AppConfig snapshot() {
        return params == null ? null : params.copyExtractionParams();
    }

    /** 清空剪贴板（清空后 {@link #isEmpty()} 回到 true） */
    public void clear() {
        params = null;
    }

    /**
     * 复制/应用两项的<b>参数判定快照</b>（R7.2/R7.3/R7.6）：目标集合"是否有生效参数 / 是否统一"的判定
     * 一律经 {@link EffectiveParamsService}（唯一实现，禁止各写一套），本类只做布尔组合；
     * {@code copied} 位就是本剪贴板是否非空（{@link #isEmpty()}）。
     *
     * @param targets     本次"所选"集合（宿主按勾选优先 → 右键目标解析）
     * @param independent 目标是否有独立参数（宿主的既有判定）
     */
    public ParamsInfo paramsInfo(List<Object> targets, boolean independent,
                                 EffectiveParamsService service,
                                 EffectiveParamsService.Context ctx,
                                 int topLevelCount, int distinctGroupCount) {
        return new ParamsInfo(independent,
                service.anyHasEffectiveParams(targets, ctx), service.isUniform(targets, ctx), !isEmpty(),
                topLevelCount, distinctGroupCount);
    }

    // ==================== 批量应用与"整批一条撤销记录"（R7.4/R7.5） ====================

    /**
     * 把剪贴板里的快照应用到全部目标：逐目标经 {@link #scopeOf} 解析作用域、经 {@link #apply} 走确认路径；
     * 整批只压<b>一条</b> {@code PARAM_SET}（各目标 before 快照按 path / groupNo 收进同一记录的载荷）。
     */
    public void applyToSelection(List<Object> targets, boolean imageMode) {
        if (isEmpty() || targets == null || targets.isEmpty()) {
            return;
        }
        batchImageMode = imageMode;
        batchItemBefore = new HashMap<>();
        batchGroupBefore = new HashMap<>();
        try {
            for (Object target : targets) {
                apply.apply(scopeOf.apply(target), imageMode, snapshot());
            }
        } finally {
            batchImageMode = null;
        }
        if (!batchItemBefore.isEmpty() || !batchGroupBefore.isEmpty()) {
            undoPush.accept(recordOf(imageMode, null, null, null, batchItemBefore, batchGroupBefore));
        }
        batchItemBefore = null;
        batchGroupBefore = null;
    }

    /**
     * {@code PARAM_SET} 撤销压栈的<b>唯一出口</b>（宿主三处应用函数都经这里，R7.5）。
     *
     * <p>批量应用期间只把该目标的 <i>before</i> 快照累积进本次批量记录，不单独压栈；其余情形
     * （单目标应用、以及批量期间意外出现的全局作用域）照旧立即压一条 —— 后者在菜单驱动下不可达
     * （所选集合只含条目/组），保留该分支是为了"宁可多一条、也不静默丢一条撤销记录"。</p>
     */
    public void pushParamUndo(boolean imageMode, String path, Integer groupNo, AppConfig before) {
        if (batchImageMode != null && (path != null || groupNo != null)) {
            if (path != null) {
                batchItemBefore.put(path, before);
            } else {
                batchGroupBefore.put(groupNo, before);
            }
            return;
        }
        undoPush.accept(recordOf(imageMode, path, groupNo, before, null, null));
    }

    /**
     * 一条 {@code PARAM_SET} 记录：单目标应用把 {@code path}/{@code groupNo}/{@code before} 三个字段
     * 用作载荷；批量应用把 {@code itemBefore}/{@code groupBefore} 两个映射用作载荷（撤销侧按映射是否
     * 为空区分，见 {@code MainController.onUndoClicked} 的 {@code PARAM_SET} 分支）。
     */
    private static UndoManager.Record<PreviewItem> recordOf(boolean imageMode, String path, Integer groupNo,
                                                            AppConfig before, Map<String, AppConfig> itemBefore,
                                                            Map<Integer, AppConfig> groupBefore) {
        return new UndoManager.Record<>(UndoManager.RecordType.PARAM_SET, List.of(),
                null, -1, List.of(), List.of(), List.of(), List.of(),
                false, null, null, null, imageMode, path, groupNo, before,
                false, itemBefore, groupBefore);
    }
}
