package com.mhtextractor;

import atlantafx.base.theme.NordLight;
import com.mhtextractor.uicore.GuiState;
import com.mhtextractor.uicore.GuiStateManager;
import com.mhtextractor.uicore.SingleInstanceLock;
import com.mhtextractor.gui.component.SingleInstanceNotice;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.gui.component.SidePanelHost;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.i18n.Texts;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

@Slf4j
public class App extends Application {

    private static String customCssUrl;

    @Override
    public void start(Stage primaryStage) throws IOException {
        if (!SingleInstanceLock.tryAcquire()) {
            SingleInstanceNotice.show(primaryStage);
            return;
        }

        // 全局未捕获异常处理器 — 将异常输出到控制台和日志
        Thread.currentThread().setUncaughtExceptionHandler((t, e) -> {
            log.error(I18n.msg("log.app.01", t.getName(), e.getMessage()), e);
            System.err.println("FATAL: " + e.getMessage());
            e.printStackTrace();
        });

        // 会话 13（i18n）：locale 必须在 FXMLLoader.load() 之前就绪——FXML 的 %key 解析发生在 load 期间
        GuiState guiState = GuiStateManager.load();
        if (guiState != null && guiState.getLocale() != null && !guiState.getLocale().isBlank()) {
            Locale locale = I18n.parseLocale(guiState.getLocale());
            if (locale != null) {
                I18n.setLocale(locale);
            }
        }

        var confirmer = new com.mhtextractor.gui.component.GuiConfirmer(() ->
                primaryStage.getScene() == null ? null : (javafx.scene.control.Label) primaryStage.getScene().lookup("#statusLabel"));
        com.mhtextractor.uiclient.UiportRuntime.start(I18n.currentLocale().toLanguageTag(), confirmer, Platform::runLater,
                runtime -> {
                    try { finishStart(primaryStage, runtime); }
                    catch (Exception error) { error.printStackTrace(); Platform.exit(); }
                }, error -> { error.printStackTrace(); Platform.exit(); });
    }

    private void finishStart(Stage primaryStage, com.mhtextractor.uiclient.UiportRuntime runtime) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/mhtextractor/gui/view/MainView.fxml"));
        // P6-B：控制器要拿"FXML 命名空间"（fx:id → MainViewRefs）来取代 42 个 @FXML 节点字段，
        // 而 namespace 只有 loader 有 —— controller factory 是把它交给控制器的入口（缺这根线就是
        // 指南记的"未验证"缺口）。它会覆盖 MainView.fxml 里的 fx:controller 实例化（工厂优先），
        // 但"注入 @FXML 成员 → 反射调用 initialize()"的时机与默认路径逐字相同
        // （javafx.fxml.FXMLLoader 源码 926-931 与 2555-2664 行），故 initialize() 里 namespace 已完整。
        // TestMain 里同一行是逐行等价的镜像（改一处必须改另一处）。
        loader.setControllerFactory(_ -> new com.mhtextractor.gui.controller.MainController(loader, runtime.port(), runtime::closeAsync));
        loader.setResources(Texts.bundle());
        // 会话 19：场景根外包一层 StackPane，供 NotificationHost 挂"右上角 toast + 居中提醒"两层
        //（取代模态 Alert；两条图层的 z 序与鼠标穿透见 NotificationHost 的类说明）
        // ⚠️ 必须先落到 Parent 局部变量再传：直接写 new StackPane(loader.load()) 会被推断成
        //    StackPane(Node...) 变参重载（load() 推成 Node[]），运行时 ClassCastException（实测踩过）。
        Parent root = loader.load();
        // Q6a（R3.2 层序）：主界面外包一层"覆盖层"，右侧参数侧边栏是它的第二个子节点 ——
        // 覆盖层再被包成 shell，而 NotificationHost.install 把 toast/模态两层【追加】到 shell 末尾，
        // 于是"主界面 → 侧边栏 → 提醒层"由父子结构唯一决定，不依赖 shell 的子节点顺序，
        // 也不需要用（更不许用）toFront 去提权。详见 SidePanelHost 的类注释。
        StackPane overlay = SidePanelHost.install(root);
        StackPane shell = new StackPane(overlay);
        Scene scene = new Scene(shell);
        NotificationHost.install(scene, shell);
        com.mhtextractor.gui.component.BackendConnectionPresenter.bind(runtime, scene.lookup("#statusLabel"));
        // 控制器由 FXMLLoader 装配（生产启动器<b>不</b>持有它的引用：P7 起没有任何测试模式分支，
        // 也不再需要"把控制器交给测试侧"这条通路 —— 那道通路已移到测试源集的 gui.acceptance.TestMain）

        // 引入 AtlantaFX Nord Light 主题
        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());

        // 注入自定义样式到所有窗口（包括 Dialog/Alert 创建的独立 Scene）
        customCssUrl = Objects.requireNonNull(getClass().getResource("/custom.css")).toExternalForm();
        scene.getStylesheets().add(customCssUrl);
        injectStylesToAllWindows();

        // 窗口初始大小自适应屏幕（宽度 80%：保证列表下方按钮行 7 个按钮完整显示；高度 85%）
        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        double winWidth  = screenBounds.getWidth()  * 0.80;
        double winHeight = screenBounds.getHeight() * 0.85;

        primaryStage.setTitle(I18n.get("app.title"));
        primaryStage.setScene(scene);
        com.mhtextractor.gui.controller.ParamSidePanel.of((javafx.scene.Node)scene.lookup("#statusLabel"), runtime.port());
        primaryStage.setWidth(winWidth);
        primaryStage.setHeight(winHeight);
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(650);
        primaryStage.centerOnScreen();
        primaryStage.show();
        log.info(I18n.msg("log.app.ready"));
        // 后端请求无响应时由 D1 通知流程处理，旧连接状态标签继续隐藏。
        var backendStatus = (javafx.scene.control.Label) scene.lookup("#backendStatusLabel");
        if (backendStatus != null) {
            backendStatus.setVisible(false);
            backendStatus.setManaged(false);
        }

        // P7（测试面收口）：本类**不再**有任何测试模式分支。
        // 演练（原 -Dagent.exit.delay）与 GUI 自动化（原 -Dtest.mode=true）都由测试源集的
        // com.mhtextractor.gui.acceptance.TestMain 承载 —— 它按与本方法逐行相同的装配起窗口，
        // 只是多一层"起完窗口后驱动 FeatureExercise"的测试外壳。生产启动代码不再读任何测试开关。
    }

    /**
     * 为所有现有和未来的窗口（含 Dialog/Alert 弹窗）注入自定义 CSS
     */
    private void injectStylesToAllWindows() {
        // 先为当前已有的所有窗口注入
        for (Window w : Window.getWindows()) {
            injectToWindow(w);
        }

        // 监听新窗口创建（Dialog/Alert 会创建新 Stage）
        Window.getWindows().addListener((ListChangeListener<Window>) c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    for (Window w : c.getAddedSubList()) {
                        injectToWindow(w);
                    }
                }
            }
        });
    }
    @SuppressWarnings("unchecked")
    private void injectToWindow(Window window) {
        if (!(window instanceof Stage stage)) return;
        Scene s = stage.getScene();
        if (s == null) {
            // 场景可能稍后设置，监听场景变化
            javafx.beans.value.ChangeListener<Scene>[] holder = new javafx.beans.value.ChangeListener[1];
            holder[0] = (_, _, newS) -> {
                if (newS != null) {
                    if (!newS.getStylesheets().contains(customCssUrl)) {
                        newS.getStylesheets().add(customCssUrl);
                    }
                    stage.sceneProperty().removeListener(holder[0]);
                }
            };
            stage.sceneProperty().addListener(holder[0]);
        } else {
            if (!s.getStylesheets().contains(customCssUrl)) {
                s.getStylesheets().add(customCssUrl);
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
