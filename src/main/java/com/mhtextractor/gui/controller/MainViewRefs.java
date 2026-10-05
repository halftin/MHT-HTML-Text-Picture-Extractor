package com.mhtextractor.gui.controller;

import atlantafx.base.controls.ToggleSwitch;
import com.mhtextractor.gui.component.LogTextArea;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code MainView.fxml} 命名空间里的节点引用集合：{@code fx:id} → 控件（P6-B）。
 *
 * <h2>它取代了什么</h2>
 * <p>P6-B 之前，{@code MainController} 里有 <b>42 个 {@code @FXML} 节点字段</b>（50 行），
 * 由 {@code FXMLLoader} 反射注入；本类把同一批引用改成"由命名空间构造 + 具名访问器访问"，
 * 于是控制器不再持有 42 个字段，且"某个 {@code fx:id} 丢了"从<b>运行期 NPE</b> 变成
 * <b>构造期显式异常（并指名是哪个 {@code fx:id}）</b>。</p>
 *
 * <h2>为什么放在 {@code gui.controller}</h2>
 * <p>它是"这一个控制器的视图引用集合"，与 {@code MainController} 同包：</p>
 * <ul>
 *   <li>{@code MainController} 与同包生产协作类（{@code MainViewInstaller}）直接使用；</li>
 *   <li>测试源集的同包直访驱动（{@code AcceptanceDriver}）与 {@code tools/probes} 探针要读同一批节点，
 *       故本类是 {@code public}（访问器本身就是"生产行为"，不是为测试开放的状态面 ——
 *       它不含任何可变业务状态，只是命名空间的一张只读映射）。</li>
 * </ul>
 *
 * <h2>{@code from(...)} 的语义（本步最容易埋雷的地方）</h2>
 * <p><b>缺失即失败、且指名 {@code fx:id}</b>：44 个名字里漏一个，症状会是运行期 NPE；
 * 所以 {@link #from(Map)} 会对 {@link #SPEC} 里的每一项做"存在 + 类型"校验，
 * 任一不满足就抛 {@link IllegalStateException}，消息里带 {@code fx:id="<名字>"}。</p>
 * <p><b>{@code all()}</b> 返回同一张映射（44 项、全部非 null），供探针机械断言"一个都没漏"
 * （见 {@code tools/probes/MainViewRefsProbe.java}）。</p>
 *
 * <h2>{@code modeToggleGroup} 为什么让 {@code all()} 的值类型是 {@code Object}</h2>
 * <p>44 个 {@code fx:id} 中 43 个是 {@code Node}，唯一例外 {@code modeToggleGroup} 是
 * {@code javafx.scene.control.ToggleGroup}（写在 {@code <fx:define>} 里，<b>不是</b> {@code Node}）——
 * 这是实测事实（同一结论早先记录在 {@code FeatureExercise} 与 {@code ArchitectureTest} 的演进记录里）。
 * 若把 {@code all()} 声明成 {@code Map<String, Node>}，这一项就<b>放不进去</b>，
 * "44 项全非 null 且数量与 FXML 的 {@code fx:id} 数一致"这条断言将无法成立。</p>
 *
 * <p><b>⏱ 时机</b>：{@code FXMLLoader.loadImpl} 的顺序是"先往 namespace 写 location/resources →
 * 解析 XML（每个 {@code fx:id} 元素构造完成时写进同一张 namespace）→ injectFields →
 * 反射调用控制器的 {@code initialize()}"，因此 {@code initialize()} 期间 namespace 已经完整
 * （证据：JDK 自带 {@code src.zip} 的 {@code javafx.fxml.FXMLLoader} 源码 2555-2664 行 +
 * 探针里的运行期断言）。</p>
 */
public final class MainViewRefs {

    /**
     * 44 个 {@code fx:id} 及其期望控件类型 —— <b>唯一清单</b>。
     *
     * <p>⚠️ {@code MainView.fxml} 增删 {@code fx:id} 必须同步改这里；漏改会在
     * {@link #from(Map)} 处立刻炸，而不是等某个按钮点了没反应。
     * 顺序 = FXML 里的声明顺序（<b>不是</b>字母序），便于与本类访问器逐段对照。</p>
     */
    private static final Map<String, Class<?>> SPEC = spec();

    private static Map<String, Class<?>> spec() {
        Map<String, Class<?>> m = new LinkedHashMap<>();
        // ===== 顶部控制栏 =====
        m.put("modeToggleGroup", ToggleGroup.class);          // fx:define：不是 Node
        m.put("textModeToggle", ToggleButton.class);
        m.put("imageModeToggle", ToggleButton.class);
        m.put("inputFolderField", TextField.class);
        m.put("outputFolderField", TextField.class);
        m.put("browseInputButton", Button.class);
        m.put("browseOutputButton", Button.class);
        // ===== 预览工作区（左：列表） =====
        m.put("fileCountLabel", Label.class);
        m.put("selectAllCheck", CheckBox.class);
        m.put("badgeHeaderLabel", Label.class);
        m.put("fileListView", ListView.class);
        m.put("dragOverlay", Pane.class);
        // ===== 列表按钮行 =====
        m.put("removeButton", Button.class);
        m.put("clearParamsButton", Button.class);
        m.put("undoButton", Button.class);
        m.put("viewSourceButton", Button.class);
        m.put("groupButton", Button.class);
        m.put("ungroupButton", Button.class);
        m.put("applyGlobalButton", Button.class);
        // ===== 预览工作区（右：信息区与内容区） =====
        m.put("modeLabel", Label.class);
        m.put("outputModeLabel", Label.class);
        m.put("outputNameLabel", Label.class);
        m.put("groupNameBox", HBox.class);
        m.put("groupNameField", TextField.class);
        m.put("titleEditBox", HBox.class);
        m.put("titleField", TextField.class);
        m.put("titleInfoLabel", Label.class);
        m.put("outputResultLabel", Label.class);
        m.put("adjustCheck", CheckBox.class);
        m.put("textPreviewArea", TextArea.class);
        m.put("imagePreviewBox", VBox.class);
        m.put("imageHintLabel", Label.class);
        m.put("extractResultBanner", Label.class);
        m.put("previewEffectButton", Button.class);
        m.put("renameNoteLabel", Label.class);
        m.put("imageScrollPane", ScrollPane.class);
        m.put("thumbnailPane", FlowPane.class);
        m.put("paramSettingsButton", Button.class);
        // ===== 底部操作区 =====
        m.put("progressBar", ProgressBar.class);
        m.put("statusLabel", Label.class);
        m.put("startButton", Button.class);
        m.put("stopButton", Button.class);
        m.put("logTextArea", LogTextArea.class);
        m.put("openFolderAfterCompleteCheck", ToggleSwitch.class);
        return Collections.unmodifiableMap(m);
    }

    /** {@code fx:id} → 控件（44 项，全部非 null；unmodifiable）。 */
    private final Map<String, Object> byId;

    private MainViewRefs(Map<String, Object> byId) {
        this.byId = byId;
    }

    /**
     * 由 FXML 命名空间构造（{@code FXMLLoader.getNamespace()}）。
     *
     * @param namespace 命名空间（除 44 个 {@code fx:id} 外还含 {@code location}/{@code resources} 两个键，
     *                  本类只取 {@link #SPEC} 里的键）
     * @throws IllegalStateException 任一项缺失或类型不符 —— 消息里带 {@code fx:id="<名字>"}
     */
    public static MainViewRefs from(Map<String, Object> namespace) {
        Objects.requireNonNull(namespace,
                "namespace 为 null：MainController 必须由 FXMLLoader 的 controller factory 构造"
                        + "（见 App.start() / TestMain 的 setControllerFactory 接线）");
        Map<String, Object> byId = new LinkedHashMap<>();
        for (Map.Entry<String, Class<?>> e : SPEC.entrySet()) {
            byId.put(e.getKey(), require(namespace, e.getKey(), e.getValue()));
        }
        return new MainViewRefs(Collections.unmodifiableMap(byId));
    }

    private static Object require(Map<String, Object> namespace, String fxId, Class<?> expected) {
        Object value = namespace.get(fxId);
        if (value == null) {
            throw new IllegalStateException("MainViewRefs.from：FXML 命名空间里缺少 fx:id=\"" + fxId
                    + "\"（期望 " + expected.getName() + "）—— MainView.fxml 的 fx:id 与 MainViewRefs.SPEC 必须一一对应");
        }
        if (!expected.isInstance(value)) {
            throw new IllegalStateException("MainViewRefs.from：fx:id=\"" + fxId + "\" 的实际类型 "
                    + value.getClass().getName() + " 与期望 " + expected.getName() + " 不符");
        }
        return value;
    }

    // ==================== 访问器（一成员一访问器，与 SPEC 一一对应） ====================

    /** {@code fx:id=modeToggleGroup}（{@link ToggleGroup}，<b>不是</b> {@code Node}）。 */
    public ToggleGroup modeToggleGroup() {
        return (ToggleGroup) byId.get("modeToggleGroup");
    }

    public ToggleButton textModeToggle() {
        return (ToggleButton) byId.get("textModeToggle");
    }

    public ToggleButton imageModeToggle() {
        return (ToggleButton) byId.get("imageModeToggle");
    }

    public TextField inputFolderField() {
        return (TextField) byId.get("inputFolderField");
    }

    public TextField outputFolderField() {
        return (TextField) byId.get("outputFolderField");
    }

    public Button browseInputButton() {
        return (Button) byId.get("browseInputButton");
    }

    public Button browseOutputButton() {
        return (Button) byId.get("browseOutputButton");
    }

    public Label fileCountLabel() {
        return (Label) byId.get("fileCountLabel");
    }

    public CheckBox selectAllCheck() {
        return (CheckBox) byId.get("selectAllCheck");
    }

    public Label badgeHeaderLabel() {
        return (Label) byId.get("badgeHeaderLabel");
    }

    @SuppressWarnings("unchecked")
    public ListView<Object> fileListView() {
        return (ListView<Object>) byId.get("fileListView");
    }

    public Pane dragOverlay() {
        return (Pane) byId.get("dragOverlay");
    }

    public Button removeButton() {
        return (Button) byId.get("removeButton");
    }

    public Button clearParamsButton() {
        return (Button) byId.get("clearParamsButton");
    }

    public Button undoButton() {
        return (Button) byId.get("undoButton");
    }

    public Button viewSourceButton() {
        return (Button) byId.get("viewSourceButton");
    }

    public Button groupButton() {
        return (Button) byId.get("groupButton");
    }

    public Button ungroupButton() {
        return (Button) byId.get("ungroupButton");
    }

    public Button applyGlobalButton() {
        return (Button) byId.get("applyGlobalButton");
    }

    public Label modeLabel() {
        return (Label) byId.get("modeLabel");
    }

    public Label outputModeLabel() {
        return (Label) byId.get("outputModeLabel");
    }

    public Label outputNameLabel() {
        return (Label) byId.get("outputNameLabel");
    }

    public HBox groupNameBox() {
        return (HBox) byId.get("groupNameBox");
    }

    public TextField groupNameField() {
        return (TextField) byId.get("groupNameField");
    }

    public HBox titleEditBox() {
        return (HBox) byId.get("titleEditBox");
    }

    public TextField titleField() {
        return (TextField) byId.get("titleField");
    }

    public Label titleInfoLabel() {
        return (Label) byId.get("titleInfoLabel");
    }

    public Label outputResultLabel() {
        return (Label) byId.get("outputResultLabel");
    }

    public CheckBox adjustCheck() {
        return (CheckBox) byId.get("adjustCheck");
    }

    public TextArea textPreviewArea() {
        return (TextArea) byId.get("textPreviewArea");
    }

    public VBox imagePreviewBox() {
        return (VBox) byId.get("imagePreviewBox");
    }

    public Label imageHintLabel() {
        return (Label) byId.get("imageHintLabel");
    }

    public Label extractResultBanner() {
        return (Label) byId.get("extractResultBanner");
    }

    public Button previewEffectButton() {
        return (Button) byId.get("previewEffectButton");
    }

    public Label renameNoteLabel() {
        return (Label) byId.get("renameNoteLabel");
    }

    public ScrollPane imageScrollPane() {
        return (ScrollPane) byId.get("imageScrollPane");
    }

    public FlowPane thumbnailPane() {
        return (FlowPane) byId.get("thumbnailPane");
    }

    public Button paramSettingsButton() {
        return (Button) byId.get("paramSettingsButton");
    }

    public ProgressBar progressBar() {
        return (ProgressBar) byId.get("progressBar");
    }

    public Label statusLabel() {
        return (Label) byId.get("statusLabel");
    }

    public Button startButton() {
        return (Button) byId.get("startButton");
    }

    public Button stopButton() {
        return (Button) byId.get("stopButton");
    }

    public LogTextArea logTextArea() {
        return (LogTextArea) byId.get("logTextArea");
    }

    public ToggleSwitch openFolderAfterCompleteCheck() {
        return (ToggleSwitch) byId.get("openFolderAfterCompleteCheck");
    }

    /** 本类暴露的全部 {@code fx:id} → 控件（44 项、全部非 null；探针用它断言"一个都没漏"）。 */
    public Map<String, Object> all() {
        return byId;
    }
}
