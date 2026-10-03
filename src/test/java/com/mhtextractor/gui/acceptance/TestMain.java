package com.mhtextractor.gui.acceptance;

import atlantafx.base.theme.NordLight;
import com.mhtextractor.config.GuiState;
import com.mhtextractor.config.GuiStateManager;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.gui.component.SidePanelHost;
import com.mhtextractor.gui.controller.FeatureExercise;
import com.mhtextractor.gui.controller.MainController;
import com.mhtextractor.i18n.I18n;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.Locale;
import java.util.Objects;

/**
 * GUI 自动化的<b>测试侧入口</b>（P7：把 {@code -Dtest.mode} 从生产启动代码里拿掉）。
 *
 * <h2>为什么要有这个类</h2>
 * <p>P7 之前，自动化是这样起的：{@code java ... -Dtest.mode=true com.mhtextractor.App}，
 * 由 {@code App.start()} 里的一段 {@code "true".equals(System.getProperty("test.mode"))} 分支
 * 反射调用 {@code FeatureExercise.runAutomatedTests()}。那意味着<b>生产启动器里跑着测试逻辑</b>：
 * 一个开关、一个反射挂钩、一个 {@code CONTROLLER} 字段，还有 {@code MainController} 关窗路径上
 * 第二处 {@code test.mode} 旁路。</p>
 *
 * <p>P7 把这层耦合整个搬到测试源集：本类照 {@code App.start()} 的装配顺序<b>逐行等价</b>地起同一个
 * 真窗口（{@code MainView.fxml} + 同一个 {@code MainController} + 同一套 {@code SidePanelHost} /
 * {@code NotificationHost} 接线 + 同一套 AtlantaFX 主题与 {@code custom.css}），
 * 只是在窗口起来之后再驱动测试。{@code App} 因此恢复成纯生产启动器。</p>
 *
 * <h2>两种测试模式</h2>
 * <ul>
 *   <li>{@code -Dtest.mode=true}（默认）—— GUI 自动化：延迟 2s 后在 FX 线程跑
 *       {@code FeatureExercise.runAutomatedTests()}，把结果写 {@code logs/test-results.txt}，
 *       然后 {@code Platform.exit() + System.exit(0/1)}。<b>结果文件契约不变</b>：
 *       仍是 {@code === RESULTS: N passed, M failed ===} 行（{@code run-matrix.ps1} 校验它）。</li>
 *   <li>{@code -Dagent.exit.delay=<ms>} —— 追踪演练：跑 {@code exerciseAllFeatures()}，
 *       到点自尽。原先由 {@code App} 承载（native 可达性元数据采集用），P7 一并搬到这里。</li>
 * </ul>
 *
 * <p>⚠️ 它必须与 {@code target/classes} 一起出现在 classpath 上；<b>打进生产 jar 与 native 镜像
 * 的只有 {@code App}</b>，正式发行包不含本类。</p>
 *
 * <p>⚠️ 本类与 {@code App} 的装配是<b>有意重复</b>的：{@code App} 的装配顺序本身就是被测对象的一部分
 * （{@code NotificationHost} 的层序、{@code SidePanelHost} 的覆盖层都由它决定），
 * 把这段抽成共享工具会引入"测试跑的不是生产那段代码"的疑问。改 {@code App.start()} 的装配时，
 * 必须同步改这里。</p>
 */
public final class TestMain extends Application {

    /** 起窗口之后、开始驱动断言之前的稳定等待（毫秒）——与旧 {@code App} 的 2000ms 一致。 */
    private static final long AUTOMATION_DELAY_MS = 2000;

    /** 自动化总时限（毫秒）：超时强制退出 1，绝不允许测试 harness 永久挂住。 */
    private static final long AUTOMATION_WATCHDOG_MS = 300_000;

    private static String customCssUrl;

    @Override
    public void start(Stage primaryStage) throws Exception {
        // 与 App.start() 同：locale 必须在 FXMLLoader.load() 之前就绪
        GuiState guiState = GuiStateManager.load();
        if (guiState != null && guiState.getLocale() != null && !guiState.getLocale().isBlank()) {
            Locale locale = I18n.parseLocale(guiState.getLocale());
            if (locale != null) {
                I18n.setLocale(locale);
            }
        }

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/mhtextractor/gui/view/MainView.fxml"));
        loader.setResources(I18n.bundle());
        Parent root = loader.load();
        StackPane overlay = SidePanelHost.install(root);
        StackPane shell = new StackPane(overlay);
        Scene scene = new Scene(shell);
        NotificationHost.install(scene, shell);
        MainController controller = loader.getController();

        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());
        customCssUrl = Objects.requireNonNull(getClass().getResource("/custom.css")).toExternalForm();
        scene.getStylesheets().add(customCssUrl);

        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        primaryStage.setTitle(I18n.get("app.title"));
        primaryStage.setScene(scene);
        primaryStage.setWidth(screenBounds.getWidth() * 0.80);
        primaryStage.setHeight(screenBounds.getHeight() * 0.85);
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(650);
        primaryStage.centerOnScreen();
        primaryStage.show();

        String agentDelay = System.getProperty("agent.exit.delay");
        if (agentDelay != null) {
            FeatureExercise exercise = new FeatureExercise(controller);
            exercise.exerciseAllFeatures();
            new java.util.Timer(true).schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    Platform.exit();
                    System.exit(0);
                }
            }, Long.parseLong(agentDelay));
            return;
        }

        if (!"true".equals(System.getProperty("test.mode"))) {
            // 既没给测试开关也没给演练延迟：什么都不驱动（不是 GUI 自动化的启动方式）
            System.err.println("[TestMain] neither -Dtest.mode=true nor -Dagent.exit.delay=<ms> given"
                    + " - window is up but no test will run");
            return;
        }

        // 超时保护：自动化若挂住，进程必须自己退出，不能堵住复验流水线
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(AUTOMATION_WATCHDOG_MS);
            } catch (InterruptedException e) {
                return;
            }
            System.err.println("[TestMain] WATCHDOG: automated tests exceeded "
                    + AUTOMATION_WATCHDOG_MS + " ms - forcing exit 1");
            Runtime.getRuntime().halt(1);
        }, "test-mode-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        new java.util.Timer(true).schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                // 自动化测试直接操作 JavaFX 控件，必须回到 FX 线程执行
                Platform.runLater(() -> {
                    boolean success = new FeatureExercise(controller).runAutomatedTests();
                    Platform.exit();
                    System.exit(success ? 0 : 1);
                });
            }
        }, AUTOMATION_DELAY_MS);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
