import atlantafx.base.controls.ModalPane;
import atlantafx.base.controls.Notification;
import atlantafx.base.theme.NordLight;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mhtextractor.App;
import com.mhtextractor.uiclient.BackendSession;
import com.mhtextractor.i18n.Texts;
import com.mhtextractor.gui.component.BackendConnectionPresenter;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.uiport.ConfirmRequest;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Real JVM companion + public GUI collaborators, no test-source classes or private access. */
public final class BackendConnectionProbe extends Application {
    private static final ObjectMapper JSON = new ObjectMapper();
    private Stage stage;
    private StackPane shell;
    private Label status;
    private Path root;
    private int checks;

    @Override public void start(Stage stage) throws Exception {
        this.stage = stage;
        root = Files.createTempDirectory(Path.of("H:/TEST/mht"), "p4-17-gui-");
        System.setProperty("mht.backend.executable", root.resolve("missing.exe").toString());
        I18n.setLocale(I18n.parseLocale(System.getProperty("probe.locale", "en_US")));
        System.setProperty("mht.uiport", "ipc");
        System.setProperty("mht.backend.transport", "jvm");
        // 生产 App 接线使用真实 JVM IPC；不可用连接仍由下方 BackendSession 场景覆盖。
        new App().start(stage);
        Platform.setImplicitExit(false);
        Thread.ofPlatform().name("backend-gui-probe").daemon(true).start(() -> {
            try {
                await(() -> stage.getScene() != null && stage.isShowing()
                        && !((Label) stage.getScene().lookup("#backendStatusLabel")).isVisible(), 45);
                fx(() -> {
                    check("production-ipc-window-ready", stage.isShowing());
                    check("production-feature-ready", !stage.getScene().lookup("#textModeToggle").isDisabled());
                    check("production-backend-label-hidden",
                            !((Label) stage.getScene().lookup("#backendStatusLabel")).isVisible());
                    check("production-no-backend-toast",
                            stage.getScene().getRoot().lookupAll(".notification").isEmpty());
                    javafx.event.Event.fireEvent(stage, new javafx.stage.WindowEvent(stage,
                            javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
                });
                await(() -> !stage.isShowing(), 30);
                fx(() -> {
                    stage.setOnCloseRequest(null);
                    status = new Label();
                    shell = new StackPane(new VBox(status));
                    Scene scene = new Scene(shell, 850, 550);
                    NotificationHost.install(scene, shell);
                    stage.setScene(scene);
                    Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());
                    scene.getStylesheets().add(App.class.getResource("/custom.css").toExternalForm());
                    stage.show();
                });
                for (String mode : List.of("answer", "replace", "timeout", "disconnect", "exit")) run(mode);
                fx(() -> {
                    var decision = new java.util.concurrent.atomic.AtomicReference<String>();
                    var deliveries = new java.util.concurrent.atomic.AtomicInteger();
                    Runnable cancel = new com.mhtextractor.gui.component.GuiConfirmer(() -> shell).confirmAsync(
                            new ConfirmRequest("import.quotaReached", java.util.Map.of("groups", "2", "files", "17",
                                    "accumulatedGroups", "4", "accumulatedFiles", "29"), List.of("continue", "stop"), 0),
                            choice -> { decision.set(choice); deliveries.incrementAndGet(); });
                    shell.applyCss();
                    shell.layout();
                    String message = ((Notification) modal().getContent()).getMessage();
                    check("quota-named-current-parameters", message.contains(Texts.get("import.quotaReached", "groups", "2", "files", "17")));
                    check("quota-named-accumulated-parameters", message.contains(Texts.get("import.quotaStop", "groups", "4", "files", "29")));
                    check("quota-no-unrendered-parameters", !message.contains("{files}") && !message.contains("{groups}"));
                    button(true).fire();
                    check("quota-continue-choice", "continue".equals(decision.get()));
                    cancel.run();
                    check("quota-answer-remains-once", "continue".equals(decision.get()) && deliveries.get() == 1);
                });
                // Direct stale-handle control: cancellation must not touch a newer local card.
                fx(() -> {
                    Runnable stale = NotificationHost.confirmCancellable(shell, Texts.get("notification.ok"),
                            org.kordamp.ikonli.fontawesome5.FontAwesomeSolid.QUESTION_CIRCLE, ignored -> { });
                    NotificationHost.confirm(shell, Texts.get("notification.cancel"), ignored -> { });
                    stale.run();
                    check("stale-dismiss-keeps-new-card", NotificationHost.hasPendingDecision(shell));
                    button(false).fire();
                });
                System.out.println("BACKEND-GUI RESULTS: " + checks + " passed, 0 failed; locale=" + Texts.currentLocale());
                fx(() -> stage.hide());
                Platform.exit();
            } catch (Throwable error) {
                error.printStackTrace();
                System.out.println("BACKEND-GUI RESULTS: FAILED " + error);
                Platform.exit();
                System.exit(1);
            }
        });
    }

    private void run(String mode) throws Exception {
        Path work = Files.createDirectories(root.resolve(mode));
        Path source = deepSource(work);
        var states = new LinkedBlockingQueue<BackendSession.State>();
        var questions = new LinkedBlockingQueue<String>();
        var ends = new LinkedBlockingQueue<BackendSession.End>();
        var acks = new LinkedBlockingQueue<Boolean>();
        BackendConnectionPresenter presenter = new BackendConnectionPresenter(status, shell);
        BackendSession.View view = new BackendSession.View() {
            public void statusChanged(BackendSession.Status update) {
                presenter.statusChanged(update);
                states.add(update.state());
                System.out.println("GUI STATE mode=" + mode + " " + update);
            }
            public void confirmationRequested(String id, ConfirmRequest request, Consumer<String> answer) {
                presenter.confirmationRequested(id, request, answer);
                shell.applyCss();
                shell.layout();
                check("card-rendered-" + mode, NotificationHost.hasPendingDecision(shell));
                check("card-visible-" + mode, modal().isDisplay() && modal().getContent().getBoundsInParent().getHeight() > 0);
                check("card-has-composite-content-" + mode,
                        ((Notification) modal().getContent()).getMessage().contains(Texts.get("alert.recursionDepthSkip")));
                check("no-default-button-" + mode, modal().lookupAll(".button").stream()
                        .filter(Button.class::isInstance).map(Button.class::cast).noneMatch(Button::isDefaultButton));
                System.out.println("GUI CARD id=" + id + " key=" + request.key() + " timeoutMs=" + request.timeoutMs());
                var snapshot = shell.snapshot(null, null);
                var bitmap = new java.awt.image.BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(),
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
                snapshot.getPixelReader().getPixels(0, 0, bitmap.getWidth(), bitmap.getHeight(),
                        javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, bitmap.getWidth());
                bitmap.setRGB(0, 0, bitmap.getWidth(), bitmap.getHeight(), pixels, 0, bitmap.getWidth());
                try { javax.imageio.ImageIO.write(bitmap, "png", work.resolve("card-" + id + ".png").toFile()); }
                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                questions.add(id);
            }
            public void confirmationFinished(String id, BackendSession.End reason) {
                presenter.confirmationFinished(id, reason);
                System.out.println("GUI FINISH id=" + id + " reason=" + reason);
                ends.add(reason);
            }
            public void confirmationAnswerReceived(String id, boolean accepted) {
                presenter.confirmationAnswerReceived(id, accepted);
                System.out.println("BACKEND ANSWER ACK id=" + id + " accepted=" + accepted);
                acks.add(accepted);
            }
        };
        Set<Long> before = ProcessHandle.current().descendants().map(ProcessHandle::pid).collect(Collectors.toSet());
        try (var session = new BackendSession(Platform::runLater, view)) {
            session.start(() -> {
                try { return command(work); } catch (Exception e) { throw new java.io.IOException(e); }
            }, java.time.Duration.ofSeconds(10));
            state(states, BackendSession.State.CONNECTED);
            ProcessHandle peer = ProcessHandle.current().descendants().filter(p -> !before.contains(p.pid())).findFirst().orElseThrow();
            session.request("importPaths", JSON.createObjectNode().set("paths", JSON.valueToTree(List.of(source.toString()))))
                    .get(5, TimeUnit.SECONDS);
            var extract = session.request("extract", JSON.createObjectNode().put("checkedOnly", false)
                    .put("outputFolder", work.resolve("output").toString()));
            String first = questions.poll(5, TimeUnit.SECONDS);
            check("received-first-" + mode, first != null);
            long start = System.nanoTime();
            if (mode.equals("disconnect") || mode.equals("exit")) {
                if (mode.equals("disconnect")) session.close(); else peer.destroyForcibly();
                check("disconnect-cancel-" + mode, ends.poll(2, TimeUnit.SECONDS) == BackendSession.End.DISCONNECTED);
                if (mode.equals("exit")) state(states, BackendSession.State.DISCONNECTED);
                fx(() -> check("disconnect-removes-card-" + mode, !NotificationHost.hasPendingDecision(shell)));
                check("disconnect-releases-request-" + mode, extract.isCompletedExceptionally());
            } else {
                if (mode.equals("answer")) {
                    fx(() -> button(!Boolean.getBoolean("probe.cg.cancel")).fire());
                    check("continue-suppresses-next-question", questions.poll(2, TimeUnit.SECONDS) == null);
                }
                else if (mode.equals("replace")) {
                    fx(() -> NotificationHost.error(shell, Texts.get("notification.ok")));
                    check("replacement-delivered-cancel", ends.poll(2, TimeUnit.SECONDS) == BackendSession.End.ANSWERED);
                } else {
                    check("server-30s-timeout", ends.poll(33, TimeUnit.SECONDS) == BackendSession.End.TIMEOUT);
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    check("server-timeout-duration", elapsed >= 28_000 && elapsed < 33_000);
                    System.out.println("SERVER 30S EXPIRY elapsedMs=" + elapsed);
                }
                if (!mode.equals("answer")) {
                    check("cancel-causes-next-question-" + mode, questions.poll(5, TimeUnit.SECONDS) != null);
                    fx(() -> button(true).fire());
                }
                var result = extract.get(5, TimeUnit.SECONDS);
                System.out.println("BACKEND EXTRACT RESULT mode=" + mode + " " + result);
                check("backend-terminal-" + mode, result.path("end").asText().equals("SUCCEEDED"));
                check("no-extra-question-" + mode, questions.isEmpty());
                check("backend-received-answer-" + mode, Boolean.TRUE.equals(acks.poll(3, TimeUnit.SECONDS)));
                fx(() -> {
                    check("card-cleared-" + mode, !NotificationHost.hasPendingDecision(shell));
                    check("status-reset-" + mode, status.getText().equals(Texts.get("backend.status.connected")));
                    if (mode.equals("timeout")) check("timeout-toast-visible", shell.lookupAll(".notification").stream()
                            .filter(Notification.class::isInstance).map(Notification.class::cast)
                            .anyMatch(n -> n.getMessage().equals(Texts.get("backend.confirmTimeout"))));
                });
            }
            session.close();
            peer.onExit().get(7, TimeUnit.SECONDS);
            check("peer-exited-" + mode, !peer.isAlive());
        }
    }

    private ProcessBuilder command(Path work) throws Exception {
        String cp = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !new File(entry).getName().matches("(?i)(javafx|atlantafx|ikonli).*"))
                .map(entry -> Path.of(entry).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        var command = List.of(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                "--limit-modules", "java.se", "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                "-cp", cp, "com.mhtextractor.backend.BackendCli", "--serve", "--locale", Texts.currentLocale().getLanguage());
        Files.write(work.resolve("command.txt"), command);
        return new ProcessBuilder(command).directory(work.toFile()).redirectError(work.resolve("stderr.txt").toFile());
    }

    private Path deepSource(Path work) throws Exception {
        String leaf = "Content-Type: text/html; charset=utf-8\r\n\r\n<html><body>probe</body></html>";
        String body = "Content-Type: multipart/mixed; boundary=leaf\r\n\r\n--leaf\r\n" + leaf
                + "\r\n--leaf\r\n" + leaf + "\r\n--leaf--\r\n";
        for (int i = 98; i >= 0; i--) body = "Content-Type: multipart/mixed; boundary=b" + i
                + "\r\n\r\n--b" + i + "\r\n" + body + "\r\n--b" + i + "--\r\n";
        Path source = work.resolve("deep.mht");
        Files.writeString(source, "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=outer\r\n\r\n--outer\r\n"
                + body + "\r\n--outer--\r\n");
        return source;
    }

    private ModalPane modal() { return (ModalPane) shell.lookup(".modal-pane"); }
    private Button button(boolean ok) {
        modal().getContent().applyCss();
        return modal().getContent().lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b -> b.getText().equals(Texts.get(ok ? "notification.ok" : "notification.cancel"))).findFirst().orElseThrow();
    }
    private void state(LinkedBlockingQueue<BackendSession.State> states, BackendSession.State wanted) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) if (states.poll(1, TimeUnit.SECONDS) == wanted) return;
        throw new AssertionError("Missing state " + wanted);
    }
    private void check(String name, boolean pass) {
        if (!pass) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }
    private void fx(Runnable action) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try { action.run(); done.complete(null); } catch (Throwable e) { done.completeExceptionally(e); }
        });
        done.get(5, TimeUnit.SECONDS);
    }
    private void await(java.util.function.BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            var result = new CompletableFuture<Boolean>();
            Platform.runLater(() -> result.complete(condition.getAsBoolean()));
            if (result.get(5, TimeUnit.SECONDS)) return;
            Thread.sleep(25);
        }
        throw new AssertionError("GUI condition timed out");
    }
    public static void main(String[] args) { launch(args); }
}
