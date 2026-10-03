package com.mhtextractor.gui.util;

import javafx.geometry.Bounds;
import javafx.scene.SnapshotParameters;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link DragGeometry} 的<b>无头</b>单测（P6-A：搬迁的主要收益之一）。
 *
 * <h2>"需要 toolkit 吗"——查证过，不需要</h2>
 * <p>按提示词要求先查证再决定"单测 vs GUI 探针"。用独立探针（{@code .claude-work/p6a/ToolkitProbe.java}，
 * <b>不</b>启动 {@code Platform}）在 JavaFX 25.0.3 上实测的原始输出：</p>
 * <pre>
 * toolkit-started=false
 * OK   | new SnapshotParameters()
 * OK   | new BoundingBox(1,2,3,4)
 * OK   | new Pane()
 * OK   | new Region()
 * OK   | Node.localToScene on Pane child (no Scene)
 *        sceneBounds=BoundingBox [minX:15.0, minY:25.0, ... width:30.0, height:40.0, ...]
 *        origin=Point2D [x = 10.0, y = 20.0]
 *        out=BoundingBox [minX:5.0, minY:5.0, ... width:30.0, height:40.0, ...]
 * OK   | geometry arithmetic with non-zero offsets
 * </pre>
 * <p>结论：{@code SnapshotParameters} / {@code Color} / {@code BoundingBox} / {@code Pane} / {@code Region}
 * 与 {@code localToScene} 全部可用 ⇒ <b>不需要</b>把断言推到 GUI 探针，也不需要启动 toolkit。</p>
 *
 * <p><b>本类的断言为什么不是"恒真的零"</b>：探针里 {@code out} 的 {@code minX/minY} 是 {@code 5.0}
 * 而不是 {@code 0.0}（cell 15/25 减 overlay 10/20），宽度是 30/40 —— 这些数字只有在
 * "先取场景包围盒、再减 overlay 原点"这条实现下才成立。若把实现改成"直接返回 {@code getBoundsInLocal}"
 * 或"减 cell 自己的 layoutX"，下面的断言会立刻变红。</p>
 */
class DragGeometryTest {

    @Test
    void transparentSnapParamsUseTransparentFillAndDefaultEverythingElse() {
        SnapshotParameters sp = DragGeometry.transparentSnapParams();
        assertSame(Color.TRANSPARENT, sp.getFill(), "拖拽影像必须透明背景（否则快照带白底，动画里是白块）");
        // 其余保持 JavaFX 默认（探针实测默认值：viewport/transform/camera = null，depthBuffer = false）
        assertNull(sp.getViewport());
        assertNull(sp.getTransform());
        assertNull(sp.getCamera());
        assertFalse(sp.isDepthBuffer());
    }

    /**
     * 每次调用必须是<b>新实例</b>。
     *
     * <p>为什么值得断言：调用点会各自改 viewport / 复用同一参数对象做多次快照；若实现改成共享静态实例，
     * 一处改动会串到另一处（且不报错、只表现为快照位置错乱）。</p>
     */
    @Test
    void transparentSnapParamsReturnAFreshInstanceEveryTime() {
        SnapshotParameters first = DragGeometry.transparentSnapParams();
        SnapshotParameters second = DragGeometry.transparentSnapParams();
        assertNotSame(first, second);
        first.setDepthBuffer(true);
        assertFalse(second.isDepthBuffer(), "改动一个实例不得影响另一个（共享实例会串味）");
        assertSame(Color.TRANSPARENT, second.getFill());
    }

    /** 覆盖层坐标系换算：单元格场景包围盒 − 覆盖层原点（非零偏移，避免"恒为零"的假通过）。 */
    @Test
    void cellBoundsAreRelativeToOverlayOrigin() {
        Pane overlay = new Pane();
        Region cell = new Region();
        // 与生产同形：cell 与 overlay 是同一父容器下的兄弟节点（overlay = refs.dragOverlay()）
        new Pane(overlay, cell);
        overlay.setLayoutX(10);
        overlay.setLayoutY(20);
        cell.setLayoutX(15);
        cell.setLayoutY(25);
        cell.resize(30, 40);

        Bounds b = DragGeometry.cellBoundsInOverlay(cell, overlay);
        assertEquals(5.0, b.getMinX(), 1e-9, "场景 15 − 覆盖层原点 10");
        assertEquals(5.0, b.getMinY(), 1e-9, "场景 25 − 覆盖层原点 20");
        assertEquals(30.0, b.getWidth(), 1e-9);
        assertEquals(40.0, b.getHeight(), 1e-9);
    }

    /**
     * 本函数存在的理由（对共同祖先的<b>平移</b>免疫）：{@code overlay} 与 {@code cell} 一起被平移时，
     * <b>相对</b>结果不变 —— 这正是"减去 overlay 原点"而不是"直接返回场景坐标"的价值
     * （列表整体滚动/窗口移动时，拖拽影像的落点仍然算得对）。
     */
    @Test
    void resultIsInvariantToTranslationOfTheCommonAncestor() {
        Pane overlay = new Pane();
        Region cell = new Region();
        Pane root = new Pane(overlay, cell);
        overlay.setLayoutX(10);
        overlay.setLayoutY(20);
        cell.setLayoutX(15);
        cell.setLayoutY(25);
        cell.resize(30, 40);

        Bounds before = DragGeometry.cellBoundsInOverlay(cell, overlay);
        // 共同祖先平移：两边都被同等平移 ⇒ 相对关系不变（缩放不满足这条，见下一个测试）
        root.setTranslateX(123);
        root.setTranslateY(-45);
        Bounds after = DragGeometry.cellBoundsInOverlay(cell, overlay);

        assertEquals(before.getMinX(), after.getMinX(), 1e-9);
        assertEquals(before.getMinY(), after.getMinY(), 1e-9);
        assertEquals(before.getWidth(), after.getWidth(), 1e-9);
        assertEquals(before.getHeight(), after.getHeight(), 1e-9);
        System.out.println("[draggeometry] 共同祖先平移 " + 123 + "/" + (-45)
                + " 前后的相对包围盒一致：" + before);
    }

    /**
     * 共同祖先<b>缩放</b>时，相对结果按同一比例缩放（<b>不是</b>不变）。
     *
     * <p>⚠️ 本断言的第一版是错的：我原以为缩放也会抵消（像平移那样）。
     * 实测 {@code root.setScaleX(2)} 后 {@code minX} 从 5.0 变成 10.0 ⇒ <b>缩放不抵消</b>，
     * 而这是<b>正确</b>行为：等比缩放（无论支点在哪）会把坐标差乘上该比例，
     * 于是"覆盖层坐标系里的偏移"按同一比例放大 —— 拖拽影像的落点换算因此仍然正确。
     * 断言改为钉住这条真性质（比例关系），而不是我原先猜的"不变"。</p>
     */
    @Test
    void resultScalesWithTheCommonAncestor() {
        Pane overlay = new Pane();
        Region cell = new Region();
        Pane root = new Pane(overlay, cell);
        overlay.setLayoutX(10);
        overlay.setLayoutY(20);
        cell.setLayoutX(15);
        cell.setLayoutY(25);
        cell.resize(30, 40);

        Bounds before = DragGeometry.cellBoundsInOverlay(cell, overlay);
        root.setScaleX(2);
        root.setScaleY(3);
        Bounds after = DragGeometry.cellBoundsInOverlay(cell, overlay);

        // 等比缩放把"坐标差"乘上缩放因子（与支点位置无关）
        assertEquals(before.getMinX() * 2, after.getMinX(), 1e-9);
        assertEquals(before.getMinY() * 3, after.getMinY(), 1e-9);
        assertEquals(before.getWidth() * 2, after.getWidth(), 1e-9);
        assertEquals(before.getHeight() * 3, after.getHeight(), 1e-9);
        System.out.println("[draggeometry] 共同祖先缩放 (2,3)：相对包围盒 " + before + " → " + after);
    }

    /** 覆盖层原点处时，结果就等于单元格自身的<b>场景</b>包围盒（退化情形，与定义一致）。 */
    @Test
    void withOverlayAtOriginResultEqualsSceneBounds() {
        Pane overlay = new Pane();
        Region cell = new Region();
        new Pane(overlay, cell);
        cell.setLayoutX(7);
        cell.setLayoutY(9);
        cell.resize(11, 13);

        Bounds scene = cell.localToScene(cell.getBoundsInLocal());
        Bounds b = DragGeometry.cellBoundsInOverlay(cell, overlay);
        assertEquals(scene.getMinX(), b.getMinX(), 1e-9);
        assertEquals(scene.getMinY(), b.getMinY(), 1e-9);
        assertEquals(scene.getWidth(), b.getWidth(), 1e-9);
        assertEquals(scene.getHeight(), b.getHeight(), 1e-9);
    }
}
