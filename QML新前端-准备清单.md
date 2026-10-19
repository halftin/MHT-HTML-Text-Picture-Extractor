# QML 新前端：需要准备什么

> 针对 `D:\JAVA\mht-resource-extractor`（Java 25 + JavaFX 25 + GraalVM native）
> 目标：用 **QML / Qt Quick** 重写前端，要求**体积小、性能好**
> 撰写依据：实测该仓库当前代码与文档（见每节引用的文件/行号），非推测。

---

## 一、结论速览

### ✅ 你最大的资产：前端/后端分离**已经完成了**

这不是"收尾中"的状态 —— 分离在**架构层已经闭合**：

| 事实 | 证据 |
| --- | --- |
| 端口层 **零 JavaFX、零 I18n、零业务类型** | `CLAUDE.md:89`，不变式 `uiportHasNoJavaFxDependency` 强制（`uiport` 47 类 / 0 违规） |
| 端口是**四通道抽象**，前端只经它访问工作区 | `uiport/Uiport.java:9-22` — `commands()` / `queries()` / `events()` / `confirmer()` |
| 已有**两个**端口实现 | `app/InProcessUiport.java`（进程内）、`app/IpcUiport.java`（跨进程） |
| 后端是**独立可执行进程** | `backend/BackendCli --serve`，JSON Lines over stdin/stdout |
| 协议**语言中立**，只有 JSON | `backend/IPC.md` |
| 视图模型已定义齐全 | `uiport/` 实测 **29 个源文件 / 64 个顶层类型**（含 52 个 `record`）：`WorkspaceVm` / `GroupVm` / `ItemVm` / `TargetId` / `ParamSchemaVm` / `PreStartStateVm`… （`CLAUDE.md:157` 记「47 类」为 P3 时期口径，现已增长） |

**含义：QML 前端不是"新整合"，而是端口的第三个消费者。** 硬骨头（业务与 UI 解耦、视图模型、事件流、确认往返、revision 一致性）已经有人替你啃完了。

### ⚠️ 但有三件事必须先想清楚

1. **QML 完全替换 JavaFX，不是叠加。** 两套 UI 栈并存 = 体积和复杂度双输，与"体积小"直接冲突。
2. **体积不会自动变小。** JavaFX 前端那部分（`javafx-graphics` 等 DLL）确实能省掉，但你要加上 Qt 的 `Qt6Core/Gui/Qml/Quick/QuickControls2` 一整套 DLL。净效果取决于 `windeployqt` 用得干不干净 —— **这是需要实测的第一件事，不是最后一件事**。
3. **既有的 17 条架构不变式、44 份 native 基线、`ArchitectureTest` 全部围绕 JavaFX 建立。** 换成 QML 后，其中一批（`headlessNativeMetadataHasNoJavaFx`、`MainController` 行数、`guiBusinessStateDoesNotGrow`…）会**失去意义或需要重写**。这是真实的治理成本，不要低估。

### 🔑 一个关键的重新定性

`CLAUDE.md:136` 明确写：

> **不引入 `WebView`**（**仅约束 JavaFX 前端**；**换框架后按新框架实际评估** —— 见《UI可切换重构方案》§14 决策 16）

也就是说 —— **这条禁令是给你留门的。** 如果换 QML，`WebView` 的限制按新框架重新评估。鉴于该项目有 `platform/` 包做 HTML 源码查看器（`HtmlSourceViewer` + `HtmlFindIndex` + `HtmlWrapText` + `CdpClient`），**QML 的 `WebEngineView` 可能是比手写 HTML 查看器更省事的路径** —— 但 QtWebEngine 基于 Chromium，体积代价极大（每个 WebEngine 部署都是 100MB+ 量级），与"体积小"冲突。**这一条需要你明确取舍。**

---

## 二、需要准备的组件（本机环境）

沿用《环境评估与安装清单.md》的结论，但**版本选择要改**：

| 组件 | 建议 | 理由 |
| --- | --- | --- |
| **MSVC** | ✅ 已有（VS 2026，工具集 14.44 / 14.51） | 与 Qt 的 `win64_msvc2022_64` 二进制兼容 |
| **Windows SDK** | ✅ 已有 10.0.26100 | |
| **CMake** | ❌ 需装，**≥ 3.21**（建议 3.27+） | Qt 6 官方要求；`qt_add_qml_module` 需要 |
| **Ninja** | ❌ 建议装 | Qt/QML 项目构建快得多；也可用 VS 生成器 |
| **Qt** | ❌ 需装，**建议 Qt 6.8 LTS 而非 6.10.2** | 见下 |
| **JDK** | ✅ 已有 Liberica NIK 25（`D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3`） | 后端侧 |
| **Node** | ✅ 已有 24.19.0 | 用于协议 spike（见第五节） |

### Qt 版本：为什么改推 6.8 LTS

上一份文档推荐 6.10.2，理由是 `PyExamples/requirements.txt` 锁了 `PySide6==6.10.2`。**那个约束在这里不成立** —— 你不走 Python 路线，也就不需要与 PySide6 对齐。

对 QML 桌面应用而言：

- **6.8 LTS**：长期支持、`Qt Quick Controls` 成熟稳定、企业级验证充分。**推荐。**
- **6.10.x**：`Qt Quick Controls` 的 Fluent/WinUI 风格原生支持更好（QML 有自己的 Style 体系，这是 FluentUIStyle 做不到的），但相对更新。

> 💡 这正是你最初想要"FluentUI 风格"的**正道**：QML 下用 `Qt Quick Controls` 的 `FluentWinUI3` 样式，而不是 `QProxyStyle`。

### 需要的 Qt 模块

| 模块 | 用途 | 必需? |
| --- | --- | --- |
| `Qt6::Core` `Gui` `Qml` `Quick` | QML 运行时 | ✅ 必需 |
| `Qt6::QuickControls2` | 控件库（含 FluentWinUI3 样式） | ✅ 必需 |
| `Qt6::Svg` | 图标 | 建议 |
| `Qt6::Network` | 若用本地 socket 替代管道 | 可选 |
| `Qt6::Concurrent` | 后台解码 WebP/大图 | 建议 |
| `Qt6::WebEngineQuick` | HTML 源码查看器 | ⚠️ 体积极大，权衡 |
| `Qt6::Multimedia` | 不需要 | ❌ |
| `Qt6::ShaderTools` | 若要自定义 QML 特效（`qsb` 编译着色器） | 可选 |

**不需要** Qt 的 Linguist 工具链（`lrelease`）—— 见第四节 i18n 讨论，但如果走 `.ts/.qm` 方案则仍需要。

---

## 三、需要在仓库里新建的工程

### 3.1 C++ 桥接层（**这是唯一的重头戏**）

位置建议：`frontend-qml/`（新建顶层目录，与 `src/` 平级，不要塞进 Maven 的 `src/main/java`）。

它要干的事，逐条对应现有 Java 侧实现：

| 职责 | 参照实现 | 要点 |
| --- | --- | --- |
| 拉起后端进程 | `app/BackendLauncher.java` | `stdin/stdout` 必须 **PIPE**，`stderr` **独立**，**严禁合并进 stdout**（`IPC.md:112`） |
| JSON Lines 增量解析 | `app/IpcCodec.java` | 每行恰好一个 JSON 对象，LF 结尾，**立即 flush**；EOF 前无 LF 的最后一行也算一帧 |
| 启动握手 | `IPC.md:225-256` | 必须发 `hello{protocolVersion:1, version, buildId}`，**三个预期值不能从对端 `ready` 复制**（否则失去防篡改意义） |
| revision 一致性 | `IPC.md:481-484` | 所有 response 顶层带 `revision`；**必须精确存 `long`，不能用浮点比较**；分页必须带 revision，不匹配返回 `INVALID_PARAMS` |
| 文件引用（FileRef） | `IPC.md:501-511` | **最大风险点**，见下 |
| 确认往返 | `app/IpcConfirmer.java`、`IPC.md:279-320` | 30 秒超时上限，`actionKey=null` 表示取消 |
| 事件流 | `uiport/PortEvents.java` | 订阅式；`close()` 即退订 |
| 生命周期 | `tools/ipc/orphan-recovery-control.mjs` | EOF = 正常关闭；客户端异常死亡需后端 3 秒内自然退出 |

### 3.2 ⚠️ FileRef 机制 —— 最容易低估的部分

协议**刻意避免把大批数据塞进管道**，改用「服务端签发文件」：

- `workspace{bulk:true}` → `{manifest: FileRef}`，`FileRef = {path, bytes, mediaType}`
- `workspacePage` → `snapshotFile`；`previewLoaded` → `manifest`
- `lastStats` / `affectedPaths` / `presentation` / `outputUnitPlan` 全是 FileRef
- 读完必须调 `releaseFiles` 释放，否则会话根堆积

**对 QML 的影响**：QML/JS 侧读本地文件能力弱且受限，**读写 FileRef 必须在 C++ 桥里做**，把解析后的对象再暴露给 QML。不要试图在 QML 里读这些 JSON 文件。

**必须验证的点**（协议明文规定，容易踩）：
- `bytes` 必须**精确等于**文件长度（`IPC.md:502`）
- 只允许在 `hello.bulkRoot` 内创建上传文件；拒绝链接/junction 重定向（`IPC.md:528-531`）
- `previewThumbnail.imagePath` 是**真实缓存图路径**，媒体类型按**字节魔数**判定 —— 不是 PNG，**不得伪称 PNG**（`IPC.md:437-446`）
- 规格明确列出支持 PNG/JPEG/GIF/BMP/**WebP**/ICO/CUR/AVIF，并提醒「这不等于承诺所有前端都能解码这些格式」

👉 **Qt 的 `QImage` 能直接读 PNG/JPEG/GIF/BMP/WebP(视构建)/AVIF(视插件)**。这比 JavaFX 侧（后端自带纯 Java WebP 解码器 `glavo webp`）反而可能更简单 —— 但**必须用真实样本实测**，尤其是 AVIF 和 WebP 在 MSVC 构建下的插件可用性。

### 3.3 视图模型映射层

`uiport` 里 29 个 VM 需要映射到 QML 可用的模型。建议：

- `QAbstractItemModel` 子类做工作区列表（组 / 条目 / 勾选 / 拖拽）
- `QSortFilterProxyModel` 做筛选
- 单个 VM → `QObject` + `Q_PROPERTY`（`QML_ELEMENT` 注册）
- 图片走 `QQuickImageProvider` + `QQuickAsyncImageProvider`（后台线程解码，避免卡 UI）

**参数表单是最大的机械工作量**：`IPC.md:173-179` 记载 `AppConfig` **29 个持久化属性**，`IPC.md:181-182` 又加 4 个 `@JsonIgnore` 运行时字段 → `RuntimeConfig` **33 项**（`AppConfig.java` 实测 203 行）。而 `ParamSchemaVm` 是**数据驱动**的（`IPC.md:193-198`：每个 field 恰好十个属性 `id/type/labelKey/group/defaultValue/enabledBy/disabledValue/nullWhenDisabled/promptText/templateEnter`，文本 14 field、图片 11 field）。

> ✅ **好消息**：既然是 schema 驱动，就可以写**一个通用 QML 表单渲染器**，而不是手搓 33 个控件。请务必走这条路。

### 3.4 需要新增的 Qt 侧构建配置

- `CMakeLists.txt` + `qt_add_executable` + `qt_add_qml_module`
- QML 模块 URI / 资源前缀规划（`qrc` 打包，避免外置 `.qml` 文件）
- `windeployqt` 后处理，**只带实际用到的模块**（体积关键）
- 发布目录布局：沿用现有约定 `MhtExtractor.exe`（根）+ `Backend\MhtExtractorBackend.exe`（`CLAUDE.md:445`、`IPC.md:325`）

---

## 四、必须解决的跨栈问题

### 4.1 i18n（**571 键**）

现状（`CLAUDE.md:104`）：

- `i18n/messages` 两份 bundle（zh / en），**各 571 键**，其中 `log.*` 243 条
- 协议层**零 I18n 引用**，**不传预渲染正文**（`IPC.md:296-297`）—— 明说「前端按当前语言渲染」
- 日志事件也只传 `key` + `namedParams`，前端渲染（`IPC.md:39-44`）
- 甚至嵌套文案（`ExtractWarning` → `$key.N` / `$arg.N.name`）也要求**前端先解析嵌套再替换外层**

**所以前端必须自带一份 571 键的翻译资源。** 两个方案：

| 方案 | 优点 | 缺点 |
| --- | --- | --- |
| **A. 复用 Java bundle**，构建期脚本导出 `.json`，QML 侧 `qsTr` 替代品查表 | 单一事实源，不用维护两份 | 需要写导出脚本 + QML 侧查表机制；失去 Qt 的 `.qm` 工具链 |
| **B. 转为 Qt `.ts` / `.qm`** | 走 Qt 官方 i18n 工具链 | **两份 571 键要人工同步**，必然漂移 |

> 建议 **A**：单一事实源。571 键人工同步是注定失败的。注意协议要求「**避免后台 locale 固化**」—— 后端故意不渲染文案，前端切语言要能立即生效。

### 4.2 确认往返的前端语义

`IPC.md:279-323` 规定得极细，QML 侧必须如实实现：

- 收到 `confirmationRequested` → 弹卡片；`defaultAction=null` 意味着**默认按取消交付**
- 超时上限 **30 秒**（`IpcConfirmations.DEFAULT_TIMEOUT_MS`），且前端要**本地也计时**（期限 + 250ms 传输余量）
- 另有 `confirmationExpired` 事件，收到即收卡
- 取消/超时都回 `actionKey=null`
- **活动终态也要收卡兜底**（否则卡片残留）

### 4.3 协议客户端兼容性

- 用**包含关系**检测 `ready.data.methods` 与 `capabilities`，**允许未知方法名**（`IPC.md:221`、`237`）
- 未声明的能力**不能当作已支持**
- **可以增量接入**：先做 `workspace` + `setChecked`（最小能力集），再逐步加。这给了你分阶段迁移的合法路径。

---

## 五、建议的推进顺序（**按风险从高到低**）

### 第 0 步：先做 Spike，别先装 Qt ⭐

仓库里**已经有** `tools/ipc/client.mjs`（16 KB，Node），已完整实现握手 + JSON Lines 传输 + 身份校验（实测：`client.mjs:156-164` 含 `hello` 握手与 `expectedIdentity` 校验）。

**先用它把协议跑通，零 Qt 成本。** 这一步能提前暴露：

- FileRef 生命周期是否好用
- revision 语义是否容易搞错
- 确认往返时序是否别扭
- 事件流顺序假设是否成立

> 这一步几乎零成本，却能避免"装完 Qt 写完桥接层才发现协议理解错了"的返工。

### 第 1 步：体积实测（**决定项目是否成立**）

在写任何业务代码前，先做一个最小 QML 应用 + 最小后端，实测：

1. `windeployqt` 后前端目录的真实体积
2. 后端 headless native exe 的真实体积（现有 GUI 版是 `MhtExtractor.exe` 94 MB + 动态 DLL ≈108 MB / 23 文件；headless 子集应显著更小 —— **但我没有实测数据，需你实测**）
3. 对比现状 ≈108 MB（dynamic）/ ≈98 MB（static）

**如果两者相加比现在还大，"体积小"这个目标就不成立**，需要立刻调整预期或换技术选型（比如直接上 C++/Qt 全栈，或 Rust + `slint`/`iced`）。

### 第 2 步：最小可用前端

`workspace` + `workspacePage` + 列表渲染 + 勾选 + 导入。

### 第 3 步：参数域

schema 驱动的通用表单渲染器 + 33 项 `RuntimeConfig`。

### 第 4 步：预览 / 提取 / 确认

FileRef 图片管线、`QQuickAsyncImageProvider`、进度事件、确认卡片、i18n。

### 第 5 步：替换与治理

- 移除 JavaFX → `pom.xml` 去掉 `javafx-controls` / `javafx-fxml`（注意 `headless-layer-b` 那个执行配置的用途会改变）
- 重写/裁剪那 17 条不变式中依赖 JavaFX 的部分
- 重建 native 基线（当前 44 份）

---

## 六、必须先回答的问题（这些决定方案是否成立）

| # | 问题 | 影响 |
| --- | --- | --- |
| 1 | **Java 后端是否保留？** | 若保留 → QML 只做前端，本文档方案成立。若想全栈 C++/Qt → 那是**重写整个项目**，`uiport`/`IPC.md`/GraalVM/44 份基线的投入全部作废，请先明确 |
| 2 | **HTML 源码查看器怎么办？** | 手写（延续 `platform/` 的逻辑，可移植）vs `WebEngineView`（省事但 +100MB 量级，与体积目标冲突） |
| 3 | **"体积小"的目标值是多少？** | 现状 ≈108MB。定不出目标值就无法判断方案是否成功 |
| 4 | **跨平台还是仅 Windows？** | 若仅 Windows，可用更多原生优化；若跨平台，Qt 的跨平台优势才体现出来 |
| 5 | **JavaFX 前端是"最终移除"还是"并存过渡"？** | 并存会长期维护两套 UI，与体积/复杂度目标冲突 |

---

## 七、与我先前建议的关系（更正）

上一份《环境评估与安装清单.md》是**针对 FluentUIStyle 这个 Qt Widgets 样式库**写的，其中：

- ✅ **仍然有效**：MSVC / Windows SDK 已具备、CMake 缺失、缺 Qt SDK、`E:` 盘空间充足、装到 `E:` 的建议
- ❌ **不再适用**：Qt 6.10.2（那是为 PySide6 对齐而选）、`lrelease`/Multimedia 的取舍、FluentUIStyle 本身（**QML 用不上它**）
- ➕ **新增**：Qt 6.8 LTS 建议、QML 模块清单、C++ 桥接层、FileRef 机制、i18n 571 键、.ts/.qm 或 JSON 导出决策

---

*本文档所有关于 `mht-resource-extractor` 的论断均引用了具体文件与行号；关于本机环境的论断来自实测。未在该机器上安装任何软件。*
