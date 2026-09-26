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

    /**
     * 已加载的主控制器（E0.6：供反射挂钩把控制器交给测试侧 FeatureExercise）。
     *
     * <p>此前通过 {@code loader.getController()} 就地取用；现在挂钩是静态方法，故保存为字段。
     * 正式运行（无 test.mode/agent.exit.delay）时该字段不被读取，属无害引用。</p>
     */
    private static Object CONTROLLER;

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
        loader.setResources(I18n.bundle());
        Scene scene = new Scene(loader.load());
        CONTROLLER = loader.getController();

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
        // 会话 18 · E0.6：演练/自动化测试代码已外迁到 test sources 的
        // com.mhtextractor.gui.controller.FeatureExercise，生产类不再承载测试脚手架。
        // 由于 main 源码不可依赖 test 源码，这里用"反射挂钩"调用：生产代码只保留一个
        // 类名常量与调用点（零编译期依赖），测试类由 test classpath 提供。
        String d = System.getProperty("agent.exit.delay");
        if (d != null) {
            invokeFeatureExercise("exerciseAllFeatures");
            new java.util.Timer(true).schedule(new java.util.TimerTask() {
                public void run() { Platform.exit(); System.exit(0); }
            }, Long.parseLong(d));
        }
        if ("true".equals(System.getProperty("test.mode"))) {
            new java.util.Timer(true).schedule(new java.util.TimerTask() {
                public void run() {
                    // 自动化测试直接操作 JavaFX 控件，必须回到 FX 线程执行
                    Platform.runLater(() -> {
                        boolean success = invokeFeatureExercise("runAutomatedTests");
                        Platform.exit();
                        System.exit(success ? 0 : 1);
                    });
                }
            }, 2000);
        }
    }

    /**
     * 反射调用测试侧的 UI 演练/自动化入口（E0.6）。
     *
     * <p><b>为什么用反射</b>：{@code FeatureExercise} 住在 {@code src/test/java}，而本文件在
     * {@code src/main/java} —— main 不能编译期依赖 test。反射把该依赖降为<b>运行期字符串</b>，
     * 同时让"生产类不再含测试脚手架"这一目标成立。</p>
     *
     * <p><b>契约</b>：目标方法必须是 {@code public static void} 且无参。
     * 若测试类不在 classpath（如正式发行包），记 warn 并返回 false，不影响正常启动。</p>
     *
     * @return true = 调用成功；false = 类/方法缺失或抛错（已记录）
     */
    private static boolean invokeFeatureExercise(String method) {
        try {
            Class<?> c = Class.forName("com.mhtextractor.gui.controller.FeatureExercise");
            // FeatureExercise 用组合持有控制器：构造参数为该包内可见的 MainController 类型
            Class<?> mcType = Class.forName("com.mhtextractor.gui.controller.MainController");
            Object driver = c.getConstructor(mcType).newInstance(CONTROLLER);
            c.getMethod(method).invoke(driver);
            return true;
        } catch (Throwable t) {
            log.error("FeatureExercise.{} 调用失败（测试类不在 classpath 或执行出错）", method, t);
            return false;
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
