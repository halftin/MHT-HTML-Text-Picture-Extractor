# 基于 GraalVM 的 MHT 资源提取器项目打包工作流程

> **目标**: 将 JavaFX 25 + Maven 项目编译为 GraalVM Native Image 绿色免安装 Windows EXE。
> **适用场景**: 从 Git 拉取代码后，在任何 Windows 设备上完成打包。
> **最后更新**: 2026-10-09 | **已验证 JDK 版本**: Liberica NIK 25.0.3 + Oracle GraalVM 25.0.2+10.1
> **项目版本**: 以 `pom.xml` 的 `<version>` 为**唯一来源**（当前 `3.0.0`）。所有构建脚本（`build-native.cmd` / `.ps1` / `.sh` / `run-native-image.bat`）均从 pom 动态读取，**禁止**在脚本里硬编码 jar 名（历史问题：曾写死 `mhtExtractor-2.3.1.jar` / `-2.2-SNAPSHOT.jar`，与 pom 不一致导致打包找不到 jar）。

> ## 📌 状态更新（2026-09-27 实测）—— **以脚本与 CLAUDE.md 为准**
>
> **权威来源**：`build-native.cmd`（脚本本身）与 `CLAUDE.md` 的「native 打包现状」。本文件是**打包参考手册 + 打包机操作手册**（环境清单 / 元数据体系 / 构建细节 / 验收清单）；**打包机上的最短路径**见 **§10.2**，**打包后人工验收清单**见 **§10.5.6**。
> 本文档其余章节保留为**背景与原理**；其中的示例路径 / 版本号可能已过时（下表已逐条标注）。
>
> | 项 | 状态 |
> |---|---|
> | **MSVC 工具链** | ✅ **已装好并实测打通（2026-10-02）**：VS 2026 Community 装在 `E:\msvc\community`（含 VC.Tools.x86.x64），`vswhere.exe` 在 `C:\Program Files (x86)\Microsoft Visual Studio\Installer\`，Windows SDK 10.0.26100 在 `E:\Windows Kits\10\`（注册表 `KitsRoot10` 指向它）。`check-env.cmd` → **exit=0「全部就绪」**，native EXE 已成功产出并逐字节验证。**下面「仍是唯一卡点」的说法已作废，详情见 §十** |
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
> **（历史记录）如何解除 MSVC 缺失** —— 本机 2026-10-02 已完成，不再需要执行：
> 装 Visual Studio **任意版本**的「使用 C++ 的桌面开发」工作负载即可（本机装的是 VS 2026 Community）。
> 关键点是 `vswhere.exe` 必须出现在 `C:\Program Files (x86)\Microsoft Visual Studio\Installer\` ——
> 它由 VS Installer 放在那里，**与 VS 本体装在哪块盘无关**（本机 VS 在 `E:\msvc\community` 也能被找到）。
> 装完先跑 `check-env.cmd` 确认退出码为 0。
>
> **另需知**：项目已完成架构重构（新增零 JavaFX 的 `app` 应用层）与全量国际化（**547 键 × 2 locale** + 22 错误码）。
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
| **架构分层**（2026-09 重构后） | `gui → app → {core, extractor, processor, output, util, cache, config, i18n}`；`model` 为纯数据模型。**业务层与 app 层零 JavaFX**，由 `ArchitectureTest` 的 **17 条**不变式强制（2026-10-08 实测） |
| **国际化** | zh_CN / en_US，**各 547 键**（`src/main/resources/i18n/messages_*.properties`，两份键集与占位符集合必须一致）+ **22** 个 `ErrorCode` → **native 需资源注册**（见 §4.9） |
| **预览缓存** | 会话级 `<java.io.tmpdir>/mht-extractor-preview/`，Jackson 读写 `PreviewCacheMeta` → **native 需反射条目**（见 §4.9） |
| **测试** | **768** 单测（默认执行）+ **68** 无头（`headless-layer-b`）；**42 份**产物黄金基线（21 例 × zh/en）；GUI 自动化 **zh/en 各 34/0**（入口见 §10.5.5）。当前回归基线汇总见 **§10.11** |

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

- 已用**完整 JDK** 编译出 fat JAR: `mvn clean package -DskipTests`（**现行口径 JDK 25 / Liberica NIK**；历史记录中的 JDK 21 仅作背景）
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

> 🚫 **本节示例已作废（2026-10-02 起）**：**不要**再往生产类（`App` / `MainController`）里添加演练方法。演练入口现在位于**测试源集**（`com.mhtextractor.gui.acceptance.TestMain` → `FeatureExercise` / `AgentExercise`），生产启动器不含任何测试分支。以下清单仅作"演练应覆盖哪些功能"的**内容参考**。

（历史做法）在 `MainController.java` 中添加 `exerciseAllFeatures()` 方法，程序化演练所有 UI 功能。关键演练内容:

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
  {"name": "com.mhtextractor.model.AppConfig"},
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
| `com.mhtextractor.model.AppConfig` | 全部 getter/setter（字段较多） | 命名配置 `config/*.json` |
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
     -Dtest.mode=true -cp "target/classes;target/test-classes;<cp.txt>" com.mhtextractor.gui.acceptance.TestMain
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
**🚨 该"建议删除"已被实测推翻（2026-09-27 实测，详见 `CLAUDE.md`）**：旧格式 **291** 个类型、统一格式 **194** 个，其中 **231 个仅存在于旧格式**；native-image 对同一 type 取**并集**，**直接删旧文件会丢 231 条**。正解是"并集合并 + 方法/字段级对账（丢失数=0）"。

**📌 2026-10-04 更新：过时条目已清理（策略从"只并集"升级为"并集 + 过时项审计"）**

- 判据：该注册的类在 native 构建真正看得到的产物里是否**存在** —— `target/lib/*.jar`、shaded jar、
  `target/classes`，以及两台 JDK 的模块镜像。
- 清理结果（提交 `cea00c3`）：删掉 **65 个"类已不存在"的注册**（reflect-config / reachability / jni 共 72 条），
  含 JavaFX 8 时代的 `com.sun.javafx.scene.control.skin.*Skin`、包名写错的
  `javafx.scene.control.{BorderPane,HBox,…}`、`prism.*` 内部类，以及本项目已删类
  `JavaFxUtil$SmoothProgressProperty` 的悬空条目（即上面那条"悬空"已消除）。
  **类型级对账 loss = 0**；另补注册了效果用的 HLSL 着色器资源 `LinearConvolveShadow_8.obj`。
- **审计脚本（随仓库分发）**：`tools/native/audit-native-metadata.ps1` —— 过时项扫描 +
  `-AgentDir` 差集（自动过滤 FXMLLoader 失败查找造成的幻影条目）。
- **清理后回归**：native 产物基线 **42 passed / 0 failed**（2026-10-05 复核；21 例 × zh/en）。
- ⚠️ **仍然不要"整文件重写"**：任何元数据改动都按 §4.9.3 的纯增量 + 方法/字段级对账执行。

（§4.4 的手工模板仅作历史参考；若仍保留旧格式文件，注意其条目会与统一格式合并。）

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
#     OUTPUT_DIR      = <仓库同级>\<仓库名>-dist（**2026-10-02 起的新默认**，
#                       例：D:\JAVA\mht-resource-extractor-dist）
#                       🚨 旧默认是 <仓库>\dist，已改掉：本机实测**项目树内**的 native EXE 会让
#                          JavaFX 的 Font.loadFont(...) 返回 null，全部 Ikonli 图标变空心方框；
#                          输出到树外即正常。脚本现在会在输出目录位于树内时明确告警。详见 §十。
#     NATIVE_XMX      = 4G（默认；原脚本写死 8G，本机总内存约 15 GB）
#     NATIVE_RECIPE   = dynamic（**默认**，≈70 MB，随包 7 个 JavaFX DLL）
#                       static（≈98 MB，JavaFX 静态链接进 EXE，dist 不含 JavaFX DLL）
#                       脚本会挑"配方匹配"的 native-image JDK；两种都装时靠它区分。
#   ✅ MSVC 已于 2026-10-02 装好（E:\msvc\community），native-image 能自己用 vswhere 找到它。

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

# ========== Step 3: 抽取随包 DLL（**按配方**，2026-10-02 修正）==========
# 随包内容由**配方**决定（判据见 §十；build-native.cmd 会自动判定并打印）：
#   dynamic（默认，≈70 MB）：抽 7 个 JavaFX DLL + 5 个 VC++ 运行库 → 必须随包
#   static （≈98 MB）      ：**只**抽 5 个 VC++ 运行库 → JavaFX 已静态链接进 EXE，
#                            再放同名 DLL 会双份加载并在 WinApplication._init 段错误
#                            （ExceptionCode -1073741819，读地址 0x0）
mkdir -p target/jfx-dlls
cd target/lib
JFX_GRAPHICS_JAR=$(ls javafx-graphics-*-win.jar | head -1)
JFX_NATIVE_DLLS=$( [ "$RECIPE" = dynamic ] && echo "glass.dll prism_common.dll prism_d3d.dll prism_sw.dll decora_sse.dll javafx_font.dll javafx_iio.dll" )
for dll in $JFX_NATIVE_DLLS msvcp140.dll msvcp140_1.dll msvcp140_2.dll \
            vcruntime140.dll vcruntime140_1.dll; do
    jar xf "$JFX_GRAPHICS_JAR" "$dll" 2>/dev/null
    mv "$dll" ../jfx-dlls/ 2>/dev/null
done
cd ../..

# ========== Step 4: Native Image 编译 ==========
# GRAALVM_HOME 决定配方：
#   Oracle GraalVM（无 JavaFX 静态库） → dynamic，≈70 MB
#   NIK Full（自带 JavaFX 静态库）      → static，≈98 MB
#   用 NATIVE_RECIPE=dynamic|static 可强制；build-native.cmd 会自动挑匹配的那个 JDK。
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
# ⚠️ 下一行**只在 static 配方**需要（NIK）；dynamic 配方必须留空，否则链接器找不到该 .lib：
#   -H:NativeLinkerOption="<NIK>\lib\static\windows-amd64\prism_common.lib"

# ========== Step 5: 打包分发 ==========
# native-image 会把 EXE **以及它动态依赖的 JDK DLL**（awt/fontmanager/freetype/java/
# javaaccessbridge/javajpeg/jawt/jvm/lcms）一起写进 -H:Path 指定的目录，整目录搬走即可；
# 再补上 Step 3 抽出来的 5 个 VC++ 运行库 DLL。
# 🚨 配方相关（见 Step 3 的说明）：**static 配方不要**把 JavaFX DLL 放进 dist（WinApplication._init 段错误）；**dynamic 配方必须随包**。
mkdir -p target/dist
cp target/native-image/MhtExtractor.exe target/dist/
cp target/native-image/*.dll target/dist/          # native-image 自动落地的 9 个 JDK DLL
cp target/jfx-dlls/msvcp140*.dll target/dist/      # 5 个 VC++ 运行库
cp target/jfx-dlls/vcruntime140*.dll target/dist/

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

> 🚨 **`-H:NativeLinkerOption=...prism_common.lib` 只在 static 配方（NIK）需要**。
> 不加会以 `error LNK2001: unresolved external symbol
> Java_com_sun_glass_ui_Application__1overrideNativeWindowHandle` 结束 —— 原因：`jni-config.json`
> 给 `com.sun.glass.ui.Application` 开了 `allDeclaredMethods`，native-image 于是为它的 native 方法
> 生成 JNI 入口调用，而该符号的实现住在 **prism_common.lib**（`dumpbin /EXPORTS` 实测），
> native-image 自己挑库时却只挑了 glass / prism_d3d / javafx_font / javafx_iio。
> **dynamic 配方不需要它**：该符号由 `java.library.path` 里的 `prism_common.dll` 提供（link.exe 直接
> 吃 DLL 的导出表）。`build-native.cmd` 会按配方自动处理（static 找不到 .lib 时打印 `[WARN]`）。

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
`OUTPUT_DIR`（默认 **`<仓库同级>\<仓库名>-dist`，即项目树外**；旧默认 `<仓库>\dist` 已废弃，理由见 §10.7.1）/
`NATIVE_RECIPE`（**`dynamic` 默认** ≈70 MB / `static` ≈98 MB，见 §10.3.4）/ `NATIVE_XMX`（默认 4G）/ `VSWHERE` / `LOG_DIR`。

**配方（`NATIVE_RECIPE`）决定三件事**：用哪个 native-image JDK、抽不抽那 7 个 JavaFX DLL、dist 是 22 个文件还是 15 个。
三档开关的期望产物：

| 命令 | 期望 |
|---|---|
| `build-native.cmd --prepare-only` | exit 0；`mvn package` 成功；`target\jfx-dlls` 下 **12 个 DLL**（dynamic = 7 JavaFX + 5 VC++；static 只有 5 个）；`logs\native-build-<stamp>.{log,steps.tsv,report.json}` 三件套 |
| `build-native.cmd` | 上表 + native-image 链接 + 组装 dist；**`[STEP SMOKE]` 还会在 native 镜像里渲染一次提醒卡片**（防"native 下卡片不渲染"这类专属回归，见 §8.7） |
| 失败时 | 打印 `=== FAILURE ===`（失败步 + 退出码 + 日志末 40 行），**仍然产出报告** |

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
| `Graphics Device initialization failed for: d3d, sw` | **旧配方**（动态 JavaFX / Oracle GraalVM）下 DLL 不在 EXE 同目录 | 仅**旧配方**需要把 JavaFX DLL 放同目录。**NIK Full 下恰恰相反**：JavaFX 已静态链接，再放同名 DLL 会双份加载并段错误（§十） |
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
| 图标**全变空心方框** | **EXE 位于项目树内**（本机实测；与构建配方无关） | 先把 EXE 挪到项目树外再跑；再用 §10.5.4 的 `Font.loadFont` 探针确认。本项目已把默认 OUTPUT_DIR 移出树外并在构建时告警 |
| 图标不显示（少数几个） | Ikonli 字体/SPI 未注册 | 检查 reachability-metadata 的 `resources` 是否含 `META-INF/resources/fontawesome5/**/fa-*.ttf` 与 `META-INF/services/org.kordamp.ikonli.IkonHandler` |

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

**根因（2026-10-02 纠正）**: **不是** "NIK 静态库与 JavaFX 21 时代的 JNI 配置不兼容"。
真实原因是：这个符号的实现**在 `prism_common.lib` 里，不在 `glass.lib` 里**（`dumpbin /EXPORTS` 实测），
而 native-image 自己挑静态库时只挑了 `glass / prism_d3d / javafx_font / javafx_iio`，于是没人提供它。

**解决（一行，已内置到 `build-native.cmd`）**:
```
-H:NativeLinkerOption=<NIK>\lib\static\windows-amd64\prism_common.lib
```
**不需要**换回 Oracle GraalVM —— 本机已用 NIK 25.0.3 走通全部 6 步并产出可交付的 EXE。

---

### 8.6 已知环境陷阱（2026-09 架构重构 + 国际化后新增）

**错误**: `Error: Failed to find 'vswhere.exe' for locating Visual Studio installations.`
（native-image 退出码 20）—— ✅ **本机 2026-10-02 已解除**

**根因**: 未安装 MSVC 工具链。GraalVM native-image 在 Windows 上**只支持 MSVC**
（**不支持 MinGW/GCC**），且 `--no-fallback` 要求走完 C 编译+链接。

**解决**: 装 Visual Studio（任意版本）+「使用 C++ 的桌面开发」工作负载即可；VS 本体可以装在
**非 C 盘**（本机在 `E:\msvc\community`），只要 `vswhere.exe` 出现在
`C:\Program Files (x86)\Microsoft Visual Studio\Installer\`。装完 `check-env.cmd` 应报 exit=0。
> 历史记录：本机曾实测"连 HelloWorld 都 exit=20"，那是装 VS 之前的状态，现已不再适用。

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

### 8.7 症状 → 排查速查（2026-10 之后新增/变动项）

> 上面 §8.1–8.6 与 §八 其余条目仍然有效；本节只列**最近几轮改动后**新增或已变动的排查方向。

| 症状 | 排查方向 |
|---|---|
| 界面出现 `!key!` 字样 | i18n **bundle 未注册** → 检查 `reachability-metadata.json` 是否含 `{"bundle": "i18n/messages"}` 与各 locale glob（§4.9.2） |
| 预览报错 / 缩略图全部"无法加载" | ① `PreviewCacheMeta` 及其嵌套 record 的**反射条目缺失**（§4.9.1，用 agent 重采）；② 图片是 WebP 时缺 `org.glavo.webp` 相关条目 |
| FXML 加载失败（`LoadException` / 控件构造器 `NoSuchMethodException`） | 资源模式缺该 FXML（§4.9.2）；`<fx:define>` 实例化的控件需反射构造器（§10.3.5）。⚠️ **模态参数窗口类已删除**，参数表单仍由 `ParamDialog.fxml` + `ParamFormPane` 装载，该 FXML 仍需注册 |
| 找 `ParamDialogController` / 参数窗口 `Stage` | 已不存在（阶段 D 删除）：参数编辑只有**右侧非模态侧边栏**一条路径（`ParamSidePanel` / `ParamFormPane` / `ParamPanelActions`） |
| GUI 自动化"跑过了"但结果可疑 | 入口是**测试源集**的 `com.mhtextractor.gui.acceptance.TestMain`（生产启动器 `App` **已不含**任何测试分支）。跑前删 `logs/test-results.txt`、跑后核对 **mtime**；locale 由 `logs/gui-state.json` 决定（**覆盖** `-Duser.language`） |
| native 下提醒卡片不渲染 / 界面变黑卡死 | JavaFX **效果 peer** 的反射注册缺口（2026-10-03 已修）；`build-native.cmd` 已内置 `[STEP SMOKE]` 卡片渲染冒烟，正常跑一次构建即可拦住该回归 |
| "无 JavaFX classpath"断言在本机跑不通 | Liberica **Full** JDK 把 JavaFX 内置为 JDK 运行时模块 —— 该断言在本机**物理不可达**，见 §8.6 |

---

## 九、给 Claude Code CLI 的提示

如果在新的会话或新设备上使用 Claude Code CLI 打包此项目：

1. **先读本文档**，了解整体流程；**并先读 §顶部的"打包前必读"**（当前阻塞与待改项）
2. **运行环境检查**（第三章），确认 JDK 25 / native-image / Maven / **MSVC** 都已就绪。
   > NIK 25.0.3 **自带 `native-image`**，故独立 GraalVM 已非必需（历史方案仍可用）
3. **检查配置文件**（第四章），`src/main/resources/META-INF/native-image/com.mhtextractor/` 下的配置文件需存在；
   **但注意**：`reflect-config.json` 已过时且与 `reachability-metadata.json` 不一致（见 §4.9.4），
   统一格式才是权威
4. **编译 JDK 与 native-image JDK 是分开解析的**：默认 `NATIVE_RECIPE=dynamic` 用 Oracle GraalVM（JavaFX 随包 DLL，≈70 MB）；`set NATIVE_RECIPE=static` 换 NIK Full（JavaFX 静态链接，≈98 MB）。若遇 §8.5 的 LNK2001，**不要**换 GraalVM，加 `-H:NativeLinkerOption=...prism_common.lib` 即可（脚本已内置）
5. **所有工具路径必须纯 ASCII**，含中文的路径会导致模块加载失败（`Module org.graalvm.nativeimage.builder not found`）
6. **`--initialize-at-run-time` 参数一个都不能少**，缺失任何一个 JavaFX 子包都会崩溃
7. **"DLL 放同目录"这条要分开看**：
   - native-image **自动落地**的 JDK DLL（`awt`/`java`/`javaaccessbridge`/`javajpeg`/`jawt`/`jvm`/`lcms`，个别配方还会多 `fontmanager`/`freetype`）+ 5 个 VC++ 运行库 DLL → **必须随包**；
   - JavaFX 的 `glass`/`prism_*`/`javafx_font`/`javafx_iio`/`decora_sse` → **只有 dynamic 配方随包**；**static（NIK Full）下绝不能放**（会段错误，§十）。
   > 两份清单的**权威来源**是构建报告 `logs/native-build-<stamp>.report.json` 的 `artifacts`（带每个文件的 SHA256），不要照抄本文档的历史清单
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
6. 按第五章命令执行构建（**`build-native.cmd` 已自动探测路径，无需手工改任何路径**；要覆盖用环境变量）
7. 如果构建失败，根据错误信息对照第八章排查
8. **构建成功后务必冒烟验证**：启动 → 导入 → 预览（确认缓存目录生成）→ 提取 →
   切换语言（zh/en 各一次）→ 退出（确认缓存清空）。这些路径正是元数据缺失时才会崩的地方
---

## 十、2026-10-02 实机打通记录：可复用配方、踩过的坑与验证流程

> **本章是最新事实。与前面任何章节冲突时，一律以本章为准。**
> 本章所有结论都在本机实测过，并留有可复查的证据（截图 / 构建日志 / SHA-256 / 产物树）。

### 10.1 环境事实（本机，2026-10-02）

| 组件 | 实际位置 | 说明 |
|------|----------|------|
| MSVC | `E:\msvc\community` | VS **2026** Community 18.10.3，含 `Microsoft.VisualStudio.Component.VC.Tools.x86.x64`。`cl.exe` 在 `VC\Tools\MSVC\{14.44.35207, 14.51.36231}\bin\Hostx64\x64\` |
| vswhere | `C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe` | native-image 靠它定位 VS；**VS 本体可以装在非 C 盘**，关键是它在标准位置 |
| Windows SDK | `E:\Windows Kits\10\Lib\10.0.26100.0` | 注册表 `HKLM\SOFTWARE\WOW6432Node\Microsoft\Windows Kits\Installed Roots` 的 `KitsRoot10 = E:\Windows Kits\10\` |
| 编译 JDK | `D:\install\New Folder`（javac 25.0.2） | **纯 JDK**（无 JavaFX 模块）；JavaFX 类由 Maven 依赖提供；Lombok 正常 |
| native-image JDK（**默认，dynamic**） | `D:\JAVA\graalvm-jdk-25_windows-x64_bin\graalvm-jdk-25.0.2+10.1` | Oracle GraalVM 25.0.2：**无** JavaFX 静态库 → JavaFX 走随包 DLL，≈70 MB。⚠️ 它是 zip 解压的**两层**结构，脚本已会多探一层 |
| native-image JDK（可选，**static**） | `D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3`（NIK Full 25.0.3） | 自带 `lib\static\windows-amd64\*.lib` → JavaFX 静态链接进 EXE，≈98 MB，dist 不含 JavaFX DLL。`set NATIVE_RECIPE=static` 即切换 |
| Maven | 仓库自带 `mvnw.cmd` | 不必预装；脚本会自动找 wrapper 分发或 PATH 上的 `mvn.cmd` |

`check-env.cmd` → **exit=0「全部就绪」**。

### 10.2 一次成功构建的最短路径

```bat
:: 0) 预检（应 exit=0）
check-env.cmd

:: 1) 直接跑，什么都不用设（脚本自动探测 JDK / native-image / Maven / MSVC）
build-native.cmd
::   默认输出到 <仓库同级>\<仓库名>-dist，例：D:\JAVA\mht-resource-extractor-dist
::   想放别处（推荐树外）：set OUTPUT_DIR=D:\JAVA\newmht

:: 其他开关
build-native.cmd --prepare-only     :: 只做 JVM 侧准备（mvn package + 抽 DLL），不调 native-image
build-native.cmd --skip-package     :: 跳过 mvn package
build-native.cmd --no-editbin       :: 保留控制台子系统（排启动期崩溃时用）
```

本机实测耗时：`mvn clean package` 约 1–2 分钟 + `native-image` 约 **1 分 26 秒**。

**打包机标准操作流程（按顺序，别跳步）**

1. **预检**：`check-env.cmd` → **exit 0**（`20` = 只缺 MSVC，`1` = 缺其它关键项）。
2. **只准备不链接**：`build-native.cmd --prepare-only` → exit 0；确认 `target\jfx-dlls` 下 DLL 数符合配方
   （dynamic 12 / static 5）、`logs\` 下三件套已生成。这一步不依赖 MSVC，用来先排环境问题。
3. **元数据**：**仅当元数据相关代码有改动**（新增 Jackson DTO / FXML / properties / 图片资源）才重采，
   按 §4.9.3 的并集合并 + 方法/字段级对账（丢失 = 0）执行。
4. **先跑 JVM 侧回归**（基线见 §10.11）：确认真实功能没坏，再花时间做 native —— native 只会掩盖问题，不会暴露它。
5. **完整构建**：`build-native.cmd` → exit 0；核对 `logs\native-build-<stamp>.report.json` 的
   `status=OK` / `failedStep` 与 `artifacts`，**不要只看退出码**。
6. **验收**：§10.5 的三档机制验证（启动 / 产物黄金基线 / 交叉复核）+ **§10.5.6 的人工验收清单**。

### 10.3 五个真缺陷（根因 + 修法，别再重踩）

#### 10.3.1 `check-env.cmd` 在第 6/9 步直接崩：`\Microsoft was unexpected at this time`

- **根因**：批处理的 `:say` 子程序写成 `if "%~1"=="" ( echo. ) else ( echo %~1 )`。
  当传入字符串含 `(x86)` 时（如 `%ProgramFiles(x86)%\Microsoft Visual Studio\...`），那个 `)` 会在
  **解析期**提前闭合 `else` 块，剩下的 `\Microsoft ...` 变成非法语法 → cmd 中止整批，退出码 255。
- **定位手法（可复用）**：先把原始输出 `> file 2>&1` 落盘（PowerShell 的 `2>&1 |` 会打乱 stdout/stderr 顺序），
  再往可疑行前插 `echo MARK-n` 逐行二分。本例 `MARK-115` 之后才报错，直接定位到第 125 行。
- **修法**：改用延迟展开 —— `set "SAYMSG=%~1"` + `echo !SAYMSG!`（延迟展开发生在解析之后，括号无害）。
  `build-native.cmd` 的 `:say` 有**同一颗雷**，已一并修掉。
- **通用教训**：`call :label "含(括号)的值"` 传给"带括号的 if 块"是通用陷阱；传路径给 `:say` 一律走延迟展开。

#### 10.3.2 `editbin` 永远找不到（EXE 停在控制台子系统，双击带黑窗）

- **根因**：脚本用 `dir /b /s "...\VC\Tools\MSVC\*\bin\Hostx64\x64\editbin.exe"`。
  **`dir /s` 不支持"中间目录"里的通配符**，静默返回空 —— 文件其实在 `14.44.35207\bin\HostX64\x64\`
  （注意 `HostX64` / `Hostx64` 大小写在不同版本里都不一样，Windows 文件系统不区分大小写，所以 `Hostx64` 能匹配两者）。
- **修法**：`for /d %%d in ("%VSPATH%\VC\Tools\MSVC\*") do if not defined EDITBIN if exist "%%~d\bin\Hostx64\x64\editbin.exe" set "EDITBIN=..."`
- **验证**：`check-env.cmd` 的 `[6/9]` 现在打印 `[OK] editbin.exe`；构建第 6 步打印
  `editbin: GUI 子系统已启用（双击不再弹黑窗）`。

#### 10.3.3 LNK2001：`Java_com_sun_glass_ui_Application__1overrideNativeWindowHandle`

见 §8.5（已就地纠正）。一行修法：
`-H:NativeLinkerOption=<NIK>\lib\static\windows-amd64\prism_common.lib`。
`build-native.cmd` 已内置（自动探测 + 找不到时 `[WARN]`）。

#### 10.3.4 JavaFX DLL 能不能随包 —— **取决于配方**

| 配方 | JavaFX 原生库在哪 | dist 里放 JavaFX DLL？ |
|---|---|---|
| **dynamic（默认，≈70 MB）** | 在 EXE 外面，运行期 `System.load` | ✅ **必须随包**（glass / prism_common / prism_d3d / prism_sw / decora_sse / javafx_font / javafx_iio 共 7 个） |
| **static（≈98 MB）** | 已静态链接进 EXE | ❌ **绝不能放** |

- **static 下放了的后果**：EXE 同目录一旦存在 `glass.dll`，就会加载第二份 glass；静态链接那份的
  全局量从未初始化 → `com.sun.glass.ui.win.WinApplication._init` 解引用空指针。
- **对照实验（照这个格式做）**：同一份 static EXE，
  - 同目录放那 7 个 JavaFX DLL → `HasExited=True`，stderr 出现
    `[ [ SegfaultHandler caught a segfault ] ] ExceptionCode: -1073741819, reading address 0x0`；
  - 只放 native-image 自动落地的 9 个 JDK DLL + 5 个 VC 运行库 DLL → 正常出窗口。
- **修法**：`build-native.cmd` 第 4 步按配方决定抽哪些 DLL（并且**先清空** `target\jfx-dlls`，
  防止上一次另一种配方留下的 DLL 混进来），第 6 步把它们复制进 dist。

#### 10.3.5 元数据缺口：FXML 在第 24 行就崩

- **现象**：native EXE 能启动到 `FXMLLoader.load()`，然后
  `javafx.fxml.LoadException: /com/mhtextractor/gui/view/MainView.fxml:24`
  → `NoSuchMethodException: javafx.scene.control.ToggleGroup.<init>()`。
- **根因**：`MainView.fxml` 用 `<fx:define><ToggleGroup fx:id="modeToggleGroup"/></fx:define>`
  实例化控件，需要 `ToggleGroup` 的**反射构造器**条目，而当时的 `reachability-metadata.json` 里没有。
  同一轮 agent 采集还补出了 `com.sun.scenario.effect.impl.prism.ps.PPSBlend_SRC_INPeer` 与资源
  `.../hlsl/Blend_SRC_IN.obj`（混合效果用得到）。
- **修法**：跑 tracing agent（§10.6）并按**并集**合并。本次共补 **16 个反射类型 + 1 个资源**，
  `git diff --numstat` 为 **+128 / −0**（纯增量）。
- **教训**：**"能编译能链接"不等于"能跑"**。元数据缺口只在运行期炸，且往往炸在 FXML / Jackson / 资源这三处。

### 10.4 交付目录长什么样（两种配方，照抄即可）

**默认 = dynamic（≈70 MB / 22 个文件）** —— Oracle GraalVM，JavaFX 原生库随包：

```
MhtExtractor.exe        70,033,408 B   ← GUI 子系统（已 editbin）
glass.dll  prism_common.dll  prism_d3d.dll  prism_sw.dll  decora_sse.dll
javafx_font.dll  javafx_iio.dll                        ← 7 个 JavaFX DLL（**必须随包**）
awt.dll  fontmanager.dll  freetype.dll  java.dll  javaaccessbridge.dll
javajpeg.dll  jawt.dll  jvm.dll  lcms.dll              ← native-image 自动落地的 9 个 JDK DLL
msvcp140.dll  msvcp140_1.dll  msvcp140_2.dll  vcruntime140.dll  vcruntime140_1.dll
                                                       ← 5 个 VC++ 运行库
```

**可选 = static（≈98 MB / 15 个文件）** —— NIK Full，JavaFX 静态链接进 EXE：
同样这些文件，但 **去掉那 7 个 JavaFX DLL**（放了会段错误，§10.3.4）。

🚨 **dynamic 的 dist 还多两个文件：`启动.cmd` 与 `Launch.cmd`**（各约 2 KB）。**非 ASCII（中文）路径下
必须双击 `启动.cmd`**，直接双击 exe 会启动失败 —— 原因、对照实验与另外两个补救方案见 §10.8。
static 配方对路径无要求，直接双击 exe 即可。

两者都：
- **没有** `logs/`（应用首次运行才创建；打包时不要带）
- 整个目录**拷走即用**：无需 JDK/JRE，也无需预装 VC 运行库。
- **产物逐字节等价**：两种配方在 native 下都跑出 **42/0** 的产物黄金基线（§10.5.2），
  且与 JVM 产物树同名同内容。**选配方只影响体积，不影响产物。**

> 📌 **清单里的 JDK DLL 集合会随 native-image / JDK 版本略有出入**（例如本机 2026-10-04 用
> dynamic 配方复跑时是 `awt / java / javaaccessbridge / javajpeg / jawt / jvm / lcms` **7 个**，
> 没有 `fontmanager` / `freetype`；dist 共 **22 个文件**，`MhtExtractor.exe` **70,242,304 B**）。
> **以构建报告 `artifacts` 为准**，上表只用于"心里有数"。

### 10.5 验证配方（**三档，跑完才算验完**）

"能启动"≠"能提取"。本机实测证明必须做完下面三档。

#### 10.5.1 第一档：能启动、图标正常、能关掉

```powershell
# 拷到项目树外，从无关的工作目录启动（关键：不要从项目树内运行，见 §10.7.1）
$p = Start-Process 'D:\JAVA\newmht\MhtExtractor.exe' -WorkingDirectory 'C:\Windows' -PassThru
Start-Sleep 12; $p.Refresh()
$p.MainWindowTitle          # 期望 "MHT 资源提取器"
$p.CloseMainWindow()        # 期望 ~2 秒内 HasExited=True 且 ExitCode=0（无残留进程）
```

- 本机基线：**冷启动到窗口 0.42 s**（JVM 对照 1.93 s）；工作集 234 MB（JVM 300 MB）。
- `-D` 属性可用：`MhtExtractor.exe -Duser.language=en` → 窗口标题变 `MHT Resource Extractor`。

#### 10.5.2 第二档：跑仓库自己的产物黄金基线（**最强的一档**）

`src/test/java/com/mhtextractor/baseline/BaselineCases.java` 是一个**零 JavaFX、可复用**的无头运行器
（**21 个用例 × zh/en = 42 份基线**，每份含**逐产物 SHA-256 + 内容摘要**）。把它包进 native 镜像跑一遍，
就等于"让 native 二进制去对黄金基线"：

```
// .claude-work/baseline-probe/NativeBaselineProbe.java（本次新增，可复用）
// 主循环：for each case, for each loc("zh","en")
//           BaselineCases.run() 跑两遍（独立目录，确定性自检：两遍摘要必须一致）
//           再与 src/test/resources/baselines/<case>.<loc>.json 比对
```

构建要点：主类换成探针，classpath 用
`<probe classes>;target\classes;target\test-classes;<target\cp.txt 的内容>`，
其余 native-image 参数与正式构建完全相同（含 `-H:NativeLinkerOption=...prism_common.lib`）；
**运行时 CWD 必须是仓库根**（`BaselineCases.FIXTURE_DIR` 是相对路径）。

本机结果（`.claude-work/jvm-baseline.log` / `native-baseline.log`）：

```
[PASS] [text-merged-chapter/zh]  products=1 total=2 success=2 fail=0 skip=0
[PASS] [text-separate-titled/zh] products=2 total=3 success=2 fail=0 skip=1
... （共 40 行）
NATIVE-BASELINE: 42 passed, 0 failed     ← native 二进制
NATIVE-BASELINE: 42 passed, 0 failed     ← JVM 对照（同一探针）
```

> 📌 **2026-10-04 复核**：在元数据清理（§4.9.4 的 `cea00c3`）之后重建探针并重跑 ——
> **`NATIVE-BASELINE: 42 passed, 0 failed`**。⚠️ **探针的工作根必须传一个干净目录**
> （默认 `.claude-work/native-baseline-run` 不清空，而 `BaselineCases` 的会话目录名是固定的，
> 第二次运行会**追加/翻倍**已有文件 → 假失败）。`ProductBaselineTest` 用 JUnit `@TempDir`，不受影响。

覆盖到的**参数驱动行为**：章节编号（中文数字/西文）、标题标记提取命名、内容区域限定（字面量+正则）、
模板变量 `{filename}/{date}/{time}`、边界路径（不可解析/缺 CTE/递归超限）、图片单独/合并、
批量重命名 + 魔数扩展名纠正、高速内存（预加载）模式、区域+名称提取、ZIP/CBZ 打包、RFC 2047 中文文件名。

#### 10.5.3 第二档的交叉复核（换一种手段再验一遍）

把 `native-baseline-jvm/` 与 `native-baseline-native/` 两棵产物树（各 288 个文件）做逐文件 SHA 比对：

```
only in JVM: 0        only in native: 0
content differs: 16   ← 全部是 .zip / .cbz
```

追到根：**归档唯一差异是 ZIP 条目里的写入时刻（DOS 时间字段）**；
CRC / 压缩前后大小 / 压缩方式 / 属性 / 文件名 / **解压后条目内容**全部相同。
仓库基线本来就"只比归档内条目、不比归档字节"（注释写明"归档字节含时间戳"），
所以 16 个归档按条目比对 **0 处不一致**。

> ⚠️ 别用"JVM 连跑两遍字节相同"当控制组 —— 那个控制组**太弱**（两遍落在同一个 2 秒 DOS 时间桶里）。

#### 10.5.4 诊断图标问题的小探针（遇症状先用它）

```java
// 在 native 镜像里打印：ServiceLoader 找到几个 IkonHandler / 资源 URL / Font.loadFont 的返回
InputStream s = new FontAwesomeSolidIkonHandler().getFontResourceAsStream();
System.out.println("STREAM=" + (s == null ? "null" : s.available()));
System.out.println("FONT=" + Font.loadFont(s, 12.0));
```

- 项目树**外**运行：`STREAM=avail=202744` + `FONT=family='Font Awesome 5 Free Solid'` → 图标正常
- 项目树**内**运行：`STREAM=avail=202744` 但 `FONT=null` → 图标全变空心方框

#### 10.5.5 GUI 自动化（`test.mode`，**入口在测试源集**）

> 🚩 **P7（2026-10-02）起入口变了**：生产启动器 `com.mhtextractor.App` **不再读 `test.mode`**，也不再反射
> 调用演练类。GUI 自动化/演练的入口是**测试源集**的 `com.mhtextractor.gui.acceptance.TestMain`
> （按与 `App.start()` 相同的装配起真窗口，再驱动 `FeatureExercise`）。

```bat
:: 需要 target\test-classes（演练类在测试源码里，生产 jar 不含）
:: classpath = target\classes;target\test-classes;<target\cp.txt 的内容>；JavaFX 走 --module-path
java --module-path <jfx-win jars> --add-modules javafx.controls,javafx.fxml ^
     -Dtest.mode=true -Duser.language=zh -Duser.country=CN ^
     -cp "target\classes;target\test-classes;<cp.txt>" com.mhtextractor.gui.acceptance.TestMain
:: 结果落 logs/test-results.txt，末行应为 "=== RESULTS: 33 passed, 0 failed ==="
```

🚨 三条纪律（本项目真被坑过）：

- **跑前删 `logs/test-results.txt`，跑后核对它的 mtime** —— 否则读到的可能是上一次的 33/0；
  并确认日志里没有 `Exception in Application start method`。
- **locale 由 `logs/gui-state.json` 决定**，它**覆盖** `-Duser.language`：要跑 en 就先播种/移走该文件，
  并断言"英文运行的日志里中文字符数 = 0"。
- **不要**用 `mvn javafx:run -Dtest.mode=true` —— `-D` 会被 Maven 吃掉，测试根本不会启动。

zh 与 en 各 **33 passed / 0 failed** 是当前基线。把该入口打进 native 镜像在历史上也验过（zh/en 各 31/0，为旧基线值）；
**当前 `build-native.cmd` 内置的是更小的 `[STEP SMOKE]` 卡片渲染冒烟**（`tools/probes/AppCardProbe.java`），
native 侧的全量 GUI 自动化仍以 JVM 侧这份为准。
⚠️ GUI 断言验的是**控件状态**，**不验落盘产物**，所以它**替代不了** §10.5.2。

> 🚨 踩过的坑：`FeatureExercise.runAutomatedTests()` 里 `new FileWriter("logs/test-results.txt")`
> 在 `logs\` 不存在时会抛 `FileNotFoundException`，而异常被外层 `catch (Exception)` 吞掉 →
> 方法"正常"返回 → `System.exit(0)` → 现象是**2.4 秒静默退出、零产物、零堆栈**，极易被误判成"跑过了"。
> 已修（自建 `logs\` + 结果通道自足 + 退出码诚实）。判据仍然是**核对 `logs/test-results.txt` 的 mtime**。

#### 10.5.6 打包后人工验收清单（在 native EXE 上逐项过）

> §10.5.1–10.5.5 是**机制层**验证；本节是**功能层**清单，用于"机制都过了，功能是否真的没坏"。
> ⭐ = 直接命中 native 元数据风险点（i18n 资源注册 / Jackson 反射），**这几项必须做**。
> 运行前提：**从项目树外启动**（否则图标空心方框，§10.7.1）；dynamic 配方在中文路径下用 `启动.cmd`（§10.8）。

**A. ⭐ 元数据与生命周期（本项目的重点）**

| # | 验收项 | 为什么重要 |
|---|---|---|
| 1 | ⭐ 启动后切换语言（zh ⇄ en）：界面全量跟随、**无 `!key!` 残留** | 验证 i18n **bundle 资源注册**生效（最易漏） |
| 2 | ⭐ 点「查看预览效果」后，`%TEMP%\mht-extractor-preview\<64位hex>\` 下出现 `meta.json` + `images\` + `.complete` | 验证 Jackson 对 `PreviewCacheMeta` 的**反射构造**生效 |
| 3 | ⭐ 再次预览同一文件（命中缓存）：立即出缩略图、不再重新解析 | 验证 `meta.json` **反序列化**路径 |
| 4 | ⭐ 有未提取的预览缓存时点 X：出现「清空缓存」等待卡片 → 清完自动关窗 → **进程无残留** | 验证关窗链路与"有未答确认卡不许关窗"守卫 |
| 5 | ⭐ 单击缩略图 → 用系统默认查看器打开缓存图片（被系统策略阻断时按应用内兜底：`platform.FileOpener` 的两级协议，失败会如实报错而非谎报"已打开"） | 验证 `PreviewCacheStore.imageFileFor` + `platform.FileOpener` |

**B. 文本提取**

| # | 验收项 |
|---|---|
| 1 | 合并输出：多文件 → 单个 TXT，章号连续（中文数字 / 西文随 locale） |
| 2 | 单独输出：每来源一个 TXT，可含标题命名 |
| 3 | 内容区域限定：起止标记的**字面量与正则两种模式都要试**；模板变量 `{filename}`/`{datetime}`/`{date}`/`{time}` |
| 4 | 标题提取、编码自动修复、章节序号勾选 |

**C. 图片提取**

| # | 验收项 |
|---|---|
| 1 | 合并输出（单目录）/ 单独输出（每文件一目录） |
| 2 | 流式 与 高速内存（预加载）两种模式 |
| 3 | 名称提取、区域限定、批量重命名 |
| 4 | ZIP / CBZ 打包 + 归档后删源目录 |
| 5 | 广告过滤、Data URI、魔数扩展名纠正 |

**D. 列表 / 分组 / 撤销 / 拖拽**

| # | 验收项 |
|---|---|
| 1 | 文件列表、勾选、全选三态、位置序号、章节徽标 |
| 2 | 合并为组 / 拆分组 / 组名编辑 / 组参数独立与继承 |
| 3 | 撤销（移除 / 导入 / 拖拽 / 组合并 / 拆分 / 参数 / 清除 / 应用全局） |
| 4 | 拖拽：组内排序、跨组、出组、组行移动、两组合并 |
| 5 | 移除（含整组、空组自动销毁） |

**E. 界面与其它**

| # | 验收项 |
|---|---|
| 1 | **参数侧边栏**（右侧非模态）：打开/收起、确定提交、**折叠保留草稿**、目标切换确认、高速模式确认；命名配置保存/加载/删除 |
| 2 | HTML 源码查看器（左侧非模态）：语法高亮、查找栏、**鼠标选择 + 复制**、软换行与行号对齐 |
| 3 | 输入/输出目录选择、浏览按钮、完成后自动打开输出文件夹 |
| 4 | 在浏览器中打开 / 查看源码（CDP 原生查看器或回退源码页） |
| 5 | 进度条三态配色、取消提取、失败/跳过/警告可视化回写 |
| 6 | `logs/gui-state.json` 持久化与启动恢复（含 `locale`） |

### 10.6 元数据采集与合并（本次的实际做法）

1. **采集**（NIK 自带 agent，JVM 模式即可）：

   ```bat
   java -agentlib:native-image-agent=config-output-dir=target\agent-out ^
        --module-path <jfx-win jars> --add-modules javafx.controls,javafx.fxml ^
        -Duser.language=zh -Duser.country=CN -Dtest.mode=true ^
        -cp "target\classes;target\test-classes;<cp.txt>" com.mhtextractor.gui.acceptance.TestMain
   ```

   想覆盖更全就跑**两轮**，第二轮用 `config-merge-dir=`（把两轮并集留在同一目录）。
   若报 `Can't find dependent libraries`，改用全路径 `-agentpath=<NIK>\bin\native-image-agent.dll=...`。
   > ⚠️ `mvn clean` 会**删掉** `target\agent-out` 与 `target\cp.txt`，采集完要及时合并/备份。

2. **合并**（纯增量，绝不整文件重排）。本次脚本 `.claude-work/merge-agent-metadata.py` 的要点：
   - 用 **order-preserving** 解析（Python 的 `json.loads` 保留键序）；
   - 写回前做**字节级 round-trip 自检**：`json.dumps(obj, ensure_ascii=False, indent=1)` 再把 `\n`
     换成 `\r\n` 并补尾换行，必须与读进来的字节**完全一致**，否则拒绝写入（这条能挡住"重排整文件"）；
   - 只做"加类型 / 加成员 / 加资源"，同 type 按字段与方法并集合并；
   - 写回后核对 **`git diff --numstat` 的删除数为 0**。

   本次结果：反射类型 `194 → 210`、资源 `2918 → 2919`，`+128 / −0`。

### 10.7 本机环境陷阱（新增，按"会不会浪费时间"排序）

#### 10.7.1 🚨 项目树内的 native EXE 会让全部 Ikonli 图标变空心方框

- **症状**：图标全是空心方框（字体缺字形），但**文字、布局、字体、功能全部正常**。
- **实测规则（同一份 EXE 字节，只改文件位置）**：

  | EXE 所在位置 | 图标 |
  |---|---|
  | `D:\JAVA\mht-resource-extractor\dist\`（**旧默认输出目录**） | ❌ 全部空心方框 |
  | `D:\JAVA\mht-resource-extractor\xcheck-plain\` | ❌ |
  | `D:\JAVA\mht-resource-extractor\.claude-work\...` | ❌ |
  | `D:\JAVA\newmht\`、`C:\mht-probe-abs\`、`C:\xcheck.dotted\`、75 字符长路径 | ✅ 正常 |

- **已排除**：`-jar` vs `-cp`、`-H:Name`、路径长度、点目录、祖先 `target\` 目录、`-Djava.library.path`、
  `target\jfx-dlls`、EXE 旁边的 DLL 集合、ServiceLoader 提供者数量、字体资源是否嵌入、加载的原生模块集合、
  以及**构建配方**（NIK 静态 JavaFX 与 `D:\JAVA\MhtExtractorV2.2` 那种动态 JavaFX 的旧构建，
  放进项目树内**一样中招**）。
- **钉到的机制层面**：`Font.loadFont(InputStream, double)` 在树内返回 `null`、树外返回真字体；
  而 TTF 资源本身两处都可读（202,744 字节）。本机装有文件系统虚拟化过滤栈
  （`fltmc filters` 可见 `wcifs` / `bindflt` / `bfs`×13）与沙箱服务，**很可能是本机沙箱环境的副作用**。
  换一台普通机器跑一次"项目树内的 dist"即可反证。
- **本项目已做的处置**：`build-native.cmd` 的默认 `OUTPUT_DIR` 改为 `<仓库同级>\<仓库名>-dist`（树外），
  并在输出目录位于树内时打印醒目 `[WARN]`（只告警不阻断）。已测边界：默认 → 不告警；
  `<仓库>\dist`、`<仓库>` → 告警；同名前缀的兄弟目录（`mht-resource-extractor-dist`）→ 不误报。

#### 10.7.2 PowerShell 会把 `-Dfoo=bar` 参数吃掉

现象：`java ... -Duser.language=zh` 变成 `ClassNotFoundException: /language=zh`；
或 `-Djava.library.path=...` 被解析成"要写到 `target\.library.path=target` 这个目录"（见 §8.5）。

- **可靠做法**：把带 `-D` 的命令写进一个 `.cmd` / `.ps1` 文件再执行，
  或用 `Start-Process -ArgumentList '<一整条预引号字符串>'`。本次所有 JVM 侧命令都改成经 `.cmd` 驱动后一次通过。
- 同理**不要**给 `.ps1` 里的自动变量 `$args` 赋值（会静默毁掉整个脚本作用域，症状是 0 字节输出）。

#### 10.7.3 用脚本改文件时，中文锚点必须按 UTF-8 读，不能按 Latin-1 读

- 为了"保字节"用 Latin-1（byte-preserving）做替换：**匹配 ASCII 锚点没问题，匹配中文一律匹配不到**。
  正确做法：`path.read_bytes().decode("utf-8")` → 替换 → `.encode("utf-8")` 写回。
  `.cmd` / `.md` 都是 **UTF-8 无 BOM + CRLF**，写完必须复查 `CRLF / BareLF / BOM` 三项。
- 另：把含中文路径的辅助脚本用 `Out-File -Encoding ascii` 落盘会把路径写成 `?`（本次真踩到）——
  辅助脚本一律用 UTF-8 写。
- 🚨 仍然**禁止**用 PowerShell 的字符串替换改文档（反引号是转义符，会把整篇改坏，见 `CLAUDE.md`）。

#### 10.7.4 截 GUI 窗口的图：不置顶就会截到前台浏览器

`GetWindowRect` + `CopyFromScreen` 截的是"屏幕该矩形区域里**当前可见**的东西"。必须先把目标窗口
`SetWindowPos(hwnd, HWND_TOPMOST, …, SWP_NOMOVE|SWP_NOSIZE)` 再 `SetForegroundWindow`，
否则截到的是浏览器（本次第一次截图截到的就是 DSH 界面）。

#### 10.7.5 其它一次性坑

- `mvn clean` 会删掉 `target\cp.txt` 与 `target\agent-out`；依赖 classpath 的脚本要能自己重建
  （`mvnw.cmd -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt`）。
- 交付目录里出现 `logs/` 说明有人在那个目录直接跑过应用；打包前清掉。
- 本机 `git commit` 未配身份 → 需内联 `-c user.name=... -c user.email=...`。
- 判断"构建是否真跑过"：看 `logs/native-build-<stamp>.report.json` 的 `status/steps` 与
  `logs/native-build-<stamp>.log` 里的 `[STEP k/n] END … exit=`，不要只看退出码。
- 统计测试数不要读执行方日志，直接聚合 `target/surefire-reports/*.txt`
  （2026-10-05 复核的独立聚合：**768 tests / 0 failures**（75 个类汇总）+ `headless-layer-b` **68/0**）。

### 10.8 🚨 非 ASCII（中文）路径：dynamic 配方会**启动失败** —— 已随包启动器

> **2026-10-05 实测复验**：把 dynamic 产物复制到 `D:\中文目录实验\提取器\` 直接运行 → 进程立即退出，stderr 为
> `Exception in thread "main" java.lang.RuntimeException: No toolkit found`（`com.sun.javafx.tk.Toolkit.getToolkit` → `PlatformImpl.startup`）。
> 与本节根因**完全一致**（JavaFX 原生库 `glass/prism_*.dll` 从非 ASCII 的 EXE 目录加载失败），故该限制**只与 JavaFX 有关**。

**症状**：把 dynamic 产物放进中文目录，双击 exe → **窗口一闪即逝**，stderr 里是：

```
Graphics Device initialization failed for :  d3d, sw
Error initializing QuantumRenderer: no suitable pipeline found
java.lang.UnsatisfiedLinkError: Can't load library: prism_d3d
   | java.library.path = [C:\中文测试-目录\提取器 V3, C:\windows\system32, ...]
```

**四格对照实验（同一份 EXE 字节，只改放置位置）** —— 这是定位的关键：

| EXE 位置 | 工作目录 | 结果 |
|---|---|---|
| `C:\中文测试-目录\提取器 V3`（含中文**和**空格） | 中文 | ❌ 失败 |
| `C:\中文测试无空格\提取器V3`（含中文，**无空格**） | ASCII | ❌ 失败 |
| `C:\mht space test\app`（**纯 ASCII + 空格**） | ASCII | ✅ 正常 |
| `D:\JAVA\newmht`（纯 ASCII） | 任意 | ✅ 正常 |

⇒ **只看 EXE 自己的路径里有没有非 ASCII；空格无害；工作目录、输入/输出目录是中文都无所谓。**

**根因**：dynamic 配方下 JavaFX 的原生库是**运行期用 `System.loadLibrary` 按名字加载**的，而
`java.library.path` 的**第一项就是 EXE 所在目录**。GraalVM 的 SubstrateVM 处理不了这个列表里的非 ASCII
目录（构建期/运行期的路径 → 宽字符转换有缺陷），于是 `prism_d3d` / `prism_sw` 都加载不了 →
Prism 两条流水线全挂 → 启动即退。**static 配方把 JavaFX 静态链接进 EXE，运行期根本不做这次加载，
所以完全免疫**（同一份中文目录实测三次全部正常启动）。

**三个补救方案（都实测过）**：

| 方案 | 做法 | 代价 |
|---|---|---|
| **① 随包启动器（已内置）** | 双击 **`启动.cmd`**（而不是 exe）。它把 DLL 复制到纯 ASCII 目录 `%ProgramData%\MhtExtractor\native-dlls`（失败则退到 `%PUBLIC%`），再用 `-Djava.library.path=<该目录>` 启动 | dist 多 2 个 2 KB 文件；首次运行向 `%ProgramData%` 写约 4.5 MB（之后 `xcopy /D` 只补新增/变更） |
| **② 换 static 配方** | `set NATIVE_RECIPE=static` → 98 MB，双击 exe 即可，**对路径无任何要求** | +28 MB |
| **③ 放进纯 ASCII 路径** | 例如 `D:\MhtExtractor`；不改配方不加文件 | 用户得记住这条规矩 |

**已排除的死路（别再试）**：

- **8.3 短路径**：`%~sI` 对中文目录给出的是 `C:\中文测~1\提取器~1`，**仍然是非 ASCII**。
- **`-Djava.library.path=.`**：会被规范化回**中文绝对路径**，一样失败。
- **`subst M: <中文目录>` 再跑 `M:\MhtExtractor.exe`**：实测仍然 EXITED。
- **`JAVA_TOOL_OPTIONS`**：native image 不认这个变量。
- **路径含空格**：不是问题（见上表第三行），别在这上面浪费时间。

**构建脚本已内置方案 ①**：`build-native.cmd` 第 6 步在 dynamic 配方下调用
`tools\launcher\make-launcher.ps1`，生成 `启动.cmd` 与 ASCII 名的孪生 `Launch.cmd`
（防老解压工具把中文名弄乱）。⚠️ 中文文件名**必须**由 PowerShell 创建 —— `cmd.exe` 会把 `build-native.cmd`
里的 UTF-8 字节当 ANSI 传给 Win32 API，直接在 `.cmd` 里 `copy` 成中文名会得到**乱码文件名**（实测踩过）。
static 配方不生成启动器（不需要）。

### 10.9 把活儿分给 Claude Code CLI（本次经验，省 token）

本次有两块**自包含、需要大量迭代**的活儿委派给了 `claude.exe`
（`--print --verbose --dangerously-skip-permissions --settings <白名单> --max-budget-usd N`），
提示词走 stdin。适合复制这个模式：

- **委派 1（成功）**：让 native 镜像里的 GUI 自动化真的跑起来。它定位到
  "`FileNotFoundException` 被吞 → 静默 `exit 0`"，并把结果通道改成自足，最终 zh/en 各 31/0。
- **委派 2（超预算未完成）**：图标问题的机制定位。它建了一堆探针（ServiceLoader 探针三种类路径顺序、
  `FontProbe` 两种配方）**并成功证伪了 ServiceLoader 假说**，但 $10 预算耗尽在机制上；
  真正的答案（运行位置）是用"同一 EXE 换位置"的对照实验拿到的。

**结论**：委派提示词里要写清"现象 + 已排除项 + 可用手段 + 判定纪律（核对 mtime / 带控制组 / 不许假绿）"，
并**给一个明确预算**；纯机制探索类任务要预期它可能只交付"排除清单"。

### 10.10 本章一页速查

| 要做什么 | 命令 / 判据 |
|---|---|
| 预检 | `check-env.cmd` → exit 0 |
| 构建 | `build-native.cmd`（输出默认在项目树外）→ exit 0 |
| 选配方 | 默认 `dynamic`（≈70 MB）；`set NATIVE_RECIPE=static` 换 NIK 静态（≈98 MB） |
| **放在中文目录下** | dynamic：**双击 `启动.cmd`**（直接双击 exe 会失败，§10.8）；static：双击 `MhtExtractor.exe` 就行 |
| 交付目录 | dynamic：22 个文件 / ~70 MB，**含** 7 个 JavaFX DLL；static：15 个文件 / ~98 MB，**不含**。两者都**不含** `logs/` |
| 能不能启动 | 从无关 cwd 启动 → 标题 `MHT 资源提取器`；关窗 exit 0 无残留 |
| 图标对不对 | **必须**从项目树外运行；症状是"全空心方框"（§10.7.1） |
| 能不能真提取 | native 跑 `BaselineCases` → **42 passed / 0 failed**（§10.5.2） |
| 元数据够不够 | FXML / Jackson / 资源三处不炸；`git diff --numstat` 纯增量；过时项用 `tools/native/audit-native-metadata.ps1` 审计 |
| 功能有没有坏 | 按 **§10.5.6 人工验收清单**逐项过（A 组 ⭐ 必做） |
| 回归门槛 | `mvn test`：**949/0** + `headless-layer-b` **73/0**；产物基线 **44** 份；架构不变式 **17** 条；GUI 自动化 zh/en 各 34/0（**以下为 2026-10-08 最新值**；§10.11 的历史明细中 768/68 等为当时基线） |

### 10.11 当前回归基线（2026-10-05 独立复核）

> 打包前拿这张表对照；**任何一项变红都先查清再打包**。项目版本 **3.0.0**（以 `pom.xml` 为唯一来源）。

| 项 | 命令 / 手段 | 实测 |
|---|---|---|
| 编译 + 全量单测 | `mvnw.cmd clean test`（不加 `-q`） | 默认 **768 / 0**、`headless-layer-b` **68 / 0**、`BUILD SUCCESS` |
| 独立聚合（不读执行方日志） | 聚合 `target/surefire-reports/*.txt` | **768 passed / 0 failed**（75 个类汇总） |
| 产物黄金基线 | `ProductBaselineTest` | **42 / 0**（zh+en × 21 例 × 2 遍确定性自检） |
| 结构不变式 | `ArchitectureTest` | **17 / 0**；`MainController` **1654** 行 / 上限 **2240**；`test-support` 标记 **0**；GUI 直写 **0** / 间接写 **0** |
| GUI 自动化 | §10.5.5 入口，zh 与 en 各跑一次 | **各 34 passed / 0 failed**；`logs/test-results.txt` 的 **mtime** 与本轮一致 |
| UI / 交互探针 | `tools\probes\run-probe.ps1 <Probe> [-Locale …]` | ContextMenu **121/0**、SidePanel **153/0**（+E1.2 运行时观测 6 条）、HtmlViewer **163/0**、ParamDraft **14/0**、AppCard **5/0**、NotificationAcceptance / BusyCloseProbe `ALL CHECKS PASSED`、PendingConfirmClose `PROBE DONE`；UiProbe 截断 **0**（en 屏上中文 **0**） |
| 生产日志键 | 扫描 `I18n.get` / `I18n.msg` 字面量 | `log.*` **236 键 / 247 调用点**逐键不变；全部键调用点 **442**（E4.1 起 7 个业务警告键改产 `ExtractWarning` 码值） |
| i18n bundle | 两份 properties 对账 | **547 / 547**，键集与占位符集合一致 |
| native 构建 | `build-native.cmd`（默认 dynamic） | exit 0；report `status=OK`、全部步骤 exit 0（含 `[STEP SMOKE]`）；dist **22 个文件** |
| native 产物基线 | 重建 `NativeBaselineProbe` 后运行（§10.5.2） | **42 passed / 0 failed** |
| 第三方依赖 | `pom.xml` | 自基线以来**零改动**（`noNewThirdPartyDependencies` 仍绿） |

**已知不在本表覆盖范围内的项**（详见根目录《遗留目标与未完成事项.md》）：
截图链（JVM vs native 像素对比）、真实 OS 级鼠标交互、`NATIVE_RECIPE=static` 配方的最近一轮复跑、
以及"英文运行日志中文字符数 = 0"的日志级测量。

### 10.12 GUI 随包后端（P4-17）

先用 `NATIVE_TARGET=headless` 构建 `MhtExtractorBackend.exe`，再构建 JavaFX 发行目录。
无头目标应选无 JavaFX 的 Oracle GraalVM；NIK Full 的内置 JavaFX feature 会向分析报告加入
JavaFX 类型，不能通过无头产物的零 JavaFX 断言。

JavaFX dist 步骤从 `BACKEND_DIST_DIR` 复制后端 exe 到 GUI exe 同目录。
该变量默认 `H:\TEST\mht\p4-3-headless-dist`；发布时应显式指向本次、同一构建标识的无头产物目录。
缺少后端时构建会给出警告，GUI 启动后显示后端不可用并保留进程内功能。
后端的 metadata/reports 是验收旁证，不必复制进 JavaFX 发行目录。

`启动.cmd` / `Launch.cmd` 先进入发行目录再启动 GUI；GUI 自行拉起后端、独占 stdio 管道并完成握手，
启动脚本不另起脱管后端。`-Dmht.backend.executable=<path>` 可覆盖伴随 exe 路径。
协议与确认生命周期见 `src/main/java/com/mhtextractor/backend/IPC.md`。

native 卡片冒烟产物目录由 `NATIVE_SMOKE_DIR` 覆盖，默认 `H:\TEST\mht\native-card-smoke`。
本机验证时显式设置 `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=H:\TEST\temp`，并将 `TEMP` / `TMP`、
`OUTPUT_DIR`、`LOG_DIR` 和 `NATIVE_SMOKE_DIR` 指向 H 盘。
