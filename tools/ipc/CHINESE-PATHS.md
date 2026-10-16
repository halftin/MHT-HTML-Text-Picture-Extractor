# P4-4 中文路径矩阵

`chinese-path-matrix.mjs` 使用 Node 内置模块，默认跑 8 个场景 × 4 种模式 × zh/en = 64 格。
场景是中文输入目录、中文输出目录、两者中文、中文嵌套（空格、全角括号、`&`）、中文文件名、
中文临时目录、exe 自身所在中文目录、文本模板黄金基线。前七种提取图片，最后一种提取文本。
所有产物落 H 盘；不更改 JavaFX 前端、启动器或打包手册。

在仓库根用 PowerShell 设置环境并准备 classpath（已有 classpath 文件时可复用）：

```powershell
$env:JAVA_HOME = 'D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3'
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=H:\TEST\temp -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8'
$env:TEMP = 'H:\TEST\temp'
$env:TMP = 'H:\TEST\temp'
$env:MAVEN_USER_HOME = 'H:\TEST\m2'
$env:MAVEN_OPTS = '-Dmaven.repo.local=H:\TEST\m2\repository'
.\mvnw.cmd -q -DskipTests compile
.\mvnw.cmd dependency:build-classpath '-Dmdep.outputFile=H:\TEST\mht\p4-4-classpath.txt'
node tools/ipc/chinese-path-matrix.mjs --classpath-file H:/TEST/mht/p4-4-classpath.txt --native H:/TEST/mht/p4-4-headless-dist/MhtExtractorBackend.exe --controls
```

`--root` 可指定 **H:/TEST/mht 下全新的目录**，已存在的格子会被拒绝，防止读取上次产物而假绿。
省略时生成 `p4-4-matrix-*`。可以用 `--scenes input,output`、`--modes jvm-once,native-serve`、
`--locales zh,en` 缩小复跑范围。任一非预期失败使矩阵退出 1；全格 PASS 或只有已知限制时退出 0。

默认 native 路径仍指向 P4-3 exe，优先复用。该版本严格要求临时根等于 `H:/TEST/temp`，
所以中文临时目录的 serve 格需要包含 P4-4 `IpcFiles` 修复的 exe；文本 IPC 模板还需要
P4-4 tracing agent 实采的两个 setter 元数据增量（只修改后端子集）。
仅在需要此修复时用既有构建入口重建无头版，勿覆盖 P4-3 已验收产物：

```powershell
.\mvnw.cmd -DskipTests package
$env:BUILD_JAVA_HOME = $env:JAVA_HOME
$env:GRAALVM_HOME = 'D:\JAVA\graalvm-jdk-25_windows-x64_bin\graalvm-jdk-25.0.2+10.1'
$env:NATIVE_TARGET = 'headless'
$env:OUTPUT_DIR = 'H:\TEST\mht\p4-4-headless-dist'
$env:HEADLESS_WORK_DIR = 'H:\TEST\mht\p4-4-native-work'
$env:LOG_DIR = 'H:\TEST\mht\p4-4-native-logs'
.\build-native.cmd --skip-package
```

不得与 GUI 自动化或 native 构建并发执行 `mvn clean`。

每格保留 `assertions.log`（命令、退出码、断言原文）、`capture.stdout` / `capture.stderr`（原始字节）、
`logs/backend.log`。IPC 格通过同一个 `client.mjs --scenario-file` 执行导入→分页→建组→设参→预览→
提取→读取结果文件→释放→EOF 清理；保留原始 `stdin.jsonl` / `stdout.jsonl`、`stderr.txt`、
`command.json`、`bulk-evidence.json`、`summary.json`。stdout 每行必须是 v1 协议帧，stderr 单独读取。
汇总为 `matrix.json` / `matrix.md`，每格有证据链接。

原有 3 文件 smoke / 1 万文件 stress 流程保留；`--work-dir H:/TEST/mht/p4-4-client-smoke`
可以把其证据放到本批根。`--agent-dir H:/TEST/mht/p4-4-agent-*` 则在 JVM 模式开启 tracing
agent，自动包含所需的 `jdk.unsupported` 模块；采集必须使用装有 agent 的 GraalVM JDK。
中文临时目录通过单个 argv 传参，矩阵清空继承的 `JAVA_TOOL_OPTIONS`，避免环境字符串拆开空格路径。

路径断言采用严格 UTF-8 解码及原始路径字符串相等，能抓到 GBK、问号替代及合法 UTF-8 的乱码。
输出检查词法与真实目录归属，IPC 会话输出目录必须是请求输出根的直接子目录。
SHA-256 直接读取 `image-cn-filename.{zh,en}.json` / `text-template-vars.{zh,en}.json` 的 products，
与 `BackendOnceSmokeTest` / `BaselineHasher.hashTree` 一样比较文件原始字节，不重生成期望摘要。
IPC 固有的时间戳会话根作为树根；组目录和改名的输入文件干名显式映射，文件名与摘要仍逐条相等。
图片基线登记的裸 UTF-8 MIME 文件名既有乱码被原样保留；正确解码的 `中文图片一.png` 必须精确相等。
本批不修改 MIME 行为或产物格式。

`--controls` 在独立格子中做真实命令变异，再移除变异复跑：

- CG-1：JVM `--once` 的 stdout 编码从 UTF-8 改成 GBK，原始字节必须触发严格 UTF-8 解码失败。
- CG-2：实际 `--output` 改为 `错误中文根`（仍在 H 盘本格目录），正确根回显断言必须失败。

控制组要求进程正常完成后**对应守门断言**跑红，移除变异后复绿；误因启动失败等其它错误变红会使控制组失败。
变异只存在于这些子进程的命令数组，不修改生产源文件。

C 盘收尾审计需在运行前后另存快照；矩阵证据不能代替磁盘审计。受限会话下的未读目录、
未重跑的 JavaFX native 基线、未覆盖的其它字符集应在交付报告中明确列为未验证。

## P4-10 / P4-11 追加字符场景与 cp936 边界（JVM）

原来的默认 8 场景不变。追加可选 `japanese`（かな/カナ/漢字）、`korean`（한글）、
`symbols`（单个 😀 与全角符号）、`mixed`（中文、空格、点号），同时覆盖输入/输出/临时目录与文件名。
每格生成 UTF-8 HTML 字面量夹具，通过真实 `--once` 或同一个 `client.mjs --serve` 提取。
新夹具的期望全文来自固定字面量和模板契约，比较完整文件树与严格 UTF-8 解码后的完整字符串；
不从提取结果生成期望值，不修改已有黄金基线。路径回显仍走原来的精确字符串相等断言。

场景按本机宿主 cp936 的命令行传参边界显式分类：

| 分类 | 场景 | 矩阵要求 |
|---|---|---|
| `SYSCP_SAFE` | `japanese`（假名、汉字）、`mixed`（中文、空格、点号）；原有 8 场景也归此类 | 每格必须 PASS，路径、目录、全文/摘要断言保持严格 |
| `OUTSIDE_SYSCP` | `korean`（韩文）、`symbols`（含非 BMP 😀；其中全角字符本身在 cp936 内） | 不要求提取 PASS；必须正常结束且非零退出，并在捕获的子进程诊断中匹配 `InvalidPathException: ... Illegal char <?>`，才标记 `LIMITATION(expected)` |

启动错误、超时、信号终止、setup/权限错误、缺少该异常或其它失败均为 FAIL，不能冒充已知限制。
码页外场景意外提取成功也会触发 `SYSCP_LIMITATION_GUARD`：这表示宿主边界已改变，需要重新分类，
不能继续沿用本机 cp936 的预期。该分类不是对其它宿主码页或 native 的普遍结论；本次事实依据只覆盖 JVM。
`matrix.json` 保存 `sceneClass`、`status` 和 `passed`（已知限制为 `null`），`matrix.md` 和控制台明确区分
PASS / FAIL / `LIMITATION(expected)`；有非预期失败退出 1，否则退出 0。退出 0 不表示所有场景提取成功。

指挥方 P4-10 实测：`japanese` 和 `mixed` 各 4/4 PASS；`korean` 和 `symbols` 各 0/4 提取成功。
`--input` 中的 `한글 한국어` 变为 `?? ???`，😀 变为 `??`，随后出现上述路径异常。
绕过 Node，直接用 PowerShell 数组参数调用 `BackendCli` 也复现，已定位为宿主
**命令行 → JVM argv 的码页限制**，不是产品缺陷，也不是 Node 的问题。
该限制影响本机所有经命令行传入 cp936 外字符的 JVM CLI 用户。
GUI 文件对话框传路径不经 argv，不受这条 argv 限制影响；GUI 实际行为由指挥方另行核实，本批未运行 GUI。

在已编译且 **H 盘可写** 的会话中运行（复用上面的环境变量与 classpath 准备方法）：

```powershell
node tools/ipc/chinese-path-matrix.mjs --root H:/TEST/mht/p4-11-unicode --classpath-file H:/TEST/mht/p4-2b-classpath.txt --scenes japanese,korean,symbols,mixed --modes jvm-once,jvm-serve --locales zh,en --unicode-controls
```

这是 4 场景 × 2 JVM 模式 × 2 locale = 16 格，本机预期 8 PASS + 8 `LIMITATION(expected)`，另加 CG-3 红/绿对照。
CG-3 使用 `SYSCP_SAFE` 的 `mixed` 场景（jvm-once/en），只修改真实子进程命令里的起始模板为
`BEGIN:???`（保留换行模板），期望全文保持原样；必须同时满足 `red.passed === false` 与日志含
`TEXT_EXACT_GUARD`，删除命令变异后必须 `green.passed === true`。启动失败不能算控制组通过。
旧 CG-3 使用 `symbols`，先遇到路径异常而未到全文守门；那次不能作为 CG-3 的有效证据。
setup 失败会保存原始异常并继续其它格；权限错误不能据此推断 Windows 不支持某字符。
只覆盖这里明确列出的 Unicode 样本，不声称验证 Shift-JIS/CP949 等字节编码或其它 emoji 组合。
以上命令是复跑方法，文档不是本批运行通过的证据；原始日志必须另存本批 H 盘证据根。
`node tools/ipc/path-assertions-control.mjs` 是全文断言的内存级红/绿控制，使用真实
`exactText` 守门函数；不创建文件，不替代 16 格真实矩阵及真实命令变异 CG-3。

### 绕过 Node 的直接调用复现（仅供 H 盘可写会话执行）

先运行矩阵生成样本。下面直接复用其输入夹具，给直接调用建立全新的 ASCII 工作/输出/临时目录，
隔离输入 argv 的码页问题；已有工作目录时应换新目录。参数用数组传给 java，不调用 Node。

```powershell
$directRoot = 'H:/TEST/mht/p4-11-direct'
New-Item -ItemType Directory -Path $directRoot -ErrorAction Stop | Out-Null
$directCp = (Join-Path (Get-Location) 'target/classes') + ';' + (Get-Content -Raw -Encoding UTF8 'H:/TEST/mht/p4-2b-classpath.txt').Trim()
$savedJavaOptions = $env:JAVA_TOOL_OPTIONS
$env:JAVA_TOOL_OPTIONS = ''
Push-Location $directRoot
try {
  foreach ($sample in @('한글 한국어', '😀 ＡＢＣ（全角）')) {
    $scene = if ($sample -eq '한글 한국어') { 'korean' } else { 'symbols' }
    $directOutput = "$directRoot/$scene-output"
    New-Item -ItemType Directory -Path $directOutput -ErrorAction Stop | Out-Null
    $directJvmArgs = @('--limit-modules', 'java.se', '-Djava.io.tmpdir=H:/TEST/temp', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-cp', $directCp, 'com.mhtextractor.backend.BackendCli', '--once', '--locale', 'en', '--input', "H:/TEST/mht/p4-11-unicode/$scene-jvm-once-en/$sample/input", '--output', $directOutput)
    & 'D:/install/bellsoft-liberica-vm-full-openjdk25-25.0.3/bin/java.exe' @directJvmArgs 2>&1 | Out-File -Encoding UTF8 "$directRoot/$scene-diagnostics.txt"
    $directExit = $LASTEXITCODE
    "EXIT=$directExit" | Out-File -Encoding UTF8 "$directRoot/$scene-exit.txt"
  }
} finally {
  Pop-Location
  $env:JAVA_TOOL_OPTIONS = $savedJavaOptions
}
```

比较两个 `*-diagnostics.txt` 中的 `InvalidPathException` / `Illegal char <?>` 与非零退出码。
PowerShell 诊断文件是文本重定向，矩阵 `capture.stdout` / `capture.stderr` 才是原始字节证据。
指挥方原始矩阵证据位置为 `H:/TEST/mht/p4-10-unicode/` 下各格 `assertions.log`、
`capture.stdout`、`capture.stderr`（serve 另有 `stderr.txt` / `command.json`）；旧 CG-3 的失败记录在
`symbols-jvm-once-en-CG-3-red/`。此前直接 PowerShell 调用的独立日志路径未在任务正文给出，不编造位置。
P4-11 复跑证据应另存 `H:/TEST/mht/p4-11-unicode/`（含 `matrix.json` / `matrix.md`、
`mixed-jvm-once-en-CG-3-{red,green}/assertions.log`、`CG-3.json`），直接调用证据保存到上述 `p4-11-direct/`。
所有这些复跑方法均待指挥方执行；本批只编辑，未做任何 H 盘验证。

### 未来产品侧缓解方向（本批不实现）

可考虑让 `--config` 指向的 UTF-8 文件承载输入/输出路径，或设计明确支持 UTF-8 的 `@argfile`，
只在 argv 上传递 cp936 可表示的配置/参数文件路径，从文件读取完整 Unicode 路径。
现有 `--config` 不承载这些运行时路径，不能把上述方向当作已支持的绕行方法；
实施时还需明确编码、路径解析和覆盖优先级，再做跨码页验收。

## P4-10 C 盘项目产物口径

```powershell
node tools/ipc/project-temp-audit.mjs --controls
node tools/ipc/project-temp-audit.mjs --root H:/TEST/mht/p4-10-c-audit --before
# 按本批顺序运行验证；不要并发 mvn clean 与 GUI/native。
node tools/ipc/project-temp-audit.mjs --root H:/TEST/mht/p4-10-c-audit --after
```

项目断言只覆盖 `%LOCALAPPDATA%/Temp/mht-extractor-*` 及其子项的新增。
`c-before.json` / `c-after.json` 保留原始快照，`c-audit.json` 分别计数项目、
`dsh-workspace-changes-*`、`dd_BackgroundDownload_*`、其它应用的新增和修改。
非项目类别只清点两个 Temp 根的直接子项，项目目录递归；不声称清点整盘或 DSH 快照内部。
项目根不可读时返回 UNVERIFIED（exit 2），发现项目新增返回 FAIL（exit 1），否则 PASS（exit 0）。
`--controls` 是快照数据层的红/绿/不可读对照，不创建 C 盘违规文件；不替代真实运行前后快照。
活主机的 DSH 会话快照和 Visual Studio 后台下载日志会写 C 盘，因此整盘“零新增”不可作为项目闸门。
整盘和两次快照之间创建后又删除的瞬态文件仍未验证。
