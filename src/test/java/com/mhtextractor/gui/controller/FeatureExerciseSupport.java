package com.mhtextractor.gui.controller;

import atlantafx.base.controls.ToggleSwitch;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.ParamScope;
import javafx.application.Platform;

import java.io.File;

/**
 * {@link FeatureExercise}（GUI 自动化断言）与 {@link AgentExercise}（追踪演练）共享的 GUI 驱动底座
 * （步骤 6 从 FeatureExercise 外迁，纯机械搬移，行为一字未改）。
 *
 * <p>承载两部分：</p>
 * <ul>
 *   <li><b>控件读取</b>：P7 起全部走 {@link AcceptanceDriver.View}（C3b-1 同包直接访问 + 残余反射壳），
 *       不再要求 @FXML 字段放宽可见性；按 fx:id 定位节点（含其历史注释）随代码一并外迁。</li>
 *   <li><b>共享动作</b>：侧边栏参数面板（真实路径）、FX 事件泵与导入收尾等待、样例 MHT 夹具生成。</li>
 * </ul>
 */
class FeatureExerciseSupport {

    /** 被驱动的控制器实例（组合而非继承：不改动 MainController 的构造器可见性） */
    protected final MainController mc;

    /**
     * P7（测试面收口）后，本类访问 {@code MainController} 内部状态与方法的<b>唯一</b>通道。
     *
     * <p>此前这些成员为"同包测试夹具"从 {@code private} 放宽成包级（共 44 处
     * {@code /* test-support *&#47;} 标记）。P7 把可见性全部收回，测试侧改为经
     * {@link AcceptanceDriver} 访问（C3b-1：同包 package-private 直接访问 + 残余反射壳）——
     * 生产控制器不再为测试开放状态面，而当时 31 项（现 33 项）UI 断言覆盖的行为一字未改。</p>
     */
    protected final AcceptanceDriver.View view;

    FeatureExerciseSupport(MainController mc) {
        this.mc = mc;
        this.view = new AcceptanceDriver.View(mc);
    }
    // ===== D：参数编辑只有侧边栏一条路径（模态参数窗口壳已删） =====

    /** 最近一次「确认」交回的参数快照（null = 未确认 / 取消 —— 与旧 isConfirmed() 同义）。 */
    protected AppConfig lastPanelCommit;

    /**
     * 经<b>真实侧边栏路径</b>打开参数面板并返回表单本体（替代已删除的模态直驱入口）。
     *
     * <p>顺序与生产 {@code MainController.openParamDialog} 同：{@code ParamSidePanel.of(锚点)}
     * → {@code show(target, imageMode, textCache, imageCache, commit 回调)}。面板按窗口缓存，
     * 每次 show 的目标 / 模式与上次不同且干净时会 load() 重载初值；「确认」结果由 commit 回调
     * 存进 {@link #lastPanelCommit}，调用点随后经 view 应用到对应作用域。连续切换目标前
     * 面板必须已提交干净，否则 show 会弹 R1.6 丢弃草稿确认卡（回调异步，演练会静默错位）。</p>
     */
    protected ParamFormPane openSidePanel(boolean imageMode, ParamScope.Target target) {
        lastPanelCommit = null;
        ParamSidePanel panel = ParamSidePanel.of(statusLabel());
        panel.show(target, imageMode, this.view.textParamsCache(), this.view.imageParamsCache(),
                params -> lastPanelCommit = params);
        return panel.pane();
    }

    // ===== P7：控件读取全部走 view（C3b-1 起 AcceptanceDriver，同包直接访问 + 残余反射壳），不再要求 @FXML 字段放宽可见性 =====

    protected javafx.scene.control.ListView<Object> listView() {
        return view.fileListView();
    }

    protected javafx.scene.control.CheckBox adjustCheck() {
        return view.adjustCheck();
    }

    protected javafx.scene.control.Button previewEffectButton() {
        return view.previewEffectButton();
    }

    // ===== 按 fx:id 定位节点（步骤 1 阶段 C；P7 起不再要求 @FXML 字段有可见性） =====
    //
    // 历史：直接引用 mc.<fxmlField> 要求该字段在 MainController 中保持包级可见（即必须带
    // test-support 标记）。改用 Node.lookup("#fx:id") 后字段可收回 private，暴露面随之下降。
    // 实测（.claude-work 探针）：42 个 fx:id 中 41 个可解析，唯一例外 modeToggleGroup 是
    // ToggleGroup 而非 Node —— 它不在本类使用；且 lookup 对 visible=false / managed=false /
    // disabled=true 的节点同样有效。
    // P7：唯一改动是取 Scene 的那一步改走 listView()（经反射桥拿 ListView），
    // 定位本身仍是 lookup("#fx:id")，语义与实测结论都不变。

    /** 按 fx:id 定位节点。找不到即抛异常（不静默返回 null，避免把"节点没了"伪装成断言失败）。 */
    protected javafx.scene.Node node(String fxId) {
        // MainController 是普通 FXML 控制器（不是 Node），本身没有 getScene()；
        // 借一个必然挂进场景图的控件取 Scene。见 docs/handoff/H21.md §四。
        javafx.scene.Scene sc = listView().getScene();
        if (sc == null) {
            throw new IllegalStateException("场景尚未建立，无法按 fx:id 定位节点: " + fxId);
        }
        javafx.scene.Node n = sc.getRoot().lookup("#" + fxId);
        if (n == null) {
            throw new IllegalStateException("按 fx:id 未找到节点: #" + fxId);
        }
        return n;
    }

    /** 需要具体控件类型时用这个：把 lookup 结果按类型校验后返回。 */
    protected <T> T node(String fxId, Class<T> type) {
        javafx.scene.Node n = node(fxId);
        if (!type.isInstance(n)) {
            throw new IllegalStateException("#" + fxId + " 期望 " + type.getSimpleName()
                    + "，实际 " + n.getClass().getName());
        }
        return type.cast(n);
    }

    protected javafx.scene.control.ToggleButton textModeToggle() { return node("textModeToggle", javafx.scene.control.ToggleButton.class); }
    protected javafx.scene.control.ToggleButton imageModeToggle() { return node("imageModeToggle", javafx.scene.control.ToggleButton.class); }
    protected javafx.scene.control.TextField inputFolderField() { return node("inputFolderField", javafx.scene.control.TextField.class); }
    protected javafx.scene.control.TextField outputFolderField() { return node("outputFolderField", javafx.scene.control.TextField.class); }
    protected javafx.scene.control.CheckBox selectAllCheck() { return node("selectAllCheck", javafx.scene.control.CheckBox.class); }
    protected javafx.scene.control.Button removeButton() { return node("removeButton", javafx.scene.control.Button.class); }
    protected javafx.scene.control.Button clearParamsButton() { return node("clearParamsButton", javafx.scene.control.Button.class); }
    protected javafx.scene.control.Button undoButton() { return node("undoButton", javafx.scene.control.Button.class); }
    protected javafx.scene.control.Button groupButton() { return node("groupButton", javafx.scene.control.Button.class); }
    protected javafx.scene.control.Button ungroupButton() { return node("ungroupButton", javafx.scene.control.Button.class); }
    protected javafx.scene.control.Button applyGlobalButton() { return node("applyGlobalButton", javafx.scene.control.Button.class); }
    protected javafx.scene.control.TextField groupNameField() { return node("groupNameField", javafx.scene.control.TextField.class); }
    protected javafx.scene.control.ProgressBar progressBar() { return node("progressBar", javafx.scene.control.ProgressBar.class); }
    protected javafx.scene.control.Label statusLabel() { return node("statusLabel", javafx.scene.control.Label.class); }
    protected javafx.scene.control.Button startButton() { return node("startButton", javafx.scene.control.Button.class); }
    protected javafx.scene.control.Button stopButton() { return node("stopButton", javafx.scene.control.Button.class); }
    protected atlantafx.base.controls.ToggleSwitch openFolderAfterCompleteCheck() { return node("openFolderAfterCompleteCheck", atlantafx.base.controls.ToggleSwitch.class); }
/** 测试专用：在 FX 线程上排空事件队列（进入嵌套事件循环，处理完当前已排队回调后返回）。
 *  runAutomatedTests 本身运行在 FX 线程，普通 sleep 会阻塞事件回调（如提取 onSucceeded）无法执行 */
protected void pumpFxEvents() {
    Object key = new Object();
    Platform.runLater(() -> Platform.exitNestedEventLoop(key, null));
    Platform.enterNestedEventLoop(key);
}

/** 测试专用：等待在途的统一导入收尾（统一导入为后台异步，断言列表状态前必须先排空） */
protected void waitForImportDone() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 15000;
    while (this.view.isImporting() && System.currentTimeMillis() < deadline) {
        pumpFxEvents();
        Thread.sleep(20);
    }
}
/**
 * 在指定目录生成一个最小 MHT 文件（手写 MIME 结构，含 HTML 与一张内嵌 WebP），供演练与测试使用
 */
protected File createSampleMht(java.nio.file.Path dir, String fileName) throws java.io.IOException {
    byte[] webpBytes;
    try (java.io.InputStream is = MainController.class.getResourceAsStream("/sample-preview.webp")) {
        if (is == null) {
            throw new java.io.IOException(I18n.get("test.samplePreviewMissing"));
        }
        webpBytes = is.readAllBytes();
    }
    String boundary = "----=_preview_agent_boundary";
    StringBuilder sb = new StringBuilder();
    sb.append("From: <agent@example.com>\r\n");
    sb.append("Subject: preview sample\r\n");
    sb.append("MIME-Version: 1.0\r\n");
    sb.append("Content-Type: multipart/related; boundary=\"").append(boundary).append("\"\r\n\r\n");
    sb.append("--").append(boundary).append("\r\n");
    sb.append("Content-Type: text/html; charset=\"utf-8\"\r\n");
    sb.append("Content-Transfer-Encoding: 8bit\r\n\r\n");
    sb.append(I18n.get("test.previewHtml"))
            .append("<img src=\"cid:img001\"></body></html>\r\n");
    sb.append("--").append(boundary).append("\r\n");
    sb.append("Content-Type: image/webp\r\n");
    sb.append("Content-ID: <img001>\r\n");
    sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
    sb.append(java.util.Base64.getEncoder().encodeToString(webpBytes)).append("\r\n");
    sb.append("--").append(boundary).append("--\r\n");
    File f = dir.resolve(fileName).toFile();
    java.nio.file.Files.write(f.toPath(),
            sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return f;
}
}
