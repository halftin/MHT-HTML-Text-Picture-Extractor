package com.mhtextractor;

import atlantafx.base.theme.NordLight;
import com.mhtextractor.config.GuiState;
import com.mhtextractor.config.GuiStateManager;
import com.mhtextractor.i18n.I18n;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Locale;

@Slf4j
public class App extends Application {

    private static String customCssUrl;

    @Override
    public void start(Stage primaryStage) throws IOException {
        // 全局未捕获异常处理器 — 将异常输出到控制台和日志
        Thread.currentThread().setUncaughtExceptionHandler((t, e) -> {
            log.error("未捕获异常 [{}]: {}", t.getName(), e.getMessage(), e);
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

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/mhtextractor/gui/view/MainView.fxml"));
        Scene scene = new Scene(loader.load());

        // 引入 AtlantaFX Nord Light 主题
        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());

        // 注入自定义样式到所有窗口（包括 Dialog/Alert 创建的独立 Scene）
        customCssUrl = getClass().getResource("/custom.css").toExternalForm();
        scene.getStylesheets().add(customCssUrl);
        injectStylesToAllWindows();

        // 窗口初始大小自适应屏幕（宽度 80%：保证列表下方按钮行 7 个按钮完整显示；高度 85%）
        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        double winWidth  = screenBounds.getWidth()  * 0.80;
        double winHeight = screenBounds.getHeight() * 0.85;

        primaryStage.setTitle(I18n.get("app.title"));
        primaryStage.setScene(scene);
        primaryStage.setWidth(winWidth);
        primaryStage.setHeight(winHeight);
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(650);
        primaryStage.centerOnScreen();
        primaryStage.show();

        // Tracing Agent 模式
        String d = System.getProperty("agent.exit.delay");
        if (d != null) {
            com.mhtextractor.gui.controller.MainController c = loader.getController();
            try { c.exerciseAllFeatures(); } catch (Exception ignored) {}
            new java.util.Timer(true).schedule(new java.util.TimerTask() {
                public void run() { Platform.exit(); System.exit(0); }
            }, Long.parseLong(d));
        }
        if ("true".equals(System.getProperty("test.mode"))) {
            final com.mhtextractor.gui.controller.MainController c = loader.getController();
            new java.util.Timer(true).schedule(new java.util.TimerTask() {
                public void run() {
                    // 自动化测试直接操作 JavaFX 控件，必须回到 FX 线程执行
                    Platform.runLater(() -> {
                        boolean success = true;
                        try {
                            c.runAutomatedTests();
                        } catch (Throwable t) {
                            success = false;
                            log.error("自动化测试执行失败", t);
                        }
                        Platform.exit();
                        System.exit(success ? 0 : 1);
                    });
                }
            }, 2000);
        }
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
            holder[0] = (obs, oldS, newS) -> {
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
