package com.mhtextractor;

import atlantafx.base.theme.NordLight;
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

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/mhtextractor/gui/view/MainView.fxml"));
        Scene scene = new Scene(loader.load());

        // 引入 AtlantaFX Nord Light 主题
        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());

        // 注入自定义样式到所有窗口（包括 Dialog/Alert 创建的独立 Scene）
        customCssUrl = getClass().getResource("/custom.css").toExternalForm();
        scene.getStylesheets().add(customCssUrl);
        injectStylesToAllWindows();

        // 窗口初始大小自适应屏幕70%，最小尺寸保持原有固定值
        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        double winWidth  = screenBounds.getWidth()  * 0.75;
        double winHeight = screenBounds.getHeight() * 0.85;

        primaryStage.setTitle("MHT 资源提取器");
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
