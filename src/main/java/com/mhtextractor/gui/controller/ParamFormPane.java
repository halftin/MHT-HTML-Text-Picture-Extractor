package com.mhtextractor.gui.controller;

import atlantafx.base.controls.ToggleSwitch;
import com.mhtextractor.app.EffectiveParamsService;
import com.mhtextractor.app.ParamDraft;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.gui.component.ParamTargetBar;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.uicore.ParamTargetText;
import com.mhtextractor.util.ParamScope;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.HorizontalDirection;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * 提取参数表单面板（纯 {@code Pane}，可嵌入）：承载「文本参数组 / 图片参数组」两页控件与全部表单联动，
 * 提供「载入初值 → 编辑 → 收集参数」的完整闭环，由非模态侧边栏 {@link ParamSidePanel} 嵌入
 * （D 起模态参数窗口壳已删，参数编辑只有侧边栏这一条产品路径）。
 *
 * <p><b>本类不持窗口</b>：没有 {@code Stage} / {@code Scene} / 模态阻塞，也不直接弹提醒
 * （{@code NotificationHost}）。窗口关闭与命名配置的存取由宿主注入 ——
 * {@link #setCloseHandler(Consumer)} 与 {@link #setNamedConfigActions(Runnable, Runnable, Runnable)}；
 * 未注入时对应动作是<b>无操作</b>，不会静默改变参数（只影响按钮有没有反应，不影响参数语义）。</p>
 *
 * <p><b>它是 FXML 的根对象也是它的控制器</b>：{@code ParamDialog.fxml} 的根元素是
 * {@code <fx:root type="javafx.scene.layout.BorderPane">}（没有 {@code fx:controller}），
 * 构造时由本类把 {@code this} 同时交给 {@code setRoot} / {@code setController}，
 * 于是 {@code @FXML} 字段（及其子类继承到的同一批字段）注入到同一个对象上。
 * {@code %key} 文案仍由 {@code loader.setResources(I18n.bundle())} 解析，与拆分前逐字一致。</p>
 *
 * <h2>本类新增的两件事（Q5b：R1.4 / R1.5）</h2>
 * <ul>
 *   <li><b>目标信息栏</b>（R1.4）：{@link ParamTargetBar} 挂在 {@code BorderPane} 的 <b>top</b> ——
 *       即窗口标题栏正下方那<b>固定一行</b>，它不随参数区的滚动条跑掉。纯代码构建，{@code ParamDialog.fxml}
 *       一个字未改（因此也没有 native 元数据增补）。文案由 {@link ParamTargetText} 拼（纯静态、可无头单测），
 *       组内混合提示的判定复用 {@code app.EffectiveParamsService.hasMixedEffectiveParams}。</li>
 *   <li><b>草稿状态机</b>（R1.5）：{@link ParamDraft}（{@code app} 包，纯逻辑、零 JavaFX）。
 *       {@link #load} 把初值写进控件<b>之后</b>记基线，于是"刚打开 = 不脏"；{@link #isDirty()} 按需计算；
 *       {@link #commitDraft()} 才是提交（只由确认路径调用）。</li>
 * </ul>
 *
 * <h2>{@link #isDirty()} 为什么是"按需计算"而不是"事件驱动"</h2>
 * <p>{@code collect()} 要读 39 个控件并构建 {@code AppConfig}，挂在每个控件的
 * {@code textProperty()/selectedProperty()} 上意味着"每敲一个字符就重建一份参数快照 + 算一次签名"，
 * 而本步<b>没有任何消费方需要实时值</b>（模态窗口只在确认时关心是否提交过）。故取按需计算：
 * 只有真正问"脏不脏"时才 {@code collect()} + 比较，一次查询的代价与一次"确认"同级。</p>
 * <p><b>Q6b 接上了"按钮实时禁用"</b>：判定仍然是按需计算，改动的只是<b>什么时候去问一次</b> ——
 * {@link #setDraftStateListener(Runnable)} 收到的回调<b>不携带任何状态</b>，收方自己再查一次
 * {@link #isDirty()}。触发点是三个点（不是 39 个控件监听）：
 * {@link #load} 重记基线后、{@link #commitDraft()} 提交后、以及面板上冒泡到本节点的
 * {@code MOUSE_CLICKED} / {@code KEY_RELEASED}（用户确实改动了控件值的两个最小事件）。
 * 于是"改完立刻就问一次"，而不是"每敲一个字符就重算一次签名"：一次编辑最多多算一次签名，
 * 且不监听 {@code textProperty()}（那才是 Q5b 明确拒绝的每击键重算）。</p>
 *
 * <p><b>已知的宿主职责</b>（有意留在宿主，不搬进来）：</p>
 * <ul>
 *   <li>窗口级接线（{@code NotificationHost.install(...)} 等）—— 见
 *       {@link ParamSidePanel}（D 起唯一的宿主）与其宿主窗口。</li>
 *   <li>「高速内存模式」的确认提醒卡片：它需要窗口内的提醒层锚点，故监听由共用的宿主动作件
 *       {@link ParamPanelActions} 挂载（<b>产品行为不变</b>：仍在勾选时弹出、取消仍回退勾选）。</li>
 *   <li>命名配置的文件 IO 与提醒文案（{@code ConfigManager} + {@code TextInputDialog} +
 *       {@code NotificationHost}）：本类只转发三个动作，<b>不决定</b>用模态窗口问用户配置名。</li>
 * </ul>
 */
public class ParamFormPane extends BorderPane {

    private static final String FXML_PATH = "/com/mhtextractor/gui/view/ParamDialog.fxml";

    // ===== 文本参数组 =====
    @FXML TitledPane textTitlePane;
    @FXML TitledPane textContentLimitPane;
    @FXML TitledPane textTemplatePane;
    @FXML TitledPane textOutputModePane;
    @FXML ToggleSwitch addChapterNumberCheck;
    @FXML ToggleSwitch enableTitleExtractCheck;
    @FXML TextField titleStartMarkerField;
    @FXML TextField titleEndMarkerField;
    @FXML ToggleSwitch titleUseRegexCheck;
    @FXML ToggleSwitch enableLimitCheck;
    @FXML TextField startMarkerField;
    @FXML TextField endMarkerField;
    @FXML ToggleSwitch useRegexCheck;
    @FXML ToggleSwitch enableTemplateCheck;
    @FXML TextField startTemplateField;
    @FXML TextField endTemplateField;
    @FXML ToggleSwitch separateFilesCheck;
    @FXML ToggleSwitch autoFixEncodingCheck;

    // ===== 图片参数组 =====
    @FXML TitledPane imageNamePane;
    @FXML TitledPane imageRegionPane;
    @FXML TitledPane imageProcessPane;
    @FXML TitledPane imageOutputModePane;
    @FXML TitledPane imagePerformancePane;
    @FXML ToggleSwitch imageNameExtractCheck;
    @FXML TextField imageNameStartField;
    @FXML TextField imageNameEndField;
    @FXML ToggleSwitch imageRegionExtractCheck;
    @FXML TextField imageRegionStartField;
    @FXML TextField imageRegionEndField;
    @FXML ToggleSwitch imageRenameCheck;
    @FXML ToggleSwitch imageZipCheck;
    @FXML ToggleSwitch imageCbzCheck;
    @FXML ToggleSwitch singleOutputCheck;
    @FXML ToggleSwitch highSpeedMemoryCheck;

    // ===== 分组与底部操作 =====
    @FXML VBox textParamsBox;
    @FXML VBox imageParamsBox;
    @FXML ComboBox<String> configComboBox;
    @FXML Button cancelButton;
    @FXML Button confirmButton;

    /** 「确定 / 取消」出口（宿主注入）：true = 确定，false = 取消 */
    private Consumer<Boolean> closeHandler;
    /** 命名配置三动作（宿主注入）：保存 / 加载 / 删除 */
    private Runnable saveConfigAction;
    private Runnable loadConfigAction;
    private Runnable deleteConfigAction;

    /**
     * 「草稿状态可能变了」的窄通知（宿主注入，见类注释的 Q6b 一节）。
     *
     * <p>它<b>不携带</b>dirty 值，只是"此刻该重新问一次 {@link #isDirty()} 了"的信号 ——
     * 这样脏判定的真值仍只有 {@link ParamDraft} 一处，本类不会另存一个 dirty 布尔。</p>
     */
    private Runnable draftStateListener;

    private boolean imageMode;

    /** 参数草稿状态机（R1.5）：基线 / 脏判定 / 提交；纯逻辑在 {@code app.ParamDraft}。 */
    private final ParamDraft draft = new ParamDraft();
    /** 「组内参数是否混合」的判定入口（R1.4 增强项）；服务本身无状态，仅此一处持有。 */
    private final EffectiveParamsService effectiveParams = new EffectiveParamsService();
    /** 目标信息栏（R1.4）：固定挂在 top，见类注释。 */
    private final ParamTargetBar targetBar = new ParamTargetBar();

    /** 独立构造：载入 {@value #FXML_PATH} 并把本对象同时作为根与控制器（{@code fx:root} 用法）。 */
    public ParamFormPane() {
        loadFxml();
        // 信息栏挂在 top（标题栏下方固定一行）：FXML 未改，节点是纯代码加的
        setTop(targetBar);
        // 用户编辑的触发点：面板级两个冒泡事件（见类注释的 Q6b 一节）。
        // 只挂本节点、不挂 39 个控件，子控件的值变更会冒泡到这里，而用户的一次编辑
        // 至多产生一次"该重问 dirty 了"的回调。
        addEventHandler(MouseEvent.MOUSE_CLICKED, e -> notifyDraftStateChanged());
        addEventHandler(KeyEvent.KEY_RELEASED, e -> notifyDraftStateChanged());
    }

    private void loadFxml() {
        FXMLLoader loader = new FXMLLoader(ParamFormPane.class.getResource(FXML_PATH));
        loader.setResources(I18n.bundle());
        loader.setRoot(this);
        loader.setController(this);
        try {
            loader.load();
        } catch (IOException e) {
            throw new IllegalStateException("载入参数表单 FXML 失败：" + FXML_PATH, e);
        }
    }

    // ==================== 宿主注入 ====================

    /** 注入「确定 / 取消」出口（true = 确定，false = 取消）；未注入时按钮无操作。 */
    public void setCloseHandler(Consumer<Boolean> closeHandler) {
        this.closeHandler = closeHandler;
    }

    /** 注入命名配置三动作（保存 / 加载 / 删除）；未注入时按钮无操作（存取方式由宿主决定）。 */
    public void setNamedConfigActions(Runnable save, Runnable load, Runnable delete) {
        this.saveConfigAction = save;
        this.loadConfigAction = load;
        this.deleteConfigAction = delete;
    }

    /**
     * 注入「草稿状态可能变了」的窄通知（Q6b）：在 {@link #load} 重记基线后、
     * {@link #commitDraft()} 提交后、以及用户在面板里点选 / 按键后各触发一次。
     *
     * <p>回调里<b>不要</b>假定脏或干净，自己查 {@link #isDirty()}；本类不会记住上一次是什么值。</p>
     */
    public void setDraftStateListener(Runnable listener) {
        this.draftStateListener = listener;
    }

    // ==================== 表单 API ====================

    /**
     * 按模式与作用域载入初值（= 拆分前模态窗口壳 configure 的对外入口）：
     * 当前模式控件从目标初值初始化（组未设置时回退全局已确认参数，均无则用默认参数）；
     * 另一模式控件由全局缓存初始化（隐藏不可见，两组隔离互不影响）。载入后触发联动并切模式。
     */
    public void load(AppConfig textCache, AppConfig imageCache, boolean imageMode, ParamScope.Target target) {
        AppConfig currentInitial = ParamScope.initialParams(target, imageMode, textCache, imageCache);
        if (currentInitial == null) {
            currentInitial = new AppConfig();
        }
        if (imageMode) {
            applyTextParamsToGui(textCache != null ? textCache : new AppConfig());
            applyImageParamsToGui(currentInitial);
        } else {
            applyTextParamsToGui(currentInitial);
            applyImageParamsToGui(imageCache != null ? imageCache : new AppConfig());
        }
        // 触发联动，保证子控件启用/禁用与初始值一致（含模板默认值回填）
        toggleLimitSettings();
        toggleTemplateSettings();
        toggleTitleSettings();
        toggleImageNameSettings();
        toggleImageRegionSettings();
        updateTitleExtractTooltip();
        setImageMode(imageMode);
        // R1.5：基线取"初值写进控件之后"的当前值 —— 于是"刚打开、什么都没动"必不脏
        //（含 load 期间联动回填的默认模板文本，它们不是用户改动）。
        draft.baseline(collect());
        // Q6b：重记基线 = 草稿状态变了（切换目标丢弃草稿 / 重新载入初值），让宿主重算按钮态
        notifyDraftStateChanged();
        // R1.4：刷新目标信息栏（组内混合提示的上下文就是本次载入用的全局参数缓存）
        targetBar.show(target, null,
                ParamTargetText.isGroupMixed(target,
                        new EffectiveParamsService.Context(imageMode, textCache, imageCache)));
    }

    /**
     * 是否有未提交改动（R1.5）：把当前控件值收成参数快照，交给 {@link ParamDraft} 按值比较。
     *
     * <p><b>按需计算</b>（不是事件驱动），理由与 Q6a 的接法见类注释。每次调用都会
     * {@code collect()}，故不要把它放进每帧/每按键的路径。</p>
     */
    public boolean isDirty() {
        return draft.isDirty(collect());
    }

    /**
     * 提交草稿（R1.9：仅「确认」提交）：把当前值记为新的基线，脏标记清零。
     *
     * <p>只应由确认路径调用（本类的 {@code onConfirm}）。取消 / 折叠 / 切换目标都不该调 ——
     * 那会把"未确认的编辑"就地升级成基线，折叠丢草稿的缺陷就是这么来的。</p>
     */
    public void commitDraft() {
        draft.commit(collect());
        // Q6b：提交 = 草稿状态变了（脏 → 干净），让宿主重算按钮态
        notifyDraftStateChanged();
    }

    /** 以两组控件当前值构建参数快照（仅参数，不含目录/模式/GUI 状态）；字段映射与拆分前逐条一致。 */
    public AppConfig collect() {
        AppConfig config = new AppConfig();

        // 文本参数
        config.setSeparateFiles(separateFilesCheck.isSelected());
        config.setEnableContentLimit(enableLimitCheck.isSelected());
        config.setStartMarker(startMarkerField.getText());
        config.setEndMarker(endMarkerField.getText());
        config.setUseRegex(useRegexCheck.isSelected());
        config.setEnableTemplate(enableTemplateCheck.isSelected());
        config.setStartTemplate(startTemplateField.getText());
        config.setEndTemplate(endTemplateField.getText());
        config.setAddChapterNumber(addChapterNumberCheck.isSelected());
        config.setEnableTitleExtraction(enableTitleExtractCheck.isSelected());
        config.setTitleStartMarker(titleStartMarkerField.getText());
        config.setTitleEndMarker(titleEndMarkerField.getText());
        config.setTitleUseRegex(titleUseRegexCheck.isSelected());
        config.setAutoFixEncoding(autoFixEncodingCheck.isSelected());

        // 图片参数
        config.setEnableNameExtraction(imageNameExtractCheck.isSelected());
        config.setImageNameStartMarker(imageNameStartField.getText());
        config.setImageNameEndMarker(imageNameEndField.getText());
        config.setEnableRegionExtraction(imageRegionExtractCheck.isSelected());
        config.setImageRegionStartMarker(
                config.isEnableRegionExtraction() ? imageRegionStartField.getText() : null);
        config.setImageRegionEndMarker(
                config.isEnableRegionExtraction() ? imageRegionEndField.getText() : null);
        config.setEnableBatchRenaming(imageRenameCheck.isSelected());
        config.setEnableZipPackaging(imageZipCheck.isSelected());
        config.setEnableCbzPackaging(imageCbzCheck.isSelected());
        config.setSingleOutputMode(singleOutputCheck.isSelected());
        config.setHighSpeedMemoryMode(highSpeedMemoryCheck.isSelected());

        return config;
    }

    /** 切模式：显示当前模式的参数组（另一组隐藏且不占位）；初值载入时由 {@link #load} 调用。 */
    public void setImageMode(boolean imageMode) {
        this.imageMode = imageMode;
        textParamsBox.setVisible(!imageMode);
        textParamsBox.setManaged(!imageMode);
        imageParamsBox.setVisible(imageMode);
        imageParamsBox.setManaged(imageMode);
    }

    /** 当前是否为图片模式（宿主用于标题、以及"配置只填充当前模式参数组"的判断）。 */
    public boolean isImageMode() {
        return imageMode;
    }

    /**
     * 把一份命名配置填充到<b>当前模式</b>的参数组（另一组保持隔离，确定后才生效）：
     * = 拆分前 {@code onLoadConfig} 的填充段，联动与 tooltip 一并刷新。
     */
    void applyLoadedConfig(AppConfig config) {
        if (imageMode) {
            applyImageParamsToGui(config);
            toggleImageNameSettings();
            toggleImageRegionSettings();
        } else {
            applyTextParamsToGui(config);
            toggleLimitSettings();
            toggleTemplateSettings();
            toggleTitleSettings();
            updateTitleExtractTooltip();
        }
    }

    /** 刷新命名配置下拉框的备选项（数据来源由宿主提供：本类不做文件 IO）。 */
    void setConfigNames(List<String> names) {
        configComboBox.setItems(FXCollections.observableArrayList(names));
    }

    // ==================== 初始化（FXMLLoader 注入完成后调用） ====================

    @FXML
    private void initialize() {
        ToggleSwitch[] toggles = {enableLimitCheck, useRegexCheck, enableTemplateCheck,
                addChapterNumberCheck, enableTitleExtractCheck, titleUseRegexCheck,
                separateFilesCheck, autoFixEncodingCheck, imageNameExtractCheck,
                imageRegionExtractCheck, imageRenameCheck, imageZipCheck,
                imageCbzCheck, singleOutputCheck, highSpeedMemoryCheck};
        for (ToggleSwitch t : toggles) {
            t.setLabelPosition(HorizontalDirection.RIGHT);
        }

        // 文本参数组联动
        enableLimitCheck.selectedProperty().addListener((obs, old, newVal) -> toggleLimitSettings());
        enableTemplateCheck.selectedProperty().addListener((obs, old, newVal) -> toggleTemplateSettings());
        enableTitleExtractCheck.selectedProperty().addListener((obs, old, newVal) -> toggleTitleSettings());
        addChapterNumberCheck.selectedProperty().addListener((obs, old, newVal) -> updateTitleExtractTooltip());

        // 图片参数组联动
        imageNameExtractCheck.selectedProperty().addListener((obs, old, newVal) -> toggleImageNameSettings());
        imageRegionExtractCheck.selectedProperty().addListener((obs, old, newVal) -> toggleImageRegionSettings());

        // 注：「高速内存模式」的提醒卡片需要窗口提醒层，监听由宿主动作件挂载（见 ParamPanelActions.install）。
        // 注：命名配置列表由宿主在注入数据源后刷新（本类不碰文件系统）。

        setupTemplateFieldEnterKeyHandler();
    }

    // ==================== FXML 动作（转发给宿主注入的实现） ====================

    @FXML
    private void onConfirm() {
        // R1.9「仅确认提交」：先把当前值提交成新基线（清脏），再把出口交给宿主。
        // 出口的签名与调用次数都未变（仍是 closeHandler.accept(TRUE) 一次）。
        commitDraft();
        if (closeHandler != null) {
            closeHandler.accept(Boolean.TRUE);
        }
    }

    @FXML
    private void onCancel() {
        if (closeHandler != null) {
            closeHandler.accept(Boolean.FALSE);
        }
    }

    @FXML
    private void onSaveConfig() {
        if (saveConfigAction != null) {
            saveConfigAction.run();
        }
    }

    @FXML
    private void onLoadConfig() {
        if (loadConfigAction != null) {
            loadConfigAction.run();
        }
    }

    @FXML
    private void onDeleteConfig() {
        if (deleteConfigAction != null) {
            deleteConfigAction.run();
        }
    }

    // ==================== 从 AppConfig 填充控件 ====================

    private void applyTextParamsToGui(AppConfig config) {
        separateFilesCheck.setSelected(config.isSeparateFiles());
        enableLimitCheck.setSelected(config.isEnableContentLimit());
        startMarkerField.setText(orEmpty(config.getStartMarker()));
        endMarkerField.setText(orEmpty(config.getEndMarker()));
        useRegexCheck.setSelected(config.isUseRegex());
        enableTemplateCheck.setSelected(config.isEnableTemplate());
        startTemplateField.setText(orEmpty(config.getStartTemplate()));
        endTemplateField.setText(orEmpty(config.getEndTemplate()));
        addChapterNumberCheck.setSelected(config.isAddChapterNumber());
        enableTitleExtractCheck.setSelected(config.isEnableTitleExtraction());
        titleStartMarkerField.setText(orEmpty(config.getTitleStartMarker()));
        titleEndMarkerField.setText(orEmpty(config.getTitleEndMarker()));
        titleUseRegexCheck.setSelected(config.isTitleUseRegex());
        autoFixEncodingCheck.setSelected(config.isAutoFixEncoding());
    }

    private void applyImageParamsToGui(AppConfig config) {
        imageNameExtractCheck.setSelected(config.isEnableNameExtraction());
        imageNameStartField.setText(orEmpty(config.getImageNameStartMarker()));
        imageNameEndField.setText(orEmpty(config.getImageNameEndMarker()));
        imageRegionExtractCheck.setSelected(config.isEnableRegionExtraction());
        imageRegionStartField.setText(orEmpty(config.getImageRegionStartMarker()));
        imageRegionEndField.setText(orEmpty(config.getImageRegionEndMarker()));
        imageRenameCheck.setSelected(config.isEnableBatchRenaming());
        imageZipCheck.setSelected(config.isEnableZipPackaging());
        imageCbzCheck.setSelected(config.isEnableCbzPackaging());
        singleOutputCheck.setSelected(config.isSingleOutputMode());
        highSpeedMemoryCheck.setSelected(config.isHighSpeedMemoryMode());
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    // ==================== 子控件条件联动 ====================

    /** 内容限定字段联动：未勾选时禁用标记输入框并清空 */
    private void toggleLimitSettings() {
        boolean enabled = enableLimitCheck.isSelected();
        startMarkerField.setDisable(!enabled);
        endMarkerField.setDisable(!enabled);
        useRegexCheck.setDisable(!enabled);
        if (!enabled) {
            startMarkerField.clear();
            endMarkerField.clear();
            useRegexCheck.setSelected(false);
        }
    }

    /** 自定义模板字段联动：未勾选时禁用模板输入框并恢复默认值 */
    private void toggleTemplateSettings() {
        boolean enabled = enableTemplateCheck.isSelected();
        startTemplateField.setDisable(!enabled);
        endTemplateField.setDisable(!enabled);
        if (!enabled) {
            startTemplateField.setText("\\n========== START ==========\\n");
            endTemplateField.setText("\\n========== END ==========\\n");
        }
    }

    /** 标题提取字段联动：未勾选时禁用标记输入框并清空 */
    private void toggleTitleSettings() {
        boolean enabled = enableTitleExtractCheck.isSelected();
        titleStartMarkerField.setDisable(!enabled);
        titleEndMarkerField.setDisable(!enabled);
        titleUseRegexCheck.setDisable(!enabled);
        if (!enabled) {
            titleStartMarkerField.clear();
            titleEndMarkerField.clear();
            titleUseRegexCheck.setSelected(false);
        }
        updateTitleExtractTooltip();
    }

    /** 更新标题提取开关的 tooltip，反映章节序号+标题的组合状态 */
    private void updateTitleExtractTooltip() {
        boolean hasChapterNumber = addChapterNumberCheck.isSelected();
        boolean enableExtract = enableTitleExtractCheck.isSelected();
        String tooltip;
        if (enableExtract) {
            tooltip = hasChapterNumber
                    ? I18n.get("dialog.chapterModeNumberTitle")
                    : I18n.get("dialog.chapterModeTitleOnly");
        } else {
            tooltip = I18n.get("dialog.disabled");
        }
        enableTitleExtractCheck.setTooltip(new Tooltip(tooltip));
    }

    /** 图片名称提取字段联动：未勾选时禁用标记输入框并清空 */
    private void toggleImageNameSettings() {
        boolean enabled = imageNameExtractCheck.isSelected();
        imageNameStartField.setDisable(!enabled);
        imageNameEndField.setDisable(!enabled);
        if (!enabled) {
            imageNameStartField.clear();
            imageNameEndField.clear();
        }
    }

    /** 图片区域限定字段联动：未勾选时禁用标记输入框并清空 */
    private void toggleImageRegionSettings() {
        boolean enabled = imageRegionExtractCheck.isSelected();
        imageRegionStartField.setDisable(!enabled);
        imageRegionEndField.setDisable(!enabled);
        if (!enabled) {
            imageRegionStartField.clear();
            imageRegionEndField.clear();
        }
    }

    /** 模板输入框回车处理：按下回车时插入 \n 字符串 */
    private void setupTemplateFieldEnterKeyHandler() {
        javafx.event.EventHandler<javafx.scene.input.KeyEvent> enterHandler = event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                event.consume();
                TextField source = (TextField) event.getSource();
                int caretPos = source.getCaretPosition();
                String currentText = source.getText();
                String newText = currentText.substring(0, caretPos) + "\\n" + currentText.substring(caretPos);
                source.setText(newText);
                source.positionCaret(caretPos + 2);
            }
        };

        startTemplateField.setOnKeyPressed(enterHandler);
        endTemplateField.setOnKeyPressed(enterHandler);
    }

    // ==================== Q6b：草稿变更通知 ====================

    /**
     * 通知宿主"草稿状态可能变了"。<b>不带状态值</b>：宿主收到后自己查 {@link #isDirty()}，
     * 于是"脏不脏"只有 {@link ParamDraft} 一个判定点（不在这里另存布尔）。
     */
    private void notifyDraftStateChanged() {
        if (draftStateListener != null) {
            draftStateListener.run();
        }
    }
}
