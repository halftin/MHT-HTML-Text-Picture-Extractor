# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **最后更新：2026-10-02**（**GraalVM native 打包首次实机打通并逐字节验证** —— 见文末「native 打包现状」与《基于graalvm的MHT资源提取器项目打包工作流程.md》§十）+ 架构重构 + 全量国际化 + 预览缓存持久化 + **WorkspaceModel 接入生产：阶段 1b/2/3/4 全部完成** + **预览区"单击缩略图用系统默认图片查看器打开缓存图片"恢复** + **交互提醒从模态 Alert 全量替换为应用内通知** + **修复「移除项目后用同目录无法再加回来」：导入去重口径改为可见项** + **P7 测试面收口：生产控制器不再为测试开放状态面，`-Dtest.mode` 与生产启动代码解耦**）
> 文档集**只有三份**：**本文件**（工程约定，改代码前必读）+ **两份 GraalVM 打包文档**（构建 / 打包机操作）。
> 历史会话交接（H19–H36、架构重构最终报告、任务规划、新会话启动包等 20 份）已从仓库移除 —— 需要追溯时见 git 历史（提交 `386c249` 及更早）。

---

## Build & Run

> ⚠️ **本机 `mvn` 不在 PATH**。用**仓库自带的 `mvnw.cmd`**（已提交；`only-script` 模式，不需要 `maven-wrapper.jar`），或指定 **Liberica NIK 25** 作为 JDK。
> **打包机只需 JDK(NIK) + MSVC + 网络**：`mvnw.cmd` 会自己把 Maven 3.9.16 下到 `%MAVEN_USER_HOME%`（默认 `%USERPROFILE%\.m2\wrapper\dists`），**不必预装 Maven**。
> 已实测：清空 wrapper 分发缓存 + Maven 不在 PATH → `build-native.cmd --prepare-only` 仍 **exit=0**（步骤 3 自动引导 Maven，27 秒含下载）。
> 🚨 新机器第一步先跑 **`check-env.cmd`**：9 项预检（PowerShell / JDK / native-image / Maven / MSVC / SDK / 内存磁盘 / git / 网络）+ 修复命令；退出码 **0=就绪 / 20=缺 MSVC / 1=缺其它关键项**，与 `build-native.cmd` 的码位对齐。

```bash
# 本机实际可用的构建命令（Linux/bash 形式，wrapper 路径按本机固定）
export JAVA_HOME='D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3'
MVN='/c/Users/Admin/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn.cmd'

"$MVN" -q -DskipTests package      # 出 jar
"$MVN" test                        # 全量单测（**不要加 -q**，会吞掉 surefire 摘要）
"$MVN" -q -DskipTests compile       # 快速语法检查
#   🚨 不要加 -Dmaven.compiler.release=21：源码已用 Java 22+ 的未命名变量 `_`
#      （如 PreviewListCell:276、MainController:422），release=21 会**必然编译失败**（实测）。
#      pom 的 release 是 25。`.claude-work/run-matrix.ps1` 的同类问题**已修**（2026-09-27：三处去掉该参数，并转为 CRLF）。
```

**GUI 自动化回归**（`test.mode`）—— ⚠️ **不要用 `mvn javafx:run -Dtest.mode=true`**：`-D` 会被 Maven 吃掉，不传进子 JVM。必须直接起 java。
🚩 **P7 起入口是测试源集的类**（生产启动器 `App` 已不含任何测试分支）：

```bash
java --module-path <jfx-win-jars> --add-modules javafx.controls,javafx.fxml \
     -Duser.language=zh -Duser.country=CN \
     -Dtest.mode=true -cp "target/classes;target/test-classes;<cp.txt>" \
     com.mhtextractor.gui.acceptance.TestMain
# 结果写入 logs/test-results.txt（当前基线：31 passed / 0 failed；zh 与 en 各 31/0）
```
🚨 **跑之前先删 `logs/test-results.txt`**：那只是"上一次运行留下的文件"。本次若在断言之前就崩了
（例如 `App.start()` 抛异常），你读到的会是**上一次的 31/0** —— 本项目真发生过。
**判据**：跑完核对结果文件的 **mtime**，并确认日志里没有 `Exception in Application start method`。

🚨 **GUI 自动化想验某个语言时，`-Duser.language` 可能被吃掉**：`App.start()` 先读
`logs/gui-state.json` 的 `locale` 并 `I18n.setLocale(...)`，它**优先于系统属性**。
所以"跑 zh"实际可能在跑 en（本项目真发生过：连续两次 `-Duser.language=zh` 的日志全是英文，
因为上一次运行把 `"locale":"en_US"` 回写了）。**先播种 gui-state.json 或删掉它再跑**，
并断言日志语言符合预期（例如"英文运行的日志里中文字符数必须为 0"）。

**native EXE**（需要 GraalVM/NIK + **MSVC**）：`build-native.cmd`（另有 `.ps1` / `.sh` / `run-native-image.bat`）。
> ✅ 本机**已能**构建 native（MSVC 就位，2026-10-02 实测打通）。**默认 dynamic 配方**（Oracle GraalVM + JavaFX 随包 DLL）→ 交付物 **22 个文件 / ~70 MB**；`set NATIVE_RECIPE=static` 可换 NIK 静态配方（15 个文件 / ~98 MB）——见文末「native 打包现状」。

---

## Architecture

JavaFX 25 桌面 GUI，从 `.mht`（MIME HTML）批量提取文本与图片。入口 `com.mhtextractor.App`。

### 分层（**依赖方向严格单向：gui → app → 业务包**）

```
gui          ← JavaFX 视图/控制器（唯一允许依赖 javafx.* 的层之一）
  ↓
app          ← 无头应用层（零 JavaFX）：MhtExtractorApp / WorkspaceModel /
  ↓            WorkspaceParamService / ExtractionPreconditions
业务包        ← core / extractor / processor / output / util / cache / config / i18n
model        ← 纯数据模型（PreviewItem / ThumbnailData），零 JavaFX，被 app 与 gui 共用
```

**架构红线（由 `ArchitectureTest` 的 11 条不变式自动强制）**：

| 不变式 | 含义 |
|---|---|
| `businessPackagesHaveNoUiFrameworkDependency` | 业务包（`core`/`extractor`/`processor`/`output`/`util`/`cache`/`config`/`i18n`/`model`/`app`）**不得依赖 `javafx.*`**（按**字节码常量池**扫描，不是查 import —— 全限定名引用也能抓到）。带白名单腐化检测。 |
| `applicationLayerDoesNotDependOnViewOrShell` | `app` 不得 import `gui` / `logging` / `App` |
| `mainControllerIsShrinking` | `MainController` 行数**只减不增**（当前上限值见测试内注释） |
| `guiLayerDoesNotInvokeCacheRulesDirectly` | GUI 不得直接调用预览缓存的删除规则（须经 `WorkspaceParamService`） |
| `guiDoesNotMutateDataOrderDirectly` | **GUI 不得直接改动 workspace 数据序**（须经 `WorkspaceModel`）。数据序是 GUI 与模型**共享的同一个列表**，直写会绕过撤销记录与结构维护而**静默分叉**（不报错）。当前实测 **0 处** |
| `headlessTestPackagesHaveNoJavaFx` | 无头测试包源码不得出现 UI 框架字面量 |
| `businessLayerI18nUsageDoesNotGrow` | 业务层 `I18n.get` 引用数不得超过基线（防"业务层继续拼用户文案"）。**2026-09-27 起口径修订为"剥掉 `log.*` / `logger.*` 调用整段后再计数"**：日志文案必须随界面语言走（用户就在界面日志区读它），不存在"改成码值 + 表现层渲染"的替代路径；基线 37 → **52**（净增 15 处全是"英文界面显示中文"的修 bug，见测试内演进记录） |
| `noNewThirdPartyDependencies` | 不得新增第三方依赖（**保护 native 打包**） |
| `testSupportSurfaceDoesNotGrow` / `allowlistOnlyTargetsBusinessPackages` / `baselineHarnessIsPresent` | 防测试夹具膨胀 / 白名单校验 / 基线在位 |

### 业务层

| 包 | 职责 |
|---|---|
| `core` | `MimeParser`（Jakarta Mail 解析 MIME 树 → `ParsedMht`：`htmlContent` / `cidMap` / `locationDataMap` / `imageInfoList`；递归深度上限 100；编码恢复 UTF-8/GBK/GB2312/GB18030/ISO-8859-1）· `ContentExtractor`（Jsoup HTML→纯文本，区域限定，标题提取）· `ChapterGenerator` + `ChapterNamingStrategy`（中文 `第一章` / 西文 `Chapter 1`，按 locale 选策略；**0-based 语义**） |
| `extractor` | `BaseExtractor` · `TextExtractor`（薄适配）· `ImageExtractor`（区域限定 / 广告过滤 / CID+Location 去重 / Data URI / 魔数扩展名）· **`ImageSource`** + `InMemoryImageSource`（写入层的图片数据源抽象，使写入层不再绑定具体容器） |
| `processor` | `BatchProcessor`（基类：文件收集、进度回调、取消、`ProcessStats`）· `TextBatchProcessor`（合并/单独）· `ImageBatchProcessor`（流式/预加载 × 合并/单独；`ParsedMhtProvider`、`ImageOutputHandler`；**缓存命中即跳过解析与提取**） |
| `output` | `FileWriter`（模板变量 `{filename}`/`{datetime}`/…）· `ImageWriter`（`CREATE_NEW` 原子写、唯一名、魔数扩展名纠正）· `Archiver`（ZIP/CBZ） |
| `util` | `OutputUnitPlanner`（输出单元规划 + 分桶）· `GroupStructure` · `GroupItem` · `DropResolver` · `UndoManager` · `MhtImportPlanner` · `IncrementalImport` · `SelectionState` · `UnitProgressAggregator` · `ParamScope` · `SanitizeUtils` · `EncodingUtils` · `FileUtils` · `HtmlUtils` · `HtmlExportBuilder` · `ThumbnailPixels`（纯 ARGB 解码）· `ExtractOutcome` |
| `cache` | **预览缓存（会话级）**：`PreviewCacheKey`（绝对路径+size+mtime+参数指纹+格式版本，SHA-256 目录名）· `PreviewCacheMeta`（`meta.json` schema）· `PreviewCacheStore`（构造注入 `Path root`；`.tmp`→改名→`.complete` 原子写；`totalBytes()`；**`imageFileFor(meta, index)`** —— 由 meta 反推缓存图片真实路径，供预览区"点击查看"用，避免 GUI 自己拼目录布局）· `PreviewCacheLifecycle`（四条删除规则集中实现 + 每项目写锁 + 10GiB 台阶提醒）· `DirectoryImageSource`（从缓存目录读图） |
| `config` | `AppConfig`（Jackson POJO；**命名配置只存提取参数**，`inputFolder`/`outputFolder`/`imageModeEnabled`/`openFolderAfterComplete` 为 `@JsonIgnore` 运行时字段；含 `imageExtractionFingerprint()`）· `ConfigManager`（`config/` 命名配置 CRUD）· `GuiState`/`GuiStateManager`（`logs/gui-state.json`：模式/目录/开关/**locale**） |
| `i18n` | **`I18n`**（唯一文案入口；界面/产走 `get` 具名占位符 `{name}`，日志走 `msg` 位置占位符 `{0}`；缺键返回 `!key!` + `log.error`）· **`ErrorCode`**（21 个错误码，键 `error.<CODE>`）· 两份 bundle `src/main/resources/i18n/messages_zh_CN.properties` / `messages_en_US.properties`（**各 537 键，键集与占位符集合都必须一致**，由 `I18nTest` 断言；其中 **235 条为 `log.*`**） |
| `model` | 纯数据模型：`PreviewItem`（原为 `MainController` 内部类；领域数据与 JavaFX 属性已分离）· `ThumbnailData`（`coreName/width/height/pixels`，**持解码后 ARGB `int[]`** 而非 `Image` 或 PNG 字节——见其 javadoc 的取舍说明） |

### app 层（无头应用层，**目标产物：换 UI 框架可复用**）

```java
MhtExtractorApp                      // 门面
  importPaths(addresses) / importFolder(folder)      // → List<Path>
  extractText(files, params, sessionDir, ProgressSink, CancelToken)
  extractImages(...)                                 // → ExtractResult(stats, sessionOutputDir)
  interface ProgressSink / CancelToken               // 纯接口，UI 侧自行适配（JavaFX 或其他）
  record ExtractResult(BatchProcessor.ProcessStats stats, Path sessionOutputDir)

WorkspaceModel          // 无头工作区，**已是生产路径的数据序唯一写入者**
                        //   语义操作：addItems（去重+压记录）/group/ungroup/removeItems/removeGroup/undo/clear
                        //   撤销应用：applyUndo(record) —— undo() 与 GUI 共用的**唯一**结构恢复实现
                        //   结构原语：appendItems/appendGroup（不去重、不压记录，供导入路径用）
                        //   模型**独占**业务数据（Q4/P3 起删掉共享构造 `new WorkspaceModel(list)`）
                        //   + displayOrder/topLevel/allItems/visibleCount/groups/undoDepth/nextGroupNo
WorkspaceParamService   // 参数变更与移除的缓存规则收口（13 处调用点归一到此类）
ExtractionPreconditions // 提取前置校验规则（含 Level 分级 + Denial），把规则从 GUI 移出
```

> **`app` 层是"业务可脱离 UI 运行"的验收物**：它零 JavaFX，`LayerBTest`（6 测试）与 `StubUiAcceptanceTest`（3 测试）证明"用非 JavaFX 的桩 UI 走完 导入→校验→建组→参数→移除/撤销→图片提取→文本提取"并与产物基线逐字节一致。

### GUI

- **`MainController`** — FXML 控制器（**4052 行**，`ArchitectureTest.mainControllerIsShrinking` 强制**只减不增**，上限常量 **4370**，余量 318）：模式切换、输入/输出目录、文件列表（拖拽排序/分组/勾选/章节徽标）、文本与图片预览、预解析线程、提取任务启停、`logs/gui-state.json` 持久化。持有两个按模式确认的参数缓存（null = 该模式未确认参数）。
  > 会话 18 起持续减重：6111 → 5109（外迁演练代码）→ 5002（外迁 `PreviewItem`）→ 4985 → **4935**（步骤 2 预览编排 → `app.PreviewService`）→ **4435**（步骤 3 `PreviewListCell` 外迁为顶层类）→ **4334**（WorkspaceModel 接入生产：导入/移除/整组移除/建组/拆分组/撤销应用六条路径全部切到模型）→ **4326**（日志全面国际化：多行 log 调用折叠为单行 `I18n.msg(...)`）→ **4355**（恢复"单击缩略图打开缓存图片"并加环境兜底，**+29 行，是唯一一次净增**）→ **4352**（会话 19：**27 处模态 Alert 全量换成 `NotificationHost` 应用内提醒，净减 3 行**）→ **4356**（同一会话续：关窗前清缓存改成**无按钮等待卡片**，清完自动关窗；**+4 行**）→ **4360**（同轮交付审计：补上"有未答确认卡时不许关窗"的守卫，**+4 行**——防"窗口关了但进程残留"）→ **4361**（提醒卡片**按用途给图标**：只多 1 行 `FontAwesomeSolid` import，20 个图标覆盖 27 个调用点）→ **4052**（Q 系列后续阶段继续外迁服务与收口）。列表单元格渲染/拖拽已不在本类。
  > ✅ 数据序（`items`）**只由 `WorkspaceModel` 拥有**（Q4/P3 起不再与 GUI 共享可变列表；`items` 是 `WorkspaceModel.topLevelView()` 的**只读投影**，唯一写操作 `clear()` 转发为模型命令）。GUI 对它的**直接写入点已归零**，由不变式 `guiDoesNotMutateDataOrderDirectly` 持续强制；**间接写（把数据序交给 util 层改写）Q0/P0 起也为 0**，且已从"打印度量"升级为**硬断言**（同一轮把拖拽正向与拖拽撤销收进 `WorkspaceModel.applyResolvedDrop` / `applyUndo`）。
  > ✅ **P7：本类不再为测试开放任何状态面** —— 44 处 `test-support` 标记全部摘除（`ArchitectureTest.TEST_SUPPORT_MARKER_MAX` 随之归零）。真正需要同包生产协作的 5 个成员（`ws`/`items`/`ITEM_VIEW`/`performDrop`/`effectiveForObject`）留包级且**不带标记**；其余 39 个收回 `private`，GUI 自动化改经**测试源集**的 `com.mhtextractor.gui.acceptance.MainViewTestBridge`（反射）访问。本类**也不再读 `test.mode`**。
- **`ParamDialogController`** — 「提取参数设置」模态弹窗；仅「确定」提交；底部固定行是命名配置 CRUD。
- **`LogTextArea`** — 自定义 JavaFX 组件，经 `TextAreaAppender` 注册为 Logback consumer（日志进界面）。
- **`NotificationHost`（2026-09-27 新建，`gui/component`）** — **应用内提醒的唯一入口**（生产代码已**零模态 Alert**）：
  错误 = 居中 `ModalPane`（必须点"确定"）/ 警告·信息 = 右上角 toast / 确认 = 居中卡片 + **回调**（取代 `showAndWait`）/
  **等待卡片** = 居中 `Message`（零可交互控件，后台干完自动收卡片 + 收尾，关窗前清缓存用）。
  每个窗口把场景根包一层 `StackPane` 后 `install` 一次（主窗口在 `App.start()`、参数窗口在 `ParamDialogController.create()`）；
  调用点只传一个锚点节点（如 `statusLabel`），**控制器不持有宿主字段**——这是保住 `MainController` 只减不增的关键。
  ⚠️ 改这块之前先把下面「交互提醒约定」整节读完（含 3 个"编译通过但运行炸"的坑与 1 项"机制未证实"）。
- **`MainView.fxml` / `ParamDialog.fxml`** — 全部文案为 `%key`（经 `loader.setResources(I18n.bundle())` 注入），**不得再出现中文字面量**。
- **`com.mhtextractor.platform`（步骤 5 新建）** — 与操作系统交互的基础设施，**零 JavaFX**，已纳入"零 UI 框架"不变式扫描：
  - `BrowserLauncher`（159 行，**门面**）— `openHtml` / `openViewSource` + 启动三级兜底（直接起 exe → `cmd start` → `rundll32 url.dll,FileProtocolHandler`）
  - `SourceViewPage`（314）— 源码视图页生成（HTML 转义 + 行号 + 语法高亮 + 链接下划线）。页头文案由**调用方**传入（本层不产本地化文案）
  - `CdpClient`（320）— CDP 协议客户端 + 调试实例的进程/端口管理（原生 Socket，native-image 安全）
  - `BrowserDetector`（197）— 浏览器探测与识别（注册表 `UserChoice` → `http` 协议命令 → 常见路径 → `where.exe`）
  - `FileOpener`（~250）— **用系统默认程序打开文件**：`cmd /c start "" "<file>"` + **退出码/输出校验** → 「照片」协议兜底（`ms-photos:viewer?fileName=<urlencoded>`）→ 如实报失败。含"UAC 关闭 → UWP 文件激活被静默阻断"的环境预判（见「预览缓存约定」）
- `JavaFxUtil`（留在 `gui.util`：确实依赖 JavaFX）。⚠️ 其中 `SmoothProgressProperty` 与 `runLater`/`isFxThread`/`createSmoothProgressProperty` 已在 2026-09-27 的清理中作为**零引用死代码**删除；`reflect-config.json` 里仍有指向它的旧条目（悬空，见下文 reflect-config 说明）。

### 测试代码位置（重要）

演练与自动化测试**已从生产类外迁**（P7 起入口也在测试源集）：

- **`src/test/java/com/mhtextractor/gui/controller/FeatureExercise.java`**（1451 行）— 承载 `exerciseAllFeatures()`（追踪 agent 演练）与 `runAutomatedTests()`（31 项 GUI 断言）
- **`src/test/java/com/mhtextractor/gui/acceptance/TestMain.java`**（152 行）— **GUI 自动化/演练的入口**（`-Dtest.mode=true` / `-Dagent.exit.delay`）：按与 `App.start()` 逐行相同的装配起真窗口，再驱动 `FeatureExercise`。**P7 起生产启动器 `App` 里没有任何测试分支**（原先的 `-Dtest.mode` 分支与 `invokeFeatureExercise` 反射挂钩已删除）。
- **`src/test/java/com/mhtextractor/gui/acceptance/MainViewTestBridge.java`**（~430 行）— 访问 `MainController` **private** 成员的**反射桥**（P7：生产类的 44 处 `test-support` 让步已全部收回）。
- **好处**：`MainController` 不再内嵌测试代码（A-2 自锁解除），且测试代码**不进生产 jar / 不进 native 镜像**。

### 测试与基线资产

| 资产 | 位置 | 规模 |
|---|---|---|
| 单元测试 | `src/test/java` | **626 测试 / 56 类**（默认执行）+ `headless-layer-b` **65**（无 JavaFX 执行） |
| MHT 夹具 | `src/test/resources/fixtures/` | 11 个（simple-text / single-image / multi-image / data-uri / data-uri-mixed / region / titles / cn-filename / garbage / deep-nesting / no-cte） |
| **产物黄金基线** | `src/test/resources/baselines/` | **40 份**（20 例 × zh/en）——重构的字节级标尺；只证明"没变"不证明"对"，已知缺陷以 `knownIssues` 标注 |
| 架构不变式 | `src/test/java/com/mhtextractor/baseline/ArchitectureTest.java` | 11 条 |

> **回归门槛（每个改动都必须全绿）**：`mvn test` = 默认执行 **626/0** + `headless-layer-b` **65/0**；
> 产物基线 **40 份**；架构不变式 **11 条**；GUI 自动化 **zh 31/0、en 31/0**。
> 两条约束：`MainController` ≤ **4370** 行（实测 **4052**）、`test-support` 标记 = **0**（P7 起上限归零，实测 0）—— 均**只减不增**。
> ⚠️ **标记计数口径只覆盖 `MainController`**（实现是按该文件文本 `indexOf` 计数）。谁要把测试支撑面挪到别的 GUI 类，这道闸门**不会拦**；P7 起测试访问通道只有测试源集的 `MainViewTestBridge`。

---

## 国际化约定

- **两个入口，各管一类文案**（都在 `com.mhtextractor.i18n.I18n`）：
  - **`I18n.get(key, args...)`** —— 界面/产物文案；`args` 为**交替键值对**：`I18n.get("extract.groupFileCount", "group", "组1", "count", 3)`
  - **`I18n.msg(key, args...)`** —— **日志文案专用**（2026-09-27 新增）；占位符为**位置索引** `{0} {1} …`，
    调用点形如 `log.info(I18n.msg("log.textbatchprocessor.06", files.size()))`。
    位置索引内部仍复用同一渲染引擎（`\w+` 本就覆盖数字）。两种写法**不要混用**。
- ⚠️ `I18n.msg` 会先把实参 `String.valueOf`：**null 必须渲染成 `null`**（与 logback 的 `{}` 一致），
  否则会退化成把 `{0}` 原样打进日志 —— 单元测试日志里真出现过 `输入文件夹：{0}`，`I18nTest` 已钉死该行为。
- **禁止**业务代码直接 `ResourceBundle.getBundle`
- **禁止**在 FXML 写中文字面量（用 `%key`）
- 缺键 → 返回 `!key!` + `log.error`（**不返回 null、不原样返回键名**）：界面上 `!key!` 一眼可辨，
  且 `I18nAcceptanceTest` 会断言活跃键（`I18n.get` **与** `I18n.msg` 两个字面量入口 + FXML `%key` + `ErrorCode`）
  渲染后不含 `!`
- **两份 bundle 键集必须完全一致**（`I18nTest` 断言），**且同键的占位符集合也必须一致**
  （`placeholderSetsMatchAcrossBundles` —— 位置索引少写一个不会报错，只会在日志里打印 `{2}`）
- **不国际化**：**持久化字段名**（`meta.json` / `gui-state.json` 的 JSON key 属语言无关契约）
- **测试/演练代码的中文分两类，别一刀切**（2026-09-27 修正，此前"测试代码一律不国际化"的写法导致英文日志里漏出中文）：
  - **夹具数据**（导入来源名、组名、生成的 MHT 正文与标题、资源缺失提示…）—— 会被生产代码打进
    **界面日志区/预览区**，属用户可见文案 ⇒ **必须走 `test.*` 键**。曾漏接线，英文运行的日志里出现
    `导入开始：来源=自动化重复导入`（现为 `Import started: source=Automated re-import`）。
    判定口径：**这段文字会不会出现在用户看得见的地方**。
  - **断言失败消息**（`"组名编辑失败"` 等）—— 只写 `logs/test-results.txt`，**从不进日志区**，
    属测试诊断 ⇒ 保持中文、不入 bundle。
- 默认输出名：**中文 locale 保留既有行为**（`组N` / `输出文件`），仅 `en_US` 用英文（`Group N` / `Output Files`）——老用户零影响
- **日志已全部国际化**（2026-09-27 起，bundle 内 **235 条 `log.*` 键**）：日志直接显示在界面日志区，属用户可见文案。
  例外仅剩**纯开发者诊断**：内部不变量断言抛出的异常文案（如 `SHA-256 not available`）保持 ASCII，不入 bundle。

### 🚨 界面布局：英文文案更长，**按钮行容器必须可换行**

`I18nAcceptanceTest` 管不到布局。实测（**`tools/probes/UiProbe.java`** 只读探针；跑法见文末「验证工具（`tools/`）」）：
en_US 下主界面 7 个按钮的 pref 宽度合计 **1093px**，而中栏（列表栏）在 1920 全屏时也只有 1050px
—— 左右两栏都是 `hgrow=ALWAYS`，**中栏永远拿不到全部富余宽度，全屏也救不回来**。
`HBox` 只能把按钮压到 pref 之下 → 文本被省略号截断。

**规则**：多按钮行用 **`FlowPane`**（宽度不足即换行，按钮恒以 pref 绘制）；
固定列宽的标签列**不要写 `prefWidth`**（让 GridPane 按内容算，如 `Input Directory:` 需 104px > 原写死的 80px）；
英文明显更长的短行元素（`modeLabel` / `fileCountLabel` / `paramSettingsButton`）加 `minWidth="-Infinity"`（= `USE_PREF_SIZE`），
把缺口交给中间的 `Region` 吸收。**改完用探针复验**：

```powershell
# 一条命令搞定（自动推导 JavaFX module-path 与 classpath，必要时自动 mvn package）：
#   powershell -ExecutionPolicy Bypass -File tools\probes\run-probe.ps1 UiProbe [-Locale en_US] [-Rebuild]
# 输出 truncated / CJK-on-screen 清单 + 截图到 .claude-work\uiprobe\（gitignored 草稿区）
```

> 已按此规则修好：主界面按钮行（`FlowPane`）、输入/输出目录列的固定宽度、
> 右上角模式/计数行、参数弹窗的 5 个标签列与配置下拉框、变量说明标签（`wrapText="true"`）。
> 探针复验：en/zh × 900/1100/1536/1920 四档窗口 + 参数弹窗，**截断数均为 0**。


---

## 预览缓存约定（新增）

- 位置：`<java.io.tmpdir>/mht-extractor-preview/`（**会话级**，退出清空）；与 `<tmpdir>/mht-extractor-view/`（`HtmlExportBuilder`）并列
- 目录结构：`<64位SHA256>/ { meta.json, images/, .complete }`；写入中为 `<key>.tmp/`
- **命中判定四要素**：绝对路径 + size + mtime + `AppConfig.imageExtractionFingerprint()` + `FORMAT_VERSION`
- **四条删除规则**（集中在 `PreviewCacheLifecycle`，不得散落）：(a) 参数变化 (b) 提取完成 (c) 项目移除 (d) 退出清空
- 每 10 GiB 台阶弹**非模态**提醒（不限制使用）
- **`FORMAT_VERSION` 递增即让全部旧缓存失效**——改动 `PreviewCacheMeta` 结构语义时必须递增
- **点击查看（单击缩略图）**：`PreviewCacheStore.imageFileFor(meta, index)` 由 **meta 四要素反推 key** 得到缓存图片真实路径
  → `platform.FileOpener`（**非 AWT**，守住"主源集零 `java.awt`"）：① 系统默认查看器（`cmd /c start "" "<图>"`）
  → ② 「照片」协议兜底（`ms-photos:viewer?fileName=<urlencoded>`，实测在被阻断的环境里仍能弹窗）
  → ③ 全失败则打 `log.maincontroller.65`；两级之间用**退出码 + 输出**粗校验，**不再谎报"已打开"**。
  缩略图与 `meta.images()` **同序 1:1**（下标语义见 `openCachedImage` 与 `renderThumbnails` 的注释）。
  🚨 **本机环境陷阱**：`EnableLUA=0`（UAC 关闭）→ 全会话高完整性 → Windows **静默阻断 UWP 的「文件激活」**
  （处理器进程起来了、窗口永不出现、**退出码仍是 0**）——此时**资源管理器里双击图片同样打不开**。
  `FileOpener` 以「UAC 关闭 **且** 该扩展名默认处理器是打包应用（ProgId `AppX*`）」预判该状态。
- 🚨 **这条功能曾在 E1.4 被静默抹掉**（它从未进过任何提交 → 改 `buildThumbBox` 签名时被顺手删干净，
  而**编译通过、测试全绿**，所以三轮重构都没发现）。**动缩略图渲染 / `ThumbnailData` 前先把本节读完**。

---

## 交互提醒约定（`NotificationHost`，2026-09-27 新增）

- **生产代码零模态 Alert**：`new Alert` / `showAndWait` 在 `src/main/java` 已**归零**（只允许出现在测试代码里）。
  提醒一律走 `com.mhtextractor.gui.component.NotificationHost`（静态入口，调用点只传一个**锚点节点**，
  如 `statusLabel` / `configComboBox`）。**控制器不持有宿主字段** —— 这是保住 `MainController` 只减不增的关键。
- **三层语义（用户拍板）**：

  | 语义 | 入口 | 行为 |
  |---|---|---|
  | 错误 | `error(anchor, msg)` | 居中 `ModalPane` + `DANGER` 卡片，**必须点"确定"** |
  | 警告 / 信息 | `toastWarning` / `toastInfo` | 右上角 toast，非阻塞、可堆叠（上限 5）、✕ 关闭 |
  | 确认 | `confirm(anchor, msg, ok -> …)` | 居中卡片 + 确定/取消，**回调式**（`persistent=true`，遮挡层 / ESC 不关） |
  | 告知后继续 | `busy(anchor, title, desc, work, onDone)` | 居中 `Message` 卡片：**零可交互控件**（无按钮、无 ✕），后台干活，**干完自动收卡片 + 执行收尾**（关窗前清缓存用） |

- **接线**：把场景根包一层 `StackPane` 并 `NotificationHost.install(scene, shell)`
  （主窗口在 `App.start()`、参数窗口在 `ParamDialogController.create()`）；
  没装也会在第一次调用时**就地补装**（根不是 `StackPane` 就包一层 + 打日志，**不静默吞掉提醒**）。
- **图标"按用途"给，不是"一个严重级别一个图标"**（2026-09-27 追加）：每个调用点传一个 `FontAwesomeSolid` 图标，
  让卡片一眼看出"这是谁的事"。现用映射：

  | 用途 | 图标 | 用途 | 图标 |
  |---|---|---|---|
  | 文件不存在 | `FILE` | 提取失败 | `FILE_EXPORT` |
  | 目录不存在 / 打开文件夹 | `FOLDER_OPEN` | 大文件预览 / 读文件信息失败 | `FILE_ALT` |
  | 浏览器启动、查看失败 | `GLOBE` | 移除全部 / 删除配置 | `TRASH_ALT` |
  | 导入失败 | `FILE_IMPORT` | 整组移除 | `OBJECT_GROUP` |
  | 缓存占用台阶 / 盘符根确认 | `HDD` | 列表已空 | `LIST` |
  | 清空缓存（等待卡片） | `BROOM` | 参数未确认 / 打开参数窗口失败 | `SLIDERS_H` |
  | 导入配额确认 | `CLIPBOARD_LIST` | 递归深度超限 | `SITEMAP` |
  | 配置保存失败 | `SAVE` | 配置加载失败 | `FILE_DOWNLOAD` |
  | 选择类提醒（未选配置） | `HAND_POINTER` | 高速内存模式 | `MEMORY` |

  不带图标的旧签名保留（默认中性图标，供调试/降级路径用）；**新代码请显式传用途图标**。
  ⚠️ 加图标只是**改实参**，不增加行数（`MainController` 的"只减不增"红线不受影响）；
  但**每加一个图标常量都要先确认 `ikonli-fontawesome5-pack` 里有**（本项目只有这一个图标包）。
- 🚨 **不要 `showAndWait`**：`ModalPane` 只有非阻塞 `show(Node)` / `hide()`；"确认后继续"必须写成回调。
  后面的代码长就按三种落法选：直接塞 lambda / 抽私有方法 / 加"已确认"布尔参数**重入自己**。
- 🚨 **通知里的按钮不要设 `setDefaultButton(true)`**：`defaultButton` 是**场景级**的，实测会把全窗口 ENTER
  劫持到通知的按钮上（本项目主源码原本零 `defaultButton`）。
- 🚨 **放进 `ModalPane` 的内容必须 `setMaxHeight(Region.USE_PREF_SIZE)`**：否则被拉满整窗高度（实测 400×761 → 400×149）。
- 🚨 **`ModalPane` 的顺序写死不能改**（`NotificationHost.showModal`，实测踩出来的静默失效）：
  ① 先 `modalPane.applyCss()` 让**皮肤就位** → ② 再 `setContent(node)`（内容是**皮肤的内容监听器**挂上去的，
  皮肤不在就永远挂不上：现象是 `display=true` 但高度 0、屏幕上什么都没有）→ ③ 最后才 `show(node)`
  （`ModalPaneSkin` 构造时会自己 `hide()` 一次，会把它之前的显示打回 false）。**启动早期就发提醒时必现**。
- ⚠️ **"ESC / 点遮挡层不会关掉确认卡"属"行为符合预期、机制未证实"**（本机送不进皮肤处理器）——
  以后要验这条，**必须带控制组**（非 persistent 的反例必须先能关掉），否则是**假通过**。
- 🚨 **有"未回答的确认卡"时不许关窗**（`MainController.onWindowCloseRequest` 里的 `NotificationHost.hasPendingDecision(statusLabel)` 守卫）。
  旧的 Alert 是 `APPLICATION_MODAL`，等回答期间主窗**根本关不掉**；换成窗口内卡片后 ✕ 仍然可点，
  于是"关窗卡片顶掉确认卡 → 回调永不触发 → 后台线程（提取线程是**非 daemon**）永久阻塞 → **窗口消失但进程残留**"。
  **任何人再动关窗路径都要保住这两条**：
  ① 有未答确认 → 不放行；② 真有卡片顶替未答确认 → 按"取消"交付决定（`showModal` 里做）。
- 🚨 **非 daemon 线程活着时，关掉最后一个窗口 JVM 不会退出**（本机实测：60s 仍不退，只能杀进程）。
  所以任何"让某个线程永久等待"的路径都会变成用户眼里的**残留进程** —— 提醒类的回调"可能不触发"就等于风险。
- 与"表单窗口"的边界：**"提醒"全换，"表单"不动** —— 参数设置窗口（`openAndWait`）与配置名输入
  （`TextInputDialog`）仍是模态窗口，这是有意的。

---

## Key dependencies

Jakarta Mail 2.0.2（MIME）· Jsoup 1.18.1（HTML）· Jackson 2.18.6（JSON）· **glavo webp 0.2.0**（纯 Java WebP 解码，无 JNI）· AtlantaFX 2.1.0（主题）· Logback 1.5.26 · Ikonli 12.3.1 · JUnit 6.0.3

> **新增依赖前务必三思**：项目要出 GraalVM native EXE，每个新依赖都可能带来反射/JNI/资源元数据负担。`ArchitectureTest.noNewThirdPartyDependencies` 会直接失败。

---

## Native Image

可达性元数据位于 `src/main/resources/META-INF/native-image/com.mhtextractor/`：

| 文件 | 状态 |
|---|---|
| `reachability-metadata.json` | **权威**（现代统一格式）。含 reflection（含 `PreviewCacheMeta` 及嵌套 record 的 Jackson 构造器）+ resources（含 `{"bundle": "i18n/messages"}` 等） |
| `reflect-config.json` | ⚠️ **旧格式。CLAUDE.md 曾写"建议删旧留新"——该建议已被实测推翻，见下** |
| `resource-config.json` | 含 FXML / `custom.css` / logback / 字体 / i18n bundle pattern |
| `jni-config.json` · `proxy-config.json` · `serialization-config.json` · `native-image.properties` | `native-image.properties` 仅设 `JavaArgs = -Djava.library.path=target\\jfx-dlls` |

### 🚨 元数据必须用 tracing agent 采集，不许手工推断

**这是本项目用代价换来的纪律**。示例教训：曾推断 i18n 需要 `ResourceBundle` / `PropertyResourceBundle` / `Locale` 的**反射条目**——**agent 实测为 0 条**（classpath bundle 走 `PropertyResourceBundle(stream)` 直接构造，无反射）；真正需要的是**资源注册** `{"bundle": "i18n/messages"}`。

采集流程（NIK 自带 `native-image-agent.dll`，JVM 下即可）：

```bash
java -agentlib:native-image-agent=config-output-dir=target/agent-out -cp ... <驱动或 App>
# 若报 "Can't find dependent libraries"，改用全路径形式：
#   -agentpath=<JDK>/bin/native-image-agent.dll=config-output-dir=target/agent-out
```

**合并纪律**：
1. **按并集合并**（agent 输出 ∪ 仓库现有），**勿直接覆盖**——旧版有些条目新演练捕获不到但运行必需
2. **必须是纯增量 diff**：核对 `git diff --numstat`。曾在合并脚本里用 `json.dumps(indent=1)` 重排整个文件，产生 **−606 行**无关变更；正确做法是 append-only（参考历史 `merge-*-native-metadata.py` 思路）
3. **方法/字段级对账**：修改前后集合比对，**丢失数必须为 0**
4. ⚠️ **不要往 `src/main/resources` 放临时/备份文件**——会被 Maven 静默打进 jar（本项目真发生过 `.bak` 事故）

---

## 🚨 native 打包现状（2026-10-02：**已打通并可交付**）

**产物**：GraalVM native EXE 绿色免安装版（`MhtExtractor.exe` + DLL）。NIK 自带 `native-image 25.0.3` 可用（无需另装 GraalVM）；MSVC 已就位（VS 2026 在 `E:\msvc\community`，`check-env.cmd` → exit 0）。两种配方都端到端验过，**产物逐字节一致**（`BaselineCases` 32/0，JVM-vs-native 产物树 288/288 同名；冷启动 0.42 s）。

| 配方 | 交付物 | 必须记住的三条 |
|---|---|---|
| `NATIVE_RECIPE=dynamic`（**默认**） | 22 文件 / ~70 MB | ① 那 7 个 JavaFX DLL **必须随包**；② 🚨 **非 ASCII 路径下启动即退**（`UnsatisfiedLinkError: prism_d3d`）→ 中文路径请双击随包的 `启动.cmd`；③ 链接要加 `-H:NativeLinkerOption=<NIK>\lib\static\windows-amd64\prism_common.lib`（不加必 LNK2001） |
| `NATIVE_RECIPE=static` | 15 文件 / ~98 MB | ① JavaFX DLL **一个都不能放**（双份加载 → `WinApplication._init` 段错误 `-1073741819`）；② 中文路径免疫；③ 其余同上 |

- 输出目录默认 `<仓库同级>\<仓库名>-dist`（树外）。🚨 **不要放项目树内运行**：树内的 EXE 会让 `Font.loadFont(...)` 返回 `null` → **图标全变空心方框**。
- 运行期依赖随配方走：两种配方都需要 native-image 自动落地的 **9 个 JDK DLL + 5 个 VC++ 运行库**。
- 🚨 **`shaded jar` 不是可交付产物**：本机能 `java -jar` 只因构建用的 Liberica Full JDK 把 JavaFX 内置成了 JDK 模块；换普通 JDK 25 即报"缺少 JavaFX 运行时组件"。要用 jar 交付得补一个不继承 `Application` 的 launcher 并改 shade 的 `Main-Class`（会牵动 native 入口，**动前先想清楚**）。
- 📡 **构建可观测性**：主日志 `logs/native-build-<stamp>.log`（实时 tee，含每步 `[STEP k/n] BEGIN/END exit=`）、步骤 TSV、机器可读 `*.report.json`（context→input→steps→artifacts，含 SHA256）。构建进行中读日志用 `Get-Content`/`FileShare.ReadWrite`（**`File.ReadAllText` 会失败**）。
- ⚠️ **四个实测坑**：(1) 编译 JDK 与 native-image JDK 必须**独立解析**；(2) `dir /s` 不支持中间目录通配，`mvn.cmd` 要当文件名给；(3) 🚨 **`.ps1` 必须 CRLF**（PS 5.1 在 LF-only 的 `.ps1` 里会**静默丢弃**哈希表字面量中注释行之后的键——曾让 `build-report.json` 的 `steps` 凭空消失）；(4) 日志要能边写边读。
- ✅ **无头执行 `headless-layer-b` 已修复**：让"无 JavaFX"成立需要**两个条件同时满足** —— `argLine` 把 boot layer 限定在 `java.se`（管 JDK 内置模块层）**且** `classpathDependencyExcludes` 剔干净 jar（管 classpath 未命名模块），且排除坐标**带 classifier 与不带各写一遍**（该选项只做完全相等匹配）。修完后 `LayerBTest` 原断言**一字未改**即通过。

### 其它已知缺陷（登记未修）

| 缺陷 | 位置 | 影响 |
|---|---|---|
| 业务层仍在产"已解析文案" | `ImageExtractor` 经 `I18n.get` 产 warning 文案 → 写入 `PreviewCacheMeta.warnings` | 缓存内警告语言取决于预览时 locale；彻底修需业务层产码值，会触及缓存 schema |
| `AD_KEYWORDS` 硬编码 | `ImageExtractor` | 若参数化，**必须同步加入 `imageExtractionFingerprint()`**，否则预览缓存串味 |
| `reflect-config.json` 与统一元数据并存 | `src/main/resources/META-INF/native-image/` | 🚨 **不可直接删除**（实测）：旧格式 **291** 个类型、统一格式 **210** 个，相当一部分**仅存在于旧格式**；native-image 对同 type 取**并集**，删旧文件会丢条目 —— 正解是"并集合并 + 方法/字段级对账（丢失数=0）"。清理该文件**必须用 40 份产物基线做回归**。另：指向 `JavaFxUtil$SmoothProgressProperty` 的条目已**悬空**（该类已删） |
| 工作区有 22/105 个 java 文件是 CRLF | 全仓库 | 与 `.gitattributes` 的 `*.java text eol=lf` 不符。`eol` 规则在 `git add` 时会规范化为 LF，**仓库内存储不受影响**，但 `git status` 会反复告警。一次性修法：`git add --renormalize .` |
| `MATRIX ALL GREEN` 会与断言失败并存 | `.claude-work/run-matrix.ps1`（gitignored） | 该脚本只看退出码，而 GUI 自动化断言失败时 JVM **仍 exit 0**。✅ 已修：脚本尾部同时校验 `RESULTS:` 行的 `N passed, 0 failed`，不满足即 exit 1 |

---

## 本机 DSH / Claude CLI 环境陷阱（动手跑命令前先读）

- **会话权限必须是完全权限 / `danger-full-access`**：`read-only` / `workspace-write` 下受限令牌会导致**所有子进程启动失败**，报 `0xC0000142`（十进制 `3221225794`，`STATUS_DLL_INIT_FAILED`）。
- 本机**没有 pwsh 7**：工具描述中的 `pwsh -Command` 实际由 **Windows PowerShell 5.1** 执行。
- **DSH 传给 pwsh 的 stdin 是空的**：不要靠管道向脚本喂数据，用文件或 argv。**中文放文件或 argv 是安全的**（PS 5.1 经 stdin 传中文会乱码）。
- **PS 5.1 的自动变量陷阱**：脚本里**不要**写 `$args = @(...)`（`$args` 是自动变量，赋值会静默破坏整个脚本作用域，表现为 0 字节输出）。
- **执行策略**：`CurrentUser=RemoteSigned` 会覆盖 `LocalMachine=Bypass`，`.ps1` 无法直接运行 → 用 `-ExecutionPolicy Bypass`，或经 `[scriptblock]::Create((Get-Content $f -Raw -Encoding UTF8))` 执行。
- **写无 BOM 的 UTF-8**：`Set-Content -Encoding UTF8` 会写 BOM（Node 解析失败），须用
  `[System.IO.File]::WriteAllText($p,$s,(New-Object System.Text.UTF8Encoding($false)))`
- 🚨 **但 `.ps1` 反过来：含非 ASCII（中文）的 `.ps1` 必须带 UTF-8 BOM**。PS 5.1 对**无 BOM** 的脚本按系统
  ANSI(GBK) 解码：中文变乱码，轻则输出乱码、**重则整个脚本无法解析**。2026-09-27 实测：
  `.claude-work/inspect-cache.ps1` 与 `e2e-verify.ps1` 里的 `张` 被解成 `寮?`，把字符串打断 →
  两个脚本**从未能运行**（`powershell -File` 直接 ParserError），加 BOM 后立刻正常。
  两种调用方式（`-File` 与 `[scriptblock]::Create((Get-Content -Raw -Encoding UTF8))`）加 BOM **都兼容**（实测）。
  **规则：数据文件（Node 读）无 BOM；`.ps1` 含中文则必须有 BOM**（纯 ASCII 的可不带）。
- **PS 参数被吞**：`-Dfoo=bar` 形式的参数会被 PowerShell 解析坏（`ClassNotFoundException: /mode=true`），**用参数数组 `@(...)` 传**。
- 🚨 **禁止用 PowerShell 做"文本替换"来改文档内容**（2026-09-27 真实事故）：PS 里「反引号 + 字母」是转义序列，
  `$text.Replace(...)` 批量改 markdown 时会把**每个反引号**换成它后面那个字母 ——
  实测 `CLAUDE.md` 整篇变成 `tmvn packaget` 这种样子（反引号→`t`），另一份文档变成 `mCLAUDE.mdm`（反引号→`m`）。
  **改内容一律用 edit 工具，或写成 Node/Java 小程序；永远不要用 PS 字符串替换碰文档。**
- 🚨 **文档被改坏时的恢复路径（已实测可行）**：DSH 会话 transcript 里存着"改坏前"的完整文本 ——
  `%USERPROFILE%\.dsh\sessions\<项目目录名>\session-<id>\session.v4.jsonl.zstd`。
  该文件是**多个独立 zstd 帧拼接**的（`zstdDecompressSync` / `createZstdDecompress` 都只解第一帧，只拿到几百字符），
  必须**按魔数 `28 B5 2F FD` 切帧、逐帧解压**再拼起来，然后从 `# CLAUDE.md` 起截到 `</instructions>` 得到全文。
  **恢复后必须做等价核对**（恢复文本的每个反引号换成"损坏时那个字母"后应与损坏文件逐字符相同）。
  参照实现（**随仓库分发**）：`tools/recovery/recover-doc.js`、`tools/recovery/verify-recovery.js`。
  > 另一条更简单的路径：若该文件**只有本次改动**（`git status` 显示它原本未被改过），直接 `git checkout -- <file>` 还原 HEAD 字节。
- 🚨 **`.cmd` 里 `:label` 与函数体之间不能插任何东西**（哪怕一行 `REM` 或另一个 `:label`）：
  `call :label` 会**落空滑进下一个子程序**，现象是"该步 exit=0、耗时极短、却什么都没执行"。
  2026-09-27 实测：给 `build-native.cmd` 插子程序时把 `:tee` 与函数体拆开 → 步骤 3 报 `exit=0` 但 Maven 根本没跑
  （0.25 秒、日志里 0 行 `[INFO]`），直到步骤 4 因缺 `target\lib` 才暴露。**改子程序后必须实跑并核对该步是否真有输出。**
- 🚨 **`build-native.cmd` 的 FAILURE 段读日志必须带 `-Encoding UTF8`**：日志是 UTF-8，PS 5.1 的 `Get-Content`
  默认按 ANSI(GBK) 读 → "环境探测"变成"鐜鎺㈡祴"。控制组实测：不加=乱码、加了=正常。
- 🚨 **PowerShell 是构建链的硬依赖**：时间戳、实时 tee、构建报告、以及 `mvnw.cmd`（batch/PowerShell 混合脚本）都用它。
  PATH 里少了 `%SystemRoot%\System32\WindowsPowerShell\v1.0` 时现象很难看（`exit=9009` + 时间戳与日志名全空）；
  `build-native.cmd` 已在步骤 1 提前挡住，`check-env.cmd` 的 `[2/9]` 也会先报出来。
- **仓库自带 Maven wrapper**：`mvnw` / `mvnw.cmd` / `.mvn/wrapper/maven-wrapper.properties`（`only-script` 模式，**无 jar**）。
  打包机**不必装 Maven**（实测：清空 wrapper 分发缓存 + Maven 不在 PATH → `build-native.cmd --prepare-only` exit=0）。
  公司网络拉不到 Apache 时可用 `MVNW_REPOURL` 指向内网镜像。
- 🚨 **"PowerShell 数行比 Java 少"的真正原因是编码，不是换行**（2026-09-27 实测定位）：
  PS 5.1 的 `Get-Content` **不带 `-Encoding` 时按系统 ANSI(GBK) 读** UTF-8 无 BOM 文件，
  DBCS 解码回退会**吞掉"以非 ASCII 字符结尾那一行"的换行符**，把行合并。
  实测同一个 `MainController.java`（纯 LF、4443 个 LF 字节）：
  `Get-Content`（默认）= **4277**，`Get-Content -Encoding UTF8` = **4443**，`ReadAllLines` = **4443** —— 少的 166 行正好是"行尾是中文"的行数。
  **因此：数行/读源码一律显式 `-Encoding UTF8`（或直接读字节），不要用默认 `Get-Content`。**
  同理，`.claude-work/*.ps1` 里读源码的 `Get-Content` 调用都应补上该参数（当前 13 处里只有 2 处带）。
- **驱动 Claude Code**：原直连原生 exe `%APPDATA%\npm\node_modules\@anthropic-ai\claude-code\bin\claude.exe`
  （**不要用 `shell: true` 或 `.cmd` shim**，会触发 `DEP0190` 注入风险警告；提示词走 stdin，写后必须 `end()`）。
  ⚠️ **本项目的实施通道已改为 DSH subagent**（原路径与实际安装位置 `C:\Users\Admin\.local\bin\claude.exe` 都不再是主线），
  这段只在需要直接驱动 Claude Code 时参考。
- **git 身份未配置**：`git commit` 会报 `Author identity unknown`，须内联
  `git -c user.name="Administrator" -c user.email="admin@mht-extractor.local" commit -m "..."`（**不要** `git config`，那会改用户环境）。
- **`git` 忽略规则已锚定到仓库根**：`.gitignore` 用 `/config/`、`/logs/`、`/temp/`、`/输出文件/`。**不要改回不带前导 `/` 的写法**——那样会误伤 `src/test/java/com/mhtextractor/config/`，导致新测试文件被静默忽略（本项目真发生过）。
- 🚨 **本机 UAC 是关闭的（`HKLM\...\Policies\System\EnableLUA = 0`）**：整个会话（含 `explorer.exe`、IDE、本工具的 pwsh）
  都是**高完整性令牌**。后果：**UWP/AppX 应用的「文件激活」被 Windows 静默阻断** —— 处理器进程会被拉起但**没有窗口**、
  **退出码仍是 0**，**连在资源管理器里双击图片也打不开**（2026-09-27 实测）。协议激活不受影响（`start ms-photos:` 正常）。
  凡"调用系统默认程序打开文件"的功能都要走 `platform.FileOpener`（见「预览缓存约定」）。
- **不要整文件读取 `MainController.java`**（**4052 行**）：用 `grep -n` 定位 + 区间读，否则上下文会被吃掉。
- **行号会漂移**：本文件或任何文档中提到的行号，引用前先用 `grep -n` 复核。

---

## 验证纪律（本项目最重要的工程约定）

**执行方（人或 agent）的自述不能作为验收依据。** 每条结论都要用**不同手段**独立复验。已验证有效的手段：

| 手段 | 曾发现的问题 |
|---|---|
| 直接聚合 `target/surefire-reports/*.txt` 算测试数（不读执行方日志） | 多次校正基线 |
| **独立 diff 比对**（如缓存复用：跑两轮输出目录后 `git diff --no-index`，比对 SHA-256） | 证明复用无字节偏差 |
| 自写探针直连生产路径（如 `<tmpdir>/mht-extractor-preview` 真实 write→load） | 证明缓存 schema 与命中判定正确 |
| **方法/字段级存量对账**（比对元数据修改前后集合） | 证明去重没丢条目 |
| **包围语句分析**（对"剩余中文"向上回溯判断是否 log/注释/测试） | 证明无 UI 泄漏 |
| `git diff --numstat` 看增删行数 | 发现合并脚本**重排整文件**（−606 行） |
| 字节码/常量池扫描（而非 grep import） | 发现全限定名引用可绕过 import 扫描 |
| 跑 GUI 自动化**前先删 `logs/test-results.txt`**，跑完核对 **mtime** | 本轮被它骗过一次：`App.start()` 抛 `ClassCastException` 使断言根本没跑，读到的却是上一次会话的 **"31 passed, 0 failed"** |
| 给"预期不成立"的断言配**控制组**（先造一个必然成立的反例跑同一段测量） | 发现两条断言是**假通过**：ESC 根本没送达皮肤处理器，所以"ESC 没关掉它"什么也没证明 |
| **会自己消失的 UI**（自动收起的卡片 / 瞬时状态）：断言必须**同帧**做，或用**闸门**卡住后台任务 | 等待卡片只显示几毫秒，+300ms 再去查已经收完了 —— 我第一版探针据此把**正确的实现**误判成 FAIL |

**并且：验证脚本自身也可能是错的。** 本项目四次被自己的验证脚本误导——`mvn -q` 吞掉 surefire 摘要（误判 0 测试）；正则 `[,)]` 漏掉分号结尾的枚举常量（误判"少一个"）；`$null -eq $int` 判断不可靠（误判"全部未完成"）。**第四次（2026-09-27）是「过滤器反噬」**：聚合 surefire 报告时用 `-notlike '*output*'` 排除 `*-output.txt`，把 `output.ImageWriterTest` 与 `OutputUnit*` 一并滤掉（少算 38 个测试，误判「聚合数与 Maven 汇总不符」）。**对"不一致"要再核一遍再下结论。**

---

## 验证工具（`tools/`，随仓库分发）

这些脚本/探针**进仓库**，所以打包机上的智能体遇到问题时可以直接用。
（`.claude-work/` 是本地草稿区，**被 gitignore、不随仓库分发** —— 两者不要混。）

| 工具 | 用途 |
|---|---|
| `check-env.cmd`（仓库根） | 打包机第一步：9 项环境预检 + 修复命令；退出码 **0=就绪 / 20=缺 MSVC / 1=缺其它关键项** |
| `tools/probes/run-probe.ps1` | **编译并运行下面的 UI 探针**：自动推导 JavaFX module-path 与 classpath，`target/` 缺失时自动 `mvn package`；`-List` 列出全部探针；纯 ASCII，PS 5.1 直接可跑 |
| `tools/probes/UiProbe.java` | 布局体检：四档窗口 × zh/en，报 `TRUNCATED` / `CJK-ON-SCREEN` 清单 + 截图（英文文案变长导致的截断就是它抓到的） |
| `tools/probes/NotificationAcceptance.java` | 交互提醒验收：错误居中必须点确定 / 警告信息右上 toast / 确认回调 / 等待卡片自动收（14 项断言） |
| `tools/probes/BusyCloseProbe.java` | 「关窗前清缓存」等待卡片：可见性 + 干完自动收 + 收尾回调（20 项断言） |
| `tools/probes/PendingConfirmCloseProbe.java` | "有未答确认卡时不许关窗 / 卡片被顶替按取消交付"（防残留进程，5 项断言） |
| `tools/probes/NonDaemonExitProbe.java` | 非 daemon 线程 × 关窗退出：**预期"不退出"**，复现"窗口没了但进程还在" |
| `tools/probes/ModalPaneStateProbe.java` | `ModalPane` 显示状态机（`viewOrder` / `display` / 内容挂载时机）—— 排查"卡片设了却看不见" |
| `tools/probes/IconShowcaseProbe.java` | 按用途图标实拍（toast / 确认 / 等待各一张 PNG） |
| `tools/recovery/recover-doc.js` | 文档被误替换后的恢复：从 DSH transcript **切帧解压**并提取原文 |
| `tools/recovery/verify-recovery.js` | 恢复的**等价核对**（恢复文本的反引号换成"损坏时那个字母"后应与损坏文件逐字符相同） |
| `tools/recovery/restore-claude-md.js` | 用恢复出的文本写回 `CLAUDE.md`（剥掉 reminder 尾标记、统一结尾换行） |
| `tools/launcher/make-launcher.ps1` | 给 dynamic 产物生成启动器 `启动.cmd`/`Launch.cmd`（把 DLL 暂存到纯 ASCII 目录）—— 修"中文路径下启动失败"，第 6 步自动调用。⚠️ 中文文件名只能由 PowerShell 创建 |
| `tools/diag/tee-probe.cmd` | 「命令行 tee 最小复现」：定位"某步 exit=0 但什么都没执行"这类 `call :label` 落空问题 |

```powershell
# 跑一个探针（探针输出 PNG/日志默认写到 <仓库>\.claude-work\uiprobe\，gitignored）
powershell -ExecutionPolicy Bypass -File tools\probes\run-probe.ps1 UiProbe
powershell -ExecutionPolicy Bypass -File tools\probes\run-probe.ps1 UiProbe -Locale en_US -Rebuild
powershell -ExecutionPolicy Bypass -File tools\probes\run-probe.ps1 -List
```

### P7 起：测试怎么访问生产控制器

生产类**不再为测试开放状态面**（`MainController` 的 `test-support` 标记上限已归零）。测试要读状态时只有两条正路：

1. **测试源集的反射桥** `com.mhtextractor.gui.acceptance.MainViewTestBridge`（`View` 包装类，一成员一方法）——
   生产线上一行不改，反射名与 `MainController` 字段/方法名是**隐式契约**（改名不会编译报错，会在下次 GUI 自动化时抛 `NoSuchFieldException`）。
2. **真正的公开行为**（如 `WorkspaceModel` 的公开 API）——优先用这条。

探针（`tools/probes/*`）用 `getDeclaredField/Method + setAccessible(true)` 直接读私有成员，是同一思路的独立实现。
