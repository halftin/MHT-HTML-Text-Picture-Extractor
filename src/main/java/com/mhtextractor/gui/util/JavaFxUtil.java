package com.mhtextractor.gui.util;

import javafx.application.Platform;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.function.Consumer;

/**
 * JavaFX UI 工具类
 */
public class JavaFxUtil {

    /**
     * 在 JavaFX 线程上执行 Runnable
     */
    public static void runLater(Runnable runnable) {
        Platform.runLater(runnable);
    }

    /**
     * 显示目录选择器
     */
    public static String showDirectoryChooser(Window owner, String title) {
        return showDirectoryChooser(owner, title, null);
    }

    /**
     * 显示目录选择器（支持指定初始目录）
     * @param owner 所有者窗口
     * @param title 标题
     * @param initialDirectory 初始目录路径，如果为 null 则使用系统默认位置
     */
    public static String showDirectoryChooser(Window owner, String title, String initialDirectory) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);

        // 如果提供了初始目录且该目录存在，则设置为初始位置
        if (initialDirectory != null && !initialDirectory.trim().isEmpty()) {
            File initDir = new File(initialDirectory);
            if (initDir.exists() && initDir.isDirectory()) {
                chooser.setInitialDirectory(initDir);
            }
        }

        File dir = chooser.showDialog(owner);
        return dir != null ? dir.getAbsolutePath() : null;
    }

    /**
     * 异步执行任务（不阻塞 UI 线程）
     */
    public static void runAsync(Runnable task, Consumer<Throwable> onError) {
        new Thread(() -> {
            try {
                task.run();
            } catch (Throwable e) {
                runLater(() -> onError.accept(e));
            }
        }).start();
    }

    /**
     * 判断是否在 JavaFX 线程上
     */
    public static boolean isFxThread() {
        return Platform.isFxApplicationThread();
    }
}
