import com.mhtextractor.app.BackendWorkspace;
import com.mhtextractor.gui.component.GuiConfirmer;
import com.mhtextractor.gui.controller.MainController;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uicore.FrontendPort;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;

import java.util.concurrent.atomic.AtomicReference;

/** Explicit in-process probe assembly using the production business root. */
final class ProbeRuntime {
    private static final java.util.Map<FrontendPort, Owner> owners = new java.util.concurrent.ConcurrentHashMap<>();
    private record Owner(FrontendPort port, BackendWorkspace root) {
        void close() { port.close(); root.close(); }
    }

    private static Owner open() {
        var context = new AtomicReference<>(new AppConfig());
        var root = new BackendWorkspace(new GuiConfirmer(() -> javafx.stage.Window.getWindows().stream()
                .filter(window -> window.getScene() != null)
                .map(window -> window.getScene().lookup("#statusLabel"))
                .filter(java.util.Objects::nonNull).findFirst().orElse(null)), context::get, Runnable::run);
        var port = new FrontendPort(root.port(), Platform::runLater, context::set);
        port.bootstrap(context.get());
        var owner = new Owner(port, root);
        owners.put(port, owner);
        Runtime.getRuntime().addShutdownHook(new Thread(owner::close, "probe-backend-close"));
        return owner;
    }

    static FrontendPort port() { return open().port(); }

    static void close(FrontendPort port, Runnable finished) {
        Owner owner = owners.remove(port);
        Thread.ofPlatform().daemon().name("probe-backend-shutdown").start(() -> {
            if (owner != null) owner.close(); Platform.runLater(finished);
        });
    }

    static MainController controller(FXMLLoader loader) {
        FrontendPort port = port();
        return new MainController(loader, port, finished -> close(port, finished));
    }
}
