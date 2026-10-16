[中文](README.md) · **English**

# MHT/HTML Resource Extractor 3.0

> A Windows desktop tool that **batch-extracts plain text and images** from `.mht` / `.mhtml` / `.html` / `.htm`
> files (including HTML sidecar folders). Release name **MHTExtractor3.0** (source version `3.0.0`).
> UI languages: **中文 / English**.

---

## 1. What it does

| Feature | Description |
|---|---|
| **Batch import** | Drag in or pick multiple `.mht`/`.mhtml`/`.html`/`.htm` files, or **a whole folder**; an HTML file plus its sidecar folder (`xxx.files/`) counts as one source |
| **Grouping** | Group / ungroup / rename (group names kept unique) / collapse-expand; groups and items share the same parameter semantics |
| **Three-level parameters** | **Global / group / per-item** parameters with undo, copy-paste, and named-config save & apply |
| **Text extraction** | Parse MIME/HTML into plain text; automatic title detection; chapter naming (Chinese 「第一章」 / Western 「Chapter 1」) |
| **Image extraction** | CID/Location de-duplication, Data URIs, ad filtering, **region limiting** (extract only the marked range), magic-number extension correction |
| **Preview** | "Preview results" renders real text plus thumbnails; click a thumbnail to open it in the system viewer; session-level preview cache (a cache hit skips parsing entirely) |
| **Output** | Name-template variables `{filename}` (full name) / `{basename}` (without extension) / `{datetime}` / `{date}` / `{time}`; merge or split text; merge or separate images; ZIP/CBZ archiving |
| **Interface** | Chinese / English bilingual UI; non-modal parameter side panel; in-app notification cards (error / warning / confirm / waiting); built-in log panel |
| **Single instance** | Only one instance may run; a second launch is notified and exits automatically |
| **Headless backend (advanced)** | The bundled `Backend\MhtExtractorBackend.exe` provides a CLI and a `--serve` mode for scripting; the GUI does not need it |

---

## 2. System requirements

| Item | Requirement |
|---|---|
| OS | **Windows 10 / 11 x64** |
| Runtime | **Nothing to install** — no Java / .NET / VC++ runtime needed; the package ships its own JVM and DLLs |
| Memory | **Dynamic cap: 50% of the machine's physical RAM** (see "Memory behaviour") |
| Disk | The preview cache lives in the system temp directory; the output directory is your choice |
| **Path** | ⚠️ **The full path of the folder containing the program must be ASCII-only** (see "Known limitation") |

### Memory behaviour (you can adjust it)

- The release uses a **dynamic cap** of `-XX:MaximumHeapSizePercent=50`: about **7.7 GB** on a 16 GB machine and
  **3.8 GB** on an 8 GB machine — it adapts to the host.
  (Measured: on a 15.3 GB machine the cap is 7849 MB ≈ 50%. The default would have been 80%; this release lowers it to 50%.)
- **Want a smaller cap?** Add a runtime argument when starting (verified to work):

```
MhtExtractor.exe -XX:MaxHeapSize=2g
```

- A handy trick: right-click `MhtExtractor.exe` → Send to → Desktop (create shortcut), then append the argument to the shortcut's **Target**.

---

## 3. Download and folder layout

Unzip the release archive to get **one ASCII directory** (**do not rename it to a Chinese/Japanese or other non-ASCII name**):

```
MHT-HTML-Extractor3.0/            <-- the folder name must stay ASCII
├── MhtExtractor.exe              <-- double-click to start (GUI, 68.5 MB)
├── glass.dll  prism_common.dll  prism_d3d.dll  prism_sw.dll
├── decora_sse.dll  javafx_font.dll  javafx_iio.dll          <-- JavaFX native DLLs
├── jvm.dll  java.dll  awt.dll  javajpeg.dll  lcms.dll
├── javaaccessbridge.dll  jawt.dll                           <-- JVM / image native libraries
├── msvcp140*.dll  vcruntime140*.dll                         <-- VC++ runtime (bundled, no install)
├── Backend/
│   └── MhtExtractorBackend.exe    <-- headless backend (advanced; the GUI does not need it)
├── README.en.md                   <-- this file
├── README.md                      <-- Chinese version
└── LICENSE                        <-- Mulan PSL v2
```

> This release ships **no** `启动.cmd` / `Launch.cmd` batch launcher — just double-click `MhtExtractor.exe`,
> and respect the **ASCII-only path** requirement below.

---

## 4. Known limitation: the program folder must be an ASCII-only path

**Symptom**: if you put the program in a folder whose path contains non-ASCII characters (for example
`D:\我的工具\提取器\`), double-clicking `MhtExtractor.exe` **fails to start**.

**Measured evidence** (same build, only the containing folder differs):

| Containing folder | Result |
|---|---|
| `H:\TEST\mht\e2e-ascii\MHT-HTML-Extractor3.0\` (all ASCII) | ✅ process stays alive, window title "MHT 资源提取器" |
| `H:\TEST\mht\中文目录测试\MHT-HTML-Extractor3.0\` (parent folder has Chinese) | ❌ **exit code 1, no window** |

**Cause**: the program is built with GraalVM native-image, and **JavaFX native DLL loading only supports ASCII
paths** — a limitation of that toolchain, not a logic defect of this tool.

**Workaround**: **keep the folder ASCII; the file name may be anything.**

| Placement | Result |
|---|---|
| `D:\tools\MHT-HTML-Extractor3.0\` | ✅ works |
| `D:\工具\MHT-HTML-Extractor3.0\` | ❌ may fail to start |
| `D:\tools\提取器3.0.exe` (**file name** non-ASCII, folder ASCII) | ✅ works (the limit applies only to the **directory path**) |

---

## 5. Quick start (5 steps)

1. **Unzip** into an ASCII-only folder → double-click `MhtExtractor.exe`.
2. **Import**: drag `.mht`/`.html` files or an entire folder **onto the window**, or use "Add files / Add folder".
3. **Organise** (optional): tick items → "Group"; right-click an item or group to rename, remove, or set parameters.
4. **Set parameters** (optional): in the parameter side panel choose the target (global / group / item) → fill in the output
   directory, name template, image region markers, etc. → save or apply to the target.
5. **Extract**: click "Extract text" or "Extract images" (or "Preview results" first to check) → collect the results from the output folder.

---

## 6. FAQ

| Problem | What to do |
|---|---|
| Double-click does nothing / window flashes and closes | 99% it is a **non-ASCII path** → move the folder to an ASCII path as described in section 4 |
| First launch is a bit slow | First load of the native image plus font initialisation; it gets faster afterwards |
| Antivirus warning | Common false positive for small unsigned tools; whitelist it, or build from source yourself |
| Not enough memory / want a lower cap | Override at run time with `-XX:MaxHeapSize=…` (see section 2) |
| No images extracted | Check whether "region limiting" is enabled with wrong start/end markers; the preview shows a notification card |
| Batch / scripted use | `Backend\MhtExtractorBackend.exe --help` (headless CLI / `--serve` IPC mode) |

---

## 7. Building from source (optional)

```
Prerequisites: Windows x64 + JDK 25 (with native-image, e.g. BellSoft Liberica NIK 25) + MSVC toolchain

1) Environment check   check-env.cmd        (exit 0 = ready / 20 = MSVC missing / 1 = something else missing)
2) One-shot build      build-native.cmd     (dynamic recipe by default; output goes to the dist folder)
3) Other recipes       set NATIVE_RECIPE=static      (JavaFX linked into the exe: bigger, slower start)
                       set NATIVE_TARGET=headless    (build the headless backend only)
```

Tech stack: Java 25 + JavaFX 25 + GraalVM native-image (Liberica NIK 25); `build-native.cmd` is the single build implementation.

---

## 8. License and feedback

- License: **Mulan Permissive Software License v2 (Mulan PSL v2)** — see `LICENSE`.
- Questions and suggestions: please open an Issue on the project page (Gitee / GitHub).
