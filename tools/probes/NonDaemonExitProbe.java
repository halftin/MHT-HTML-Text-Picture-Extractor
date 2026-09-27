import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.Timer;
import java.util.TimerTask;

/**
 * Does the JVM exit when the last JavaFX window closes while a NON-daemon thread is still running?
 *
 * <p>This settles whether "确认卡片被替换后，卡在 future.join() 的提取线程" 会留下**残留进程**。
 * 主程序里提取线程是 `new Thread(task).start()`（非 daemon），导入线程才是 daemon。
 * 跑法：给它 60s；**若进程自己退出 = 无残留风险**，若超时未退 = 风险成立。</p>
 */
public class NonDaemonExitProbe extends Application {

    @Override
    public void start(Stage stage) {
        stage.setScene(new Scene(new StackPane(new Label("x")), 300, 200));
        stage.setTitle("NonDaemonExitProbe");
        stage.show();

        Thread hog = new Thread(() -> {
            try {
                Thread.sleep(600_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "non-daemon-hog");
        hog.start();
        System.out.println("started non-daemon hog; closing the last window in 1.5s...");

        new Timer(true).schedule(new TimerTask() {
            public void run() {
                Platform.runLater(stage::close);
            }
        }, 1500);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
