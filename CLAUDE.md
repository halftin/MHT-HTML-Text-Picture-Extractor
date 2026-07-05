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

- **`MainController`** — FXML controller managing mode switching (text/image), config CRUD via `ConfigManager`, worker thread launch/cancel. Contains tracing agent support (`exerciseAllFeatures()`) and automated test mode (`runAutomatedTests()`).
- **`LogTextArea`** — Custom JavaFX component registered as Logback consumer via `TextAreaAppender`.
- **`MainView.fxml`** — Single-view layout with ToggleButtons for mode selection, TitledPanes for settings, progress bar, and log output.

### Config (`com.mhtextractor.config`)

- **`AppConfig`** — POJO with Jackson annotations for JSON serialization.
- **`ConfigManager`** — Save/load/delete named configs in `config/` directory.

### Native Image

GraalVM reachability metadata lives in `src/main/resources/META-INF/native-image/`. The `build-native.cmd` script compiles with JDK 21, extracts JavaFX DLLs, then invokes `native-image` with `--no-fallback`. `App.java` has two special modes: `agent.exit.delay` (tracing agent exercise) and `test.mode=true` (automated GUI tests outputting to file).

### Key dependencies

Jakarta Mail 2.0.2 (MIME parsing), Jsoup 1.18.1 (HTML), Jackson 2.18.6 (config JSON), AtlantaFX 2.1.0 (Nord Light theme), Logback 1.5.26 (logging).
