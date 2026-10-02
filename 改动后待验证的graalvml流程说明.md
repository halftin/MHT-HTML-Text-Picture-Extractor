# 改动后待验证的 GraalVM 打包流程说明

> 本文档面向**打包电脑上的智能体/操作者**：说明**当前代码状态**对 GraalVM native-image 打包的影响、需执行的步骤与验收清单。
> 打包总体流程以《基于graalvm的MHT资源提取器项目打包工作流程.md》为准，本文只补充**变动相关项**。
>
> **最后更新：2026-10-02**（✅ **本机已实机打通并逐字节验证产物** —— 见 §零 与 §五；构建细节与踩坑记录见《基于graalvm的MHT资源提取器项目打包工作流程.md》§十）
> 本文件是**打包机操作手册**：先跑 `check-env.cmd` 预检 → 再按第三节构建 → 最后按第五节验收。构建可观测性契约见打包工作流程文档 §6.1 与 `CLAUDE.md`。

---

## 零、先看这个：当前的阻塞与必做项（2026-10-02 复核：**已无阻塞项**）

| 项 | 状态 | 必须做的 |
|---|---|---|
| **MSVC 工具链** | ✅ **已就位（2026-10-02）** | 无需再做任何事。VS **2026** Community 在 `E:\msvc\community`；`vswhere.exe` 在 `C:\Program Files (x86)\Microsoft Visual Studio\Installer\`；Windows SDK 10.0.26100 在 `E:\Windows Kits\10\`。`check-env.cmd` → **exit=0**。⚠️ **VS 装在非 C 盘也能被找到**（关键只是 `vswhere.exe` 在标准位置） |
| **用哪个 native-image（决定配方）** | ✅ **默认 dynamic** | 默认 `NATIVE_RECIPE=dynamic` → 用 **Oracle GraalVM 25.0.2**（`D:\JAVA\graalvm-jdk-25_windows-x64_bin\graalvm-jdk-25.0.2+10.1`）→ **≈70 MB**，dist 随包 7 个 JavaFX DLL。要 ≈98 MB 的 NIK 静态配方就 `set NATIVE_RECIPE=static`（NIK 自带 `native-image`）。两种 JDK 都装时脚本靠配方挑；`check-env.cmd` 的 `[4/9]` 会打印实际选中哪个与配方 |
| ~~`build-native.cmd` 的 4 处 `X:\` 硬编码~~ | ✅ **已修掉（2026-09-27）** | **不用再手改路径**：脚本自动探测 JDK / native-image / Maven / 输出目录；要覆盖就用环境变量（`BUILD_JAVA_HOME` / `GRAALVM_HOME` / `MAVEN_CMD` / `OUTPUT_DIR` / **`NATIVE_RECIPE`（`dynamic` 默认 / `static`）** / `NATIVE_XMX` / `VSWHERE` / `LOG_DIR`） |
| ~~`-J-Xmx8G`~~ | ✅ **已改为 `NATIVE_XMX`，默认 4G** | 机器内存大可用环境变量调高 |
| **四个脚本的关系** | ✅ **已归一** | `build-native.cmd` 是**唯一实现**；`.ps1` / `.sh` / `run-native-image.bat` 只是委托入口（原先四份各写死自己的路径与 VS 版本号） |
| **元数据重采** | ⚠️ **仅当元数据相关代码有改动时才做** | 见 §二 |
| **构建可观测性** | ✅ **已就绪（2026-09-27）** | 打包机上的构建会**实时**输出到控制台**与日志**，并产出机器可读报告 —— 见 §三 第 7 点 |
| **Maven 要不要装** | ✅ **不必预装** | 仓库自带 wrapper（`mvnw` / `mvnw.cmd` / `.mvn/wrapper/`，`only-script` 模式**无 jar**）。实测：清空分发缓存 + Maven 不在 PATH → `build-native.cmd --prepare-only` 仍 `exit=0`（自动下 Maven 3.9.16）。内网可用 `MVNW_REPOURL` 指镜像 |
| **打包前第一步** | ✅ **先跑 `check-env.cmd`** | 9 项预检（PowerShell / JDK / native-image / Maven / MSVC / SDK / 内存磁盘 / git / 网络）+ 修复命令。退出码 **0=就绪 / 20=缺 MSVC / 1=缺其它关键项**，与 `build-native.cmd` 对齐。**别等构建跑到一半才发现缺东西** |
| **排障工具** | ✅ 随仓库分发 | `tools\probes\run-probe.ps1`（UI 探针：布局截断 / 提醒 / 等待卡片 / 关窗残留）、`tools\recovery\*`（文档误改后的恢复）、`tools\diag\tee-probe.cmd`。清单见 `CLAUDE.md` 文末「验证工具」 |

> **一句话**：本机已无阻塞项 —— `check-env.cmd` 应 exit=0，然后直接 `build-native.cmd`。
> 唯一要记住的坑是**输出目录不要落在项目树内**（会让全部图标变空心方框，见 §六），
> 新默认值已经把 dist 放到项目树外了。
>
> **（历史记录）当年如何解除 MSVC 缺失**：装 Visual Studio 任意版本 +「使用 C++ 的桌面开发」工作负载即可；
> 关键是 `vswhere.exe` 必须出现在 `C:\Program Files (x86)\Microsoft Visual Studio\Installer\`（由 VS Installer 放置，
> 与 VS 本体装在哪块盘无关）。

---

## 一、本次改动对打包的影响总览

### 1.1 架构重构（新增零 JavaFX 的 `app` 应用层）

| 改动 | 对 native 打包的影响 | 需要做的 |
|---|---|---|
| 新增 `com.mhtextractor.app`（`MhtExtractorApp` / `WorkspaceModel` / `WorkspaceParamService` / `ExtractionPreconditions`） | **纯逻辑类，静态可达**，无反射、无 Jackson 参与 | **无需反射条目** |
| 新增 `com.mhtextractor.model`（`PreviewItem` / `ThumbnailData`） | 同上（`ThumbnailData` 持 `int[]` ARGB 像素，无 AWT/ImageIO 依赖，对 native 友好） | **无需反射条目** |
| 1139 行演练/测试代码从 `MainController` 外迁至**测试源集** `src/test/java/.../FeatureExercise.java` | `App.java` 通过**反射**调用 `invokeFeatureExercise(...)`。**类名以字符串形式出现**（`"com.mhtextractor.gui.controller.FeatureExercise"`），**可能需要在 reflect-config 中注册该类及其 `exerciseAllFeatures` / `runAutomatedTests` 方法** | ⚠️ **用 agent 采集确认**（`-Dtest.mode=true` 或 `-Dagent.exit.delay` 演练时会触发）；若 agent 未捕获而 native 下 test.mode 报 `NoSuchMethodException`，需手工补 |
| `MainController` 6111 → 约 4800 行 | 无影响 | 无 |
| `ArchitectureTest` 的 11 条不变式 | 纯测试，不进产物 | 无 |

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

1. **拉取最新代码**。
2. **前置检查**：`pom.xml` 的 `<version>`；`where vswhere.exe` 或 `C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe` 是否存在。
   （**不用**再手工核对 4 处路径——脚本已自动探测。）
3. **先跑一次"只准备、不链接"的构建**（不依赖 MSVC，约 12 秒，用来确认脚本与环境就绪）：
   ```bat
   build-native.cmd --prepare-only
   ```
   期望：`exit 0`、Maven `BUILD SUCCESS`、`target\jfx-dlls` 下 **12 个 DLL**（`dynamic` 配方 = 7 个 JavaFX DLL
   + 5 个 VC++ 运行库；`static` 配方则只有那 5 个 —— JavaFX 静态链接进 EXE，放出去会段错误，见 §六），
   并在 `logs\` 下产出 `native-build-<stamp>.log` / `.steps.tsv` / `.report.json` 三件套。
4. **（仅当元数据相关代码有改动时才做）重采并合并**：按 §二.3 执行，先核对 `git diff --numstat` 是纯增量。
5. **跑 JVM 侧回归**（确认功能没坏，再花时间做 native）：
   ```bash
   "%MAVEN_HOME%\bin\mvn.cmd" test        # 期望 420/0（36 类）+ 53/0（headless-layer-b）
   # GUI 自动化（勿用 mvn javafx:run，-D 会被 Maven 吃掉）
   java --module-path <jfx-win-jars> --add-modules javafx.controls,javafx.fxml \
        -Duser.language=zh -Duser.country=CN -Dtest.mode=true \
        -cp "target/classes;target/test-classes;<cp.txt>" com.mhtextractor.App
   # 期望 logs/test-results.txt：31 passed / 0 failed（zh；en 同样 31/0）
   ```
6. **完整构建**：
   ```bat
   build-native.cmd
   ```
   在输出目录产出 **22 个文件 / 约 70 MB**（dynamic）：`MhtExtractor.exe`（70,033,408 B）+ **7 个 JavaFX DLL**
   + native-image 自动落地的 9 个 JDK DLL + 5 个 VC++ 运行库 DLL（"整个目录拷走即用"；**不含** `logs/`）。
   `NATIVE_RECIPE=static` 时是 15 个文件 / 约 98 MB（去掉那 7 个 JavaFX DLL）。
   dynamic 还会多出 `启动.cmd` 与 `Launch.cmd` 两个启动器 —— **放在中文/非 ASCII 路径下必须用它启动**（见 §六）。
   默认输出目录 = `<仓库同级>\<仓库名>-dist`（**项目树外**）。
7. **给智能体的实时跟踪**——构建**进行中**即可读：
   ```powershell
   Get-Content logs\native-build-*.log -Wait -Tail 50
   ```
   三件套与报告的字段含义见《基于graalvm的…打包工作流程.md》§6.1。
   ⚠️ 读正在写的日志请用 `Get-Content`／`FileStream + FileShare.ReadWrite`；**`File.ReadAllText` 会失败**。
8. **按 §五 清单在 native EXE 上真机验收**。
9. **（可选）native 下复验自动化**：native EXE 加 `-Dtest.mode=true`
   （注意：`test.mode` 走的是**测试源集**的 `FeatureExercise`，需确认该 jar/类在 native 镜像可达——
   若不可达，改为按 §五 手工验收）。

---

## 四、已知失败/阻塞（登记，避免误判）

| 项 | 说明 |
|---|---|
| ~~`mvn test` 有 1 个失败~~ | ✅ **已修复（步骤 0，2026-09-26）**。原 `LayerBTest.runsWithoutUiFrameworkOnClasspathWhenHeadlessExecutionIsActive` 失败，根因是 `headless-layer-b` 执行**无法真正剔除 JavaFX**：既需 ① `argLine` 把 boot layer 限定 `java.se`（JDK 模块层），又需 ② `classpathDependencyExcludes` 剔 jar（classpath），**且排除坐标必须带 classifier 与不带 classifier 各写一遍**。现基线 **406/0（默认）+ 53/0（headless）** |
| ~~**native 构建阻塞**~~ | ✅ **已解除（2026-10-02）**：MSVC 就位后 `build-native.cmd` 一次跑通（mvn package 约 1–2 分钟 + native-image **1 分 26 秒**），交付物 15 文件 / 98.2 MB。过程中真实遇到并已修掉的三个坑：**LNK2001**（缺 `prism_common.lib` 链接选项）、**JavaFX DLL 随包段错误**、**元数据缺 `ToggleGroup` 导致 FXML 第 24 行崩** —— 见 §六 |
| ~~纯 Data URI 的 MHT 提取不到图片~~ | ✅ **已修复（步骤 7a，2026-09-26）**。两个根因：① `ImageExtractor.extractAllImagesInOrder` 在 `imageInfoList` 为空时提前 return，使 Data URI 提取成为**死代码**；② `ImageBatchProcessor` 的"无图片内容"快速检查只看 `cidMap`/`locationDataMap`。`data-uri.mht` 由 `SKIPPED` 变 `SUCCESS`（2 张图），基线已同步 |

---

## 五、native EXE 验收清单

> ✅ **2026-10-02：本清单已在 native EXE 上实跑通过，并留下两层证据。**
> - **产物层（最强，替代不了）**：把仓库自己的**产物黄金基线**（`BaselineCases`，16 用例 × zh/en = 32 份，
>   每份含逐产物 SHA-256 + 内容摘要）**包进 native 镜像运行 → 32 passed / 0 failed**（JVM 对照同样 32/0）。
>   与 JVM 产物树逐文件比对：288 / 288 同名，仅归档容器字节差 ZIP 写入时刻（解压后条目 **0 处**不一致）。
>   ⇒ §五.B（文本：合并/单独/章节号/标题命名/区域限定 字面+正则/模板变量）与 §五.C（图片：合并/单独/流式/预加载/
>   名称提取/区域限定/批量重命名/ZIP/CBZ/Data URI/魔数纠正）的**参数语义已在 native 下逐字节对上**。
> - **控件层**：native 镜像内 GUI 自动化 **zh 31/0 + en 31/0**（含 §五.A 的 i18n、预览缓存、关窗清缓存、`test.mode`）。
>   ⚠️ 控件层断言**不验落盘产物**，所以它**不能替代**上面那层。
> - 运行环境：**必须从项目树外启动**（否则图标全变空心方框，见 §六）。
>
> 本次重构/国际化改动的**重点新增项**已标 ⭐。其余为既有功能回归（下面保留为人工过一眼的补充清单）。

> 🚨 **跑 `test.mode` 前先删 `logs/test-results.txt`，跑完核对它的 mtime** —— 否则读到的可能是上一次的 31/0。
> 另：`FeatureExercise.runAutomatedTests()` 里 `new FileWriter("logs/test-results.txt")` 在 `logs\` 不存在时
> 会抛 `FileNotFoundException` 并被外层 `catch (Exception)` 吞掉 → 表现为 **2.4 秒静默 exit 0、零产物**（已修）。

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
| `Error: Failed to find 'vswhere.exe'` | MSVC 未安装（✅ 本机 2026-10-02 已装）。装 VS 任意版本 + C++ 桌面开发即可；**VS 可装在非 C 盘** |
| `error LNK2001: unresolved external symbol Java_com_sun_glass_ui_Application__1overrideNativeWindowHandle` | ✅ **已修**：该符号的实现住在 **`prism_common.lib`**（`dumpbin /EXPORTS` 实测），native-image 自己挑库时没挑它 → 加 `-H:NativeLinkerOption=<NIK>\lib\static\windows-amd64\prism_common.lib`（`build-native.cmd` 已内置）。**不需要**换 GraalVM |
| **中文（非 ASCII）路径下双击 exe → 窗口一闪即逝** | 🚨 **dynamic 配方特有**：SubstrateVM 处理不了 `java.library.path` 里的非 ASCII 目录，而首项就是 EXE 所在目录 → `UnsatisfiedLinkError: Can't load library: prism_d3d` / `Graphics Device initialization failed for : d3d, sw`。**双击 `启动.cmd`**（它把 DLL 暂存到 `%ProgramData%\MhtExtractor\native-dlls` 再指 `-Djava.library.path`）；或 `set NATIVE_RECIPE=static` 换成 98 MB 的静态配方（对路径无要求）；或放进纯 ASCII 路径。<br>⚠️ **空格无害**，工作目录/输入输出目录是中文也不影响；8.3 短路径、`-Djava.library.path=.`、`subst` 盘符都**无效**（实测） |
| **所有 Ikonli 图标变空心方框**（文字/布局/功能都正常） | 🚨 **EXE 位于项目树内**（本机实测；与构建配方无关）。把 dist 挪到项目树外再跑。判据：native 下 `Font.loadFont(...)` 在树内返回 `null`、树外返回真字体（资源本身两处都可读） |
| 应用启动即**段错误**（`ExceptionCode -1073741819`，读地址 0） | 🚨 **只在 static 配方会这样**：dist/EXE 同目录里放了 JavaFX 的 DLL（`glass`/`prism_*`/`javafx_font`/`javafx_iio`/`decora_sse`）。NIK Full 已把 JavaFX 静态链接进 EXE，再放同名 DLL 会双份加载 → static 下只保留 9 个 JDK DLL + 5 个 VC++ 运行库。<br>⚠️ **dynamic 配方恰恰相反**：那 7 个 JavaFX DLL **必须**随包，少了会启动失败 |
| FXML 加载失败：`NoSuchMethodException: javafx.scene.control.ToggleGroup.<init>()`（或其它控件） | 元数据缺口（`<fx:define>` 实例化控件需要其反射构造器）→ 跑 tracing agent 重采并按并集合并（§二.3） |
| `Module org.graalvm.nativeimage.builder not found` | 工具路径含中文 → 移到纯 ASCII 路径 |
| 构建产物里多出 `.bak` 等文件 | 临时文件放在 `src/main/resources` 下了 → 移出该目录 |
| `mvn test` 有 1 个失败 | 见 §四（已知构建配置缺陷，非代码问题） |
| `-agentlib:native-image-agent` 报 `Can't find dependent libraries` | 改用 `-agentpath=<NIK>/bin/native-image-agent.dll=config-output-dir=...` 全路径形式 |

---

## 七、JVM 侧回归基线（打包前先确认这些是绿的）

| 项 | 基线（2026-10-02 复核实测） |
|---|---|
| 单元测试（默认执行） | **420 测试 / 36 类 / 0 失败**（独立聚合 `target/surefire-reports/*.txt` 得出） |
| `headless-layer-b` | **53 / 0** |
| GUI 自动化（zh / en） | **各 31 passed / 0 failed**（JVM 与 **native 镜像内**都是 31/0） |
| 产物黄金基线 | **32 份**（16 例 × zh/en）全部 SHA-256 一致 —— 且 **native 二进制里也 32/0** |
| 架构不变式 | **11 条**（业务层零 JavaFX、GUI 不得直写数据序 等） |
| `MainController` | **4361 行 / 上限 4370**（余量 9 行） |
| 业务层 `I18n.` 引用 | **52**（基线 52，只减不增） |

> **跑 GUI 自动化时注意**（本项目踩过）：**不要**用 `mvn javafx:run -Dtest.mode=true` ——
> `-D` 会被 Maven 吃掉、不传进子 JVM，测试根本没跑起来。必须直接 `java` 起（见 §三.6）。
> 另：**en locale 断言会被 `logs/gui-state.json` 里持久化的 locale 覆盖**，跑 en 前先移走该文件（或改其 `locale`）。
