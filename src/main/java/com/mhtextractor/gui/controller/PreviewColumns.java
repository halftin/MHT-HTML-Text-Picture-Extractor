package com.mhtextractor.gui.controller;

import com.mhtextractor.uicore.ListColumnWidths;
import com.mhtextractor.uicore.GuiState;
import com.mhtextractor.uicore.GuiStateManager;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

/** One per-list column state, shared by the header and every virtualized cell. */
final class PreviewColumns {
    private static final Object KEY = new Object();
    private static final double STATUS_WIDTH = 48;
    // Reserve space for the vertical scroll bar even when the list is short.
    private static final double SCROLL_GUTTER = 18;
    private final ObjectProperty<ListColumnWidths> widths =
            new SimpleObjectProperty<>();
    private final DoubleBinding name;
    private final DoubleBinding source;
    private final Label badgeHeader;

    private PreviewColumns(ListView<?> list, HBox header) {
        Label badge = (Label) header.lookup("#badgeHeaderLabel");
        badgeHeader = badge;
        Label nameHeader = (Label) header.lookup("#nameColumnHeader");
        Label sourceHeader = (Label) header.lookup("#sourceColumnHeader");
        Label status = (Label) header.lookup("#statusColumnHeader");
        fixed(badge, 40);
        fixed((Region) header.lookup("#orderColumnHeader"), 40);
        fixed(status, STATUS_WIDTH);
        GuiState saved = GuiStateManager.load();
        if (saved != null && valid(saved.getNameColumnWidth()) && valid(saved.getSourceColumnWidth())) {
            widths.set(new ListColumnWidths(saved.getNameColumnWidth(), saved.getSourceColumnWidth()));
        }
        header.paddingProperty().bind(Bindings.createObjectBinding(() -> new Insets(2,
                10 + SCROLL_GUTTER + list.getInsets().getRight(), 2,
                10 + list.getInsets().getLeft()), list.insetsProperty()));
        ColumnWidthController controller = new ColumnWidthController(list, header);
        DoubleBinding fixedWidth = Bindings.createDoubleBinding(() ->
                header.getPadding().getLeft() + header.getPadding().getRight()
                + 40 + 40 + (badge.isManaged() ? 40 : 0) + STATUS_WIDTH
                + header.getSpacing() * (badge.isManaged() ? 5 : 4),
                header.paddingProperty(), badge.managedProperty(), header.spacingProperty());
        DoubleBinding available = Bindings.createDoubleBinding(() -> Math.max(0,
                controller.viewportWidth() - header.getPadding().getLeft() - header.getPadding().getRight()
                - 40 - 40 - (badge.isManaged() ? 40 : 0) - STATUS_WIDTH
                - header.getSpacing() * (badge.isManaged() ? 5 : 4)),
                controller.viewportBoundsProperty(), header.paddingProperty(), badge.managedProperty(), header.spacingProperty());
        name = Bindings.createDoubleBinding(() -> widths.get() == null
                ? available.get() * ListColumnWidths.DEFAULT.fileName()
                    / (ListColumnWidths.DEFAULT.fileName() + ListColumnWidths.DEFAULT.source())
                : widths.get().fileName(), available, widths);
        source = Bindings.createDoubleBinding(() -> widths.get() == null
                ? available.get() - name.get() : widths.get().source(), available, name, widths);
        bind(nameHeader, name);
        bind(sourceHeader, source);
        controller.install(nameHeader, sourceHeader, fixedWidth, name, source,
                (n, s) -> widths.set(new ListColumnWidths(n, s)), this::save);
    }

    static void install(ListView<?> list) {
        HBox header = (HBox) list.getParent().getParent().lookup("#previewColumnHeader");
        list.getProperties().put(KEY, new PreviewColumns(list, header));
    }

    static void bindCell(ListView<?> list, Label name, Label source, Label status) {
        PreviewColumns columns = (PreviewColumns) list.getProperties().get(KEY);
        bind(name, columns.name);
        bind(source, columns.source);
        fixed(status, STATUS_WIDTH);
    }

    static boolean hasBadgeColumn(ListView<?> list) {
        return ((PreviewColumns) list.getProperties().get(KEY)).badgeHeader.isManaged();
    }

    private static boolean valid(Double width) {
        return width != null && Double.isFinite(width) && width >= ColumnWidthController.MIN_WIDTH;
    }

    private void save() {
        if (widths.get() == null) return;
        GuiState state = GuiStateManager.load();
        if (state == null) state = new GuiState();
        state.setNameColumnWidth(widths.get().fileName());
        state.setSourceColumnWidth(widths.get().source());
        GuiStateManager.save(state);
    }

    private static void bind(Region region, DoubleBinding width) {
        region.minWidthProperty().bind(width);
        region.prefWidthProperty().bind(width);
        region.maxWidthProperty().bind(width);
    }

    private static void fixed(Region region, double width) {
        region.setMinWidth(width);
        region.setPrefWidth(width);
        region.setMaxWidth(width);
    }
}
