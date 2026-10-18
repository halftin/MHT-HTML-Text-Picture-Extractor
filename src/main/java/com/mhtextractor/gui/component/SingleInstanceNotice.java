package com.mhtextractor.gui.component;

import atlantafx.base.theme.NordLight;
import com.mhtextractor.i18n.Texts;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;

/** A short application notification before a duplicate GUI process exits. */
@Slf4j
public final class SingleInstanceNotice {
    private SingleInstanceNotice() {}

    public static void show(Stage stage) {
        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());
        StackPane shell = new StackPane();
        Scene scene = new Scene(shell, 480, 180);
        NotificationHost.install(scene, shell);
        stage.setTitle(Texts.get("app.title"));
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();
        NotificationHost.toastWarning(shell, Texts.get("app.alreadyRunning"));
        log.warn(Texts.msg("log.app.alreadyRunning"));
        PauseTransition exitDelay = new PauseTransition(Duration.seconds(3));
        exitDelay.setOnFinished(_ -> Platform.exit());
        exitDelay.play();
    }
}
