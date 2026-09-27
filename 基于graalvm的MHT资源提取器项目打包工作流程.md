# 基于 GraalVM 的 MHT 资源提取器项目打包工作流程

> **目标**: 将 JavaFX 25 + Maven 项目编译为 GraalVM Native Image 绿色免安装 Windows EXE。
> **适用场景**: 从 Git 拉取代码后，在任何 Windows 设备上完成打包。
> **最后更新**: 2026-09-26 | **已验证 JDK 版本**: Liberica NIK 25.0.3 + Oracle GraalVM 25.0.2+10.1
> **项目版本**: 以 `pom.xml` 的 `<version>` 为**唯一来源**（当前 `3.0.0`）。所有构建脚本（`build-native.cmd` / `.ps1` / `.sh` / `run-native-image.bat`）均从 pom 动态读取，**禁止**在脚本里硬编码 jar 名（历史问题：曾写死 `mhtExtractor-2.3.1.jar` / `-2.2-SNAPSHOT.jar`，与 pom 不一致导致打包找不到 jar）。

> ## 📌 状态更新（2026-09-27 实测）—— **以脚本与 CLAUDE.md 为准**
>
> **权威来源**：`build-native.cmd`（脚本本身）与 `CLAUDE.md` 的「native 打包现状」。本文件是**参考手册**（环境清单 / 元数据体系 / 构建细节）；**打包机上的最短路径**见《改动后待验证的graalvml流程说明.md》。
> 本文档其余章节保留为**背景与原理**；其中的示例路径 / 版本号可能已过时（下表已逐条标注）。
>
> | 项 | 状态 |
> |---|---|
> | **MSVC 工具链** | ⛔ **仍是唯一卡点**。实测钉死：`vswhere.exe` 不在任何标准位置、**C:/D: 全盘（深度 5）无 `cl.exe`/`vswhere.exe`**、无 VS 安装目录、无 Windows SDK 注册表项；最小 HelloWorld 探针 `exit=20`。`native-image` 在 Windows 上**只支持 MSVC**（不支持 MinGW/GCC）。解除见下方命令 |
> | **独立 GraalVM** | ✅ **不再需要**。NIK 25.0.3 **自带 `native-image 25.0.3`**，脚本已把它作为 native-image 来源 |
> | ~~`build-native.cmd` 的 4 处 `X:\` 硬编码~~ | ✅ **已全部修掉（2026-09-27）**：脚本改为**自动探测** JDK / native-image / Maven / 输出目录，且四者均可用环境变量覆盖（`BUILD_JAVA_HOME` / `GRAALVM_HOME` / `MAVEN_CMD` / `OUTPUT_DIR`）。**§5.1 与 §六 里"手工改 4 处路径"的说明已作废** |
> | ~~`-J-Xmx8G`~~ | ✅ 已改为可配置的 `NATIVE_XMX`，**默认 4G**（本机总内存约 15 GB） |
> | **Maven 要不要预装** | ✅ **不必**：仓库自带 wrapper（`mvnw` / `mvnw.cmd` / `.mvn/wrapper/`，`only-script` 模式，**无 jar**）。实测清空分发缓存 + Maven 不在 PATH → `build-native.cmd --prepare-only` 仍 `exit=0` |
> | **打包前第一步** | ✅ **先跑 `check-env.cmd`**（§3.8）：9 项预检 + 修复命令；退出码 **0=就绪 / 20=缺 MSVC / 1=缺其它关键项**，与 `build-native.cmd` 对齐 |
> | **排障工具** | ✅ 随仓库分发：`tools\probes\run-probe.ps1`（UI 探针）、`tools\recovery\*`（文档误改恢复）、`tools\diag\tee-probe.cmd`；清单见 `CLAUDE.md` 文末「验证工具」 |
> | **本机 `mvn` 不在 PATH** | ⚠️ 必须用 Maven wrapper。脚本会自动到 `%USERPROFILE%\.m2\wrapper\dists` 下递归找 `mvn.cmd` |
> | **四个脚本的关系** | ✅ 已归一（2026-09-27）：`build-native.cmd` 是**唯一实现**，`.ps1` / `.sh` / `run-native-image.bat` 只是**委托入口**。原先四份脚本各写死自己的 `X:\` / `Y:\` 路径与 VS 版本号（甚至写到 VS "18"），且 native-image 参数被抄了三份 |
> | **构建可观测性** | ✅ **新增（2026-09-27）**：全过程**实时**同时输出到控制台与日志、每步 `[STEP k/n] BEGIN/END` + ISO 时间、结束时产出**机器可读报告**（输入 jar 与产物清单都带 SHA256）。见 §六 的「构建用法」 |
> | **JavaFX 来源** | ⚠️ **Liberica `Full` JDK 把 JavaFX 25.0.3 内置为 JDK 模块**（7 个：`javafx.base/controls/fxml/graphics/media/swing/web`）。因此 pom 里的 JavaFX 依赖在**编译期**起作用，运行期由 JDK 提供；这会导致"无 JavaFX classpath"类验证在本机物理不可达（详见 `CLAUDE.md`） |
>
> **解除 MSVC 的唯一一步**（需管理员；引导器已实测可下载 4.48 MB）：
>
> ```bat
> curl -L -o vs_BuildTools.exe https://aka.ms/vs/17/release/vs_BuildTools.exe
> vs_BuildTools.exe --quiet --wait --norestart --nocache --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended
> ```
>
> 装完 `vswhere.exe` 出现在 `C:\Program Files (x86)\Microsoft Visual Studio\Installer\`，重跑脚本即可。
> 注：本机 `winget` **源打不开**（`0x8A15000B`），故走引导器直下。
>
> **另需知**：项目已完成架构重构（新增零 JavaFX 的 `app` 应用层）与全量国际化（276 键 + 20 错误码）。
> **这带来两类新的 native 元数据需求**：① Jackson 序列化的纯 DTO（如 `PreviewCacheMeta` 及其嵌套 record）
> 需反射条目；② i18n bundle 需**资源注册** `{"bundle": "i18n/messages"}`。
> **两者均已备齐**，但**改动后必须重采**——见 §4.3 与 §4.9。

---

## 一、项目概况

| 项目 | 详情 |
|------|------|
| 构建工具 | Maven (pom.xml) —— ⚠️ 本机 `mvn` 不在 PATH，用 wrapper + 指定 NIK 为 `JAVA_HOME` |
| 编译 JDK | Liberica NIK 25.0.3（含 JavaFX + native-image，统一编译和构建） |
| Native Image JDK | **NIK 25.0.3 自带的 `native-image`**（独立 GraalVM 不再必需）；历史方案 GraalVM 25.0.2+ 仍可用 |
| JavaFX | 25.0.3 (win classifier)，无 module-info.java，classpath 模式。**注意：Liberica Full JDK 亦内置 JavaFX 模块** |
| 主类 | `com.mhtextractor.App` |
| 关键依赖 | Jackson, Ikonli, AtlantaFX, Jakarta Mail, Jsoup, Logback, Lombok 1.18.44, **glavo webp 0.2.0**（纯 Java，无 JNI） |
| 打包路径 | pom.xml → fat JAR → native-image → EXE + DLLs |
| **架构分层**（2026-09 重构后） | `gui → app → {core, extractor, processor, output, util, cache, config, i18n}`；`model` 为纯数据模型。**业务层与 app 层零 JavaFX**，由 `ArchitectureTest` 的 11 条不变式强制 |
| **国际化** | zh_CN / en_US，276 键（`src/main/resources/i18n/messages_*.properties`）+ 20 错误码 → **native 需资源注册**（见 §4.9） |
| **预览缓存** | 会话级 `<java.io.tmpdir>/mht-extractor-preview/`，Jackson 读写 `PreviewCacheMeta` → **native 需反射条目**（见 §4.9） |
| **测试** | 374 单测 / 33 类；32 份产物黄金基线；30 项 GUI 自动化（`test.mode`，zh/en 各 30/0） |

> **v2.3.1 升级说明**: 编译器升级至 JDK 25（`maven.compiler.release=25`），Lombok 升级至 1.18.44（兼容 JDK 25），JavaFX 升级至 25.0.3。编译和 native-image 不再需要两套 JDK 切换。Liberica NIK 25.0.3 的静态链接与项目现有的 JNI 配置存在兼容性问题（`glass.lib` 缺少符号），当前仍推荐用 GraalVM 25.0.2 执行 native-image 步骤。

> **注意**: 本项目代码中已有一处关键修改——`MainController.java` 中将 `java.awt.Desktop.getDesktop().open(folder)` 替换为 `Runtime.getRuntime().exec("explorer.exe " + path)`。这是因为 AWT 在 native-image 中不被支持。

---

## 二、核心原则（最重要）

### 2.1 编译与构建（v2.3.1 起统一为 JDK 25）

```
阶段1: 源码 → 字节码         使用 Liberica NIK 25.0.3（含 JavaFX）
阶段2: 字节码 → Native EXE   使用 GraalVM 25.0.2（与 JNI 配置兼容）
```

**为什么 native-image 仍用 GraalVM 25.0.2？** NIK 25.0.3 的 native-image 采用静态链接模式（`.lib`），其内置 JavaFX 25 的 `glass.lib` 缺少 `Java_com_sun_glass_ui_Application__1overrideNativeWindowHandle` 符号，导致链接失败。GraalVM 25.0.2 使用动态链接（`.dll`）模式，与项目现有的 JNI 配置兼容。

> **升级方向**: 如需统一为 NIK，需用 Tracing Agent 重新生成 native-image 配置文件（`jni-config.json` 等），匹配 NIK 内置 JavaFX 25 的 JNI 符号表。

### 2.2 封闭世界假设（Closed World）

Native Image 只包含静态分析可达的代码。任何通过 `Class.forName()`、反射 API、JNI `FindClass`、动态代理、资源文件 `getResource()` 访问的代码都**必须显式注册**。遗漏任何一项都会导致运行时崩溃，且错误信息往往不是直观的"缺少配置"，而是 `ClassNotFoundException` 或 `NoSuchMethodError`。

---

## 三、新设备环境检查清单

在开始打包前，逐一检查以下条件。**缺失任何一项都必须先安装**。

### 3.1 JDK 25（编译用）

| 检查项 | 验证命令 |
|--------|----------|
| 是否安装 | `<JDK25_HOME>/bin/java -version` 应输出 `25.x.x` |
| 是否含 JavaFX | `<JDK25_HOME>/bin/java --list-modules \| grep javafx` 应有输出（Liberica Full 或 Azul Zulu FX） |

> v2.3.1 起编译目标升级为 JDK 25（`maven.compiler.release=25`），Lombok 1.18.44 已兼容 JDK 25。推荐使用 Liberica Full JDK 25 或任何包含 JavaFX 的 JDK 25 发行版。

**如果未安装**: 从 [BellSoft Liberica](https://bell-sw.com/pages/downloads/) 下载 "Full JDK 25" Windows x64 版本，安装后记录其 `bin` 目录的上级路径（即 `JAVA_HOME`）。

### 3.2 GraalVM JDK 25+（Native Image 构建用）

| 检查项 | 验证命令 |
|--------|----------|
| 是否安装 | `<GRAALVM_HOME>/bin/java -version` 应输出 `25.x.x` 且包含 `GraalVM` |
| 是否有 native-image | `<GRAALVM_HOME>/bin/native-image.cmd --version` 应正常输出版本号 |
| 是否有 native-image-agent | `<GRAALVM_HOME>/bin/java -agentlib:native-image-agent=help` 应无 "not found" 错误 |

> **注意**: GraalVM Community Edition 和 Oracle GraalVM 均可用。确保版本 ≥ 25.0.0（低版本部分选项不同）。

**如果未安装**: 从 [Oracle GraalVM](https://www.graalvm.org/downloads/) 下载 JDK 25 for Windows x64，解压后记录其路径。

### 3.3 Apache Maven

| 检查项 | 验证命令 |
|--------|----------|
| 是否安装 | `mvn --version` 应正常输出 |

**如果未安装**: 从 [Maven 官网](https://maven.apache.org/download.cgi) 下载 3.9+ 版本，解压后将 `bin` 目录加入 PATH。

### 3.4 Microsoft Visual Studio 2022+（含 MSVC 工具链）

| 检查项 | 验证命令 |
|--------|----------|
| 是否安装 | 在开始菜单搜索 "Developer Command Prompt for VS 2022" 或 "x64 Native Tools" |
| cl.exe 是否可用 | 在 VS 命令行中运行 `cl.exe` 应输出版本信息 |
| link.exe 是否优先 | 在 VS 命令行中运行 `where link.exe`，第一个结果应是 MSVC 的 link.exe，**不是** Git 的 link.exe |

**为什么需要**: Native Image 在 Windows 上使用 MSVC 编译器/链接器生成最终的 .exe 文件。这是硬性依赖。

> **警告**: Git for Windows 会在 `C:\Program Files\Git\usr\bin\` 中携带一个名为 `link.exe` 的工具，它与 MSVC 的 `link.exe` 冲突。VS 的 `vcvars64.bat` 脚本会将 MSVC 路径放在 PATH 最前面来解决此冲突，因此**必须在运行 native-image 的同一个终端中先执行 `vcvars64.bat`**。

**如果未安装**: 下载 [Visual Studio 2022 Community](https://visualstudio.microsoft.com/downloads/)（免费），安装时勾选 "Desktop development with C++" 工作负载。

### 3.5 Windows SDK

Windows SDK 随 Visual Studio 一起安装，无需额外操作。如果未安装，Visual Studio Installer → "Desktop development with C++" → 勾选 "Windows SDK"。

### 3.6 系统资源

| 检查项 | 最低要求 | 推荐 |
|--------|----------|------|
| 内存 | 16 GB | **32 GB 以上**（native-image 是内存大户；脚本默认 `-J-Xmx4G`，可用 `NATIVE_XMX` 调高） |
| 磁盘 | 2 GB 空闲 | 5 GB（含依赖缓存） |
| CPU | 4 核 | 8 核以上（构建更快） |

### 3.7 杀毒软件

构建时建议**临时禁用实时扫描**或将项目目录加入排除列表，否则 native-image 构建可能从几十秒膨胀到数十分钟。

### 3.8 一键检查脚本 —— **已随仓库提供：`check-env.cmd`**

**不要手工复制粘贴脚本**：仓库根目录已有 `check-env.cmd`，打包机上直接运行即可（9 项预检 + 结论 + 修复命令）：

| 检查 | 内容 |
|---|---|
| 1/9 项目文件 | `pom.xml` 可读 + 版本号 |
| 2/9 PowerShell | ⚠️ **构建链硬依赖**（时间戳 / 实时 tee / 构建报告 / `mvnw.cmd` 都用它） |
| 3/9 编译 JDK | 需要 JDK 25，打印 `javac -version` |
| 4/9 native-image | 从 NIK / GraalVM 目录探测（**与编译 JDK 分开解析**） |
| 5/9 Maven | PATH → 仓库内 `mvnw.cmd` → wrapper 缓存；**有 `mvnw` 时无需预装 Maven** |
| 6/9 MSVC | `vswhere.exe` → `VC.Tools.x86.x64` → `vcvars64.bat` → `editbin.exe` + Windows SDK |
| 7/9 内存与磁盘 | native-image 默认 `-J-Xmx4G`，编译期需数 GB 临时空间 |
| 8/9 git | 只影响构建报告里的 `gitCommit` |
| 9/9 网络 | 首次构建要拉依赖；连不上会预警 |

**退出码**：`0` 全部就绪 / `20` 只缺 MSVC（native-image 的唯一硬依赖）/ `1` 缺其它关键项 —— 与 `build-native.cmd` 的码位对齐。

> 下面这份 bash 版**仅为历史参考**（Git Bash / WSL 可继续用）；**维护中的唯一实现是 `check-env.cmd`**。

```bash
#!/bin/bash
echo "=== 环境检查 ==="
errors=0

# JDK 25
echo -n "JDK 25: "
if java25=$(command -v java 2>/dev/null) && "$java25" -version 2>&1 | grep -q "25\."; then
    echo "OK ($(which java))"
else
    echo "MISSING - 需要 JDK 25（含 JavaFX）用于编译"
    errors=$((errors+1))
fi

# GraalVM
echo -n "GraalVM: "
for candidate in "$GRAALVM_HOME/bin/java" "$JAVA_HOME/bin/java" "java"; do
    if "$candidate" -version 2>&1 | grep -q "GraalVM" 2>/dev/null; then
        echo "OK ($candidate)"
        break
    fi
done
if [ $? -ne 0 ]; then
    echo "MISSING - 需要 GraalVM JDK 25+ 用于 native-image"
    errors=$((errors+1))
fi

# Maven
echo -n "Maven: "
if mvn --version >/dev/null 2>&1; then
    echo "OK"
else
    echo "MISSING"
    errors=$((errors+1))
fi

# MSVC
echo -n "MSVC (cl.exe): "
if cl.exe >/dev/null 2>&1; then
    echo "OK"
else
    echo "MISSING - 需要在 VS 命令行中运行"
    errors=$((errors+1))
fi

echo "=== 检查完成: $errors 项缺失 ==="
[ $errors -gt 0 ] && exit 1 || exit 0
```

---

## 四、Native Image 配置文件体系

以下 6 个文件（或至少 5 个 JSON + 1 个 metadata）需要放置到 `src/main/resources/META-INF/native-image/com.mhtextractor/` 目录下。**在新设备上这些文件不会自动存在，需要按以下方法生成**。

### 4.1 文件清单

| 文件 | 生成方式 | 用途 |
|------|----------|------|
| `reachability-metadata.json` | **Tracing Agent 自动生成** | 反射 + 资源配置（GraalVM 25 新统一格式） |
| `reflect-config.json` | **手工编写**（本文档提供模板） | 补充的反射配置（Agent 无法覆盖的部分） |
| `jni-config.json` | **手工编写**（本文档提供模板） | JNI 可访问的类和方法 |
| `proxy-config.json` | **手工编写** | 动态代理接口 |
| `serialization-config.json` | **手工编写** | Java 序列化目标类 |
| `resource-config.json` | **手工编写** | 资源文件 glob 匹配模式 |

> **说明**: `reachability-metadata.json` 是 GraalVM 25 引入的新格式（将反射和资源合并到一个文件）。旧的分离格式（`reflect-config.json` 等）仍然被支持，两者可以共存，native-image 会合并它们。Tracing Agent 在 GraalVM 25 中输出新格式，手工编写的配置仍可用旧格式。

### 4.2 第一步: 创建目录结构

```bash
mkdir -p src/main/resources/META-INF/native-image/com.mhtextractor
```

### 4.3 第二步: 运行 Tracing Agent 生成 `reachability-metadata.json`

这是**最关键的一步**。Agent 在 JVM 模式下运行应用，自动记录所有反射调用和资源访问。

#### 4.3.1 前置条件

- 已用 JDK 21 编译出 fat JAR: `mvn clean package -DskipTests`
- 确保 `target/lib/` 目录下有所有依赖（包括 `*-win.jar` 的 JavaFX JAR）

#### 4.3.2 在 App.java 中添加自动退出机制

如果当前 `App.java` 还没有 agent 自动退出代码，添加以下内容到 `start()` 方法末尾（`primaryStage.show()` 之后）:

```java
// Agent mode: auto-exit after delay for clean agent config flush
String agentDelay = System.getProperty("agent.exit.delay");
if (agentDelay != null) {
    exerciseForAgent(loader);
    long delayMs = Long.parseLong(agentDelay);
    new java.util.Timer(true).schedule(new java.util.TimerTask() {
        public void run() {
            javafx.application.Platform.exit();
            System.exit(0);
        }
    }, delayMs);
}
```

在 `MainController.java` 中添加 `exerciseAllFeatures()` 方法，程序化演练所有 UI 功能。关键演练内容:

- ToggleButton 文本/图片模式切换
- 所有 ToggleSwitch 开关操作
- TitledPane 展开/折叠
- TextField 填入内容
- 创建 Alert/TextInputDialog/DirectoryChooser/Tooltip 实例（但不弹出）
- ComboBox 选项修改
- Jackson 序列化/反序列化 AppConfig
- 高速内存模式切换（触发 Alert 警告弹窗）

> **为什么用程序化演练而不是手动操作**: 在 CI/无头环境中无法与 GUI 交互，程序化演练复用同一套 UI 操作代码，确保 agent 捕获到完整的反射元数据。在交互式桌面环境中，也可以用程序化演练代替手动点击。

#### 4.3.3 运行 Agent

```bash
# 设置 GraalVM 为当前 JDK
export JAVA_HOME=<GRAALVM_HOME>
export PATH=$JAVA_HOME/bin:$PATH

# 创建输出目录
mkdir -p agent-config

# 运行应用 + Agent（约 95 秒后自动退出）
java -agentlib:native-image-agent=config-output-dir=agent-config \
  -Dagent.exit.delay=95000 \
  --module-path target/lib \
  --add-modules=javafx.controls,javafx.fxml,javafx.graphics \
  -cp "target/classes;target/lib/*" \
  com.mhtextractor.App

# 确认输出
ls -la agent-config/reachability-metadata.json
wc -l agent-config/reachability-metadata.json  # 应在 3000 行左右
```

**为什么需要 `--module-path` 和 `--add-modules`**: 本项目没有 `module-info.java`（classpath 模式），但 GraalVM JDK 25 不内置 JavaFX。这两个参数让 JVM 能从 Maven 依赖的 JavaFX JAR 中加载 JavaFX 模块和原生 DLL。

#### 4.3.4 复制到项目资源目录

```bash
cp agent-config/reachability-metadata.json \
   src/main/resources/META-INF/native-image/com.mhtextractor/
```

### 4.4 第三步: 创建 `reflect-config.json`（手工补充）

Agent 无法捕获通过 `new` 直接实例化的类（这些类静态可达，不需要反射配置），以及未被演练到的代码路径中的反射调用。以下内容保存为 `reflect-config.json`:

```json
[
  {
    "name": "com.mhtextractor.core.MimeParser",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.mhtextractor.core.ContentExtractor",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.mhtextractor.core.ChapterGenerator",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.mhtextractor.processor.BatchProcessor",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.mhtextractor.processor.TextBatchProcessor",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.mhtextractor.processor.ImageBatchProcessor",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.mhtextractor.processor.BatchProcessor$ProcessStats",
    "allDeclaredFields": true,
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.mhtextractor.processor.BatchProcessor$ProgressCallback",
    "allDeclaredMethods": true
  },
  {
    "name": "com.mhtextractor.extractor.BaseExtractor",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.mhtextractor.extractor.TextExtractor",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.mhtextractor.extractor.ImageExtractor",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.mhtextractor.extractor.WarningProvider",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.mhtextractor.output.Archiver",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.mhtextractor.output.FileWriter",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.mhtextractor.output.ImageWriter",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.mhtextractor.util.EncodingUtils",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.mhtextractor.util.FileUtils",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.mhtextractor.util.HtmlUtils",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.mhtextractor.util.SanitizeUtils",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.mhtextractor.gui.util.JavaFxUtil",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "jakarta.mail.Session",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "jakarta.mail.internet.MimeMultipart",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "jakarta.mail.internet.MimeBodyPart",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "jakarta.mail.internet.ContentType",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "jakarta.mail.internet.ParameterList",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "jakarta.activation.MimetypesFileTypeMap",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "jakarta.activation.DataHandler",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.mail.handlers.multipart_mixed",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.mail.handlers.text_plain",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.mail.handlers.text_html",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.mail.handlers.message_rfc822",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "org.jsoup.Jsoup",
    "allDeclaredMethods": true
  },
  {
    "name": "org.jsoup.nodes.Document",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "org.jsoup.nodes.Element",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "org.jsoup.select.Elements",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "javafx.beans.property.BooleanProperty",
    "allDeclaredMethods": true
  },
  {
    "name": "javafx.beans.property.SimpleBooleanProperty",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "javafx.beans.property.StringProperty",
    "allDeclaredMethods": true
  },
  {
    "name": "javafx.beans.property.SimpleStringProperty",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "javafx.beans.property.ObjectProperty",
    "allDeclaredMethods": true
  },
  {
    "name": "javafx.beans.property.DoubleProperty",
    "allDeclaredMethods": true
  },
  {
    "name": "javafx.beans.property.SimpleDoubleProperty",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "javafx.scene.Node",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true,
    "allDeclaredFields": true
  },
  {
    "name": "javafx.scene.Parent",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true,
    "allDeclaredFields": true
  },
  {
    "name": "javafx.scene.layout.Region",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true,
    "allDeclaredFields": true
  },
  {
    "name": "javafx.scene.layout.Pane",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true,
    "allDeclaredFields": true
  },
  {
    "name": "javafx.stage.DirectoryChooser",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "javafx.scene.control.Alert",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "javafx.scene.control.TextInputDialog",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "javafx.scene.control.Tooltip",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "javafx.scene.control.ButtonType",
    "allDeclaredFields": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.tk.quantum.QuantumToolkit",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.sun.javafx.tk.Toolkit",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.application.PlatformImpl",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.application.LauncherImpl",
    "allDeclaredMethods": true
  },
  {
    "name": "javafx.fxml.FXMLLoader",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.sun.javafx.fxml.FXMLLoaderHelper",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.glass.ui.win.WinPlatformFactory",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.win.WinApplication",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.sun.javafx.scene.control.skin.ToggleButtonSkin",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.javafx.scene.control.skin.TextFieldSkin",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.javafx.scene.control.skin.ComboBoxListViewSkin",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.javafx.scene.control.skin.TitledPaneSkin",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.javafx.scene.control.skin.TabPaneSkin",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.javafx.scene.control.skin.ProgressBarSkin",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.marlin.DRenderer",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.marlin.DRendererContext",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.marlin.MarlinRenderingEngine",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.fasterxml.jackson.databind.ObjectMapper",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.fasterxml.jackson.datatype.jsr310.JavaTimeModule",
    "allDeclaredConstructors": true,
    "methods": [{"name": "<init>", "parameterTypes": []}]
  }
]
```

> **说明**: 此清单覆盖了所有已知需要通过反射访问的类。如果项目新增了类或依赖，需要在其中追加新条目。特别关注 `allDeclaredMethods`——它只包含类**自身声明的**方法，不包含父类方法。如果 FXML 属性解析失败（报 `Cannot determine type for property`），需要同时注册父类（如 `Labeled` 定义了 `text` 属性，只注册 `Label` 不够）。

### 4.5 第四步: 创建 `jni-config.json`

JavaFX 通过 JNI 与原生窗口系统通信。Native code 调用 `FindClass`、`GetMethodID` 时需要目标类在此注册。

```json
[
  {
    "name": "java.lang.Runnable",
    "allDeclaredMethods": true
  },
  {
    "name": "java.lang.String",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "java.lang.Object",
    "allDeclaredMethods": true
  },
  {
    "name": "java.lang.Thread",
    "allDeclaredMethods": true
  },
  {
    "name": "java.io.File",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "java.util.List",
    "allDeclaredMethods": true
  },
  {
    "name": "java.util.ArrayList",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "javafx.stage.DirectoryChooser",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "javafx.stage.FileChooser",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.glass.ui.Screen",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.sun.glass.ui.win.WinScreen",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.sun.glass.ui.win.WinApplication",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.sun.glass.ui.win.WinWindow",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.win.WinView",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.win.WinCursor",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.win.WinTimer",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.win.WinPixels",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.win.WinMenu",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.win.WinCommonDialogs",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.win.WinDnDClipboard",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.win.WinSystemClipboard",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.win.WinGestureSupport",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.win.WinAccessible",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.win.WinChildWindow",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.win.WinRobot",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.win.WinScreen",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.utils.NativeLibLoader",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.EventLoop",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.Application",
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.sun.prism.d3d.D3DPipeline",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.prism.sw.SWPipeline",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.prism.d3d.D3DResourceFactory",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.prism.d3d.D3DContext",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.prism.d3d.D3DGraphics",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.prism.d3d.D3DRenderTarget",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.prism.d3d.D3DTexture",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.prism.d3d.D3DShader",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.prism.d3d.D3DMeshView",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.prism.d3d.D3DSwapChain",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.prism.sw.SWResourceFactory",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.prism.sw.SWContext",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.prism.sw.SWGraphics",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.prism.sw.SWRenderTarget",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.prism.sw.SWTexture",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.prism.sw.SWMaskTexture",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.prism.impl.PrismSettings",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.scenario.effect.impl.hw.d3d.D3DShader",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.scenario.effect.impl.hw.d3d.D3DContext",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.scenario.effect.impl.hw.d3d.D3DRenderer",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.font.PrismFontFactory",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.font.directwrite.DWFactory",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.font.directwrite.DWGlyphLayout",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.font.directwrite.IDWriteFactory",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.font.directwrite.DWRITE_GLYPH_RUN",
    "allDeclaredFields": true
  },
  {
    "name": "com.sun.javafx.font.directwrite.JFXTextRenderer",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.javafx.font.directwrite.D2D1_COLOR_F",
    "allDeclaredFields": true
  },
  {
    "name": "com.sun.javafx.font.FontConfigManager$FcCompFont",
    "allDeclaredFields": true
  },
  {
    "name": "com.sun.javafx.font.FontConfigManager$FontConfigFont",
    "allDeclaredFields": true
  },
  {
    "name": "com.sun.javafx.iio.ImageLoaderFactory",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.iio.ios.IosImageLoaderFactory",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.javafx.iio.jpg.JPEGImageLoaderFactory",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.javafx.iio.png.PNGImageLoaderFactory",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.javafx.iio.gif.GIFImageLoaderFactory",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.javafx.iio.bmp.BMPImageLoaderFactory",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.media.jfxmediaimpl.NativeMediaManager",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.media.jfxmedia.locator.Locator",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.Size",
    "allDeclaredConstructors": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.sun.glass.ui.PlatformFactory",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.Clipboard",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.Menu",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.MenuBar",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.glass.ui.MenuItem",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.prism.impl.BaseResourceFactory",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.prism.impl.BaseContext",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.prism.impl.BaseGraphics",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.prism.GraphicsPipeline",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.prism.ResourceFactory",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.scenario.effect.Effect",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.scenario.effect.impl.prism.PrRenderer",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.scenario.Settings",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.embed.EmbeddedStageInterface",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.stage.WindowHelper",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.stage.StageHelper",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.tk.quantum.QuantumClipboard",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.tk.quantum.GlassScene",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.tk.quantum.ViewScene",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.tk.quantum.PaintCollector",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.tk.quantum.PresentingPainter",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.javafx.tk.quantum.UploadingPainter",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.javafx.tk.quantum.QuantumRenderer",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.tk.quantum.WindowStage",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.tk.quantum.EmbeddedStage",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.javafx.css.StyleManager",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.logging.PlatformLogger",
    "allDeclaredMethods": true
  },
  {
    "name": "com.sun.javafx.util.Utils",
    "allDeclaredMethods": true
  },
  {
    "name": "javafx.scene.control.skin.ToggleButtonSkin",
    "allDeclaredConstructors": true
  },
  {
    "name": "javafx.scene.control.skin.TextFieldSkin",
    "allDeclaredConstructors": true
  },
  {
    "name": "javafx.scene.control.skin.ButtonSkin",
    "allDeclaredConstructors": true
  },
  {
    "name": "javafx.scene.control.skin.ComboBoxListViewSkin",
    "allDeclaredConstructors": true
  },
  {
    "name": "javafx.scene.control.skin.TitledPaneSkin",
    "allDeclaredConstructors": true
  },
  {
    "name": "javafx.scene.control.skin.TabPaneSkin",
    "allDeclaredConstructors": true
  },
  {
    "name": "javafx.scene.control.skin.ProgressBarSkin",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.javafx.scene.control.skin.ScrollPaneSkin",
    "allDeclaredConstructors": true
  },
  {
    "name": "com.sun.javafx.scene.text.TextLayout",
    "allDeclaredMethods": true
  }
]
```

> **说明**: 以上 JNI 配置涵盖了 Windows 平台 (win)、Direct3D 渲染管线 (d3d)、软件渲染回退 (sw) 以及 DirectWrite 字体渲染。如果 GraalVM 大版本升级（如 25 → 27），JavaFX 内部类的包名和继承链可能变化，届时需要查阅新版 GraalVM 文档或在 JVM 模式下重新运行 Tracing Agent 验证。

### 4.6 第五步: 创建 `proxy-config.json`

```json
[
  {"interfaces": ["javafx.collections.ListChangeListener"]},
  {"interfaces": ["javafx.beans.value.ChangeListener"]},
  {"interfaces": ["java.util.function.Supplier"]},
  {"interfaces": ["com.mhtextractor.processor.BatchProcessor$ProgressCallback"]},
  {"interfaces": ["java.util.concurrent.Callable"]}
]
```

### 4.7 第六步: 创建 `serialization-config.json`

```json
[
  {"name": "com.mhtextractor.config.AppConfig"},
  {"name": "java.lang.String"},
  {"name": "java.lang.Boolean"},
  {"name": "java.util.ArrayList"}
]
```

### 4.8 第七步: 创建 `resource-config.json`

```json
{
  "resources": {
    "includes": [
      {"pattern": "\\Qcom/mhtextractor/gui/view/MainView.fxml\\E"},
      {"pattern": "\\Qcustom.css\\E"},
      {"pattern": "\\Qlogback.xml\\E"},
      {"pattern": "\\Qatlantafx/base/theme/\\E.*\\.(css|bss)$"},
      {"pattern": "\\QMETA-INF/resources/ikonli/\\E.*\\.ttf$"},
      {"pattern": "\\QMETA-INF/resources/fontawesome5/\\E.*\\.ttf$"},
      {"pattern": "\\QMETA-INF/services/\\E.*"},
      {"pattern": "\\Qcom/sun/javafx/scene/control/skin/modena/\\E.*\\.(css|bss)$"},
      {"pattern": "\\Qcom/sun/javafx/css/\\E.*"},
      {"pattern": "\\Qatlantafx/base/\\E.*\\.(css|bss)$"},
      {"pattern": ".*\\.properties$"},
      {"pattern": ".*\\.obj$"},
      {"pattern": ".*\\.frag$"},
      {"pattern": ".*\\.hlsl$"}
    ]
  }
}
```

> **关键**: `.*\\.obj$` 模式包含 D3D shader 文件。缺少此模式将导致 UI 渲染时崩溃（`InternalError: Error loading stock shader FillPgram_Color`）。`.*\\.properties$` 包含 i18n 资源文件。`\\Q...\\E` 是 Java 正则的 literal 引用语法。

---

### 4.9 项目专属：改动后必须重采的元数据清单（2026-09 重构后新增）

> **本节是本项目的"变更 → 元数据"对照表。** 项目在 2026-09 完成了架构重构（新增零 JavaFX 的 `app` 应用层）、
> 全量国际化、预览缓存持久化。这三件事各自引入了新的 native 元数据需求。**只改代码不重采元数据，
> `mvn package` 会正常通过，但 native EXE 会在运行时才炸** —— 这是最危险的一类问题。

#### 4.9.1 已登记需反射的纯 DTO（Jackson 序列化）

Jackson 靠反射构造 record / POJO。**任何新增的"被 Jackson 读写的 DTO"都必须补反射条目**。
当前 `reachability-metadata.json` 已含：

| 类型 | 反射需求 | 用途 |
|---|---|---|
| `com.mhtextractor.cache.PreviewCacheMeta` | `<init>(int, Source, String, boolean, List, List)` + 6 个访问器 | 预览缓存 `meta.json` |
| `PreviewCacheMeta$Source` | `<init>(String, long, long)` + 3 个访问器 | 同上（嵌套 record） |
| `PreviewCacheMeta$ImageEntry` | `<init>(String, String, String, String, List)` + 5 个访问器 | 同上（嵌套 record） |
| `com.mhtextractor.config.AppConfig` | 全部 getter/setter（字段较多） | 命名配置 `config/*.json` |
| `com.mhtextractor.config.GuiState` | `<init>` + 各 getter/setter（含 `getLocale`/`setLocale`） | `logs/gui-state.json` |

> **新增 DTO 时怎么做**：写一个最小 driver（或用 `test.mode`）走一遍该 DTO 的序列化 + **反序列化**，
> 用 tracing agent 采集，然后**按并集合并**（见 4.9.3）。

#### 4.9.2 i18n 的资源注册（不是反射！）

**实测结论（务必记住，这条曾判断错方向）**：i18n **不需要** `ResourceBundle` / `PropertyResourceBundle` /
`Locale` 的**反射条目**（agent 实测为 **0 条**）——classpath 模式下 properties bundle 走
`ResourceBundle.Control.newBundle` → `new PropertyResourceBundle(stream)` 的**直接构造**路径，无反射。

**真正需要的是资源注册**（已在 `reachability-metadata.json`）：

```json
{ "bundle": "i18n/messages" }                                          // ← 核心：注册 bundle 全部 locale 变体
{ "glob": "i18n/messages{,_en,_en_US,_zh,_zh_CN,_zh_Hans,_zh_Hans_CN}.properties" }
{ "glob": "META-INF/services/java.util.spi.ResourceBundleControlProvider" }
{ "glob": "com/sun/javafx/scene/control/skin/resources/controls_en{,_US}.properties", "module": "javafx.controls" }
```

`resource-config.json` 另含 `\Qi18n/\E.*\.properties$`（显式声明）与 `.*\.properties$`（通配兜底）。

> **新增资源（FXML / properties / 图片 / 字体）都要补 pattern**。漏了**不会构建失败**，只在运行时抛异常。

#### 4.9.3 采集与合并纪律（本项目用代价换来的）

**① 元数据必须用 tracing agent 采集，不许手工推断。** 上面 4.9.2 就是反例。
NIK 自带 agent，JVM 下即可运行：

```bash
java -agentlib:native-image-agent=config-output-dir=target/agent-out \
     -Dtest.mode=true -cp "target/classes;target/test-classes;<cp.txt>" com.mhtextractor.App
# 若报 "Can't find dependent libraries"，改用全路径形式：
#   -agentpath=<NIK>/bin/native-image-agent.dll=config-output-dir=target/agent-out
# 建议跑两轮（zh 与 en 各一次），确保两份 bundle 都被采集
```

**② 按并集合并，勿直接覆盖。** 旧版有些条目新演练捕获不到但运行必需。

**③ 必须是纯增量 diff。** 核对 `git diff --numstat`。
反例：曾在合并脚本里用 `json.dumps(indent=1)`，**重排整个文件产生 −606 行**无关变更；
正确做法是 **append-only**（本项目历史做法：agent 采集 → 只追加新条目 → 去重同 type 时取字段/方法并集）。

**④ 方法/字段级对账：丢失数必须为 0。** 合并前后比对各 type 的 fields/methods 集合。
本项目一次合并的合格样本：`reflection 201 → 192`（去重 12 个重复 type）、**方法丢失 0 / 字段丢失 0**、
`git diff` 为 `+61 / −0` 纯增量。

**⑤ 不要往 `src/main/resources` 放临时或备份文件** —— 会被 Maven 静默打进 jar。
（本项目真发生过：`reachability-metadata.json.bak` 被 `jar tf` 发现打进了产物。）

#### 4.9.4 `reflect-config.json` 的处理建议

`src/main/resources/META-INF/native-image/com.mhtextractor/` 下**同时存在**：

| 文件 | 格式 | 状态 |
|---|---|---|
| `reachability-metadata.json` | GraalVM 25 统一格式 | ✅ **权威**（含 cache 包与 i18n 条目） |
| `reflect-config.json` | 旧分离格式（291 条） | ⚠️ **已过时**：不含 `com.mhtextractor.cache.*`，也不含 `ImageBatchProcessor` / `ImageSource` 等近期类 |

两种格式**可以共存**，native-image 对同一 type 取**并集**。因此补齐统一格式即可，**无需同步维护旧文件**。
**🚨 该"建议删除"已被实测推翻（2026-09-27 实测，详见 `CLAUDE.md`）**：旧格式 **291** 个类型、统一格式 **194** 个，其中 **231 个仅存在于旧格式**；native-image 对同一 type 取**并集**，**直接删旧文件会丢 231 条**。且本机 native 被 MSVC 阻塞、**无法验证**，在无法验证时改元数据风险 > 收益。正解是"并集合并 + 方法/字段级对账（丢失数=0）"。另：该文件里指向 `JavaFxUtil$SmoothProgressProperty` 的条目已**悬空**（该类已被删除）。
（§4.4 的手工模板仅作历史参考；若仍保留该文件，注意其条目会与统一格式合并。）

---

## 五、构建命令

### 5.1 完整构建流程

Native Image 构建需要 MSVC 链接器环境。**在同一个终端中**按顺序执行：

```bash
# ========== Step 0: 设置路径变量（按实际环境修改） ==========
JDK25_HOME=<你的 JDK 25 路径（Liberica Full JDK 25）>
GRAALVM_HOME=<你的 GraalVM JDK 25+ 路径>
PROJECT_DIR=<项目根目录>

# ⚠️ 已作废（2026-09-27）：build-native.cmd **不再需要手改路径** —— 它自动探测 JDK / native-image /
#   Maven / 输出目录；要覆盖就用**环境变量**（不改脚本）：
#     BUILD_JAVA_HOME / GRAALVM_HOME / MAVEN_CMD / OUTPUT_DIR / NATIVE_XMX / VSWHERE / LOG_DIR
#   本机实测（仅供参考，脚本会自己找到）：
#     BUILD_JAVA_HOME = 取 JAVA_HOME —— 本机那是**纯 JDK 25（无 native-image）**，
#                       所以脚本把编译 JDK 与 native-image JDK **分开解析**，后者取 NIK
#     GRAALVM_HOME    = D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3（NIK 自带 native-image）
#     MAVEN_CMD       = 自动到 %USERPROFILE%\.m2\wrapper\dists 下递归找 mvn.cmd
#     OUTPUT_DIR      = <仓库>\dist（默认）
#     NATIVE_XMX      = 4G（默认；原脚本写死 8G，本机总内存约 15 GB）
#   ⚠️ 本机仍缺 MSVC → native-image 会报 "Failed to find 'vswhere.exe'"（exit=20）。
#      必须先装 VS 2022 Build Tools 17.6+（勾选「使用 C++ 的桌面开发」）；脚本会在**开跑前**就拦下并提示。

# ========== Step 1: 初始化 MSVC 环境（必须最先执行） ==========
# 找到 vcvars64.bat 的位置（通常在 VS 安装目录下）
# 示例路径: "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
call "<VS安装目录>\VC\Auxiliary\Build\vcvars64.bat"

# ========== Step 2: 编译（JDK 25） ==========
export JAVA_HOME=$JDK25_HOME
export PATH=$JAVA_HOME/bin:$PATH
cd $PROJECT_DIR
mvn clean package -DskipTests
# ⚠️ 本机 mvn 不在 PATH → 实际用 "%MAVEN_HOME%\bin\mvn.cmd" clean package -DskipTests
#    并确保 JAVA_HOME 指向 NIK 25（否则 javac + Lombok 静默失效，全项目报"找不到符号 log"）

# 版本号以 pom.xml 为唯一来源（jar 产物名 = mhtExtractor-<version>.jar）
APP_VERSION=$(grep -oP '<version>\K[^<]+' "$PROJECT_DIR/pom.xml" | head -1)
echo "项目版本: $APP_VERSION"

# ========== Step 3: 提取 JavaFX 原生 DLL ==========
mkdir -p target/jfx-dlls
cd target/lib
# 从 JavaFX Graphics JAR 中提取所有 DLL
# javafx-graphics-<version>-win.jar 中的 version 可能变化
JFX_GRAPHICS_JAR=$(ls javafx-graphics-*-win.jar | head -1)
for dll in glass.dll prism_common.dll prism_d3d.dll prism_sw.dll \
            decora_sse.dll javafx_font.dll javafx_iio.dll \
            msvcp140.dll msvcp140_1.dll msvcp140_2.dll \
            vcruntime140.dll vcruntime140_1.dll; do
    jar xf "$JFX_GRAPHICS_JAR" "$dll" 2>/dev/null
    mv "$dll" ../jfx-dlls/ 2>/dev/null
done
cd ../..

# ========== Step 4: Native Image 编译 ==========
export JAVA_HOME=$GRAALVM_HOME
export PATH=$JAVA_HOME/bin:$PATH

native-image.cmd \
  -jar "target/mhtExtractor-${APP_VERSION}.jar" \
  --no-fallback \
  -H:Path=target/native-image \
  -H:Name=MhtExtractor \
  -H:+JNI \
  -H:+ReportExceptionStackTraces \
  --enable-all-security-services \
  --enable-http \
  --enable-https \
  --enable-url-protocols=file,http,https \
  --initialize-at-run-time=com.sun.glass,com.sun.prism,com.sun.scenario,com.sun.javafx.font,com.sun.javafx.iio,com.sun.media,com.sun.webkit,com.sun.javafx.tk.quantum,com.sun.marlin \
  -Djava.library.path="target/jfx-dlls" \
  -J-Xmx4G \
  -Ob

# ========== Step 5: 打包分发 ==========
mkdir -p target/dist
cp target/native-image/MhtExtractor.exe target/dist/
cp target/native-image/*.dll target/dist/
cp target/jfx-dlls/*.dll target/dist/

# ========== Step 6: 隐藏控制台窗口 ==========
# 查找 editbin.exe（在 VS 安装目录下）
EDITBIN=$(find "${VS150COMNTOOLS:-/c/Program Files}" -name "editbin.exe" -path "*/x64/*" 2>/dev/null | head -1)
if [ -n "$EDITBIN" ]; then
    "$EDITBIN" /SUBSYSTEM:WINDOWS target/dist/MhtExtractor.exe
    echo "GUI mode enabled - no console window"
else
    echo "WARNING: editbin.exe not found, EXE will show console window"
fi
```

### 5.2 各参数说明

| 参数 | 用途 | 必要性 |
|------|------|--------|
| `--no-fallback` | 禁止降级为 fallback JAR | **必须**: 确保生成真正的 native image |
| `-H:Path=target/native-image` | 输出目录 | - |
| `-H:Name=MhtExtractor` | 输出文件名 | - |
| `-H:+JNI` | 启用 JNI 支持 | **必须**: JavaFX 通过 JNI 驱动窗口/渲染 |
| `-H:+ReportExceptionStackTraces` | 运行时输出完整堆栈 | 推荐: 便于调试 |
| `--enable-all-security-services` | 启用全部安全/加密服务 | **必须**: JavaFX SSL 支持 |
| `--enable-http/--enable-https` | 启用 HTTP/HTTPS | Jakarta Mail URL 处理需要 |
| `--enable-url-protocols=file,http,https` | 注册 URL 协议处理器 | **必须**: Ikonli 通过 `file:` URL 加载字体 |
| `--initialize-at-run-time=<包列表>` | 指定在运行时初始化的包 | **必须**: JavaFX 在 `<clinit>` 中加载原生 DLL，构建时执行会失败 |
| `-Djava.library.path=target/jfx-dlls` | 构建时原生库路径 | **必须**: 构建时需要验证原生方法签名 |
| `NATIVE_XMX`（原写死 `-J-Xmx8G`） | Native Image 构建堆上限 | **默认 4G**；可按机器内存用环境变量调高（本机总内存约 15 GB，勿盲目 8G） |
| `-Ob` | 优化级别 balanced | 平衡构建速度和输出大小 |

### 5.3 `--initialize-at-run-time` 详解

JavaFX 的内部类在 `<clinit>`（静态初始化块）中会加载原生 DLL。如果在构建时执行这些初始化，会因为 native-image 构建环境没有 GPU/显示器而失败。

必须指定**整个包**为运行时初始化（使用逗号分隔，不支持通配符）：

```
com.sun.glass          → Glass 窗口系统
com.sun.prism          → Prism 渲染管线 (D3D + SW)
com.sun.scenario       → 视觉效果
com.sun.javafx.font    → 字体渲染 (DirectWrite)
com.sun.javafx.iio     → 图像 I/O
com.sun.media          → 媒体引擎
com.sun.webkit         → WebView
com.sun.javafx.tk.quantum → 主事件循环
com.sun.marlin         → 2D 渲染器
```

缺少任何一个包，native-image 构建虽然可能成功，但运行时会崩溃。

---

## 六、`build-native.cmd`：构建入口与可观测性

> ⚠️ **本节原有的"脚本模板"已过时（2026-09-27）**。`build-native.cmd` **已在仓库里**且是**唯一实现**，
> 不需要再"生成"；下面保留的模板**仅作历史参考**，其中的 `<你的 JDK 25 路径>` 占位符与"手工改 4 处路径"
> 的做法**都不要再照做**。当前用法：

```bat
build-native.cmd                 完整构建：预检 -> mvn package -> 抽 DLL -> native-image -> 组装 dist
build-native.cmd --prepare-only  只做 JVM 侧准备（不调用 native-image）—— 无 MSVC 时也能跑
build-native.cmd --skip-package  跳过 mvn package
build-native.cmd --no-editbin    保留控制台子系统（便于看启动期崩溃）
```

环境变量覆盖（不设则自动探测）：`BUILD_JAVA_HOME` / `GRAALVM_HOME` / `MAVEN_CMD` /
`OUTPUT_DIR`（默认 `<仓库>\dist`）/ `NATIVE_XMX`（默认 4G）/ `VSWHERE` / `LOG_DIR`。

**退出码**：`0` 成功 / `1` 其它失败 / **`20` 缺 MSVC 工具链**（与 native-image 同码，便于脚本判断）。

### 6.1 可观测性契约（给在打包机上驱动构建的智能体）

每次构建产出三件套（`<stamp>` = `yyyyMMdd-HHmmss`）：

| 产物 | 位置 | 内容 |
|---|---|---|
| 主日志 | `logs/native-build-<stamp>.log` | **全过程实时**（控制台与日志同步）：环境探测值、每个外部命令的**完整输出**、每步 `[STEP k/n] BEGIN/END … exit=` 标记（ISO 时间） |
| 步骤明细 | `logs/native-build-<stamp>.steps.tsv` | TSV：`step / name / begin / end / exit` |
| **机器可读报告** | `logs/native-build-<stamp>.report.json` | `status` / `failedStep` / `gitCommit` / **context**（JDK、native-image、Maven、VS、版本、xmx）→ **input**（jar 体积+SHA256）→ **steps**（逐步耗时与退出码）→ **artifacts**（`dist` 内每个文件的体积+SHA256） |

> 报告放 `logs/` 而**不是** `dist/`，让 `dist/` 只留交付物（exe + DLL），"整个目录拷走即用"。

**实时跟踪**（构建进行中即可用，实测）：

```powershell
Get-Content logs\native-build-*.log -Wait -Tail 50
```

⚠️ 读正在写的日志必须用 `Get-Content` 或 `FileStream + FileShare.ReadWrite`；
**`File.ReadAllText` 会失败**（它用 `FileShare.Read`，与活跃写者不兼容）。

**失败时**：脚本打印 `=== FAILURE ===` 段（失败步 + 退出码 + 日志末 40 行），**仍然产出报告**。

---

以下为历史模板（**勿再照做**，仅作演进参考）：


```batch
@echo off
REM ============================================================
REM MHT资源批量提取器 - GraalVM Native Image 一键构建脚本
REM 使用方法: 根据本机环境修改下方路径变量后运行
REM ============================================================
setlocal enabledelayedexpansion

REM ====== 请根据本机环境修改以下路径 ======
set "JDK25_HOME=<你的 JDK 25 路径（Liberica Full JDK 25）>"
set "GRAALVM_HOME=<你的 GraalVM JDK 25+ 路径>"
set "MAVEN_HOME=<你的 Maven 路径>"
REM VS vcvars64.bat 路径（通常在 VS 安装目录下）
set "VCVARS64=<你的 VS 安装目录>\VC\Auxiliary\Build\vcvars64.bat"

REM ====== 验证环境 ======
if not exist "%JDK25_HOME%\bin\java.exe" (
    echo [ERROR] JDK 25 未找到: %JDK25_HOME%
    echo 请修改脚本中的 JDK25_HOME 变量
    pause & exit /b 1
)
if not exist "%GRAALVM_HOME%\bin\native-image.cmd" (
    echo [ERROR] GraalVM 未找到: %GRAALVM_HOME%
    echo 请修改脚本中的 GRAALVM_HOME 变量
    pause & exit /b 1
)
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
    echo [ERROR] Maven 未找到: %MAVEN_HOME%
    echo 请修改脚本中的 MAVEN_HOME 变量
    pause & exit /b 1
)
if not exist "%VCVARS64%" (
    echo [WARNING] vcvars64.bat 未找到，构建可能失败
)

REM ===== Step 0: 读取版本号（pom.xml 为唯一来源，禁止硬编码，否则脚本与 pom 漂移） =====
set "APP_VERSION="
for /f "tokens=*" %%i in ('powershell -NoProfile -Command "([xml](Get-Content pom.xml -Raw)).project.version"') do set "APP_VERSION=%%i"
if not defined APP_VERSION (
    REM PowerShell 不可用时的兜底：findstr 直读 4 空格缩进的 <version>
    for /f "tokens=3 delims=<>" %%i in ('findstr /r /c:"^    <version>" pom.xml') do set "APP_VERSION=%%i"
)
if not defined APP_VERSION (
    echo [ERROR] 无法从 pom.xml 读取版本号
    pause & exit /b 1
)
set "JFX_VERSION="
for /f "tokens=3 delims=<>" %%i in ('findstr /r /c:"<javafx.version>" pom.xml') do set "JFX_VERSION=%%i"
if not defined JFX_VERSION (
    echo [ERROR] 无法从 pom.xml 读取 javafx.version
    pause & exit /b 1
)
echo 项目版本: %APP_VERSION%  JavaFX: %JFX_VERSION%

echo === Step 1: 初始化 MSVC 环境 ===
call "%VCVARS64%" >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] MSVC 环境初始化失败
    pause & exit /b 1
)
echo MSVC 环境就绪

echo === Step 2: 编译项目 (JDK 25) ===
set JAVA_HOME=%JDK25_HOME%
set PATH=%JAVA_HOME%\bin;%PATH%
call "%MAVEN_HOME%\bin\mvn.cmd" clean package -DskipTests -f pom.xml
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Maven 编译失败
    pause & exit /b 1
)

echo === Step 3: 提取 JavaFX DLLs ===
if not exist "target\jfx-dlls" mkdir "target\jfx-dlls"
cd target\lib
for %%f in (glass.dll prism_common.dll prism_d3d.dll prism_sw.dll decora_sse.dll javafx_font.dll javafx_iio.dll msvcp140.dll msvcp140_1.dll msvcp140_2.dll vcruntime140.dll vcruntime140_1.dll) do (
    "%GRAALVM_HOME%\bin\jar" xf javafx-graphics-%JFX_VERSION%-win.jar %%f 2>nul
    if exist %%f move /Y %%f ..\jfx-dlls\ >nul 2>&1
)
cd ..\..

echo === Step 4: Native Image 编译 (GraalVM JDK 25) ===
set JAVA_HOME=%GRAALVM_HOME%
set PATH=%JAVA_HOME%\bin;%PATH%

"%GRAALVM_HOME%\bin\native-image.cmd" ^
  -jar target/mhtExtractor-%APP_VERSION%.jar ^
  --no-fallback ^
  -H:Path=target/native-image ^
  -H:Name=MhtExtractor ^
  -H:+JNI ^
  -H:+ReportExceptionStackTraces ^
  --enable-all-security-services ^
  --enable-http --enable-https ^
  --enable-url-protocols=file,http,https ^
  --initialize-at-run-time=com.sun.glass,com.sun.prism,com.sun.scenario,com.sun.javafx.font,com.sun.javafx.iio,com.sun.media,com.sun.webkit,com.sun.javafx.tk.quantum,com.sun.marlin ^
  -Djava.library.path="target/jfx-dlls" ^
  -J-Xmx4G ^
  -Ob

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Native Image 构建失败
    pause & exit /b 1
)

echo === Step 5: 打包便携版 ===
if not exist "target\dist" mkdir "target\dist"
copy /Y "target\native-image\MhtExtractor.exe" "target\dist\" >nul
copy /Y "target\native-image\*.dll" "target\dist\" >nul
copy /Y "target\jfx-dlls\*.dll" "target\dist\" >nul

echo === Step 6: 隐藏控制台窗口 ===
for /f "delims=" %%i in ('where /r "%VSINSTALLDIR%" editbin.exe 2^>nul ^| findstr /r "Hostx64.*editbin"') do set EDITBIN=%%i
if defined EDITBIN (
    "%EDITBIN%" /SUBSYSTEM:WINDOWS "target\dist\MhtExtractor.exe" >nul 2>&1
    echo GUI 模式已启用
) else (
    echo [WARNING] 未找到 editbin.exe，EXE 将以控制台模式运行（双击会有黑窗口）
)

echo.
echo ============================================================
echo   构建成功!
echo   便携版位置: target\dist\
echo   将此文件夹复制到任意 Windows 10/11 电脑即可运行
echo   无需安装 JDK/JRE
echo ============================================================
endlocal
```

> **使用说明**: 在新设备上，只需修改脚本顶部的 4 个路径变量（`JDK21_HOME`、`GRAALVM_HOME`、`MAVEN_HOME`、`VCVARS64`），然后在项目根目录运行 `build-native.cmd` 即可完成全部打包。

---

## 七、发行版打包

构建完成后 `target/dist/` 中的文件是原始构建产物（EXE 名为 `MhtExtractor.exe`）。需要将其打包为正式发行版，文件夹和 EXE 使用中文命名。

### 7.1 读取版本号

版本号定义在 `pom.xml` 的 `<version>` 标签中：

```bash
# 从 pom.xml 提取版本号（去除 -SNAPSHOT 后缀）
VERSION=$(grep -oP '<version>\K[^<]+' pom.xml | head -1 | sed 's/-SNAPSHOT//')
echo "项目版本: $VERSION"
```

输出示例: `3.0.0`

> **注意**: 上面用于**发行版目录/文件名**时才会剥离 `-SNAPSHOT`；用于定位 Maven 产物 jar 时必须保留原始版本（产物名为 `target/mhtExtractor-<version>.jar`，例如 `mhtExtractor-3.0.0.jar`）。

### 7.2 打包命令

> **关键限制**: GraalVM native-image 编译的 JavaFX 应用，其 **EXE 所在文件夹的完整路径**（从盘符起每一级目录）必须全部是 ASCII 字符（英文/数字/符号），不能包含中文、日文等非 ASCII 字符。这是 JavaFX 原生 DLL 加载器 `NativeLibLoader` 在 native-image 环境下的已知限制——当路径含非 ASCII 字符时，`System.loadLibrary()` 无法定位 `glass.dll` 和 `prism_*.dll`，导致 `Graphics Device initialization failed`。
>
> **可以包含中文的是**: ZIP 文件名、EXE 文件名本身。**不能包含中文的是**: EXE 所在的任一级文件夹名。
>
> 因此发行版采用 **"ZIP 中文名 + 内部 ASCII 文件夹 + 中文 EXE 名"** 的结构。

以下命令在项目根目录执行，**不依赖任何硬编码路径**：

```bash
# ====== 从 pom.xml 读取版本号 ======
VERSION=$(grep -oP '<version>\K[^<]+' pom.xml | head -1 | sed 's/-SNAPSHOT//')
RELEASE_NAME="MHT文件文本图片批量提取器V${VERSION}"      # ZIP 和 EXE 用中文名
RELEASE_DIR="./MhtExtractorV${VERSION}"                  # 内部文件夹用 ASCII 名（关键！）

# ====== 创建发行版目录 ======
rm -rf "$RELEASE_DIR" 2>/dev/null
mkdir -p "$RELEASE_DIR"

# ====== 复制并重命名 EXE（中文名 OK） ======
cp target/dist/MhtExtractor.exe "$RELEASE_DIR/${RELEASE_NAME}.exe"

# ====== 复制所有 DLL ======
cp target/dist/*.dll "$RELEASE_DIR/"

# ====== 创建 ZIP（中文名 OK） ======
rm -f "${RELEASE_NAME}.zip" 2>/dev/null
powershell -Command "Compress-Archive -Path '$RELEASE_DIR' -DestinationPath '${RELEASE_NAME}.zip' -Force"

echo "发行版已创建: ${RELEASE_NAME}.zip"
echo "解压后文件夹: ${RELEASE_DIR}/"
echo "注意: 解压后请勿将文件夹重命名为非 ASCII 字符，否则将无法运行"
```

执行后项目根目录下会生成：

```
./
├── MhtExtractorV2.2/                         ← ASCII 文件夹名（必须！）
│   ├── MHT文件文本图片批量提取器V2.2.exe      ← 中文 EXE 名（OK）
│   └── *.dll
└── MHT文件文本图片批量提取器V2.2.zip          ← 中文 ZIP 名（OK，~25 MB）
```

### 7.3 在 `build-native.cmd` 中集成发行版打包

```batch
echo === Step 7: 创建发行版 ===
REM 从 pom.xml 读取版本号
for /f "tokens=*" %%i in ('powershell -Command "[xml](Get-Content pom.xml).project.version"') do set VERSION=%%i
set VERSION=%VERSION:-SNAPSHOT=%
set "RELEASE_NAME=MHT文件文本图片批量提取器V%VERSION%"
REM 内部文件夹必须用 ASCII 名（JavaFX DLL 加载限制）
set "RELEASE_DIR=MhtExtractorV%VERSION%"

if exist "%RELEASE_DIR%" rmdir /s /q "%RELEASE_DIR%"
mkdir "%RELEASE_DIR%"
copy /Y "target\dist\MhtExtractor.exe" "%RELEASE_DIR%\%RELEASE_NAME%.exe" >nul
copy /Y "target\dist\*.dll" "%RELEASE_DIR%\" >nul
powershell -Command "Compress-Archive -Path '%RELEASE_DIR%' -DestinationPath '%RELEASE_NAME%.zip' -Force"
echo 发行版: %RELEASE_NAME%.zip
```

### 7.4 路径限制与兼容性

**路径限制**（已验证）:

| 测试场景 | 路径示例 | 结果 |
|----------|----------|------|
| ASCII 文件夹 + 中文 EXE 名 | `Y:/java-env/test-dll/中文名.exe` | OK |
| 中文文件夹 + 英文 EXE 名 | `Y:/java-env/中文文件夹/test.exe` | 失败 |
| 中文父目录 | `Y:/java环境/test-dll/test.exe` | 失败 |
| ASCII 全路径 | `Y:/instll/java/target/dist/MhtExtractor.exe` | OK |

**结论**: 从盘符到 EXE 所在文件夹的每一级目录名都必须纯 ASCII。EXE 文件名和 ZIP 文件名可以包含中文。

**系统兼容性**:

发行版可在**任意 Windows 10（版本 1607+）/ Windows 11** 设备上运行，无需安装任何依赖：

| 依赖项 | 是否需要安装 | 说明 |
|--------|-------------|------|
| JDK / JRE | **不需要** | 已编译为原生 EXE |
| .NET Framework | **不需要** | 本应用不使用 .NET |
| Visual C++ Redistributable | **不需要** | msvcp140.dll / vcruntime140.dll 已捆绑 |
| DirectX | **不需要** | 无显卡时自动回退软件渲染 |
| 管理员权限 | **不需要** | 普通用户即可运行 |

**唯一要求**: Windows 10 版本 ≥ 1607（2016年8月发布），因为系统需要内置的 Universal C Runtime (ucrtbase.dll)。`awt.dll`、`java.dll`、`jvm.dll` 是 GraalVM 在启用 `-H:+JNI` 时自动生成的 JNI 桥接库，必须一起分发。

### 7.5 原始构建产物目录结构（参考）

构建完成后 `target/dist/` 的原始结构（打包前）：

```
target/dist/
├── MhtExtractor.exe       # Native Image (~60 MB, GUI 模式, 英文名)
├── awt.dll                 # GraalVM JNI 桥接库
├── java.dll
├── jvm.dll
├── glass.dll               # JavaFX Glass 窗口系统
├── prism_d3d.dll           # Direct3D 硬件渲染
├── prism_sw.dll            # 软件渲染回退
├── prism_common.dll        # Prism 通用
├── decora_sse.dll          # CSS 效果
├── javafx_font.dll         # 字体渲染
├── javafx_iio.dll          # 图像 I/O
├── msvcp140.dll            # MSVC++ 运行时
├── msvcp140_1.dll
├── msvcp140_2.dll
├── vcruntime140.dll
└── vcruntime140_1.dll
```

总大小约 65MB。`awt.dll`、`java.dll`、`jvm.dll` 是 GraalVM 在启用 `-H:+JNI` 时自动生成的 JNI 桥接库，必须一起分发。

---

## 八、常见问题与调试

### 8.1 编译阶段

| 现象 | 可能原因 | 解决方法 |
|------|----------|----------|
| `Fatal error compiling: TypeTag :: UNKNOWN` | Lombok 版本不兼容当前 JDK | 升级 Lombok 到最新版本（当前 1.18.44+ 兼容 JDK 25） |
| `mvn: command not found` | Maven 未在 PATH | 用完整路径或安装 Maven |
| `Failed to delete target\lib` | 之前构建的 DLL 被占用 | 跳过 clean: `mvn package -DskipTests` |

### 8.2 Native Image 构建阶段

| 现象 | 可能原因 | 解决方法 |
|------|----------|----------|
| `ClassNotFoundException: com.sun.javafx.tk.quantum.QuantumToolkit` | 未在 reflect-config 中注册 | 添加到 reflect-config.json |
| `ClassNotFoundException: com.sun.glass.ui.win.WinPlatformFactory` | Glass 工厂类未注册 | 添加到 reflect-config.json |
| `NoClassDefFoundError: java/lang/Runnable` (via JNI) | 核心 JNI 回调类未注册 | 添加到 jni-config.json |
| `NoSuchMethodError: com.sun.glass.ui.Screen.<init>(JII...F)V` | Screen 构造器未对 JNI 暴露 | 添加 `allDeclaredConstructors` 到 jni-config |
| `Graphics Device initialization failed for: d3d, sw` | DLL 不在 EXE 同目录 | 复制所有 DLL 到 EXE 目录 |
| `InternalError: Error loading stock shader FillPgram_Color` | D3D shader 资源未包含 | 在 resource-config.json 添加 `.*\\.obj$` |
| `Cannot determine type for property` (FXML) | 属性 getter/setter 未注册 | 注册属性所属的**父类**而非子类 |
| 构建超过 10 分钟 | 杀毒软件实时扫描 | 排除项目目录 |
| Build crash (OOM) | 内存不足 | 增大 `-J-Xmx` 值 |

### 8.3 运行时

| 现象 | 可能原因 | 解决方法 |
|------|----------|----------|
| 双击 EXE 弹出黑窗口 | PE 子系统是 CONSOLE | 运行 `editbin /SUBSYSTEM:WINDOWS` |
| 浏览按钮无效 | JNI 回调类未注册 | 添加 `java.io.File`/`DirectoryChooser` 到 jni-config |
| UI 切换后控件状态异常 | JavaFX 属性类未注册 | 添加 `BooleanProperty`/`StringProperty` 等 |
| 提取处理失败 | 内部类或 Jakarta Mail handler 未注册 | 检查 reflect-config 是否覆盖所有 processor/extractor/util 类 |
| 图标不显示 | Ikonli 字体/SPI 未注册 | 检查 resource-config 包含 `.ttf` 和 `META-INF/services/` |

### 8.4 调试建议

1. **构建时加 `--verbose`**: 输出详细分析信息，可以看到找到的类型数、注册的反射类型数
2. **对比 agent 配置**: 搜索 `reachability-metadata.json` 确认怀疑的类是否存在
3. **命令行运行 EXE**: 从 cmd 运行 `MhtExtractor.exe` 可以看到 `System.err` 输出（异常堆栈），GUI 模式下这些输出不可见
4. **检查反射注册数**: 构建日志中 `types registered for reflection` 应 ≥ 4000（完整配置）
5. **分步验证**: 先确保 JVM 模式正常运行（`java -jar target/...`），再构建 native-image

### 8.5 已知环境陷阱（2026-05 验证）

**错误**: `Module org.graalvm.nativeimage.builder not found`，native-image 启动即崩溃。

**根因**: GraalVM 安装路径（任一级目录）包含中文字符。模块解析器无法处理非 ASCII 路径。

**解决**: 将 GraalVM 移动到纯英文路径，例如 `Y:\graalvm-sdk\`。同时确保项目路径、输出路径也避免中文。

---

**错误**: `Writing image to non-existent directory target\native-image\.library.path=target is not allowed`。

**根因**: 在 PowerShell 中直接调用 native-image 时，`-Djava.library.path=target\jfx-dlls` 参数被 PowerShell 误解析为 `-Djava` 参数名 + `.library.path=...` 值，导致输出路径被拼接。

**解决**: 
- 将 `-Djava.library.path=target\jfx-dlls` 写入 `META-INF/native-image/com.mhtextractor/native-image.properties`（`JavaArgs = -Djava.library.path=target\\jfx-dlls`），命令行不再传此参数。
- 或从原生 Windows 命令提示符（cmd.exe）运行，而非 PowerShell。

---

**错误**: `Failed to automatically set up Windows build environment`，cl.exe 无法找到。

**根因**: MSYS2/Git Bash 环境中，`cmd.exe //c` 无法访问 `C:\Program Files\Microsoft Visual Studio\` 目录（文件系统虚拟化/路径编码问题）。

**解决**: 
- 不通过 bash 启动 cmd.exe，直接在 Windows 原生 cmd.exe 或 PowerShell 中运行构建脚本。
- 如果用 PowerShell，手动设置 MSVC 环境变量（PATH/INCLUDE/LIB），不要依赖 `vcvars64.bat`。

---

**错误**: NIK 25.0.3 native-image 链接失败 `error LNK2001: unresolved external symbol Java_com_sun_glass_ui_Application__1overrideNativeWindowHandle`。

**根因**: NIK 25.0.3 内置 JavaFX 25 的静态链接库 `glass.lib` 与项目当前 JNI 配置（基于 JavaFX 21 生成）不兼容。NIK 使用静态链接，GraalVM 使用动态链接。

**解决**:
- 编译用 NIK 25.0.3，native-image 仍用 GraalVM 25.0.2（动态链接模式）。
- 或重新用 Tracing Agent 在 NIK 环境下生成全套 native-image 配置文件。

---

### 8.6 已知环境陷阱（2026-09 架构重构 + 国际化后新增）

**错误**: `Error: Failed to find 'vswhere.exe' for locating Visual Studio installations.`
（native-image 退出码 20）

**根因**: 本机**未安装 MSVC 工具链**。GraalVM native-image 在 Windows 上**只支持 MSVC**
（**不支持 MinGW/GCC**），且 `--no-fallback` 要求走完 C 编译+链接。

**解决**: 安装 **Visual Studio 2022 Build Tools（17.6+）**，勾选「使用 C++ 的桌面开发」+
「MSVC v143 - VS 2022 C++ x64/x86 生成工具」+「Windows 10/11 SDK」。
装后 `vswhere.exe` 出现在 `C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe`。
> 实测：本机即使是最小 HelloWorld 也无法构建，故此问题**必须先解决才能验证任何 native 改动**。

---

**现象**: 无头层测试断言"JavaFX 不在 classpath 上"失败（`pom.xml` 的 `headless-layer-b` 执行）。

**根因（两层）**: ① 该项目 surefire 的 `classpathDependencyExcludes` 写的是无 classifier 的
`org.openjfx:javafx-controls`，而实际依赖带 **classifier `win`**（并带出无 classifier 的传递变体），
**一个都没匹配上**；② **决定性原因**：**Liberica `Full` JDK 把 JavaFX 内置为 JDK 运行时模块**
（`javafx.base@25.0.3` … 共 7 个，见 `<JDK>/jmods/javafx.base.jmod`）。
`classpathDependencyExcludes` 只能剔 Maven 依赖，**剔不掉 JDK 模块**。

**验证方法**: 剔除 classpath 上全部 `openjfx` 条目后，用探针跑
`Class.forName("javafx.application.Platform")` —— **仍然成功**，即"无 JavaFX classpath"本机物理不可达。

**解决（三选一）**: ① 断言降级为 `assumeTrue` 并标注"本机不可验证"；
② **用不含 JavaFX 的 JDK**（Liberica `Standard`/`Lite` 或 Temurin）执行该 surefire execution；
③ 固化为手工脚本 + 证据留档。**并修正/删除 `classpathDependencyExcludes` 的错误坐标。**

---

**错误**: native EXE 运行到"预览缓存落盘"或"切换语言"时才崩溃（构建期完全正常）。

**根因**: 缺少运行期反射/资源元数据（**这类问题不会在 `mvn package` 阶段暴露**）：
① `PreviewCacheMeta` 等 **Jackson 序列化的 record** 需要 `<init>` + 访问器反射条目；
② i18n 需要**资源注册** `{"bundle": "i18n/messages"}`（**不是** `ResourceBundle` 的反射条目——
曾判断错方向，agent 实测该反射需求为 **0 条**）。

**解决**: 见 **§4.9**（采集与合并纪律）。核心三条：
用 tracing agent 采集（勿手工推断）、**按并集合并**、**核对 `git diff --numstat` 为纯增量**。

---

**错误**: 构建产物里多出 `reachability-metadata.json.bak` 之类的文件。

**根因**: 把备份/临时文件放在了 `src/main/resources/` 下 —— Maven 会把该目录**全部**内容打进 jar。

**解决**: 备份放仓库外或 `.claude-work/`（已 gitignore）。**永远不要在 `src/main/resources` 下留临时文件。**

---

## 九、给 Claude Code CLI 的提示

如果在新的会话或新设备上使用 Claude Code CLI 打包此项目：

1. **先读本文档**，了解整体流程；**并先读 §顶部的"打包前必读"**（当前阻塞与待改项）
2. **运行环境检查**（第三章），确认 JDK 25 / native-image / Maven / **MSVC** 都已就绪。
   > NIK 25.0.3 **自带 `native-image`**，故独立 GraalVM 已非必需（历史方案仍可用）
3. **检查配置文件**（第四章），`src/main/resources/META-INF/native-image/com.mhtextractor/` 下的配置文件需存在；
   **但注意**：`reflect-config.json` 已过时且与 `reachability-metadata.json` 不一致（见 §4.9.4），
   统一格式才是权威
4. **编译用 NIK 25 的 JDK；native-image 可用 NIK 自带的**（若遇 §8.5 的静态链接符号错误，再退回 GraalVM 25.0.2）
5. **所有工具路径必须纯 ASCII**，含中文的路径会导致模块加载失败（`Module org.graalvm.nativeimage.builder not found`）
6. **`--initialize-at-run-time` 参数一个都不能少**，缺失任何一个 JavaFX 子包都会崩溃
7. **DLL 必须放在 EXE 同目录**，包括 JNI 桥接库 `awt.dll`/`java.dll`/`jvm.dll`
8. **构建后用 `editbin /SUBSYSTEM:WINDOWS`** 隐藏控制台窗口
9. **遇到 `Failed to delete target\lib`**: 跳过 clean，用 `mvn package` 而非 `mvn clean package`
10. **本机 `mvn` 不在 PATH**：用 Maven wrapper，并令 `JAVA_HOME` 指向 NIK 25
11. **架构/功能改动后必须重采元数据**（§4.9）：新增 Jackson DTO → 补反射；
    新增 FXML/properties/图片 → 补资源 pattern
12. **遇到新错误**: 搜索本文档第八章（含 §8.5/§8.6 的实测陷阱），或在命令行运行 EXE 获取具体堆栈

### 自动化建议

Claude Code CLI 可以通过以下步骤在新设备上自动完成全部打包：

1. 读取本文档了解流程（**含 §顶部"打包前必读"**）
2. 搜索本机环境（`where java`、`where mvn`、检查 VS 安装与 `vswhere.exe`、`java --list-modules | findstr javafx`）
3. 如果没有找到，提示用户提供路径或自行安装（**MSVC 缺失则无法继续，务必先明确告知用户**）
4. 检查 `src/main/resources/META-INF/native-image/` 目录是否存在
5. 如果配置文件不存在或不完整，**用 tracing agent 采集**（§4.9.3），不要手工推断
6. 按第五章命令执行构建（**先把 `build-native.cmd` 的 4 处 `X:\` 路径改成本机实际值**）
7. 如果构建失败，根据错误信息对照第八章排查
8. **构建成功后务必冒烟验证**：启动 → 导入 → 预览（确认缓存目录生成）→ 提取 →
   切换语言（zh/en 各一次）→ 退出（确认缓存清空）。这些路径正是元数据缺失时才会崩的地方
