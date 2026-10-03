package com.mhtextractor.gui.controller;

import com.mhtextractor.app.WorkspaceModel;
import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.processor.BatchProcessor;
import com.mhtextractor.util.DropResolver;
import com.mhtextractor.util.GroupItem;
import com.mhtextractor.util.MhtImportPlanner;
import com.mhtextractor.util.OutputUnitPlanner;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GUI 自动化的驱动面（C3b-1：反射桥退场第一步）。
 *
 * <h2>它是什么</h2>
 * <p>P7 之后测试访问 {@code MainController} 内部状态的通道是
 * {@code com.mhtextractor.gui.acceptance.MainViewTestBridge} —— 住在 {@code acceptance} 包（与
 * {@code MainController} <b>不同包</b>），因此 40 个访问点<b>全部</b>走反射。本类把它迁到
 * {@code gui.controller} 包（<b>与 {@code MainController} 同包</b>，测试源集），并保留
 * {@code View} 形态与<b>完全相同的公开方法名/签名</b>，使 {@code FeatureExercise} 的调用点零逻辑改动。
 * 迁移后按三类落地：</p>
 * <ul>
 *   <li><b>A —— 同包 package-private 生产 API 直接访问</b>（零生产改动）：{@code ws} / {@code refs} /
 *       {@code ITEM_VIEW} / {@code previewCacheLifecycle} / {@code listInteraction} /
 *       {@code effectiveForObject} / {@code updateListButtons} / {@code saveGuiState} /
 *       {@code ctxClearParams}，以及 {@code listInteraction.findCell} / {@code performDrop}。</li>
 *   <li><b>B —— 由模型可读投影派生</b>（零生产改动）：{@code allPreviewItems} / {@code allVisibleItems} /
 *       {@code topLevelGroups} / {@code visibleMembersOf} / {@code visibleCount} 全部委托
 *       {@link WorkspaceModel}（与 {@code MainController} 私有实现逐条一致 —— 它本身就是委托模型）；
 *       {@code isImporting} 由开始按钮的唯一禁用公式反推（见该方法 javadoc 的等价性与已知偏差）。</li>
 *   <li><b>C / D —— 残余反射壳</b>（本步不做，逐点理由见交付物残差清单）：可观察 UI 动作替代
 *       （导入/参数/移除/刷新类）与内部状态探针（{@code textParamsCache} / {@code parseToken} /
 *       {@code running} / 私有记录 {@code ImportSession} 构造等）仍是 {@code MainController} 的
 *       {@code private} 成员，不动生产源码的前提下没有等价入口。</li>
 * </ul>
 *
 * <h2>纪律（谁改这里谁负责）</h2>
 * <ul>
 *   <li>本类<b>只允许被测试代码</b>引用；住在 {@code src/test/java}，不进生产 jar、不进 native 镜像。</li>
 *   <li>残余反射名（字段/方法名）与 {@code MainController} 源码是<b>隐式契约</b>：重命名字段不会编译报错，
 *       只会在下次 GUI 自动化时抛 {@link NoSuchFieldException}。残余清单只减不增
 *       （C3b 后续步骤逐类消掉）。</li>
 *   <li>数据序只读：{@link #dataOrder()} 返回模型的只读投影，清场一律经 {@link WorkspaceModel} 命令
 *       （{@code clearItems} / {@code clearUndoStack}），不得直写。</li>
 * </ul>
 */
public final class AcceptanceDriver {

    private AcceptanceDriver() {
    }

    /** 主控制器实例（与生产同一个类；本类不构造它）。 */
    public static final class View {

        private final MainController mc;

        public View(MainController mc) {
            this.mc = mc;
        }

        /** 被测控制器本体（需要传给仍接受 {@code MainController} 的生产协作类时用）。 */
        public MainController controller() {
            return mc;
        }

        // ==================== A：同包 package-private 生产 API，直接访问（零生产改动） ====================

        /** 工作区模型（= 生产字段 {@code MainController.ws}，包级）。 */
        public WorkspaceModel model() {
            return mc.ws;
        }

        /** 数据序只读投影（= 原 {@code mc.items}）。 */
        public List<Object> dataOrder() {
            return mc.ws.topLevelView();
        }

        /** 数据序是否为空（= 原 {@code mc.items.isEmpty()}）。 */
        public boolean dataOrderIsEmpty() {
            return dataOrder().isEmpty();
        }

        /** 清场（= 原 {@code mc.items.clear()}）：转发为模型命令，语义与数据序投影的 clear() 一致。 */
        public void clearDataOrder() {
            mc.ws.clearItems();
        }

        /** 清撤销栈（= 原 {@code mc.ws.clearUndoStack()}）。 */
        public void clearUndoStack() {
            mc.ws.clearUndoStack();
        }

        /** FXML 节点引用集合（生产字段 {@code MainController.refs}，包级）。 */
        public MainViewRefs refs() {
            return mc.refs;
        }

        /** 列表控件本体（{@code fx:id=fileListView}）。 */
        public ListView<Object> fileListView() {
            return mc.refs.fileListView();
        }

        /** 章节勾选框（{@code fx:id=adjustCheck}）。 */
        public CheckBox adjustCheck() {
            return mc.refs.adjustCheck();
        }

        /** 「查看预览效果」按钮（{@code fx:id=previewEffectButton}）。 */
        public Button previewEffectButton() {
            return mc.refs.previewEffectButton();
        }

        /** 数据序规划视图（生产静态字段 {@code MainController.ITEM_VIEW}，包级）。 */
        public OutputUnitPlanner.ItemView<PreviewItem> itemView() {
            return MainController.ITEM_VIEW;
        }

        /** 当前模式下某条目的生效参数（生产方法 {@code MainController.effectiveForObject}，包级）。 */
        public AppConfig effectiveForObject(Object o) {
            return mc.effectiveForObject(o);
        }

        /** 列表操作按钮可用性刷新（生产方法，包级）。 */
        public void updateListButtons() {
            mc.updateListButtons();
        }

        /** GUI 状态回写（生产方法，包级）。 */
        public void saveGuiState() {
            mc.saveGuiState();
        }

        /** 右键菜单"清除参数"项（生产字段 {@code MainController.ctxClearParams}，包级）。 */
        public MenuItem ctxClearParams() {
            return mc.ctxClearParams;
        }

        /** 预览缓存生命周期（生产字段 {@code MainController.previewCacheLifecycle}，包级）。 */
        public PreviewCacheLifecycle previewCacheLifecycle() {
            return mc.previewCacheLifecycle;
        }

        /** 双击 / 单元格交互用：返回条目对应的 {@link PreviewListCell}（包级类型，同包直接引用，无需按名反射）。 */
        public Node findCellNode(Object item) {
            return mc.listInteraction.findCell(item);
        }

        /** 拖拽（程序化驱动，与真实 DnD 同一实现）：第 4 个参数（真实 DnD 的发起单元格）在程序化演练路径恒为 null。 */
        public boolean performDrop(Object dragged, Object target, DropResolver.Zone zone) {
            return mc.listInteraction.performDrop(dragged, target, zone, null);
        }

        // ==================== B：由模型可读投影派生（与 MainController 私有实现逐条一致） ====================
        // MainController 的 allPreviewItems / allVisibleItems / topLevelGroups / visibleMembersOf /
        // visibleCount 全部委托 WorkspaceModel（allItems / groups / visibleCount），此处同样只经 mc.ws 派生。
        // 不从 fileListView().getItems() 推导：折叠组的成员不在显示序中，会漏掉 ——
        // MainController 自身 javadoc 也写明"按数据序遍历，否则全选/提取名单/进度统计漏掉折叠组成员"。

        /** 数据模型中的所有项目（含组内成员与已移除项）。 */
        public List<PreviewItem> allPreviewItems() {
            return mc.ws.allItems();
        }

        /** 全部有效项目（removed == false，含组内成员），按数据序。 */
        public List<PreviewItem> allVisibleItems() {
            return mc.ws.allItems().stream().filter(i -> !i.removed).toList();
        }

        /** 顶层组列表（按数据序）。 */
        public List<GroupItem<PreviewItem>> topLevelGroups() {
            return mc.ws.groups();
        }

        /** 组内有效成员（removed == false）。 */
        public List<PreviewItem> visibleMembersOf(GroupItem<?> g) {
            List<PreviewItem> out = new ArrayList<>();
            for (Object m : g.getMembers()) {
                if (m instanceof PreviewItem p && !p.removed) {
                    out.add(p);
                }
            }
            return out;
        }

        /** 可见文件数：removed 标记的项目在列表中完全隐藏，不参与任何计数（组条目不计入）。 */
        public long visibleCount() {
            return mc.ws.visibleCount();
        }

        /**
         * 是否正在导入 —— <b>B 类派生尝试失败，回退反射壳</b>（C3b-1 实测，残差清单记录）。
         *
         * <p>曾按任务方案用开始按钮禁用公式反推（{@code disabled && anyMode && visibleCount>0 && !dirty}），
         * GUI 自动化 zh/en 双双 15/16 失败。根因（日志实证）：{@code waitForImportDone} 的语义是
         * "排空在途导入"—— 而<b>导入进行中且列表为空</b>（{@code visibleCount==0}）恰是自动化里的
         * <b>常态</b>（清场后第一次导入目录就是它）。此时按钮禁用公式读不到"导入中"，
         * {@code waitForImportDone} 不再泵 FX 事件 → 后台导入的落地回调永远排不上队 →
         * 后续每次导入都被生产代码以"已有导入进行中"跳过（日志：WARN log.maincontroller.03），
         * 直到某次泵事件把积压的导入落地（日志：导入完成：新增 8 个项目，落在测试中途）。
         * 精确可观察面只有 {@code MainController} 的私有字段 {@code importPending} ——
         * 不动生产源码的前提下无法等价派生，故本点保留反射。</p>
         */
        public boolean isImporting() {
            return call(mc, "isImporting");
        }

        // ==================== C / D：残余反射壳（C3b-1 本步不做；逐点理由见交付物残差清单） ====================
        // 以下成员在 MainController 中为 private，且不存在"不动生产源码"的等价入口：
        // C 类（可观察 UI 动作替代：导入/参数/移除/刷新类，须重走真实手势）与 D 类（内部状态探针：
        // 参数缓存/解析代次/提取状态/私有记录构造）。反射名与 MainController 源码仍是隐式契约（P7 收口口径）。

        public void importFromFolder(String dir) {
            call(mc, "importFromFolder", String.class, dir);
        }

        public void importByPaths(List<String> addresses, String sourceDesc, boolean fromDrag) {
            call(mc, "importByPaths", List.class, addresses, String.class, sourceDesc,
                    boolean.class, fromDrag);
        }

        public void applyImportPlan(MhtImportPlanner.Plan plan) {
            call(mc, "applyImportPlan", MhtImportPlanner.Plan.class, plan);
        }

        public void applyConfirmedParams(boolean imageMode, AppConfig params) {
            call(mc, "applyConfirmedParams", boolean.class, imageMode, AppConfig.class, params);
        }

        public void applyGroupParams(GroupItem<PreviewItem> group, boolean imageMode, AppConfig params) {
            call(mc, "applyGroupParams", GroupItem.class, group,
                    boolean.class, imageMode, AppConfig.class, params);
        }

        public void applyItemParams(PreviewItem item, boolean imageMode, AppConfig params) {
            call(mc, "applyItemParams", PreviewItem.class, item, boolean.class, imageMode,
                    AppConfig.class, params);
        }

        public AppConfig buildRuntimeConfig() {
            return call(mc, "buildRuntimeConfig");
        }

        public void removeItems(List<PreviewItem> targets) {
            call(mc, "removeItems", List.class, targets);
        }

        public void rebuildDisplay() {
            call(mc, "rebuildDisplay");
        }

        public void refreshAfterListChange() {
            call(mc, "refreshAfterListChange");
        }

        public void refreshAfterParamsChanged() {
            call(mc, "refreshAfterParamsChanged");
        }

        public void updateStartButtonState() {
            call(mc, "updateStartButtonState");
        }

        public void updateProgressStatus(int token) {
            call(mc, "updateProgressStatus", int.class, token);
        }

        public void updateContextMenuState(Object target) {
            call(mc, "updateContextMenuState", Object.class, target);
        }

        public void updateImportDropHighlight(boolean on) {
            call(mc, "updateImportDropHighlight", boolean.class, on);
        }

        public void clearImportDropHighlight() {
            call(mc, "clearImportDropHighlight");
        }

        public void cancelWorkspaceActivity() {
            call(mc, "cancelWorkspaceActivity");
        }

        /** 折叠/展开：{@code GroupCommandController.toggleGroupExpanded} 本身是包级方法（同包直接调用），
         *  但实例在 {@code MainController} 的 <b>私有</b>字段 {@code groupCommand} 里 —— 只反射读字段一次。 */
        public void toggleGroupExpanded(GroupItem<PreviewItem> g) {
            groupCommand().toggleGroupExpanded(g);
        }

        /** 折叠/展开动画收尾：同上，方法包级、实例在私有字段里。 */
        public void finishExpandAnim() {
            groupCommand().finishExpandAnim();
        }

        private GroupCommandController groupCommand() {
            return field(mc, "groupCommand");
        }

        // ---- 提取状态（协调器持有；生产类私有只读投影） ----

        public AtomicBoolean running() {
            return call(mc, "running");
        }

        public AtomicReference<BatchProcessor.ProcessStats> extractStats() {
            return call(mc, "extractStats");
        }

        // ---- 可变状态字段（P7 起已收回 private） ----

        public AppConfig textParamsCache() {
            return field(mc, "textParamsCache");
        }

        public void textParamsCache(AppConfig v) {
            setField(mc, "textParamsCache", v);
        }

        public AppConfig imageParamsCache() {
            return field(mc, "imageParamsCache");
        }

        public void imageParamsCache(AppConfig v) {
            setField(mc, "imageParamsCache", v);
        }

        public int parseToken() {
            return field(mc, "parseToken");
        }

        public boolean imageLoadActive() {
            return field(field(mc, "previewLoader"), "imageLoadActive");
        }

        public void imageLoadActive(boolean v) {
            setField(field(mc, "previewLoader"), "imageLoadActive", v);
        }

        public int importDragDepth() {
            return field(mc, "importDragDepth");
        }

        public void importDragDepth(int v) {
            setField(mc, "importDragDepth", v);
        }

        public void importPending(Object session) {
            setField(mc, "importPending", session);
        }

        /** {@code MainController.ImportSession} 的私有记录构造（{@code record} 是隐式 private static）。 */
        public Object newImportSession(List<String> addresses, String sourceDesc, boolean fromDrag) {
            try {
                Class<?> t = Class.forName("com.mhtextractor.gui.controller.MainController$ImportSession");
                Constructor<?> c = t.getDeclaredConstructor(List.class, String.class, boolean.class);
                c.setAccessible(true);
                return c.newInstance(addresses, sourceDesc, fromDrag);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("构造 MainController.ImportSession 失败", unwrap(ex));
            }
        }

        // ==================== 反射原语（仅残余 C/D 使用；随残余点消失而删除） ====================

        private static Field fieldOf(Object target, String name) {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            throw new IllegalStateException("AcceptanceDriver：MainController 里找不到字段 " + name
                    + "（P7 收口后字段名与反射名是隐式契约，改名必须同步改本类）");
        }

        @SuppressWarnings("unchecked")
        private static <T> T field(Object target, String name) {
            try {
                return (T) fieldOf(target, name).get(target);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("读字段失败：" + name, e);
            }
        }

        private static void setField(Object target, String name, Object value) {
            try {
                fieldOf(target, name).set(target, value);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("写字段失败：" + name, e);
            }
        }

        private static Method methodOf(Object target, String name, Class<?>[] types) {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    Method m = c.getDeclaredMethod(name, types);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException e) {
                    c = c.getSuperclass();
                }
            }
            throw new IllegalStateException("AcceptanceDriver：MainController 里找不到方法 " + name
                    + "（P7 收口后方法名与反射名是隐式契约，改名必须同步改本类）");
        }

        @SuppressWarnings("unchecked")
        private static <T> T call(Object target, String name, Object... argsWithTypes) {
            Class<?>[] types = new Class<?>[argsWithTypes.length / 2];
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                types[i] = (Class<?>) argsWithTypes[i * 2];
                args[i] = argsWithTypes[i * 2 + 1];
            }
            try {
                return (T) methodOf(target, name, types).invoke(target, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                if (cause instanceof Error err) {
                    throw err;
                }
                throw new IllegalStateException("调用 " + name + " 抛出受检异常", cause);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("调用方法失败：" + name, e);
            }
        }

        private static Throwable unwrap(Throwable t) {
            return t instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
        }
    }
}
