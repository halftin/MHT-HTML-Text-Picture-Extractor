# JSON Lines 控制协议 v1（P4-2a + P4-2b + P4-13 启动握手 + P4-14 确认往返）

启动 `com.mhtextractor.backend.BackendCli --serve`，stdin/stdout 为 UTF-8 JSON Lines：
每行恰好一个 JSON 对象，输出行尾为 LF，字符串中的换行由 JSON 转义。每帧立即 flush。
日志沿用独立 logback 文件配置（`logs/backend.log`）；stderr 留给 JVM/启动诊断。
客户端须分别读取 stdout、stderr，不能合并。工作目录与临时目录按仓库约定放 H 盘。

帧示例：

```json
{"v":1,"type":"event","event":"ready","data":{"methods":["workspace","setChecked","importPaths","group","setParams","extract","workspacePage","previewFiles","fileInfo","releaseFiles","metrics","confirmAnswer","hello"]}}
{"v":1,"type":"request","id":"h1","method":"hello","params":{"protocolVersion":1,"version":"3.0.0","buildId":"3.0.0-p4-13-ipc1"}}
{"v":1,"type":"response","id":"h1","result":{"kind":"welcome","protocolVersion":1,"version":"3.0.0","buildId":"3.0.0-p4-13-ipc1","capabilities":["workspace","setChecked","workspacePage","bulkFiles","confirmationRoundTrip"],"confirmationRoundTrip":true}}
{"v":1,"type":"request","id":"q1","method":"workspace","params":{}}
{"v":1,"type":"response","id":"q1","result":{"groups":[],"topLevelItems":[],"canUndo":false,"undoDepth":0,"selection":"NONE"}}
{"v":1,"type":"request","id":"c1","method":"setChecked","params":{"paths":["H:/input/a.mht"],"checked":true}}
{"v":1,"type":"response","id":"c1","result":null}
{"v":1,"type":"error","id":null,"error":{"code":"BAD_JSON","message":"Expected one JSON object per line"}}
```

- 请求必须携带整数 `v=1`、`type=request`、非空字符串 `id`、字符串 `method`、对象 `params`。
  多余字段忽略，重复 JSON 键和尾随第二个 JSON 值拒绝。id 原样回显；由客户端负责区分在途请求，
  服务端不去重、不缓存 id，同 id 再次请求会再次执行。
- 启动只发一次 `ready` 事件，之后同步逐行执行、逐行应答；每个请求恰好一个 response 或 error。
  `setChecked` 复用端口的静默语义，**不生成工作区事件**，客户端在响应后查询新快照。
- `workspace` 返回完整只读 `WorkspaceVm`：组与条目的所有字段、数组顺序、null 序号、
  枚举名称均保留；不额外发送列表逐项事件。
- `setChecked` 先完整验证 `paths` 数组与布尔 `checked` 再调用端口；路径按工作区 id 精确匹配。
  未知路径/null 元素跳过，重复路径/重复设置/空数组沿用端口语义。不新增撤销记录。
- `BAD_JSON`：语法错误/重复键/尾随值，id 为 null；`INVALID_REQUEST`：帧形状/版本错误；
  `UNKNOWN_METHOD`：方法未开放；`INVALID_PARAMS`：参数类型错误。后三者在可提取合法 id 时回显，
  否则为 null。`INTERNAL_ERROR`：端口运行时异常，详情只入日志；不承诺异常命令自动回滚。
  这些错误不会结束进程，下一行仍可服务。物理 IO 故障结束会话（退出码 1）；stdin EOF 退出码 0。
  EOF 前无 LF 的最后一行也按一帧处理。

正式 `--serve` 初始工作区为空。P4-2b 新方法见下；恢复会话仍未开放，确认往返见 P4-14。
非空契约测试用测试源集装配器在真实子进程内建立分组/条目/移除夹具，再运行同一个生产 IpcServer；
没有给生产入口加测试数据开关。与直接端口执行同一命令序列后，用独立 Jackson record 序列化结果
逐字段比较（包括数组顺序、null 和转义字符串；忽略对象键顺序这一无语义差异）。正式 CLI 另有子进程往返测试。

本地方案 §7 现为 C1–C20 冲突表，没有独立 D3 条目；传输依据取 §14 决策 9 中的 D3 引用。

## P4-2b 兼容规则与方法发现

帧版本仍为整数 `v=1`，四种帧与原有五种错误码的语义不变。P4-2b 扩展添加方法和这些方法产生的事件，
`ready.data.methods` 在原来的两项后追加支持的方法；客户端应按包含关系检测能力，允许未知方法名。
原有 `workspace` 的完整映射、顺序、null 与 `setChecked` 静默语义保持不变。旧客户端不调用新方法
就不会收到新活动事件。P4-13 增加下面的强制启动握手；没有重连或并行命令执行。

## P4-13 启动版本/构建标识握手

`ready` 仅表示管道可用，**不表示业务请求已获准**。其 `methods` 保持原两项在前、
P4-2b 方法次之，并追加 `hello`。所有 `--serve` 会话必须先完成 `hello`：
`params` 为正整数 `protocolVersion`、非空字符串 `version`、非空字符串 `buildId`。
三个值是客户端随 UI/交付物携带的**预期值**，不能从对端 ready 复制以伪造匹配。
多余字段仍忽略；类型/空值错误为 `INVALID_PARAMS`。请求帧本身仍须 `v=1`。

匹配成功返回普通 `response`（同 id），`result.kind=welcome`，携带上述三个实际值、
字符串数组 `capabilities` 与布尔 `confirmationRoundTrip`。
正式后端能力集为 `workspace`、`setChecked`、`workspacePage`、`bulkFiles`、`confirmationRoundTrip`；
完整会话的确认往返标志为 true；仅装配最小端口的契约宿主只声明前两项，标志仍为 false。
能力名允许扩展，客户端按包含关系检测，不能把未声明能力当作已支持。

新增专用错误码 **`HANDSHAKE_FAILED`**：版本/构建标识不匹配，或尚未握手就请求业务方法。
错误回显合法 id，message 指出 expected / backend declares 或要求先发 hello。
失配不执行任何业务命令，进程保持服务；修正后可重新 hello。
重复 hello 每次重新校验，失败（包括参数非法）会撤销之前的握手，后续业务请求再次拒绝。
原五个错误码仍各司原职，坏 JSON/坏帧的检查先于握手检查。

保留整数帧版本 `v=1` 的理由：JSON Lines 编码、四类帧、id 对应、旧方法字段与业务语义均未变。
握手协议版本单独用 `protocolVersion=1`，本批只接受精确匹配，不做降级。
**兼容边界**：既有业务请求/响应格式兼容；旧客户端必须在 ready 后补一次 hello 才能继续，
未升级客户端会明确收到 HANDSHAKE_FAILED。本批不提供绕过握手的兼容开关。
`--once` 不使用 IPC，不受握手影响。

构建标识由 Maven 过滤 `/backend-build.properties` 注入：`version=${project.version}`，
`buildId=${backend.build.id}`；默认 buildId 为 `${project.version}-p4-13-ipc1`。
发版可用 `-Dbackend.build.id=<release-id>` 指定不可复用的发布标识，UI 与后端应分发同一预期标识。
这是**构建标识**，不是产物 SHA-256 或真实性签名；复用标识无法发现内容篡改。
未过滤/缺失资源直接启动失败，不静默给默认版本。基于资源的标识也便于控制组篡改：
测试仅在子进程 classpath 首部覆盖资源，客户端保留原预期值，生产 CLI 无测试注入开关。

`BackendLauncher.launch(ProcessBuilder, BackendIdentity, Duration)` 可拉起 JVM/native `--serve` 命令，
限时读取 ready、发送 hello、独立验证 welcome；错误、EOF、超时、失配均抛可读 `IOException`
并结束所启动的进程。调用方显式配置工作目录/临时目录，stdin/stdout 须为 PIPE、stderr 须独立，
默认未重定向的 stderr 改为继承诊断流，禁止合并进 stdout。
成功后返回 `Connection`：identity、不可变能力集、确认往返标志以及独占的输入/输出流。
调用方持续消费业务 response/error/event，`close()` 发送 EOF，超时则强制清理进程。
P4-17 JavaFX 启动器通过 `app.BackendSession` 复用此启动器；native EXE 的重建/运行需另验。
无头打包输入复制上述过滤后的属性文件，headless 元数据仅增补 tracing agent 实际记录的资源 glob；
既有七份 GUI 元数据不变。

以下方法每请求仍恰好一个 response/error。`importPaths` / `extract` 同步等待后端活动完成再响应，
期间允许发送下表列出的事件；客户端必须持续读取 stdout，不能只等紧邻请求的下一行。
导入与提取的模型回写、端口事件在 IPC 宿主线程串行执行，后台到宿主回调队列容量 **256**，满时背压。
P4-14 用独立 daemon 读线程持续接收 stdin（读队列 256），不在读线程执行端口命令。
活动等待期间宿主处理 `confirmAnswer` 与 EOF，其余请求按接收顺序暂存（最多 256；溢出返回
既有 `INVALID_REQUEST`），活动收尾后继续执行。确认回答的响应可以早于原活动请求的响应；
客户端必须按 id 匹配，不能假定响应与请求的全局顺序一致。普通业务命令仍串行执行。
stdin EOF/读取 IO 故障不再等确认结束才发现：释放在途确认、取消提取、等待 worker 收尾并清理会话。
P4-3 实际交付为独立无头 native 构建；本处曾误归 P4-3 的确认往返和断连语义由 P4-14 承接。
显式的通用「取消某个在途请求」方法仍未开放。

## P4-14 确认往返、超时与断连

新增普通 event `confirmationRequested` 与普通 request 方法 `confirmAnswer`。
仍为 `v=1` 的 request/response/event/error 四类帧，原五个错误码及 P4-13 的
`HANDSHAKE_FAILED` 均不改变；没有新增帧类型或错误码。`ready.data.methods` 追加 `confirmAnswer`。

```json
{"v":1,"type":"event","event":"confirmationRequested","data":{"confirmationId":"confirm-1","key":"alert.recursionDepthHeader","namedParams":{},"actionKeys":["continue"],"defaultAction":null,"timeoutMs":30000}}
{"v":1,"type":"request","id":"answer-1","method":"confirmAnswer","params":{"confirmationId":"confirm-1","actionKey":"continue"}}
{"v":1,"type":"response","id":"answer-1","result":{"accepted":true}}
{"v":1,"type":"request","id":"answer-2","method":"confirmAnswer","params":{"confirmationId":"confirm-2","actionKey":null}}
{"v":1,"type":"response","id":"answer-2","result":{"accepted":true}}
```

- `confirmationId` 是本会话内唯一、不复用的字符串，与普通 request id 独立；不能跨会话回答。
  `key` 标识问题类型及文案入口，`namedParams` 是字符串具名参数对象，`actionKeys` 是有序动作键数组。
  `defaultAction=null` 明确默认按取消交付；取消选项始终存在，用 `actionKey=null` 回答。
  非 null 的答案必须属于 `actionKeys`。协议层零 I18n 引用，不传预渲染正文，尤其不序列化
  `ConfirmRequest.message`。前端按当前语言渲染，下列组合键口径由前端实现。
- `alert.recursionDepthHeader`：组合同名前缀的 Header、Content、Continue、Skip 四个既有键；
  选 `continue` 继续既有解析流程，取消/超时按既有语义跳过超深子树。
  **继续也不会解除解析器深度上限**。跳过可能得到 SUCCEEDED + SKIPPED 文件结果，不等于整任务 CANCELLED。
- `import.quotaReached`：`groups/files` 为单轮配额，`accumulatedGroups/accumulatedFiles` 为已导入累计；
  组合既有 `import.quotaReached`、`import.quotaContinue`、`import.quotaStop`，最后一个键的
  groups/files 用累计值渲染。动作 `continue/stop`；`stop`、null、超时均停止下一轮。
  既有 in-process 渲染正文保留，IPC 只取键和参数。
- `confirmAnswer` 必须已完成 hello，`confirmationId` 必须是非空字符串，`actionKey` 必须显式存在
  且为字符串或 null。已到期、重复、未知 id 回答返回 `{accepted:false}`，不改变其它确认。
  在途确认的非候选动作返回 `INVALID_PARAMS`，不消耗确认。每张卡答案、取消、超时只能有一个胜者。
- 超时上限来自 `IpcConfirmations.DEFAULT_TIMEOUT_MS=30000`（30 秒）。JVM 可用
  `-Dmht.ipc.confirmTimeoutMs=<正整数毫秒>` 配置；native 可用现有 JVM 系统属性机制配置。
  缺省/请求 `timeoutMs<=0` 仍受这个会话上限保护，正请求期限取两者较小值。
  帧中的 `timeoutMs` 是实际期限，从后端创建 token 时开始，采用单调时钟；不会因重复 await/回答延长。
  无回答到期后交付 null，移除确认，工作线程继续既有取消语义并到达活动终态，管道继续服务。
  非正值/非数字配置拒绝启动。前端须在回答、当地计时到期或活动终态时移除卡片。
- 客户端关闭 stdin/进程退出导致 EOF，或读取失败：在途 token 全部交付 null，提取请求取消并
  交付一次 CANCELLED（已完成 worker 的终态仍按既有自然完成竞态裁决），释放确认中的非 daemon worker。
  stdout 尚可读时仍可收到终态及 extract 响应；stdout 已断开的场景只能在后端完成终态与清理，
  不承诺已断管道送达帧。写 IO 故障同样在 finally 取消与清理，退出码沿用 1；EOF 沿用 0。
  借用流由调用方关闭；异常后仍阻塞 readLine 的输入线程为 daemon，不阻碍进程自然退出。
  真实管道测试要求确认在途时 EOF 后 **3 秒内自然退出**，并断言无残留进程/非 daemon worker/读线程。

P4-17 JavaFX 前端已接入确认卡和应答。新增可选事件 `confirmationExpired.data={confirmationId}`：
后端期限到达时先发此事件，再释放等待者，前端收卡、提示并复位；旧客户端可以忽略此扩展事件。
前端同时保留当地计时（期限加 250 ms 传输余量，至多 30 s 加余量）与活动终态收卡兜底。
断连清除本会话在途卡，卡片顶替回送 `actionKey=null`；确认卡取消句柄仅关闭其所属卡片。
启动默认查找 GUI exe 同目录的 `MhtExtractorBackend.exe`，JVM jar 查找 jar 同目录，
散装 classes 查找工作目录；`-Dmht.backend.executable=<path>` 可覆盖。拉起命令为
`<exe> -Djava.io.tmpdir=<GUI tmpdir> --serve --locale <GUI language>`。
握手必须声明完整后端能力集且确认往返标志为 true。失败显示状态与警告 toast；
现有列表、预览、提取仍走进程内 app 端口。native EXE 重建与上述路径运行另验。
`BackendIpcConfirmationTest` 使用真实无头子进程、深度 101 的两个兄弟分支，比较 IPC/in-process 的
确认次数与逐文件结果：确定询问一次，取消/不回答询问两次，排除“静默按取消”的伪往返。
CG-1 故意不答；CG-2 在看到确认帧后关客户端输入，并用自然返回的测试宿主检查线程，避免
`System.exit` 掩盖残留 worker。移除 timed get/移除 EOF 处理可分别令有限时间断言跑红。

| 新方法 | params | result |
|---|---|---|
| `importPaths` | `paths`: 非空绝对地址字符串数组（文件或目录） | `{count,failed}`：当前工作区条目数与导入是否报告错误；不含条目列表 |
| `group` | `paths`: 非空工作区 id 数组；`namePrefix`: 非空字符串 | null；复用端口建组，未知 id 跳过 |
| `setParams` | `imageMode`: boolean；`config`: 命名配置格式的 AppConfig JSON 对象 | null；复用全局参数写入、撤销和缓存失效；整体替换该模式配置 |
| `extract` | `checkedOnly`: boolean；`outputFolder`: H 盘产物白名单内的绝对目录 | `{generation,end,outputFolder,count,failCount,skipCount,warnCount,resultsFile}`；失败终态可无统计和结果文件 |
| `workspacePage` | `offset`: 非负 int；`limit`: 1–256 的 int | `{total,offset,groupCount,items}`；超过总数返回空数组 |
| `previewFiles` | `path`: 已导入且未移除的工作区 id | `{imageCount,manifest}`；只传文件描述，不传正文、HTML、图像字节或像素 |
| `fileInfo` | `path`: 本会话签发的文件路径 | 文件描述；检查存在与规范化后的会话归属，无通用文件内容读取方法 |
| `releaseFiles` | `paths`: 非空已签发文件路径数组 | null；全部校验成功再去重删除，有一个非法条目就整批拒绝 |
| `metrics` | `{}` | `{heapPoolPeakBytes,callbackQueuePeak,rawProgress,sentProgress}`，口径见压测 |
| `confirmAnswer` | `confirmationId`: 非空字符串；`actionKey`: 候选动作字符串或 null | `{accepted}`：是否交付了这张在途卡的决定 |

`config` 沿用持久化配置属性名，例如 `enableCustomTemplate`（不是 Java 字段 `enableTemplate`）、
`startTemplate`、`endTemplate`、`separateFiles`。未知属性报 INVALID_PARAMS；四个被 JsonIgnore 的运行时
字段不经该对象设置，输出目录由 `extract` 单独校验。导入每轮上限 200 组 / 20000 文件；达到配额后
若仍有剩余，发配额确认，确定才继续下一轮；取消/超时停止。`count` 必须由客户端核对，不能假定所有输入都已落地。
读取不到的输入及解析失败仍沿用业务层容错语义；不存在的直接输入报 INVALID_PARAMS。

`workspacePage` 使用端口不可变快照，首次按需查询时建立服务器内部列表；工作区写命令使快照失效。
分页序为顶层条目（含 removed）先行，再按 `workspace.groups` 顺序及组内顺序列成员；每条 ItemVm
字段与 v1 相同。组名和分组细节仍可显式查询旧 `workspace`。该方法只限制管道返回规模，模型和首次
查询快照仍为 O(n) 内存；启动、导入、参数事件均不自动推完整列表。

| 扩展事件 | data |
|---|---|
| `importRound` | `{generation,newFiles,newGroups}`：一轮一次，不含计划中的文件路径数组 |
| `importFinished` | `{generation,count,failed}`：活动收尾一次；count 为本次会话新增累计 |
| `paramsChanged` | `{kind,affectedCount}`：不传 affectedPaths |
| `notice` | `{key}`：业务通知键，不传正文/列表 |
| `extractionProgress` | `{generation,overall}`：进度 0–1；每进入更大的整数百分比桶才发，最多 **101** 帧/任务 |
| `extractionTerminal` | 与 extract 结果相同的统计摘要，**不含 resultsFile 或 files 数组**；每任务一次 |
| `confirmationRequested` | `{confirmationId,key,namedParams,actionKeys,defaultAction,timeoutMs}`；不传预渲染文案 |
| `confirmationExpired` | `{confirmationId}`；P4-17 可选扩展，到期取消后前端收卡与提示 |

## 批量文件、安全与生命周期

`--serve` 要求显式设置 `-Djava.io.tmpdir=H:/TEST/temp` 或该根下已存在的子目录（允许中文、空格），
配置目录与根目录均不得经链接跳转到别处。每实例在配置目录下
创建随机 `mht-ipc-*` 目录；预览缓存也注入此会话根，避免清掉另一个实例/GUI 的缓存。
文件描述为 `{path,bytes,mediaType}`，path 是服务端签发的绝对路径，bytes 为落盘长度。
预览 manifest 是一个 JSON 文件，包含 `text` / `html` 文件描述和 `thumbnails` 数组；每个图像描述
另有 `name` / `index`。图像使用现有预览缓存中的编码原图，客户端自行解码、缩放为缩略图；
服务端不引入图像编码器，不改变 JavaFX 的 ARGB 缩略图链路。HTML 是 UTF-8 的原始源码文件，
不是浏览器导出页；正文走现有 SourceParseService，图片走 PreviewService。即使图片很多，
管道也只返回一个 manifest 描述。提取逐文件结果 `{path,outcome,message}` 同样走 `resultsFile`。

客户端读取后可主动释放文件；提取完成按已有缓存生命周期清理已处理输入的预览缓存，旧的图像引用
因而失效。EOF/正常退出/传输 IO 异常结束后删除本实例所有 bulk 文件与缓存，持久化提取产物保留。
强制杀进程/系统崩溃可能留 H 盘随机会话目录，自动回收孤儿目录未在本批实现。

输入和输出白名单为 **H:/TEST/mht 的子路径**，不允许直接操作根本身。先要求绝对路径、normalize 后
检查目录边界，再用 `toRealPath` 复核真实归属；导入目录预检树中路径，单个 HTML 地址也预检其侧车目录，
拒绝跳出白名单的链接/junction。
输出校验最近存在的祖先，再创建目录并复核；preview/extract 在打开文件前再次校验已导入 id。
`group`/旧 `setChecked` 的 paths 仅是工作区标识，不作为文件读取目标。
`fileInfo` / `releaseFiles` 同时检查规范化后的会话根与签发集合，拒绝同目录未签发文件、其它实例路径、
外部输入路径和目录穿越；错误不会泄露文件内容。没有任意路径读取接口。
这些是 IPC 参数边界检查，不能防御具有相同 OS 权限的本机进程在校验与文件打开之间恶意替换目录。

## 可复跑客户端与 C13 压测

Node 只使用内置模块，不需 npm install。命令在仓库根执行（PowerShell 5.1 参数数组）：

```powershell
$env:JAVA_HOME='D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3'
$env:JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=H:\TEST\temp'
$env:MAVEN_USER_HOME='H:\TEST\m2'
.\mvnw.cmd @('-q','-Dmaven.repo.local=H:\TEST\m2\repository','-DskipTests','compile')
.\mvnw.cmd @('-q','-Dmaven.repo.local=H:\TEST\m2\repository','dependency:build-classpath','-Dmdep.outputFile=H:\TEST\mht\p4-2b-classpath.txt')
node tools/ipc/client.mjs
node tools/ipc/client.mjs --stress
```

`--classpath-file <file>` 可覆盖依赖清单文件；`--classpath <完整classpath>` 可直接传入（用于复验）。
`--identity-file <file>` 可指定 UI 持有的预期构建属性文件，默认读取 `target/classes/backend-build.properties`。
脚本在 ready 后先 hello，逐字段核对 welcome，再执行下面的工作流。
脚本物理剔除 UI 框架 jars，启动 Java 子进程并限制为 java.se / `-Xmx512m`。
完整走导入→分页收齐 id→分组→全局参数→预览文件读取→提取→结果文件/产物核对→释放→stdin EOF。
stdout 与 stderr 分开消费及落盘，退出时验证 exitCode=0、会话目录消失、终态一次、输出正文与模板各出现
输入数次。摘要另打印独立 backend.log 的尾部；stderr 的 JVM 启动诊断不当作协议帧。

smoke 造 3 个 HTML（内嵌 1px PNG），stress 造 **10000** 个。压测产物固定落
`H:/TEST/mht/p4-2b-stress`，`input` 可重复覆盖，`output` 为业务时间戳目录，`stdout.jsonl` / `stderr.txt` /
`summary.json` / `command.json` 保存本次原始证据。不要并发运行同一模式的两个脚本。
压测是等价于测试的断言脚本，任何违规 Node 非零退出；不并入默认 mvn test，避免引入 Node 测试依赖。

量化闸门（代码中的强断言）：

- 实际输入、导入条目、分页唯一 id、逐文件结果、产物正文/模板计数均为 10000。
- 全流程总帧数 **≤256**，输出单帧 **≤64 KiB**；进度 **1–101** 帧，原始回调数 **>合并后×10**；
  活动事件与导入响应仅含标量摘要，不得带全量列表或嵌套列表。
- 分页单次最多 256 项，分页响应峰值 **<5s**，任意命令响应峰值 **<120s**；流程超时 **180s**（不含造输入）。
- `heapPoolPeakBytes` 为 JVM 各 HEAP MemoryPoolMXBean 的 `peakUsage.used` **之和**（池峰值可能不同时，
  是保守上界，不是 RSS）；要求 **<768 MiB**，另以 JVM `-Xmx512m` 限制堆。回调队列峰值 **≤256**。

这验证本机单次 10k 小文件的可用性，不涵盖 10k 大型 MHT 或大量大图预览吞吐、共享内存、native、
GUI 自动化/探针、P4-3/P4-4。协议总体输入帧大小上限与更细的分页版本令牌未在本批实现。
