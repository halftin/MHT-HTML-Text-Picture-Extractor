import atlantafx.base.controls.ModalPane;
import atlantafx.base.controls.Notification;
import atlantafx.base.theme.NordLight;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mhtextractor.App;
import com.mhtextractor.app.UiportRuntime;
import com.mhtextractor.gui.component.BackendConnectionPresenter;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.i18n.I18n;
import javafx.application.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real NotificationHost and real IPC rebuilding; expected prose is independent of the bundles. */
public final class BackendUnavailableProbe extends Application {
    private StackPane shell;
    private UiportRuntime runtime;
    private Button importButton,extractButton,cancelButton,localButton;
    private final AtomicInteger attempts=new AtomicInteger(),completed=new AtomicInteger(),rebuilds=new AtomicInteger(),localClicks=new AtomicInteger();
    private final ListAppender<ILoggingEvent> logs=new ListAppender<>();
    private String[] words;
    private int checks;
    @Override public void start(Stage stage){
        Platform.setImplicitExit(false);
        I18n.setLocale(I18n.parseLocale(System.getProperty("probe.locale","zh_CN")));
        boolean zh=I18n.currentLocale().getLanguage().equals("zh");
        words=zh?new String[]{"后台服务不可用，请重启以拉起后台或检查后台文件完整性。","后台服务不可用，是否重建连接？",
                "重建失败，请重新下载后台服务程序文件。","后台服务不可用，无法进行提取和解析。","是","否","确定"}
                :new String[]{"The backend service is unavailable. Restart to launch it or check the integrity of the backend files.",
                "The backend service is unavailable. Rebuild the connection?","Rebuilding failed. Please download the backend service program files again.",
                "The backend service is unavailable. Extraction and parsing cannot proceed.","Yes","No","OK"};
        System.setProperty("mht.uiport","ipc");System.setProperty("mht.backend.transport","executable");
        System.setProperty("mht.backend.executable","H:/no-such-dir/MhtExtractorBackend.exe");
        logs.start();((Logger)LoggerFactory.getLogger(BackendConnectionPresenter.class)).addAppender(logs);
        importButton=new Button("import");extractButton=new Button("extract");cancelButton=new Button("cancel preview");localButton=new Button("local UI");
        shell=new StackPane(new VBox(new Label("D1"),importButton,extractButton,cancelButton,localButton));
        var scene=new Scene(shell,850,550);NotificationHost.install(scene,shell);
        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());
        scene.getStylesheets().add(App.class.getResource("/custom.css").toExternalForm());stage.setScene(scene);stage.show();
        localButton.setOnAction(e->localClicks.incrementAndGet());
        UiportRuntime.start(I18n.currentLocale().toLanguageTag(),request->{throw new AssertionError("Unexpected backend confirmation");},Platform::runLater,owner->{
            runtime=owner;BackendConnectionPresenter.bind(runtime,shell);
            var presenter=new BackendConnectionPresenter(null,shell);
            runtime.onUnavailable(()->presenter.requestUnavailable(done->{rebuilds.incrementAndGet();runtime.rebuild(done);}));
            importButton.setOnAction(e->runtime.port().commands().importPaths(List.of("H:/TEST/mht/input.mht"),"probe",false));
            extractButton.setOnAction(e->runtime.port().commands().startExtraction(false));
            cancelButton.setOnAction(e->runtime.port().execute(p->{attempts.incrementAndGet();p.commands().cancelPreviewLoad();return null;},ignored->completed.incrementAndGet()));
            Thread.ofPlatform().daemon().name("d1-probe").start(()->run(stage));
        },error->fail(error));
        Thread.ofPlatform().daemon().name("d1-probe-deadline").start(()->{try{Thread.sleep(100_000);}catch(InterruptedException ignored){}System.err.println("D1_PROBE_TIMEOUT");System.exit(1);});
    }
    private void run(Stage stage){
        try{
            fx(()->{
                check("status-alone-no-card",!modal().isDisplay());
                check("status-alone-no-d1-log",logs.list.isEmpty());
                check("backend-buttons-clickable",!importButton.isDisabled()&&!extractButton.isDisabled()&&!cancelButton.isDisabled());
                localButton.fire();check("pure-ui-no-d1",localClicks.get()==1&&!modal().isDisplay());
                importButton.fire();
            });
            awaitCard(words[1]);
            fx(()->{question();button(words[5]).fire();card(words[3],List.of(words[6]));button(words[6]).fire();});
            awaitHidden();
            fx(()->extractButton.fire());awaitCard(words[1]);
            fx(()->{question();button(words[4]).fire();check("rebuild-card-answered",!NotificationHost.hasPendingDecision(shell));});
            awaitCard(words[2]);
            fx(()->{card(words[2],List.of(words[6]));check("failed-rebuild-once",rebuilds.get()==1);button(words[6]).fire();});
            awaitHidden();
            fx(()->cancelButton.fire());awaitCard(words[1]);
            fx(()->{question();System.setProperty("mht.backend.transport","jvm");button(words[4]).fire();});
            var connected=new CompletableFuture<Integer>();
            await(()->runtime.port().implementation() instanceof com.mhtextractor.app.IpcUiport ipc
                    &&ipc.status().state()==com.mhtextractor.app.BackendSession.State.CONNECTED,45);
            // Barrier behind the replacement and its complete snapshot delivery.
            fx(()->runtime.port().execute(p->p.queries().workspace().topLevelItems().size(),connected::complete));
            connected.get(35,TimeUnit.SECONDS);
            fx(()->{
                check("D1a-original-attempt-exactly-once",attempts.get()==1);
                check("D1a-original-callback-never-resumed",completed.get()==0);
                check("successful-rebuild-once",rebuilds.get()==2);
                check("success-no-terminal-card",!modal().isDisplay());
                check("success-buttons-clickable",!importButton.isDisabled()&&!extractButton.isDisabled()&&!cancelButton.isDisabled());
                long count=logs.list.stream().filter(e->e.getFormattedMessage().equals(words[0])).count();
                check("exact-unavailable-log-three-times",count==3);
                System.out.println("D1_NO_REPLAY attempts="+attempts+" completed="+completed+" rebuilds="+rebuilds);
            });
            var stopped=new CompletableFuture<Void>();runtime.closeAsync(()->stopped.complete(null));stopped.get(40,TimeUnit.SECONDS);
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("logs"));
            java.nio.file.Files.writeString(java.nio.file.Path.of("logs/gui-state.json"),
                    "{\"locale\":\""+I18n.currentLocale()+"\",\"imageModeEnabled\":false,\"inputFolder\":\"\",\"outputFolder\":\"\"}");
            fx(()->{
                stage.hide();System.setProperty("mht.backend.transport","executable");
                try{new App().start(stage);}catch(Exception error){throw new IllegalStateException(error);}
            });
            await(stage::isShowing,45);Thread.sleep(1000);
            fx(()->{
                shell=(StackPane)stage.getScene().getRoot();
                check("production-missing-exe-window-ready",stage.isShowing());
                check("production-startup-no-d1-card",!modal().isDisplay());
                check("production-mode-button-clickable",!stage.getScene().lookup("#textModeToggle").isDisabled());
                ((ToggleButton)stage.getScene().lookup("#imageModeToggle")).fire();
            });
            awaitCard(words[1]);
            fx(()->{
                question();button(words[5]).fire();card(words[3],List.of(words[6]));button(words[6]).fire();
                System.out.println("D1_PROBE checks="+checks+" failures=0 locale="+I18n.currentLocale());
            });
            awaitHidden();
            fx(()->javafx.event.Event.fireEvent(stage,new javafx.stage.WindowEvent(stage,javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)));
            await(()->!stage.isShowing(),40);Platform.exit();System.exit(0);
        }catch(Throwable error){fail(error);}
    }
    private void question(){
        card(words[1],List.of(words[4],words[5]));
        check("yes-no-pending-decision",NotificationHost.hasPendingDecision(shell));
        check("log-verbatim",logs.list.stream().anyMatch(e->e.getFormattedMessage().equals(words[0])));
        System.out.println("D1_LOG_EXACT "+words[0]);
    }
    private void card(String text,List<String> labels){
        shell.applyCss();shell.layout();var card=(Notification)modal().getContent();
        check("message-verbatim",card.getMessage().equals(text));
        var buttons=card.lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast).filter(b->!b.getText().isBlank()).toList();
        check("card-actions-exact",buttons.size()==labels.size()&&buttons.stream().map(Button::getText).collect(java.util.stream.Collectors.toSet()).equals(Set.copyOf(labels)));
        check("no-default-button",buttons.stream().noneMatch(Button::isDefaultButton));
        check("card-max-height-pref",card.getMaxHeight()==Region.USE_PREF_SIZE);
        check("error-card-danger-style",card.getStyleClass().contains(atlantafx.base.theme.Styles.DANGER));
        check("centered-persistent-modal",modal().isDisplay()&&modal().getPersistent()&&modal().getAlignment()==javafx.geometry.Pos.CENTER);
        System.out.println("D1_CARD_EXACT message="+card.getMessage()+" actions="+labels);
    }
    private ModalPane modal(){return (ModalPane)shell.lookup(".modal-pane");}
    private Button button(String text){shell.applyCss();return modal().getContent().lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast).filter(b->text.equals(b.getText())).findFirst().orElseThrow();}
    private void awaitCard(String text)throws Exception{await(()->modal().isDisplay()&&modal().getContent() instanceof Notification n&&text.equals(n.getMessage()),45);}
    private void awaitHidden()throws Exception{await(()->!modal().isDisplay(),5);}
    private void await(java.util.function.BooleanSupplier condition,int seconds)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(System.nanoTime()<end){var ready=new CompletableFuture<Boolean>();fx(()->ready.complete(condition.getAsBoolean()));if(ready.get())return;Thread.sleep(25);}
        throw new AssertionError("GUI deadline exceeded");
    }
    private void fx(Runnable action)throws Exception{
        var done=new CompletableFuture<Void>();Platform.runLater(()->{try{action.run();done.complete(null);}catch(Throwable e){done.completeExceptionally(e);}});done.get(20,TimeUnit.SECONDS);
    }
    private void check(String name,boolean pass){if(!pass)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    private void fail(Throwable error){error.printStackTrace();if(runtime!=null)runtime.closeAsync(()->System.exit(1));else System.exit(1);}
    public static void main(String[] args){launch(args);}
}
