# 基于 GraalVM 的 MHT 资源提取器项目打包工作流程

> **目标**: 将 JavaFX 25 + Maven 项目编译为 GraalVM Native Image 绿色免安装 Windows EXE。
> **适用场景**: 从 Git 拉取代码后，在任何 Windows 设备上完成打包。
> **最后更新**: 2026-05-31 | **已验证 JDK 版本**: Liberica NIK 25.0.3 + Oracle GraalVM 25.0.2+10.1

---

## 一、项目概况

| 项目 | 详情 |
|------|------|
| 构建工具 | Maven (pom.xml) |
| 编译 JDK | Liberica NIK 25.0.3（含 JavaFX + native-image，统一编译和构建） |
| Native Image JDK | GraalVM 25.0.2+ 或 Liberica NIK 25.0.3 |
| JavaFX | 25.0.3 (win classifier)，无 module-info.java，classpath 模式 |
| 主类 | `com.mhtextractor.App` |
| 关键依赖 | Jackson, Ikonli, AtlantaFX, Jakarta Mail, Jsoup, Logback, Lombok 1.18.44 |
| 打包路径 | pom.xml → fat JAR → native-image → EXE + DLLs |

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

### 3.1 JDK 21（编译用）

| 检查项 | 验证命令 |
|--------|----------|
| 是否安装 | `<JDK21_HOME>/bin/java -version` 应输出 `21.x.x` |
| 是否含 JavaFX | `<JDK21_HOME>/bin/java --list-modules \| grep javafx` 应有输出（Liberica Full 或 Azul Zulu FX） |

> **推荐**: 使用 Liberica Full JDK 21 或任何包含 JavaFX 的 JDK 21 发行版。普通 JDK 21 不含 JavaFX，编译可通过但 Tracing Agent 阶段无法运行。

**如果未安装**: 从 [BellSoft Liberica](https://bell-sw.com/pages/downloads/) 下载 "Full JDK 21" Windows x64 版本，安装后记录其 `bin` 目录的上级路径（即 `JAVA_HOME`）。

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
| 内存 | 16 GB | 32 GB（native-image 使用 `-J-Xmx8G`） |
| 磁盘 | 2 GB 空闲 | 5 GB（含依赖缓存） |
| CPU | 4 核 | 8 核以上（构建更快） |

### 3.7 杀毒软件

构建时建议**临时禁用实时扫描**或将项目目录加入排除列表，否则 native-image 构建可能从几十秒膨胀到数十分钟。

### 3.8 一键检查脚本

将以下内容保存为 `check-env.sh`（需要 Git Bash 或 WSL），在新设备上运行以快速诊断环境：

```bash
#!/bin/bash
echo "=== 环境检查 ==="
errors=0

# JDK 21
echo -n "JDK 21: "
if java21=$(command -v java 2>/dev/null) && "$java21" -version 2>&1 | grep -q "21\."; then
    echo "OK ($(which java))"
else
    echo "MISSING - 需要 JDK 21（含 JavaFX）用于编译"
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
    "name": "com.mhtextractor.gui.util.JavaFxUtil$SmoothProgressProperty",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true,
    "allDeclaredFields": true
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

## 五、构建命令

### 5.1 完整构建流程

Native Image 构建需要 MSVC 链接器环境。**在同一个终端中**按顺序执行：

```bash
# ========== Step 0: 设置路径变量（按实际环境修改） ==========
JDK21_HOME=<你的 JDK 21 路径>
GRAALVM_HOME=<你的 GraalVM JDK 25+ 路径>
PROJECT_DIR=<项目根目录>

# ========== Step 1: 初始化 MSVC 环境（必须最先执行） ==========
# 找到 vcvars64.bat 的位置（通常在 VS 安装目录下）
# 示例路径: "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
call "<VS安装目录>\VC\Auxiliary\Build\vcvars64.bat"

# ========== Step 2: 编译（JDK 21） ==========
export JAVA_HOME=$JDK21_HOME
export PATH=$JAVA_HOME/bin:$PATH
cd $PROJECT_DIR
mvn clean package -DskipTests

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
  -jar target/mhtExtractor-*.jar \
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
  -J-Xmx8G \
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
| `-J-Xmx8G` | Native Image 构建堆上限 | 内存充足时保持 8G |
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

## 六、生成 `build-native.cmd` 一键构建脚本

以下脚本内容可以通过 Claude Code CLI 在新设备上直接写入项目根目录：

```batch
@echo off
REM ============================================================
REM MHT资源批量提取器 - GraalVM Native Image 一键构建脚本
REM 使用方法: 根据本机环境修改下方路径变量后运行
REM ============================================================
setlocal enabledelayedexpansion

REM ====== 请根据本机环境修改以下路径 ======
set "JDK21_HOME=<你的 JDK 21 路径>"
set "GRAALVM_HOME=<你的 GraalVM JDK 25+ 路径>"
set "MAVEN_HOME=<你的 Maven 路径>"
REM VS vcvars64.bat 路径（通常在 VS 安装目录下）
set "VCVARS64=<你的 VS 安装目录>\VC\Auxiliary\Build\vcvars64.bat"

REM ====== 验证环境 ======
if not exist "%JDK21_HOME%\bin\java.exe" (
    echo [ERROR] JDK 21 未找到: %JDK21_HOME%
    echo 请修改脚本中的 JDK21_HOME 变量
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

echo === Step 1: 初始化 MSVC 环境 ===
call "%VCVARS64%" >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] MSVC 环境初始化失败
    pause & exit /b 1
)
echo MSVC 环境就绪

echo === Step 2: 编译项目 (JDK 21) ===
set JAVA_HOME=%JDK21_HOME%
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
    "%GRAALVM_HOME%\bin\jar" xf javafx-graphics-*-win.jar %%f 2>nul
    if exist %%f move /Y %%f ..\jfx-dlls\ >nul 2>&1
)
cd ..\..

echo === Step 4: Native Image 编译 (GraalVM JDK 25) ===
set JAVA_HOME=%GRAALVM_HOME%
set PATH=%JAVA_HOME%\bin;%PATH%

"%GRAALVM_HOME%\bin\native-image.cmd" ^
  -jar target/mhtExtractor-*.jar ^
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
  -J-Xmx8G ^
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

输出示例: `2.2`

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
| `Fatal error compiling: TypeTag :: UNKNOWN` | 用 JDK 25 编译 Lombok 注解的源码 | 切换为 JDK 21 编译 |
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

## 九、给 Claude Code CLI 的提示

如果在新的会话或新设备上使用 Claude Code CLI 打包此项目：

1. **先读本文档**，了解整体流程
2. **运行环境检查**（第三章），确认 Liberica NIK 25.0.3、GraalVM JDK 25.0.2、Maven、MSVC 都已就绪
3. **检查并生成配置文件**（第四章），`src/main/resources/META-INF/native-image/com.mhtextractor/` 下的 7 个配置文件都需要存在
4. **编译用 NIK 25.0.3，native-image 用 GraalVM 25.0.2**，当前不宜混用
5. **所有工具路径必须纯 ASCII**，含中文的路径会导致模块加载失败（`Module org.graalvm.nativeimage.builder not found`）
6. **`--initialize-at-run-time` 参数一个都不能少**，缺失任何一个 JavaFX 子包都会崩溃
7. **DLL 必须放在 EXE 同目录**，包括 JNI 桥接库 `awt.dll`/`java.dll`/`jvm.dll`
8. **构建后用 `editbin /SUBSYSTEM:WINDOWS`** 隐藏控制台窗口
9. **遇到 `Failed to delete target\lib`**: 跳过 clean，用 `mvn package` 而非 `mvn clean package`
10. **遇到新错误**: 搜索本文档第八章，或在命令行运行 EXE 获取具体堆栈信息

### 自动化建议

Claude Code CLI 可以通过以下步骤在新设备上自动完成全部打包：

1. 读取本文档了解流程
2. 搜索本机环境（`where java`、`where mvn`、检查 VS 安装）
3. 如果没有找到，提示用户提供路径或自行安装
4. 检查 `src/main/resources/META-INF/native-image/` 目录是否存在
5. 如果配置文件不存在，按第四章模板创建
6. 按第五章命令执行构建
7. 如果构建失败，根据错误信息对照第八章排查
