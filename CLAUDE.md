# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **最后更新：2026-09-27**（架构重构 + 全量国际化 + 预览缓存持久化 + **WorkspaceModel 接入生产：阶段 1b/2/3/4 全部完成**）
> 本轮交接：`docs/handoff/H33.md`（含**共享存储路线**、以及"接入生产前必须先逐行核对模型语义"的 3 个实例）

---

## Build & Run

> ⚠️ **本机 `mvn` 不在 PATH**。必须用 Maven wrapper 并指定 **Liberica NIK 25** 作为 JDK。

```bash
# 本机实际可用的构建命令（Linux/bash 形式，wrapper 路径按本机固定）
export JAVA_HOME='D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3'
MVN='/c/Users/Admin/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn.cmd'

"$MVN" -q -DskipTests package      # 出 jar
"$MVN" test                        # 全量单测（**不要加 -q**，会吞掉 surefire 摘要）
"$MVN" -q -Dmaven.compiler.release=21 -DskipTests compile   # 快速语法检查
```

**GUI 自动化回归**（`test.mode`）—— ⚠️ **不要用 `mvn javafx:run -Dtest.mode=true`**：`-D` 会被 Maven 吃掉，不传进子 JVM。必须直接起 java：

```bash
java --module-path <jfx-win-jars> --add-modules javafx.controls,javafx.fxml \
     -Duser.language=zh -Duser.country=CN \
     -Dtest.mode=true -cp "target/classes;target/test-classes;<cp.txt>" com.mhtextractor.App
# 结果写入 logs/test-results.txt（当前基线：30 passed / 0 failed；zh 与 en 各 30/0）
```

**native EXE**（需要 GraalVM/NIK + **MSVC**）：`build-native.cmd`（另有 `.ps1` / `.sh` / `run-native-image.bat`）。
> ⚠️ 见文末「native 打包现状」——本机当前**无法**构建 native（缺 MSVC）。

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
| `businessLayerI18nUsageDoesNotGrow` | 业务层 `I18n.` 引用数不得超过基线（防"业务层继续拼文案"） |
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
| `cache` | **预览缓存（会话级）**：`PreviewCacheKey`（绝对路径+size+mtime+参数指纹+格式版本，SHA-256 目录名）· `PreviewCacheMeta`（`meta.json` schema）· `PreviewCacheStore`（构造注入 `Path root`；`.tmp`→改名→`.complete` 原子写；`totalBytes()`）· `PreviewCacheLifecycle`（四条删除规则集中实现 + 每项目写锁 + 10GiB 台阶提醒）· `DirectoryImageSource`（从缓存目录读图） |
| `config` | `AppConfig`（Jackson POJO；**命名配置只存提取参数**，`inputFolder`/`outputFolder`/`imageModeEnabled`/`openFolderAfterComplete` 为 `@JsonIgnore` 运行时字段；含 `imageExtractionFingerprint()`）· `ConfigManager`（`config/` 命名配置 CRUD）· `GuiState`/`GuiStateManager`（`logs/gui-state.json`：模式/目录/开关/**locale**） |
| `i18n` | **`I18n`**（唯一文案入口；具名占位符 `{name}`；缺键返回 `!key!` + `log.error`）· **`ErrorCode`**（20 个错误码，键 `error.<CODE>`）· 两份 bundle `src/main/resources/i18n/messages_zh_CN.properties` / `messages_en_US.properties`（**276 键，键集必须一致**，由 `I18nTest` 断言） |
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
                        //   共享构造：new WorkspaceModel(list) —— 与 GUI 共用同一份数据序列表（见 H33 §二）
                        //   + displayOrder/topLevel/allItems/visibleCount/groups/undoDepth/nextGroupNo
WorkspaceParamService   // 参数变更与移除的缓存规则收口（13 处调用点归一到此类）
ExtractionPreconditions // 提取前置校验规则（含 Level 分级 + Denial），把规则从 GUI 移出
```

> **`app` 层是"业务可脱离 UI 运行"的验收物**：它零 JavaFX，`LayerBTest`（6 测试）与 `StubUiAcceptanceTest`（3 测试）证明"用非 JavaFX 的桩 UI 走完 导入→校验→建组→参数→移除/撤销→图片提取→文本提取"并与产物基线逐字节一致。

### GUI

- **`MainController`** — FXML 控制器（**4334 行**，`ArchitectureTest.mainControllerIsShrinking` 强制**只减不增**，上限常量 **4370**，余量 36）：模式切换、输入/输出目录、文件列表（拖拽排序/分组/勾选/章节徽标）、文本与图片预览、预解析线程、提取任务启停、`logs/gui-state.json` 持久化。持有两个按模式确认的参数缓存（null = 该模式未确认参数）。
  > 会话 18 起持续减重：6111 → 5109（外迁演练代码）→ 5002（外迁 `PreviewItem`）→ 4985 → **4935**（步骤 2 预览编排 → `app.PreviewService`）→ **4435**（步骤 3 `PreviewListCell` 外迁为顶层类）→ **4334**（WorkspaceModel 接入生产：导入/移除/整组移除/建组/拆分组/撤销应用六条路径全部切到模型）。列表单元格渲染/拖拽已不在本类。
  > ✅ 数据序（`items`）与 `WorkspaceModel` **共享同一份列表**，GUI 对它的**直接**写入点已 **归零**，由第 11 条不变式 `guiDoesNotMutateDataOrderDirectly` 持续强制（该断言同时把"间接写"的处数打印出来）。⚠️ 边界：另有 **2 处间接写** —— 把 `items` 交给 util 层方法改写：`DropResolver.apply(items, …)`（拖拽正向，:2222）与 `GroupStructure.restoreStructure(items, …)`（拖拽类撤销，:2975）。两者都在**有意不迁移**的拖拽路径（REORDER / MERGE_GROUPS）上，决策依据见 `H33.md` §六.2。
- **`ParamDialogController`** — 「提取参数设置」模态弹窗；仅「确定」提交；底部固定行是命名配置 CRUD。
- **`LogTextArea`** — 自定义 JavaFX 组件，经 `TextAreaAppender` 注册为 Logback consumer（日志进界面）。
- **`MainView.fxml` / `ParamDialog.fxml`** — 全部文案为 `%key`（经 `loader.setResources(I18n.bundle())` 注入），**不得再出现中文字面量**。
- **`com.mhtextractor.platform`（步骤 5 新建）** — 与操作系统交互的基础设施，**零 JavaFX**，已纳入"零 UI 框架"不变式扫描：
  - `BrowserLauncher`（159 行，**门面**）— `openHtml` / `openViewSource` + 启动三级兜底（直接起 exe → `cmd start` → `rundll32 url.dll,FileProtocolHandler`）
  - `SourceViewPage`（314）— 源码视图页生成（HTML 转义 + 行号 + 语法高亮 + 链接下划线）。页头文案由**调用方**传入（本层不产本地化文案）
  - `CdpClient`（320）— CDP 协议客户端 + 调试实例的进程/端口管理（原生 Socket，native-image 安全）
  - `BrowserDetector`（197）— 浏览器探测与识别（注册表 `UserChoice` → `http` 协议命令 → 常见路径 → `where.exe`）
- `JavaFxUtil`（留在 `gui.util`：确实依赖 JavaFX）。⚠️ 其中 `SmoothProgressProperty` 与 `runLater`/`isFxThread`/`createSmoothProgressProperty` 已在 2026-09-27 的清理中作为**零引用死代码**删除；`reflect-config.json` 里仍有指向它的旧条目（悬空，见下文 reflect-config 说明）。

### 测试代码位置（重要）

演练与自动化测试**已从生产类外迁**：

- **`src/test/java/com/mhtextractor/gui/controller/FeatureExercise.java`**（约 1250 行）— 承载 `exerciseAllFeatures()`（追踪 agent 演练）与 `runAutomatedTests()`（30 项 GUI 断言）
- `App.java` 通过**反射** `invokeFeatureExercise("exerciseAllFeatures" | "runAutomatedTests")` 调用
- **好处**：`MainController` 不再内嵌测试代码（A-2 自锁解除），且测试代码**不进生产 jar / 不进 native 镜像**
- 反射是字符串形式，故 `App` 源码中不会出现 `FeatureExercise` 字面量（不触发无头包扫描守卫）

### 测试与基线资产

| 资产 | 位置 | 规模 |
|---|---|---|
| 单元测试 | `src/test/java` | **406 测试 / 35 类**（`util`/`processor` 覆盖最好） |
| MHT 夹具 | `src/test/resources/fixtures/` | 11 个（simple-text / single-image / multi-image / data-uri / data-uri-mixed / region / titles / cn-filename / garbage / deep-nesting / no-cte） |
| **产物黄金基线** | `src/test/resources/baselines/` | **32 份**（16 例 × zh/en）——重构的字节级标尺；只证明"没变"不证明"对"，已知缺陷以 `knownIssues` 标注 |
| 架构不变式 | `src/test/java/com/mhtextractor/baseline/ArchitectureTest.java` | 11 条 |

> **回归门槛（每个改动都必须全绿）**：`mvn test` = 默认执行 **406/0**（35 类）+ `headless-layer-b` **53/0**；
> 产物基线 **32 份**；架构不变式 **11 条**；GUI 自动化 **zh 30/0、en 30/0**。
> 两条约束：`MainController` ≤ **4370** 行（实测 4334，**刻意留 36 行余量** —— 零余量会让一次常规清理直接打红，见 `ArchitectureTest` 常量 javadoc）、`/* test-support */` 标记 ≤ **44** 个（实测 44，顶格）—— 均**只减不增**。

---

## 国际化约定（新增）

- **唯一入口 `I18n.get(key, args...)`**；`args` 为**交替键值对**：`I18n.get("extract.groupFileCount", "group", "组1", "count", 3)`
- **禁止**业务代码直接 `ResourceBundle.getBundle`
- **禁止**在 FXML 写中文字面量（用 `%key`）
- 缺键 → 返回 `!key!` + `log.error`（**不返回 null、不原样返回键名**）：界面上 `!key!` 一眼可辨，且 `I18nAcceptanceTest` 会断言活跃 UI 键渲染后不含 `!`
- **两份 bundle 键集必须完全一致**（`I18nTest` 断言）
- **不国际化**：`log.*` 中文（面向开发者）、测试/演练代码、**持久化字段名**（`meta.json` / `gui-state.json` 的 JSON key 属语言无关契约）
- 默认输出名：**中文 locale 保留既有行为**（`组N` / `输出文件`），仅 `en_US` 用英文（`Group N` / `Output Files`）——老用户零影响

---

## 预览缓存约定（新增）

- 位置：`<java.io.tmpdir>/mht-extractor-preview/`（**会话级**，退出清空）；与 `<tmpdir>/mht-extractor-view/`（`HtmlExportBuilder`）并列
- 目录结构：`<64位SHA256>/ { meta.json, images/, .complete }`；写入中为 `<key>.tmp/`
- **命中判定四要素**：绝对路径 + size + mtime + `AppConfig.imageExtractionFingerprint()` + `FORMAT_VERSION`
- **四条删除规则**（集中在 `PreviewCacheLifecycle`，不得散落）：(a) 参数变化 (b) 提取完成 (c) 项目移除 (d) 退出清空
- 每 10 GiB 台阶弹**非模态**提醒（不限制使用）
- **`FORMAT_VERSION` 递增即让全部旧缓存失效**——改动 `PreviewCacheMeta` 结构语义时必须递增

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

## 🚨 native 打包现状（截至 2026-09-27）

**目标产物**：GraalVM native EXE **绿色免安装版**（`MhtExtractor.exe` + DLL，双击即用、无需装 JDK/JRE）。

| 项 | 状态 |
|---|---|
| NIK 自带 native-image | ✅ 可用（`D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3\bin\native-image.cmd`，`native-image 25.0.3`）—— **无需单独安装 GraalVM** |
| **MSVC 工具链** | ❌ **缺失，且是唯一卡点**。已实测钉死：`vswhere.exe` 不在任何标准位置、**C:/D: 全盘（深度 5）无 `cl.exe`/`vswhere.exe`**、无 VS 安装目录、无 Windows SDK 注册表项。最小 HelloWorld 探针 `exit=20`：`Failed to find 'vswhere.exe' for locating Visual Studio installations.`（`native-image` 在 Windows 上**只支持 MSVC**，不支持 MinGW） |
| 打包脚本 | ✅ **已修好（2026-09-27）**：四个脚本归一为 `build-native.cmd` **唯一实现**，其余入口委托调用；自动探测 JDK / native-image / Maven / 输出目录；MSVC 缺失时**提前** `exit 20` |
| JVM 侧链路 | ✅ **已端到端验证**：`build-native.cmd --prepare-only` → `exit 0`、Maven `BUILD SUCCESS`、**12 个 DLL** 正确落到 `target\jfx-dlls`（**含 `msvcp140*.dll` / `vcruntime140*.dll`** —— 证实 VC 运行库确实在 `javafx-graphics-*-win.jar` 内，绿色分发来源成立） |
| exe 产出与启动 | ❌ **未验证**（受 MSVC 阻塞） |

### 解除方式（需管理员）

```bat
curl -L -o vs_BuildTools.exe https://aka.ms/vs/17/release/vs_BuildTools.exe
vs_BuildTools.exe --quiet --wait --norestart --nocache --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended
```
装完 `vswhere.exe` 出现在 `C:\Program Files (x86)\Microsoft Visual Studio\Installer\`，重跑脚本即可。
> 本机 `winget` **源打不开**（`0x8A15000B`），故走引导器直下；引导器已实测可下载（4.48 MB）。

### 构建用法

```bat
build-native.cmd                 完整构建：预检 -> mvn package -> 抽 DLL -> native-image -> 组装 dist
build-native.cmd --prepare-only  只做 JVM 侧准备（不调用 native-image）—— 无 MSVC 时也能跑
build-native.cmd --skip-package  跳过 mvn package
build-native.cmd --no-editbin    保留控制台子系统（便于看启动期崩溃）
```
环境变量覆盖：`BUILD_JAVA_HOME` / `GRAALVM_HOME` / `MAVEN_CMD` / `OUTPUT_DIR`（默认 `<仓库>\dist`，已在 `.gitignore`）/ `NATIVE_XMX`（默认 4G，原脚本写死 8G，本机总内存约 15 GB）/ `VSWHERE`。

### ⚠️ 两个已实测的坑（改脚本前必读）

1. **编译 JDK 与 native-image JDK 必须独立解析**：本机机器级 `JAVA_HOME=D:\install\New Folder`
   是**纯 JDK 25（有 `javac`、没有 `native-image`）**。写成"先定编译 JDK、再只在它里面找
   native-image"会必然误报"找不到 native-image"。
2. **`dir` 的 `/s` 不支持中间目录通配**：`dists\apache-maven-*\*\bin\mvn.cmd` 恒无输出；
   必须把 `mvn.cmd` 作为**文件名**交给 `/s`（wrapper 真实层级是三层）。

### ❓ 仍需实测确认（拿到 MSVC 之后）

`-Djava.library.path="target/jfx-dlls"` 会把**相对路径**烘焙进镜像。Windows 加载 DLL 时默认
先搜 exe 所在目录，故"绿色分发"大概率成立 —— 但**必须实测**：把 `dist` 拷到别处、
从任意工作目录双击 `MhtExtractor.exe`，确认能启动并完成一次真实提取。

### ✅ 无头执行（`headless-layer-b`）——**已修复（步骤 0，2026-09-26）**

> 本节原先记录"该执行失败、`mvn test` 恒有 1 个失败、无 JavaFX classpath 在本机物理不可达"。
> **该结论已作废**，原因与修法如下（详见 `docs/handoff/H19.md`）。

让 `headless-layer-b` 真正"无 JavaFX"需要**两个条件同时满足**，缺一不可：

| 条件 | 手段 | 管什么 |
|---|---|---|
| **A** | `argLine` 把 boot layer 限定在 `java.se` | **JDK 模块层**：Liberica `Full` JDK 把 JavaFX 内置为 7 个运行时模块，只剔 Maven 依赖无效 |
| **B** | `classpathDependencyExcludes` 剔干净 jar | **classpath（未命名模块）**：argLine 只约束模块层，classpath 上的同名类仍可加载 |

**为什么必须两个**（实测反例）：把 `javafx-base-win.jar` 放进 classpath 后，
**加不加该 argLine 都能 `Class.forName` 成功** —— 即 argLine 对 classpath 无效；
反过来，只剔 classpath 也挡不住 JDK 内置模块。

**并且**排除坐标必须**带 classifier 与不带 classifier 各写一遍**：
`classpathDependencyExcludes` 只排除与给出的 `groupId:artifactId` **完全相等**的坐标、不做前缀匹配，
而实际依赖是 `org.openjfx:javafx-*:win` 且另带出无 classifier 的传递变体
（`mvn -X` 核对 forked classpath 证实修复前两种 jar 同时在场）。

> 修完后 `LayerBTest` 的原断言**一字未改**即通过（证明力完整保留，未降级为 `assumeTrue`），
> `mvn test` 两个执行全绿。

### 其它已知缺陷（登记未修）

| 缺陷 | 位置 | 影响 |
|---|---|---|
| ~~纯 Data URI 的 MHT 永远提取不到图片~~ | ✅ **已修复（步骤 7a，2026-09-26）** | 有**两个**根因：① `ImageExtractor.extractAllImagesInOrder` 在 `imageInfoList` 为空时提前 return，使同方法末尾的 Data URI 提取成为**死代码**；② `ImageBatchProcessor` 的"无图片内容"快速检查只看 `cidMap`/`locationDataMap`。两处均已修；`data-uri.mht` 由 `SKIPPED` 变为 `SUCCESS`（提取出 2 张图），基线同步更新。详见 `docs/handoff/H30.md` §四 |
| 业务层仍在产"已解析文案" | `ImageExtractor` 经 `I18n.get` 产 warning 文案 → 写入 `PreviewCacheMeta.warnings` | 缓存内警告语言取决于预览时 locale；彻底修需业务层产码值，会触及缓存 schema |
| `AD_KEYWORDS` 硬编码 | `ImageExtractor` | 若参数化，**必须同步加入 `imageExtractionFingerprint()`**，否则预览缓存串味 |
| `BrowserLauncher` 包归属 | ✅ **已修复（步骤 5，2026-09-26）** | 已迁出 `gui.util` 到新建的 `com.mhtextractor.platform`，并按 4 类职责拆分：`BrowserLauncher`（159 行门面）/ `SourceViewPage`（314）/ `CdpClient`（320）/ `BrowserDetector`（197）。`platform` 包已纳入"零 UI 框架"不变式扫描 |
| `reflect-config.json` 与统一元数据并存 | `src/main/resources/META-INF/native-image/` | 🚨 **不可直接删除**（H31 §四 实测）：旧格式 **291** 个类型、统一格式 **194** 个，其中 **231 个仅存在于旧格式**；native-image 对同 type 取**并集**，删旧文件会丢 231 条。且**本机 native 被 MSVC 阻塞、无法验证**，在无法验证时改元数据风险 > 收益 —— 正解是"并集合并 + 方法/字段级对账（丢失数=0）"。另：该文件里指向 `JavaFxUtil$SmoothProgressProperty` 的条目已**悬空**（该类已被删除） |
| 工作区有 22/105 个 java 文件是 CRLF | 全仓库 | 与 `.gitattributes` 的 `*.java text eol=lf` 不符。因 `eol` 规则在 `git add` 时会规范化为 LF，**仓库内存储不受影响**；但会让 `git status` 反复告警。一次性修法：`git add --renormalize .` |

---

## 本机 DSH / Claude CLI 环境陷阱（动手跑命令前先读）

- **会话权限必须是完全权限 / `danger-full-access`**：`read-only` / `workspace-write` 下受限令牌会导致**所有子进程启动失败**，报 `0xC0000142`（十进制 `3221225794`，`STATUS_DLL_INIT_FAILED`）。
- 本机**没有 pwsh 7**：工具描述中的 `pwsh -Command` 实际由 **Windows PowerShell 5.1** 执行。
- **DSH 传给 pwsh 的 stdin 是空的**：不要靠管道向脚本喂数据，用文件或 argv。**中文放文件或 argv 是安全的**（PS 5.1 经 stdin 传中文会乱码）。
- **PS 5.1 的自动变量陷阱**：脚本里**不要**写 `$args = @(...)`（`$args` 是自动变量，赋值会静默破坏整个脚本作用域，表现为 0 字节输出）。
- **执行策略**：`CurrentUser=RemoteSigned` 会覆盖 `LocalMachine=Bypass`，`.ps1` 无法直接运行 → 用 `-ExecutionPolicy Bypass`，或经 `[scriptblock]::Create((Get-Content $f -Raw -Encoding UTF8))` 执行。
- **写无 BOM 的 UTF-8**：`Set-Content -Encoding UTF8` 会写 BOM（Node 解析失败），须用
  `[System.IO.File]::WriteAllText($p,$s,(New-Object System.Text.UTF8Encoding($false)))`
- **PS 参数被吞**：`-Dfoo=bar` 形式的参数会被 PowerShell 解析坏（`ClassNotFoundException: /mode=true`），**用参数数组 `@(...)` 传**。
- 🚨 **"PowerShell 数行比 Java 少"的真正原因是编码，不是换行**（2026-09-27 实测定位）：
  PS 5.1 的 `Get-Content` **不带 `-Encoding` 时按系统 ANSI(GBK) 读** UTF-8 无 BOM 文件，
  DBCS 解码回退会**吞掉"以非 ASCII 字符结尾那一行"的换行符**，把行合并。
  实测同一个 `MainController.java`（纯 LF、4443 个 LF 字节）：
  `Get-Content`（默认）= **4277**，`Get-Content -Encoding UTF8` = **4443**，`ReadAllLines` = **4443** —— 少的 166 行正好是"行尾是中文"的行数。
  **因此：数行/读源码一律显式 `-Encoding UTF8`（或直接读字节），不要用默认 `Get-Content`。**
  同理，`.claude-work/*.ps1` 里读源码的 `Get-Content` 调用都应补上该参数（当前 13 处里只有 2 处带）。
- **驱动 Claude Code**：直连原生 exe `%APPDATA%\npm\node_modules\@anthropic-ai\claude-code\bin\claude.exe`，
  **不要用 `shell: true` 或 `.cmd` shim**（触发 `DEP0190` 注入风险警告）。提示词走 stdin（写后必须 `end()`）。
- **git 身份未配置**：`git commit` 会报 `Author identity unknown`，须内联
  `git -c user.name="Administrator" -c user.email="admin@mht-extractor.local" commit -m "..."`（**不要** `git config`，那会改用户环境）。
- **`git` 忽略规则已锚定到仓库根**：`.gitignore` 用 `/config/`、`/logs/`、`/temp/`、`/输出文件/`。**不要改回不带前导 `/` 的写法**——那样会误伤 `src/test/java/com/mhtextractor/config/`，导致新测试文件被静默忽略（本项目真发生过）。
- **不要整文件读取 `MainController.java`**（**4334 行**）：用 `grep -n` 定位 + 区间读，否则上下文会被吃掉。
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

**并且：验证脚本自身也可能是错的。** 本项目三次被自己的验证脚本误导——`mvn -q` 吞掉 surefire 摘要（误判 0 测试）；正则 `[,)]` 漏掉分号结尾的枚举常量（误判"少一个"）；`$null -eq $int` 判断不可靠（误判"全部未完成"）。**对"不一致"要再核一遍再下结论。**
