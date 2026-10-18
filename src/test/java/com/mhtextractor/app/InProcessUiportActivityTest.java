package com.mhtextractor.app;

import com.mhtextractor.baseline.BaselineCases;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.model.ThumbnailData;
import com.mhtextractor.uiport.PortEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 只经公开端口验收活动编排；闸门固定取消/完成顺序，不依赖短暂状态的轮询。 */
class InProcessUiportActivityTest {
    @TempDir Path tmp;

    private final WorkspaceModel model = new WorkspaceModel();
    private final BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
    private final List<PortEvent> events = new ArrayList<>();
    private boolean textMode = true;
    private AppConfig params = new AppConfig();

    private InProcessUiport port(WorkspaceActivityService.Parser parser,
                                 WorkspaceActivityService.ThumbnailDecoder decoder) {
        var activities = new WorkspaceActivityService(new WorkspaceActivityService.Context() {
            public List<PreviewItem> items() { return model.allItems(); }
            public boolean textMode() { return textMode; }
            public AppConfig effectiveParams(PreviewItem item) {
                return item.name.equals("no-params") ? null : params;
            }
            public AppConfig effectiveImageParams(PreviewItem item) { return params; }
        }, new PreviewService(PreviewSession.create(tmp.resolve("cache")).lifecycle()),
                decoder, deliveries::add, parser);
        var port = new InProcessUiport(model, request -> { throw new AssertionError("unexpected confirmation"); }, activities);
        port.events().subscribe(events::add);
        return port;
    }

    private InProcessUiport port(WorkspaceActivityService.Parser parser) {
        return port(parser, (image, index, config) -> new ThumbnailData("thumb-" + index, 1, 1, new int[]{-1}));
    }

    private PreviewItem item(String name) {
        var item = new PreviewItem(tmp.resolve(name).toString(), name, LocalDateTime.now(), tmp.toString());
        model.addItems(List.of(item));
        return item;
    }

    private PreviewItem imageFixture() throws Exception {
        Files.copy(BaselineCases.FIXTURE_DIR.resolve("multi-image.mht"), tmp.resolve("images.mht"));
        return item("images.mht");
    }

    private void nextDelivery() throws Exception {
        Runnable task = deliveries.poll(5, TimeUnit.SECONDS);
        assertNotNull(task, "worker must deliver within timeout");
        task.run();
    }

    private void drain() {
        Runnable task;
        while ((task = deliveries.poll()) != null) task.run();
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "worker gate timed out");
    }

    private List<PortEvent.PreparseFinished> finished() {
        return events.stream().filter(PortEvent.PreparseFinished.class::isInstance)
                .map(PortEvent.PreparseFinished.class::cast).toList();
    }

    @Test
    void fullAndIncrementalPreparsePreserveGenerationAndTitleAssignment() throws Exception {
        PreviewItem a = item("a.html");
        a.cachedTitle = "existing";
        a.titleOverride = "manual";
        item("no-params");
        AtomicInteger calls = new AtomicInteger();
        var port = port((path, config, mode) -> {
            calls.incrementAndGet();
            return new SourceParseService.ItemParse(SourceParseService.Kind.READY, "body", "new", !path.equals(a.path));
        });
        port.commands().startPreparse();
        long generation = port.queries().activityGeneration();
        nextDelivery();
        assertEquals(1, generation);
        assertEquals(generation, finished().getFirst().generation());
        assertEquals("existing", a.cachedTitle);
        assertEquals("manual", a.titleOverride);
        assertEquals("body", a.cachedText);
        assertEquals(new PortEvent.PreparseProgress(generation, 1, 1), events.getLast());
        PreviewItem b = item("b.html");
        textMode = false;
        b.cachedText = "old text";
        port.commands().startPreparseFor(List.of(b.path));
        nextDelivery();
        assertEquals(generation, finished().getLast().generation());
        assertEquals("new", b.cachedTitle);
        assertEquals("old text", b.cachedText);
        assertEquals(2, calls.get());
        assertEquals(new PortEvent.PreparseProgress(generation, 2, 2), events.getLast());
    }

    @Test
    void preparseFailureEmptyAndUnparseableAreDeliveredWithoutErasingCaches() throws Exception {
        PreviewItem bad = item("bad");
        PreviewItem noHtml = item("no-html");
        PreviewItem empty = item("empty");
        bad.cachedTitle = noHtml.cachedTitle = "keep";
        var port = port((path, config, mode) -> {
            if (path.equals(bad.path)) throw new java.io.IOException("broken");
            return new SourceParseService.ItemParse(path.equals(noHtml.path)
                    ? SourceParseService.Kind.UNPARSEABLE : SourceParseService.Kind.EMPTY, "", null, false);
        });
        port.commands().startPreparse();
        nextDelivery(); nextDelivery(); nextDelivery();
        assertEquals(List.of("preview.parseFailed", "preview.unparseable"),
                finished().subList(0, 2).stream().map(e -> e.result().errorKey()).toList());
        assertEquals("broken", finished().getFirst().result().errorDetail());
        assertEquals(PreviewItem.Status.EMPTY, empty.status);
        assertEquals("keep", bad.cachedTitle);
        assertEquals("keep", noHtml.cachedTitle);
        assertEquals(new PortEvent.PreparseProgress(1, 3, 3), events.getLast());
    }

    @Test
    void cancellationRejectsQueuedResultBeforeItCanWriteTitleOrStatus() throws Exception {
        PreviewItem item = item("a");
        item.cachedTitle = "keep";
        var port = port((path, config, mode) ->
                new SourceParseService.ItemParse(SourceParseService.Kind.READY, "old", "old title", true));
        port.commands().startPreparse();
        Runnable late = deliveries.poll(5, TimeUnit.SECONDS);
        assertNotNull(late);
        port.commands().cancelWorkspaceActivity();
        late.run();
        assertEquals(2, port.queries().activityGeneration());
        assertEquals(PreviewItem.Status.WAITING, item.status);
        assertEquals("keep", item.cachedTitle);
        assertNull(item.cachedText);
        assertTrue(finished().isEmpty());
    }

    @Test
    void cancellationDuringParseDoesNotPolluteNextGeneration() throws Exception {
        PreviewItem item = item("a");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var port = port((path, config, mode) -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try { await(release); } finally { exited.countDown(); }
                return new SourceParseService.ItemParse(SourceParseService.Kind.READY, "late", "late", true);
            }
            return new SourceParseService.ItemParse(SourceParseService.Kind.READY, "current", "current", true);
        });
        try {
            port.commands().startPreparse();
            await(entered);
            port.commands().cancelWorkspaceActivity();
            port.commands().startPreparse();
            nextDelivery();
            release.countDown();
            await(exited);
            drain();
            assertEquals("current", item.cachedTitle);
            assertEquals(3, finished().getFirst().generation());
            assertEquals(1, finished().size());
        } finally { release.countDown(); }
    }

    @Test
    void previewSuccessPublishesStateAndKeepsMetaForSystemFileOpening() throws Exception {
        PreviewItem item = imageFixture();
        var port = port(SourceParseService::parse);
        port.commands().loadPreview(item.path, false);
        assertEquals(item.path, port.queries().previewLoadingPath());
        port.commands().awaitPreviewInFlight(5000);
        drain();
        assertNull(port.queries().previewLoadingPath());
        assertNotNull(item.cachedMeta);
        assertFalse(item.cachedThumbs.isEmpty());
        assertNull(item.cachedImages);
        assertTrue(events.stream().anyMatch(PortEvent.PreviewLoaded.class::isInstance));
        var states = events.stream().filter(PortEvent.PreviewLoadStateChanged.class::isInstance)
                .map(PortEvent.PreviewLoadStateChanged.class::cast).map(PortEvent.PreviewLoadStateChanged::active).toList();
        assertEquals(List.of(true, false), states);
        // 单击打开所依赖的同一 imageFileFor 仍能从落盘元数据定位真实文件。
        assertTrue(Files.isRegularFile(PreviewSession.create(tmp.resolve("cache")).store().imageFileFor(item.cachedMeta, 0)));
    }

    @Test
    void previewFailureDropsActiveStateAndPrepareFailureNeverEntersIt() throws Exception {
        PreviewItem missing = item("missing.mht");
        PreviewItem image = imageFixture();
        var port = port(SourceParseService::parse, (data, index, config) -> { throw new IllegalStateException("decode"); });
        port.commands().loadPreview(missing.path, false);
        assertTrue(((PortEvent.PreviewLoadFailed) events.getLast()).fileInfo());
        assertNull(port.queries().previewLoadingPath());
        assertFalse(events.stream().anyMatch(PortEvent.PreviewLoadStateChanged.class::isInstance));
        port.commands().loadPreview(image.path, false);
        port.commands().awaitPreviewInFlight(5000);
        drain();
        assertNull(port.queries().previewLoadingPath());
        assertTrue(events.stream().anyMatch(e -> e instanceof PortEvent.PreviewLoadFailed f && !f.fileInfo()));
        assertNull(image.cachedThumbs);
    }

    @Test
    void largeFileThresholdRequiresConfirmationOnlyAboveFiftyMiB() throws Exception {
        long threshold = 50L * 1024 * 1024;
        assertFalse(WorkspaceActivityService.requiresLargeFileConfirmation(threshold));
        assertTrue(WorkspaceActivityService.requiresLargeFileConfirmation(threshold + 1));
        PreviewItem large = item("large.mht");
        try (var file = new RandomAccessFile(large.path, "rw")) { file.setLength(threshold + 1); }
        var port = port(SourceParseService::parse);
        port.commands().loadPreview(large.path, false);
        assertEquals(new PortEvent.PreviewConfirmationRequired(large.path, large.name, 50), events.getLast());
        assertNull(port.queries().previewLoadingPath());
    }

    @Test
    void oldPreviewCompletionCannotClearNewRequestAndStaleResultIsNotApplied() throws Exception {
        PreviewItem item = imageFixture();
        CountDownLatch oldEntered = new CountDownLatch(1), oldRelease = new CountDownLatch(1);
        CountDownLatch newEntered = new CountDownLatch(1), newRelease = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var port = port(SourceParseService::parse, (image, index, config) -> {
            try {
                if (calls.incrementAndGet() == 1) { oldEntered.countDown(); await(oldRelease); }
                else { newEntered.countDown(); await(newRelease); }
            } catch (InterruptedException e) { throw new RuntimeException(e); }
            return new ThumbnailData("thumb", 1, 1, new int[]{-1});
        });
        try {
            port.commands().loadPreview(item.path, false);
            await(oldEntered);
            port.commands().cancelWorkspaceActivity();
            port.commands().loadPreview(item.path, false);
            await(newEntered);
            oldRelease.countDown();
            nextDelivery(); nextDelivery(); nextDelivery(); // 旧 thumbnail / complete / finally
            assertEquals(item.path, port.queries().previewLoadingPath());
            assertNull(item.cachedThumbs);
            assertTrue(events.stream().anyMatch(e -> e instanceof PortEvent.PreviewLoaded done
                    && done.generation() < port.queries().previewGeneration()));
            assertFalse(events.stream().anyMatch(e -> e instanceof PortEvent.PreviewLoaded done
                    && done.generation() == port.queries().previewGeneration()));
            params = null; // 新请求落盘后失效，也必须复位在途状态。
            newRelease.countDown();
            port.commands().awaitPreviewInFlight(5000);
            drain();
            assertNull(port.queries().previewLoadingPath());
            assertNull(item.cachedMeta);
            assertTrue(events.stream().anyMatch(e -> e instanceof PortEvent.PreviewLoaded done
                    && done.generation() == port.queries().previewGeneration()));
            // 陈旧缓存清理 worker 已纳入在途清单：join 后缓存项目目录必须已删除，
            // 收尾不得与外部递归删除（@TempDir 清理）赛跑。
            port.commands().awaitPreviewInFlight(5000);
            try (var dirs = Files.list(tmp.resolve("cache"))) {
                assertEquals(0, dirs.count(), "joined cleanup worker must remove the stale cache");
            }
        } finally { oldRelease.countDown(); newRelease.countDown(); port.commands().awaitPreviewInFlight(5000); }
    }

    @Test
    void zeroImagesAndCancellationHaveExactlyOneTerminalPerStartedLoad() throws Exception {
        PreviewItem empty = item("empty.html");
        Files.writeString(Path.of(empty.path), "<html><body>no images</body></html>");
        var port = port(SourceParseService::parse);
        for (boolean cancel : List.of(false, true)) {
            empty.cachedThumbs = null;
            events.clear();
            port.commands().loadPreview(empty.path, true);
            if (cancel) port.commands().cancelPreviewLoad();
            port.commands().awaitPreviewInFlight(5000);
            drain();
            assertNull(port.queries().previewLoadingPath());
            assertEquals(1, events.stream().filter(e -> e instanceof PortEvent.PreviewLoaded
                    || e instanceof PortEvent.PreviewLoadFailed).count());
            System.out.println("ZERO cancel=" + cancel + " terminalEvent=true loadingPath=null");
        }
    }

    @Test
    void regionDecisionEndsLoadBeforeCardAndYesRestartsWhileNoDoesNot() throws Exception {
        Files.copy(BaselineCases.FIXTURE_DIR.resolve("region.mht"), tmp.resolve("region.mht"));
        PreviewItem item = item("region.mht");
        params.setImageRegionStartMarker("<!--START-->");
        params.setImageRegionEndMarker("<p>");
        var port = port(SourceParseService::parse);
        for (boolean yes : List.of(false, true)) {
            events.clear();
            port.commands().loadPreview(item.path, true);
            port.commands().awaitPreviewInFlight(5000);
            drain();
            assertNull(port.queries().previewLoadingPath());
            assertNull(item.cachedThumbs);
            assertInstanceOf(PortEvent.PreviewRegionConfirmationRequired.class, events.getLast());
            assertTrue(events.stream().anyMatch(PortEvent.PreviewLoaded.class::isInstance));
            long token = port.queries().previewGeneration();
            port.commands().resolvePreviewRegion(token, item.path, yes);
            if (yes) {
                assertEquals(item.path, port.queries().previewLoadingPath());
                port.commands().awaitPreviewInFlight(5000);
                drain();
                assertEquals(4, item.cachedThumbs.size());
            } else {
                assertEquals("preview.region.declined", ((PortEvent.PreviewNotice) events.getLast()).key());
            }
            assertNull(port.queries().previewLoadingPath());
            System.out.println("R5 accepted=" + yes + " loadingPath=null thumbs="
                    + (item.cachedThumbs == null ? "null" : item.cachedThumbs.size()));
        }
    }

    @Test
    void staleRegionDecisionCannotRestartOrOverwriteAnotherSelection() throws Exception {
        Files.copy(BaselineCases.FIXTURE_DIR.resolve("region.mht"), tmp.resolve("region.mht"));
        PreviewItem item = item("region.mht");
        params.setImageRegionStartMarker("<!--START-->");
        params.setImageRegionEndMarker("<p>");
        var port = port(SourceParseService::parse);
        port.commands().loadPreview(item.path, true);
        port.commands().awaitPreviewInFlight(5000);
        drain();
        long token = port.queries().previewGeneration();
        port.commands().cancelPreviewLoad();
        port.commands().resolvePreviewRegion(token, item.path, true);
        assertNull(port.queries().previewLoadingPath());
        assertNull(item.cachedThumbs);
    }

    @Test
    void decoderErrorStillDeliversFailureAndReleasesLoad() throws Exception {
        PreviewItem item = imageFixture();
        var port = port(SourceParseService::parse, (image, index, config) -> { throw new AssertionError("decoder"); });
        port.commands().loadPreview(item.path, true);
        port.commands().awaitPreviewInFlight(5000);
        drain();
        assertNull(port.queries().previewLoadingPath());
        assertEquals(1, events.stream().filter(PortEvent.PreviewLoadFailed.class::isInstance).count());
    }

    @Test
    void stuckDecoderTimesOutWithoutApplyingLateResult() throws Exception {
        PreviewItem item = imageFixture();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var port = port(SourceParseService::parse, (image, index, config) -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new ThumbnailData("thumb", 1, 1, new int[]{-1});
        });
        try {
            port.commands().loadPreview(item.path, true);
            await(entered);
            Runnable timeout = deliveries.poll(35, TimeUnit.SECONDS);
            assertNotNull(timeout);
            timeout.run();
            assertNull(port.queries().previewLoadingPath());
            var failed = events.stream().filter(PortEvent.PreviewLoadFailed.class::isInstance)
                    .map(PortEvent.PreviewLoadFailed.class::cast).findFirst().orElseThrow();
            assertEquals("preview.loadTimeout", failed.detailKey());
            assertNull(failed.detail());
            release.countDown();
            port.commands().awaitPreviewInFlight(5000);
            drain();
            assertNull(item.cachedThumbs);
            assertEquals(1, events.stream().filter(e -> e instanceof PortEvent.PreviewLoaded
                    || e instanceof PortEvent.PreviewLoadFailed).count());
            System.out.println("TIMEOUT terminalEvent=true loadingPath=null lateResultRejected=true");
        } finally { release.countDown(); port.commands().awaitPreviewInFlight(5000); }
    }
}
