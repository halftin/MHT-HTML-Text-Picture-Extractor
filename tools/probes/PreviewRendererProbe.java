import com.mhtextractor.gui.controller.PreviewRenderer;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uicore.PreviewRow;
import com.mhtextractor.uiport.*;
import javafx.application.Application;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import java.nio.file.*;
import java.util.*;

/** Real file decode and FX rendering; deliberately different backend names catch frontend recomputation. */
public class PreviewRendererProbe extends Application implements PreviewRenderer.Host {
    private final FlowPane thumbnails = new FlowPane();
    private final ScrollPane images = new ScrollPane(thumbnails);
    private final Label hint = new Label();
    private final Button effect = new Button();
    private final TextArea text = new TextArea();
    private int failures;
    @Override public void start(Stage stage) {
        try {
            var renderer = new PreviewRenderer(this);
            Path work = Files.createTempDirectory("mht-extractor-pf4-renderer-");
            byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aK1sAAAAASUVORK5CYII=");
            Path real = Files.write(work.resolve("cache.bin"), png);
            var row = new PreviewRow(new ItemVm(real.toString(), "source", true, false, 0, UpdateKind.NONE), ignored -> {});
            for (String name : List.of("f07_backend-name.png", "00001.png")) {
                var file = new PortEvent.ImageFile(work.resolve("not-the-image").toString(), png.length, "image/png",
                        "png", "backend-core.png", 1, 1, 0, name, new PortEvent.ImageFile.Dimensions(1, 1), real.toString());
                var decoded = renderer.decodeFile(file);
                check("PNG pixels nonempty", decoded.pixels() != null && decoded.width() > 0 && decoded.height() > 0);
                check("coreName is backend fact", "backend-core.png".equals(decoded.coreName()));
                row.cachedThumbs = List.of(decoded); row.cachedImages = List.of(file);
                renderer.renderThumbnails(row);
                check("presentation displayName " + name, label().equals(name));
                check("presentation thumbnail nonempty", box().getChildren().getFirst() instanceof ImageView view && view.getImage() != null);
                thumbnails.getChildren().clear();
                renderer.appendThumb(row, decoded, 0, file);
                check("event displayName " + name, label().equals(name));
                check("click handler retained", box().getOnMouseClicked() != null);
            }
            try (var input = PreviewRendererProbe.class.getResourceAsStream("/sample-preview.webp")) {
                Path webp = Files.write(work.resolve("webp-cache.bin"), Objects.requireNonNull(input).readAllBytes());
                var decoded = renderer.decodeFile(new PortEvent.ImageFile(webp.toString(), Files.size(webp), "image/webp",
                        "webp", "00001.webp", 1, 1, 0, "00001.webp"));
                check("WebP pixels nonempty", decoded.pixels() != null && decoded.width() > 0 && decoded.height() > 0);
                check("WebP backend coreName", "00001.webp".equals(decoded.coreName()));
            }
            var missing = renderer.decodeFile(new PortEvent.ImageFile(work.resolve("missing").toString(), 0,
                    "image/png", "png", "missing.png", 0, 0, 0));
            check("unreadable file placeholder", missing.pixels() == null && "missing.png".equals(missing.coreName()));
        } catch (Throwable error) { error.printStackTrace(); failures++; }
        System.out.println("PREVIEW-RENDERER failures=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
    private VBox box() { return (VBox) thumbnails.getChildren().getFirst(); }
    private String label() { return ((Label) box().getChildren().get(1)).getText(); }
    private void check(String name, boolean ok) { if (!ok) failures++; System.out.println((ok ? "PASS " : "FAIL ") + name); }
    public TextArea textPreviewArea() { return text; }
    public Label imageHintLabel() { return hint; }
    public ScrollPane imageScrollPane() { return images; }
    public FlowPane thumbnailPane() { return thumbnails; }
    public Button previewEffectButton() { return effect; }
    public AppConfig effectiveParams(Object item) { var config = new AppConfig(); config.setSingleOutputMode(true); return config; }
    public int visibleCount() { return 2; }
    public int unitIndex(Object item) { return 0; }
    public static void main(String[] args) { launch(args); }
}
