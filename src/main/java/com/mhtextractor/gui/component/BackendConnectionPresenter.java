package com.mhtextractor.gui.component;

import com.mhtextractor.app.BackendSession;
import com.mhtextractor.app.Texts;
import com.mhtextractor.app.UiportRuntime;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import com.mhtextractor.uiport.ConfirmRequest;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/** View-only companion status and confirmation wiring, independent of MainController's business port. */
@Slf4j
public final class BackendConnectionPresenter implements BackendSession.View {
    private final Label status;
    private final Node anchor;
    private final GuiConfirmer confirmer;
    private String activeId;
    private Runnable dismiss;
    private boolean deciding,rebuilding;

    /** Request failures drive D1. Connection status notifications never open this flow. */
    public static BackendConnectionPresenter bind(UiportRuntime runtime,Node anchor) {
        var presenter=new BackendConnectionPresenter(null,anchor);
        runtime.onUnavailable(()->presenter.requestUnavailable(runtime::rebuild));
        return presenter;
    }

    public void requestUnavailable(Consumer<Consumer<Boolean>> rebuild) {
        if(!Platform.isFxApplicationThread()){Platform.runLater(()->requestUnavailable(rebuild));return;}
        if(deciding||rebuilding)return;
        log.error(Texts.msg("log.backend.unavailable"));
        deciding=true;
        NotificationHost.confirmYesNo(anchor,Texts.get("backend.rebuild.confirm"),accepted->{
            deciding=false;
            if(!accepted){NotificationHost.error(anchor,Texts.get("backend.rebuild.declined"),FontAwesomeSolid.EXCLAMATION_CIRCLE);return;}
            rebuilding=true;
            var delivered=new java.util.concurrent.atomic.AtomicBoolean();
            Consumer<Boolean> finished=success->{
                Runnable update=()->{
                    if(!delivered.compareAndSet(false,true))return;
                    rebuilding=false;
                    if(!success)NotificationHost.error(anchor,Texts.get("backend.rebuild.failed"),FontAwesomeSolid.EXCLAMATION_CIRCLE);
                };
                if(Platform.isFxApplicationThread())update.run();else Platform.runLater(update);
            };
            try{rebuild.accept(finished);}catch(RuntimeException error){finished.accept(false);}
        });
    }

    public BackendConnectionPresenter(Label status, Node anchor) {
        this.status = status;
        this.anchor = anchor;
        confirmer = new GuiConfirmer(() -> anchor);
    }

    public static BackendSession install(Stage stage, Label status, Node anchor) {
        var presenter = new BackendConnectionPresenter(status, anchor);
        var session = new BackendSession(Platform::runLater, presenter);
        stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, _ -> session.close());
        session.start(Texts.currentLocale().getLanguage());
        return session;
    }

    @Override public void statusChanged(BackendSession.Status update) {
        String message = Texts.get("backend.status." + update.state().name().toLowerCase(java.util.Locale.ROOT));
        status.setText(message);
        status.setTooltip(new Tooltip(update.detail().isBlank() ? message : update.detail()));
        log.info(Texts.msg("log.backendconnection.01", message, update.detail()));
    }

    @Override public void confirmationRequested(String id, ConfirmRequest request, Consumer<String> answer) {
        activeId = id;
        dismiss = confirmer.confirmAsync(request, choice -> {
            log.info(Texts.msg("log.backendconnection.02", id, choice));
            answer.accept(choice);
        });
        log.info(Texts.msg("log.backendconnection.03", id, request.key()));
    }

    @Override public void confirmationFinished(String id, BackendSession.End reason) {
        if (id.equals(activeId)) {
            Runnable old = dismiss;
            activeId = null;
            dismiss = null;
            if (old != null) old.run();
        }
        if (reason == BackendSession.End.TIMEOUT) {
            NotificationHost.toastWarning(anchor, Texts.get("backend.confirmTimeout"));
        }
        log.info(Texts.msg("log.backendconnection.04", id, reason));
    }

    @Override public void confirmationAnswerReceived(String id, boolean accepted) {
        log.info(Texts.msg("log.backendconnection.05", id, accepted));
    }
}
