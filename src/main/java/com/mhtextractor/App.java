package com.mhtextractor;

import atlantafx.base.theme.NordLight;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public class App extends Application {

    @Override
    public void start(Stage primaryStage) throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/mhtextractor/gui/view/MainView.fxml"));
        Scene scene = new Scene(loader.load());

        // 引入 AtlantaFX Nord Light 主题
        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());
        // 引入自定义样式覆盖 ToggleSwitch 样式
        scene.getStylesheets().add(getClass().getResource("/custom.css").toExternalForm());

        primaryStage.setTitle("MHT 资源提取器 2.0");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(650);
        primaryStage.show();


    }

    public static void main(String[] args) {
        launch(args);
    }
}
