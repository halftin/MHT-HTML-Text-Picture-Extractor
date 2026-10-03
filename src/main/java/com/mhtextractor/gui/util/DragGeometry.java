package com.mhtextractor.gui.util;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.paint.Color;

/**
 * 拖拽排序动画的<b>几何换算与快照参数</b>（P6-A 余下候选）：从 {@code MainController} 逐字搬出的两个纯函数。
 *
 * <h2>为什么值得独立成类</h2>
 * <p>搬迁前这两个成员住在 {@code MainController} 里，而 {@code transparentSnapParams()}
 * <b>还被另一个类用</b>（{@code PreviewListCell} 采集拖拽影像时同样要透明背景快照）——
 * 于是"控制器必须保持包级可见"这条封装让步，只为了让邻居借一个 {@code SnapshotParameters}。
 * 搬到本类后两个调用方都只依赖一个具名工具，控制器的那处包级放宽面可以收回去。</p>
 *
 * <h2>为什么 {@code cellBoundsInOverlay} 要显式收 overlay 实参</h2>
 * <p>搬迁前它是<b>实例方法</b>，方法体里读的是控制器的 {@code refs.dragOverlay()}。
 * 既然本类不许持有控制器实例（那只会把耦合换个地方住），覆盖层就<b>显式传入</b>：
 * {@code cellBoundsInOverlay(cell, overlay)}。语义与搬迁前逐字一致 ——
 * {@code cell} 的<b>场景</b>包围盒减去 overlay 原点在场景中的位置。</p>
 *
 * <h2>无头可测</h2>
 * <p>实测（P6-A，JavaFX 25.0.3，未启动 toolkit 的 JVM）：{@code new SnapshotParameters()}、
 * {@code Color.TRANSPARENT}、{@code BoundingBox}、{@code Pane}/{@code Region} 的构造与
 * {@code localToScene} 全部可用 —— 故这两个函数有<b>真正的无头单测</b>
 * （{@code DragGeometryTest}），不必把断言推到 GUI 探针。</p>
 */
public final class DragGeometry {

    private DragGeometry() {
    }

    /** 拖拽快照参数：透明填充（逐字搬自 {@code MainController.transparentSnapParams()}）。 */
    public static SnapshotParameters transparentSnapParams() {
        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        return sp;
    }

    /** 单元格在拖拽覆盖层坐标系中的位置 */
    public static Bounds cellBoundsInOverlay(Node cell, Parent overlay) {
        Bounds sceneBounds = cell.localToScene(cell.getBoundsInLocal());
        Point2D origin = overlay.localToScene(0, 0);
        return new BoundingBox(
                sceneBounds.getMinX() - origin.getX(),
                sceneBounds.getMinY() - origin.getY(),
                sceneBounds.getWidth(),
                sceneBounds.getHeight());
    }
}
