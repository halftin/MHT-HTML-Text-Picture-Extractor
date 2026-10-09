# CLAUDE.md

> 工作区指令，**改代码前必读**。
> **最后更新：2026-10-05**（分支 `2.5-beta`，项目版本 `3.0.0`，版本号以 `pom.xml` 为唯一来源）。
> 文档集**共四份**：**本文件**（工程约定）+《基于graalvm的MHT资源提取器项目打包工作流程.md》（打包手册）+
> 《遗留目标与未完成事项.md》（尚未收口的目标与边界）+《UI可切换重构方案.md》（UI 可切换 / 无头后端重构，2026-10-05 定稿）。
> **推送口径（2026-10-05）**：`git push` 时**只有本文件与打包手册**随仓库分发；《遗留目标与未完成事项.md》与《UI可切换重构方案.md》是**临时文档**（本地保留、已 `.gitignore`、不入远程仓库）。
> 其余历史文档已删除，需要追溯请看 git 历史。

---

## 1. 项目与当前状态

JavaFX 25 桌面 GUI：把 `.mht` / `.mhtml` / `.html` / `.htm`（含 HTML 侧车文件夹）批量提取为**文本**与**图片**。
入口 `com.mhtextractor.App`。

| 项 | 现状（2026-10-05 实测） |
|---|---|
| 重构阶段 | A1 / A2 / B / C / D / E / F **全部实施并验收**；第二批 **S0–S6（E1–E5）** 亦已实施并验收；《UI可切换重构方案》**P0（三条度量/不变式）与 P1-1…P1-5（全局参数缓存 + 预览缓存构造 + 侧边栏会话基线 + 参数剪贴板 + 撤销载荷 + 启动前顺序门链下沉 app，闸门 2 已归零）已实施并验收**；**P2-1/P2-2（抽 ui-core：`ExtractOutcomeStyles` / `HtmlFindIndex` / `ParamTargetText` / `FileMenuModel` 下沉 `com.mhtextractor.uicore`，零 JavaFX）已实施并验收**；**P3-1（端口能力覆盖矩阵草案：167 条 → 覆盖 42 / 前端 92 / 缺口 33）· P3-2（`com.mhtextractor.uiport` 最小切片 API 定稿，19 类 / 0 违规）· P3-3a（`app.InProcessUiport` 进程内适配器，工作区可回答子集 + 端口诚实化收敛）· P3-3b-1（产物命名规则下沉 `app.OutputNameService`；gui 直连业务类型 34 → 32）· P3-3b-2（预览缓存键计算与每项目写锁下沉 `app.PreviewService`；直连 32 → 30；`MainController` 1706 → 1705）· P3-3b-3（提取运行时配置 + 输出单元计划下沉 `app.ExtractionPlanService`；直连 30 → 29；`MainController` 1705 → **1679**）· P3-3b-4（「查看源码」导出下沉 `app.SourceViewService`；直连 29 → 28）· P3-3b-5（会话状态持久化下沉 `app.SessionStateService`；直连 28 → 26；`MainController` 1679 → 1677）· P3-3b-6（预览命名/扩展名纯函数下沉 `app.PreviewNamingService`；直连 26 → 23）· P3-3b-7（预解析解析+取正文/标题下沉 `app.SourceParseService`；直连 23 → 20）· P3-3b-8（命名配置 + 像素打包两单例收口 `app.NamedConfigService`/`app.PreviewPixelService`；直连 20 → 18）· P3-3b-9（提取每文件覆盖映射构造下沉 `app.ExtractionPlanService.overrides`；直连 18 → 17）· P3-3b-10（gui 去除 `i18n.ErrorCode` 直连，改写为 `I18n.get("error.<NAME>")`；直连 17 → 16）· P3-3b-11（gui 去除 `extractor.ImageExtractor` 直连，改用 `app.PreviewImageData`；直连 16 → 15）· P3-3b-12（gui 去除 `util.SelectionState` 直连，改用 `app.SelectionRules`；直连 15 → 14）· P3-3b-13（提取结果回写下沉 `app.ExtractResultService`；直连 14 → 13）· P3-3b-14（`util.ExtractOutcome` 迁入 `model`；直连 13 → 12）· P3-3b-15（`util.ChangeListener` 迁入 `model`；直连 12 → 11）· P3-3b-16（预览缓存生命周期经 `app.PreviewSession` 门面收口；直连 11 → 10）· P3-3b-17（`PreviewResult` 增中性 `ImageRef`/`images()`，gui 去除 `cache.PreviewCacheMeta` 直连；直连 10 → 9）· P3-3b-18（gui 去除 `util.GroupStructure` 直连，改用 `app.WorkspaceStructure` 包装；直连 9 → 8）· P3-3b-19（`util.GroupItem` 迁入 `model`；直连 8 → 7）· P3-3b-20（`util.MhtImportPlanner` 迁入 `app`；直连 7 → 6）· P3-3b-21（`util.DropResolver` 与 `util.UndoManager` 迁入 `app`；直连 6 → 4）· P3-3b-22（新增中性 `app.ExtractionStats` DTO，gui 去除 `processor.BatchProcessor` 直连；直连 4 → 3）· P3-3b-23（`util.ParamScope` 迁入 `model`；直连 3 → 2）· P3-3b-24（新增 `app.Texts` 文案门面，gui 去除 `i18n.I18n` 直连；直连 2 → 1）· P3-3b-25（`config.AppConfig` + `util.FingerprintEncoder` 迁入 `model`；直连 1 → **0，P3 目标达成**）· **P3-B1（端口查询三件 `runtimeConfig`/`outputUnitPlan`/`paramSchema`；表单改 schema 驱动；访问面 119 → 118；测试 863 → 869；`MainController` 1677 → 1670）· P3-B2（端口活动编排三件：取消工作区活动 + 预解析编排/事件 + 预览加载编排/事件；新增 `uiport.PreparseResult`/`app.WorkspaceActivityService`；`PreparseController` −184 行、`PreviewLoader` −315 行；访问面 118 → 116；测试 869 → 877；`MainController` 1670 → 1654）· **P3-B3（端口勾选 `setChecked` + 分组命令集 `group/ungroup/renameGroup/toggleGroupExpanded`；勾选唯一写入口收口到模型、组名唯一化以模型为源、展开状态后端化且幂等；测试 877 → 884、headless 68 → 73、`MainController` 1654 → 1648）· **P3-B4（端口参数命令族 `setGlobalParams`/`setGroupParams`/`setItemParams`/`clearParams`/`applyGlobalParams`；新增 `app.WorkspaceParamCommandService`（三层应用+撤销压栈+缓存失效整体下沉）、`app.ParamTargets`（勾选优先解析只搬不改）、`uiport.TargetId`；测试 884 → 912；⚠️ `MainController` 1648 → 1656（+8 装配适配器，**已裁决接受并记账**））· **P3-B5（导入编排下沉：端口 `importPaths`/`applyImportPlan` + `ImportStateChanged`/`ImportRound`/`ImportFinished` 事件 + `ImportPlanVm`/`ImportBatchVm`；新增 `app.WorkspaceImportCoordinator`、`gui.component.GuiConfirmer`；**BC-5 一次导入=每轮恰好一条撤销记录**（首轮曾误作「每批一条」而红，已逐处反向回退并拆小重做）；访问面 116 → 114；`MainController` 1656 → 1655）· **P3-B6（提取任务通道契约：端口 `startExtraction(checkedOnly)`/`cancelExtraction()` + non-sealed `ExtractionChannelEvent`（`ExtractionProgress`/`ExtractionTerminal`/`ExtractionResult`）+ `ExtractionEnd`；新增 `app.WorkspaceExtractionCoordinator`；`ExtractionStats` 迁入 `model`；收尾另修 worker `catch(Exception)`→`catch(Throwable)`（Error 亦须交付 FAILED 终态，否则通道晾死）；访问面 114 → 108；测试 912 → 914；`MainController` 1655 → 1654）已实施并验收 —— **P3 缺口复算 = 0**（33 → 0，12/12 处置项落地）**；**P4-1（无头后端骨架 `backend-cli`：新增 `backend` 包 + 手写参数解析 + 无头 logback（冲突 C8）+ `BackendOnceSmokeTest` 7 用例；`ArchitectureTest` 把 `backend` 纳入零-JavaFX 扫描并把访问面上限 116 → 108；测试 914 → 921）· **P4-2a（IPC 核心：`backend/IpcServer` + `IPC.md` 协议 v1〔request/response/event/error 四类帧 + 5 个错误码；stdout 只放协议帧〕+ `--serve` 真服务端 + 契约测试〔IPC 与 in-process 双路径一致〕；测试 921 → 926）· **P4-2b（IPC 批量数据走文件 + 脚本客户端 + 1 万文件压测：`workspacePage` 分页（offset/limit ≤256）、`IpcSession`/`IpcFiles`、`tools/ipc/client.mjs` 脚本客户端、`BackendIpcBulkTest`；测试 926 → 932）· **P4-3（独立 native 无头构建 `NATIVE_TARGET=headless` + 元数据**只做增量后端子集**（既有七份 diff=0）+ **第 17 条不变式「无头构建不得含 JavaFX」**；产物 **2 文件 ≈35.5 MB、零 JavaFX DLL**；测试 932 → 933）· **P4-4（中文目录矩阵：修复中文临时目录限制；`tools/ipc/chinese-path-matrix.mjs` 可复跑矩阵〔6 类中文路径场景 × JVM/native × `--once`/`--serve`(脚本客户端) × zh/en〕+ `path-assertions.mjs` + `CHINESE-PATHS.md`；**64/64 格通过**、120 产物 SHA-256 复核；测试 933 → 936）· **P4-5（扩 `backend-cli` 参数面：`--config <AppConfig.json>`〔命名配置属性名；`FAIL_ON_UNKNOWN_PROPERTIES` 严格校验；显式 flags 覆盖 config；`--serve` 下显式拒绝〕+ **无头侧 42 份产物基线 42/42**〔`BackendConfigBaselineTest`：21 用例 × {zh,en} 经无头路径逐份对照〕；测试 936 → 940）· **P4-13（C2 `{basename}` + C3 启动握手 `hello`/`welcome` + `BackendLauncher`；测试 940 → 945、基线 42 → 44）· P4-14（C4 确认卡跨进程语义：真实确认往返 + 断开即取消 + 30 s 可配置超时；测试 945 → 949）· **P4-15（修「查看预览效果永久停在加载态」+ 区域限定放宽，预览与提取一致；测试 949 → 958；`IMAGE_EXTRACTION_RULES_VERSION` 1 → 2）已实施并验收**；**P4-17（A-4：方案 §14 决策 10/11 的 GUI 侧接线——GUI 启动时拉起随包 `MhtExtractorBackend.exe`、校验握手、用应用内确认卡应答 IPC 确认、断连即取消 + 30 s 超时兜底；dist 随包后端；测试 961 → 964、i18n 547 → 571）已实施并验收**（2026-10-09，见《遗留目标与未完成事项.md》§13）；**V1（可见性回收阶段二首批：探针/测试反射归零，测试访问面 108 → 88，迁移 20/29；剩余 9 条无等价公开观察路径、保留原断言与清单登记，未扩生产 API、未改可见性）· P4-18（三项便宜的未验证项补齐：en 应用日志 CJK=0 / 点击遮挡层不关确认卡 / `>50MB` 确认分支；新增 `LargeFileConfirmationProbe`；单测仍 964+73）已实施并验收**（2026-10-09）；**P4-19（native 元数据缺口 `PreviewCacheMeta$ImageEntry[]`：写缓存时 Jackson 反射实例化数组类；补注册 + native 红/绿控制组 + 重建 dist；测试仍 964+73）已实施并验收**（2026-10-10） |
| `MainController` | **1654** 行（上限常量 **2240**）。P3-B4 的 +8（1648→1656）已**裁决接受并记账**（装配适配器）；B5/B6 各回落 1 行至 **1654** |
| 测试 | 默认执行 **964**（+ `headless-layer-b` **73**）；架构不变式 **17** 条；产物黄金基线 **44** 份（P4-13 增 `{basename}` 两例；P4-15 只改 2 份的产物）|
| GUI 自动化 | zh / en **各 34 passed / 0 failed**；`tools/probes/` 探针全绿 |
| i18n | zh_CN / en_US **各 571 键**（两份键集与占位符集合必须一致，P4-17 后差异 = 0）+ **22** 个 `ErrorCode` |
| 日志键 | `log.*` **243 键**（P4-15/16/17 累计新增；既有键文案逐键不变） |
| native | 默认 `dynamic`：**23 文件 ≈108 MB**（**P4-17 起随包 `MhtExtractorBackend.exe`**，GUI 启动时自行拉起并握手）；`static`：15 文件 ≈98 MB；**`NATIVE_TARGET=headless`（P4-3）**：**2 文件 ≈35.5 MB**（`MhtExtractorBackend.exe`，**零 JavaFX**） |
| 规模 | 主源集 **187** 个 java 文件（其中 `gui` **31**、`uicore` **4**、`uiport` **21**、`app` **44**、`model` **10**、`backend` **12**、`config` **3**、`util` **12**）/ 测试源集 **120** 个（2026-10-08 P4-14 实测） |

---

## 2. Build & Run

> ⚠️ 本机 **`mvn` 不在 PATH**：用仓库自带的 **`mvnw.cmd`**，并显式指定 JDK（Liberica NIK 25）。

```bash
export JAVA_HOME='D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3'
MVN='/c/Users/Admin/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn.cmd'

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
# 结果写入 logs/test-results.txt（当前基线：zh 34/0、en 34/0）
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

**依赖方向严格单向：`gui → app → 业务包`**；`app`、业务包、`model`、`platform` **零 JavaFX**
（按**字节码常量池**扫描强制，不是查 import —— 全限定名引用也能抓到）。

```
gui          ← JavaFX 视图/控制器（唯一允许依赖 javafx.* 的层之一）
  ↓
app          ← 无头应用层（零 JavaFX）：MhtExtractorApp / WorkspaceModel / WorkspaceParamService /
  ↓            WorkspaceImportService / WorkspaceExtractionService / ExtractionPreconditions / PreviewService
业务包        ← core / extractor / processor / output / util / cache / config / i18n
model        ← 纯数据模型（PreviewItem / ThumbnailData / ExtractWarning），零 JavaFX，app 与 gui 共用
platform     ← 与操作系统交互的基础设施（零 JavaFX，纳入同一条不变式扫描）
uicore       ← 框架无关的前端共用逻辑（零 JavaFX，P2 起纳入同一条不变式扫描）
uiport       ← 端口层（命令/查询/事件/确认往返；零 JavaFX、零 I18n、零业务类型；P3 起，uiportHasNoJavaFxDependency 强制）
backend      ← 无头后端（P4 起）：backend-cli 入口（--once / --serve）+ 无头 logback + **启动握手（hello/welcome：协议版本/构建标识/能力集，P4-13）**；零 JavaFX（已纳入 businessPackagesHaveNoUiFrameworkDependency 扫描）
```

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
| `i18n` | `I18n`（**唯一文案入口**；界面走 `get` 具名占位符 `{name}`，日志走 `msg` 位置占位符 `{0}`，结构化警告走 `render(ExtractWarning)`；缺键返回 `!key!` + `log.error`）· `ErrorCode`（**22** 个，键 `error.<CODE>`）· 两份 bundle（**各 571 键**，`log.*` **243** 条） |
| `model` | `PreviewItem`（领域数据与 JavaFX 属性已分离）· `ThumbnailData`（持解码后 ARGB `int[]`，不用 `Image`/PNG 字节）· `ExtractWarning`（**结构化警告**：i18n 键 + 具名参数 `Map`，语言中立；E4.1）· **P3 起陆续迁入**：`GroupItem`、`ChangeListener`、`ExtractOutcome`、`ParamScope`（三态目标/生效参数解析）、`AppConfig`（参数数据模型，Jackson POJO）、`FingerprintEncoder`（指纹长度前缀编码唯一口径） |
| `platform` | `BrowserLauncher`（`openHtml` / `openViewSource` + 三级兜底）· `SourceViewPage`（源码视图页生成）· `CdpClient`（CDP 客户端 + 调试实例管理，原生 Socket，native 安全）· `BrowserDetector` · `FileOpener`（**用系统默认程序打开文件**，含 UAC 关闭导致 UWP 文件激活被阻断的环境预判）· `HtmlTextSelection`（选择/复制语义，纯函数）· `HtmlWrapMap`（折行几何 ↔ 逻辑字符偏移，纯函数） |

### app 层（**业务可脱离 UI 运行的验收物**）

```java
MhtExtractorApp              // 门面：importPaths/importFolder；extractText/extractImages（ProgressSink / CancelToken）
WorkspaceModel               // 数据序、分组关系、撤销命令的**唯一所有者**；GUI 的 items 是它的只读投影
WorkspaceParamService        // 参数变更/移除引起的缓存失效收口（GUI 不得直接调缓存删除规则）
WorkspaceImportService       // 导入轮次/累计/配额计数（无 JavaFX）
WorkspaceExtractionService   // 提取业务段；**业务统计的唯一来源**
PreviewService               // 预览缓存读写与命中判定
ExtractionPreconditions      // 提取前置校验（Level 分级 + Denial）
```

`LayerBTest`（无 JavaFX 执行）+ `StubUiAcceptanceTest` 证明"用非 JavaFX 的桩 UI 走完导入→校验→建组→参数→移除/撤销→提取"，
并与产物基线逐字节一致。

### GUI 层

- **`MainController`（1654 行）** —— 只做 FXML 事件接线、协作者装配、UI 状态呈现；**行数只减不增**（P3-B4 的 +8 已裁决记账）。
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
| `businessPackagesHaveNoUiFrameworkDependency` | 业务包 + `app` + `platform` + `model` **不得依赖 `javafx.*`**（字节码常量池口径，带白名单腐化检测） |
| `applicationLayerDoesNotDependOnViewOrShell` | `app` 不得 import `gui` / `logging` / `App` |
| `mainControllerIsShrinking` | `MainController` 行数（实测 **1654**）≤ 上限 **2240**。P3-B4 的 +8 已裁决接受；**此后只减不增** |
| `reflectionAgainstMainControllerIsZero` | 测试对 `MainController` 私有成员的**反射 = 0**（改用测试源集的同包 acceptance driver） |
| `testSupportSurfaceDoesNotGrow` | `/* test-support */` 标记 = **0**。⚠️ 该计数**只覆盖 `MainController` 文本** |
| `headlessTestPackagesHaveNoJavaFx` | 无头测试包源码不得出现 UI 框架字面量 |
| `businessLayerI18nUsageDoesNotGrow` | 业务层 `I18n.` 引用 ≤ 基线 **52**（实测 P4-1 后 **46**；P3-3b-20 后曾为 43，P4-1 的 `backend` 3 处计入；`MhtImportPlanner.Plan.summary` 随类迁出业务包扫描范围，故 45 → 43；剥掉 `log.*` 整段后再计数）。**P2-2b 起扫描范围剔除 `uicore`/`uiport`**（前端共用逻辑/端口层，不算业务层） |
| `noNewThirdPartyDependencies` | 不得新增第三方依赖（保护 native 打包） |
| `guiLayerDoesNotInvokeCacheRulesDirectly` | GUI 不得直接调预览缓存的删除规则（须经 `WorkspaceParamService`） |
| `guiDoesNotMutateDataOrderDirectly` | GUI 不得直接/间接改 workspace 数据序（须经 `WorkspaceModel`）；实测 **0 / 0** |
| `testAccessSurfaceIsFrozen` | 测试访问面冻结：`src/test/resources/test-access-surface.tsv` **88** 条（direct 79 / reflect 3 / reflect-dynamic 6；S2 曾一次性上调至 124，P2-1 删 5 条 HtmlFindIndex，P3-B1 删 `MainController.ITEM_VIEW`，P3-B2 删 2，P3-B5 删 2，P3-B6 删 6（`ExtractionTaskCoordinator` 下沉接入点）——**均因下沉后成员消失**，且不变式同时校验「发现 ⊆ 清单」与「无陈旧条目」，凭空删行必然失败）**只减不增**；发现集合 ⊆ 清单、清单无陈旧条目、`com.mhtextractor.gui` 同包驱动必须登记。扫描器 `baseline.TestAccessSurface`（字节码成员库 + 驱动类常量池引用；不用反射 API） |
| `allowlistOnlyTargetsBusinessPackages` · `baselineHarnessIsPresent` | 防白名单滥用 / 基线与夹具在位 |
| `uiportHasNoJavaFxDependency` | **P0 新增**：`uiport` 端口层字节码常量池零 `javafx/`（字节码扫描）。**P3-2 起包已建立，实测 47 类 / 0 违规**（P3-B1…B6 新增 `OutputUnitVm`/`ParamSchemaVm`/`PreparseResult`/`TargetId`/`ImportPlanVm`/`ExtractionChannelEvent` 族等）；基线违规数 **0**，扫描器 `baseline.UiportSignature` |
| `guiBusinessStateDoesNotGrow` | **P0 新增**：`gui` 层业务状态持有/构造点（`AppConfig` / `Map<String,AppConfig>` 字段 + 缓存/剪贴板/撤销载荷构造点（`new UndoManager.Record(...)` 含 `<>`））**只减不增**；基线 **0**（P1-3 后 a=b=c=d=0；P0 时 9 → P1-1 7 → P1-2 3 → P1-3 0，已提前达 P3 目标并保持只减不增）。扫描器 `baseline.GuiCoupling` |
| `guiDirectBusinessPackageUsageDoesNotGrow` | **P0 新增**：`gui` 源码直连业务包 distinct (包, 类型) 数 **只减不增**；基线 **0**（业务 import 0；P0 35 → … → P3-3b-24 1 → P3-3b-25 **0 归零**），**已达 P3 目标**。扫描器 `baseline.GuiCoupling` |
| `headlessNativeMetadataHasNoJavaFx` | **P4-3 新增（第 17 条）**：**无头 native 元数据零 JavaFX 条目**（解码 JSON + properties 文本，匹配 `javafx/` 与 `javafx.`）。后端子集在 `tools/native/headless/reachability-metadata.json`，由 `build-native.cmd` 的 `NATIVE_TARGET=headless` 使用；构建层另有 `tools/native/assert-headless-metadata.ps1` 独立断言（不满足则非零退出）。**既有七份元数据一字不动**（C-4 红线）。 |

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
  文档被改坏时用 `tools/recovery/recover-doc.js`（从 DSH transcript 切帧解压恢复）+ `verify-recovery.js` 核对。
- **`-Dfoo=bar` 会被 PowerShell 解析坏**（`ClassNotFoundException: /mode=true`）：写成 `.cmd` 或用参数数组 `@(...)`。
  DSH 传给 pwsh 的 **stdin 是空的**；中文走文件或 argv 是安全的。不要给自动变量 `$args` 赋值。
- **`.cmd` 的 `:label` 与函数体之间不能插任何东西**（`call :label` 会落空滑进下一个子程序 → 现象是"exit=0 却什么都没执行"）。
- **`logs/gui-state.json` 的 `locale` 覆盖 `-Duser.language`**；**`logs/test-results.txt` 必须核对 mtime**。
- 🚨 **本机 UAC 关闭（`EnableLUA=0`）**：会话全为高完整性 ⇒ Windows **静默阻断 UWP 的"文件激活"**
  （进程起来但无窗口、**退出码仍 0**，资源管理器里双击图片也打不开）。凡"用系统默认程序打开文件"一律走 `platform.FileOpener`。
- 🚨 **运行时/测试产物一律放 H 盘（2026-10-05 起）**：本项目产物放 `H:\TEST\mht\`、临时放 `H:\TEST\temp\`；跑 GUI / native / 探针 / 黑盒前设 **`-Djava.io.tmpdir=H:\TEST\temp`**（否则预览缓存 `mht-extractor-preview`、浏览器查看页 `mht-extractor-view` 会写进 `C:\Users\Admin\AppData\Local\Temp`）。**不要再把生成数据塞到 C 盘**，验收时断言 C 盘无新增生成数据。宿主目录 `.dsh` / `.agents-anywhere` / `.m2` / `.openjfx` 已是指向 `H:\TEST\*` 的 **junction**（旧 C 路径仍可用，新脚本请用 H 路径）；详见 `H:\TEST\MIGRATION-BRIEF.md`。
- **任何 `mvn clean` 不得与 GUI 自动化 / native 构建并发**（会互删 `target/`）。
- **不要整文件读取 `MainController.java`**（1654 行）：`grep -n` 定位 + 区间读。
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

**native 卡片冒烟：三处叠加缺陷全部已修（2026-10-08，`d9e0c7f` + `351f6de`）——「static 冒烟一律挂死」的旧说法作废**

① **脚本读/超时缺陷（已修，P4-7 `d9e0c7f`）**：旧 `native-card-smoke.ps1` 用顺序 `StandardOutput.ReadToEnd()` + `StandardError.ReadToEnd()` 再 `WaitForExit(timeout)` → **经典 stdio 死锁**，`-TimeoutSec` 对读管道**无效**，现象是 `[smoke] running probe ...` 后长时间零输出（曾挂 16 分钟）。**修法**：两路输出**重定向到文件**、立即限时等待、超时 `taskkill /PID … /T /F` + 返回 1、退出码用 Win32 `GetExitCodeProcess` 读取（本机 `$proc.ExitCode` 恒为空）。**harness 实测**：修后 static 冒烟 **30 s 内返回 exit=1**（不再假挂）。

② **`-OutDir` 从不清 DLL（已修 P4-8）**：冒烟脚本把 `-JfxDlls\*.dll` 拷进 `-OutDir`，但**从不清理 `-OutDir` 里已有的 DLL**；而 `build-native.cmd` 集成调用固定用持久目录 `.claude-work\native-card-smoke`。→ **dynamic 冒烟跑过之后再来 static**，残留的 7 个 JavaFX DLL 被加载 → `WinApplication._init` **段错误**（`0xC0000005`，`SegfaultHandler`） → **static 端到端 `build-native.cmd` FAILED（退出码 1）**。harness 已复现（`smoke-output.log.err` 里有段错误栈），且**实施方只在干净目录下验过**，其「static 通过」不覆盖集成路径。**已修（P4-8）**：构建前清 `-OutDir` 的 `*.dll`，再由 native-image 重新生成（`-SkipNativeBuild` 语义保留；harness 用**哨兵 DLL** 验证清理确实执行）。⚠️ **但它单独不够** —— 见 ③。

③ **真根因（P4-9 `351f6de`，已修并由 harness 复验）**：`build-native.cmd:236` 执行 `set "PATH=%BUILD_JAVA_HOME%\bin;%PATH%"`，而 **NIK JDK 的 `bin` 里含全部 7 个 JavaFX 原生 DLL**（`glass`/`prism_common`/`prism_d3d`/`prism_sw`/`decora_sse`/`javafx_font`/`javafx_iio`）→ **static 镜像运行时从 PATH 命中它们** → 与静态链接的 JavaFX 冲突 → `WinApplication._init` **段错误 `0xC0000005`** → `[smoke] FAILED` → static 集成构建 `exit 1`。**控制组**：只给 standalone 命令前置同一行 PATH，即**从 PASS 变 FAIL**。**修法**：static 配方的冒烟**子进程**重建 PATH 并剔除 `BUILD_JAVA_HOME\bin`/`GRAALVM_HOME\bin`（**dynamic 原样保留**，它本来就依赖这些 DLL）；顺带把误导性的 `Usual cause: missing JavaFX effect-peer reflection metadata` 改成指向 `smoke-output.log/.err`。**harness 复验**：集成 static 带冒烟 → `exit 0` + `[STEP SMOKE] END exit=0` + `[smoke] RESULTS: 5 passed, 0 failed`；集成 dynamic → `exit 0`、dist 22 文件/71.8 MB。

另：`NativeBaselineProbe` 的工作根必须用**仓库内相对路径**（绝对 `H:\...` 会 `AccessDeniedException`）；且**两个配方共用同一 dist 目录**——`NATIVE_RECIPE` 漏设或 dist 未清空都会让产物计数被污染。

**2026-10-08 收口轮（P4-10/11/12，提交 `a268a7b`）——四条口径与两条重要事实**

1. 🚨 **`testAccessSurfaceIsFrozen` 的扫描范围包含 `tools/probes/*.java`**：在**探针**里加反射（如 `getDeclaredField`/`setAccessible`）同样会算成**清单外新增访问点**而变红（P4-12 实测：ESC 观测曾因此把第 13 条打红）。→ **探针里尽量只用公开 API**；真需要动态访问就必须进冻结清单（且清单**只减不增**，得先去掉别的条）。
2. 🚨 **宿主命令行码页边界（cp936）**：路径里含**非 ANSI 码页字符**（韩文 `한글`、非 BMP emoji `😀`）时，**「命令行 → JVM argv」会把它们压成 `?`** → `java.nio.file.InvalidPathException: Illegal char <?>`（`CliArgs.parse` / `IpcFiles` 处崩）。**日文假名/汉字/全角/中文都在 cp936 内，故正常**。**控制组**：**绕过 node**、直接用 PowerShell 数组参数调 `BackendCli` **同样复现** → 是**宿主限制**，不是产品缺陷、也不是 node 的锅；但**所有 CLI 用户都会遇到**（GUI 走文件对话框不经 argv，不受影响）。矩阵把它归类为 **`LIMITATION(expected) reason=HOST_CP936_ARGV`**，不再当作 PASS/FAIL。缓解方向（未实现）：让 `--config` 这类 **UTF-8 文件**也能携带输入/输出路径，或支持 `@argfile`。
3. **C 盘「零新增」的正确口径 = 项目产物**：`C:\Users\Admin\AppData\Local\Temp` 下 **`mht-extractor-*` 新增 = 0**（`tools/ipc/project-temp-audit.mjs`，三态 PASS/FAIL/UNVERIFIED）。**整盘零新增在活主机上不可达**（DSH 会话快照 `dsh-workspace-changes-*`、VS 后台日志 `dd_BackgroundDownload_*` 都会写 C 盘）——脚本已改成分别计数并**显式列出未验证范围**。
4. **ESC 观测（`NotificationAcceptance`）**：默认改为 **`scroll-pane` 公开事件路由**（`Event.fireEvent(ModalPane 的直接 ScrollPane 子节点, KeyEvent)`），**零反射**；zh_CN / en_US 均 **`failures=0 unverified=0`**（非持久 `consumed=true` 关闭卡片 / 持久 `consumed=false` 保持打开，同一 skin 实例对照保留）。

**收口轮实测（harness 亲手）**：矩阵 16 格 = **8 PASS + 8 LIMITATION(expected)**、CG-3 **RED=FAIL GREEN=PASS**（红日志含 `TEXT_EXACT_GUARD`）；内存文本守门 字面=PASS/注入`?`=FAIL/撤销=PASS；C 盘 `projectStatus=PASS`；**en_US 全 14 探针 exit=0**；**无头 exe 重建后 native 42/42**；**无头 native IPC（`--serve`）CLIENT_EXIT=0 + TEXT_EXACT_MATCH + stderr 0 字节**；**static 构建 exit 0 + `[STEP SMOKE] exit=0` + 15 文件/99.4 MB + 0 个 JavaFX DLL**，**静态版 `BaselineProbeStatic`：`NATIVE-BASELINE: 42 passed, 0 failed`**（⚠️ 该次运行发生时基线总数为 **42**；**2026-10-09 P4-16 后已按 44 重跑并全绿**：`NATIVE-BASELINE: 44 passed, 0 failed`，见台账 §11）；**P4-7 超时杀树：`taskkill` SUCCESS + 无残留进程**。

**迁移/改包名时必须全仓库检索调用方（含 `tools/probes/`）**：P3-3b-19 把 `util.GroupItem` 迁到 `model` 后，`src` 内编译全绿，但 `tools/probes/ContextMenuProbe`/`ParamDraftProbe` 仍 import 旧包 → 两个探针**编译失败**（exit=1，易被误当功能回归）。**教训**：包迁移/签名变更后，除 `mvn test` 外必须 `grep -rn <旧全限定名> .`（覆盖 `tools/`）并**复跑受影响探针**。

**`HtmlViewerProbe` step5 hscroll 时序竞态：已定位并修复（2026-10-08，P4-6 `2f9be95`）**。
症状：`FAIL step5 hscroll: every selection rect stays inside the viewport (rects=2, inside=false)`（**只有这一条**会抖），抖动率曾达 **25%~50%**（P4-4 轮 1/4、P4-5 轮 2/4）。
**定性 = 探针时序（非产品缺陷）**：受控复现显示 631 px 视口下 cell 仍用旧 `[0,96)` 折行区间，首个选中矩形越界 **83.2 px**，产品排队刷新后几何恢复正常。
**修法**：新增异步步 `HSCROLL_READY`，在还原窗口尺寸后**等待渲染网格与加宽前捕获的网格一致**再断言（注释明写 *Do not wait on selection rectangles: those remain assertions*）；并修掉「轮询在 `cancel()` 后仍排队于 FX 线程 → **重复推进步骤**」的真 bug。
**断言 `rectsInside && rects >= 1` 与容差未变**；修前 62.5%(5/8) → 修后 **harness 亲手连跑 6 次 6× `166/0`**。→ **纪律保留：探针报红仍先复跑 ≥3 次再下结论**。

**P4-15（2026-10-09）：预览"永久加载态"根因与修复（harness 独立复现 + 控制组）**
- **根因**：加载态结束后 **`imageHintLabel` 无人复位**。`PreviewLoader` 在 `PreviewLoadStateChanged(active=false)` 时只调 `updateProgressStatus(...)`，而该实现（`PreparseController`）**只刷新预解析进度、不碰图片提示**；同时 app 层有两条"结束却不发终态事件"的路径（取消竞态 / `stale`）。
- **实测控制组**（自建 `PreviewHangProbe`，`.claude-work/verify/`）：修复前 **B（取消）/D（stale）`terminalEvent=false`**；修复后 **A–E 全部 `true`**、`loadingPath` 全为 null。证据：`H:\TEST\mht\p4-15-prefix-control.txt` 与 `p4-15-postfix-probe.txt`。
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
- **交付**：`app.BackendExecutable`（按"GUI exe 同目录 → jar 同目录 → cwd"定位随包后端，支持 `-Dmht.backend.executable`）+ `app.BackendSession`（异步启动 `--serve`、握手校验、`UNAVAILABLE` 降级）+ `gui.component.BackendConnectionPresenter`（连接状态呈现 + 确认应答桥）；`GuiConfirmer`/`NotificationHost` 承接"后端发起的确认"；`build-native.cmd` 把 `MhtExtractorBackend.exe` 复制进 dist（`BACKEND_DIST_DIR`，默认 `H:\TEST\mht\p4-3-headless-dist`；缺失只警告不失败）。
- **握手**：复用 `BackendLauncher`/`BackendIdentity`，校验协议版本 + 版本号 + **buildId** + **五项能力集**（workspace / setChecked / workspacePage / bulkFiles / confirmationRoundTrip）+ 确认标志；任一不符 → `UNAVAILABLE` 并提示（实测原文含 `buildId mismatch`、`Protocol version mismatch`、`handshake timed out after PT1S`、`welcome lacks required capabilities`）。
- **降级（关键）**：**后端缺失/失败时 GUI 照常可用**（列表/预览/提取仍走进程内 `app`），只是显示"后端不可用"——真实 `App.start` 缺后端与真实 native 缺后端两种情形都验过。
- **确认与超时**：后端经 IPC 发确认请求 → 应用内确认卡（键 + 具名参数）→ 回送 `confirmAnswer`；**卡片被顶替按"取消"交付**，旧句柄关不掉新卡；**断开即取消**、后端 30 s 到期（实测 `server-30s-timeout elapsedMs=29935`）与前端本地期限 +250 ms 兜底都会**复位状态**且不晾住 GUI。
- **本批不做**：GUI 业务**未**改走 IPC（仍走进程内 `app`），这是 P5/后续的范围。
- **harness 独立验收**：全量 **964/0/0 + headless 73/0**（`MVN_EXIT=0`）、`ArchitectureTest` 17/0、**GUI zh/en 34/0**、**16/16 探针 exit=0**；native：JavaFX dynamic `exit 0` + dist **23 文件/107.5 MB（含后端 exe）** + 卡片冒烟 `exit 0` + `状态 OK`、headless `exit 0`、**无头 native 44/44**、**NIK static 基线条 `NATIVE-BASELINE: 44 passed, 0 failed`**；i18n **571/571（差异 0）**、`log.*` **243**。
- 🚨 **教训（P4-16 的延伸）**：`build-native.cmd` 内部会跑 `mvn clean`，**它会删掉 `target/cp.txt`** → 因此必须在**紧接** `build-baseline-native.cmd` **之前**重建 `cp.txt`，否则基线条镜像缺依赖 → `NoClassDefFoundError: org.slf4j.LoggerFactory`（**第二次踩到**，仍是假红）。
- 🔧 **harness 紧急修正（已披露）**：本批把 `tools/native/native-card-smoke.ps1` 的默认 `-OutDir` 与 `build-native.cmd` 的 `NATIVE_SMOKE_DIR` **硬编码成 `H:\TEST\mht\native-card-smoke`** → ① 违反打包手册"任何 Windows 设备可打包"的承诺；② 破坏 `§6②`（P4-8）记录的"集成调用固定用持久目录 `.claude-work\native-card-smoke`"不变量。**修法**：恢复"**环境变量优先、否则仓库内 `.claude-work\native-card-smoke`**"；保留该批的两处好改动（探针 classes 落到 `-OutDir\probe-classes`、冒烟子进程用 `-WindowStyle Hidden`），并重建验证（`exit 0` + `[STEP SMOKE] END exit=0`）。

**P4-18 / V1（2026-10-09 会话）：三项便宜未验证项已补 + 可见性回收阶段二首批**
- **日志级语言纯净度（已补，此前只验过"屏上中文=0"）**：en 运行的应用日志 `logs/mht-extractor.log` **CJK=0**（43886 B），zh 控制组 **4278**（57729 B）。**只统计应用日志**；`logs/test-results*.txt` 的中文断言消息按 §4 不计入。统计口径与注入控制组（clean=0 / injected=2 / CLI red exit=1）见 `.claude-work/p4-18-report.md`；可复跑入口 `node .claude-work/p4-18.mjs count <log>`。
- **点击遮挡层不关确认卡（已补）**：`NotificationAcceptance` 用公开 `Event.fireEvent` 向 AtlantaFX `ModalPane` 的**唯一直接 `ScrollPane` 遮罩**派发真实 `MouseEvent`（零反射），zh/en 各连跑 3 次 **`failures=0 unverified=0`**；含"投递未到达"与"持久性改 false"两组红控制。与 ESC（P4-12）同一套公开事件路由。
- **`>50MB` 大文件确认分支（已补）**：新增 `tools/probes/LargeFileConfirmationProbe`（真实 `PreviewLoader → InProcessUiport → WorkspaceActivityService → PreviewService → NotificationHost` 卡片），**20 条断言 × zh/en 全绿**，含恰好 50 MiB **不**确认 / +1 需确认、取消不加载不缓存、确认后真实 PNG 解码；两组红控制。**仍未覆盖**：从完整 `MainView` 手工导入该夹具、巨大图片自身的内存压力。
- 🚨 **`NonDaemonExitProbe` 是「判据 ≠ 通过」的探针**：它故意起一个睡 **600 s** 的非 daemon 线程，源码判据是"JVM 应 **60 s 内**自退"；而 `run-probe.ps1` **不设**该超时，于是它必然等约 600 s 才自然 `exit=0`。**`exit=0` 不能当作"关窗后无残留风险"通过**；生产关窗生命周期仍以 `BusyCloseProbe` / `PendingConfirmCloseProbe` 为准。（会话内限时杀树 `taskkill /T /F` 被宿主拒绝，无法用超时终止来测量。）这是本批发现的**判据/运行器不匹配**，非产品回归。
- 🔧 **harness 修正（已披露）**：批次运行器原在 `tools/probes/p4-18.mjs`，但它**硬断言**证据根必须落在 `h:\test\mht\` 下（机器专有）→ 违反 `tools/` 的分发/可移植承诺，已移入 **`.claude-work/`**（本地草稿区，与 `run-matrix.ps1` 同类）。`LargeFileConfirmationProbe` 默认夹具路径仍为本机 `H:\TEST\mht\p4-18\fixtures`（可用 `-Dprobe.fixtures` 覆盖），与既有探针一致。
- **V1（可见性回收阶段二首批）**：探针/测试反射归零，测试访问面 **108 → 88**（迁移 20/29）；新增 `baseline.AppConfigFieldContract` 以**字节码字段表**保住"AppConfig 新增字段必须同步签名/深拷贝"守护（零反射）。剩余 **9 条**无等价公开观察路径（`SidePanelProbe.field/fieldDeep/invoke`、`HtmlViewerProbe.invoke/invokeStatic`、`ContextMenuProbe.fieldOf`、`MainController.contextMenu/refs`、`FileListContextMenu.actions`）——**保留原断言与清单登记，未扩生产 API、未改可见性**。详见 `.claude-work/v1-report.md`。

**P4-19（2026-10-10）：native 元数据缺口 `PreviewCacheMeta$ImageEntry[]`（用户 native 实测预览失败）**
- **现象**：native dynamic 版图片提取模式点「查看预览效果」→ `解析失败: MissingReflectionRegistrationError: Cannot reflectively instantiate the array class 'com.mhtextractor.cache.PreviewCacheMeta$ImageEntry[]'`；JVM 完全正常（native-only）。
- **根因**：触发在**写缓存** `PreviewCacheStore.write` 的 `MAPPER.writeValue`；Jackson 为 record 集合属性建 serializer 时经 `PotentialCreator.introspectParamNames` 反射实例化该**数组类**，镜像未注册。**只在 `images` 为空时触发**（用户那条正好"没有找到图片资源"）。这也解释了"**没弹卡片**"：失败在 `rebuild` 写盘阶段，`completePreview`→`PreviewNotices` 从未执行；通用解析失败按 `PreviewLoader` 语义本就只内联显示（**不是**弹卡片）。
- **修复**：`reachability-metadata.json` 纯增量注册 `PreviewCacheMeta$ImageEntry[]`（agent 差集确认的唯一真实缺失项）+ 一条 agent 采集的 resource glob；对账丢失数 0。
- **红/绿控制组（真 native）**：新增 `tools/native/NativePreviewCacheProbe.java` + `tools/native/native-preview-cache-smoke.ps1`（把探针**编成 native 镜像**再跑；`-MetadataDir` 切修复前快照做红组）。**红** = 报错逐字一致、栈顶 `PreviewCacheStore.write`；**绿** = `2 passed, 0 failed`（含 `write/load round-trip`）。🚨 **只调 `load` 的探针在修复前也通过**——该缺口只由**序列化**触发，控制组必须含 `write`。
- **验证**：`build-native.cmd` exit 0 + report `status=OK` + 卡片冒烟 exit 0，dist 更新 `D:\JAVA\mht-resource-extractor-dist`；`audit-native-metadata.ps1` `STALE = 0 / 590`；全量 `mvnw clean test` **964/0 + 73/0**。
- 🔧 **harness 修正（已披露）**：冒烟补 `-H:+JNI` 与 `-H:NativeLinkerOption=prism_common.lib`（NIK 静态库配方必需，否则 LNK2001）、`-o x.exe` → `-H:Path/-H:Name`（避免 `.exe.exe`）；native-only 探针从 `tools/probes/` 移入 `tools/native/`（不进 JVM 探针集）。
- **未验证**：用户那次真实 GUI 点击未在 native 下自动复现（用等价探针覆盖同一 `write` 路径与同一数组类），请以新 dist 重测。

**验证脚本自身也可能是错的**：
`mvn -q` 吞过 surefire 摘要；正则漏过以分号结尾的枚举常量；`$null -eq $int` 不可靠；
聚合报告时用 `-notlike '*output*'` 把 `output.ImageWriterTest` 一并滤掉（少算 38 个）；`$proc.ExitCode` 在本机**恒为空**
（曾把 9 个全绿探针误报为失败）。**对"不一致"要再核一遍再下结论。**

**三档闸门（每个改动都要过）**

| 档 | 内容 | 判据 |
|---|---|---|
| **A**（分钟级） | `compile` + 定向单测 + 结构事实（行数 / 标记 / 直写） | 全绿；无"重排整文件"型 diff |
| **B**（每阶段） | `mvn clean test` + 独立聚合 + `ProductBaselineTest` + `ArchitectureTest` + GUI 自动化 + 探针 + 日志键对账 | 默认 **964/0** + headless **73/0**；产物基线 **44/0**；不变式 **17/0**；GUI zh/en **34/0**；探针 **16 原有** + **`LargeFileConfirmationProbe`（P4-18 新增）** 全绿（⚠️ `NonDaemonExitProbe` 判据为「60 s 内自退」而 `run-probe.ps1` 不设该超时，实际约 600 s 自然退出，`exit=0` **不得**当作「无残留风险」通过，见本节 P4-18 条）；`log.*` 键逐键不变（P4-15/16/17 只新增键）|
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
| `tools/native/native-preview-cache-smoke.ps1` | **P4-19 新增**：把 `tools/native/NativePreviewCacheProbe.java` 真的编成 native 镜像并跑（`load` 空/非空 + `write` 回读）；`-MetadataDir` 切快照做红组 |
| `tools/recovery/*` | 文档被误替换后的恢复与等价核对 |
| `tools/drive/drive-claude.mjs` | 驱动 Claude Code CLI（多候选解析 exe 路径；提示词走文件，不走 stdin） |
| `tools/ipc/client.mjs` | **P4-2b 新增**：无头后端 IPC 脚本客户端（Node 内置模块；起 `--serve` → 导入→分组→设参→提取 → 收事件 → 干净退出；stdout/stderr 分流） |
| `tools/ipc/verify-gui-backend.ps1` | **P4-17 新增**：GUI 随包后端（拉起/握手/确认/断连）的端到端验证脚本 |
| `tools/diag/tee-probe.cmd` | 「命令行 tee 最小复现」：定位 `call :label` 落空类问题 |
| `tools/launcher/make-launcher.ps1` | 生成 dynamic 产物的 `启动.cmd` / `Launch.cmd`（修"中文路径下启动失败"） |

**测试怎样访问生产控制器**：生产类**不为测试开放状态面**（`test-support` 标记 = 0）。两条正路：
① 测试源集的同包 driver `com.mhtextractor.gui.controller.AcceptanceDriver`（**直访，无反射**；`MainController` 现共 **49** 个包级成员、其中 **30** 个被驱动面访问，全测试访问面 **88** 条由第 13 条不变式 `testAccessSurfaceIsFrozen` 冻结）；
② 真正的公开行为（如 `WorkspaceModel` 的公开 API）——**优先用这条**。⚠️ **探针也在访问面扫描范围内**（见 §6）：探针里加反射同样会把第 13 条打红（P4-12 实测），所以**探针也优先走公开 API**（例如用 `Event.fireEvent` 派发真实事件，而不是反射调私有 handler）。

### 派发实施方：Claude Code CLI 与 Codex CLI（**长活走 detach**，2026-10-08）

两条实施通道：`tools/drive/drive-claude.mjs`（Claude Code CLI）与 **`codex_run` 工具**（Codex CLI，底层是 `.claude-work/codex/drive-codex.mjs`）。
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

---

## 8. 依赖

Jakarta Mail 2.0.2（MIME）· Jsoup 1.18.1（HTML）· Jackson 2.18.6（JSON）· **glavo webp 0.2.0**（纯 Java WebP 解码，无 JNI）·
AtlantaFX 2.1.0（主题）· Logback 1.5.26 · Ikonli 12.3.1 · JUnit 6.0.3 · Lombok 1.18.44

> **新增依赖前务必三思**：项目要出 GraalVM native EXE，每个新依赖都可能带来反射 / JNI / 资源元数据负担。
> `ArchitectureTest.noNewThirdPartyDependencies` 会直接失败。加图标常量前先确认 `ikonli-fontawesome5-pack` 里有。

---

## 9. native 打包现状（摘要）

- **产物**：GraalVM native EXE（`MhtExtractor.exe` + DLL），绿色免安装；**默认 `dynamic` 23 文件 / ≈108 MB**（P4-17 起随包 `MhtExtractorBackend.exe`），
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
- `MainController` 已从 3875 行降到 **1654** 行（P3-B4 的 +8 已裁决记账）：参数域随**步骤 1** 外迁到 `ParamDomainController`（579 行）、
  提取编排随**步骤 2** 收口到 `ExtractionTaskCoordinator`（854 行），残余为 GUI 事件接线与状态呈现；
  `FeatureExercise` 已于步骤 6 拆分（演练 → `AgentExercise`、共享底座 → `FeatureExerciseSupport`、
  业务断言 → 各业务包 JUnit；1633 → 当前 **1058** 行）。
- **2026-10-05 本轮交付**：查看器「提取标题」可编辑并覆盖输出命名（`2d874ea`）、查找全部命中底色 + Chrome 配色分层（`dcdcbb7`）、
  右键菜单文案（`96285bd`）、「只提取选中的列表项」开关 + 右键「应用到所选」5 档动态文案（`5ca1908`）；
  文档收敛为四份、其中两份临时文档不入远程（`c8a700c` / `d31a116` + 本次提交）。
- 测试控制器的通道是**同包直访**（`AcceptanceDriver`）。口径已拍板为**选项 D**：不数可见性，数**实际被测试访问的
  (生产类, 成员) 点**，由第 13 条不变式 `testAccessSurfaceIsFrozen` 冻结（清单 **88** 条；见 §3 与
  《遗留目标与未完成事项.md》§1.6 / §2.4）。**本期只冻结、不回收** `private`。
