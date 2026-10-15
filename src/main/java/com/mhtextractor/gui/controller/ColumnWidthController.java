package com.mhtextractor.gui.controller;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.BiConsumer;

/** Shared horizontal viewport preserves header/cell alignment without skin-specific offsets. */
final class ColumnWidthController {
    static final double MIN_WIDTH = 64;
    private final ScrollPane scroll = new ScrollPane();
    private final VBox content;

    ColumnWidthController(ListView<?> list, HBox header) {
        StackPane listArea = (StackPane) list.getParent();
        VBox parent = (VBox) listArea.getParent();
        int index = parent.getChildren().indexOf(header);
        parent.getChildren().removeAll(header, listArea);
        content = new VBox(parent.getSpacing(), header, listArea);
        content.setMinHeight(0);
        VBox.setVgrow(listArea, Priority.ALWAYS);
        scroll.setId("previewHorizontalScroll");
        scroll.setContent(content);
        scroll.setFitToWidth(false);
        scroll.setFitToHeight(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // Keep a usable row and horizontal bar in the smallest supported window.
        scroll.setMinSize(0, 80);
        scroll.setStyle("-fx-padding: 0; -fx-background-insets: 0; -fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        parent.getChildren().add(index, scroll);
    }

    double viewportWidth() { return scroll.getViewportBounds().getWidth(); }
    ReadOnlyObjectProperty<Bounds> viewportBoundsProperty() { return scroll.viewportBoundsProperty(); }

    void install(Label nameHeader, Label sourceHeader, DoubleBinding fixed,
                 DoubleBinding name, DoubleBinding source, BiConsumer<Double, Double> resize, Runnable save) {
        DoubleBinding total = Bindings.createDoubleBinding(
                () -> Math.max(viewportWidth(), fixed.get() + name.get() + source.get()),
                scroll.viewportBoundsProperty(), fixed, name, source);
        content.minWidthProperty().bind(total);
        content.prefWidthProperty().bind(total);
        content.maxWidthProperty().bind(total);
        handle(nameHeader, "nameColumnResize", true, name, source, resize, save);
        handle(sourceHeader, "sourceColumnResize", false, name, source, resize, save);
    }

    private void handle(Label label, String id, boolean fileName, DoubleBinding name, DoubleBinding source,
                        BiConsumer<Double, Double> resize, Runnable save) {
        HBox header = (HBox) label.getParent();
        int index = header.getChildren().indexOf(label);
        header.getChildren().remove(label);
        Region grip = new Region();
        grip.setId(id);
        grip.setMinWidth(5);
        grip.setPrefWidth(5);
        grip.setMaxWidth(5);
        grip.setCursor(Cursor.H_RESIZE);
        grip.setStyle("-fx-border-color: transparent -color-fg-muted transparent transparent; -fx-border-width: 0 1 0 0;");
        StackPane slot = new StackPane(label, grip);
        StackPane.setAlignment(label, Pos.CENTER_LEFT);
        StackPane.setAlignment(grip, Pos.CENTER_RIGHT);
        slot.minWidthProperty().bind(label.prefWidthProperty());
        slot.prefWidthProperty().bind(label.prefWidthProperty());
        slot.maxWidthProperty().bind(label.prefWidthProperty());
        header.getChildren().add(index, slot);
        double[] start = new double[3];
        grip.setOnMousePressed(event -> {
            start[0] = event.getScreenX();
            start[1] = name.get();
            start[2] = source.get();
            event.consume();
        });
        grip.setOnMouseDragged(event -> {
            double delta = event.getScreenX() - start[0];
            resize.accept(fileName ? Math.max(MIN_WIDTH, start[1] + delta) : start[1],
                    fileName ? start[2] : Math.max(MIN_WIDTH, start[2] + delta));
            event.consume();
        });
        grip.setOnMouseReleased(event -> { save.run(); event.consume(); });
    }
}
