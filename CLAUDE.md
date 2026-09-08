# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Compile (skip tests)
mvn package -DskipTests

# Run in dev mode
mvn javafx:run

# Build native EXE (requires GraalVM JDK 25 + VS build tools)
build-native.cmd
```

## Architecture

The project is a **JavaFX 25 desktop GUI** (MVC pattern) for extracting text and images from `.mht` (MIME HTML) files. Entry point: `com.mhtextractor.App`.

### Layer 1 — Core Parsing (`com.mhtextractor.core`)

- **`MimeParser`** — Parses `.mht` files via Jakarta Mail (MIME tree traversal). Returns `ParsedMht` containing: `htmlContent`, `cidMap` (Content-ID → bytes), `locationDataMap` (Content-Location → bytes), and `imageInfoList` (ordered image metadata). Handles recursive depth limits (threshold: 100) and encoding recovery (tries UTF-8, GBK, GB2312, GB18030, ISO-8859-1).
- **`ContentExtractor`** — Extracts plain text from HTML via Jsoup. Supports area limiting (start/end markers, optional regex), encoding auto-fix, and chapter title extraction.
- **`ChapterGenerator`** — Generates Chinese chapter numbering (第一章, 第二章…).

### Layer 2 — Extractors (`com.mhtextractor.extractor`)

- **`BaseExtractor`** — Abstract base holding a `ParsedMht` reference and warning list.
- **`TextExtractor`** — Delegates HTML → plain text conversion to `ContentExtractor`.
- **`ImageExtractor`** — Extracts images from parsed MHT. Supports: region limiting, ad filtering (keyword blacklist), deduplication by CID/Location, Data URI extraction, and magic-number-based format detection.

### Layer 3 — Batch Processors (`com.mhtextractor.processor`)

- **`BatchProcessor`** — Abstract base: file collection (sorted by modified time), progress callbacks, cancel support, `ProcessStats` accumulation.
- **`TextBatchProcessor`** — Two modes: merged (single TXT) or separate (one TXT per MHT). Applies chapter numbering and title extraction per file.
- **`ImageBatchProcessor`** — Two modes: streaming (parse on demand) or high-speed memory (preload all). Supports single output dir or per-file dirs, plus optional ZIP/CBZ archiving. Uses strategy pattern internally (`ParsedMhtProvider`, `ImageOutputHandler`).

### Layer 4 — Output (`com.mhtextractor.output`)

- **`FileWriter`** — Template-based text output with variable substitution (`{filename}`, `{datetime}`, etc.).
- **`ImageWriter`** — Image saving with parallel writes, sequence renaming, and magic-number extension correction.
- **`Archiver`** — ZIP/CBZ archive creation.

### GUI (`com.mhtextractor.gui`)

- **`MainController`** — FXML controller for the single-window preview workspace: mode switching (text/image), input/output dir selection, file list with drag reorder, chapter badges, per-file chapter override, text preview / lazy image preview, pre-parse thread, worker thread launch/cancel, and `logs/gui-state.json` persistence. Holds two per-mode confirmed param caches (`textParamsCache`/`imageParamsCache`; null = params not confirmed for that mode — starting extraction then shows a confirmation prompt). Contains tracing agent support (`exerciseAllFeatures()`) and automated test mode (`runAutomatedTests()`).
- **`ParamDialogController`** — Modal parameter dialog opened by the "提取参数设置" button. Shows the current mode's param group (text/image), GUI state preserved per mode across switches; only 确定 commits (cancel/X discard). Config CRUD (load/save/delete named configs) lives in its bottom fixed rows. Loading a config only fills the current mode's controls.
- **`LogTextArea`** — Custom JavaFX component registered as Logback consumer via `TextAreaAppender`.
- **`MainView.fxml`** — Top: mode toggles + input/output dirs. Center: preview workspace (file list left, info/preview area right, "提取参数设置" button). Bottom: progress, status, "打开输出文件夹" toggle, start/stop, log. Parameter controls live in `ParamDialog.fxml`.

### Config (`com.mhtextractor.config`)

- **`AppConfig`** — POJO with Jackson annotations. Named configs (in `config/`) store extraction params ONLY: `inputFolder`/`outputFolder`/`imageModeEnabled`/`openFolderAfterComplete` are `@JsonIgnore` (runtime fields set by MainController at extraction time). Legacy config files with those fields still load (unknown/ignored).
- **`ConfigManager`** — Save/load/delete named configs in `config/` directory.
- **`GuiState` / `GuiStateManager`** — Main-window GUI state (mode, input/output dirs, open-folder-after-complete) persisted as a single JSON at `logs/gui-state.json`, restored on startup.

### Native Image

GraalVM reachability metadata lives in `src/main/resources/META-INF/native-image/`. The `build-native.cmd` script compiles with Liberica NIK 25, extracts JavaFX DLLs, then invokes `native-image` with `--no-fallback`. `App.java` has two special modes: `agent.exit.delay` (tracing agent exercise) and `test.mode=true` (automated GUI tests outputting to file).

> **编译环境注意**：项目须用 **Liberica NIK 25** 编译（pom 注释与 `build-native.cmd` 一致）。在 Oracle OpenJDK 25.0.2 上，javac + Lombok 在 `-sourcepath` 含多个条目（maven-compiler-plugin 默认行为）时注解处理会静默失效（全项目报 `找不到符号 log`/getter）；JDK 21/22 又无法读取 JavaFX 25 的 class 文件，因此本机编译请使用 NIK 25。`<release>` 已改为引用 `${maven.compiler.release}` 属性，必要时可用 `-Dmaven.compiler.release=21` 覆盖做快速语法检查。

### Key dependencies

Jakarta Mail 2.0.2 (MIME parsing), Jsoup 1.18.1 (HTML), Jackson 2.18.6 (config JSON), AtlantaFX 2.1.0 (Nord Light theme), Logback 1.5.26 (logging).
