# Dual frontend contract

`dual-client.mjs` uses only IPC JSON and the shared handshake, framing, process and
FileRef facilities exported by `client.mjs`. Importing that module does not launch
the legacy smoke workflow. Node needs no packages beyond its built-in modules.
Self-test uses an isolated scratch directory and removes it; CI can select its
scratch root with `--self-test --temp <directory>` without Java or a backend.

Run from the repository root after compiling the Java classes and preparing
`target/lib` (or supply `--classpath`):

```powershell
$env:JAVA_HOME = 'D:/install/bellsoft-liberica-vm-full-openjdk25-25.0.3'
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=H:/TEST/temp'
node tools/ipc/dual-client.mjs --self-test
node tools/ipc/dual-client.mjs --out H:/TEST/mht/dual-jvm
node tools/ipc/dual-client.mjs --backend <MhtExtractorBackend.exe> --out H:/TEST/mht/dual-native
./mvnw.cmd '-Dtest=DualFrontendContractTest' test
./mvnw.cmd '-Dtest=DualFrontendContractTest' '-Dmht.dual.backend=<MhtExtractorBackend.exe>' test
```

The filtered identity defaults to `target/classes/backend-build.properties`;
`--identity-file` overrides it. The expected protocol version is the single hello
table in `client.mjs`, covered by `BackendIpcContractTest`. The greeting cannot
choose the client's expected identity.

`--scenario-file <json>` accepts `paths` (absolute fixture paths), `groupName`,
`textParams` and `imageParams` (IPC Config objects). Defaults use `data-uri.mht`
and `single-image.mht` from `src/test/resources/fixtures`. Both parameter pages
round-trip through `paramValues`/`collectParams` before confirmation. Each mode
then runs preparse, image preview, worker waiting and asynchronous extraction.
Image preview explicitly selects image mode even when extracting text.

`--out` contains the exact backend command, stdin/stdout JSONL, stderr, logs,
products and `tree.json`. Trees contain sorted relative paths and raw file-byte
SHA-256. These scenarios disable archives: archive timestamps require
`BaselineHasher`'s decompressed-entry convention and are outside this byte test.
`DualFrontendContractTest` independently hashes both output directories using
`BaselineHasher`, compares group/member order and checked state, and compares the
neutral count parameters used by `summaryLine`, without comparing locale text
or elapsed time. Its `sha-comparison.json` includes both rendered summaries.

Events must own strictly increasing revisions; responses may repeat the current
revision. Unsafe JSON integer revisions are rejected rather than rounded.
Preparse start precedes finish; import finish precedes importing=false. Each
successful extraction has a running true/false pair and exactly one terminal;
its stats are in the terminal, with no cancellation-only `extractionResult`.
Async progress retains backend order and can briefly decrease. No ordering is
assumed between different producers or between terminal and running=false.

Every encountered FileRef, including nested event/snapshot/preview references,
is read and released before continuing. Immediate `fileInfo` rejection verifies
revocation. Owned bulk files must disappear; borrowed images must survive until
backend cache cleanup. EOF must exit zero and remove `welcome.bulkRoot`.

Negative controls can disable `enableCustomTemplate` in the text
`setGlobalParams` request (the contract must fail with `PRODUCT_TREE_MISMATCH`),
omit `releaseFiles` (the next `fileInfo` check must fail), change a reported
member's `checked` value, or change a reported stats `summaryParams.warned` value.
Restore the script bytes and rerun before acceptance. Self-test includes bad
frames, invalid error codes, malformed/outside/deleted FileRefs, revision
regression/overflow and event-order controls without a backend.

Scope and risks: native builds can differ in metadata and runtime behavior;
selecting a JVM backend proves no other native build. This fixture tests success
events, not cancellation races or slow/blocked pipes. New event shapes and new
FileRef fields need corresponding consumption checks; EOF cleanup alone cannot
prove timely release. The default fixture is deliberately small and does not
cover archives, all formats, languages or a new graphical frontend.
