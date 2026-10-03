package com.mhtextractor.gui.acceptance;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.gui.controller.MainController;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.DropResolver;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

/**
 * {@code MainController} 的<b>只读/驱动反射桥</b>（P7 测试面收口，<b>测试源集</b>）。
 *
 * <h2>它为什么存在</h2>
 * <p>P7 的目标是"生产控制器不再为测试开放状态面"：{@code MainController} 里那 44 处
 * {@code /* test-support *&#47;} 标记（= 为同包测试夹具从 {@code private} 放宽到包级的字段与方法）
 * 必须归零。可选做法有两种：</p>
 * <ol>
 *   <li>把它们改成 {@code public} 并删标记 —— 标记归零，但状态面<b>更宽</b>了，与目标背道而驰；</li>
 *   <li><b>改回 {@code private}</b>，测试侧用反射访问 —— 生产类的封装真正收回，测试仍能驱动同一批行为。</li>
 * </ol>
 * <p>本类实现第 2 种。它是<b>测试资产</b>（住在 {@code src/test/java}，不进生产 jar、不进 native 镜像），
 * 所以"反射"的代价只落在测试侧；生产线上一行都不改。</p>
 *
 * <h2>纪律（谁改这里谁负责）</h2>
 * <ul>
 *   <li>本类<b>只允许被测试代码</b>引用。生产源码引用它会导致主源集编译失败（test 源集对 main 不可见）——
 *       这正好是想要的硬约束。</li>
 *   <li>反射名（字段/方法名）与 {@code MainController} 源码是<b>隐式契约</b>：重命名字段不会编译报错，
 *       只会在下次 GUI 自动化时抛 {@link NoSuchFieldException}。因此本类只允许访问
 *       P7 收口清单里的成员，<b>不得</b>成为"随便读生产私有状态"的万能入口。</li>
 *   <li>真正需要长期存在的生产行为（导入 / 应用参数 / 移除 / 建组…）应做成 {@code MainController}
 *       的具名方法，而不是从这里伸进去。本类只承载"P7 之前靠包级可见性拿到"的那批。</li>
 * </ul>
 */
public final class MainViewTestBridge {

    private MainViewTestBridge() {
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

        // ==================== 数据序 ====================
        // 注：这些<b>不是</b>反射 —— WorkspaceModel 的 API 本来就是 public（模型是业务数据的唯一所有者），
        // 本类只是把"FeatureExercise 直接摸 mc.items / mc.ws"改成"经模型的公开 API"。
        // 模型引用本身取自 MainController 的私有字段（P7 起 private），故仍需一次反射。

        /** 工作区模型（= 原 {@code mc.ws}）。 */
        public com.mhtextractor.app.WorkspaceModel model() {
            return field(mc, "ws");
        }

        /** 数据序只读投影（= 原 {@code mc.items}）。 */
        public java.util.List<Object> dataOrder() {
            return model().topLevelView();
        }

        /** 数据序是否为空（= 原 {@code mc.items.isEmpty()}）。 */
        public boolean dataOrderIsEmpty() {
            return dataOrder().isEmpty();
        }

        /** 清场（= 原 {@code mc.items.clear()}）：转发为模型命令，语义与数据序投影的 clear() 一致。 */
        public void clearDataOrder() {
            model().clearItems();
        }

        /** 清撤销栈（= 原 {@code mc.ws.clearUndoStack()}）。 */
        public void clearUndoStack() {
            model().clearUndoStack();
        }

        // ==================== FXML 节点（P6-B 起经 MainViewRefs，不再是 @FXML 字段） ====================
        // P6-B 之前这里反射读 42 个 @FXML 控件字段；现在控制器只持有 refs（MainViewRefs），
        // 控件引用一律经它取 —— 反射名从"控件字段名"变成"refs 字段名"，契约同步收窄到一处。

        /** FXML 节点引用集合（{@code MainController.refs}，P6-B 起取代 42 个 {@code @FXML} 字段）。 */
        public com.mhtextractor.gui.controller.MainViewRefs refs() {
            return field(mc, "refs");
        }

        /** 列表控件本体（{@code fx:id=fileListView}）。 */
        public javafx.scene.control.ListView<Object> fileListView() {
            return refs().fileListView();
        }

        /** 章节勾选框（{@code fx:id=adjustCheck}）。 */
        public javafx.scene.control.CheckBox adjustCheck() {
            return refs().adjustCheck();
        }

        /** 「查看预览效果」按钮（{@code fx:id=previewEffectButton}）。 */
        public javafx.scene.control.Button previewEffectButton() {
            return refs().previewEffectButton();
        }

        // ==================== 标记成员的反射访问 ====================

        /** 数据序规划视图（原 {@code MainController.ITEM_VIEW}，P7 起收回 private static）。 */
        public com.mhtextractor.util.OutputUnitPlanner.ItemView<PreviewItem> itemView() {
            return fieldStatic("ITEM_VIEW");
        }

        public java.util.List<PreviewItem> allVisibleItems() {
            return call(mc, "allVisibleItems");
        }

        public java.util.List<PreviewItem> allPreviewItems() {
            return call(mc, "allPreviewItems");
        }

        public java.util.List<com.mhtextractor.util.GroupItem<PreviewItem>> topLevelGroups() {
            return call(mc, "topLevelGroups");
        }

        public java.util.List<PreviewItem> visibleMembersOf(com.mhtextractor.util.GroupItem<?> g) {
            return call(mc, "visibleMembersOf", com.mhtextractor.util.GroupItem.class, g);
        }

        public long visibleCount() {
            return call(mc, "visibleCount");
        }

        public boolean isImporting() {
            return call(mc, "isImporting");
        }

        public void importFromFolder(String dir) {
            call(mc, "importFromFolder", String.class, dir);
        }

        public void importByPaths(java.util.List<String> addresses, String sourceDesc, boolean fromDrag) {
            call(mc, "importByPaths", java.util.List.class, addresses, String.class, sourceDesc,
                    boolean.class, fromDrag);
        }

        public void applyImportPlan(com.mhtextractor.util.MhtImportPlanner.Plan plan) {
            call(mc, "applyImportPlan", com.mhtextractor.util.MhtImportPlanner.Plan.class, plan);
        }

        public void applyConfirmedParams(boolean imageMode, AppConfig params) {
            call(mc, "applyConfirmedParams", boolean.class, imageMode, AppConfig.class, params);
        }

        public void applyGroupParams(com.mhtextractor.util.GroupItem<PreviewItem> group, boolean imageMode,
                                     AppConfig params) {
            call(mc, "applyGroupParams", com.mhtextractor.util.GroupItem.class, group,
                    boolean.class, imageMode, AppConfig.class, params);
        }

        public void applyItemParams(PreviewItem item, boolean imageMode, AppConfig params) {
            call(mc, "applyItemParams", PreviewItem.class, item, boolean.class, imageMode,
                    AppConfig.class, params);
        }

        public AppConfig effectiveForObject(Object o) {
            return call(mc, "effectiveForObject", Object.class, o);
        }

        public AppConfig buildRuntimeConfig() {
            return call(mc, "buildRuntimeConfig");
        }

        public void removeItems(java.util.List<PreviewItem> targets) {
            call(mc, "removeItems", java.util.List.class, targets);
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

        public void updateListButtons() {
            call(mc, "updateListButtons");
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

        public void saveGuiState() {
            call(mc, "saveGuiState");
        }

        public void toggleGroupExpanded(com.mhtextractor.util.GroupItem<PreviewItem> g) {
            call(groupCommand(), "toggleGroupExpanded", com.mhtextractor.util.GroupItem.class, g);
        }

        public void finishExpandAnim() {
            call(groupCommand(), "finishExpandAnim");
        }

        /** C2：列表交互协作者（findCell / performDrop 已从 MainController 外迁到 ListInteractionController）。 */
        private Object listInteraction() {
            return field(mc, "listInteraction");
        }

        /** C3a：分组命令协作者（onGroupClicked / onUngroupClicked / toggleGroupExpanded / finishExpandAnim 已外迁）。 */
        private Object groupCommand() {
            return field(mc, "groupCommand");
        }

        /** 双击 / 单元格交互用：返回 {@code javafx.scene.Node}（{@code PreviewListCell} 非 public）。 */
        public javafx.scene.Node findCellNode(Object item) {
            Object cell = call(listInteraction(), "findCell", Object.class, item);
            if (cell == null) {
                return null;
            }
            if (!(cell instanceof javafx.scene.Node n)) {
                throw new IllegalStateException("findCell 返回的不是 Node：" + cell.getClass().getName());
            }
            return n;
        }

        public boolean performDrop(Object dragged, Object target, DropResolver.Zone zone) {
            // 第 4 个参数（真实 DnD 的发起单元格）在程序化演练路径恒为 null；其类型
            // PreviewListCell 是包级类，测试源集取不到 Class 字面量，故按名字反射取。
            Class<?> cellType;
            try {
                cellType = Class.forName("com.mhtextractor.gui.controller.PreviewListCell");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("找不到 PreviewListCell（P7 收口后按名字反射）", e);
            }
            return call(listInteraction(), "performDrop", Object.class, dragged, Object.class, target,
                    DropResolver.Zone.class, zone, cellType, null);
        }

        // ---- 提取状态（协调器持有；生产类只读投影） ----

        public java.util.concurrent.atomic.AtomicBoolean running() {
            return call(mc, "running");
        }

        public java.util.concurrent.atomic.AtomicReference<com.mhtextractor.processor.BatchProcessor.ProcessStats>
                extractStats() {
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
        public Object newImportSession(java.util.List<String> addresses, String sourceDesc, boolean fromDrag) {
            try {
                Class<?> t = Class.forName("com.mhtextractor.gui.controller.MainController$ImportSession");
                java.lang.reflect.Constructor<?> c = t.getDeclaredConstructor(
                        java.util.List.class, String.class, boolean.class);
                c.setAccessible(true);
                return c.newInstance(addresses, sourceDesc, fromDrag);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("构造 MainController.ImportSession 失败", unwrap(ex));
            }
        }

        /** 右键菜单"清除参数"项（原 {@code mc.ctxClearParams}，P7 起收回 private）。 */
        public javafx.scene.control.MenuItem ctxClearParams() {
            return field(mc, "ctxClearParams");
        }

        public com.mhtextractor.cache.PreviewCacheLifecycle previewCacheLifecycle() {
            return field(mc, "previewCacheLifecycle");
        }

        // ==================== 反射原语 ====================

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
            throw new IllegalStateException("MainViewTestBridge：MainController 里找不到字段 " + name
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

        @SuppressWarnings("unchecked")
        private static <T> T fieldGetter(Object target, String name) {
            return (T) field(target, name);
        }

        /** 读 {@code MainController} 的私有静态字段（无需实例）。 */
        @SuppressWarnings("unchecked")
        private static <T> T fieldStatic(String name) {
            Class<?> c = MainController.class;
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return (T) f.get(null);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("读静态字段失败：" + name, e);
                }
            }
            throw new IllegalStateException("MainViewTestBridge：MainController 里找不到静态字段 " + name);
        }

        private static java.lang.reflect.Method methodOf(Object target, String name, Class<?>[] types) {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Method m = c.getDeclaredMethod(name, types);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException e) {
                    c = c.getSuperclass();
                }
            }
            throw new IllegalStateException("MainViewTestBridge：MainController 里找不到方法 " + name
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
