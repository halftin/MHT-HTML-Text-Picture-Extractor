**中文** · [English](README.en.md)

# MHT/HTML 资源提取器 3.0

> 把 `.mht` / `.mhtml` / `.html` / `.htm`（含 HTML 侧车文件夹）**批量提取为纯文本与图片**的 Windows 桌面工具。
> 发行名 **MHTExtractor3.0**（源码版本 `3.0.0`）· 界面语言 **中文 / English**

---

## 一、能做什么

| 能力 | 说明 |
|---|---|
| **批量导入** | 一次拖入或选择多个 `.mht`/`.mhtml`/`.html`/`.htm`，或**整个文件夹**；HTML + 同名侧车文件夹（`xxx.files/`）算一份来源 |
| **分组管理** | 合并为组 / 取消分组 / 重命名（组名唯一化）/ 折叠展开；组与条目共用一套参数语义 |
| **参数三层** | **全局 / 分组 / 单条目**三层参数；支持撤销、复制粘贴、命名配置保存与套用 |
| **文本提取** | 解析 MIME/HTML → 纯文本；自动取标题；章节命名支持中文「第一章」/ 西文「Chapter 1」 |
| **图片提取** | CID/Location 去重、Data URI、广告过滤、**区域限定**（按起止标记只取需要的区域）、魔数纠正扩展名 |
| **预览** | 「查看预览效果」真实渲染文本 + 缩略图；缩略图单击可用系统查看器打开；会话级预览缓存（命中即跳过解析） |
| **输出** | 文件名模板变量 `{filename}`（完整文件名）/ `{basename}`（去扩展名）/ `{datetime}` / `{date}` / `{time}`；文本可合并或分开；图片可合并/单独，可打包 ZIP/CBZ |
| **界面** | 中文/英文双语；非模态参数侧边栏；应用内提醒卡片（错误/警告/确认/等待）；日志面板 |
| **单实例** | 同时只允许一个实例，重复启动会提示并自动退出 |
| **无头后端（高级）** | 随包 `Backend\MhtExtractorBackend.exe` 提供命令行与 `--serve` 模式，便于脚本化；GUI 不需要它 |

---

## 二、系统要求

| 项 | 要求 |
|---|---|
| 操作系统 | **Windows 10 / 11 x64** |
| 运行时依赖 | **无需安装** Java / .NET / VC++ 运行库 —— 发行包已自带 JVM 与所需 DLL |
| 内存 | **动态上限：机器物理内存的 50%**（见下文「内存行为」） |
| 磁盘 | 预览缓存写在系统临时目录；输出目录由你选择 |
| **路径** | ⚠️ **程序所在文件夹的完整路径必须全部是 ASCII 字符**（见「已知限制」） |

### 内存行为（可自行调整）

- 发行为**动态上限** `-XX:MaximumHeapSizePercent=50`：16 GB 机器约 **7.7 GB**、8 GB 机器约 **3.8 GB**，随机器自适应。
  （实测：15.3 GB 机器上上限 = 7849 MB ≈ 50%；默认值是 80%，本发行版已显式降到 50%。）
- **想限得更小**：启动时附加运行期参数（实测有效）

```
MhtExtractor.exe -XX:MaxHeapSize=2g
```

- 常用做法：右键 `MhtExtractor.exe` → 发送到 → 桌面快捷方式，在「目标」末尾追加上述参数。

---

## 三、下载与目录结构

解压发行压缩包，得到**一个 ASCII 目录**（**请勿重命名为中文/日文等非 ASCII 名称**）：

```
MHT-HTML-Extractor3.0/            <-- 目录名必须保持 ASCII
├── MhtExtractor.exe              <-- 双击它启动（GUI，68.5 MB）
├── glass.dll  prism_common.dll  prism_d3d.dll  prism_sw.dll
├── decora_sse.dll  javafx_font.dll  javafx_iio.dll          <-- JavaFX 原生 DLL
├── jvm.dll  java.dll  awt.dll  javajpeg.dll  lcms.dll
├── javaaccessbridge.dll  jawt.dll                           <-- JVM/图像 原生库
├── msvcp140*.dll  vcruntime140*.dll                         <-- VC++ 运行库（随包，免安装）
├── Backend/
│   └── MhtExtractorBackend.exe    <-- 无头后端（高级用；GUI 不需要）
├── README.md                      <-- 本文件
└── LICENSE                        <-- 木兰宽松许可证 v2（Mulan PSL v2）
```

> 本发行版**不提供** `启动.cmd` / `Launch.cmd` 之类批处理启动器 —— 直接双击 `MhtExtractor.exe`，
> 并遵守下面的**纯 ASCII 路径**要求。

---

## 四、已知限制：程序所在目录必须是纯 ASCII 路径

**现象**：把程序放在含中文/日文等非 ASCII 字符的目录里（例如 `D:\我的工具\提取器\`），双击 `MhtExtractor.exe` 会**启动失败**。

**实测证据**（同一份产物，只改所在目录）：

| 所在目录 | 结果 |
|---|---|
| `H:\TEST\mht\e2e-ascii\MHT-HTML-Extractor3.0\`（全 ASCII） | ✅ 进程存活，窗口标题「MHT 资源提取器」 |
| `H:\TEST\mht\中文目录测试\MHT-HTML-Extractor3.0\`（父目录含中文） | ❌ **退出码 1，无窗口** |

**原因**：本程序用 GraalVM native-image 编译，**JavaFX 原生 DLL 的加载路径只支持 ASCII** —— 这是该技术栈的限制，不是本工具的逻辑缺陷。

**规避**：**目录别用中文，文件名随意。**

| 放法 | 结果 |
|---|---|
| `D:\tools\MHT-HTML-Extractor3.0\` | ✅ 正常 |
| `D:\工具\MHT-HTML-Extractor3.0\` | ❌ 可能启动失败 |
| `D:\tools\提取器3.0.exe`（**文件名**含中文、目录 ASCII） | ✅ 正常（限制只作用于**目录路径**） |

---

## 五、快速上手（5 步）

1. **解压**到纯 ASCII 目录 → 双击 `MhtExtractor.exe`。
2. **导入**：把 `.mht`/`.html` 文件或整个文件夹**拖到窗口**，或点「添加文件 / 添加文件夹」。
3. **整理**（可选）：勾选条目 → 「合并为组」；右键条目/组可重命名、移除、设参数。
4. **设参数**（可选）：右侧参数侧边栏 → 选目标（全局/组/条目）→ 填输出目录、文件名模板、图片区域限定等 → 保存或套用到目标。
5. **提取**：点「提取文本」或「提取图片」（也可先「查看预览效果」确认）→ 到输出目录取结果。

---

## 六、常见问题

| 问题 | 处理 |
|---|---|
| 双击没反应 / 一闪而过 | 99% 是**路径含非 ASCII** → 按第四节把目录改成纯 ASCII 再试 |
| 首次启动稍慢 | native 镜像首次加载 + 字体初始化，属正常，之后会快 |
| 杀毒软件告警 | 未签名的小众工具常见误报；可加信任，或用源码自行构建 |
| 内存不够 / 想限更小 | 用 `-XX:MaxHeapSize=…` 运行期覆盖（见第二节） |
| 提取不到图片 | 检查是否开了「区域限定」且起止标记写错；预览会给出提示卡片 |
| 想批量/脚本化 | `Backend\MhtExtractorBackend.exe --help`（无头 CLI / `--serve` IPC 模式） |

---

## 七、从源码构建（可选）

```
前置：Windows x64 + JDK 25（含 native-image，如 BellSoft Liberica NIK 25）+ MSVC 工具链

1) 环境预检    check-env.cmd        (退出码 0=就绪 / 20=缺 MSVC / 1=缺其它)
2) 一键构建    build-native.cmd     (默认 dynamic 配方；产物在 dist 目录)
3) 其它配方    set NATIVE_RECIPE=static      (JavaFX 编进 exe，体积更大、启动更慢)
               set NATIVE_TARGET=headless    (只构建无头后端)
```

技术栈：Java 25 + JavaFX 25 + GraalVM native-image（Liberica NIK 25）；构建脚本 `build-native.cmd` 是唯一实现。

---

## 八、许可证与反馈

- 许可证：**木兰宽松许可证第 2 版（Mulan PSL v2）** —— 见 `LICENSE`。
- 问题与建议：请在项目主页（Gitee / GitHub）提 Issue。

---

> English documentation: **[README.en.md](README.en.md)**
