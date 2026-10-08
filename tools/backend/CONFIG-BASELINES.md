# AppConfig CLI and product baselines (P4-5)

`BackendCli --once --config <path>` reads a UTF-8 named AppConfig JSON object.
Missing properties keep AppConfig defaults (notably `autoFixEncoding=true`).
The configuration is validated before importing files or creating the output directory.

Input/output paths, extraction kind (`--extract text|images`), grouping and locale
remain CLI options. The four runtime-only properties (`inputFolder`, `outputFolder`,
`imageModeEnabled`, `openFolderAfterComplete`) are rejected in JSON.
`--config` is only supported with `--once`; IPC continues to use its existing `setParams` method.

Explicit `--separate` sets `separateFiles=true`. Either explicit template option
sets `enableCustomTemplate=true` and replaces only its corresponding template;
the other template retains its config value. Flags override config regardless of
argument order. With no config, existing flag behavior and defaults are retained.

Use the persisted names, including these mappings:

| AppConfig Java property | JSON property |
|---|---|
| enableContentLimit | enableLimit |
| enableTemplate | enableCustomTemplate |
| enableTitleExtraction | enableTitleExtract |
| enableNameExtraction | imageNameExtractEnabled |
| enableRegionExtraction | imageRegionExtractEnabled |
| enableBatchRenaming | imageRenameEnabled |
| enableZipPackaging | imageZipEnabled |
| enableCbzPackaging | imageCbzEnabled |
| highSpeedMemoryMode | legacyMemoryMode |

Other extraction fields use their existing names, including `addChapterNumber`,
`separateFiles`, `singleOutputMode`, markers, templates and `useRegex`/`titleUseRegex`.
The four deprecated string fields are still accepted for named-config compatibility.
No GUI serialization, bundles or product formats change.

JSON syntax errors, unknown/ignored properties, wrong types, boolean null,
duplicate keys, trailing JSON values and non-object roots exit **2** with the path
and readable reason on stderr. There is no scalar coercion (for example, string
`"true"` is not a boolean, and number `42` is not a string). String null is accepted.
Unreadable/missing files also exit 2.

Example:

```json
{
  "addChapterNumber": true,
  "enableTitleExtract": true,
  "titleStartMarker": "<!--NAME_START-->",
  "titleEndMarker": "<!--NAME_END-->",
  "separateFiles": true
}
```

```powershell
$env:JAVA_HOME='D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3'
$env:JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=H:\TEST\temp'
$env:MAVEN_USER_HOME='H:\TEST\m2'
$env:MAVEN_OPTS='-Dmaven.repo.local=H:\TEST\m2\repository'
.\mvnw.cmd @('-Dtest=BackendConfigTest,BackendConfigBaselineTest','test')
```

`BackendConfigBaselineTest` runs all 21 `BaselineCases` in zh/en through real
CLI subprocesses. JVM children limit module roots to `java.se` and remove UI
jars from their classpath. Parent staging directories encode the case's declared
fixture order because CLI imports sort absolute paths; source filenames, bytes,
fixed mtimes and HTML sidecars are preserved. No source is renamed and no golden
JSON is rewritten. `singleOutputMode` retains its actual config value even when
the case's `mergedOutput` metadata differs (the existing `html-same-dir` case).

Comparison uses the same `BaselineHasher` and content-digest implementation as
`ProductBaselineTest`. It compares the complete parsed JSON, including product
paths/hashes, archive entries after decompression, content digests, counts and
per-file outcomes. Object key order is ignored; arrays and values must match.
The CLI prints `result: <basename>=<outcome>` and appends `warn` to its existing
`done` summary to allow independent stats verification. `text-boundary` expects
exit 1, because its golden stats contain failure/skip results.

Evidence is persistent under `H:\TEST\mht\p4-5-jvm-baseline` or
`p4-5-native-baseline`, with a fresh directory per test run. Each matrix row points
to `config.json`, `command.json`, stdout/stderr, exit code, products,
`actual.json`, `expected.json`, and (on failure) `failure.txt`.
`matrix.tsv` is printed row by row and saved after every case.
Override the root with `-Dmht.backend.evidence=H:/TEST/mht/p4-5-...`.

To test an executable, use:

```powershell
.\mvnw.cmd @('-Dtest=BackendConfigBaselineTest,BackendConfigTest,BackendOnceSmokeTest',
  '-Dmht.backend.executable=H:/TEST/mht/p4-5-headless-dist/MhtExtractorBackend.exe','test')
```

An older executable without `--config` must be rebuilt with `NATIVE_TARGET=headless`.
Only the independent headless metadata subset may be extended, using observations
from the tracing agent. To collect the actual CLI matrix on a tracing JVM:

```powershell
.\mvnw.cmd @('-Dtest=BackendConfigBaselineTest',
  '-Dmht.backend.agentDir=H:/TEST/mht/p4-5-agent','test')
```

The tracing run adds `jdk.unsupported` to the otherwise restricted module roots
for the agent's Unsafe support, merges observations across sequential children,
and still compares every baseline. No agent is used in ordinary JVM validation.

CG-1: inject `"unknownSetting":true` into a valid config, run the same CLI command,
check exit 2 and `Unrecognized field`, then remove it and verify exit 0/products.
`BackendConfigTest` also exercises 18 malformed configs in real subprocesses.

CG-2: the following command intentionally flips `addChapterNumber` in only the
generated `text-merged-chapter.zh` config. It must fail the golden comparison;
rerun without the mutation property to restore green. It never changes sources
or baseline files.

```powershell
.\mvnw.cmd @('-Dtest=BackendConfigBaselineTest',
  '-Dmht.backend.baseline.case=text-merged-chapter','-Dmht.backend.baseline.locale=zh',
  '-Dmht.backend.baseline.mutate=true','test')
.\mvnw.cmd @('-Dtest=BackendConfigBaselineTest',
  '-Dmht.backend.baseline.case=text-merged-chapter','-Dmht.backend.baseline.locale=zh','test')
```

Filters selecting no cases fail. Without filters the matrix must contain 42 rows.
Run native builds, GUI automation and `mvn clean` sequentially, as required by
the repository validation discipline.
