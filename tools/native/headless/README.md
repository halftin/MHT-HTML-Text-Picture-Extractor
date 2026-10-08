# Headless native metadata (P4-3)

This independent subset is passed explicitly through
`-H:ConfigurationFileDirectories` by `NATIVE_TARGET=headless` in `build-native.cmd`.
It is outside `src/main/resources`, so GUI dynamic/static builds do not load it.
The seven existing GUI metadata files remain unchanged.

## Build and verify

```bat
set JAVA_HOME=D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3
set BUILD_JAVA_HOME=%JAVA_HOME%
set GRAALVM_HOME=
set NATIVE_RECIPE=dynamic
set MAVEN_USER_HOME=H:\TEST\m2
set MAVEN_OPTS=-Dmaven.repo.local=H:\TEST\m2\repository
set JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=H:\TEST\temp
set NATIVE_TARGET=headless
build-native.cmd
```

Defaults: dist `H:\TEST\mht\p4-3-headless-dist`, inputs
`H:\TEST\mht\p4-3-native-work`, logs `H:\TEST\mht\p4-3-native-logs`.
`OUTPUT_DIR`, `HEADLESS_WORK_DIR`, and `LOG_DIR` may override these within
`H:\TEST\mht\`; dist and work must be separate.
The CLI retains its console subsystem. It does not package JavaFX DLLs or the GUI
launcher. Runtime temporary files still require `-Djava.io.tmpdir=H:\TEST\temp`
on the native executable (native executables do not read `JAVA_TOOL_OPTIONS`).

`prepare-headless.ps1` assembles business classes and runtime dependency jars in
the H-drive input directory. It excludes GUI classes/resources, UI jars, and all
automatic native metadata. The optional FX adapters and module descriptor in the
**copy** of the existing webp jar are omitted; the pure Java decoder is retained.
Original dependency jars and GUI inputs are untouched.

The image charset follows the build host's Windows ANSI codepage (`GBK` on this
CP936 host), because Graal decodes CRT command-line arguments with the image
charset. This preserves the Chinese template argument in the existing native
golden smoke test. Product writers retain their existing explicit encodings.
The headless file logger explicitly uses UTF-8, independently of this image
charset; the existing smoke test still reads and checks its UTF-8 log.
`HEADLESS_CHARSET` can override this build setting. Other system codepages and the
full Chinese-directory deployment matrix remain P4-4 validation work.

Use a native-image JDK without bundled JavaFX (the script's default dynamic JDK
probe selects it automatically; an explicit `GRAALVM_HOME` may select another such
JDK). Liberica NIK Full 25.0.3 registers JavaFX initialization entries through its
internal feature even with module roots limited; the artifact assertion rejects
that configuration. The compilation JDK can still be the repository's NIK Full.

The fast 17th `ArchitectureTest` invariant parses JSON and scans decoded text plus
properties for `javafx/`, `javafx.`, and backslash paths, case-insensitively.
`assert-headless-metadata.ps1` enforces the same rule on the input snapshot and,
after native compilation, on the metadata sidecar **and** native-image's class
initialization analysis. Failures return a nonzero exit code.
`build-inputs.tsv` records input SHA-256 values; the existing build report records
the backend jar and all dist artifacts.

To reuse the existing subprocess smoke tests against the native executable:

```bat
mvnw.cmd -Dtest=BackendOnceSmokeTest -Dmht.backend.executable=H:\TEST\mht\p4-3-headless-dist\MhtExtractorBackend.exe test
```

## Tracing provenance

Collected on 2026-10-08 with Liberica NIK 25.0.3's `native-image-agent.dll`, using
`config-merge-dir=H:\TEST\mht\p4-3-agent-final\metadata`. A second collection in
`H:\TEST\mht\p4-3-agent-utf8\metadata` merged that output and observed the explicit
UTF-8 log encoder's `setCharset` call. The subset JSON is copied byte-for-byte
from the second agent output; no reflection/resource entries were inferred or
copied from the GUI metadata.

The collection classpath excludes javafx, atlantafx and ikonli jars. JVM module
roots are `java.se,jdk.unsupported` (the agent needs Unsafe; no UI modules).
Coverage: production CLI text/images in zh/en; IPC import, group, parameters,
checked selection, preview creation and cache readback, text/images extraction;
the existing `NativeBaselineProbe`, 21 cases x zh/en, each executed twice, all
42 golden baselines matched under the tracing JVM.

P4-4 incremental capture (2026-10-08): `client.mjs --agent-dir
H:/TEST/mht/p4-4-agent-green` ran the real text IPC workflow with custom templates
and matched `text-template-vars.zh` by SHA-256. The agent output registered
`AppConfig.setStartTemplate(String)` and `setEndTemplate(String)`; only these two
observed methods were merged into this subset. Existing entries and the seven
GUI metadata files remain unchanged. The original P4-3 exe fails this workflow
with `MissingReflectionRegistrationError`; the P4-4 native matrix verifies it
after rebuilding. JVM agent roots include `jdk.unsupported` for Unsafe.

Subset: 58 reflection types (including backend logback sink, `AppConfig`, preview
cache records and warnings), two JNI-accessible types, 25 resource patterns and one locale
bundle. Missing-resource probes recorded by the agent are retained.

Raw collection commands, stdout/stderr and the probe command are retained under
`H:\TEST\mht\p4-3-evidence`, `H:\TEST\mht\p4-3-agent-final`, and
`H:\TEST\mht\p4-3-agent-utf8`.
Recollect into a fresh H-drive directory, union new observations into this subset,
and rerun both the negative control and native smoke/baseline checks after changes.
