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
| 重构阶段 | A1 / A2 / B / C / D / E / F **全部实施并验收**；第二批 **S0–S6（E1–E5）** 亦已实施并验收；《UI可切换重构方案》**P0（三条度量/不变式）与 P1-1…P1-5（全局参数缓存 + 预览缓存构造 + 侧边栏会话基线 + 参数剪贴板 + 撤销载荷 + 启动前顺序门链下沉 app，闸门 2 已归零）已实施并验收**；**P2-1/P2-2（抽 ui-core：`ExtractOutcomeStyles` / `HtmlFindIndex` / `ParamTargetText` / `FileMenuModel` 下沉 `com.mhtextractor.uicore`，零 JavaFX）已实施并验收**；**P3-1（端口能力覆盖矩阵草案：167 条 → 覆盖 42 / 前端 92 / 缺口 33）· P3-2（`com.mhtextractor.uiport` 最小切片 API 定稿，19 类 / 0 违规）· P3-3a（`app.InProcessUiport` 进程内适配器，工作区可回答子集 + 端口诚实化收敛）· P3-3b-1（产物命名规则下沉 `app.OutputNameService`；gui 直连业务类型 34 → 32）· P3-3b-2（预览缓存键计算与每项目写锁下沉 `app.PreviewService`；直连 32 → 30；`MainController` 1706 → 1705）· P3-3b-3（提取运行时配置 + 输出单元计划下沉 `app.ExtractionPlanService`；直连 30 → 29；`MainController` 1705 → **1679**）· P3-3b-4（「查看源码」导出下沉 `app.SourceViewService`；直连 29 → 28）· P3-3b-5（会话状态持久化下沉 `app.SessionStateService`；直连 28 → 26；`MainController` 1679 → 1677）· P3-3b-6（预览命名/扩展名纯函数下沉 `app.PreviewNamingService`；直连 26 → 23）· P3-3b-7（预解析解析+取正文/标题下沉 `app.SourceParseService`；直连 23 → 20）· P3-3b-8（命名配置 + 像素打包两单例收口 `app.NamedConfigService`/`app.PreviewPixelService`；直连 20 → 18）· P3-3b-9（提取每文件覆盖映射构造下沉 `app.ExtractionPlanService.overrides`；直连 18 → 17）· P3-3b-10（gui 去除 `i18n.ErrorCode` 直连，改写为 `I18n.get("error.<NAME>")`；直连 17 → 16）· P3-3b-11（gui 去除 `extractor.ImageExtractor` 直连，改用 `app.PreviewImageData`；直连 16 → 15）· P3-3b-12（gui 去除 `util.SelectionState` 直连，改用 `app.SelectionRules`；直连 15 → 14）· P3-3b-13（提取结果回写下沉 `app.ExtractResultService`；直连 14 → 13）· P3-3b-14（`util.ExtractOutcome` 迁入 `model`；直连 13 → 12）· P3-3b-15（`util.ChangeListener` 迁入 `model`；直连 12 → 11）· P3-3b-16（预览缓存生命周期经 `app.PreviewSession` 门面收口；直连 11 → 10）· P3-3b-17（`PreviewResult` 增中性 `ImageRef`/`images()`，gui 去除 `cache.PreviewCacheMeta` 直连；直连 10 → 9）· P3-3b-18（gui 去除 `util.GroupStructure` 直连，改用 `app.WorkspaceStructure` 包装；直连 9 → 8）已实施并验收**（2026-10-06，未收口项见《遗留目标与未完成事项.md》） |
| `MainController` | **1677** 行（上限常量 **2240**，只减不增；为不变式自报口径） |
| 测试 | 默认执行 **863**（+ `headless-layer-b` **68**）；架构不变式 **16** 条；产物黄金基线 **42** 份 |
| GUI 自动化 | zh / en **各 34 passed / 0 failed**；`tools/probes/` 探针全绿 |
| i18n | zh_CN / en_US **各 547 键**（两份键集与占位符集合必须一致）+ **22** 个 `ErrorCode`（本轮新增 `NO_CHECKED_ITEMS`） |
| 日志键 | `log.*` **236 键 / 247 调用点**（逐键不变，重构不得偷改日志文案；2026-10-05 重数，本轮新增 `log.maincontroller.39`）；全部 `I18n.get/msg` 调用点 **442** |
| native | 默认 `dynamic`：**22 文件 ≈70 MB**；`static`：15 文件 ≈98 MB |
| 规模 | 主源集 **150** 个 java 文件（其中 `gui` **31**、`uicore` **4**、`uiport` **16**、`app` **22**）/ 测试源集 **90** 个（2026-10-06 P3-3b-1 实测） |

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
```

### 业务包

| 包 | 职责 |
|---|---|
| `core` | `MimeParser`（MIME 树 → `ParsedMht`；递归上限 100；编码恢复 UTF-8/GBK/GB18030…）· `ContentExtractor`（Jsoup → 纯文本 / 标题）· `HtmlFolderParser`（HTML + 侧车）· `SourceParsers`（按 `SourceKinds` 选解析器）· `ChapterGenerator`（中文 `第一章` / 西文 `Chapter 1`，**0-based**） |
| `extractor` | `BaseExtractor` · `TextExtractor` · `ImageExtractor`（区域限定 / 广告过滤 / CID+Location 去重 / Data URI / 魔数扩展名）· `ImageSource` + `InMemoryImageSource` |
| `processor` | `BatchProcessor`（基类：收集、进度、取消、`ProcessStats`）· `TextBatchProcessor` · `ImageBatchProcessor`（流式/预加载 × 合并/单独；策略组件 `MergedImageOutput` / `SeparateImageOutput` / `PreloadedImageInput` / `ParsedMhtProvider` / `ImageOutputHandler`；**缓存命中即跳过解析与提取**） |
| `output` | `FileWriter`（模板变量 `{filename}` / `{datetime}` / `{date}` / `{time}`）· `ImageWriter`（`CREATE_NEW` 原子写、唯一名、魔数纠正）· `Archiver`（ZIP/CBZ） |
| `util` | `SourceKinds`（**来源扩展名与侧车后缀唯一入口**）· `FingerprintEncoder`（指纹/签名的长度前缀编码**唯一口径**）· `OutputUnitPlanner` + `GroupStructure` + `GroupItem` · `DropResolver` · `UndoManager` · `MhtImportPlanner` · `IncrementalImport` · `SelectionState` · `UnitProgressAggregator` · `ParamScope` · `SanitizeUtils` / `EncodingUtils` / `FileUtils` / `HtmlUtils` / `HtmlExportBuilder` / `ThumbnailPixels` / `ExtractOutcome` |
| `cache` | **预览缓存（会话级）**：`PreviewCacheKey`（绝对路径+size+mtime+参数指纹+格式版本 → SHA-256 目录名）· `PreviewCacheMeta`（`meta.json` schema）· `PreviewCacheStore`（`.tmp`→改名→`.complete` 原子写；`imageFileFor(meta, index)` 由 meta 反推缓存图片路径）· `PreviewCacheLifecycle`（**四条删除规则集中在此** + 每项目写锁 + 10 GiB 台阶提醒）· `DirectoryImageSource` |
| `config` | `AppConfig`（Jackson POJO；命名配置只存提取参数，`inputFolder`/`outputFolder`/`imageModeEnabled`/`openFolderAfterComplete` 为 `@JsonIgnore` 运行时字段；含 `imageExtractionFingerprint()`）· `ConfigManager` · `GuiState` / `GuiStateManager`（`logs/gui-state.json`：模式/目录/开关/**locale**） |
| `i18n` | `I18n`（**唯一文案入口**；界面走 `get` 具名占位符 `{name}`，日志走 `msg` 位置占位符 `{0}`，结构化警告走 `render(ExtractWarning)`；缺键返回 `!key!` + `log.error`）· `ErrorCode`（**22** 个，键 `error.<CODE>`）· 两份 bundle（**各 547 键**，`log.*` **236** 条） |
| `model` | `PreviewItem`（领域数据与 JavaFX 属性已分离）· `ThumbnailData`（持解码后 ARGB `int[]`，不用 `Image`/PNG 字节）· `ExtractWarning`（**结构化警告**：i18n 键 + 具名参数 `Map`，语言中立；E4.1） |
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

- **`MainController`（1677 行）** —— 只做 FXML 事件接线、协作者装配、UI 状态呈现；**行数只减不增**。
  列表单元格渲染/拖拽、预览渲染/加载、分组动画、移除/撤销/窗口生命周期、预解析、信息展示均已外迁。
- **既有协作者（沿用，不要重造平行框架）**：`MainViewRefs` / `MainViewInstaller` / `ListInteractionController` +
  `MainViewActions` / `PreviewListCell` / `PreviewRenderer` / `PreviewLoader` / `SourceViewActions` /
  `GroupCommandController` / `WorkspaceLifecycleController` / `PreparseController` / `WorkspaceInfoPresenter` /
  `ImportSessionCoordinator` / `ExtractionTaskCoordinator` / `FileListContextMenu` / `ExtractOutcomeStyles` /
  `ThumbnailFxBridge` / `DragGeometry` / `GuiNotifications`。
- **参数 UI**：右侧非模态侧边栏（`ParamSidePanel` / `ParamFormPane` / `ParamPanelActions` / `ParamClipboard` /
  `ParamTargetBar` + `ParamTargetText`）。**模态参数窗口已彻底删除，不得以弹窗作为兜底路径。**
- **HTML 源码查看器**：`HtmlSourceViewer` + `HtmlFindIndex` + `platform.HtmlTextSelection` + `platform.HtmlWrapMap`
  （软换行；行号每逻辑行一次并与折行对齐；选择/查找/复制共用同一套逻辑偏移）。**不引入 `WebView`**（**仅约束 JavaFX 前端**；换框架后按新框架实际评估 —— 见《UI可切换重构方案》§14 决策 16），不另造 tokenizer。
- **提醒**：`gui.component.NotificationHost` 是**应用内提醒唯一入口**（生产代码**零模态 `Alert`**）。
- **日志进界面**：`LogTextArea` + `TextAreaAppender`（Logback consumer）。
- `MainView.fxml` / `ParamDialog.fxml` 文案全部是 `%key`（`loader.setResources(I18n.bundle())`），**不得出现中文字面量**。

### 架构红线（`ArchitectureTest` 的 16 条不变式）

| 不变式 | 含义 |
|---|---|
| `businessPackagesHaveNoUiFrameworkDependency` | 业务包 + `app` + `platform` + `model` **不得依赖 `javafx.*`**（字节码常量池口径，带白名单腐化检测） |
| `applicationLayerDoesNotDependOnViewOrShell` | `app` 不得 import `gui` / `logging` / `App` |
| `mainControllerIsShrinking` | `MainController` 行数（实测 **1677**）≤ 上限 **2240**，**只减不增** |
| `reflectionAgainstMainControllerIsZero` | 测试对 `MainController` 私有成员的**反射 = 0**（改用测试源集的同包 acceptance driver） |
| `testSupportSurfaceDoesNotGrow` | `/* test-support */` 标记 = **0**。⚠️ 该计数**只覆盖 `MainController` 文本** |
| `headlessTestPackagesHaveNoJavaFx` | 无头测试包源码不得出现 UI 框架字面量 |
| `businessLayerI18nUsageDoesNotGrow` | 业务层 `I18n.` 引用 ≤ 基线 **52**（实测 E4.1 后 **45**；剥掉 `log.*` 整段后再计数）。**P2-2b 起扫描范围剔除 `uicore`/`uiport`**（前端共用逻辑/端口层，不算业务层） |
| `noNewThirdPartyDependencies` | 不得新增第三方依赖（保护 native 打包） |
| `guiLayerDoesNotInvokeCacheRulesDirectly` | GUI 不得直接调预览缓存的删除规则（须经 `WorkspaceParamService`） |
| `guiDoesNotMutateDataOrderDirectly` | GUI 不得直接/间接改 workspace 数据序（须经 `WorkspaceModel`）；实测 **0 / 0** |
| `testAccessSurfaceIsFrozen` | 测试访问面冻结：`src/test/resources/test-access-surface.tsv` **119** 条（direct 90 / reflect 9 / reflect-dynamic 20；S2 曾一次性上调至 124，P2-1 删除 5 条 HtmlFindIndex）**只减不增**；发现集合 ⊆ 清单、清单无陈旧条目、`com.mhtextractor.gui` 同包驱动必须登记。扫描器 `baseline.TestAccessSurface`（字节码成员库 + 驱动类常量池引用；不用反射 API） |
| `allowlistOnlyTargetsBusinessPackages` · `baselineHarnessIsPresent` | 防白名单滥用 / 基线与夹具在位 |
| `uiportHasNoJavaFxDependency` | **P0 新增**：`uiport` 端口层字节码常量池零 `javafx/`（字节码扫描）。**P3-2 起包已建立，实测 19 类 / 0 违规**；基线违规数 **0**，扫描器 `baseline.UiportSignature` |
| `guiBusinessStateDoesNotGrow` | **P0 新增**：`gui` 层业务状态持有/构造点（`AppConfig` / `Map<String,AppConfig>` 字段 + 缓存/剪贴板/撤销载荷构造点（`new UndoManager.Record(...)` 含 `<>`））**只减不增**；基线 **0**（P1-3 后 a=b=c=d=0；P0 时 9 → P1-1 7 → P1-2 3 → P1-3 0，已提前达 P3 目标并保持只减不增）。扫描器 `baseline.GuiCoupling` |
| `guiDirectBusinessPackageUsageDoesNotGrow` | **P0 新增**：`gui` 源码直连业务包 distinct (包, 类型) 数 **只减不增**；基线 **8**（业务 import 70 仅作诊断；P0 35 → … → P3-3b-17 9 → P3-3b-18 8），P1–P3 逐阶段下调，P3 目标 0。扫描器 `baseline.GuiCoupling` |

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
- **不要整文件读取 `MainController.java`**（1677 行）：`grep -n` 定位 + 区间读。
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

**验证脚本自身也可能是错的**：`mvn -q` 吞过 surefire 摘要；正则漏过以分号结尾的枚举常量；`$null -eq $int` 不可靠；
聚合报告时用 `-notlike '*output*'` 把 `output.ImageWriterTest` 一并滤掉（少算 38 个）；`$proc.ExitCode` 在本机**恒为空**
（曾把 9 个全绿探针误报为失败）。**对"不一致"要再核一遍再下结论。**

**三档闸门（每个改动都要过）**

| 档 | 内容 | 判据 |
|---|---|---|
| **A**（分钟级） | `compile` + 定向单测 + 结构事实（行数 / 标记 / 直写） | 全绿；无"重排整文件"型 diff |
| **B**（每阶段） | `mvn clean test` + 独立聚合 + `ProductBaselineTest` + `ArchitectureTest` + GUI 自动化 + 探针 + 日志键对账 | 默认 **863/0** + headless **68/0**；产物基线 **42/0**；不变式 **16/0**；GUI zh/en **34/0**；`log.*` 键逐键不变 |
| **C**（阶段收尾） | native 构建 + native 产物基线 + 截图对比 + i18n 键 | `build-native.cmd` exit 0、report `status=OK`；native 基线 **42/0** |

**没实际跑过的项目要明确标为"未验证"，不许推断通过。**

---

## 7. 验证工具（`tools/`，随仓库分发）

| 工具 | 用途 |
|---|---|
| `check-env.cmd`（仓库根） | 打包机第一步：9 项环境预检 + 修复命令（0=就绪 / 20=缺 MSVC / 1=缺其它） |
| `build-native.cmd` | native 构建**唯一实现**（`.ps1` / `.sh` / `run-native-image.bat` 只是委托入口） |
| `tools/probes/run-probe.ps1` | 编译并运行 UI 探针（自动推导 JavaFX module-path 与 classpath；`target/` 缺失时自动 `mvn package`；`-List` 列全部探针） |
| `tools/probes/*.java` | `UiProbe`（布局截断 / 屏上中文字符）· `SidePanelProbe` · `HtmlViewerProbe`（含真实剪贴板逐字符比对）· `ContextMenuProbe` · `ParamDraftProbe` · `NotificationAcceptance` · `BusyCloseProbe` · `PendingConfirmCloseProbe` · `NonDaemonExitProbe` · `ModalPaneStateProbe` · `IconShowcaseProbe` · `MainViewRefsProbe` · `AppCardProbe` |
| `tools/native/audit-native-metadata.ps1` | 元数据**过时项扫描 + agent 差集**（防丢条目 / 防幻影条目） |
| `tools/native/native-card-smoke.ps1` | native 提醒卡片渲染冒烟（`build-native.cmd` 的 `[STEP SMOKE]` 调用） |
| `tools/recovery/*` | 文档被误替换后的恢复与等价核对 |
| `tools/drive/drive-claude.mjs` | 驱动 Claude Code CLI（多候选解析 exe 路径；提示词走文件，不走 stdin） |
| `tools/diag/tee-probe.cmd` | 「命令行 tee 最小复现」：定位 `call :label` 落空类问题 |
| `tools/launcher/make-launcher.ps1` | 生成 dynamic 产物的 `启动.cmd` / `Launch.cmd`（修"中文路径下启动失败"） |

**测试怎样访问生产控制器**：生产类**不为测试开放状态面**（`test-support` 标记 = 0）。两条正路：
① 测试源集的同包 driver `com.mhtextractor.gui.controller.AcceptanceDriver`（**直访，无反射**；`MainController` 现共 **49** 个包级成员、其中 **30** 个被驱动面访问，全测试访问面 **119** 条由第 13 条不变式 `testAccessSurfaceIsFrozen` 冻结）；
② 真正的公开行为（如 `WorkspaceModel` 的公开 API）——**优先用这条**。探针另走 `getDeclaredField + setAccessible`。

---

## 8. 依赖

Jakarta Mail 2.0.2（MIME）· Jsoup 1.18.1（HTML）· Jackson 2.18.6（JSON）· **glavo webp 0.2.0**（纯 Java WebP 解码，无 JNI）·
AtlantaFX 2.1.0（主题）· Logback 1.5.26 · Ikonli 12.3.1 · JUnit 6.0.3 · Lombok 1.18.44

> **新增依赖前务必三思**：项目要出 GraalVM native EXE，每个新依赖都可能带来反射 / JNI / 资源元数据负担。
> `ArchitectureTest.noNewThirdPartyDependencies` 会直接失败。加图标常量前先确认 `ikonli-fontawesome5-pack` 里有。

---

## 9. native 打包现状（摘要）

- **产物**：GraalVM native EXE（`MhtExtractor.exe` + DLL），绿色免安装；**默认 `dynamic` 22 文件 / ≈70 MB**，
  `set NATIVE_RECIPE=static` 换 NIK 静态配方（15 文件 / ≈98 MB）。两种配方产物**逐字节等价**（native 基线 **42/0**）。
- **三条必须记住的**：
  ① JavaFX DLL 是否随包**取决于配方**（dynamic **必须**随包；static **一个都不能放**，否则 `WinApplication._init` 段错误）；
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
  过时项用 `tools/native/audit-native-metadata.ps1` 审计。改元数据后**必须**做 42 份产物基线回归。

> 构建命令、配方细节、验收清单、故障排查与全部实测坑 → **《基于graalvm的MHT资源提取器项目打包工作流程.md》**。

---

## 10. 已知缺陷与遗留目标

**完整台账见《遗留目标与未完成事项.md》**。当前登记的主要几类：

- ✅ **已修复（E4.1 / S5）**：业务层不再产已解析文案，改产 `model.ExtractWarning`（键 + 具名参数）；
  `PreviewCacheMeta.warnings` 语言中立，展示层经 `I18n.render(...)` 按当前 locale 渲染；`FORMAT_VERSION` 已 2 → 3。
- ✅ **已修复（E4.2 / S4）**：`AD_KEYWORDS` 仍硬编码，但 `AppConfig.IMAGE_EXTRACTION_RULES_VERSION` 已参与
  `imageExtractionFingerprint()`；**改关键词清单 / 去重口径时必须 +1**，否则预览缓存串味。
- `MainController` 已从 3875 行降到 **1677** 行：参数域随**步骤 1** 外迁到 `ParamDomainController`（579 行）、
  提取编排随**步骤 2** 收口到 `ExtractionTaskCoordinator`（854 行），残余为 GUI 事件接线与状态呈现；
  `FeatureExercise` 已于步骤 6 拆分（演练 → `AgentExercise`、共享底座 → `FeatureExerciseSupport`、
  业务断言 → 各业务包 JUnit；1633 → 当前 **1058** 行）。
- **2026-10-05 本轮交付**：查看器「提取标题」可编辑并覆盖输出命名（`2d874ea`）、查找全部命中底色 + Chrome 配色分层（`dcdcbb7`）、
  右键菜单文案（`96285bd`）、「只提取选中的列表项」开关 + 右键「应用到所选」5 档动态文案（`5ca1908`）；
  文档收敛为四份、其中两份临时文档不入远程（`c8a700c` / `d31a116` + 本次提交）。
- 测试控制器的通道是**同包直访**（`AcceptanceDriver`）。口径已拍板为**选项 D**：不数可见性，数**实际被测试访问的
  (生产类, 成员) 点**，由第 13 条不变式 `testAccessSurfaceIsFrozen` 冻结（清单 **119** 条；见 §3 与
  《遗留目标与未完成事项.md》§1.6 / §2.4）。**本期只冻结、不回收** `private`。
