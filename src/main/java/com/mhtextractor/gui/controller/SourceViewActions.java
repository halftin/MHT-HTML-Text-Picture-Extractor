package com.mhtextractor.gui.controller;

import com.mhtextractor.i18n.Texts;
import com.mhtextractor.gui.component.GuiNotifications;
import com.mhtextractor.gui.component.HtmlSourceViewer;
import com.mhtextractor.uicore.PreviewRow;
import com.mhtextractor.platform.BrowserLauncher;
import com.mhtextractor.uicore.FrontendPort;
import javafx.application.Platform;
import javafx.scene.Node;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 源查看/浏览器动作协作者（C1a）—— 承载"在浏览器查看 HTML / 查看源码"这一组动作，
 * 从 {@code MainController} 原样外迁：浏览器查看页生成并发闸、导出、应用内源码查看器
 * 与系统浏览器启动都在本类；对宿主的全部能力需求收窄为 {@link Host} 两个方法。
 *
 * <p>搬迁前的成员在 {@code MainController} 上：{@code browserExporting} 字段、
 * {@code onContextOpenInBrowser} / {@code onContextViewSource}（右键菜单转发）与
 * {@code openHtmlInBrowser}（主体）。文案键、日志键、提醒图标/锚点、线程名
 * （{@code html-browser-launch}；导出经端口串行执行）与 {@code browserExporting}
 * 的并发去重语义全部保持不变。</p>
 *
 * <p>本类含 JavaFX，属 {@code gui} 层；业务侧不得反向依赖它。</p>
 */
@Slf4j
public final class SourceViewActions {

    /** 宿主能力窄接口：本类不持有任何控件字段，只按这两个动作驱动界面。 */
    public interface Host {
        /** 提醒/查看器的锚点节点（搬迁前是 {@code refs.statusLabel()}）。 */
        Node anchor();

        /** 状态栏文案（搬迁前是 {@code refs.statusLabel().setText(...)}）。 */
        void setStatusText(String text);
    }

    private final Host host;
    private final FrontendPort activityPort;

    /** 浏览器查看页生成并发闸（防重复点击并发导出） */
    private final AtomicBoolean browserExporting = new AtomicBoolean(false);

    public SourceViewActions(Host host, FrontendPort activityPort) {
        this.host = host;
        this.activityPort = activityPort;
    }

    /** 在浏览器中查看（浏览器查看HTML需求）：导出 MHT 内嵌 HTML 后用系统默认浏览器打开渲染视图 */
    public void openInBrowser(Object target) {
        if (target instanceof PreviewRow it && !it.removed) {
            openHtmlInBrowser(it, false);
        }
    }

    /** 在浏览器中查看源码（浏览器查看HTML需求）：用 view-source: 打开导出页面的源码视图 */
    public void viewSource(Object target) {
        if (target instanceof PreviewRow it && !it.removed) {
            openHtmlInBrowser(it, true);
        }
    }

    /** 查看HTML源码按钮（浏览器查看HTML需求）：对列表当前选中条目打开源码视图 */
    public void viewSource(PreviewRow it) {
        openHtmlInBrowser(it, true);
    }

    /** 在系统默认浏览器中查看该 MHT 内嵌 HTML（sourceMode=true 走 view-source 源码视图） */
    private void openHtmlInBrowser(PreviewRow it, boolean sourceMode) {
        File f = new File(it.path);
        if (!f.exists()) {
            log.warn(Texts.msg("log.maincontroller.22", it.path));
            GuiNotifications.fileMissing(host.anchor(), it.path);
            return;
        }
        if (!browserExporting.compareAndSet(false, true)) {
            log.info(Texts.msg("log.maincontroller.23"));
            return;
        }
        host.setStatusText(Texts.get("browser.generatingPage"));
        activityPort.execute(p -> {
            try {
                return p.queries().exportSource(it.path);
            } catch (RuntimeException e) {
                exportFailed(it, e);
                Platform.runLater(this::exportFinished);
                throw e;
            }
        }, result -> {
            try {
                switch (result.status()) {
                    case "unparseable" ->
                            throw new IllegalStateException(Texts.get("browser.exportUnparseable"));
                    case "failed" ->
                            throw new IllegalStateException(WorkspaceInfoPresenter.detailText(result.detailKey(), result.detailParams()));
                    case "success" -> {
                        Path htmlFile = Path.of(result.htmlFile().path());
                        log.info(Texts.msg("log.maincontroller.24", htmlFile, result.imageCount(), result.warningCount()));
                        // 浏览器启动可能耗时（注册表探测 / CDP 端点等待最长约 15 秒），独立后台线程避免冻结界面
                        Thread launcher = new Thread(() -> {
                            if (sourceMode) {
                                // R2：源码不再交给外部浏览器，改为应用内左侧覆盖式查看器（禁用 WebView，R2.6）。
                                HtmlSourceViewer.showFile(host.anchor(), Texts.get("browser.viewerTitle"), htmlFile);
                                return;
                            }
                            boolean ok = BrowserLauncher.openHtml(htmlFile);
                            if (!ok) {
                                log.warn(Texts.msg("log.maincontroller.25", htmlFile));
                                Platform.runLater(() -> GuiNotifications.browserLaunchFailed(host.anchor(), htmlFile));
                            }
                        }, "html-browser-launch");
                        launcher.setDaemon(true);
                        launcher.start();
                    }
                    default -> throw new IllegalStateException(result.status());
                }
            } catch (Exception e) {
                exportFailed(it, e);
            } finally {
                exportFinished();
            }
        });
    }

    private void exportFailed(PreviewRow it, Exception error) {
        log.error(Texts.msg("log.maincontroller.26", it.path), error);
        Platform.runLater(() -> GuiNotifications.browserViewFailed(host.anchor()));
    }

    private void exportFinished() {
        browserExporting.set(false);
        host.setStatusText(Texts.get("main.statusReady"));
    }
}
