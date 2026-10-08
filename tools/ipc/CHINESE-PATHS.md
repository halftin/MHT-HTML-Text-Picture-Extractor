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
`--locales zh,en` 缩小复跑范围。任一格失败使矩阵退出 1。

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
