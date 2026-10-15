# JSON Lines 控制协议 v1（P4-2a + P4-2b + P4-13 启动握手 + P4-14 确认往返）

## P5a-8：命名配置端口

`ready.data.methods` 与 `welcome.capabilities` 追加以下四方法。沿用握手、帧类型和响应 revision；
命名配置读写不改变工作区、撤销栈或 revision，不发布工作区事件。

| 方法 | params | result |
|---|---|---|
| `listNamedConfigs` | `{}` | `{names:array<string>,revision}`，保留文件系统枚举顺序，不排序 |
| `loadNamedConfig` | `{name:string或null}` | `{config:Config或null,revision}`，仅持久化提取参数 |
| `saveNamedConfig` | `{name:string或null,config:Config或null}` | null；重名覆盖 |
| `deleteNamedConfig` | `{name:string或null}` | null；缺文件为错误 |

Config 使用既有持久化提取字段和默认值，不含 inputFolder/outputFolder/imageModeEnabled/openFolderAfterComplete。
GUI 经端口访问后端工作目录的 `config/`，不另建前端读写路径。
名称原样拼接 `.json`，不 trim、不主动校验；空串/空白可保存，null 与既有 Java 拼接一致，使用 `null.json`。
名字已有 `.json` 时再追加一份后缀；路径分隔符按既有文件系统规则解释。
首次访问创建目录（失败仅日志告警）；初始化后目录消失不自动重建，列表为空，保存失败。
缺文件、非法文件名、读写/解析/删除失败返回新增 `CONFIG_IO_ERROR`，message 保留原 IOException 诊断。
Java IPC 适配器将此码映射回 IOException；调用方继续使用原错误卡。该错误不会使连接失效。
缺 name、非 string/null 的 name、缺 config 或非法 Config 形状沿用 `INVALID_PARAMS`；空/null 的合法值不混同缺字段。

## P5a-7：缓存台阶与后端诊断事件

两类事件沿用 `v/type/event/data` 帧和服务端统一递增 `data.revision`；不改变工作区。

| event | data（另含 revision） |
|---|---|
| `cacheSizeAlert` | `step:integer,actualBytes:integer,stepBytes:integer,root:string,key:"cache.sizeAlert",namedParams:{dir:string},level:"WARN"` |
| `backendLog` | `level:"WARN"/"ERROR",key:string,namedParams:object<string,string>,timestamp:integer` |

缓存测量均为字节，stepBytes 默认 10×1024³；前端用载荷换算 GiB（actual 保留一位），
沿用 `cache.sizeAlert` 与 `cache.sizeAlertNote`，不为呈现再查询 cacheState。
恰好达到台阶即提醒；同台阶并发/重复检查一次；一次跨多个台阶只提醒当前台阶。
检查发现回落时更新观测台阶，不提醒；再次上升到该台阶可以重新提醒。
缓存删除后也重新观测，两个传输各在后端注册一次 hook，共用同一前端消费入口。

后端日志文件接收原有日志；只有 WARN/ERROR 经会话 appender 转发。
`I18n.msg` 保留同一次同步日志调用的原键和具名参数（数字名 `0/1/…` 对应既有日志占位符）；
前端按当前 locale 渲染。没有结构化来源的日志以 `log.backend.unstructured` 加 logger/异常类型呈现，
完整诊断留在 backend.log；IPC 不携带渲染后的 message。timestamp 为 Unix epoch 毫秒。
日志实参若是 `I18n.render(ExtractWarning)`，用具名参数中的 `$key.N` 与 `$arg.N.name`
保留嵌套键/原参，`N` 的值为空；前端先解析嵌套文案再替换外层占位符，避免后台 locale 固化。
INFO/DEBUG 不产生 backendLog；前端另有级别过滤。日志事件与缓存事件不触发快照查询。

GUI 启动后端前创建发行根 logs；启动参数固定日志根，后端独立启动也把 Backend 目录归并至其父发行根。
会话根包含 preview-cache、端口导出原文件、签发副本和上传/未签发残留；关闭按缓存规则清空后删除会话树，
不跟随目录重定向。清理最多等待 2 秒，失败记录日志并继续关闭；未删成功的目录留作可观察证据。

## P5a-1e：C28/C29、Q10–Q13 与完整状态投影

`ready.data.methods` 与 welcome 能力列表追加 `setTitleOverride`、`applyParamsToTargets`、
`parameterState`、`preStartState`、`presentation`、`exportSource`，并开放 Q09 `cacheState`。
帧类型、错误码及旧方法不变。C28/C29/Q10/Q11/Q12 必须携带当前 `revision`；
不匹配返回 `INVALID_PARAMS`，先重新读取快照，再由用户重试；不得自动重放写操作。

| ID / 方法 | params | result |
|---|---|---|
| C28 `setTitleOverride` | `path:string,title:string或null,revision:integer` | null；trim、空串转 null，无撤销记录；实际变化发 `workspaceChanged(TITLE_CHANGED)`，snapshot 含同版 ItemVm |
| C29 `applyParamsToTargets` | `imageMode:boolean,params:完整Config,fallbackTargets:FileRef,revision:integer` | null；`config` 是 `params` 的兼容别名，必须且只能给其中一项；完整 Config 是全部持久化提取字段，不包含四个运行时字段 |
| Q10 `parameterState` | `imageMode:boolean,fallbackTargets:FileRef,revision:integer,targetResolution?:selectedFirst/explicit` | `{state:FileRef,revision}`，文件内容见下 |
| Q11 `preStartState` | `runtime:RuntimeContext,checkedOnly:boolean,revision:integer` | 下述 PreStartState VM + revision |
| Q12 `presentation` | `paths:FileRef,runtime:RuntimeContext,revision:integer` | `{items:FileRef,revision}`，文件为有序 PresentationVm 数组 |
| Q13 `exportSource` | `path:string` | `{status,htmlFile,imageCount,warningCount,detail,sessionId,revision}`；无需预先导入工作区 |

`fallbackTargets` 文件为 `array<TargetId>`，`paths` 文件为 `array<string>`，沿用会话内上传校验。
C29 与 Q10 共用后端勾选优先解析：有未移除的勾选项时忽略 fallback，否则采用首个可解析 fallback。
Q10 可选 `targetResolution` 省略或为 `selectedFirst` 时保持上述语义；`explicit` 忽略勾选，
按上传的稳定 TargetId 顺序逐目标返回既有 TargetState，重复 ID 取首次，空集合返回空状态。
`explicit` 的目标必须存在且未移除；未知目标或非法解析方式沿用 `INVALID_PARAMS`。
两种查询均只读，不改变勾选、参数、撤销栈或 revision；响应字段不变，C29 不受此选项影响。
组内条目的参数应用路由到所属组；重复组路由去重。C29 在任何写入前捕获整批 before，
只压一条 `PARAM_SET`，不循环逐项参数命令；相同参数为空操作。完成缓存失效后发
`paramsChanged(SET_TARGETS)` 与 `workspaceChanged(PARAMS_CHANGED)`。

Q10 文件：`textConfirmed:boolean,textGlobal:Config或null,imageConfirmed:boolean,imageGlobal:Config或null,
targets:array<TargetId>,count:integer,states:array<TargetState>,uniform:boolean,hasEffective:boolean`。
TargetState：`target:TargetId,independent:boolean,effective:boolean,source:NONE/GLOBAL/GROUP,config:Config或null`。
null 表示未确认/无生效参数，不能替换为默认 Config；null 参与混合值判定，空集合 uniform=true、hasEffective=false。

Q11：`denial:{key,level,namedParams}或null,textConfirmed,imageConfirmed,defaultNamedGroups:array<string>,subsetMergedName:boolean`。
拒绝顺序为输入空、输出空、输入非目录、无可提取项、checkedOnly 下无勾选项；级别沿用既有业务规则。
默认名组及合并单元来自同版后端计划。runtime 的空路径作为事实返回 denial；查询不创建输出目录、不含 draftDirty、不发确认卡。

Q12 每项：`path,displayName:string或null,parsing:boolean,imageMode:boolean,status,head,tail,textFile:FileRef或null,images:array<ImageFile>`。
正文始终走 UTF-8 文件；head/tail 与显示名复用模板/命名业务规则；未解析显示名保留 parsing 状态。
图片描述与 E12/E13 共用实际缓存原图表示：`path,bytes,mediaType,imagePath,extension,coreName,displayName,width,height,index,dimensions:{width,height}`。
`imagePath` 为真实缓存图片路径，不能伪称 PNG；mediaType/extension 按魔数，displayName 随当前序与参数重算。
共享缓存图片仍由原缓存生命周期回收，客户端 release 不得删除借用原图。

Q13 status 为 `unparseable/failed/success`。成功的 htmlFile 为 UTF-8 HTML FileRef，附带 imageCount/warningCount；
失败保留导出器/解析器诊断 detail。sessionId 用于丢弃旧会话结果，HTML 不应在浏览器仍引用时释放。

ItemVm 追加 `modifiedTime,status,errorMsg,chapterEnabled,badge,cachedTitle,titleOverride,extractOutcome,extractDetail,textFile,previewManifest`。
modifiedTime 为 LocalDateTime 的 ISO 字符串，textFile/previewManifest 为 FileRef 或 null（空可省略），不传 cachedText 正文。
GroupVm 追加 `confirmation:{textConfirmed,imageConfirmed}`（该组独立参数确认摘要）。WorkspaceVm 追加 `details`：
`textConfirmed,imageConfirmed,activityGeneration,previewGeneration,loadingPath,importing,importGeneration,running,extractionGeneration,lastStats,sessionOutputFolder`。
lastStats 是完整 ExtractionStats 的 JSON FileRef，不以 StatsVm 四计数代替；所有状态随宿主串行事件快照投影。

E19 追加 `undoKind:string或null,affected:FileRef,suggestedSelection:TargetId或null`，affected 文件为有序 TargetId 数组。
撤销完成才提供 undoKind/选择建议，U2 参数撤销已由后端恢复 before 并恰好消耗一条记录。Q09 `cacheState` 返回
`root,totalBytes,hasCachedEntries,revision`，hasCachedEntries 读取真实缓存目录，不从 bytes>0 推断。

Java 端口的新细节查询需要与参数命令服务共享真实 GlobalParameterCache/PreviewSession；
注入外部参数服务的旧 InProcessUiport 构造器保留，细节查询使用新增完整构造器装配，不能另存确认状态。
新字段及构造器均追加；旧构造器的空补充字段用 NON_NULL/NON_EMPTY 排除，保留其原进程内 JSON 形状。

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
  P5a-1a 起，`setChecked` 实际改变时生成 `workspaceChanged(CHECKED_CHANGED)`；重复设置为空操作。
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

## P5a-1c 参数命令、查询与撤销边界

认领 C14–C19、Q05–Q07、E01 和旧 setParams 共用底座。ready.methods 在既有方法后追加以下九项。
仍用 v1 四类帧及六个既有错误码，所有旧字段保留。GUI 接线保持现状。

| ID / 方法 | params（全部 R） | result |
|---|---|---|
| C14 setGlobalParams | imageMode:boolean，params:Config | null |
| C15 setGroupParams | groupNo:非负 long，imageMode:boolean，params:Config | null；未知组 no-op |
| C16 setItemParams | path:非空白 string，imageMode:boolean，params:Config | null；未知条目 id no-op；不读取源文件识别目标 |
| C17 clearParams | fallbackTargets:FileRef（array<TargetId>），imageMode:boolean | null；后端勾选优先，回退第一个可解析目标；整批恰好一条撤销 |
| C18 applyGlobalParams | target:TargetId，imageMode:boolean | null；目标有效参数应用到该模式全局，清该模式全部独立覆盖；另一模式不变 |
| C19 undo | {} | null（空栈 no-op，或成功撤销结构/参数记录）；非空栈恰好消耗一条记录 |
| Q05 runtimeConfig | runtime:RuntimeContext | {config:RuntimeConfig,revision} |
| Q06 outputUnitPlan | checkedOnly:boolean，runtime:RuntimeContext | {units:FileRef,revision}；文件为 array<OutputUnitVm> |
| Q07 paramSchema | imageMode:boolean | {schema:ParamSchemaVm,revision} |

参数写入复用同一 WorkspaceParamCommandService、GlobalParameterCache、WorkspaceModel 撤销栈和
WorkspaceParamService 缓存规则。Config 整体替换，缺失字段沿用 AppConfig 初值（autoFixEncoding=true，
其余 boolean=false，string=null）；新命令严格验证每个已提供属性的类型，未知属性、runtime 属性、
boolean 的 null/字符串/数字及 string 的非字符串非 null 值均为 INVALID_PARAMS，完整验证后才执行。
setParams 仍接受旧 `{imageMode,config}`，继续保存旧脚本提取模式，其旧反序列化行为不改。
新五命令的 imageMode 只决定参数作用模式，不改变脚本模式。

Config 为 AppConfig 的 29 个持久化属性，使用既有 JSON 名称（并非 schema 的 Java 字段 id）：
separateFiles、enableLimit、startMarker、endMarker、useRegex、enableCustomTemplate、startTemplate、
endTemplate、addChapterNumber、autoFixEncoding、enableTitleExtract、titleStartMarker、titleEndMarker、
titleUseRegex、imageNameExtractEnabled、imageNameStartMarker、imageNameEndMarker、imageRegionExtractEnabled、
imageRegionStartMarker、imageRegionEndMarker、imageRenameEnabled、imageZipEnabled、imageCbzEnabled、
singleOutputMode、legacyMemoryMode、nameLimitStart、nameLimitEnd、contentLimitStart、contentLimitEnd。
最后四个废弃兼容属性仍保留，不静默丢弃。

RuntimeConfig 显式输出上述全部属性，加 inputFolder/outputFolder（string 或 null）、
imageModeEnabled/openFolderAfterComplete（boolean），共 33 项，包括四个 JsonIgnore 属性。
请求的 RuntimeContext 四项全部必填；目录按既有路径边界校验，查询不创建输出目录。
Q05/Q06 每次创建请求局部计划上下文，读取同一后端参数权威源，调用现有 ExtractionPlanService；
不保存前端偏好、不覆盖 setParams/extract 的脚本模式或目录，不推进 revision。
尚未实现 P1 的 Java Uiport.forRequest 门面（须随未来 IpcUiport/前置 root 迁移闭合）；
本批同步查询已绑定本次 runtime，活动/提取的更广 Context 迁移不在此处冒称完成。

OutputUnitVm 文件每项恰好六字段：outputName:string 或 null、group:boolean、params:RuntimeConfig、
paths:array<string>、outputPath:string 或 null、labelKey:string 或 null。数组顺序和 null 保留，
不添加 fileCount（数量为 paths.length）。生效参数解析与输出规划复用进程内实现。

ParamSchemaVm 顶层为 imageMode/groups/fields；group 为 id/labelKey/noteKey（最后一项可 null）。
每个 field 显式且始终发送十项：id、type（BOOLEAN/TEXT）、labelKey、group、defaultValue、enabledBy、
disabledValue、nullWhenDisabled、promptText、templateEnter。defaultValue/disabledValue 只允许
boolean/string/null。其余可空字符串及提示文本原样保留。来源是 ParamSchemaService 的同一 schema，
文本 14 个 field、图片 11 个 field；“十字段”指每个 field 的十个属性，不是表单字段数。
编码不用 schema record 反射，不增加 native 元数据。

E01 paramsChanged 保留 kind/affectedCount，追加 imageMode、affectedPaths:FileRef、revision。
affectedPaths 文件为 array<string>，顺序与端口事件相同；在参数写入、压栈及磁盘缓存失效之后发布。
图片参数变化实际删除受影响预览缓存，文本参数变化保留图片缓存，遵循既有规则。
后端同时通过 PreviewItem.resetPreviewState 清理受影响条目的内存预览态，防止下一次 loadPreview
复用已经删除的文件。自动重解析的完整 GUI 后处理仍属前置迁移；当前客户端可用既有预解析命令发起。
未知组/条目与无可清参数的 no-op 不压栈、不发 E01、不推进 revision。

**U2 后端完整参数撤销已实现（P5a-1f）**：C19 在同一后端模型内弹出一条记录，
经 WorkspaceParamCommandService 恢复 PARAM_SET（全局/组/项/批量）、CLEAR_PARAMS、
APPLY_GLOBAL 的 before 快照。null 恢复未确认；只恢复记录对应模式，另一模式不变。
恢复后的 Q10 independent/effective/source/Config、uniform/hasEffective 与操作前一致。
派生预览态与提取结果重置；磁盘缓存复用前向 WorkspaceParamService.paramsChanged 规则 (a)：
图片模式全局恢复保守全删、目标恢复按 affected 删除，文本模式保留图片缓存。
全部恢复与缓存失效完成后只发布 workspaceChanged(UNDO_APPLIED)，含 undoKind、affected、
suggestedSelection 与同版完整 snapshot；响应、Q08/canUndo、工作区与后续 Q10 观察一致。
IPC 校验非空栈恰好减少一条；空栈保持 no-op。帧类型和既有错误码集合不变。
GUI 本地撤销路径删除与自动重解析接线留 P5a-3；本批不修改 GUI，也不改变结构撤销行为。

## P4-2b 兼容规则与方法发现

帧版本仍为整数 `v=1`，四种帧与原有五种错误码的语义不变。P4-2b 扩展添加方法和这些方法产生的事件，
`ready.data.methods` 在原来的两项后追加支持的方法；客户端应按包含关系检测能力，允许未知方法名。
原有 `workspace` 字段、顺序、null 与 `setChecked` 请求/结果保持不变；P5a-1a 仅追加字段与事件。
旧客户端须忽略未知字段和事件。P4-13 增加下面的强制启动握手；没有自动重连或并行命令执行。

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
启动默认查找发行目录下的 `Backend\MhtExtractorBackend.exe`（P4-21 前后端分包）；旧发行包的
根目录扁平布局仍兼容定位，JVM jar 查找 jar 同目录、散装 classes 查找工作目录；
`-Dmht.backend.executable=<path>` 可覆盖。拉起命令为
`<exe> -Djava.io.tmpdir=<GUI tmpdir> --serve --locale <GUI language>`。
握手必须声明完整后端能力集且确认往返标志为 true。失败显示状态与警告 toast；
现有列表、预览、提取仍走进程内 app 端口。native EXE 重建与上述路径运行另验。
`BackendIpcConfirmationTest` 使用真实无头子进程、深度 101 的两个兄弟分支，比较 IPC/in-process 的
确认次数与逐文件结果：确定询问一次，取消/不回答询问两次，排除“静默按取消”的伪往返。
CG-1 故意不答；CG-2 在看到确认帧后关客户端输入，并用自然返回的测试宿主检查线程，避免
`System.exit` 掩盖残留 worker。移除 timed get/移除 EOF 处理可分别令有限时间断言跑红。

| 新方法 | params | result |
|---|---|---|
| `importPaths` | `paths`: 非空绝对地址字符串数组，或 `pathsFile`: FileRef（二选一）；可选 `sourceDesc`（默认 IPC）、`fromDrag`（默认 false）、`async`（默认 false） | 同步 `{count,failed}`：当前工作区条目数与导入是否报告错误；异步 null 表示已启动 |
| `group` | `paths`: 工作区 id 数组，或 `pathsFile`: FileRef（二选一）；`namePrefix`: 非空字符串 | null；复用端口建组，旧数组未知 id 跳过 |
| `setParams` | `imageMode`: boolean；`config`: 命名配置格式的 AppConfig JSON 对象 | null；复用全局参数写入、撤销和缓存失效；整体替换该模式配置 |
| `extract` | `checkedOnly`: boolean；`outputFolder`: 绝对目录（可选白名单限制） | `{generation,end,outputFolder,count,failCount,skipCount,warnCount,resultsFile}`；失败终态可无统计和结果文件 |
| `workspacePage` | `offset`: 非负 int；`limit`: 1–256 的 int；`revision`: integer（可选） | `{total,offset,groupCount,items,revision,snapshotFile}`；超过总数返回空数组，版本不匹配拒绝 |
| `previewFiles` | `path`: 已导入且未移除的工作区 id | `{imageCount,manifest}`；只传文件描述，不传正文、HTML、图像字节或像素 |
| `fileInfo` | `path`: 本会话签发的文件路径 | 文件描述；检查存在与规范化后的会话归属，无通用文件内容读取方法 |
| `releaseFiles` | `paths`: 非空已签发文件路径数组 | null；全部校验成功再释放，非法条目整批拒绝；bulk 文件删除，缓存原图仅撤销签发，实际删除归预览生命周期 |
| `metrics` | `{}` | `{heapPoolPeakBytes,callbackQueuePeak,rawProgress,sentProgress,callbackQueueSize,activityWorkers}`，口径见压测 |
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
| `paramsChanged` | `{kind,affectedCount,imageMode,affectedPaths,revision}`：affectedPaths 为 JSON array<string> 的 FileRef（P5a-1c 追加） |
| `notice` | `{key}`：业务通知键，不传正文/列表 |
| `extractionProgress` | `{generation,overall}`：进度 0–1；旧 extract 每进入更大的整数百分比桶才发，最多 **101** 帧/任务；异步 startExtraction 保留端口进度顺序 |
| `extractionTerminal` | 与 extract 结果相同的统计摘要，追加 sessionOutputFolder 和 stats:FileRef或null，**不含 resultsFile 或 files 数组**；每任务一次 |
| `confirmationRequested` | `{confirmationId,key,namedParams,actionKeys,defaultAction,timeoutMs}`；不传预渲染文案 |
| `confirmationExpired` | `{confirmationId}`；P4-17 可选扩展，到期取消后前端收卡与提示 |

上述所有事件的 data 均追加必填 `revision:integer`。原字段保持不变。

## P5a-1b 活动、导入、预览与回调泵

认领 C01–C08、C20–C21、Q01–Q03、E02–E15、E21 与 previewFiles。
新增 12 方法、12 事件；扩展 importPaths / previewFiles 载荷及 importRound / importFinished / notice 三事件。
配套扩展 metrics 的泵观测字段、修正 releaseFiles 的缓存借用释放语义；共涉及四个旧方法。
旧 13 方法仍在 ready.methods 最前，1a 的八方法随后，1b 的十二方法追加。
帧类型、错误码与旧字段保持不变。所有事件携带独立递增 revision；查询 result 与 response 顶层均带同源 revision。

### 命令与查询

| 方法 | params（R = 必填） | result |
|---|---|---|
| cancelWorkspaceActivity | {} | null；只取消预解析/预览 |
| cancelPreviewLoad | {} | null；无在途也推进预览代际和 revision |
| startPreparse | runtime: RuntimeContext/R | null；非空目标才推进活动代际并发 started |
| startPreparseFor | paths: FileRef/R（array<string>）；runtime/R | null；不发 started，不推进活动代际 |
| refreshPreparseProgress | {} | null；每次发新的进度事件 |
| loadPreview | path:string/R，largeFileConfirmed:boolean/R，runtime/R | null；大文件未确认时只发 confirmationRequired |
| resolvePreviewRegion | generation:非负 long/R，path:string/R，accepted:boolean/R | null；旧代际 no-op；仅此命令应答区域确认 |
| awaitPreviewInFlight | millis:非负 long/R | null；包括清理 worker；到期不承诺全部退出 |
| applyImportPlan | plan: FileRef/R（ImportPlanVm） | null；不启动扫描，不自动发 importStateChanged |
| activityGeneration | {} | {generation,revision} |
| previewGeneration | {} | {generation,revision} |
| previewLoadingPath | {} | {path:string或null,revision} |

RuntimeContext 四字段全部必填：inputFolder/outputFolder 为绝对路径或 null，
imageModeEnabled/openFolderAfterComplete 为 boolean。活动启动时同步捕获模式和生效参数；
该请求的模式不写旧 setParams/extract 脚本模式。当前活动所用目录不参与解析，仍校验路径边界。
P5a-1c 增加参数/schema 查询；P5a-1f 完成后端 U2 参数撤销。Java forRequest 门面迁移另批处理；当前不增加端口签名。

ImportPlanVm 文件全部必填：batches、ignoredNonMht、hiddenDirs、emptyDirs、unreadable、depthLimited、quotaHit、hasMore；
五个计数为非负 int，两个标志为 boolean。batch 的 dirPath:string、folderName:string或null、grouped:boolean、filePaths:array<string> 全部必填。
整份计划、地址和所有类型在调用端口前校验，计划/paths 数组均由会话根内上传 FileRef 承载。
异步导入期间拒绝重入；普通模型写入排队，配额确认仍经 confirmAnswer。每轮恰好一条 ImportRound，撤销按整轮压栈。

### 事件字典

以下各 data 另含 revision:integer。

| 事件 | data |
|---|---|
| workspaceActivityChanged | generation |
| preparseProgress | generation,done,total |
| importStateChanged | generation,importing:boolean；true/false 各一次 |
| importRound | 原 generation,newFiles,newGroups；追加 plan:FileRef、madeListNonEmpty:boolean、createdGroups:FileRef（array<GroupVm>） |
| importFinished | 原 generation,count,failed；追加 accumulatedPaths:FileRef（array<string>） |
| preparseStarted | generation |
| preparseFinished | generation,result:FileRef（完整 PreparseResult，含失败项） |
| previewLoadStateChanged | generation,path:string或null,active:boolean |
| previewConfirmationRequired | path,name,sizeMiB；用户拒绝无需再发 loadPreview，接受调用 loadPreview(true) |
| previewLoadFailed | generation,path,fileInfo:boolean,detail:string或null |
| previewThumbnail | generation,path,index,imagePath,mediaType,extension,coreName,width,height,bytes；path 为来源 id，imagePath 为缓存图像路径，bytes 为图像字节数 |
| previewLoaded | generation,path,manifest:FileRef或null；取消/无结果 null；同一 owned load 终态恰好一次 |
| previewRegionConfirmationRequired | generation,path；不产生 confirmationId |
| previewNotice | generation,path,key,namedParams:object<string,string> |
| notice | 原 key；追加 namedParams:object<string,string>、level:ERROR/WARNING/INFO |

PreparseResult 文件：path,status,errorKey,errorDetail,text,textAssigned,title,titleAssigned 全部保留，
status 为 WAITING/READY/EMPTY/FAILED；可空字符串字段显式 null，assigned=false 不清旧缓存。
导入 WorkspaceChanged 缓冲到本轮撤销压栈后发；其快照与该事件 revision 相等。
导入结束顺序为 importFinished → importStateChanged(false)。

### D13：真实格式与文件生命周期

覆盖旧设计表的 PNG 要求：不转码 PNG、不传 ARGB/base64。
previewThumbnail.imagePath 与 manifest.thumbnails[].imagePath 为真实缓存图像绝对路径；
mediaType 根据实际字节魔数生成（JPEG 为 image/jpeg，扩展名 jpg），extension 为真实格式。
width/height 为编码原图尺寸，不是 150px 解码缩略图尺寸；不支持的尺寸头为 0，客户端必须解码并处理失败。
支持标识 PNG/JPEG/GIF/BMP/WebP/ICO/CUR/AVIF；这不等于承诺所有前端都能解码这些格式。
coreName 复用已有图片命名服务；不因输出顺序改变改写缓存图像。

previewFiles 保留同步脚本导出 text/html/thumbnails 与所有旧字段，图像追加 imagePath/mediaType/extension/coreName/width/height。
兼容 pngThumbnails:false；true 明确 INVALID_PARAMS，不能假装已转 PNG。
previewLoaded 的 manifest 同样有 text/html/thumbnails；正文/HTML 的导出在独立 worker 计算、
写成服务端生成文件名的 UTF-8 文件，再由宿主签发。取消/旧代际时 manifest=null；
导出异常显式追加 exportError 诊断并返回 manifest=null，不假装文件已导出。
缓存图片借用预览生命周期，releaseFiles 仅释放签发记录；参数/提取/移除/退出仍可让原图失效。
manifest/正文/HTML 是独立 bulk 文件，releaseFiles 实际删除。禁止当永久 URL 使用。

### 单写者、背压与关闭

宿主是模型命令/回调交付/事件编码/revision 分配/快照唯一串行点。
worker 提交到容量 256 的 FIFO 回调队列；满队列每 25ms 重试，以阻塞 worker 背压，不丢终态。
同一生产者顺序保持，不同生产者以成功入队次序线性化；同一个回调内的事件保持端口顺序。
宿主每轮最多排空 256 回调，stdin 空闲也以 25ms 周期继续交付。
所有帧先由宿主序列化，再进入容量 256 的输出 FIFO；唯一 mht-ipc-output 线程执行 write/flush，
revision 先于入输出队列分配，写出顺序与分配顺序一致。输出排队/单次写停滞 3s 为传输失败，不静默丢帧。

同步脚本 await 与 awaitPreviewInFlight 每个短等待继续泵回调/输入；confirmAnswer、取消、区域决定、活动三查询优先；
普通请求暂存最多 256，超限 INVALID_REQUEST。旧同步脚本的普通查询继续排队，不抢在它的完成响应之前。
EOF 取消已有活动、释放确认 waiter，排空已提交回调并等待预解析/导入/预览与清理 worker（10s 上限），
再停止输出 writer（3.5s join）并由 session owner 删除文件。闲置 EOF 不额外伪造活动变更。
Windows 同步管道的 write/close/interrupt 本身均可能阻塞；生产 runner 使用原始 stdio 描述符，
超时后通过 JDK 25 标准 FFM 调用系统 Kernel32.CancelIoEx，先取消本进程的在途 I/O 再 join（另限 1s）。
输出失败而 stdin 仍打开时，同样先取消输入 read，再关闭输入 reader。正常关闭不加载这个按需取消分支。
JDK 25 未启用 native access 时会给该异常分支输出警告，可用 --enable-native-access=ALL-UNNAMED；
FFM 的 native-image 支持和可达性尚需 Gate C 实跑，本批没有修改打包配方或元数据。
API 依据：[Microsoft CancelIoEx](https://learn.microsoft.com/en-us/windows/win32/api/ioapiset/nf-ioapiset-cancelioex)。
输出已断开时仍完成内部终态和 worker 收尾，不能承诺向断开的 peer 交付帧。
超时/输出异常明确失败；自然返回测试宿主检查线程，不用 System.exit 掩盖残留线程。

## P5a-1a 结构、来源、快照与文件上传

本批新增 8 个 wire 方法（C11–C13、C24–C27、Q08），扩展 C09/C10、Q04 与 hello；
新增 E19，扩展 E22–E24。GUI 仍使用进程内端口。C25–C27 是本批最小端口增量；
完整参数撤销、运行 Context、活动/预览接线由后续批次实现，不声明完整 GUI 重同步能力。

兼容边界：保留既有 13 方法及 6 个业务事件的全部字段、四种帧与六个错误码。
所有成功 response 顶层追加必填 `revision:integer`，`result:null` 原样保留。
每个发出的事件独占一个递增 revision；ready 的 revision 为 0，不消费变更序号。
查询不推进、结构空操作不推进；同一命令多个事件不能复用序号。
一个后端实例内单调，进程重建从 0 开始；客户端用 `(sessionId,revision)` 判重。
`ready.data` 与 `hello.result` 追加必填 `sessionId:string`，hello 另有 `bulkRoot:string`
（本会话真实绝对目录），保留原有五项 capabilities 与 confirmationRoundTrip。
ready.methods 保留全部旧名并追加上述八个新方法。

### 字段字典

| 对象 | 追加字段（全部必填） | JSON 类型 / 语义 |
|---|---|---|
| ItemVm（顶层、组成员、分页、文件快照统一） | sourceFolder | string 或 null；原始来源，null/空串均原样保留，不读来源目录 |
| GroupVm | sourceFolders | array<string 或 null>；未移除成员的来源首次出现顺序去重，摘要中空串归一 null，完整路径不按末级名合并 |
| WorkspaceVm wire | revision、topLevelOrder | integer、FileRef；顺序文件为 array<TargetId>，保留顶层组与条目穿插关系 |
| workspacePage.result | revision、snapshotFile | integer、FileRef；同版完整 WorkspaceVm，含所有组/成员/来源摘要与顺序文件 |
| workspaceChanged.data | kind、revision、snapshot | string、integer、FileRef；完成状态的完整同版 WorkspaceVm |
| canUndo.result | canUndo、undoDepth、revision | boolean、integer、integer；后端唯一栈事实，结构与 U2 参数记录均可撤销 |
| applyDrop.result | changed、affectedPaths、revision | boolean、FileRef、integer；受影响路径文件为 array<string> |

FileRef=`{path:string,bytes:integer,mediaType:string}`，三字段必填，JSON 文件 mediaType 必须为
`application/json`，bytes 必须精确等于文件长度。服务端文件名随机生成，客户端不能指定输出文件名。
TargetId=`{path:string或null,groupNo:integer或null}`，两字段必填且恰一非 null，组号为非负 long。
revision 为非负 long（JSON integer），客户端须精确存储，不能以浮点数比较。

`workspace` 原 params `{}` / 原五字段结果继续存在，追加上述字段。
可选 `bulk:boolean` 缺省 false，true 时 result=`{revision:integer,manifest:FileRef}`；
manifest 内容为完整 WorkspaceVm。大列表客户用 bulk 或 workspacePage，不把完整数组搬进管道。
第一页捕获不可变 VM 与 revision 一次；后续页带该 revision。任意发出事件使新查询重新捕获，
旧 revision 页请求返回 INVALID_PARAMS，禁止拼接两版。未携带 revision 的旧分页请求继续工作。
snapshotFile / 顺序文件由服务端签发，与 releaseFiles 共用生命周期；释放后需重新拉取第一页快照。

### 新命令和请求扩展

| ID / method | params（R 必填，O 选填） | result / 错误 |
|---|---|---|
| C09 setChecked | paths:array<string或null> 或 pathsFile:FileRef（二选一 R），checked:boolean/R | null；非法类型/两源同时出现/非法上传 INVALID_PARAMS；旧未知 id/null/空数组跳过 |
| C10 group | paths:array<string> 或 pathsFile:FileRef（二选一 R），namePrefix:string/R（非空白） | null；非法文件路径/类型 INVALID_PARAMS；旧数组未知 id 跳过 |
| C11 ungroup | groupNo:integer/R | null；未知组 no-op，非法组号 INVALID_PARAMS |
| C12 renameGroup | groupNo:integer/R，newName:string/R | null；唯一化由模型负责；null/非字符串 INVALID_PARAMS，未知组 no-op |
| C13 toggleGroupExpanded | groupNo:integer/R | null；实际翻转才发事件；未知组 no-op，非法组号 INVALID_PARAMS；不自动重放 |
| C24 clearItems | {} | null；只清数据序，保留 undo 栈/组号；空工作区 no-op |
| C25 applyDrop | dragged:TargetId/R、target:TargetId或null/R、zone:string/R（TOP/INTO/BOTTOM）、revision:integer/R | 上述 Drop VM；旧 revision/非法 ID/zone INVALID_PARAMS；target=null 仅 BOTTOM，INTO 仅组；自身/原位 no-op |
| C26 removeItems | paths:FileRef/R（文件 array<string>） | null；隐藏移除、空组销毁、撤销、缓存规则 c / 内存态重置；非法上传/路径 INVALID_PARAMS |
| C27 removeGroup | groupNo:integer/R | null；整组移除与撤销，缓存规则 c / 内存态重置；非法组号 INVALID_PARAMS，未知组 no-op |
| Q08 canUndo | {} | 上述 Undo VM；委托端口查询 |

新增 paths 文件为空数组也可用。上传文件允许客户端在 hello.bulkRoot 内创建；校验
绝对规范化路径、真实路径（拒绝链接/junction 重定向）、普通文件、精确长度及 JSON 类型后再读。
上传不进入服务端签发集合，不能借 fileInfo/releaseFiles 读取或删除上传；退出时由会话清理。
其它会话根、根外路径、相对路径、越权业务路径均 INVALID_PARAMS；不开放任意路径读取。
旧 setChecked 的 paths 是精确匹配 ID，不读取文件，未知字符串（包括相对未知 ID）仍跳过。
新文件参数中的业务路径必须绝对，并受 mht.allow.roots 约束；旧 group 数组仍仅作为精确匹配 ID。

E19 kind 保留模型九类 `ITEMS_ADDED/GROUPED/UNGROUPED/ITEMS_REMOVED/GROUP_REMOVED/REORDERED/MERGED/UNDO_APPLIED/CLEARED`，
新增 `CHECKED_CHANGED/GROUP_RENAMED/GROUP_EXPANDED_CHANGED`。结构处理先完成，再发布事件；
拖放父组变化时清条目独立参数、失效图片缓存并重置内存状态；已有有效参数才触发必要预解析。
不增加参数 undo 记录，不把两个参数命令拼成一次拖放。缩略图 D13 仍为缓存编码原图路径与真实格式，
本批不增加预览入口，不转码 PNG、不传 ARGB。

宿主在 stdin 空闲时每 25ms 处理有界回调队列（每轮最多 256），不在输入读线程改模型。
C25 后处理预解析因此不依赖下一次同步活动；未产生 wire 事件的有效回调也推进 revision。
新增活动命令与完整预解析事件仍留给 1b；本批只补 C25 所需的宿主回调处理。

### 示例帧（文件内容另述）

```json
{"v":1,"type":"event","event":"ready","data":{"methods":["workspace","setChecked","importPaths","group","setParams","extract","workspacePage","previewFiles","fileInfo","releaseFiles","metrics","confirmAnswer","hello","ungroup","renameGroup","toggleGroupExpanded","clearItems","applyDrop","removeItems","removeGroup","canUndo"],"sessionId":"session-A","revision":0}}
{"v":1,"type":"response","id":"h","revision":0,"result":{"kind":"welcome","protocolVersion":1,"version":"3.0.0","buildId":"3.0.0-p4-13-ipc1","capabilities":["workspace","setChecked","workspacePage","bulkFiles","confirmationRoundTrip"],"confirmationRoundTrip":true,"sessionId":"session-A","bulkRoot":"H:/TEST/temp/mht-ipc-123"}}
{"v":1,"type":"request","id":"w","method":"workspace","params":{"bulk":true}}
{"v":1,"type":"response","id":"w","revision":3,"result":{"revision":3,"manifest":{"path":"H:/TEST/temp/mht-ipc-123/bulk-1.json","bytes":230,"mediaType":"application/json"}}}
{"v":1,"type":"request","id":"p","method":"workspacePage","params":{"offset":0,"limit":1,"revision":3}}
{"v":1,"type":"response","id":"p","revision":3,"result":{"total":0,"offset":0,"groupCount":0,"items":[],"revision":3,"snapshotFile":{"path":"H:/TEST/temp/mht-ipc-123/bulk-2.json","bytes":230,"mediaType":"application/json"}}}
{"v":1,"type":"request","id":"r","method":"renameGroup","params":{"groupNo":1,"newName":"new"}}
{"v":1,"type":"request","id":"c","method":"setChecked","params":{"pathsFile":{"path":"H:/TEST/temp/mht-ipc-123/client-1.json","bytes":2,"mediaType":"application/json"},"checked":false}}
{"v":1,"type":"request","id":"g","method":"group","params":{"pathsFile":{"path":"H:/TEST/temp/mht-ipc-123/client-1.json","bytes":2,"mediaType":"application/json"},"namePrefix":"new"}}
{"v":1,"type":"request","id":"ug","method":"ungroup","params":{"groupNo":1}}
{"v":1,"type":"request","id":"te","method":"toggleGroupExpanded","params":{"groupNo":1}}
{"v":1,"type":"request","id":"cl","method":"clearItems","params":{}}
{"v":1,"type":"request","id":"rg","method":"removeGroup","params":{"groupNo":1}}
{"v":1,"type":"response","id":"rg","revision":4,"result":null}
{"v":1,"type":"request","id":"x","method":"removeItems","params":{"paths":{"path":"H:/TEST/temp/mht-ipc-123/client-1.json","bytes":2,"mediaType":"application/json"}}}
{"v":1,"type":"request","id":"d","method":"applyDrop","params":{"dragged":{"path":"H:/input/a.html","groupNo":null},"target":null,"zone":"BOTTOM","revision":3}}
{"v":1,"type":"response","id":"d","revision":4,"result":{"changed":true,"revision":4,"affectedPaths":{"path":"H:/TEST/temp/mht-ipc-123/bulk-3.json","bytes":19,"mediaType":"application/json"}}}
{"v":1,"type":"event","event":"workspaceChanged","data":{"kind":"REORDERED","revision":4,"snapshot":{"path":"H:/TEST/temp/mht-ipc-123/bulk-4.json","bytes":230,"mediaType":"application/json"}}}
{"v":1,"type":"request","id":"u","method":"canUndo","params":{}}
{"v":1,"type":"response","id":"u","revision":4,"result":{"canUndo":true,"undoDepth":1,"revision":4}}
{"v":1,"type":"event","event":"confirmationRequested","data":{"confirmationId":"confirm-1","key":"alert.recursionDepthHeader","namedParams":{},"actionKeys":["continue"],"defaultAction":null,"timeoutMs":30000,"revision":5}}
{"v":1,"type":"event","event":"confirmationExpired","data":{"confirmationId":"confirm-1","revision":6}}
{"v":1,"type":"error","id":"p","error":{"code":"INVALID_PARAMS","message":"Invalid method parameters"}}
```

示例 bytes 为示意，真实请求必须使用实际长度；事件发生在对应命令响应之前。

默认 workspace 的空工作区示例保留旧五字段：

```json
{"v":1,"type":"request","id":"wl","method":"workspace","params":{}}
{"v":1,"type":"response","id":"wl","revision":4,"result":{"groups":[],"topLevelItems":[],"canUndo":true,"undoDepth":1,"selection":"NONE","revision":4,"topLevelOrder":{"path":"H:/TEST/temp/mht-ipc-123/bulk-order.json","bytes":2,"mediaType":"application/json"}}}
```

完整结构文件的来源与混合顺序字段示例（selection 使用实际端口枚举）：

```json
{"groups":[{"no":1,"name":"new","expanded":true,"defaultNamed":false,"members":[{"id":"H:/input/a.html","name":"a.html","checked":true,"removed":false,"unitIndex":0,"update":"NONE","sourceFolder":"H:/input"}],"sourceFolders":["H:/input"]}],"topLevelItems":[],"canUndo":true,"undoDepth":1,"selection":"ALL","revision":4,"topLevelOrder":{"path":"H:/TEST/temp/mht-ipc-123/bulk-order.json","bytes":27,"mediaType":"application/json"}}
```

其中顺序文件内容为 `[{"path":null,"groupNo":1}]`；paths 上传空清单为 `[]`。
`ungroup/toggleGroupExpanded/removeGroup` 请求均仅 `{groupNo:1}`；`clearItems/canUndo` 为 `{}`。
上传路径文件示例 `["H:/input/a.html"]`；顺序文件示例
`[{"path":null,"groupNo":1},{"path":"H:/input/b.html","groupNo":null}]`。

## 批量文件、安全与生命周期

`--serve` 使用绝对 `java.io.tmpdir`（允许中文、空格），本机自测设 `H:/TEST/temp`。
每实例在配置目录下
创建随机 `mht-ipc-*` 目录；预览缓存也注入此会话根，避免清掉另一个实例/GUI 的缓存。
文件描述为 `{path,bytes,mediaType}`，path 是服务端签发的绝对路径，bytes 为落盘长度。
预览 manifest 是一个 JSON 文件，包含 `text` / `html` 文件描述和 `thumbnails` 数组；每个图像描述
另有 `name` / `index`。图像使用现有预览缓存中的编码原图，客户端自行解码、缩放为缩略图；
服务端不引入图像编码器，不改变 JavaFX 的 ARGB 缩略图链路。HTML 是 UTF-8 的原始源码文件，
不是浏览器导出页；正文走现有 SourceParseService，图片走 PreviewService。即使图片很多，
管道也只返回一个 manifest 描述。提取逐文件结果 `{path,outcome,message}` 同样走 `resultsFile`。

客户端读取后可主动释放文件；提取完成按已有缓存生命周期清理已处理输入的预览缓存，旧的图像引用
因而失效。EOF/正常退出/传输 IO 异常结束后删除本实例所有 bulk 文件与缓存，持久化提取产物保留。
强制杀进程/系统崩溃可能留下随机会话目录；后端 `--serve` / `--once` 启动时，
只扫描配置临时根的直属 `mht-ipc-*` 目录，跳过符号链接/重定向目录。
每个会话终生持有 `.session.lock` 排他文件锁；扫描仅在 `tryLock` 成功时回收，
锁被占用（包括本 JVM 的重叠锁）则跳过。无锁文件的旧会话也回收。
临时根内持久保留 `.mht-ipc-startup.lock`，串行化扫描与会话创建/持锁，
排除新会话尚未创建锁文件的窗口；正常退出释放会话锁并删除会话目录。
回收不跟随树内重定向，不删除临时根、其它前缀目录或直属文件，且不受 `mht.allow.roots` 影响。
这是后端本地行为，不增加协议方法或事件；无法安全访问/删除的条目留待后续启动重试。
单次启动的回收预算为 250 ms（在目录/文件操作间检查）；大型孤儿可分多次启动清完，
只有整个目录删除成功才计入回收数，避免历史积压阻塞启动握手。文件系统单次 IO 的耗时不受此预算硬中断。

输入/输出默认只要求绝对路径；`-Dmht.allow.roots` 可选开启白名单（按平台 pathSeparator 分隔绝对根），
开启时不允许直接操作根本身。先要求绝对路径、normalize 后
检查目录边界，再用 `toRealPath` 复核真实归属；导入目录预检树中路径，单个 HTML 地址也预检其侧车目录，
拒绝跳出白名单的链接/junction。
输出校验最近存在的祖先，再创建目录并复核；preview/extract 在打开文件前再次校验已导入 id。
`group`/旧 `setChecked` 的 paths 仅是工作区标识，不作为文件读取目标。
`fileInfo` / `releaseFiles` 同时检查规范化后的会话根与签发集合，拒绝同目录未签发文件、其它实例路径、
外部输入路径和目录穿越；错误不会泄露文件内容。没有任意路径读取接口。
这些是 IPC 参数边界检查，不能防御具有相同 OS 权限的本机进程在校验与文件打开之间恶意替换目录。

## P5a-1d 提取结果与统计

认领 C22–C23、E16–E18、E20；E19 的 kind、同 revision 完整 snapshot 沿用 1a/1b。
已有端口异步通道仍由 WorkspaceExtractionCoordinator 唯一拥有；本批将它暴露到 IPC 并补结果腿。
ready.data.methods 在原方法之后追加 startExtraction / cancelExtraction，welcome.capabilities 同步追加这两个名称。
帧类型、错误码、旧字段与旧 extract 同步脚本行为保持兼容。

| 方法 | params | result |
|---|---|---|
| startExtraction | checkedOnly:boolean/R，runtime:RuntimeContext/R | null；启动后立即响应，不等待 worker |
| cancelExtraction | {} | null；取消当前 run，重复请求为空操作 |

RuntimeContext 与 1b 相同。提取必须提供非空绝对 outputFolder，inputFolder 可以为 null；
目录在启动前按既有文件边界规则校验。模式、参数、输出单元与会话目录在启动时冻结，
不会写入旧 setParams/extract 的脚本模式。已有任务仍 running 时重入 startExtraction 报 INVALID_PARAMS。
取消和确认回答始终可在活动期间处理；普通工作区写命令仍排队。
取消后的 running=false 只表示终态已交付，不能据此删除 worker 使用的文件。

所有事件 data 都带独立递增 revision，generation 为所属 run 代际。

| 事件 | data（另含 revision） |
|---|---|
| extractionProgress | generation,overall；异步路径保持端口顺序，旧 extract 保持百分比桶合并 |
| extractionTerminal | generation,end,outputFolder；旧可选 count/failCount/skipCount/warnCount 保留；追加 sessionOutputFolder:string或null、stats:FileRef或null |
| extractionResult | generation,sessionOutputFolder:string或null、stats:FileRef；取消 worker 收尾后 partial 恰好一次 |
| runningChanged | generation,running:boolean；启动 true，终态 false；InProcessUiport 投影协调器既有状态 |

E20 在端口适配层启用：启动命令成功返回后、worker 的宿主回调交付前，投影协调器的 running/generation；
终态交付时向同一订阅者投影协调器已复位的 running。命令与回调使用同一宿主串行线程；
适配层不持有第二份任务状态。取消时的 false 与取消终态一起在请求时刻交付，不表示 worker 已退出。

每 run 恰好一个 SUCCEEDED / FAILED / CANCELLED 终态；取消终态在请求时交付，stats=null，
无需等待 worker。成功的完整统计随终态交付，不额外发 extractionResult；失败（包含 Error）stats=null。
取消后 worker 返回非空统计时才发 partial；异常没有 partial。旧 run 迟到的结果遵守端口陈旧守卫，
不会投递到新 run。outputFolder 与 sessionOutputFolder 是同一冻结目录的兼容别名。

stats FileRef 指向完整 UTF-8 JSON：
`{summaryLine:string或null,failCount:integer,skipCount:integer,warnCount:integer,files:array<FileOutcome>}`。
FileOutcome 为 `{path:string,outcome:string,message:string或null,warnings:array<ExtractWarning>}`；
ExtractWarning 为 `{key:string,args:object<string,string>}`。全部字段保留，空 warnings 为 []。
outcome 为 NONE/SUCCESS/FAILED/SKIPPED/SUCCESS_WARNED；summaryLine/message 沿用兼容诊断字符串。
统计和文件结果不内联到协议帧，不传缩略图像素。stats 文件由会话签发，遵守既有 releaseFiles 与退出清理规则。
旧 extract.resultsFile 仍是仅 path/outcome/message 的数组，与完整 stats 文件分别签发。
统计与 importRound.plan 均采用显式完整投影，避免 native 对未注册 record 反射的依赖；文件字段保持原定义。

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
