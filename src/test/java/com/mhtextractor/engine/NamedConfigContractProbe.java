package com.mhtextractor.engine;

import com.mhtextractor.uiclient.IpcUiport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uiport.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real child-process contract driver; isolated working directory shared by both adapters. */
public final class NamedConfigContractProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Confirmer CARDS = request -> { throw new AssertionError("unexpected confirmation"); };
    private static AppConfig config() {
        var c = new AppConfig();
        c.setSeparateFiles(true); c.setEnableContentLimit(true); c.setStartMarker("<start>");
        c.setEndMarker(null); c.setUseRegex(true); c.setEnableTitleExtraction(true);
        c.setEnableZipPackaging(true); c.setImageRegionStartMarker("<image>");
        c.setHighSpeedMemoryMode(true); c.setOutputFolder("runtime-only");
        c.setEnableTemplate(true); c.setStartTemplate("start-template"); c.setEndTemplate("end-template");
        c.setAddChapterNumber(true); c.setAutoFixEncoding(false);
        c.setTitleStartMarker("title-start"); c.setTitleEndMarker("title-end"); c.setTitleUseRegex(true);
        c.setEnableNameExtraction(true); c.setImageNameStartMarker("image-name-start"); c.setImageNameEndMarker("image-name-end");
        c.setEnableRegionExtraction(true); c.setImageRegionEndMarker("image-region-end");
        c.setEnableBatchRenaming(true); c.setEnableCbzPackaging(true); c.setSingleOutputMode(true);
        c.setNameLimitStart("legacy-name-start"); c.setNameLimitEnd("legacy-name-end");
        c.setContentLimitStart("legacy-content-start"); c.setContentLimitEnd("legacy-content-end");
        c.setInputFolder("runtime-input"); c.setImageModeEnabled(true); c.setOpenFolderAfterComplete(true);
        return c;
    }
    private static void equalLists(Uiport local, Uiport ipc, NamedConfigService service) {
        assertEquals(service.names(), local.queries().listNamedConfigs());
        assertEquals(local.queries().listNamedConfigs(), ipc.queries().listNamedConfigs());
    }
    private static void equalLoads(Uiport local, Uiport ipc, NamedConfigService service, String name) throws Exception {
        assertEquals(service.load(name), local.queries().loadNamedConfig(name));
        assertEquals(JSON.valueToTree(local.queries().loadNamedConfig(name)), JSON.valueToTree(ipc.queries().loadNamedConfig(name)));
    }
    @FunctionalInterface private interface IoAction { void run() throws IOException; }
    private static void sameError(IoAction service, IoAction local, IoAction ipc) {
        String expected = assertThrows(IOException.class, service::run).getMessage();
        assertEquals(expected, assertThrows(IOException.class, local::run).getMessage());
        assertEquals(expected, assertThrows(IOException.class, ipc::run).getMessage());
    }
    private static void errors(Uiport local, Uiport ipc, NamedConfigService service, String name) {
        sameError(() -> service.load(name), () -> local.queries().loadNamedConfig(name), () -> ipc.queries().loadNamedConfig(name));
        sameError(() -> service.delete(name), () -> local.commands().deleteNamedConfig(name), () -> ipc.commands().deleteNamedConfig(name));
    }
    public static void main(String[] args) throws Exception {
        int code = 0;
        try { run(args); }
        catch (Throwable error) { error.printStackTrace(); code = 1; }
        System.exit(code);
    }
    private static void run(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        var local = new InProcessUiport(new WorkspaceModel(), CARDS);
        var ipc = new IpcUiport(CARDS, () -> new IpcUiport.RuntimeContext(false, null, null, false));
        try {
            ipc.start(() -> new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                    "--limit-modules", "java.se", "-Djava.io.tmpdir=H:/TEST/temp", "-Dfile.encoding=UTF-8",
                    "-Dlogback.configurationFile=logback-headless.xml", "-cp", System.getProperty("java.class.path"),
                    "com.mhtextractor.backend.BackendCli", "--serve", "--locale", "en")
                    .directory(root.toFile()).redirectError(root.resolve("backend-stderr.log").toFile()), Duration.ofSeconds(30));
            assertFalse(Files.exists(Path.of("config")));
            assertEquals(List.of(), ipc.queries().listNamedConfigs());
            assertTrue(Files.isDirectory(Path.of("config")));
            var service = new NamedConfigService();
            equalLists(local, ipc, service);
            switch (args[0]) {
                case "roundtrip" -> {
                    AppConfig c = config();
                    local.commands().saveNamedConfig("z-last", c);
                    byte[] fromLocal = Files.readAllBytes(Path.of("config/z-last.json"));
                    ipc.commands().saveNamedConfig("z-last", c);
                    assertArrayEquals(fromLocal, Files.readAllBytes(Path.of("config/z-last.json")));
                    service.save("a-first", new AppConfig());
                    equalLists(local, ipc, service);
                    assertEquals(Set.of("z-last", "a-first"), new HashSet<>(ipc.queries().listNamedConfigs()));
                    equalLoads(local, ipc, service, "z-last");
                    assertEquals(JSON.valueToTree(c), JSON.valueToTree(ipc.queries().loadNamedConfig("z-last")));
                    assertNull(ipc.queries().loadNamedConfig("z-last").getOutputFolder());
                    ipc.commands().deleteNamedConfig("z-last");
                    assertFalse(Files.exists(Path.of("config/z-last.json")));
                    local.commands().deleteNamedConfig("a-first");
                    assertEquals(List.of(), ipc.queries().listNamedConfigs());
                    errors(local, ipc, service, "z-last");
                }
                case "names" -> {
                    for (String name : Arrays.asList("", " ", null, "two.json", "configuration-中文")) {
                        service.save(name, new AppConfig());
                        local.commands().saveNamedConfig(name, config());
                        equalLoads(local, ipc, service, name);
                        ipc.commands().saveNamedConfig(name, new AppConfig());
                        equalLoads(local, ipc, service, name);
                        assertFalse(local.queries().loadNamedConfig(name).isHighSpeedMemoryMode());
                        equalLists(local, ipc, service);
                        ipc.commands().deleteNamedConfig(name);
                        assertFalse(local.queries().listNamedConfigs().contains(String.valueOf(name)));
                    }
                    local.commands().saveNamedConfig("null-config", null);
                    assertNull(ipc.queries().loadNamedConfig("null-config"));
                    ipc.commands().saveNamedConfig("null-config", null);
                    assertNull(local.queries().loadNamedConfig("null-config"));
                }
                case "errors" -> {
                    errors(local, ipc, service, "absent");
                    for (String name : List.of("invalid?name", "missing/child")) {
                        sameError(() -> service.save(name, config()), () -> local.commands().saveNamedConfig(name, config()),
                                () -> ipc.commands().saveNamedConfig(name, config()));
                        errors(local, ipc, service, name);
                    }
                    assertFalse(ipc.failure().isDone());
                    ipc.commands().saveNamedConfig("after-error", config());
                    equalLoads(local, ipc, service, "after-error");
                }
                case "missing-dir", "blocked-dir" -> {
                    Files.delete(Path.of("config"));
                    if (args[0].equals("blocked-dir")) Files.writeString(Path.of("config"), "obstruction");
                    equalLists(local, ipc, service);
                    assertEquals(List.of(), ipc.queries().listNamedConfigs());
                    sameError(() -> service.save("x", config()), () -> local.commands().saveNamedConfig("x", config()),
                            () -> ipc.commands().saveNamedConfig("x", config()));
                    errors(local, ipc, service, "x");
                }
                case "legacy" -> {
                    Files.writeString(Path.of("config/old.json"), "{\"separateFiles\":true,\"inputFolder\":\"legacy\",\"unused\":1}");
                    equalLoads(local, ipc, service, "old");
                    Files.writeString(Path.of("config/bad.json"), "{ broken");
                    // Parser errors include different stream identities; both must remain recoverable IO failures.
                    assertThrows(IOException.class, () -> service.load("bad"));
                    assertThrows(IOException.class, () -> local.queries().loadNamedConfig("bad"));
                    assertThrows(IOException.class, () -> ipc.queries().loadNamedConfig("bad"));
                    equalLists(local, ipc, service);
                    assertFalse(ipc.failure().isDone());
                }
                case "workspace" -> {
                    var events = new java.util.concurrent.CopyOnWriteArrayList<PortEvent>();
                    var before = ipc.queries().workspace();
                    try (var subscription = ipc.events().subscribe(events::add)) {
                        ipc.commands().saveNamedConfig("isolated", config());
                        ipc.queries().loadNamedConfig("isolated");
                        ipc.commands().deleteNamedConfig("isolated");
                        assertEquals(before, ipc.queries().workspace());
                        assertFalse(local.queries().canUndo());
                        assertTrue(events.isEmpty());
                    }
                }
                case "frontend" -> {
                    var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
                    var caller = Thread.currentThread();
                    var result = new java.util.concurrent.atomic.AtomicReference<AppConfig>();
                    var failed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                    try (var frontend = new com.mhtextractor.uicore.FrontendPort(ipc, callbacks::add, ignored -> { })) {
                        frontend.namedConfig(backend -> {
                            assertNotEquals(caller, Thread.currentThread());
                            backend.commands().saveNamedConfig("queued", config());
                            assertEquals(List.of("queued"), backend.queries().listNamedConfigs());
                            var loaded = backend.queries().loadNamedConfig("queued");
                            backend.commands().deleteNamedConfig("queued");
                            assertEquals(List.of(), backend.queries().listNamedConfigs());
                            return loaded;
                        }, result::set, failed::set, true);
                        Runnable success = callbacks.poll(15, java.util.concurrent.TimeUnit.SECONDS);
                        assertNotNull(success);
                        assertNull(result.get()); assertNull(failed.get());
                        success.run();
                        assertEquals(JSON.valueToTree(config()), JSON.valueToTree(result.get()));
                        frontend.namedConfig(backend -> backend.queries().loadNamedConfig("absent"),
                                ignored -> { throw new AssertionError("failure became success"); }, failed::set, true);
                        Runnable error = callbacks.poll(15, java.util.concurrent.TimeUnit.SECONDS);
                        assertNotNull(error); assertNull(failed.get());
                        error.run();
                        assertInstanceOf(IOException.class, failed.get());
                        assertFalse(ipc.failure().isDone());
                        assertTrue(callbacks.isEmpty());
                    }
                }
                default -> throw new AssertionError(args[0]);
            }
            System.out.println("CONTRACT_OK " + args[0]);
        } finally { ipc.close(); }
    }
}
