# 改动后待验证的 GraalVM 打包流程说明

> 本文档面向**打包电脑上的智能体/操作者**：说明**当前代码状态**（2026-09 架构重构 + 全量国际化 + 预览缓存持久化之后）对 GraalVM native-image 打包的影响、需执行的步骤与验收清单。
> 打包总体流程以《基于graalvm的MHT资源提取器项目打包工作流程.md》为准，本文只补充**变动相关项**。
>
> **最后更新：2026-09-26**（架构重构 + 国际化 + 预览缓存之后）

---

## 零、先看这个：当前的阻塞与必改项

| 项 | 状态 | 必须做的 |
|---|---|---|
| **MSVC 工具链** | ⛔ **本机缺失 → native 构建无法进行** | 安装 **VS 2022 Build Tools 17.6+**（勾「使用 C++ 的桌面开发」+「MSVC v143」+「Windows SDK」）。**这是硬阻塞**：实测最小 HelloWorld 也报 `Failed to find 'vswhere.exe'`（exit=20） |
| **独立 GraalVM** | ✅ **不需要** | NIK 25.0.3 自带 `native-image 25.0.3`（实测可用）。`build-native.cmd:42` 的 `GRAALVM_HOME` 直接指向 NIK 目录即可 |
| `build-native.cmd` 的 **4 处 `X:\` 硬编码** | ❌ 全部不可用（本机无 X: 盘） | 改 `:39 BUILD_JAVA_HOME`、`:42 GRAALVM_HOME`、`:45 MAVEN_HOME`、`:80 OUTPUT_DIR`（取值见打包工作流程文档 §5.1） |
| `-J-Xmx8G` | ⚠️ 偏高 | 下调至 `-J-Xmx6G`（本机总内存约 15 GB / 可用约 10 GB） |
| **元数据重采** | ⚠️ **必做** | 本次重构/国际化引入了新的反射与资源需求，见 §二 |

> **一句话**：**先把 MSVC 装上、把脚本 4 处路径改掉**，否则后面所有步骤都无法验证。

---

## 一、本次改动对打包的影响总览

### 1.1 架构重构（新增零 JavaFX 的 `app` 应用层）

| 改动 | 对 native 打包的影响 | 需要做的 |
|---|---|---|
| 新增 `com.mhtextractor.app`（`MhtExtractorApp` / `WorkspaceModel` / `WorkspaceParamService` / `ExtractionPreconditions`） | **纯逻辑类，静态可达**，无反射、无 Jackson 参与 | **无需反射条目** |
| 新增 `com.mhtextractor.model`（`PreviewItem` / `ThumbnailData`） | 同上（`ThumbnailData` 持 `int[]` ARGB 像素，无 AWT/ImageIO 依赖，对 native 友好） | **无需反射条目** |
| 1139 行演练/测试代码从 `MainController` 外迁至**测试源集** `src/test/java/.../FeatureExercise.java` | `App.java` 通过**反射**调用 `invokeFeatureExercise(...)`。**类名以字符串形式出现**（`"com.mhtextractor.gui.controller.FeatureExercise"`），**可能需要在 reflect-config 中注册该类及其 `exerciseAllFeatures` / `runAutomatedTests` 方法** | ⚠️ **用 agent 采集确认**（`-Dtest.mode=true` 或 `-Dagent.exit.delay` 演练时会触发）；若 agent 未捕获而 native 下 test.mode 报 `NoSuchMethodException`，需手工补 |
| `MainController` 6111 → 约 4800 行 | 无影响 | 无 |
| `ArchitectureTest` 的 10 条不变式 | 纯测试，不进产物 | 无 |

### 1.2 全量国际化（zh_CN / en_US）

| 改动 | 影响 | 需要做的 |
|---|---|---|
| 新增 `com.mhtextractor.i18n`（`I18n` / `ErrorCode`） | 纯静态调用；`ErrorCode` 是枚举，**不经反射/Jackson** | **无需反射条目**（agent 实测为 0 条） |
| 新增资源 `src/main/resources/i18n/messages_zh_CN.properties` / `messages_en_US.properties`（276 键） | **必须做资源注册**，否则 native 下 bundle 加载失败、界面全是 `!key!` | 已在 `reachability-metadata.json` 注册 `{"bundle": "i18n/messages"}` + 各 locale 变体 glob（见 §二） |
| FXML 文案改为 `%key`（`loader.setResources(I18n.bundle())`） | 不影响 FXML 反射需求（字段注入照旧） | 无（但需确认 `resource-config.json` 仍含两个 FXML） |
| `GuiState` 新增 `locale` 字段 | **Jackson 序列化字段变化** → 需 `getLocale`/`setLocale` 反射 | 已补（与既有 `getInputFolder`/`setInputFolder` 同构） |

### 1.3 预览缓存持久化（会话级缓存）

| 改动 | 影响 | 需要做的 |
|---|---|---|
| 新增 `com.mhtextractor.cache`（5 类） | `PreviewCacheStore` 用 **Jackson** 读写 `PreviewCacheMeta`（record + 嵌套 record `Source`/`ImageEntry`）→ **需要反射条目**，否则 **native 下预览缓存落盘/读取报错**（而 JVM 下完全正常，是最隐蔽的一类问题） | **已补 3 个类型的反射条目**（见 §二）；**改 `PreviewCacheMeta` 字段结构后必须重采** |
| 缓存位置 `<java.io.tmpdir>/mht-extractor-preview/` | 与 `HtmlExportBuilder` 的 `<tmpdir>/mht-extractor-view/` 并列；**会话级，退出清空** | 无（不涉及打包） |
| 每 10 GiB 台阶非模态提醒 | 纯 JavaFX Alert，无新依赖 | 无 |

### 1.4 其它

| 项 | 影响 |
|---|---|
| 依赖未变（仍为 Jakarta Mail / Jsoup / Jackson / glavo-webp / AtlantaFX / Logback / Ikonli） | ✅ `ArchitectureTest.noNewThirdPartyDependencies` 保证不新增依赖 |
| AWT 规避（`Runtime.exec("explorer.exe")` 替代 `Desktop.open`） | ✅ 保持（native 不支持 AWT） |
| 日志仍走 Logback + `TextAreaAppender` | ✅ 元数据既有条目不变 |
| **`reflect-config.json`（旧格式，291 条）已过时** | ⚠️ 不含 `cache` 包与近期新增类；与 `reachability-metadata.json` 并存且不一致。建议**确认后删除**，只留统一格式（native-image 对同 type 取并集，故补统一格式即可） |

---

## 二、必须核对的元数据增量（本次改动引入）

### 2.1 已登记的反射条目（Jackson DTO）

| 类型 | 反射需求 | 来源 |
|---|---|---|
| `com.mhtextractor.cache.PreviewCacheMeta` | `<init>(int, Source, String, boolean, List, List)` + `formatVersion`/`imageMode`/`images`/`paramFingerprint`/`source`/`warnings` | 预览缓存 |
| `PreviewCacheMeta$Source` | `<init>(String, long, long)` + `absolutePath`/`mtime`/`size` | 同上 |
| `PreviewCacheMeta$ImageEntry` | `<init>(String, String, String, String, List)` + `detectedExt`/`file`/`originalName`/`sourceId`/`warnings` | 同上 |
| `com.mhtextractor.config.AppConfig` | 全 getter/setter（既有，字段有增） | 命名配置 |
| `com.mhtextractor.config.GuiState` | `<init>` + getter/setter（**新增 `getLocale`/`setLocale`**） | `gui-state.json` |

### 2.2 已登记的资源条目（i18n）

```json
{ "bundle": "i18n/messages" }
{ "glob": "i18n/messages{,_en,_en_US,_zh,_zh_CN,_zh_Hans,_zh_Hans_CN}.properties" }
{ "glob": "META-INF/services/java.util.spi.ResourceBundleControlProvider" }
{ "glob": "com/sun/javafx/scene/control/skin/resources/controls_en{,_US}.properties", "module": "javafx.controls" }
```

> **重要实测结论**：i18n **不需要** `ResourceBundle` / `PropertyResourceBundle` / `Locale` 的反射条目
> （agent 实测 **0 条**）—— classpath bundle 走 `new PropertyResourceBundle(stream)` **直接构造**路径。
> 曾判断错方向，记录下来避免重犯。

### 2.3 采集与合并纪律（三条硬要求）

1. **用 tracing agent 采集，不许手工推断**（NIK 自带，JVM 下即可）：
   ```bash
   java -agentlib:native-image-agent=config-output-dir=target/agent-out \
        -Dtest.mode=true -cp "target/classes;target/test-classes;<cp.txt>" com.mhtextractor.App
   # 若报 "Can't find dependent libraries"：
   #   -agentpath=<NIK>/bin/native-image-agent.dll=config-output-dir=target/agent-out
   # 建议 zh / en 各跑一轮，确保两份 bundle 都被采集
   ```
2. **按并集合并，勿直接覆盖**（旧条目有些新演练捕获不到但运行必需）
3. **核对纯增量 + 方法级对账**：`git diff --numstat` 不应出现大片无关注释/重排；
   合并前后各 type 的 fields/methods **丢失数必须为 0**
   > 合格样本（本项目一次实测）：`reflection 201 → 192`（去重 12 个重复 type）、方法丢失 **0**、`git diff` = `+61 / −0`

### 2.4 ⚠️ 不要往 `src/main/resources` 放临时文件

Maven 会把该目录**全部**内容打进 jar。本项目真发生过
`reachability-metadata.json.bak` 被打进产物（`jar tf` 才发现）。备份放仓库外或 `.claude-work/`。

---

## 三、打包电脑上的操作流程

1. **拉取最新代码**；
2. **前置检查**：`java --list-modules | findstr javafx`（确认有 7 个 javafx 模块）、
   `where vswhere.exe` 或 `C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe` 是否存在、
   `pom.xml` 的 `<version>`；
3. **改 `build-native.cmd` 的 4 处路径**（见 §零 与打包工作流程文档 §5.1），并下调 `-Xmx`；
4. **编译**：`"%MAVEN_HOME%\bin\mvn.cmd" clean package -DskipTests`，`JAVA_HOME` 指向 NIK 25；
5. **（若元数据有改动）重采并合并**：按 §二.3 执行，先核对 `git diff --numstat` 是纯增量；
6. **跑 JVM 侧回归**（确认改动没破坏功能，再花时间做 native）：
   ```bash
   # 单测（注意：当前有 1 个已知失败，见 §四）
   "%MAVEN_HOME%\bin\mvn.cmd" test
   # GUI 自动化（勿用 mvn javafx:run，-D 会被 Maven 吃掉）
   java --module-path <jfx-win-jars> --add-modules javafx.controls,javafx.fxml \
        -Duser.language=zh -Duser.country=CN -Dtest.mode=true \
        -cp "target/classes;target/test-classes;<cp.txt>" com.mhtextractor.App
   # 期望：logs/test-results.txt 中 30 passed / 0 failed
   ```
7. **运行 `build-native.cmd`**（Step 1–6）；
8. **按 §五 清单在 native EXE 上真机验收**；
9. **（可选）native 下复验自动化**：native EXE 加 `-Dtest.mode=true`
   （注意：`test.mode` 走的是**测试源集**的 `FeatureExercise`，需确认该 jar/类在 native 镜像可达——
   若不可达，改为按 §五 手工验收）。

---

## 四、已知失败/阻塞（登记，避免误判）

| 项 | 说明 |
|---|---|
| **`mvn test` 有 1 个失败** | `LayerBTest.runsWithoutUiFrameworkOnClasspathWhenHeadlessExecutionIsActive:218`。根因是 `pom.xml` 的 `headless-layer-b` 执行**无法真正剔除 JavaFX** —— ① 排除坐标缺 classifier；② **决定性：Liberica Full JDK 把 JavaFX 内置为 JDK 模块**，`classpathDependencyExcludes` 剔不掉。**这是构建配置缺陷，非代码 bug。** 修法见打包工作流程文档 §8.6 |
| **native 构建阻塞** | 缺 MSVC（见 §零） |
| **纯 Data URI 的 MHT 提取不到图片** | 已知产品缺陷（`ImageBatchProcessor` 的"无图片内容"快速跳过）。native 验收时该夹具输出为 0 产物属**预期**（基线已标 `knownIssues`） |

---

## 五、native EXE 验收清单

> 本次重构/国际化改动的**重点新增项**已标 ⭐。其余为既有功能回归。

### A. ⭐ 本次改动相关（重点）

| # | 验收项 | 为什么重要 |
|---|---|---|
| 1 | ⭐ **启动后切换语言**：能显示中文；改 `logs/gui-state.json` 的 `"locale":"en_US"` 或 `-Duser.language=en -Duser.country=US` 启动后界面为英文、**无 `!key!` 残留** | 直接验证 **i18n bundle 资源注册**是否生效（最易漏的元数据） |
| 2 | ⭐ **预览图片并观察缓存落盘**：点「查看预览效果」后检查 `%TEMP%\mht-extractor-preview\<64位hex>\` 下有 `meta.json` + `images\` + `.complete` | 直接验证 **Jackson 对 `PreviewCacheMeta` 的反射**是否生效（缺则 native 报错） |
| 3 | ⭐ **再次预览同一文件（命中缓存）**：应立即出缩略图、不再解析 | 验证缓存读取路径（`meta.json` 反序列化） |
| 4 | ⭐ **关窗清空**：有未提取的预览缓存时点 X → 弹「关闭程序前需要清空缓存…」→ 清空后关闭 | 验证关窗链路（`onWindowCloseRequest`） |
| 5 | ⭐ **`test.mode` 演练**：native EXE 带 `-Dtest.mode=true` 能否运行 | 验证 `FeatureExercise` 的**反射可达性**（本次从生产类外迁，新增的反射依赖点） |

### B. 文本提取

| # | 验收项 |
|---|---|
| 1 | 合并输出：多文件 → 单个 TXT，章号连续 |
| 2 | 单独输出：每 MHT 一个 TXT，可含标题命名 |
| 3 | 内容限定：起止标记（字面 / 正则两种模式**都要试**）、模板变量 `{filename}`/`{datetime}` |
| 4 | 标题提取、编码自动修复、章节序号勾选 |

### C. 图片提取

| # | 验收项 |
|---|---|
| 1 | 合并输出（单目录）/ 单独输出（每文件一目录） |
| 2 | 流式 与 高速内存（预加载）两种模式 |
| 3 | 名称提取、区域限定、批量重命名 |
| 4 | ZIP / CBZ 打包 + 归档后删源目录 |
| 5 | 广告过滤、Data URI、魔数扩展名纠正 |

### D. 列表 / 分组 / 撤销 / 拖拽

| # | 验收项 |
|---|---|
| 1 | 文件列表、勾选、全选三态、位置序号、章节徽标 |
| 2 | 合并为组 / 拆分组 / 组名编辑 / 组参数独立与继承 |
| 3 | 撤销（移除/导入/拖拽/组合并/拆分/参数/清除/应用全局） |
| 4 | 拖拽：组内排序、跨组、出组、组行移动、两组合并 |
| 5 | 移除（含整组、空组自动销毁） |

### E. 其它

| # | 验收项 |
|---|---|
| 1 | 参数弹窗打开/确定/取消、命名配置 保存/加载/删除 |
| 2 | 输入/输出目录选择、浏览按钮、完成后自动打开输出文件夹 |
| 3 | 在浏览器中打开 / 查看源码（CDP 原生查看器或回退源码页） |
| 4 | 进度条三态配色、取消提取、失败/跳过/警告可视化回写 |
| 5 | `logs/gui-state.json` 持久化与启动恢复（含 locale） |

---

## 六、故障排查速查

| 症状 | 排查方向 |
|---|---|
| 界面显示 `!key!` 字样 | i18n **bundle 未注册** → 检查 `reachability-metadata.json` 是否有 `{"bundle": "i18n/messages"}` |
| 预览图片报错 / 缩略图全部"无法加载" | **`PreviewCacheMeta` 反射条目缺失** → 检查 §2.1 的三个 cache 类型；用 agent 重采 |
| `test.mode` 报 `NoSuchMethodException` / `ClassNotFoundException: ...FeatureExercise` | 测试源集类未被 native 镜像收录 → 需在 reflect-config 补该类与方法，或改为手工验收 |
| 参数窗口打开报 FXML 加载失败 | `resource-config.json` 缺 `ParamDialog.fxml` / `MainView.fxml` |
| 渲染异常/黑屏：shader 加载失败 | 检查 `reachability-metadata.json` 是否含 `com.sun.prism.shader.*` 与 `.obj$` 资源模式 |
| 缩略图"无法加载"（非缓存问题） | `org.glavo.webp` 条目缺失（agent 演练应已捕获）；或该图不是 WebP/PNG/JPEG/GIF |
| `Error: Failed to find 'vswhere.exe'` | **MSVC 未安装**（见 §零） |
| `error LNK2001: unresolved external symbol Java_com_sun_glass_ui_Application__1overrideNativeWindowHandle` | NIK 静态链接与 JNI 配置不兼容 → native-image 改用 GraalVM 25.0.2，或按 NIK 重采全套元数据 |
| `Module org.graalvm.nativeimage.builder not found` | 工具路径含中文 → 移到纯 ASCII 路径 |
| 构建产物里多出 `.bak` 等文件 | 临时文件放在 `src/main/resources` 下了 → 移出该目录 |
| `mvn test` 有 1 个失败 | 见 §四（已知构建配置缺陷，非代码问题） |
| `-agentlib:native-image-agent` 报 `Can't find dependent libraries` | 改用 `-agentpath=<NIK>/bin/native-image-agent.dll=config-output-dir=...` 全路径形式 |

---

## 七、JVM 侧回归基线（打包前先确认这些是绿的）

| 项 | 基线 |
|---|---|
| 单元测试 | **374 测试 / 33 类**（其中 **1 个已知失败**，见 §四） |
| GUI 自动化（zh） | **30 passed / 0 failed** |
| GUI 自动化（en） | **30 passed / 0 failed** |
| 产物黄金基线 | 32 份（16 例 × zh/en）全部 SHA 一致 |
| 架构不变式 | 10 条（业务层零 JavaFX 等） |

> **跑 GUI 自动化时注意**（本项目踩过）：**不要**用 `mvn javafx:run -Dtest.mode=true` ——
> `-D` 会被 Maven 吃掉、不传进子 JVM，测试根本没跑起来。必须直接 `java` 起（见 §三.6）。
> 另：**en locale 断言会被 `logs/gui-state.json` 里持久化的 locale 覆盖**，跑 en 前先移走该文件（或改其 `locale`）。
