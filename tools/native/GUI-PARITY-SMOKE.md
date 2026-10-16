# JVM/native GUI evidence

Run serially after packaging `target/classes`, `target/lib` and `target/jfx-dlls`.
Supply a JDK 25 with JavaFX as `JavaHome`, an Oracle GraalVM 25 without JavaFX
static libraries as `GraalHome`, and a matching production native backend as
`Backend`. These tools use the dynamic recipe. MSVC is discovered with the same
`vswhere`/`vcvars64.bat` sequence as `native-preview-warning-smoke.ps1`.

```powershell
$jdk = $env:BUILD_JAVA_HOME
$graal = $env:GRAALVM_HOME
$backend = 'H:\TEST\mht\my-dist\Backend\MhtExtractorBackend.exe'
& tools/native/native-screenshot-parity.ps1 -OutDir H:\TEST\mht\parity -Threshold 0 `
    -JavaHome $jdk -GraalHome $graal -Backend $backend
# A builds the shared source once. B can reuse only an image whose source,
# target/classes, dependency hashes and executable hash still match.
& tools/native/native-gui-e2e-smoke.ps1 -OutDir H:\TEST\mht\e2e -JavaHome $jdk `
    -Backend $backend -BuildDir H:\TEST\mht\parity\build
& tools/native/native-gui-e2e-smoke.ps1 -OutDir H:\TEST\mht\e2e-red -JavaHome $jdk `
    -Backend $backend -BuildDir H:\TEST\mht\parity\build -InjectWrongSource
# Expected exit 1, specifically RED_CONTROL_MATCH, after the source assertion.
& tools/probes/CrossProcessLogPurityProbe.ps1 -OutDir H:\TEST\mht\purity `
    -JavaHome $jdk -Backend $backend
& tools/probes/CrossProcessLogPurityProbe.ps1 -OutDir H:\TEST\mht\purity-red `
    -JavaHome $jdk -Backend $backend -InjectCjk
# Expected exit 1 with en application log CJK=2; zh controls still run.
```

Use fresh output directories. Each entry also accepts `TempRoot` (default
`H:\TEST\temp`). A rejects threshold 1 because its known RGB mutation must fail
at the same threshold. All six pairwise comparisons are retained: JVM/JVM,
native/native and four cross pairs. Nonzero comparison exits make A return 1
after completing the controls. This is evidence of difference, not an
acquisition failure. Every comparison also has an independent ARGB bounding-box
measurement. Do not classify equal-sized cross/control differences as noise
without inspecting their location/content.

The shared Java probe loads production FXML, `MainController`, `FrontendPort`,
`IpcUiport` and the native `BackendCli`. It imports through the real input field,
sets parameters through the public production command, fires the real preview
button and fires `WINDOW_CLOSE_REQUEST` into the installed production lifecycle.
The screenshot scenario is the empty main view, 1100x720 scene pixels; the
import/preview scenario is covered separately by B. This is a scoped smoke of
the production assembly, not a test of the distribution launcher or `App.main`.
No test-source classes, private reflection or additional product accessors are
used. PNG output uses a minimal lossless JDK zip encoder shared by both builds.

The scripts seed only the isolated `work/logs/gui-state.json` locale and use
the default production `logback.xml`/`logback-headless.xml`, INFO levels and
appenders. The native backend gets `mht.logs.root=<work>/logs` to isolate its
default `backend.log`; no filter or language substitution is applied. `TEMP`,
`TMP`, `JAVA_TOOL_OPTIONS` and explicit `java.io.tmpdir` point to caller-selected
H drive locations. The screenshot acquisitions reuse one working-directory
path before archiving it, preserving the visible default output-directory
field. Runtime/process cleanup failures are fatal and retain evidence.

C captures actual `PortEvent.BackendLog` values in `backend-events.txt`, and
uses production `BackendEventPresenter` to render them into
`forwarded-backend.log`. It also counts the GUI and backend default logs. The
workflow deliberately enables region extraction with missing markers, producing
real `log.imageextractor.01` WARN events. Each file must be nonempty; each en
count must be zero, each zh count positive. Counted code points are
U+3400..4DBF, U+4E00..9FFF, U+F900..FAFF, U+20000..2FFFF,
U+30000..323AF. UTF-8 decoding is strict.

Count-only and independent recount (replace `work` with the locale directory):

```powershell
$files = @('work/logs/mht-extractor.log', 'work/logs/backend.log', 'work/forwarded-backend.log')
& tools/probes/CrossProcessLogPurityProbe.ps1 -CountFiles $files -Expected en
& "$jdk/bin/java.exe" tools/verify/CjkLogCount.java en @files
```

Every child command, stdout, stderr and Win32 exit code is saved separately.
The positive path relies on natural process exit, not `Process.ExitCode` or a
forced successful exit. A `[PASS]` marker without the process/temp checks does
not prove cleanup. Formal screenshot baseline approval, other DPI/scaling,
non-ASCII screenshot paths, static recipe and physical mouse interaction remain
outside this smoke's scope.
