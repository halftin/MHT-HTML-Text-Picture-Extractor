# JSON Lines 控制协议 v1（P4-2a）

启动 `com.mhtextractor.backend.BackendCli --serve`，stdin/stdout 为 UTF-8 JSON Lines：
每行恰好一个 JSON 对象，输出行尾为 LF，字符串中的换行由 JSON 转义。每帧立即 flush。
日志沿用独立 logback 文件配置（`logs/backend.log`）；stderr 留给 JVM/启动诊断。
客户端须分别读取 stdout、stderr，不能合并。工作目录与临时目录按仓库约定放 H 盘。

帧示例：

```json
{"v":1,"type":"event","event":"ready","data":{"methods":["workspace","setChecked"]}}
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

边界：正式 `--serve` 初始工作区为空；本批不开放导入、恢复会话、提取、确认往返或其它端口能力。
非空契约测试用测试源集装配器在真实子进程内建立分组/条目/移除夹具，再运行同一个生产 IpcServer；
没有给生产入口加测试数据开关。与直接端口执行同一命令序列后，用独立 Jackson record 序列化结果
逐字段比较（包括数组顺序、null 和转义字符串；忽略对象键顺序这一无语义差异）。正式 CLI 另有子进程往返测试。

本批没有 bulk 路径协议、脚本客户端、进度合并、分页/流控、帧大小上限、协议握手协商、重连、
并行请求执行、共享内存、1 万文件压测或 native 支持验证。输入/快照当前整体驻内存，不能视为
C13 性能验收；预览文本/HTML/缩略图后续按方案 §14 决策 9、§15 走文件路径。
本地方案 §7 现为 C1–C20 冲突表，没有独立 D3 条目；传输依据取 §14 决策 9 中的 D3 引用。
