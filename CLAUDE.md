# CLAUDE.md

> 工作区指令，**改代码前必读**。
> **最后更新：2026-10-19**（分支 `2.5-beta`，项目版本 **`3.0.1`**，版本号以 `pom.xml` 为唯一来源；**P-F 批次（P-F0…P-F13，含 P-F2b/P-F10b）全部完成并提交**，见 §1 的 P-F 行与台账 §94–§112）。
> 文档集**共五份**：**本文件**（工程约定）+《基于graalvm的MHT资源提取器项目打包工作流程.md》（打包手册）+
> 《遗留目标与未完成事项.md》（尚未收口的目标与边界）+《UI可切换重构方案.md》（UI 可切换 / 无头后端重构，2026-10-05 定稿）+
> 《未验证项.md》（**未验证项与验证计划**；2026-10-16 起单独成文，**所有验证/测试工作在该文专门处理**）。
> **推送口径（2026-10-12 修订）**：**不做远程推送 —— 只提交到本地仓库**（用户 2026-10-12 明确要求；`1e37fdc` 是最后一次 push）。若确需 `git push`（必须**用户显式要求**），**只有本文件与打包手册**随仓库分发；《遗留目标与未完成事项.md》《UI可切换重构方案.md》《未验证项.md》是**临时文档**（本地保留、已 `.gitignore`、不入远程仓库）。
> 其余历史文档已删除，需要追溯请看 git 历史。

---

## 1. 项目与当前状态

JavaFX 25 桌面 GUI：把 `.mht` / `.mhtml` / `.html` / `.htm`（含 HTML 侧车文件夹）批量提取为**文本**与**图片**。
入口 `com.mhtextractor.App`。

| 项 | 现状（2026-10-19 实测；P-F 批次后） |
|---|---|
| **P-F 批次（2026-10-19 全部完成）** | **P-F0…P-F13 + P-F2b + P-F10b 全部实施、独立验收、本地提交**（未推送）：P-F1 `3907c10` · P-F2 `75863a5` · P-F2b-1 `fc482f7` · P-F2b-2 `fd2d4df` · P-F3 `d1d60b5` · P-F4 `d3f713a` · P-F5 `9476e2d` · P-F11 `a621ee8` · P-F12/13 `7fcbb00` · P-F9 `97f023f` · P-F10 `fcd6816` · P-F10b `4e70996` · P-F6 `cf7b7c1` · P-F7 `4af5f08`。最终基线：`clean test` **1134/0**（1 跳过）+ headless **74/0** + 架构 **17/0** + 产物基线 **46 份** + GUI zh/en **37/0**；前端闭包 **discovered=0 / FROZEN_MAX=0 / PENDING_MAX=15**；GUI native **业务标记 HITS=0**（后端 `-ExpectBusiness` 对照 PASS）；`MainController` **1607** 行；版本 **3.0.1**（协议 **2**）。 |
| **P-F6 复核（2026-10-19，已验收）** | 产物闸门 `tools/verify/assert-gui-mirror-clean.ps1` 已分发并接入 `build-native.cmd`；指挥方亲手跑 `-DistDir`（HITS=0）与 22 项控制全绿；dist **23 文件 / 113,674,868 B**，GUI exe **69,009,408 B**。 |
| 重构阶段 | A1 / A2 / B / C / D / E / F **全部实施并验收**；第二批 **S0–S6（E1–E5）** 亦已实施并验收；《UI可切换重构方案》**P0（三条度量/不变式）与 P1-1…P1-5（全局参数缓存 + 预览缓存构造 + 侧边栏会话基线 + 参数剪贴板 + 撤销载荷 + 启动前顺序门链下沉 app，闸门 2 已归零）已实施并验收**；**P2-1/P2-2（抽 ui-core：`ExtractOutcomeStyles` / `HtmlFindIndex` / `ParamTargetText` / `FileMenuModel` 下沉 `com.mhtextractor.uicore`，零 JavaFX）已实施并验收**；**P3-1（端口能力覆盖矩阵草案：167 条 → 覆盖 42 / 前端 92 / 缺口 33）· P3-2（`com.mhtextractor.uiport` 最小切片 API 定稿，19 类 / 0 违规）· P3-3a（`app.InProcessUiport` 进程内适配器，工作区可回答子集 + 端口诚实化收敛）· P3-3b-1（产物命名规则下沉 `app.OutputNameService`；gui 直连业务类型 34 → 32）· P3-3b-2（预览缓存键计算与每项目写锁下沉 `app.PreviewService`；直连 32 → 30；`MainController` 1706 → 1705）· P3-3b-3（提取运行时配置 + 输出单元计划下沉 `app.ExtractionPlanService`；直连 30 → 29；`MainController` 1705 → **1679**）· P3-3b-4（「查看源码」导出下沉 `app.SourceViewService`；直连 29 → 28）· P3-3b-5（会话状态持久化下沉 `app.SessionStateService`；直连 28 → 26；`MainController` 1679 → 1677）· P3-3b-6（预览命名/扩展名纯函数下沉 `app.PreviewNamingService`；直连 26 → 23）· P3-3b-7（预解析解析+取正文/标题下沉 `app.SourceParseService`；直连 23 → 20）· P3-3b-8（命名配置 + 像素打包两单例收口 `app.NamedConfigService`/`app.PreviewPixelService`；直连 20 → 18）· P3-3b-9（提取每文件覆盖映射构造下沉 `app.ExtractionPlanService.overrides`；直连 18 → 17）· P3-3b-10（gui 去除 `i18n.ErrorCode` 直连，改写为 `I18n.get("error.<NAME>")`；直连 17 → 16）· P3-3b-11（gui 去除 `extractor.ImageExtractor` 直连，改用 `app.PreviewImageData`；直连 16 → 15）· P3-3b-12（gui 去除 `util.SelectionState` 直连，改用 `app.SelectionRules`；直连 15 → 14）· P3-3b-13（提取结果回写下沉 `app.ExtractResultService`；直连 14 → 13）· P3-3b-14（`util.ExtractOutcome` 迁入 `model`；直连 13 → 12）· P3-3b-15（`util.ChangeListener` 迁入 `model`；直连 12 → 11）· P3-3b-16（预览缓存生命周期经 `app.PreviewSession` 门面收口；直连 11 → 10）· P3-3b-17（`PreviewResult` 增中性 `ImageRef`/`images()`，gui 去除 `cache.PreviewCacheMeta` 直连；直连 10 → 9）· P3-3b-18（gui 去除 `util.GroupStructure` 直连，改用 `app.WorkspaceStructure` 包装；直连 9 → 8）· P3-3b-19（`util.GroupItem` 迁入 `model`；直连 8 → 7）· P3-3b-20（`util.MhtImportPlanner` 迁入 `app`；直连 7 → 6）· P3-3b-21（`util.DropResolver` 与 `util.UndoManager` 迁入 `app`；直连 6 → 4）· P3-3b-22（新增中性 `app.ExtractionStats` DTO，gui 去除 `processor.BatchProcessor` 直连；直连 4 → 3）· P3-3b-23（`util.ParamScope` 迁入 `model`；直连 3 → 2）· P3-3b-24（新增 `app.Texts` 文案门面，gui 去除 `i18n.I18n` 直连；直连 2 → 1）· P3-3b-25（`config.AppConfig` + `util.FingerprintEncoder` 迁入 `model`；直连 1 → **0，P3 目标达成**）· **P3-B1（端口查询三件 `runtimeConfig`/`outputUnitPlan`/`paramSchema`；表单改 schema 驱动；访问面 119 → 118；测试 863 → 869；`MainController` 1677 → 1670）· P3-B2（端口活动编排三件：取消工作区活动 + 预解析编排/事件 + 预览加载编排/事件；新增 `uiport.PreparseResult`/`app.WorkspaceActivityService`；`PreparseController` −184 行、`PreviewLoader` −315 行；访问面 118 → 116；测试 869 → 877；`MainController` 1670 → 1654）· **P3-B3（端口勾选 `setChecked` + 分组命令集 `group/ungroup/renameGroup/toggleGroupExpanded`；勾选唯一写入口收口到模型、组名唯一化以模型为源、展开状态后端化且幂等；测试 877 → 884、headless 68 → 73、`MainController` 1654 → 1648）· **P3-B4（端口参数命令族 `setGlobalParams`/`setGroupParams`/`setItemParams`/`clearParams`/`applyGlobalParams`；新增 `app.WorkspaceParamCommandService`（三层应用+撤销压栈+缓存失效整体下沉）、`app.ParamTargets`（勾选优先解析只搬不改）、`uiport.TargetId`；测试 884 → 912；⚠️ `MainController` 1648 → 1656（+8 装配适配器，**已裁决接受并记账**））· **P3-B5（导入编排下沉：端口 `importPaths`/`applyImportPlan` + `ImportStateChanged`/`ImportRound`/`ImportFinished` 事件 + `ImportPlanVm`/`ImportBatchVm`；新增 `app.WorkspaceImportCoordinator`、`gui.component.GuiConfirmer`；**BC-5 一次导入=每轮恰好一条撤销记录**（首轮曾误作「每批一条」而红，已逐处反向回退并拆小重做）；访问面 116 → 114；`MainController` 1656 → 1655）· **P3-B6（提取任务通道契约：端口 `startExtraction(checkedOnly)`/`cancelExtraction()` + non-sealed `ExtractionChannelEvent`（`ExtractionProgress`/`ExtractionTerminal`/`ExtractionResult`）+ `ExtractionEnd`；新增 `app.WorkspaceExtractionCoordinator`；`ExtractionStats` 迁入 `model`；收尾另修 worker `catch(Exception)`→`catch(Throwable)`（Error 亦须交付 FAILED 终态，否则通道晾死）；访问面 114 → 108；测试 912 → 914；`MainController` 1655 → 1654）已实施并验收 —— **P3 缺口复算 = 0**（33 → 0，12/12 处置项落地）**；**P4-1（无头后端骨架 `backend-cli`：新增 `backend` 包 + 手写参数解析 + 无头 logback（冲突 C8）+ `BackendOnceSmokeTest` 7 用例；`ArchitectureTest` 把 `backend` 纳入零-JavaFX 扫描并把访问面上限 116 → 108；测试 914 → 921）· **P4-2a（IPC 核心：`backend/IpcServer` + `IPC.md` 协议 v1〔request/response/event/error 四类帧 + 5 个错误码；stdout 只放协议帧〕+ `--serve` 真服务端 + 契约测试〔IPC 与 in-process 双路径一致〕；测试 921 → 926）· **P4-2b（IPC 批量数据走文件 + 脚本客户端 + 1 万文件压测：`workspacePage` 分页（offset/limit ≤256）、`IpcSession`/`IpcFiles`、`tools/ipc/client.mjs` 脚本客户端、`BackendIpcBulkTest`；测试 926 → 932）· **P4-3（独立 native 无头构建 `NATIVE_TARGET=headless` + 元数据**只做增量后端子集**（既有七份 diff=0）+ **第 17 条不变式「无头构建不得含 JavaFX」**；产物 **2 文件 ≈35.5 MB、零 JavaFX DLL**；测试 932 → 933）· **P4-4（中文目录矩阵：修复中文临时目录限制；`tools/ipc/chinese-path-matrix.mjs` 可复跑矩阵〔6 类中文路径场景 × JVM/native × `--once`/`--serve`(脚本客户端) × zh/en〕+ `path-assertions.mjs` + `CHINESE-PATHS.md`；**64/64 格通过**、120 产物 SHA-256 复核；测试 933 → 936）· **P4-5（扩 `backend-cli` 参数面：`--config <AppConfig.json>`〔命名配置属性名；`FAIL_ON_UNKNOWN_PROPERTIES` 严格校验；显式 flags 覆盖 config；`--serve` 下显式拒绝〕+ **无头侧 42 份产物基线 42/42**〔`BackendConfigBaselineTest`：21 用例 × {zh,en} 经无头路径逐份对照〕；测试 936 → 940）· **P4-13（C2 `{basename}` + C3 启动握手 `hello`/`welcome` + `BackendLauncher`；测试 940 → 945、基线 42 → 44）· P4-14（C4 确认卡跨进程语义：真实确认往返 + 断开即取消 + 30 s 可配置超时；测试 945 → 949）· **P4-15（修「查看预览效果永久停在加载态」+ 区域限定放宽，预览与提取一致；测试 949 → 958；`IMAGE_EXTRACTION_RULES_VERSION` 1 → 2）已实施并验收**；**P4-17（A-4：方案 §14 决策 10/11 的 GUI 侧接线——GUI 启动时拉起随包 `MhtExtractorBackend.exe`、校验握手、用应用内确认卡应答 IPC 确认、断连即取消 + 30 s 超时兜底；dist 随包后端；测试 961 → 964、i18n 547 → 571）已实施并验收**（2026-10-09，见《遗留目标与未完成事项.md》§13）；**V1（可见性回收阶段二首批：探针/测试反射归零，测试访问面 108 → 88，迁移 20/29；剩余 9 条无等价公开观察路径、保留原断言与清单登记，未扩生产 API、未改可见性）· P4-18（三项便宜的未验证项补齐：en 应用日志 CJK=0 / 点击遮挡层不关确认卡 / `>50MB` 确认分支；新增 `LargeFileConfirmationProbe`；单测仍 964+73）已实施并验收**（2026-10-09）；**P4-19（native 元数据缺口 `PreviewCacheMeta$ImageEntry[]`：写缓存时 Jackson 反射实例化数组类；补注册 + native 红/绿控制组 + 重建 dist；测试仍 964+73）已实施并验收**（2026-10-10）；**P4-20（失败也提醒 toast + 状态列 emoji→Ikonli + 后端按选项 B 停用；测试仍 964+73）已实施并验收**（2026-10-10）；**P4-21（前端/后端打包分离：后端 exe 移入 `Backend\`、前端 exe + DLL 留根；新增布局断言脚本与控制组、`BackendExecutable` 单测；测试 964→967）已实施并验收**（2026-10-10）；**P5a-0（目录/数据根机制 + `IpcFiles` 生产化：后端 cwd=发行根、tmpdir=`Backend\temp`、白名单改可选 `-Dmht.allow.roots`、`close()` 不跟随重定向目录；新增 `assert-runtime-layout.ps1` 红/绿控制组；测试 967→973）已实施并验收**（2026-10-11，`7f3212b`）；**（补）headless 元数据 `ExtractWarning` 反射缺口修复 + 「裸 type」体检（纯增量 +20/-0、`audit-metadata-depth.ps1`、真 native 红/绿冒烟 `native-preview-warning-smoke.ps1`）已实施并验收**（`1e37fdc`）；**P5a-P2（端口 ↔ IPC 协议全表 81.7 KB）· P5a-P1（gui→app 类型三分类 + 组装根搬迁设计 72 KB）已完成**（设计输入，在 `.claude-work/`）；**P5a-1a（IPC 协议扩展第一批：8 新方法 + 1 新事件 + 5 契约测试；新增 `IpcWorkspaceCodec`/`DropVm`）已实施并验收**（`0684e59`）；**P5a-1b（第二批：12 新方法 + 12 新事件 + 回调泵（FIFO 背压·单一写者）+ 缩略图按 D13 只传缓存路径与真实格式；测试 978→989）已实施并验收**（`331d784`）；**P5a-1b-r（修 native FFM 缺陷：`IpcWindowsIo.cancel*()` 改 best-effort + 无头元数据 `foreign` 段注册 2 条 downcall + 9 个 JVM 用例 + 可复跑 native 红/绿冒烟；测试 989→998）已实施并验收**（`dcdf4c9`）；**P5a 批次（2026-10-12 起）**：**P5a-1c（参数族/撤销/schema，`6b07ac5`）· P5a-1d（提取通道 + 结果腿，`17c885a`）· P5a-2（`IpcUiport` 客户端，`0dbbfe9`）· P5a-1e（端口增量 + VM 完整化，`a64f097`）· Q10（`targetResolution=explicit`，`2781c31`）· P5a-1f（U2 后端完整参数撤销，`03ca134`）· **P5a-3（接线切换：GUI 成为 IPC 瘦客户端，`434689d`；访问面 88→87、`MainController` 1654→1627）** · **P5a-4（D1 后端不可用流程 + 单次重建（是/否卡、D1a 不重放），`389ff54`；i18n 571→577）** · **P5a-7（10 GiB 台阶提醒跨进程接通 + 后端 WARN/ERROR 转发 D12 + 孤儿清理，`81494bb`；测试 1059→1069）** · **P5a-8（D10a 命名配置端口化 + D10b GuiState/GuiStateManager/SessionStateService 迁 uicore，`fa25d2f`；测试 1069→1078、`MainController` 1627→1612）** · **P5a-8-r（迁包后 native 元数据 3 处注册同步修复，`18a11f2`）** —— 均已实施并验收（本地提交，未推送）；**Gate C 复跑中发现并修复 P5a-3 引入的 native-card-smoke 回归**（探针引用 support/ProbeRuntime.java 而冒烟未带该源）。**剩余待办（2026-10-16 复核）**：**P5 新前端接入（第二客户端）+ 双前端合约测试**（唯一未启动的大目标，方案 §5）；**可见性回收阶段二已收口**（**V1-r**：87 → **85**；Codex 全量分类 + harness 逐条复核 = **A 0 / B 2 / C 85**，**真正能收回 private 的成员 = 0** —— 见台账 §85 与 `.claude-work/V1r-分类与最终方案.md`）；其余为**未验证项** —— **全部登记在《未验证项.md》**（2026-10-16 起单独成文，所有验证/测试工作在该文专门处理）；截图链 JVM-vs-native / native 端到端 GUI / 跨进程日志纯净度已由 **P5d-1 `eccaead`** 关闭（台账 §80）。 |
| **P5b 批次（2026-10-16）** | **6 单元全部 done（本地提交、未推送）**：**P5b-0** 基线复核（验证单元，无提交；`STALE 0/590`+`CANDIDATES 0`+孤儿冒烟绿/teeth红+`accept-unit` 9/0）· **P5b-1 `61c48d7`**（预览缩略图按项驻留，方案 B 有界 LRU：N=20 项/M=64 MiB，新增 `uicore.PreviewRetention`）· **P5b-2 `2cc3e91`**（列表「来源」列 + `uicore.ListColumnWidths`/`gui.PreviewColumns`，i18n +1）· **P5b-3 `8d61dc2`**（文件名/来源列宽拖动 + 表头同步横向滚动 + `GuiState` 像素持久化，新增 `gui.ColumnWidthController`）· **P5b-4 `bb2dde3`**（D16 单实例：`FileChannel` 排他锁 + 应用内提示，i18n +3）· **P5b-5 `7dd08cd`**（`tools/verify` 截图比较器 + 18 项自测）。最终 `accept-unit -Unit p5b-final -Native` **ALL GREEN**；`MainController` 全程 **1612 未增**、访问面 **87 未增**、17 条不变式 17/0、产物基线 44/0。未验证/遗留见《遗留目标与未完成事项.md》§76 与 `.claude-work/P5b-最终覆盖对账.md`。 |
| **P5c 批次（2026-10-16）** | **关闭 3 个 P5b 遗留**（用户指示指挥 Codex 修复；harness 独立验收 + 亲手红控制，只本地提交）：**P5c-1 `4dde40b`**（`tools/ipc/client.mjs` 事件守卫匹配协议：白名单 = 标量/精确 FileRef/扁平 string 映射；帧上限拆为 事件 64 KiB / 响应 256 KiB；裁定 `.claude-work/D-p5c-1-范围裁定.md`；自测 35/0；10k 默认日志 `CLIENT_EXIT=0`）· **P5c-2 `6f50221`**（真 native GUI：`NativeGuiStateProbe` 断言 P5b-3 新增 `nameColumnWidth`/`sourceColumnWidth` 往返 **GUI_STATE_OK**（生产元数据无需修复），teeth = 削弱元数据 -> 运行期 `MissingReflectionRegistrationError`；新增 `tools/native/native-single-instance-smoke.ps1` -> 第二实例 4215 ms 自退/exit 0/`log.app.alreadyRunning`、不同 cwd 控制组两实例均存活）。仍未关闭：截图正式人工基线、真实 >64 MiB 图集、人手拖动、C 盘整盘、QML 文档归属。 |
| **P5d 批次（2026-10-16）** | **补做三项长期未验证**（用户指示与 Codex 协作；harness 全部独立复跑）：**P5d-1 `eccaead`**（仅 `tools/` 新增 9 文件）—— **A 截图链 JVM-vs-native**：JVM×2/native×2 共 6 组比较 + 控制组 + 红控制，该场景**逐字节一致**（4 张 PNG SHA256 全 `F70631D7…`）；**B native 端到端 GUI 冒烟**（`native-gui-e2e-smoke.ps1`：主界面可见/两列默认 50/50/来源列文本/真实导入/预览缩略图非空/生产关窗无残留，11 条 PASS + `-InjectWrongSource` 红）；**C 跨进程日志语言纯净度**（en GUI/后端/转发三路 CJK=0，zh 控制组 11/63/18，独立计数器一致 + `-InjectCjk` 红）。仓库全量 `accept-unit -Unit p5d1 -Native` **ALL GREEN（gate 1098/0 · headless 73/0 · 架构 17/0 · GUI 36/0 · native 0）**。未验证见台账 §80。 · **P5d-2 `a77822a`**（裁决 ① renameGroup 非空前置 + ② MimeParser 缺/未知 CTE 兜底；用户选 **R1「修 + 重定基线」**）：缺/空白 CTE 回退由 `"utf-8"` 改 **RFC 2045 默认 `7bit`**、未知 CTE 经 `decodeTransfer` 降级为原样读字节（`log.mimeparser.07`）；`WorkspaceModel.renameGroup` 加 `requireNonNull`（对齐 `GroupVm`/IPC）。**重定基线**：`text-boundary` 由 `success=0/fail=1` 变 `success=1/fail=0`（no-cte 现为 SUCCESS），并**新增 `text-unparseable`**（全跳过、0 成功）承接 CLI **exit 1** 锚点 → 产物黄金基线 **44 → 46**。全量 `accept-unit -Unit p5d2b -Native` **ALL GREEN（gate 1101/0 · headless 74/0 · 架构 17/0 · GUI 36/0 · native 0）**。 · **P5d-3**（用户三项指示：文档治理 + 可见性回收评估 + FAILED 夹具）：**未验证项独立成文《未验证项.md》**（文档集四份 → **五份**，本地临时文档）；产出 `.claude-work/可见性回收评估-V2V3与V1残留.md`（**结论：V3 大面积是前端内部态、走端口会污染跨前端契约 → 重新划线；可行动路径均不动生产代码 → 不阻塞发版**）；新增夹具 `bad-charset.mht` 使 `text-boundary` = `success=1 fail=1`（**真实异常 → FAILED**，消除 §82.4 的可选加强项）。 |
| **V1-r 批次（2026-10-16）** | **可见性回收全量分类与收口**（用户指示：与 Codex 讨论剩余全部回收项 → 分类 → 开工）。**分类**（Codex 逐条给「文件:行」证据 + harness 逐条复核）：87 条 → **A 值得做 0 / B 简单处理 2 / C 完全不用动 85**。**关键更正**：旧评估「迁同包驱动即可消反射并回收」的前提**不成立** —— **Java private 连同包都不可见**，而残留反射恰恰读取**已 private** 的成员（实测 FileListContextMenu.actions 即 private：FileListContextMenu.java:116），故迁驱动**不产生任何 private 回收**（**真回收 = 0**）。**已做 B 2/2**：① AcceptanceDriver.effectiveForObject（无调用者转发）删除 → 访问面 87 → 86；② SidePanelProbe 改经**同源端口** queries().parameterState(...) 读已确认参数、删反射助手 fieldDeep → 86 → **85**（direct 78 → 77、reflect-dynamic 6 → 5）。**验证**：ArchitectureTest **17/0**（最终态 20:26:46）；**真探针 + 控制组**：改前/改后均 146 passed / 7 failed（同一批 7 条**既有**失败 ⇒ 与本次改动无关），**红控制**反转期望 → 145 passed / 8 failed（新断言仍有牙齿），还原后 SHA256 6C8C22C9F4619441 与绿跑逐字节一致。⚠️ **新发现的既有缺陷**：SidePanelProbe 现 **7 条失败**（step 4 gate order / R3.6 关窗守卫 + clearPreviewCache 反射 InvocationTargetException），**已在同批修复**（见台账 §86）：根因是 **P5a-3 后端口结果/关闭回调靠 `Platform.runLater` 送达，而该探针的步骤跑在 FX 线程上 + `Thread.sleep` 死等 ⇒ FX 队列永不推进**（实测 `ownerClose=4 ms` 但 `finished` 从不送达）；修法是探针侧新增 `pumpFx()`（公开 `Platform.enterNestedEventLoop`）与 `runOffFx()`，并在四处异步点轮询等待 —— **生产代码零改动**。修复后 **连跑 3 次 `SIDE-PANEL PROBE: 153 passed, 0 failed`（exit 0×3）**。 |
| `MainController` | **1607** 行（上限常量 **1612**，只减不增）。P-F 批次再降 5 行（P-F8 前的实测值），P3-B4 的 +8 已裁决记账 |
| 测试 | 默认执行 **1134**（+ `headless-layer-b` **74**；2026-10-19 P-F7 后实测）；架构不变式 **17** 条；产物黄金基线 **46** 份（P4-13 增 `{basename}` 两例；P5d-2 改 `text-boundary` 2 份 + 新增 `text-unparseable` 2 份作为 CLI exit-1 锚点）|
| GUI 自动化 | zh / en **各 37 passed / 0 failed**（P5b-1/P5b-2 各 +1 步）；`tools/probes/` **2026-10-16 全量复扫 20/20 全部 exit=0**（`ContextMenuProbe` **128/0** · `HtmlViewerProbe` **166/0** · `SidePanelProbe` **153/0** · `GuiE2eProbe` PROBE_COMPLETE · `BackendEventsProbe`/`ParamDraftProbe` exit=0）。⚠️ 此前**并非全绿**：20 个里 5 个红（且 `GuiE2eProbe` 连编译都过不去 ⇒ 从未运行过），已在本批修好 —— 见台账 **§86 / §87** |
| i18n | zh_CN / en_US **各 585 键**（两份键集与占位符集合必须一致，P5b 后实测差异 = 0；+`main.source` 与 3 个单实例键）+ **22** 个 `ErrorCode` |
| 日志键 | `log.*` **249 键**（可复跑口径：两份 bundle 的 `^log\.` 行数 = 编号键 243 + 具名键 6〔`log.backend.unstructured`/`cleanupFailed`/`unavailable`、`log.region.warning`、`log.app.alreadyRunning`/`ready`〕）。⚠️ 2026-10-16 复核发现旧值「246」与任何可复现计数都不符（当时已漂移），现按实测重钉；P5d-2 新增 `log.mimeparser.07`；既有键文案逐键不变 |
| native | **2026-10-19 P-F8 实测**：默认 `dynamic`：**23 文件 / 113,674,868 B（≈108.4 MB）**（随包后端在 `Backend\`、前端 exe + DLL 在根）；`static`：**16 文件 ≈138.9 MB**（**零 JavaFX DLL**）；**`NATIVE_TARGET=headless`（P4-3）**：**5 文件 ≈40 MB**（`MhtExtractorBackend.exe`，**零 JavaFX**）。三配方均 `build-native.cmd` exit 0 + `status=OK` + 卡片冒烟 `5 passed, 0 failed`；产物基线 native/JVM 各 **44/0**；⚠️ **两个配方共用同一 dist 目录，测体积前必须先清空 dist**（否则计数被上一配方残留污染） |
| 规模 | 主源集 **243** 个 java 文件（其中 `gui` **36**、`uicore` **29**、`uiport` **29**、`uiclient` **10**、`model` **16**、`engine` **37**、`backend` **17**、`platform` **9**、`i18n` **5**、`core` **11**、`extractor` **7**、`processor` **15**、`output` **3**、`util` **10**、`cache` **6**、`config` **1**）/ 测试源集 **160** 个（2026-10-16 P5b 后实测；P5b 新增 gui **3** / uicore **3** / 测试 **3** —— 缩略图驻留、来源列与列宽、单实例，均落在框架无关层或新协作者类） |

---

## 2. Build & Run

> ⚠️ 本机 **`mvn` 不在 PATH**：用仓库自带的 **`mvnw.cmd`**，并显式指定 JDK（Liberica NIK 25）。

```bash
export JAVA_HOME='<盘>:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3'
MVN='$HOME/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn.cmd'

"$MVN" -q -DskipTests package      # 出 jar
"$MVN" test                        # 全量单测（**不要加 -q**，会吞掉 surefire 摘要）
"$MVN" -q -DskipTests compile      # 快速语法检查
#   🚨 不要加 -Dmaven.compiler.release=21：源码用了 Java 22+ 的未命名变量 `_`，必然编译失败。
#      pom 的 release 是 25。
```

**GUI 自动化 / 演练**（`test.mode`）—— 🚩 **入口在测试源集**（生产启动器 `App` **不含**任何测试分支）：

```bash
java --module-path <jfx-win-jars> --add-modules javafx.controls,javafx.fxml \
     -Dtest.mode=true -Duser.language=zh -Duser.country=CN \
     -cp "target/classes;target/test-classes;<cp.txt>" \
     com.mhtextractor.gui.acceptance.TestMain
# 结果写入 logs/test-results.txt（当前基线：zh 37/0、en 37/0；P-F11 后）
```

三条纪律（都真被坑过）：

1. **跑前删 `logs/test-results.txt`，跑后核对它的 mtime**：否则在断言之前就崩（例如 `App.start()` 抛异常）时，
   你读到的是**上一次**的 34/0。同时确认日志无 `Exception in Application start method`。
2. **locale 由 `logs/gui-state.json` 决定，它覆盖 `-Duser.language`**：要跑 en 就先播种/移走该文件，
   并断言"英文运行日志里中文字符数 = 0"。
3. **不要用 `mvn javafx:run -Dtest.mode=true`**：`-D` 会被 Maven 吃掉，测试根本不会启动。

**UI / 交互探针**（布局截断、侧边栏、查看器、提醒卡片、关窗守卫…）：

```powershell
powershell -ExecutionPolicy Bypass -File tools\probes\run-probe.ps1 -List
powershell -ExecutionPolicy Bypass -File tools\probes\run-probe.ps1 UiProbe -Locale en_US
```

**native EXE**：`build-native.cmd`（默认 `dynamic`）；环境预检 `check-env.cmd`
（**0=就绪 / 20=缺 MSVC / 1=缺其它**）。完整流程、配方差异、验收清单与全部实测坑 → **打包手册**。

---

## 3. Architecture

**前端依赖：`gui → uiport / uiclient / uicore / model / i18n / platform`；后端依赖：`backend → engine → 业务包`。**
`uiclient` 只持有 IPC 传输、子进程与前端运行时，不依赖 `engine` 或 `backend` 实现；
`backend` 复用 `uiclient.BackendIdentity` 的握手身份。`app` 包已取消。
除 JavaFX 视图/外壳外，各层保持零 JavaFX（字节码常量池扫描）。

```
gui          ← JavaFX 视图/控制器
uiclient     ← UiportRuntime / IpcUiport / BackendSession / BackendExecutable /
               BackendLauncher / BackendIdentity，以及 ipc 解码、确认和文件租约
uicore       ← 框架无关的前端投影、参数表、会话持久化与呈现逻辑
uiport       ← 命令/查询/事件/确认协议；零 JavaFX、零 I18n、零业务类型
backend      ← --once / --serve、IPC 服务端和无头日志
  ↓
engine       ← BackendWorkspace / InProcessUiport / WorkspaceModel；导入、参数、
  ↓            校验、预览、提取编排和资源生命周期
业务包        ← core / extractor / processor / output / util / cache / config
model        ← 纯数据与参数字段映射（含 WorkspaceChange / PreviewImageData / ParamSchemaService）
i18n         ← 文案键与渲染
platform     ← 既有操作系统与文本基础设施；前端引用维持冻结的 15 项，不扩面
```

P-F10 移除 `mht.uiport=inprocess` 生产分支：GUI 仅走 IPC；测试/探针可直接构造
`engine.BackendWorkspace` 或 `engine.InProcessUiport`。`UiportRuntime` 的 JVM 启动选项仅
通过命令行字符串启动独立 `BackendCli` 进程，不构造后端业务对象。

### 业务包

| 包 | 职责 |
|---|---|
| `core` | `MimeParser`（MIME 树 → `ParsedMht`；递归上限 100；编码恢复 UTF-8/GBK/GB18030…）· `ContentExtractor`（Jsoup → 纯文本 / 标题）· `HtmlFolderParser`（HTML + 侧车）· `SourceParsers`（按 `SourceKinds` 选解析器）· `ChapterGenerator`（中文 `第一章` / 西文 `Chapter 1`，**0-based**） |
| `extractor` | `BaseExtractor` · `TextExtractor` · `ImageExtractor`（区域限定 / 广告过滤 / CID+Location 去重 / Data URI / 魔数扩展名）· `ImageSource` + `InMemoryImageSource` |
| `processor` | `BatchProcessor`（基类：收集、进度、取消、`ProcessStats`）· `TextBatchProcessor` · `ImageBatchProcessor`（流式/预加载 × 合并/单独；策略组件 `MergedImageOutput` / `SeparateImageOutput` / `PreloadedImageInput` / `ParsedMhtProvider` / `ImageOutputHandler`；**缓存命中即跳过解析与提取**） |
| `output` | `FileWriter`（模板变量 `{filename}`（完整文件名）/ **`{basename}`（经 `SourceKinds` 剥已知扩展名，P4-13 新增）** / `{datetime}` / `{date}` / `{time}`）· `ImageWriter`（`CREATE_NEW` 原子写、唯一名、魔数纠正）· `Archiver`（ZIP/CBZ） |
| `util` | `SourceKinds`（**来源扩展名与侧车后缀唯一入口**）· **P3 起陆续迁出**（见下 `model`/`app`）：`FingerprintEncoder` · `GroupItem` · `ParamScope` · `DropResolver` · `UndoManager` · `MhtImportPlanner` · `OutputUnitPlanner` + `GroupStructure` · `IncrementalImport` · `SelectionState` / `UnitProgressAggregator` · `SanitizeUtils` / `EncodingUtils` / `FileUtils` / `HtmlUtils` / `HtmlExportBuilder` / `ThumbnailPixels` |
| `cache` | **预览缓存（会话级）**：`PreviewCacheKey`（绝对路径+size+mtime+参数指纹+格式版本 → SHA-256 目录名）· `PreviewCacheMeta`（`meta.json` schema）· `PreviewCacheStore`（`.tmp`→改名→`.complete` 原子写；`imageFileFor(meta, index)` 由 meta 反推缓存图片路径）· `PreviewCacheLifecycle`（**四条删除规则集中在此** + 每项目写锁 + 10 GiB 台阶提醒）· `DirectoryImageSource` |
| `config` | （`AppConfig` 已迁 `model`，见下）· `ConfigManager` · `GuiState` / `GuiStateManager`（`logs/gui-state.json`：模式/目录/开关/**locale**） |
| `i18n` | `I18n`（**唯一文案入口**；界面走 `get` 具名占位符 `{name}`，日志走 `msg` 位置占位符 `{0}`，结构化警告走 `render(ExtractWarning)`；缺键返回 `!key!` + `log.error`）· `ErrorCode`（**22** 个，键 `error.<CODE>`）· 两份 bundle（**各 585 键**，`log.*` **249** 条 —— 口径见 §1「日志键」行） |
| `model` | `PreviewItem`（领域数据与 JavaFX 属性已分离）· `ThumbnailData`（持解码后 ARGB `int[]`，不用 `Image`/PNG 字节）· `ExtractWarning`（**结构化警告**：i18n 键 + 具名参数 `Map`，语言中立；E4.1）· **P3 起陆续迁入**：`GroupItem`、`ChangeListener`、`ExtractOutcome`、`ParamScope`（三态目标/生效参数解析）、`AppConfig`（参数数据模型，Jackson POJO）、`FingerprintEncoder`（指纹长度前缀编码唯一口径） |
| `platform` | `BrowserLauncher`（`openHtml` / `openViewSource` + 三级兜底）· `SourceViewPage`（源码视图页生成）· `CdpClient`（CDP 客户端 + 调试实例管理，原生 Socket，native 安全）· `BrowserDetector` · `FileOpener`（**用系统默认程序打开文件**，含 UAC 关闭导致 UWP 文件激活被阻断的环境预判）· `HtmlTextSelection`（选择/复制语义，纯函数）· `HtmlWrapMap`（折行几何 ↔ 逻辑字符偏移，纯函数） |

### engine 与前端运行时的边界（P-F10）

`WorkspaceModel` 独占数据序、分组关系和撤销命令；`BackendWorkspace` 装配后端状态，
`InProcessUiport` 将引擎编排适配为端口。导入、参数、计划、预览、提取服务均位于 `engine`。
`WorkspaceExtractionCoordinator` / `WorkspaceExtractionService` / `InProcessUiport` 同包，
`outputName` 与 `RunChannel.sessionOutputFolder` 继续保持包级访问，不为迁包放宽可见性。
`PreviewService` / `PreviewSession` / `PreviewCacheFactory` 负责缓存执行和资源生命周期。

`uiclient.UiportRuntime` 只装配 IPC 客户端。离线参数字段表复用 `model.ParamSchemaService`；
该类只映射 AppConfig 与 ParamSchemaVm，不访问工作区、文件或引擎。
`model.WorkspaceChange` 和 `PreviewImageData` 是不可变数据载体；文档链接不反向 import engine。

`LayerBTest`（无 JavaFX 执行）+ `StubUiAcceptanceTest` 证明"用非 JavaFX 的桩 UI 走完导入→校验→建组→参数→移除/撤销→提取"，
并与产物基线逐字节一致。

### GUI 层

- **`MainController`（1607 行）** —— 只做 FXML 事件接线、协作者装配、UI 状态呈现；**行数只减不增**（P3-B4 的 +8 已裁决记账；P5a-3/8 继续下沉至 1612）。
  列表单元格渲染/拖拽、预览渲染/加载、分组动画、移除/撤销/窗口生命周期、预解析、信息展示均已外迁。
- **既有协作者（沿用，不要重造平行框架）**：`MainViewRefs` / `MainViewInstaller` / `ListInteractionController` +
  `MainViewActions` / `PreviewListCell` / `PreviewRenderer` / `PreviewLoader` / `SourceViewActions` /
  `GroupCommandController` / `WorkspaceLifecycleController` / `PreparseController` / `WorkspaceInfoPresenter` /
  `ExtractionTaskCoordinator` / `FileListContextMenu` / `ExtractOutcomeStyles` /
  `ThumbnailFxBridge` / `DragGeometry` / `GuiNotifications` / `component.GuiConfirmer`。
- **导入编排已下沉**：`gui.controller.ImportSessionCoordinator` 已于 **P3-B5** 删除，编排由 `app.WorkspaceImportCoordinator` + 端口命令（`importPaths`/`applyImportPlan`）承担；gui 仅保留 `component.GuiConfirmer`（确认往返实现）。
- **参数 UI**：右侧非模态侧边栏（`ParamSidePanel` / `ParamFormPane` / `ParamPanelActions` / `ParamClipboard` /
  `ParamTargetBar` + `ParamTargetText`）。**模态参数窗口已彻底删除，不得以弹窗作为兜底路径。**
- **HTML 源码查看器**：`HtmlSourceViewer` + `HtmlFindIndex` + `platform.HtmlTextSelection` + `platform.HtmlWrapMap`
  （软换行；行号每逻辑行一次并与折行对齐；选择/查找/复制共用同一套逻辑偏移）。**不引入 `WebView`**（**仅约束 JavaFX 前端**；换框架后按新框架实际评估 —— 见《UI可切换重构方案》§14 决策 16），不另造 tokenizer。
- **提醒**：`gui.component.NotificationHost` 是**应用内提醒唯一入口**（生产代码**零模态 `Alert`**）。
- **日志进界面**：`LogTextArea` + `TextAreaAppender`（Logback consumer）。
- `MainView.fxml` / `ParamDialog.fxml` 文案全部是 `%key`（`loader.setResources(I18n.bundle())`），**不得出现中文字面量**。

### 架构红线（`ArchitectureTest` 的 17 条不变式）

| 不变式 | 含义 |
|---|---|
| `businessPackagesHaveNoUiFrameworkDependency` | 业务包 + `engine` + `uiclient` + `platform` + `model` **不得依赖 `javafx.*`**（字节码常量池口径，带白名单腐化检测） |
| `applicationLayerDoesNotDependOnViewOrShell` | `uiclient` / `engine` 不得 import `gui` / `logging` / `App` |
| `mainControllerIsShrinking` | `MainController` 行数（实测 **1607**）≤ 上限 **1612**。P3-B4 的 +8 已裁决接受；**此后只减不增**（P5a-3/8 累计 −42） |
| `reflectionAgainstMainControllerIsZero` | 测试对 `MainController` 私有成员的**反射 = 0**（改用测试源集的同包 acceptance driver） |
| `testSupportSurfaceDoesNotGrow` | `/* test-support */` 标记 = **0**。⚠️ 该计数**只覆盖 `MainController` 文本** |
| `headlessTestPackagesHaveNoJavaFx` | 无头测试包源码不得出现 UI 框架字面量 |
| `businessLayerI18nUsageDoesNotGrow` | 业务层 `I18n.` 引用 ≤ 基线 **52**（实测 P4-1 后 **46**；P3-3b-20 后曾为 43，P4-1 的 `backend` 3 处计入；`MhtImportPlanner.Plan.summary` 随类迁出业务包扫描范围，故 45 → 43；剥掉 `log.*` 整段后再计数）。**P2-2b 起扫描范围剔除 `uicore`/`uiport`**（前端共用逻辑/端口层，不算业务层） |
| `noNewThirdPartyDependencies` | 不得新增第三方依赖（保护 native 打包） |
| `guiLayerDoesNotInvokeCacheRulesDirectly` | GUI 不得直接调预览缓存的删除规则（须经 `WorkspaceParamService`） |
| `guiDoesNotMutateDataOrderDirectly` | GUI 不得直接/间接改 workspace 数据序（须经 `engine.WorkspaceModel`）；实测 **0 / 0** |
| `testAccessSurfaceIsFrozen` | 测试访问面冻结：`src/test/resources/test-access-surface.tsv` **85** 条（direct **77** / reflect 3 / reflect-dynamic **5**；**V1-r（2026-10-16）删 2 条**〔`MainController.effectiveForObject` 的无调用者驱动转发、`SidePanelProbe.fieldDeep` 改同源端口查询〕——实测原分布是 **78**/3/6＝87（旧文写的 79/3/6 是漂移）；**P5a-3 接线切换删 1 条**（组装箱搬迁后成员消失）；S2 曾一次性上调至 124，P2-1 删 5 条 HtmlFindIndex，P3-B1 删 `MainController.ITEM_VIEW`，P3-B2 删 2，P3-B5 删 2，P3-B6 删 6（`ExtractionTaskCoordinator` 下沉接入点）——**均因下沉后成员消失**，且不变式同时校验「发现 ⊆ 清单」与「无陈旧条目」，凭空删行必然失败）**只减不增**；发现集合 ⊆ 清单、清单无陈旧条目、`com.mhtextractor.gui` 同包驱动必须登记。扫描器 `baseline.TestAccessSurface`（字节码成员库 + 驱动类常量池引用；不用反射 API） |
| `allowlistOnlyTargetsBusinessPackages` · `baselineHarnessIsPresent` | 防白名单滥用 / 基线与夹具在位 |
| `uiportHasNoJavaFxDependency` | **P0 新增 / P-F10 扩展**：`uiport` 与 `uiclient` 字节码常量池零 `javafx/`（字节码扫描）。**P3-2 起包已建立，实测 47 类 / 0 违规**（P3-B1…B6 新增 `OutputUnitVm`/`ParamSchemaVm`/`PreparseResult`/`TargetId`/`ImportPlanVm`/`ExtractionChannelEvent` 族等）；基线违规数 **0**，扫描器 `baseline.UiportSignature` |
| `guiBusinessStateDoesNotGrow` | **P0 新增**：`gui` 层业务状态持有/构造点（`AppConfig` / `Map<String,AppConfig>` 字段 + 缓存/剪贴板/撤销载荷构造点（`new UndoManager.Record(...)` 含 `<>`））**只减不增**；基线 **0**（P1-3 后 a=b=c=d=0；P0 时 9 → P1-1 7 → P1-2 3 → P1-3 0，已提前达 P3 目标并保持只减不增）。扫描器 `baseline.GuiCoupling` |
| `guiDirectBusinessPackageUsageDoesNotGrow` | **P0 新增**：`gui` 源码直连业务包 distinct (包, 类型) 数 **只减不增**；基线 **0**（业务 import 0；P0 35 → … → P3-3b-24 1 → P3-3b-25 **0 归零**），**已达 P3 目标**。扫描器 `baseline.GuiCoupling` |
| `headlessNativeMetadataHasNoJavaFx` | **P4-3 新增（第 17 条）**：**无头 native 元数据零 JavaFX 条目**（解码 JSON + properties 文本，匹配 `javafx/` 与 `javafx.`）。后端子集在 `tools/native/headless/reachability-metadata.json`，由 `build-native.cmd` 的 `NATIVE_TARGET=headless` 使用；构建层另有 `tools/native/assert-headless-metadata.ps1` 独立断言（不满足则非零退出）。**既有七份元数据一字不动**（C-4 红线）。**唯一授权例外（P5a-8-r）**：P5a-8 把 `GuiState`/`GuiStateManager` 迁到 `uicore` 后，两份元数据里 3 处旧 `config.*` 注册必须同步为 `uicore.*`（否则前端 native 读写 `logs/gui-state.json` 会缺注册），且**只允许这 3 处**（`git diff` 仅 3 行）；该例外的红/绿 native 控制见 `tools/native/native-gui-state-smoke.ps1`。 |

P-F10 元数据授权范围：七份 GUI 元数据及 headless 子集内的旧类名随实际迁包同步，
方法/字段注册不得丢失；headless 中既有失效的 `sun.text.resources.cldr.FormatData_en_US` 注册删除。
本次七份 GUI 元数据无旧 app/launcher/identity 注册，因而保持逐字节不变。
前端闭包冻结 `FROZEN_MAX=0`；platform 沿用方案的保留边界，`PENDING_MAX=15`，不扩大扫描豁免。
P-F6 产物闸门：`tools/verify/assert-gui-mirror-clean.ps1 -DistDir <dist>` 扫描 GUI 原始字节，
业务包/jsoup 任一命中即失败；对同批 `-Exe Backend\MhtExtractorBackend.exe -ExpectBusiness`
必须通过（普通模式必须失败）。`build-native.cmd` 的 GUI dist 步骤已接入前两项，失败即中止构建。
可复跑红控制：`node tools/verify/gui-mirror-clean-controls.mjs --dist <dist> --out <新证据目录>`。
字节标记不能替代可达性证明；压缩、字符串裁剪或改名可能漏检，非可达类名字面量也可能误报。

**其余硬约束**：主源集不出现 `java.awt`；不引入 `WebView`（**仅 JavaFX 前端生效**，换框架后重新评估）；不新增事件总线 / DI 容器 / 通用工作流引擎。

---

## 4. 关键约定

### 数据所有权（最容易"静默分叉、不报错"的地方）
- `WorkspaceModel` **独占**数据序、分组关系、撤销命令；GUI 的 `items` 只是 `topLevelView()` 的**只读投影**
  （唯一写操作 `clear()` 转发为模型命令）。
- 显示序由 `gui.WorkspaceFxAdapter` 独占维护；投影时机由调用方显式给出（不要在动画流程中重复投影）。
- 一个用户动作只能有**一个**业务命令入口；不要在别处复制去重、分组命名、缓存失效或输出规划规则。

### 国际化
- **`I18n.get(key, k1, v1, …)`** 界面/产物（具名占位符）· **`I18n.msg(key, args…)`** 日志（位置占位符 `{0}`，
  实参先 `String.valueOf`，`null` 渲染成 `null`）。两种写法**不要混用**。
- 缺键 → `!key!` + `log.error`（不返回 null）；`I18nAcceptanceTest` 断言活跃键渲染后不含 `!`。
- **禁止** `ResourceBundle.getBundle`；**禁止** FXML 写中文字面量；**持久化字段名不国际化**。
- 测试/演练代码里的中文分两类：**会进用户可见处的**（夹具名、日志、预览文案）必须走 `test.*` 键；
  **只落 `logs/test-results.txt` 的断言消息**保持中文。
- 默认输出名：中文 locale 保留既有行为（`组N` / `输出文件`），仅 `en_US` 用英文（`Group N` / `Output Files`）。

### 预览缓存
- 位置 `<java.io.tmpdir>/mht-extractor-preview/`（会话级，退出清空）；目录 `<64位SHA256>/{meta.json, images/, .complete}`。
- 命中四要素：绝对路径 + size + mtime + `AppConfig.imageExtractionFingerprint()` + `FORMAT_VERSION`。
- **四条删除规则**集中在 `PreviewCacheLifecycle`（参数变化 / 提取完成 / 项目移除 / 退出清空），不得散落。
- **改 `PreviewCacheMeta` 结构语义必须递增 `FORMAT_VERSION`**（递增即让全部旧缓存失效）。
- 单击缩略图查看：`PreviewCacheStore.imageFileFor(meta, index)` 反推路径 → `platform.FileOpener`（**非 AWT**）：
  ① 系统默认查看器 → ② 「照片」协议兜底 → ③ 如实报失败（**不谎报"已打开"**）。
- 🚨 这条功能**曾在一次重构中被静默抹掉**（编译通过、测试全绿、三轮都没发现）。
  **动缩略图渲染 / `ThumbnailData` 之前先把这条读完。**

### 提醒与关窗（`NotificationHost`）
- 三层语义：**错误** = 居中 `ModalPane` 必须点确定 / **警告·信息** = 右上 toast / **确认** = 居中卡片 + **回调**
  / **等待卡片** = 零可交互控件、后台干完自动收（关窗前清缓存用）。
- 🚨 **不要 `showAndWait`**（`ModalPane` 只有非阻塞 `show`/`hide`）；"确认后继续"写成回调。
- 🚨 通知里的按钮**不要 `setDefaultButton(true)`**（`defaultButton` 是**场景级**的，会劫持全窗口 ENTER）。
- 🚨 放进 `ModalPane` 的内容必须 `setMaxHeight(Region.USE_PREF_SIZE)`。
- 🚨 `showModal` 的顺序写死不能改：`applyCss()` → `setContent(node)` → `show(node)`（否则"display=true 但屏幕上什么都没有"）。
- 🚨 **关窗路径必须保住两条**：① 有未答确认卡 → 不放行；② 卡片被顶替 → 按"取消"交付决定。
  否则"回调永不触发 → 非 daemon 提取线程永久阻塞 → **窗口没了但进程还在**"。
- 📄 **「零模态 `Alert`」≠「零表单窗口」**（E5.2）：`ParamPanelActions.saveNamedConfig` 的
  `TextInputDialog.showAndWait()` 与 `JavaFxUtil.showDirectoryChooser` 是**有意保留的表单语义**（输入/选目录），
  不是漏网；「提醒一律走应用内卡片」指的是错误/警告/确认/等待四类**提醒**，不是表单。

### 界面布局（英文文案更长）
多按钮行用 **`FlowPane`**（宽度不足即换行，按钮恒以 pref 绘制）；固定列宽的标签列**不要写死 `prefWidth`**；
英文明显更长的短行元素加 `minWidth="-Infinity"`。改完用 `UiProbe`（四档窗口 × zh/en）复验截断数 = 0。

---

## 5. 环境陷阱（本机，动手前先读）

- **会话权限必须 `danger-full-access`**：受限令牌下所有子进程以 `0xC0000142`（`STATUS_DLL_INIT_FAILED`）失败。
- **本机没有 pwsh 7**：`pwsh -Command` 实际由 **Windows PowerShell 5.1** 执行。
- **编码**：`Get-Content` 不带 `-Encoding` 会按 ANSI(GBK) 读 UTF-8，**吞掉"以中文结尾那一行"的换行**（数行数会少）。
  **读源码/数行一律显式 `-Encoding UTF8`**（或直接读字节）。含中文的 **`.ps1` 必须带 UTF-8 BOM**；
  Node 读的**数据文件不要 BOM**；`.ps1` 用 CRLF（LF-only 会让 PS 5.1 静默丢哈希表键）。
- 🚨 **禁止用 PowerShell 做文本替换改文档**（反引号是转义符，会把整篇改坏）。改内容用编辑器。
  文档被改坏时用开发区里的 `tools/recovery/recover-doc.js`（从 DSH transcript 切帧解压恢复）+ `verify-recovery.js` 核对（**该工具属本地开发区，不随发布**）。
- **`-Dfoo=bar` 会被 PowerShell 解析坏**（`ClassNotFoundException: /mode=true`）：写成 `.cmd` 或用参数数组 `@(...)`。
  DSH 传给 pwsh 的 **stdin 是空的**；中文走文件或 argv 是安全的。不要给自动变量 `$args` 赋值。
- **`.cmd` 的 `:label` 与函数体之间不能插任何东西**（`call :label` 会落空滑进下一个子程序 → 现象是"exit=0 却什么都没执行"）。
- **`logs/gui-state.json` 的 `locale` 覆盖 `-Duser.language`**；**`logs/test-results.txt` 必须核对 mtime**。
- 🚨 **本机 UAC 关闭（`EnableLUA=0`）**：会话全为高完整性 ⇒ Windows **静默阻断 UWP 的"文件激活"**
  （进程起来但无窗口、**退出码仍 0**，资源管理器里双击图片也打不开）。凡"用系统默认程序打开文件"一律走 `platform.FileOpener`。
- 🚨 **运行时/测试产物一律放 H 盘（2026-10-05 起）**：本项目产物放 `<盘>:\TEST\mht\`、临时放 `<盘>:\TEST\temp\`；跑 GUI / native / 探针 / 黑盒前设 **`-Djava.io.tmpdir=<盘>:\TEST\temp`**（否则预览缓存 `mht-extractor-preview`、浏览器查看页 `mht-extractor-view` 会写进 `%LOCALAPPDATA%\Temp`）。**不要再把生成数据塞到 C 盘**，验收时断言 C 盘无新增生成数据。宿主目录 `.dsh` / `.agents-anywhere` / `.m2` / `.openjfx` 已是指向 `<盘>:\TEST\*` 的 **junction**（旧 C 路径仍可用，新脚本请用 H 路径）；详见 `<盘>:\TEST\MIGRATION-BRIEF.md`。
- **任何 `mvn clean` 不得与 GUI 自动化 / native 构建并发**（会互删 `target/`）。
- **不要整文件读取 `MainController.java`**（1607 行）：`grep -n` 定位 + 区间读。
  **文档里的行号会漂移**，引用前先复核。
- **git 身份未配置**：提交需内联 `git -c user.name=… -c user.email=… commit`（不要改用户环境）。
- `.gitignore` 的 `/config/`、`/logs/`、`/temp/`、`/输出文件/` 已锚定仓库根，**不要去掉前导 `/`**
  （否则会误伤 `src/test/java/com/mhtextractor/config/`，新测试文件被静默忽略）。
- `.claude-work/` 是**本地草稿区（gitignored、不随仓库分发）**；`tools/` 才是分发件 —— 两者不要混。

---

## 6. 验证纪律（本项目最重要的工程约定）

**执行方（人或 agent）的自述不能作为验收依据。** 每条结论都要用**不同手段**独立复验。已验证有效的手段：

| 手段 | 曾发现的问题 |
|---|---|
| 直接聚合 `target/surefire-reports/*.txt` 算测试数（不读执行方日志） | 多次校正基线 |
| 独立 diff / SHA-256 比对（含 `git diff --no-index`） | 证明行为无字节偏差 |
| 自写探针直连生产路径 | 证明缓存 schema 与命中判定正确 |
| 方法/字段级存量对账（元数据修改前后） | 证明去重没丢条目 |
| 字节码常量池扫描（而非 grep import） | 发现全限定名引用可绕过 import 扫描 |
| 跑 GUI 前先删结果文件、跑完核对 **mtime** | 被"上一次的 34/0"骗过一次 |
| 给强断言配**控制组** | 发现两条断言是假通过 |
| **会自己消失的 UI**：同帧断言或用闸门卡住后台任务 | 曾把正确实现误判成 FAIL |

**native 卡片冒烟：三处叠加缺陷全部已修（2026-10-08，`aea2dd5` + `c67159a`）——「static 冒烟一律挂死」的旧说法作废**

① **脚本读/超时缺陷（已修，P4-7 `aea2dd5`）**：旧 `native-card-smoke.ps1` 用顺序 `StandardOutput.ReadToEnd()` + `StandardError.ReadToEnd()` 再 `WaitForExit(timeout)` → **经典 stdio 死锁**，`-TimeoutSec` 对读管道**无效**，现象是 `[smoke] running probe ...` 后长时间零输出（曾挂 16 分钟）。**修法**：两路输出**重定向到文件**、立即限时等待、超时 `taskkill /PID … /T /F` + 返回 1、退出码用 Win32 `GetExitCodeProcess` 读取（本机 `$proc.ExitCode` 恒为空）。**harness 实测**：修后 static 冒烟 **30 s 内返回 exit=1**（不再假挂）。

② **`-OutDir` 从不清 DLL（已修 P4-8）**：冒烟脚本把 `-JfxDlls\*.dll` 拷进 `-OutDir`，但**从不清理 `-OutDir` 里已有的 DLL**；而 `build-native.cmd` 集成调用固定用持久目录 `.claude-work\native-card-smoke`。→ **dynamic 冒烟跑过之后再来 static**，残留的 7 个 JavaFX DLL 被加载 → `WinApplication._init` **段错误**（`0xC0000005`，`SegfaultHandler`） → **static 端到端 `build-native.cmd` FAILED（退出码 1）**。harness 已复现（`smoke-output.log.err` 里有段错误栈），且**实施方只在干净目录下验过**，其「static 通过」不覆盖集成路径。**已修（P4-8）**：构建前清 `-OutDir` 的 `*.dll`，再由 native-image 重新生成（`-SkipNativeBuild` 语义保留；harness 用**哨兵 DLL** 验证清理确实执行）。⚠️ **但它单独不够** —— 见 ③。

③ **真根因（P4-9 `c67159a`，已修并由 harness 复验）**：`build-native.cmd:236` 执行 `set "PATH=%BUILD_JAVA_HOME%\bin;%PATH%"`，而 **NIK JDK 的 `bin` 里含全部 7 个 JavaFX 原生 DLL**（`glass`/`prism_common`/`prism_d3d`/`prism_sw`/`decora_sse`/`javafx_font`/`javafx_iio`）→ **static 镜像运行时从 PATH 命中它们** → 与静态链接的 JavaFX 冲突 → `WinApplication._init` **段错误 `0xC0000005`** → `[smoke] FAILED` → static 集成构建 `exit 1`。**控制组**：只给 standalone 命令前置同一行 PATH，即**从 PASS 变 FAIL**。**修法**：static 配方的冒烟**子进程**重建 PATH 并剔除 `BUILD_JAVA_HOME\bin`/`GRAALVM_HOME\bin`（**dynamic 原样保留**，它本来就依赖这些 DLL）；顺带把误导性的 `Usual cause: missing JavaFX effect-peer reflection metadata` 改成指向 `smoke-output.log/.err`。**harness 复验**：集成 static 带冒烟 → `exit 0` + `[STEP SMOKE] END exit=0` + `[smoke] RESULTS: 5 passed, 0 failed`；集成 dynamic → `exit 0`、dist 22 文件/71.8 MB。

另：`NativeBaselineProbe` 的工作根必须用**仓库内相对路径**（绝对 `<盘>:\...` 会 `AccessDeniedException`）；且**两个配方共用同一 dist 目录**——`NATIVE_RECIPE` 漏设或 dist 未清空都会让产物计数被污染。

**2026-10-08 收口轮（P4-10/11/12，提交 `074477f`）——四条口径与两条重要事实**

1. 🚨 **`testAccessSurfaceIsFrozen` 的扫描范围包含 `tools/probes/*.java`**：在**探针**里加反射（如 `getDeclaredField`/`setAccessible`）同样会算成**清单外新增访问点**而变红（P4-12 实测：ESC 观测曾因此把第 13 条打红）。→ **探针里尽量只用公开 API**；真需要动态访问就必须进冻结清单（且清单**只减不增**，得先去掉别的条）。
2. 🚨 **宿主命令行码页边界（cp936）**：路径里含**非 ANSI 码页字符**（韩文 `한글`、非 BMP emoji `😀`）时，**「命令行 → JVM argv」会把它们压成 `?`** → `java.nio.file.InvalidPathException: Illegal char <?>`（`CliArgs.parse` / `IpcFiles` 处崩）。**日文假名/汉字/全角/中文都在 cp936 内，故正常**。**控制组**：**绕过 node**、直接用 PowerShell 数组参数调 `BackendCli` **同样复现** → 是**宿主限制**，不是产品缺陷、也不是 node 的锅；但**所有 CLI 用户都会遇到**（GUI 走文件对话框不经 argv，不受影响）。矩阵把它归类为 **`LIMITATION(expected) reason=HOST_CP936_ARGV`**，不再当作 PASS/FAIL。缓解方向（未实现）：让 `--config` 这类 **UTF-8 文件**也能携带输入/输出路径，或支持 `@argfile`。
3. **C 盘「零新增」的正确口径 = 项目产物**：`%LOCALAPPDATA%\Temp` 下 **`mht-extractor-*` 新增 = 0**（`tools/ipc/project-temp-audit.mjs`，三态 PASS/FAIL/UNVERIFIED）。**整盘零新增在活主机上不可达**（DSH 会话快照 `dsh-workspace-changes-*`、VS 后台日志 `dd_BackgroundDownload_*` 都会写 C 盘）——脚本已改成分别计数并**显式列出未验证范围**。
4. **ESC 观测（`NotificationAcceptance`）**：默认改为 **`scroll-pane` 公开事件路由**（`Event.fireEvent(ModalPane 的直接 ScrollPane 子节点, KeyEvent)`），**零反射**；zh_CN / en_US 均 **`failures=0 unverified=0`**（非持久 `consumed=true` 关闭卡片 / 持久 `consumed=false` 保持打开，同一 skin 实例对照保留）。

**收口轮实测（harness 亲手）**：矩阵 16 格 = **8 PASS + 8 LIMITATION(expected)**、CG-3 **RED=FAIL GREEN=PASS**（红日志含 `TEXT_EXACT_GUARD`）；内存文本守门 字面=PASS/注入`?`=FAIL/撤销=PASS；C 盘 `projectStatus=PASS`；**en_US 全 14 探针 exit=0**；**无头 exe 重建后 native 42/42**；**无头 native IPC（`--serve`）CLIENT_EXIT=0 + TEXT_EXACT_MATCH + stderr 0 字节**；**static 构建 exit 0 + `[STEP SMOKE] exit=0` + 15 文件/99.4 MB + 0 个 JavaFX DLL**，**静态版 `BaselineProbeStatic`：`NATIVE-BASELINE: 42 passed, 0 failed`**（⚠️ 该次运行发生时基线总数为 **42**；**2026-10-09 P4-16 后已按 44 重跑并全绿**：`NATIVE-BASELINE: 44 passed, 0 failed`，见台账 §11）；**P4-7 超时杀树：`taskkill` SUCCESS + 无残留进程**。

**迁移/改包名时必须全仓库检索调用方（含 `tools/probes/`）**：P3-3b-19 把 `util.GroupItem` 迁到 `model` 后，`src` 内编译全绿，但 `tools/probes/ContextMenuProbe`/`ParamDraftProbe` 仍 import 旧包 → 两个探针**编译失败**（exit=1，易被误当功能回归）。**教训**：包迁移/签名变更后，除 `mvn test` 外必须 `grep -rn <旧全限定名> .`（覆盖 `tools/`）并**复跑受影响探针**。

**`HtmlViewerProbe` step5 hscroll 时序竞态：已定位并修复（2026-10-08，P4-6 `a815d66`）**。
症状：`FAIL step5 hscroll: every selection rect stays inside the viewport (rects=2, inside=false)`（**只有这一条**会抖），抖动率曾达 **25%~50%**（P4-4 轮 1/4、P4-5 轮 2/4）。
**定性 = 探针时序（非产品缺陷）**：受控复现显示 631 px 视口下 cell 仍用旧 `[0,96)` 折行区间，首个选中矩形越界 **83.2 px**，产品排队刷新后几何恢复正常。
**修法**：新增异步步 `HSCROLL_READY`，在还原窗口尺寸后**等待渲染网格与加宽前捕获的网格一致**再断言（注释明写 *Do not wait on selection rectangles: those remain assertions*）；并修掉「轮询在 `cancel()` 后仍排队于 FX 线程 → **重复推进步骤**」的真 bug。
**断言 `rectsInside && rects >= 1` 与容差未变**；修前 62.5%(5/8) → 修后 **harness 亲手连跑 6 次 6× `166/0`**。→ **纪律保留：探针报红仍先复跑 ≥3 次再下结论**。

**P4-15（2026-10-09）：预览"永久加载态"根因与修复（harness 独立复现 + 控制组）**
- **根因**：加载态结束后 **`imageHintLabel` 无人复位**。`PreviewLoader` 在 `PreviewLoadStateChanged(active=false)` 时只调 `updateProgressStatus(...)`，而该实现（`PreparseController`）**只刷新预解析进度、不碰图片提示**；同时 app 层有两条"结束却不发终态事件"的路径（取消竞态 / `stale`）。
- **实测控制组**（自建 `PreviewHangProbe`，`.claude-work/verify/`）：修复前 **B（取消）/D（stale）`terminalEvent=false`**；修复后 **A–E 全部 `true`**、`loadingPath` 全为 null。证据：`<盘>:\TEST\mht\p4-15-prefix-control.txt` 与 `p4-15-postfix-probe.txt`。
- **修复（双保险）**：GUI 在 `active=false` **与** `PreviewLoaded` 都调 `updateContentArea(...)`（标签必然复位）；app 层补齐终态事件（结构性）。

**P4-15：区域限定放宽口径（用户拍板 D1–D5，预览与提取一致）**
- 新增 `extractor/ImageRegion`（预览/提取共用判定）：空/空白=无效；取"开始之后第一个结束"；**结束在开始之前 ⇒ 两者都无效（R2）**；仅开始→R3（至文末）；仅结束→R4（从文首）；都无效→R2（全量）。**排序不动**（D3）。
- **R5 确认卡只存在于预览**；**提取路径静默全量**并在**汇总 + WARN 日志 + `SUCCESS_WARNED` 标记**中体现（不阻塞）。实测：`done: warn=2`、`WARN … 未设置正确区域限定参数，已全量解析`。
- 🔴 **纪律**：改"提取出哪些图片"的规则必须 +1 `IMAGE_EXTRACTION_RULES_VERSION`（本次 1→2，会让全部旧预览缓存失效）；CLI `--config` 的**持久化属性名**是 `imageRegionExtractEnabled`（**不是** Java 字段名 `enableRegionExtraction`，用错则 exit 2）。
- ⚠️ **已知边界**：规则版本升级后，`<tmpdir>\mht-extractor-preview` 里**旧版缓存目录**会让清理路径抛异常（GUI 验收首次因此红一次，清空后连跑 2 次全绿）——低危遗留项，见台账 §10.5。

**P4-16（2026-10-09）两条工具链教训**
- 🚨 **`tools/probes/*.java` 不得 import 测试源集的类**（如 `AcceptanceDriver`）：`run-probe.ps1` 只用 `target\classes`+依赖 编译探针（`:116`/`:135`），**不含 `target\test-classes`**，否则 `compile failed, exit=1`（P4-15 的 `PreviewLoadingProbe` 即因此假红）。探针要与主源集/公开 API 打交道。
- 🚨 **`.claude-work/build-baseline-native.cmd` 需要 `target/cp.txt`**：`mvn clean` 会删它，而 `run-probe.ps1` 不总会重建 → 镜像缺依赖，运行时报 `NoClassDefFoundError: org.slf4j.LoggerFactory`（假红）。**作前先跑**：`mvnw -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt`。
- **缓存容错（P4-16）**：`PreviewCacheStore.listKeys()` 对"缺 `meta.json`/JSON 非法/目录名与 meta 不匹配"的目录 **warn 跳过**（键 `log.previewcachestore.04`），可识别的旧格式仍列出；**根目录不存在时返回空**（非抛异常）。规则版本升级后旧缓存残留不再让清理路径炸掉。

**P4-17（A-4，2026-10-09）：GUI 拉起后端 + GUI 应答确认（方案 §14 决策 10/11 的前端接线）**
- **交付**：`app.BackendExecutable`（按"GUI exe 同目录 → jar 同目录 → cwd"定位随包后端，支持 `-Dmht.backend.executable`）+ `app.BackendSession`（异步启动 `--serve`、握手校验、`UNAVAILABLE` 降级）+ `gui.component.BackendConnectionPresenter`（连接状态呈现 + 确认应答桥）；`GuiConfirmer`/`NotificationHost` 承接"后端发起的确认"；`build-native.cmd` 把 `MhtExtractorBackend.exe` 复制进 dist（`BACKEND_DIST_DIR`，默认 `<盘>:\TEST\mht\p4-3-headless-dist`；缺失只警告不失败）。
- **握手**：复用 `BackendLauncher`/`BackendIdentity`，校验协议版本 + 版本号 + **buildId** + **五项能力集**（workspace / setChecked / workspacePage / bulkFiles / confirmationRoundTrip）+ 确认标志；任一不符 → `UNAVAILABLE` 并提示（实测原文含 `buildId mismatch`、`Protocol version mismatch`、`handshake timed out after PT1S`、`welcome lacks required capabilities`）。
- **降级（关键）**：**后端缺失/失败时 GUI 照常可用**（列表/预览/提取仍走进程内 `app`），只是显示"后端不可用"——真实 `App.start` 缺后端与真实 native 缺后端两种情形都验过。
- **确认与超时**：后端经 IPC 发确认请求 → 应用内确认卡（键 + 具名参数）→ 回送 `confirmAnswer`；**卡片被顶替按"取消"交付**，旧句柄关不掉新卡；**断开即取消**、后端 30 s 到期（实测 `server-30s-timeout elapsedMs=29935`）与前端本地期限 +250 ms 兜底都会**复位状态**且不晾住 GUI。
- **本批不做**：GUI 业务**未**改走 IPC（仍走进程内 `app`），这是 P5/后续的范围。
- ⛔ **2026-10-10 起按选项 B 停用（P4-20）**：P5 接线前 GUI **不再启动随包后端**（`App` 不再调用 `BackendConnectionPresenter.install`，后端状态标签隐藏）。原因：后端仍带开发期约束（`IpcFiles` 要求 `java.io.tmpdir` 在 `<盘>:/TEST/temp` 下、输入/输出白名单 `<盘>:/TEST/mht`），真实环境必然失败并产生"后端不可用"噪音。相关类/探针/脚本保留供 P5 生产化接线。
- **harness 独立验收**：全量 **964/0/0 + headless 73/0**（`MVN_EXIT=0`）、`ArchitectureTest` 17/0、**GUI zh/en 34/0**、**16/16 探针 exit=0**；native：JavaFX dynamic `exit 0` + dist **23 文件/107.5 MB（含后端 exe）** + 卡片冒烟 `exit 0` + `状态 OK`、headless `exit 0`、**无头 native 44/44**、**NIK static 基线条 `NATIVE-BASELINE: 44 passed, 0 failed`**；i18n **571/571（差异 0）**、`log.*` **243**。
- 🚨 **教训（P4-16 的延伸）**：`build-native.cmd` 内部会跑 `mvn clean`，**它会删掉 `target/cp.txt`** → 因此必须在**紧接** `build-baseline-native.cmd` **之前**重建 `cp.txt`，否则基线条镜像缺依赖 → `NoClassDefFoundError: org.slf4j.LoggerFactory`（**第二次踩到**，仍是假红）。
- 🔧 **harness 紧急修正（已披露）**：本批把 `tools/native/native-card-smoke.ps1` 的默认 `-OutDir` 与 `build-native.cmd` 的 `NATIVE_SMOKE_DIR` **硬编码成 `<盘>:\TEST\mht\native-card-smoke`** → ① 违反打包手册"任何 Windows 设备可打包"的承诺；② 破坏 `§6②`（P4-8）记录的"集成调用固定用持久目录 `.claude-work\native-card-smoke`"不变量。**修法**：恢复"**环境变量优先、否则仓库内 `.claude-work\native-card-smoke`**"；保留该批的两处好改动（探针 classes 落到 `-OutDir\probe-classes`、冒烟子进程用 `-WindowStyle Hidden`），并重建验证（`exit 0` + `[STEP SMOKE] END exit=0`）。

**P4-18 / V1（2026-10-09 会话）：三项便宜未验证项已补 + 可见性回收阶段二首批**
- **日志级语言纯净度（已补，此前只验过"屏上中文=0"）**：en 运行的应用日志 `logs/mht-extractor.log` **CJK=0**（43886 B），zh 控制组 **4278**（57729 B）。**只统计应用日志**；`logs/test-results*.txt` 的中文断言消息按 §4 不计入。统计口径与注入控制组（clean=0 / injected=2 / CLI red exit=1）见 `.claude-work/p4-18-report.md`；可复跑入口 `node .claude-work/p4-18.mjs count <log>`。
- **点击遮挡层不关确认卡（已补）**：`NotificationAcceptance` 用公开 `Event.fireEvent` 向 AtlantaFX `ModalPane` 的**唯一直接 `ScrollPane` 遮罩**派发真实 `MouseEvent`（零反射），zh/en 各连跑 3 次 **`failures=0 unverified=0`**；含"投递未到达"与"持久性改 false"两组红控制。与 ESC（P4-12）同一套公开事件路由。
- **`>50MB` 大文件确认分支（已补）**：新增 `tools/probes/LargeFileConfirmationProbe`（真实 `PreviewLoader → InProcessUiport → WorkspaceActivityService → PreviewService → NotificationHost` 卡片），**20 条断言 × zh/en 全绿**，含恰好 50 MiB **不**确认 / +1 需确认、取消不加载不缓存、确认后真实 PNG 解码；两组红控制。**仍未覆盖**：从完整 `MainView` 手工导入该夹具、巨大图片自身的内存压力。
- 🚨 **`NonDaemonExitProbe` 是「判据 ≠ 通过」的探针**：它故意起一个睡 **600 s** 的非 daemon 线程，源码判据是"JVM 应 **60 s 内**自退"；而 `run-probe.ps1` **不设**该超时，于是它必然等约 600 s 才自然 `exit=0`。**`exit=0` 不能当作"关窗后无残留风险"通过**；生产关窗生命周期仍以 `BusyCloseProbe` / `PendingConfirmCloseProbe` 为准。（会话内限时杀树 `taskkill /T /F` 被宿主拒绝，无法用超时终止来测量。）这是本批发现的**判据/运行器不匹配**，非产品回归。
- 🔧 **harness 修正（已披露）**：批次运行器原在 `tools/probes/p4-18.mjs`，但它**硬断言**证据根必须落在 `<盘>:\test\mht\` 下（机器专有）→ 违反 `tools/` 的分发/可移植承诺，已移入 **`.claude-work/`**（本地草稿区，与 `run-matrix.ps1` 同类）。`LargeFileConfirmationProbe` 默认夹具路径仍为本机 `<盘>:\TEST\mht\p4-18\fixtures`（可用 `-Dprobe.fixtures` 覆盖），与既有探针一致。
- **V1（可见性回收阶段二首批）**：探针/测试反射归零，测试访问面 **108 → 88**（迁移 20/29）；新增 `baseline.AppConfigFieldContract` 以**字节码字段表**保住"AppConfig 新增字段必须同步签名/深拷贝"守护（零反射）。剩余 **9 条**无等价公开观察路径（`SidePanelProbe.field/fieldDeep/invoke`、`HtmlViewerProbe.invoke/invokeStatic`、`ContextMenuProbe.fieldOf`、`MainController.contextMenu/refs`、`FileListContextMenu.actions`）——**保留原断言与清单登记，未扩生产 API、未改可见性**。详见 `.claude-work/v1-report.md`。

**P4-19（2026-10-10）：native 元数据缺口 `PreviewCacheMeta$ImageEntry[]`（用户 native 实测预览失败）**
- **现象**：native dynamic 版图片提取模式点「查看预览效果」→ `解析失败: MissingReflectionRegistrationError: Cannot reflectively instantiate the array class 'com.mhtextractor.cache.PreviewCacheMeta$ImageEntry[]'`；JVM 完全正常（native-only）。
- **根因**：触发在**写缓存** `PreviewCacheStore.write` 的 `MAPPER.writeValue`；Jackson 为 record 集合属性建 serializer 时经 `PotentialCreator.introspectParamNames` 反射实例化该**数组类**，镜像未注册。**只在 `images` 为空时触发**（用户那条正好"没有找到图片资源"）。这也解释了"**没弹卡片**"：失败在 `rebuild` 写盘阶段，`completePreview`→`PreviewNotices` 从未执行；通用解析失败按 `PreviewLoader` 语义本就只内联显示（**不是**弹卡片）。
- **修复**：`reachability-metadata.json` 纯增量注册 `PreviewCacheMeta$ImageEntry[]`（agent 差集确认的唯一真实缺失项）+ 一条 agent 采集的 resource glob；对账丢失数 0。
- **红/绿控制组（真 native）**：新增 `tools/native/NativePreviewCacheProbe.java` + `tools/native/native-preview-cache-smoke.ps1`（把探针**编成 native 镜像**再跑；`-MetadataDir` 切修复前快照做红组）。**红** = 报错逐字一致、栈顶 `PreviewCacheStore.write`；**绿** = `2 passed, 0 failed`（含 `write/load round-trip`）。🚨 **只调 `load` 的探针在修复前也通过**——该缺口只由**序列化**触发，控制组必须含 `write`。
- **验证**：`build-native.cmd` exit 0 + report `status=OK` + 卡片冒烟 exit 0，dist 更新 `<盘>:\JAVA\mht-resource-extractor-dist`；`audit-native-metadata.ps1` `STALE = 0 / 590`；全量 `mvnw clean test` **964/0 + 73/0**。
- 🔧 **harness 修正（已披露）**：冒烟补 `-H:+JNI` 与 `-H:NativeLinkerOption=prism_common.lib`（NIK 静态库配方必需，否则 LNK2001）、`-o x.exe` → `-H:Path/-H:Name`（避免 `.exe.exe`）；native-only 探针从 `tools/probes/` 移入 `tools/native/`（不进 JVM 探针集）。
- **未验证**：用户那次真实 GUI 点击未在 native 下自动复现（用等价探针覆盖同一 `write` 路径与同一数组类），请以新 dist 重测。

**P4-20（2026-10-10，harness 亲自实施）：失败也提醒 + 状态图标去 emoji + 后端按选项 B 停用**
- **1) 预览失败也提醒**：`PreviewLoader` 的 `PreviewLoadFailed(fileInfo=false)` 分支在原有内联提示之外，**追加右上角 toast**（`NotificationHost.toastWarning`，文案沿用 `preview.parseFailed` + 详情）。控制组（`.claude-work/verify/FailureToastProbe.java`，JVM 真 FX）：坏缓存根触发写盘失败 → `parseFailToast=true`；好缓存根（对照）→ `parseFailToast=false`（只有 r2/noImages 两条区域 toast）。
- **2) 状态列 emoji → Ikonli**：`PreviewListCell` 原用 emoji `⏳/✅/⚠️/❌`；`⚠️` 的变体选择符 `U+FE0F` 在 native 字体下渲染成方框（⚠️→三角+方框）。改为 `FontIcon`：`fas-hourglass-half` / `fas-check-circle` / `fas-exclamation-triangle` / `fas-times-circle`（与折叠箭头弃用 ▾/▸ 同因）。
- **3) 后端按选项 B 停用**：`App` 不再调用 `BackendConnectionPresenter.install`（不启动随包后端），并隐藏 `#backendStatusLabel`；不再有"后端不可用"噪音 toast。**原因**（实测）：随包后端 `IpcFiles` 要求 `java.io.tmpdir` 在 `<盘>:/TEST/temp` 下（否则 exit 1、零 stdout），且输入/输出白名单为 `<盘>:/TEST/mht`；GUI 双击时传系统 temp（C 盘）→ 必然失败。P4-17 验证脚本当时强制 `-Djava.io.tmpdir=<盘>:\TEST\temp` 才没暴露。相关类/探针/脚本保留，P5 做生产化（放开 tmpdir + 以 GUI 实际选择的根做白名单）。
- **连带修正（已披露）**：`BackendConnectionProbe` 的 production 断言按 B 改写（`production-backend-label-hidden` + `production-no-backend-toast`，替换原"期待 unavailable + 有 toast"）；其余 `BackendSession` 确认/断开/超时用例不变。`tools/ipc/verify-gui-backend.ps1` 的 Gui 模式随 B 失效，待 P5。
- **验证**：全量 `mvnw clean test` **964/0 + 73/0**；GUI **zh/en 34/0**；探针全绿（`BackendConnectionProbe` **76/0**，需 `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<盘>:\TEST\temp`）；native 重建 dist。

**P4-21（2026-10-10，harness 亲自实施）：前端/后端打包分离（后端移入 `Backend\`）**
- **布局**：发行目录根 = 前端 `MhtExtractor.exe` + JavaFX/VC++/JDK shim DLL + `启动.cmd`/`Launch.cmd`；后端在根下的 **`Backend\MhtExtractorBackend.exe`**（后端自包含、零 DLL）。理由：为 P5 多前端共享同一后端预留干净边界；前端 dynamic 配方**必须** DLL 与 exe 同级，故前端保持扁平。
- **解析**：`app.BackendExecutable.resolveIn` 优先 `Backend\<exe>`，回退旧扁平布局（旧发行包 / 开发期 jar 兼容）；`-Dmht.backend.executable` 覆盖不变。
- **构建**：`build-native.cmd` 复制后端到 `Backend\`，并**先清掉升级前的根目录旧副本与旧 `Backend\`**（只覆盖不删除会留下陈旧根副本）；构建末尾调用 `tools/native/assert-dist-layout.ps1` 断言。
- **控制组**：`BackendExecutableTest` 3 例（嵌套优先 / 仅嵌套 / 旧扁平兼容）；`assert-dist-layout.ps1` 对旧扁平伪目录 → exit 1（红）、static/dynamic 正确布局 → exit 0（绿）。**该断言在首次重建时真的报红**——抓到 `build-native.cmd` 未清理旧根副本，已修。
- **验证**：全量 `mvnw clean test` **967/0 + 73/0**（新单测 +3）；native `build-native.cmd` exit 0 + `status=OK` + 布局断言通过 + 卡片冒烟 exit 0；dist 根无后端 exe、`Backend\` 有。
- **边界**：P4-20 起 GUI 不启动后端，故 `Backend\` 目前仅交付形态、运行时不加载；`tools/ipc/verify-gui-backend.ps1` 的 Gui 模式仍待 P5。

**验证脚本自身也可能是错的**：
`mvn -q` 吞过 surefire 摘要；正则漏过以分号结尾的枚举常量；`$null -eq $int` 不可靠；
聚合报告时用 `-notlike '*output*'` 把 `output.ImageWriterTest` 一并滤掉（少算 38 个）；`$proc.ExitCode` 在本机**恒为空**
（曾把 9 个全绿探针误报为失败）。**对"不一致"要再核一遍再下结论。**

**2026-10-12 新增两条"验证器前置"坑（P5a 批次实测，都造成过假红/假数字）**：
1. 🚨 **控制组"还原后仍然红"先查 mtime**：用 `Copy-Item` 还原源文件会把**旧 mtime** 一起带回来 → Maven 增量编译判定
   "源不比 class 新" → **不重编译** → 你跑的还是注入版编译出的 class，**假红**。**正确姿势**：还原后先比 **SHA256**，
   再 **touch 源文件（或 `clean`）** 才复跑。（P5a-1a / 1b 各踩一次）
2. 🚨 **native 类脚本必须显式设 `JAVA_HOME`**：本会话环境里 `JAVA_HOME` 可能是占位坏值（实测 `<盘>:\install\New Folder`），
   `build-native.cmd` 的卡片冒烟会拿它去 `javac` → `javac failed (exit=1)`（**假红**，与产品无关）。
3. 🚨 **`native-card-smoke.ps1` 必须像 `run-probe.ps1` 那样显式带上 `support/ProbeRuntime.java`**：该 support 类在 `tools/probes/support/` 下但**是默认包**，
   `-sourcepath` 对「默认包 + 子目录」无效；要在 `-d ... $probeSrc` 之后再给一个 support 源文件实参。
   **P5a-3（`434689d`）引入的该回归让 Gate C 自那时起一直失败**，直到 P5a-6 复跑才暴露。
4. 🚨 **`audit-native-metadata.ps1` 必须在 `mvn package` 之后跑**：只在 `clean test` 之后跑（无 jar）时 classpath 不全，
   "total available" 从 **35109** 掉到 **27287** → 假 `STALE 505/590`。packaged 后的基线是 **`STALE 3/590`**
   （三条既有 JavaFX media/WebView 条目，属既有，**审计 exit 0**）。

**三档闸门（每个改动都要过）**

| 档 | 内容 | 判据 |
|---|---|---|
| **A**（分钟级） | `compile` + 定向单测 + 结构事实（行数 / 标记 / 直写） | 全绿；无"重排整文件"型 diff |
| **B**（每阶段） | `mvn clean test` + 独立聚合 + `ProductBaselineTest` + `ArchitectureTest` + GUI 自动化 + 探针 + 日志键对账 | 默认 **1101/0**（2026-10-16 P5d-2 起）+ headless **74/0**；产物基线 **46/0**；不变式 **17/0**；GUI zh/en **36/0**（P5b 后）；探针 **16 原有** + **`LargeFileConfirmationProbe`（P4-18 新增）** 全绿（⚠️ `NonDaemonExitProbe` 判据为「60 s 内自退」而 `run-probe.ps1` 不设该超时，实际约 600 s 自然退出，`exit=0` **不得**当作「无残留风险」通过，见本节 P4-18 条）；`log.*` 键逐键不变（P4-15/16/17 只新增键）|
| **C**（阶段收尾） | native 构建 + native 产物基线 + 截图对比 + i18n 键 | `build-native.cmd` exit 0、report `status=OK`；native 基线 **44/0**（2026-10-08 用含 P4-13/14 的**全新无头 exe** 复跑：matrix 45 行 = 表头 + 44，**PASS 44 / FAIL 0**）|

**没实际跑过的项目要明确标为"未验证"，不许推断通过。**

---

## 7. 验证工具（`tools/`，随仓库分发）

| 工具 | 用途 |
|---|---|
| `check-env.cmd`（仓库根） | 打包机第一步：9 项环境预检 + 修复命令（0=就绪 / 20=缺 MSVC / 1=缺其它） |
| `build-native.cmd` | native 构建**唯一实现**（`.ps1` / `.sh` / `run-native-image.bat` 只是委托入口） |
| `tools/probes/run-probe.ps1` | 编译并运行 UI 探针（自动推导 JavaFX module-path 与 classpath；`target/` 缺失时自动 `mvn package`；`-List` 列全部探针） |
| `tools/probes/*.java` | `UiProbe`（布局截断 / 屏上中文字符）· `SidePanelProbe` · `HtmlViewerProbe`（含真实剪贴板逐字符比对）· `ContextMenuProbe` · `ParamDraftProbe` · `NotificationAcceptance` · `BusyCloseProbe` · `PendingConfirmCloseProbe` · `NonDaemonExitProbe` · `ModalPaneStateProbe` · `IconShowcaseProbe` · `MainViewRefsProbe` · `AppCardProbe` · **`PreviewLoadingProbe`（P4-15/16）** · **`BackendConnectionProbe`（P4-17：后端连接 + 确认应答 + 断连/超时，zh/en 各 75 断言）** · **`LargeFileConfirmationProbe`（P4-18：`>50MB` 预览确认边界 + 取消/确认路径 + 真实 PNG 解码，20 断言 × zh/en）** |
| `tools/native/audit-native-metadata.ps1` | 元数据**过时项扫描 + agent 差集**（防丢条目 / 防幻影条目） |
| `tools/native/native-card-smoke.ps1` | native 提醒卡片渲染冒烟（`build-native.cmd` 的 `[STEP SMOKE]` 调用） |
| `tools/verify/assert-gui-mirror-clean.ps1` | **P-F6**：GUI 原始字节业务标记为零；后端 `-ExpectBusiness` 对照；已接入 GUI dist 构建步骤 |
| `tools/verify/gui-mirror-clean-controls.mjs` | **P-F6**：对配对 dist 复跑 22 项反转/字节/片段控制，记录原始命令、输出与还原 SHA256 |
| `tools/native/native-preview-cache-smoke.ps1` | **P4-19 新增**：把 `tools/native/NativePreviewCacheProbe.java` 真的编成 native 镜像并跑（`load` 空/非空 + `write` 回读）；`-MetadataDir` 切快照做红组 |
| `tools/native/audit-metadata-depth.ps1` | **元数据修复批新增**：GUI/headless **双边 type 注册深度差集**（0=无候选 / 1=有候选）；候选必须分**实证需要 / 可能不需要 / 不确定**三类并给判据，**不许为清空清单而批量补注册** |
| `tools/native/native-preview-warning-smoke.ps1` | **同批新增**：把"产生警告的预览"编成**真 native 后端**镜像跑（`-MetadataDir` 切修复前快照做红组；带"重放旧镜像即报 `Selected metadata differs from image snapshot`"防伪控制）；配套 `native-preview-warning-client.mjs` |
| `tools/native/native-ffm-smoke.ps1` | **P5a-1b-r 新增**：编 `NativeFfmProbe.java` 为 native 镜像，验证 **FFM downcall 在镜像里可调用**（`-MetadataDir` 切注册前快照 → 红 `MissingForeignRegistrationError`；注册后 → `FFM_OK`）；`-ExpectMissingRegistration` 断言红组 |
| `tools/native/native-backend-exit-smoke.ps1` | **P5a-1b-r 新增**：无头 native 后端**退出/收尾**冒烟（`--once` 与 `--serve`+EOF） |
| `tools/recovery/*`（**本地开发区；不随发布**） | 文档被误替换后的恢复与等价核对 |
| `tools/drive/drive-claude.mjs`（**本地开发区；不随发布**） | 驱动 Claude Code CLI（多候选解析 exe 路径；提示词走文件，不走 stdin） |
| `tools/ipc/client.mjs` | **P4-2b 新增**：无头后端 IPC 脚本客户端（Node 内置模块；起 `--serve` → 导入→分组→设参→提取 → 收事件 → 干净退出；stdout/stderr 分流） |
| `tools/ipc/verify-gui-backend.ps1` | **P4-17 新增**：GUI 随包后端（拉起/握手/确认/断连）的端到端验证脚本。⚠️ **选项 B（P4-20）后 GUI 不再启动后端，其 Gui 模式待 P5** |
| `tools/diag/tee-probe.cmd`（**本地开发区；不随发布**） | 「命令行 tee 最小复现」：定位 `call :label` 落空类问题 |
| `tools/launcher/make-launcher.ps1` | 生成 dynamic 产物的 `启动.cmd` / `Launch.cmd`（修"中文路径下启动失败"） |

**测试怎样访问生产控制器**：生产类**不为测试开放状态面**（`test-support` 标记 = 0）。两条正路：
① 测试源集的同包 driver `com.mhtextractor.gui.controller.AcceptanceDriver`（**直访，无反射**；`MainController` 现共 **49** 个包级成员、其中 **30** 个被驱动面访问，全测试访问面 **88** 条由第 13 条不变式 `testAccessSurfaceIsFrozen` 冻结）；
② 真正的公开行为（如 `WorkspaceModel` 的公开 API）——**优先用这条**。⚠️ **探针也在访问面扫描范围内**（见 §6）：探针里加反射同样会把第 13 条打红（P4-12 实测），所以**探针也优先走公开 API**（例如用 `Event.fireEvent` 派发真实事件，而不是反射调私有 handler）。

### 派发实施方：Claude Code CLI 与 Codex CLI（**长活走 detach**，2026-10-08）

两条实施通道：`tools/drive/drive-claude.mjs`（Claude Code CLI，**本地开发区；不随发布**）与 **`codex_run` 工具**（Codex CLI，底层是 `.claude-work/codex/drive-codex.mjs`）。
**指挥方只做统筹与独立验收，不自己改 `src/`、`tools/`**（除紧急修正并说明）。

| 任务规模 | 怎么派 | 为什么 |
|---|---|---|
| 短 / 原子（≲8 分钟） | `codex_run(prompt_file=…, label=…)` **同步** | 有界返回；同步路径自带 540s 预算，到点由 driver **自己**整树终止、核实 pid 真死、写终版 meta（`termination=channel-budget`），不把"结束"交给宿主 |
| **长活 / 架构级（可能 >8 分钟）** | **`codex_run(…, detach=true)`**，再用 **`codex_status(label=…, wait_ms=540000)`** 轮询取回 | 工具调用**立刻返回** → 不受调用通道的 ~600s 切点约束；且**只交一次启动税**，不必按 10 分钟切片（切片会逼模型只看局部、丢掉架构判断） |

> **实测依据（2026-10-08）**：走 `run_code` 的会话，其单次工具调用在 **567 / 575 / 584 / 592 / 593 s** 被宿主切断（meta 无 `status`）；
> 同一天在**直连会话**里一次同步 `codex_run` 跑到 **765 s 正常返回**。→ **"600s" 是调用通道的属性，不是 Codex 的**。长活一律走 detach。

**被中断时的纪律（不许推断通过）**

1. 先 `git status --porcelain` / `git diff` 判交付完整度，并**清掉本批可能留下的注入/半成品**（控制组注入、临时探针、临时文件）。
2. 用返回的 `thread_id` **续跑**：`codex_run(prompt_file=<下一批提示词>, label=<新标签>, resume_thread="<thread_id>")`——不重开线程、不重交启动税；续跑时**保持同一模型档**。
3. 证据全在 `.claude-work/codex-logs/`：`*.meta.json`（`status / abortReason / killVerified / threadId / usage / budgetMs`）、`*.hb.json`（心跳，driver 被杀后仍可交叉验证）、`*.jsonl`（原始事件）。**指挥方必须读这些文件，不能只看工具返回的那一行摘要。**

**思考强度（effort）怎么定——混合制（2026-10-08 用户拍板）**

默认由 harness 按下表**自判**，per-call 用户指定优先；每次派发把所选档位与理由写进汇报。

| 任务类型 | 档位 |
|---|---|
| 机械搬迁 / 改名 / 文档同步 / 跑门禁 | `low`~`medium`（**大而机械 ≠ 需要高强度**，它最吃轮数） |
| 常规实现 / 修 bug / 写测试 | `medium`~`high`（本项目默认 `high`） |
| 架构设计 / 跨层重构 / 疑难不确定问题 / **验收失败后的返工批** | `xhigh` |
| `max` / `ultra` | **必须用户放行**（`codex_run(…, unlock_effort=true)`）；`ultra` 会**自动委派子任务**，与"唯一实施方 + 指挥方独立验收"冲突，默认不用 |

- **成本 ≈ 每轮思考 token × 轮数**（实测：14 项 / 540 s 的 `low` 批 ≈ 5 个 5h 额度点；1 轮最小任务 ≈ 0 点）→ **长批 + 高强度是最贵组合**；不要因为"改动大"就升档。
- 强度不够时优先 **`resume_thread` 同线程升档**（模型标记支持运行中改强度，且不重交启动税），而不是重开一批。
- 插件侧的自治上限 `maxEffortAllowed`（默认 `xhigh`）在**机制上**兜住"升档漂移"：越过它必须显式放行、且返回值里留痕（`effort_unlocked`）。
- **选档用 `task_kind`（机制化，2026-10-10）**：派发时给 `codex_run(..., task_kind="mechanical|docs|review|implement|architect|rework")`，插件按 `lib/core.js` 的 `TASK_KINDS` 规则表定模型与强度（mechanical/docs=**low+luna** · review=medium+sol · implement=high+sol · architect/rework=**xhigh+astra**）。规则是**机制**不是自觉：显式 `model`/`effort` 仍优先，返回值回报 `model_source`/`effort_source`（`explicit|task_kind|default`）；表内最高只到 `xhigh`，`max`/`ultra` 必须显式 `unlock_effort=true`；`maxEffortAllowed` 被压低时规则档同样会被拦下。
- **改默认值/开关不用改提示词、也不用重启**：用 `codex_config` 工具（热生效——`codex_run` 每次调用都重读设置文件）。常用：`codex_config(action="set", key="defaultModel"|"defaultEffort", value=…)`、`key="autoModel"|"autoEffort"`（置 `false` 即"必须显式传 model/effort **或** task_kind"）、`key="codexConfig"`（Codex 配置白名单，见插件 README）；`action="get"` 看生效值与来源，`action="reset"` 删项目级文件。

---

## 8. 依赖

Jakarta Mail 2.0.2（MIME）· Jsoup 1.18.1（HTML）· Jackson 2.18.6（JSON）· **glavo webp 0.2.0**（纯 Java WebP 解码，无 JNI）·
AtlantaFX 2.1.0（主题）· Logback 1.5.26 · Ikonli 12.3.1 · JUnit 6.0.3 · Lombok 1.18.44

> **新增依赖前务必三思**：项目要出 GraalVM native EXE，每个新依赖都可能带来反射 / JNI / 资源元数据负担。
> `ArchitectureTest.noNewThirdPartyDependencies` 会直接失败。加图标常量前先确认 `ikonli-fontawesome5-pack` 里有。

---

## 9. native 打包现状（摘要）

- **产物**：GraalVM native EXE（`MhtExtractor.exe` + DLL），绿色免安装；**默认 `dynamic` 23 文件 / ≈108 MB**（P4-17 起随包后端；**P4-21 起在 `Backend\` 子目录**，前端 exe + DLL 在根目录），
  `set NATIVE_RECIPE=static` 换 NIK 静态配方（15 文件 / ≈98 MB）。两种配方产物**逐字节等价**（native 基线 **44/0**，2026-10-08）。
- **三条必须记住的**：
  ① JavaFX DLL 是否随包**取决于配方**（dynamic **必须**随包；static **一个都不能放** —— **包括 PATH 里也不能有**：构建机 JDK 的 `bin` 里就带着它们，见 §6③，否则 `WinApplication._init` 段错误）；
  ② 🚨 **不要从项目树内运行**（`Font.loadFont` 返回 `null` → 全部图标变空心方框）；
  ③ dynamic 配方在**非 ASCII（中文）路径**下双击 exe 会启动失败 → 双击随包的 **`启动.cmd`**，
     或改用 `static` 配方，或放进纯 ASCII 路径。
- **可达性元数据**在 `src/main/resources/META-INF/native-image/com.mhtextractor/`：
  `reachability-metadata.json`（**权威**，含 `{"bundle": "i18n/messages"}` 与 Jackson DTO 构造器）·
  `reflect-config.json`（旧格式，**与统一格式并存且必须并存** —— 同 type 取并集，删旧文件会丢条目）·
  `resource-config.json`（FXML / CSS / logback / 字体）· `jni-config.json` / `proxy-config.json` /
  `serialization-config.json` / `native-image.properties`。
- **元数据纪律**：**必须用 tracing agent 采集，不许手工推断**（i18n 的"需要 ResourceBundle 反射"就曾被推断错：实测 0 条）；
  **按并集合并**、**纯增量 diff**、**方法/字段级对账丢失数 = 0**、**不要往 `src/main/resources` 放临时/备份文件**（会被打进 jar）；
  过时项用 `tools/native/audit-native-metadata.ps1` 审计。改元数据后**必须**做**全部产物基线**回归（2026-10-08 起为 **44** 份）。

> 构建命令、配方细节、验收清单、故障排查与全部实测坑 → **《基于graalvm的MHT资源提取器项目打包工作流程.md》**。

---

## 10. 已知缺陷与遗留目标

**完整台账见《遗留目标与未完成事项.md》**。当前登记的主要几类：

- ✅ **已修复（E4.1 / S5）**：业务层不再产已解析文案，改产 `model.ExtractWarning`（键 + 具名参数）；
  `PreviewCacheMeta.warnings` 语言中立，展示层经 `I18n.render(...)` 按当前 locale 渲染；`FORMAT_VERSION` 已 2 → 3。
- ✅ **已修复（E4.2 / S4）**：`AD_KEYWORDS` 仍硬编码，但 `AppConfig.IMAGE_EXTRACTION_RULES_VERSION` 已参与
  `imageExtractionFingerprint()`；**改关键词清单 / 去重口径时必须 +1**，否则预览缓存串味。
- `MainController` 已从 3875 行降到 **1612** 行（P3-B4 的 +8 已裁决记账；P5a-3/8 继续下沉）：参数域随**步骤 1** 外迁到
  `ParamDomainController`（**376** 行）、提取编排随**步骤 2** 收口到 `ExtractionTaskCoordinator`（**396** 行），残余为 GUI 事件接线与状态呈现；
  `FeatureExercise` 已于步骤 6 拆分（演练 → `AgentExercise`、共享底座 → `FeatureExerciseSupport`、
  业务断言 → 各业务包 JUnit；1633 → 当前 **1356** 行）。
  ⚠️ 本段行数为 **2026-10-16 实测快照**，漂移以 §1 状态表为准。
- **2026-10-05 本轮交付**：查看器「提取标题」可编辑并覆盖输出命名（`5d2cee2`）、查找全部命中底色 + Chrome 配色分层（`ff25a18`）、
  右键菜单文案（`512429f`）、「只提取选中的列表项」开关 + 右键「应用到所选」5 档动态文案（`91ce9e8`）；
  文档收敛为四份、其中两份临时文档不入远程（`588c13c` / `e55187e` + 本次提交）。
- 测试控制器的通道是**同包直访**（`AcceptanceDriver`）。口径已拍板为**选项 D**：不数可见性，数**实际被测试访问的
  (生产类, 成员) 点**，由第 13 条不变式 `testAccessSurfaceIsFrozen` 冻结（清单 **88** 条；见 §3 与
  《遗留目标与未完成事项.md》§1.6 / §2.4）。**本期只冻结、不回收** `private`。
