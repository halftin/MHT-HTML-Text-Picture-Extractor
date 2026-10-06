package com.mhtextractor.gui.controller;

import com.mhtextractor.app.ExtractionStats;
import com.mhtextractor.app.MhtImportPlanner;
import com.mhtextractor.app.WorkspaceModel;
import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.app.DropResolver;
import com.mhtextractor.util.OutputUnitPlanner;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GUI 自动化的驱动面（C3b-2：<b>反射归零</b>）。
 *
 * <h2>它是什么</h2>
 * <p>P7 之后测试访问 {@code MainController} 内部状态的通道是
 * {@code com.mhtextractor.gui.acceptance.MainViewTestBridge} —— 住在 {@code acceptance} 包（与
 * {@code MainController} <b>不同包</b>），因此 40 个访问点<b>全部</b>走反射。C3b-1 把它迁到
 * {@code gui.controller} 包（<b>与 {@code MainController} 同包</b>，测试源集），保留
 * {@code View} 形态与<b>完全相同的公开方法名/签名</b>，使 {@code FeatureExercise} 的调用点零逻辑改动。
 * C3b-2（本步）按 DSH 裁决把残余反射<b>全部归零</b>，落地为三层：</p>
 * <ul>
 *   <li><b>A —— 同包 package-private 生产 API 直接访问</b>：{@code ws} / {@code refs} /
 *       {@code ITEM_VIEW} / {@code previewCacheLifecycle} / {@code listInteraction} /
 *       {@code effectiveForObject} / {@code updateListButtons} / {@code saveGuiState} /
 *       {@code ctxClearParams}，以及 {@code listInteraction.findCell} / {@code performDrop}。</li>
 *   <li><b>B —— 由模型可读投影派生</b>：{@code allPreviewItems} / {@code allVisibleItems} /
 *       {@code topLevelGroups} / {@code visibleMembersOf} / {@code visibleCount} 全部委托
 *       {@link WorkspaceModel}（与 {@code MainController} 私有实现逐条一致 —— 它本身就是委托模型）。</li>
 *   <li><b>C / D —— 真实手势或"仅可见性"包级暴露</b>（DSH 裁决授权：生产侧只把
 *       {@code private} 放宽为包级，不改行为、不加 {@code test-support} 标记、不新增成员）：
 *       <ul>
 *         <li><b>真实手势</b>（便宜且稳）：{@code importFromFolder} 走"写输入目录框 +
 *              生产监听器 {@code onInputFolderChanged}"；{@code removeItems} 走"勾选目标 +
 *              点移除按钮"（{@code onRemoveClicked → removeSelected → removeItems} 全生产路径）。</li>
 *         <li><b>包级暴露</b>（重走手势脆弱或无手势可走）：导入/参数/刷新类方法与内部状态探针
 *              —— 逐点理由见 C3b-2 交付物的可见性改动表。</li>
 *       </ul></li>
 * </ul>
 *
 * <h2>纪律（谁改这里谁负责）</h2>
 * <ul>
 *   <li>本类<b>只允许被测试代码</b>引用；住在 {@code src/test/java}，不进生产 jar、不进 native 镜像。</li>
 *   <li>包级成员名与 {@code MainController} 源码是<b>编译期契约</b>（不再是反射的隐式契约）：
 *       改名/收紧可见性会在下次编译时直接报错，不再等到 GUI 自动化抛异常。</li>
 *   <li>数据序只读：{@link #dataOrder()} 返回模型的只读投影，清场一律经 {@link WorkspaceModel} 命令
 *       （{@code clearItems} / {@code clearUndoStack}），不得直写。</li>
 *   <li>移除走按钮手势时，本驱动先清全部勾选再勾目标，保证按钮路径的移除目标恰为入参集合
 *       （按钮路径的移除目标 = 全部勾选项，直写 {@code selected} 不是写数据序）。</li>
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

        /** 预览缓存生命周期（生产字段 {@code MainController.previewCacheLifecycle}，包级；P3-3b-16 起字段类型为门面，经 {@code lifecycle()} 取出底层）。 */
        public PreviewCacheLifecycle previewCacheLifecycle() {
            return mc.previewCacheLifecycle.lifecycle();
        }

        /** 双击 / 单元格交互用：返回条目对应的 {@link PreviewListCell}（包级类型，同包直接引用）。 */
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
         * 是否正在导入（生产方法 {@code MainController.isImporting}，包级 —— DSH 裁决授权 a）。
         *
         * <p>C3b-1 曾按任务方案用开始按钮禁用公式反推（{@code disabled && anyMode && visibleCount>0 && !dirty}），
         * GUI 自动化 zh/en 双双 15/16 失败。根因（日志实证）：{@code waitForImportDone} 的语义是
         * "排空在途导入"—— 而<b>导入进行中且列表为空</b>（{@code visibleCount==0}）恰是自动化里的
         * <b>常态</b>（清场后第一次导入目录就是它）。此时按钮禁用公式读不到"导入中"，
         * {@code waitForImportDone} 不再泵 FX 事件 → 后台导入的落地回调永远排不上队。
         * 精确可观察面只有 {@code MainController} 的字段 {@code importPending}，故经 DSH 裁决
         * 把只读查询 {@code isImporting()} 放宽为包级（仅可见性，零行为改动）。</p>
         */
        public boolean isImporting() {
            return mc.isImporting();
        }

        // ==================== C：真实手势 / 包级直接访问（C3b-2：反射归零） ====================
        // 选择口径（DSH 裁决）："重走真实手势便宜且稳"的走手势（importFromFolder / removeItems）；
        // 其余是纯内部刷新或手势脆弱（构造 DnD 事件、驱动侧边栏表单、等待异步链），
        // 按裁决把生产侧 private 放宽为包级 —— 仅可见性关键字，行为零改动，不加 test-support 标记。

        /**
         * 增量导入（<b>真实手势</b>）：把目录写进输入目录框 —— {@code MainViewInstaller:131} 的
         * textProperty 监听器会触发 {@code onInputFolderChanged} → 统一导入，与用户路径一致。
         * 同值 setText 不触发监听器时，显式补一次生产处理器（此时若导入已在途，
         * {@code onImportStarted} 是同步置位的，isImporting 守卫会让补调用安全 no-op）。
         */
        public void importFromFolder(String dir) {
            mc.refs.inputFolderField().setText(dir);
            mc.onInputFolderChanged();
        }

        /** 统一导入入口（包级；真实手势 = 构造带真实文件的 Dragboard 再发 DnD 事件，脆弱不可靠，
         *  且调用点需要自定义 sourceDesc 文案）。 */
        public void importByPaths(List<String> addresses, String sourceDesc, boolean fromDrag) {
            mc.importByPaths(addresses, sourceDesc, fromDrag);
        }

        /** 应用导入计划（包级；纯内部轮次派发 —— 真实手势 = 整条异步导入链，
         *  且该测试段先用离线 Scanner 出 plan 再应用，手势不可复现）。 */
        public void applyImportPlan(MhtImportPlanner.Plan plan) {
            mc.applyImportPlan(plan);
        }

        /** 写入某模式的已确认参数缓存并刷新工作区（包级；真实手势 = 驱动 ParamSidePanel 表单
         *  填写+提交+回调，10+ 调用点全量重写且脆弱 —— 测试已直驱同一份表单面板
         *  （ParamSidePanel.pane()），缺的只是"把确认结果应用到控制器"这最后一步）。 */
        public void applyConfirmedParams(boolean imageMode, AppConfig params) {
            mc.paramDomain.applyConfirmedParams(imageMode, params);
        }

        /** 写入某组的已确认参数缓存（包级；理由同 {@link #applyConfirmedParams}，组作用域手势更重）。 */
        public void applyGroupParams(GroupItem<PreviewItem> group, boolean imageMode, AppConfig params) {
            mc.paramDomain.applyGroupParams(group, imageMode, params);
        }

        /** 写入某条目的条目级独立参数（包级；理由同 {@link #applyConfirmedParams}）。 */
        public void applyItemParams(PreviewItem item, boolean imageMode, AppConfig params) {
            mc.paramDomain.applyItemParams(item, imageMode, params);
        }

        /** 运行时提取配置构建（步骤 2 起本体在 {@code ExtractionTaskCoordinator}，
         *  经包级字段 {@code MainController.extractionCoordinator} 直访；纯函数 —— 把 GUI 状态
         *  与已确认缓存合成运行时配置，无手势可观察，在驱动里重实现会复制生产逻辑、等价性脆弱）。 */
        public AppConfig buildRuntimeConfig() {
            return mc.extractionCoordinator.buildRuntimeConfig();
        }

        /**
         * 移除（<b>真实手势</b>）：先清掉既有勾选（按钮路径的移除目标 = 全部勾选项），
         * 再勾上本次目标，然后点移除按钮 —— {@code onRemoveClicked → removeSelected → removeItems}
         * 全生产路径。仅服务"部分移除"语义（目标数 &lt; 可见数，不弹确认卡）；
         * FeatureExercise 的两处调用（单条目 / 两个条目）都满足该前提。
         */
        public void removeItems(List<PreviewItem> targets) {
            mc.listInteraction.setCheckedSilently(allVisibleItems(), false);
            mc.listInteraction.setCheckedSilently(targets, true);
            mc.refs.removeButton().fire();
        }

        /** 按数据序重建显示序（包级；纯内部刷新 —— 快进动画 + 父指针 + 投影，无便宜手势）。 */
        public void rebuildDisplay() {
            mc.rebuildDisplay();
        }

        /** 列表结构变化后的统一刷新（包级；它是所有结构手势的收尾，无独立手势）。 */
        public void refreshAfterListChange() {
            mc.refreshAfterListChange();
        }

        /** 参数变化后的统一刷新（包级；理由同 {@link #refreshAfterListChange}）。 */
        public void refreshAfterParamsChanged() {
            mc.paramDomain.refreshAfterParamsChanged();
        }

        /** 开始按钮可用性重算（包级；唯一派生函数，测试要直接驱动"模拟导入中 → 重算"断言）。 */
        public void updateStartButtonState() {
            mc.paramDomain.updateStartButtonState();
        }

        /** 进度条/状态栏刷新（包级；测试用 parseToken 精确驱动代次语义，手势不可控；C5 起经预解析协作者）。 */
        public void updateProgressStatus(int token) {
            mc.preparseController.updateProgressStatus(token);
        }

        /** 右键菜单禁用态刷新（包级；真实手势 = 弹出右键菜单，popup 处理脆弱）。 */
        public void updateContextMenuState(Object target) {
            mc.updateContextMenuState(target);
        }

        /** 外部导入拖拽落点高亮（包级；真实手势 = 外部文件 DnD 事件序列，脆弱）。 */
        public void updateImportDropHighlight(boolean on) {
            mc.updateImportDropHighlight(on);
        }

        /** 拖拽高亮硬复位（包级；理由同 {@link #updateImportDropHighlight}）。 */
        public void clearImportDropHighlight() {
            mc.clearImportDropHighlight();
        }

        /** 取消进行中的预解析/图片懒加载（包级；纯内部代次自增 + 取消，触发点=关窗/模式切换等不可控手势）。 */
        public void cancelWorkspaceActivity() {
            mc.cancelWorkspaceActivity();
        }

        /** 折叠/展开（{@code GroupCommandController.toggleGroupExpanded} 包级方法 +
         *  {@code MainController.groupCommand} 字段经 DSH 裁决授权 b 放宽为包级）。 */
        public void toggleGroupExpanded(GroupItem<PreviewItem> g) {
            mc.groupCommand.toggleGroupExpanded(g);
        }

        /** 折叠/展开动画收尾（同上）。 */
        public void finishExpandAnim() {
            mc.groupCommand.finishExpandAnim();
        }

        // ==================== D：内部状态探针（包级直接访问；DSH 裁决授权"仅可见性"） ====================

        /** 单次提取的运行标记（协调器投影，包级方法）。 */
        public AtomicBoolean running() {
            return mc.running();
        }

        /** 最近一次提取的统计（协调器只读投影，包级方法）。 */
        public AtomicReference<ExtractionStats> extractStats() {
            return mc.extractStats();
        }

        /** 文本模式已确认参数缓存（读可经 effectiveForObject 派生，但"置 null = 未确认"
         *  无手势可表达 —— 没有用户流程会把缓存清回 null 而不带其它副作用。步骤 1 起由
         *  {@code ParamDomainController} 持有（P1-1 下沉 app 层，包级同名委托方法），
         *  这里经 {@code mc.paramDomain} 调同名方法）。 */
        public AppConfig textParamsCache() {
            return mc.paramDomain.textParamsCache();
        }

        public void textParamsCache(AppConfig v) {
            mc.paramDomain.textParamsCache(v);
        }

        /** 图片模式已确认参数缓存（包级委托方法；理由同 {@link #textParamsCache()}）。 */
        public AppConfig imageParamsCache() {
            return mc.paramDomain.imageParamsCache();
        }

        public void imageParamsCache(AppConfig v) {
            mc.paramDomain.imageParamsCache(v);
        }

        /** 工作区后台活动代次（包级字段；内部计数器，无手势）。 */
        public int parseToken() {
            return mc.parseToken;
        }

        /** 预览图片懒加载在途标记（包级字段 ×2：{@code MainController.previewLoader} +
         *  {@code PreviewLoader.imageLoadActive}；测试用它做轮询等待语义）。 */
        public boolean imageLoadActive() {
            return mc.previewLoader.imageLoadActive;
        }

        public void imageLoadActive(boolean v) {
            mc.previewLoader.imageLoadActive = v;
        }

        /** 拖拽目标嵌套深度计数（包级字段；DnD 事件状态，无手势可复现）。 */
        public int importDragDepth() {
            return mc.importDragDepth;
        }

        public void importDragDepth(int v) {
            mc.importDragDepth = v;
        }

        /** 模拟"导入进行中"状态（包级字段；真实替代 = 起真导入再掐时机断言，时序脆弱）。 */
        public void importPending(MainController.ImportSession session) {
            mc.importPending = session;
        }

        /** {@code MainController.ImportSession} 的包级记录直接构造（DSH 裁决：去掉 private，
         *  消除 D-3 点名禁止的反射构造）。 */
        public MainController.ImportSession newImportSession(List<String> addresses, String sourceDesc, boolean fromDrag) {
            return new MainController.ImportSession(addresses, sourceDesc, fromDrag);
        }
    }
}
