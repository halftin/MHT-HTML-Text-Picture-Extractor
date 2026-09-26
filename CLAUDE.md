# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **最后更新：2026-09-26**（架构重构 + 全量国际化 + 预览缓存持久化之后）

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

**架构红线（由 `ArchitectureTest` 的 10 条不变式自动强制）**：

| 不变式 | 含义 |
|---|---|
| `businessPackagesHaveNoUiFrameworkDependency` | 业务包（`core`/`extractor`/`processor`/`output`/`util`/`cache`/`config`/`i18n`/`model`/`app`）**不得依赖 `javafx.*`**（按**字节码常量池**扫描，不是查 import —— 全限定名引用也能抓到）。带白名单腐化检测。 |
| `applicationLayerDoesNotDependOnViewOrShell` | `app` 不得 import `gui` / `logging` / `App` |
| `mainControllerIsShrinking` | `MainController` 行数**只减不增**（当前上限值见测试内注释） |
| `guiLayerDoesNotInvokeCacheRulesDirectly` | GUI 不得直接调用预览缓存的删除规则（须经 `WorkspaceParamService`） |
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

WorkspaceModel          // 无头工作区：addItems/group/ungroup/removeItems/undo/clear
                        //   + displayOrder/topLevel/allItems/visibleCount/groups/undoDepth
WorkspaceParamService   // 参数变更与移除的缓存规则收口（13 处调用点归一到此类）
ExtractionPreconditions // 提取前置校验规则（含 Level 分级 + Denial），把规则从 GUI 移出
```

> **`app` 层是"业务可脱离 UI 运行"的验收物**：它零 JavaFX，`LayerBTest`（6 测试）与 `StubUiAcceptanceTest`（3 测试）证明"用非 JavaFX 的桩 UI 走完 导入→校验→建组→参数→移除/撤销→图片提取→文本提取"并与产物基线逐字节一致。

### GUI

- **`MainController`** — FXML 控制器（约 4800 行，**重构中，只减不增**）：模式切换、输入/输出目录、文件列表（拖拽排序/分组/勾选/章节徽标）、文本与图片预览、预解析线程、提取任务启停、`logs/gui-state.json` 持久化。持有两个按模式确认的参数缓存（null = 该模式未确认参数）。
- **`ParamDialogController`** — 「提取参数设置」模态弹窗；仅「确定」提交；底部固定行是命名配置 CRUD。
- **`LogTextArea`** — 自定义 JavaFX 组件，经 `TextAreaAppender` 注册为 Logback consumer（日志进界面）。
- **`MainView.fxml` / `ParamDialog.fxml`** — 全部文案为 `%key`（经 `loader.setResources(I18n.bundle())` 注入），**不得再出现中文字面量**。
- **`gui.util.BrowserLauncher`**（约 840 行，零 JavaFX 但包归属不符，待迁出）— 系统浏览器打开本地 HTML；源码视图经 CDP 原生查看器，失败回退自产源码页。
- `JavaFxUtil` / `SmoothProgressProperty`。

### 测试代码位置（重要）

演练与自动化测试**已从生产类外迁**：

- **`src/test/java/com/mhtextractor/gui/controller/FeatureExercise.java`**（约 1250 行）— 承载 `exerciseAllFeatures()`（追踪 agent 演练）与 `runAutomatedTests()`（30 项 GUI 断言）
- `App.java` 通过**反射** `invokeFeatureExercise("exerciseAllFeatures" | "runAutomatedTests")` 调用
- **好处**：`MainController` 不再内嵌测试代码（A-2 自锁解除），且测试代码**不进生产 jar / 不进 native 镜像**
- 反射是字符串形式，故 `App` 源码中不会出现 `FeatureExercise` 字面量（不触发无头包扫描守卫）

### 测试与基线资产

| 资产 | 位置 | 规模 |
|---|---|---|
| 单元测试 | `src/test/java` | **374 测试 / 33 类**（`util`/`processor` 覆盖最好） |
| MHT 夹具 | `src/test/resources/fixtures/` | 11 个（simple-text / single-image / multi-image / data-uri / data-uri-mixed / region / titles / cn-filename / garbage / deep-nesting / no-cte） |
| **产物黄金基线** | `src/test/resources/baselines/` | **32 份**（16 例 × zh/en）——重构的字节级标尺；只证明"没变"不证明"对"，已知缺陷以 `knownIssues` 标注 |
| 架构不变式 | `src/test/java/com/mhtextractor/baseline/ArchitectureTest.java` | 10 条 |

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
| `reflect-config.json` | ⚠️ **旧格式，已过时且与上者不一致**（不含 cache 包与近期新增类）。建议确认后删除，只保留统一格式 |
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

## 🚨 native 打包现状（截至 2026-09-26）

| 项 | 状态 |
|---|---|
| NIK 自带 native-image | ✅ 可用（`D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3\bin\native-image.cmd`，`native-image 25.0.3`）—— **无需单独安装 GraalVM** |
| **MSVC 工具链** | ❌ **缺失** → native 构建**阻塞**。`native-image` 在 Windows 上**只支持 MSVC**（不支持 MinGW）。实测最小 HelloWorld 也失败：`Error: Failed to find 'vswhere.exe' for locating Visual Studio installations.`（exit=20） |
| `build-native.cmd` 内 4 处硬编码 `X:\` 路径 | ❌ **全部不可用**（本机无 X: 盘）。需改为本机实际路径：`BUILD_JAVA_HOME`/`GRAALVM_HOME` → NIK 目录（NIK 自带 native-image）；`MAVEN_HOME` → wrapper；`OUTPUT_DIR` → 本机可写目录。另建议把 `-J-Xmx8G` 下调（本机总内存约 15 GB） |
| 解除方式 | 安装 **Visual Studio 2022 Build Tools（17.6+）**，勾选「使用 C++ 的桌面开发」+「MSVC v143」+「Windows SDK」 |

### ⚠️ 一个已存在的构建配置缺陷（待修）

`pom.xml` 有第二个 surefire 执行 `headless-layer-b`，试图用 `classpathDependencyExcludes` 把 JavaFX **物理剔除**后跑无头层，以证明"缺 UI 框架仍能跑通"。**该执行当前失败**：

- **原因一**：排除项写的是无 classifier 的 `org.openjfx:javafx-controls`，而实际依赖带 **classifier `win`**（且带出无 classifier 的传递变体），**一个都没匹配上**
- **原因二（决定性）**：**Liberica `Full` JDK 把 JavaFX 内置为 JDK 运行时模块**（`javafx.base@25.0.3` … 共 7 个，见 `jdk/jmods/javafx.base.jmod`）。`classpathDependencyExcludes` 只能剔 Maven 依赖，**剔不掉 JDK 模块**。实测即使剔除 classpath 上全部 openjfx jar，`Class.forName("javafx.application.Platform")` **仍然成功**

> 即"无 JavaFX classpath"在本机**物理不可达**。修法三选一：① 断言降级为 `assumeTrue` 并标注"本机不可验证"；② **用不含 JavaFX 的 JDK**（Liberica `Standard`/`Lite` 或 Temurin）跑该执行；③ 固化为手工脚本 + 证据留档。
> **在当前状态下 `mvn test` 会有 1 个失败**（`LayerBTest.runsWithoutUiFrameworkOnClasspathWhenHeadlessExecutionIsActive`）——这是**已知配置缺陷，非代码 bug**。

### 其它已知缺陷（登记未修）

| 缺陷 | 位置 | 影响 |
|---|---|---|
| **纯 Data URI 的 MHT 永远提取不到图片** | `ImageBatchProcessor` 的"无图片内容"快速跳过（`cidMap` 与 `locationDataMap` 均空 → SKIPPED） | 功能静默失效：有 Data URI 图却报"跳过：无图片内容"。修它会改变产物基线，需配套更新基线 |
| 业务层仍在产"已解析文案" | `ImageExtractor` 经 `I18n.get` 产 warning 文案 → 写入 `PreviewCacheMeta.warnings` | 缓存内警告语言取决于预览时 locale；彻底修需业务层产码值，会触及缓存 schema |
| `AD_KEYWORDS` 硬编码 | `ImageExtractor` | 若参数化，**必须同步加入 `imageExtractionFingerprint()`**，否则预览缓存串味 |
| `BrowserLauncher` 包归属 | `gui.util`（零 JavaFX） | 应迁至基础设施包 |
| `reflect-config.json` 与统一元数据并存不一致 | 见上 | 建议删旧留新 |

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
- **驱动 Claude Code**：直连原生 exe `%APPDATA%\npm\node_modules\@anthropic-ai\claude-code\bin\claude.exe`，
  **不要用 `shell: true` 或 `.cmd` shim**（触发 `DEP0190` 注入风险警告）。提示词走 stdin（写后必须 `end()`）。
- **git 身份未配置**：`git commit` 会报 `Author identity unknown`，须内联
  `git -c user.name="Administrator" -c user.email="admin@mht-extractor.local" commit -m "..."`（**不要** `git config`，那会改用户环境）。
- **`git` 忽略规则已锚定到仓库根**：`.gitignore` 用 `/config/`、`/logs/`、`/temp/`、`/输出文件/`。**不要改回不带前导 `/` 的写法**——那样会误伤 `src/test/java/com/mhtextractor/config/`，导致新测试文件被静默忽略（本项目真发生过）。
- **不要整文件读取 `MainController.java`**（约 4800 行）：用 `grep -n` 定位 + 区间读，否则上下文会被吃掉。
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
