package com.mhtextractor.gui.util;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;

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
     * 判断是否在 JavaFX 线程上
     */
    public static boolean isFxThread() {
        return Platform.isFxApplicationThread();
    }

    /**
     * 创建平滑进度条动画属性。
     * 返回一个 DoubleProperty，当目标进度变化时，会在 200ms 内以缓动动画过渡到新值。
     * 使用方式：
     * <pre>
     * DoubleProperty smoothProgress = JavaFxUtil.createSmoothProgressProperty();
     * progressBar.progressProperty().bind(smoothProgress);
     * // 更新目标进度
     * JavaFxUtil.setSmoothProgress(smoothProgress, 0.5);
     * </pre>
     */
    public static DoubleProperty createSmoothProgressProperty() {
        return new SmoothProgressProperty(0.0);
    }

    /**
     * 设置平滑进度的目标值，自动触发动画过渡。
     */
    public static void setSmoothProgress(DoubleProperty property, double target) {
        if (property instanceof SmoothProgressProperty) {
            ((SmoothProgressProperty) property).setTarget(target);
        } else {
            property.set(target);
        }
    }

    /**
     * 停止平滑进度动画（用于任务取消或失败时清理）。
     */
    public static void stopSmoothProgress(DoubleProperty property) {
        if (property instanceof SmoothProgressProperty) {
            ((SmoothProgressProperty) property).stop();
        }
    }

    /**
     * 内部类：支持动画的 DoubleProperty，进度变化时带缓动过渡效果
     */
    private static class SmoothProgressProperty extends SimpleDoubleProperty {
        private Timeline timeline;

        SmoothProgressProperty(double initialValue) {
            super(initialValue);
        }

        void setTarget(double target) {
            double current = get();
            target = Math.max(0.0, Math.min(1.0, target));
            if (Math.abs(target - current) < 0.001) {
                return;
            }
            if (timeline != null) {
                timeline.stop();
            }
            timeline = new Timeline();
            timeline.getKeyFrames().addAll(
                    new KeyFrame(Duration.ZERO, new KeyValue(this, current)),
                    new KeyFrame(Duration.millis(200), new KeyValue(this, target,
                            javafx.animation.Interpolator.EASE_BOTH))
            );
            timeline.play();
        }

        void stop() {
            if (timeline != null) {
                timeline.stop();
                timeline = null;
            }
        }
    }
}
