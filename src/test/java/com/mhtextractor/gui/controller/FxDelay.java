package com.mhtextractor.gui.controller;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
/** Lets real backend callbacks and UI continuations run between automation actions. */
final class FxDelay {
    private static com.mhtextractor.uicore.FrontendPort frontend;
    private static boolean settling;
    static void bind(com.mhtextractor.uicore.FrontendPort port){frontend=port;}
    static void pause(long millis){
        if(!Platform.isFxApplicationThread()){try{Thread.sleep(millis);}catch(InterruptedException error){Thread.currentThread().interrupt();}return;}
        Object key=new Object();var timer=new PauseTransition(Duration.millis(Math.max(1,millis)));
        timer.setOnFinished(ignored->Platform.exitNestedEventLoop(key,null));timer.play();Platform.enterNestedEventLoop(key);
        if(frontend!=null&&!settling){
            settling=true;
            try{
                var done=new java.util.concurrent.CompletableFuture<Void>();
                frontend.execute(port->null,ignored->done.complete(null));
                Object barrier=new Object();long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                var poll=new javafx.animation.Timeline();
                poll.getKeyFrames().add(new javafx.animation.KeyFrame(Duration.millis(10),ignored->{
                    if(done.isDone()||System.nanoTime()>=deadline){poll.stop();Platform.exitNestedEventLoop(barrier,null);}
                }));
                poll.setCycleCount(javafx.animation.Animation.INDEFINITE);poll.play();Platform.enterNestedEventLoop(barrier);poll.stop();
                if(!done.isDone())throw new IllegalStateException("IPC command did not reach its UI snapshot within 30 seconds");
                done.join();
            }finally{settling=false;}
        }
    }
}
