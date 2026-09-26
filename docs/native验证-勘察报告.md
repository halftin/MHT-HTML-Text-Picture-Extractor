# NIK native 验证 — 勘察报告（阻塞）

> 日期：2026-09-26
> 执行者：DSH（独立勘察，未修改任何生产代码）
> 结论：**本机无法完成 native 构建**，阻塞原因是 MSVC 工具链缺失（硬依赖）。
> 同时发现**第二个独立缺陷**：native-image 元数据已过期，缺 `PreviewCacheMeta`。

---

## 一、阻塞项（硬性，必须先解决）

### 1.1 缺 MSVC 工具链 —— 已实证

实测（最小 HelloWorld 探针）：

```
$ native-image.cmd -cp . HelloNative --no-fallback -H:Name=hello-native
Error: Failed to find 'vswhere.exe' for locating Visual Studio installations.
Please make sure that Visual Studio 2022 version 17.6.0 or later is installed on your system.

native-image exit=20
```

本机勘察结果（穷尽式查找）：

| 检查项 | 结果 |
|---|---|
| `X:\` 盘 | ❌ 不存在 |
| `C:\Program Files (x86)\Microsoft Visual Studio` | ❌ 不存在 |
| `C:\Program Files\Microsoft Visual Studio` | ❌ 不存在 |
| `C:\BuildTools` / `D:\VS` / `D:\BuildTools` | ❌ 不存在 |
| `C:\Program Files (x86)\Windows Kits\10`（Windows SDK） | ❌ 不存在 |
| `vcvars64.bat`（递归搜索 C:、D:、D:\install） | ❌ 未找到 |
| `cl.exe` / `link.exe`（PATH） | ❌ 未找到 |
| 注册表 `VisualStudio\SxS\VS7`（含 WOW6432Node） | ❌ 不存在 |
| `vswhere.exe`（VS 官方定位器） | ❌ 不存在 |

**为什么绕不开**：GraalVM `native-image` 在 Windows 上**只支持 MSVC**，不支持 MinGW/GCC
（[GraalVM Discussion #9776](https://github.com/oracle/graal/discussions/9776)、
[Spring Boot 参考文档](https://docs.spring.io/spring-boot/docs/3.2.10/reference/pdf/spring-boot-reference.pdf#92#56)）。
项目用 `--no-fallback`，因此必须走完 C 编译+链接。

### 1.2 本机已具备的部分

| 组件 | 状态 |
|---|---|
| NIK 25.0.3 | ✅ `D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3` |
| **NIK 自带 native-image** | ✅ **`native-image 25.0.3`（Liberica-NIK-25.0.3-2）实测可用** —— **无需单独安装 GraalVM** |
| Maven | ✅ wrapper `apache-maven-3.9.16`（`mvn` 不在 PATH） |
| 内存 | ⚠️ 总 15.3 GB / 可用 10.1 GB（脚本要求 `-J-Xmx8G`，会吃紧但可行） |
| 历史 native 产物 | `D:\install\MhtExtractorV2.2\MhtExtractor.exe`（64.6 MB，2026-07-05）—— 说明**曾经能构建**，但早于 2.5/2.6，且当时工具链现在已不在本机 |

### 1.3 解除阻塞需要你做的事

安装 **Visual Studio 2022 Build Tools（17.6 或更高）**，并在安装器中勾选：

- **「使用 C++ 的桌面开发」工作负载**（Desktop development with C++）
- 其下的 **MSVC v143 - VS 2022 C++ x64/x86 生成工具**
- **Windows 10/11 SDK**

安装后 `vswhere.exe` 会出现在
`C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe`，
`native-image` 才能定位工具链。安装约 2–4 GB，耗时 10–30 分钟。

> 装好后告诉我，我可以立即继续（改脚本 → 全量构建 → 冒烟验证）。

---

## 二、第二个缺陷（与 MSVC 无关，值得单独修）

### 2.1 native-image 元数据已过期，缺少缓存相关类型

实测时间戳对比：

| 文件 | 修改时间 |
|---|---|
| `reachability-metadata.json` | **2026-09-07 19:42** |
| `reflect-config.json` / `resource-config.json` | 2026-09-06 19:10 |
| `jni-config.json` / `proxy-config.json` / `serialization-config.json` / `native-image.properties` | 2026-08-23 19:39 |
| **`cache` 包（会话 8/9 新增）** | **2026-09-25 20:21 起** |

**6 个元数据文件全部没有任何 `com.mhtextractor.cache` / `PreviewCacheMeta` 条目。**
现有 `com.mhtextractor.*` 条目只有：`App`、`config.AppConfig`、`config.GuiState`、
`gui.component.LogTextArea`、`gui.controller.MainController`、`gui.controller.ParamDialogController`、
`logging.TextAreaAppender`。

**为什么要紧**：`PreviewCacheStore` 用 Jackson 读写 `PreviewCacheMeta`（record，含嵌套
`Source` / `ImageEntry`）。Jackson 靠反射构造 record；native-image 下未注册反射的 record
**会在运行时抛异常**，而当前错误处理把它记为「跳过缩略图」或「读取失败」——
即 native 版可能出现"预览缓存静默不可用"。这是**只有 native 版才会暴露**的缺陷。

**顺带发现**：`reachability-metadata.json` 里 `AppConfig` 有**重复条目**（同一 type 出现两次，
字段/方法列表略异），说明历史上的 tracing agent 输出被追加而非合并/去重。

### 2.2 修法（两条路，建议 A）

**A. 重跑 tracing agent 采集（推荐，最完整）**
NIK 自带 `bin/native-image-agent.dll`。步骤：
1. `mvn package -DskipTests` 出 jar
2. 直接 java 启动，带
   `-agentlib:native-image-agent=config-output-dir=<目标目录>`
   并跑 `-Dtest.mode=true`（覆盖预览/提取/缓存路径）+ 必要时 `-Dagent.exit.delay` 的
   `exerciseAllFeatures()`
3. 把采集产物**合并**进 `src/main/resources/META-INF/native-image/com.mhtextractor/`
   （合并时顺便**去重** AppConfig 的重复条目）
4. 人工检查 `PreviewCacheMeta` / `Source` / `ImageEntry` 已被采集（若 test.mode 未覆盖缓存读，
   需确认或手工补）

**B. 手工补条目（快速兜底）**
照 `GuiState` 的既有格式，为以下类型补 `<init>` 反射条目：
`com.mhtextractor.cache.PreviewCacheMeta`、`PreviewCacheMeta$Source`、`PreviewCacheMeta$ImageEntry`。
风险：需要手工判断 Jackson 反序列化 record 所需的完整成员集合，容易漏。

> **建议在修好 MSVC 之后、与首次 native 构建同批进行**，这样能一次验证到底。
> 若你希望现在就修，我可以先做 B（风险可控），等你装好工具链再用 A 复核。

---

## 三、`build-native.cmd` 需要修正的 4 处硬编码

会话 0 与本报告共同确认：脚本内 4 个 `X:\` 路径**全部不可用**（X: 盘不存在）。

| 脚本行 | 当前值 | 应改为 |
|---|---|---|
| `:39` `BUILD_JAVA_HOME` | `X:\bellsoft-liberica-vm-full-openjdk25-25.0.3` | `D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3` |
| `:42` `GRAALVM_HOME` | `X:\graalvm-jdk-25_windows-x64_bin\graalvm-jdk-25.0.2+10.1` | **同 NIK 目录**（NIK 自带 native-image，无需独立 GraalVM） |
| `:45` `MAVEN_HOME` | `X:\apache-maven-3.9.9` | Maven wrapper：`C:\Users\Admin\.m2\wrapper\dists\apache-maven-3.9.16-bin\5grr65jo27hi51sujmtcldfovl\apache-maven-3.9.16` |
| `:80` `OUTPUT_DIR` | `X:\123\MHTtoEX` | 例如 `D:\install\MhtExtractorV<版本>`（建议在项目内或用户指定目录） |

**另建议**：把 `:86` 的 `-J-Xmx8G` 下调（本机总内存 15.3 GB / 可用 10.1 GB），
例如 `-J-Xmx6G`，降低 OOM 风险。

> ⚠️ **我没有改这个脚本**。理由：改完后无法在本机验证（缺 MSVC），
> 交付一个**未经任何执行验证**的构建脚本比留着已知不可用的原版更危险。
> 装好工具链后我会改一处、验一处。

---

## 四、当前可交付的验证（不依赖 native 构建）

native 构建被阻塞，但**大部分 native 相关风险可以提前排查**：

| 可做项 | 状态 |
|---|---|
| 元数据缺口静态排查（已做，见 §2） | ✅ 发现缺 `PreviewCacheMeta` |
| `mvn package` 出 jar（native 的输入） | ✅ 已验证通过（198/263/274 测试基线正常） |
| **在 JVM 上跑 `test.mode` 全量回归**（覆盖缓存路径的逻辑正确性） | ✅ 已做：29 通过 / 0 失败 |
| **验证缓存功能在「无反射元数据」假设下的行为** | ✅ 已做：JVM 下正常（native 才会暴露差异） |
| 纯逻辑单测全量 | ✅ **274 测试 / 20 类 / 0 失败** |
| native 构建本身 | ❌ **阻塞：缺 MSVC** |
| native EXE 冒烟（启动/导入/预览/提取/退出） | ❌ 阻塞于上一条 |

---

## 五、解除阻塞后的执行计划（装好 VS Build Tools 后）

1. **修 `build-native.cmd`** 的 4 处路径 + 下调 `-Xmx`（改一处验一处）
2. **重跑 tracing agent** 采集元数据，合并并去重，确认 `PreviewCacheMeta` 入库
3. `mvn package`（NIK 25）→ `build-native.cmd` 全流程
4. **冒烟验证**（逐项记录证据）：
   - 双击 EXE 启动、无控制台窗口（`editbin` 生效）
   - 导入样例 MHT → 预览 → **确认缓存目录真实生成**（用
     `.claude-work/inspect-cache.ps1` 检查）
   - 提取 → 确认缓存命中并复用（比对输出字节）
   - 退出 → 确认弹窗与缓存清空
   - **重点**：确认 native 下 Jackson 能正常读写 `meta.json`（即 §2 的缺陷已修）
5. 产出便携包（EXE + JavaFX DLL），记录版本号与体积

---

## 六、给用户的摘要（一句话）

**native 构建在这台机器上做不了，缺的是 Visual Studio 2022 C++ 生成工具（MSVC），
这是 GraalVM 在 Windows 上的硬依赖；NIK 自带的 native-image 本身可用，且我还发现
native 元数据过期（缺缓存类型的反射配置），这两个都修好后才能出包。**
