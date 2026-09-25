# claudecode 实施计划（图片预览缓存持久化 → 国际化）

> 版本：1.0
> 日期：2026-09-25
> 配套文件：`项目需求文档.md`（待办真值源）、`claudecode提示词.md`（逐会话提示词）
> **本文件是执行说明（How），`项目需求文档.md` 是需求与验收标准（What）。两者冲突时以需求文档为准。**

---

## 〇、项目目标与阶段划分

**总目标**：按「缓存优先、国际化最后」的顺序推进，避免两者在 `MainController.java` 与输出命名上互相冲突。

| 阶段 | 会话 | 内容 | 里程碑 |
|------|------|------|--------|
| **启动** | 会话 0 | 定标、基线、行号核对 | 可执行的验收基线 |
| **A** | 会话 1–5 | P0 缺陷修复（9 条） | 输出语义冻结 |
| **B** | 会话 6–7 | 共享基础设施（`ImageSource`、参数收敛+指纹） | 缓存地基就绪 |
| **C** | 会话 8–11 | 预览缓存持久化（需求 2） | 🎯 **需求 2 交付** |
| **D** | 会话 12–15 | 全量国际化（需求 3） | 需求 3 交付 |
| **E** | 会话 16 | NIK native 验证与打包 | 便携包交付 |

**国际化的前置依赖**：需求 3（全量 i18n）依赖阶段 A 与 C 完成——因为它要替换 `showAlert` 的失败文案，而那些文案的语义正在被 P0 修复与错误契约改造改变。**不要提前开始 i18n。**

---

## 一、本机 claudecode 运行原理（本计划的适配依据）

以下为实测得出的事实，**直接决定了提示词与操作方式**，不可忽略。

### 1.1 版本与后端

| 项 | 实测值 | 影响 |
|---|---|---|
| claudecode 版本 | `2.1.153`（`entrypoint: cli`） | CLI 形态，非 IDE 插件 |
| 后端 | `ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic` | **实际是 DeepSeek 驱动 Claude Code**，非 Anthropic 官方 |
| 主模型 | `deepseek-v4-pro[1m]`（1M 上下文） | 上下文大，但**不代表可以不清边界**（见 1.5） |
| Subagent 模型 | `deepseek-v4-flash`（`CLAUDE_CODE_SUBAGENT_MODEL`） | **子代理能力弱于主模型** → 复杂缺陷修复不应交给 subagent |
| 推理强度 | `CLAUDE_CODE_EFFORT_LEVEL=max` | 单步思考充分，可放心给复杂任务 |
| 自动更新 | `DISABLE_AUTOUPDATER=1` | 版本固定，提示词可针对 2.1.153 写 |

> ⚠️ **重要推论**：由于 subagent 跑在 `deepseek-v4-flash` 上，**用于并行探查/批量替换是可以的，但不要让它做需要深度推理的改动**（如 P0-1 索引错位、会话 10 的提取复用）。这类任务由主模型串行完成。

### 1.2 Shell 环境：Git Bash，不是 PowerShell

会话记录中 claudecode 实际执行的命令形态：

```bash
find src -type f | head -60
wc -l $(find src -name "*.java") | tail -1
grep -E "<artifactId>|<version>" pom.xml | head -30
git log -L 30,38:src/main/java/.../SanitizeUtils.java --oneline
git blame -L 34,35 src/main/java/.../SanitizeUtils.java
```

**结论**：
- 提示词中的验证命令一律用 **POSIX / Git Bash** 语法（`find`、`grep -rn`、`wc -l`、`git diff`）
- **不要写 PowerShell 命令**。注意：项目里的 `build-native.cmd` 内部会调用 `powershell -NoProfile -Command "..."` 来读 pom 版本号（`build-native.cmd:17`），这条**属于脚本内部**，与 agent 的 shell 无关
- 路径写法在 Bash 下用 `/d/JAVA/mht-resource-extractor`，在工具参数中用 `D:\JAVA\mht-resource-extractor`（参考 `.claude/settings.local.json` 里两种形态并存）

### 1.3 权限与审批模型

`C:\Users\Admin\.claude\settings.json`：

```json
{ "skipDangerousModePermissionPrompt": true }
```

项目级 `D:\JAVA\mht-resource-extractor\.claude\settings.local.json` 是**白名单制**，已允许的命令包括：

```
Bash(git add *) / Bash(git commit *) / Bash(git config *) / Bash(git log *)
Bash(where java *) / Bash(java -version) / Bash(where mvn *) / Bash(where native-image *)
Bash(reg query *) / Bash(wmic *) / Bash(xargs wc -l)
Read(//d//**) / Read(//c/Program Files/**) / Read(//c/Program Files (x86)/**)
"/d/install/bellsoft-liberica-vm-full-openjdk25-25.0.3/bin/native-image.cmd" --version
```

**结论与应对**：

| 情况 | 应对 |
|---|---|
| `mvn` 未在白名单 | 首次执行会弹审批，属正常；在提示词中**明确列出需要预授权的命令**，让用户一次性放行 |
| 本机 Maven/JDK 位置与 `build-native.cmd` 不符 | ⚠️ **`build-native.cmd` 硬编码 `X:\bellsoft-liberica-vm-full-openjdk25-25.0.3`、`X:\graalvm-jdk-25_...`、`X:\apache-maven-3.9.9`**，但白名单显示本机实际在 **`D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3`**。会话 16 前必须先核对/修正这 3 个路径 |
| 无项目级 `.mcp.json` | 没有 MCP 服务器，只有内置工具（Bash/Read/Write/Edit/Grep/Glob）。**不要假设有额外能力** |
| 项目无 `.claude/settings.json`（只有 `.local.json`） | 团队共享配置不存在；本计划不新增，避免改变用户环境 |

**建议在会话 0 追加到 `.claude/settings.local.json` 的 allow 列表**（减少逐条审批摩擦）：

```json
"Bash(grep -rn *)", "Bash(find src *)", "Bash(mvn -q -DskipTests compile)",
"Bash(mvn -q -Dmaven.compiler.release=21 -DskipTests compile)",
"Bash(mvn -q test)", "Bash(git status *)", "Bash(git diff *)", "Bash(git stash *)"
```

> 注意 `mvn` 相关命令**用 `mvn -q` 减少输出**，避免工具结果刷屏吃掉上下文。

### 1.4 跨会话状态：任务列表不继承

实测 `C:\Users\Admin\.claude\tasks\<sessionId>\*.json` 的 schema：

```json
{ "id": "1", "subject": "...", "description": "...", "activeForm": "...",
  "status": "completed|pending", "blocks": [], "blockedBy": [] }
```

关键：**任务目录以 `sessionId` 命名**（如 `d0144faf-497c-4495-aa32-3fd27874e9c8`）。

**结论（本计划最重要的机制设计）**：

> **任务列表是会话内的，不会跨会话继承。** 因此跨会话的唯一真值源必须是**文件**——即 `项目需求文档.md` 的章节状态 + 本轮产出的 handoff 记录。

**据此设计**：每个会话结束必须产出 `docs/handoff/H<n>.md`（见第五章），下一个会话第一件事是读它。**不要指望"上次的 todo 还在"**。

### 1.5 上下文管理：按会话切边界，不靠自动压缩

实测某会话的 token 增长轨迹（`cache_read_input_tokens`）：`26,240 → 27,008 → 29,440 → 32,896 → 33,536`——**每个工具结果永久累积在上下文里**。

`deepseek-v4-pro[1m]` 虽有 1M 上下文，但：

- `MainController.java` 单文件 5775 行，**完整读一次就吃掉大量上下文**
- 自动压缩（compact）会**有损摘要**，对本项目这种「改具体行号、改具体语义」的任务是灾难：压缩后 agent 会忘记「`:101-104` 的 `Pattern.quote` 要删」这类精确约束

**结论**：

| 规则 | 说明 |
|---|---|
| **一个会话只做一个会话的工作** | 见第四章的任务划分；做完就收尾交接 |
| **禁止在单会话里横跨两个阶段** | 例如同时做 P0-4 和 `ImageSource` 抽象 |
| **读大文件要用 offset/limit** | 对 `MainController.java` **禁止整文件读取**；必须用 `grep` 定位行号后按区间读（如 `offset:4441, limit:112`） |
| **不要依赖自动压缩** | 主动在会话边界交接，而不是等上下文满了被压缩 |
| **工具结果要瘦身** | 命令加 `-q`、`| head -30`、`| tail -5`，避免长输出 |

### 1.6 可用的验证手段（结合项目既有机制）

项目已有两套自动化能力，**必须在每个会话中用起来**：

| 机制 | 用法 | 用途 |
|---|---|---|
| `test.mode=true` | `mvn javafx:run -Dtest.mode=true`（或 native EXE 加 `-Dtest.mode`） | 跑 `runAutomatedTests()`，输出到文件，比对通过/失败数 |
| `agent.exit.delay` | `-Dagent.exit.delay=30000` | 触发 `exerciseAllFeatures()` 给 GraalVM 追踪代理演练；也可用于冒烟 |
| JUnit 纯逻辑测试 | `mvn test` | `util`/`processor` 已有 13 个测试文件，新增测试应沿用此风格 |

⚠️ **`mvn test` 可用性存疑**：`pom.xml:130` 声明 JUnit 6.0.3，但 `pom.xml:176` 的 surefire 是 3.2.5（JUnit 6 需 ≥3.5）。**会话 0 必须实测确认**；若跑不起来，修 surefire 提到会话 1 一起做。

### 1.7 Git 状态

- 分支：`master01`；`git add/commit/config` 已在白名单 → **允许 committer 提交**
- 建议：**每个会话结束提交一次**，提交信息含会话号，便于回滚（agent 改错时可用 `git stash`/`git revert` 快速恢复）
- 实测仓库只有 14 个提交，历史干净，**`git log -L` / `git blame` 可用**——排查"某行为何时引入"时优先用 git 而非猜测

---

## 二、六条硬规则（写入每个会话的提示词）

| # | 规则 | 理由 |
|---|------|------|
| **R1** | **先读后改**：改任何文件前必须先读它当前内容（用 `grep` 定位行号 + 区间 `read`） | 文档里的行号会漂移；实测已发现 `MainController` 等文件在会话间被改动过 |
| **R2** | **小步可编译**：每一步改动后立即 `mvn -q -Dmaven.compiler.release=21 -DskipTests compile` 验证 | 无 NIK 25 时可用 release=21 做快速语法检查（见 `CLAUDE.md` 末尾）；避免积累大量错误后再排查 |
| **R3** | **不扩大范围**：只做本会话列出的事。发现其他问题→**记入 handoff 的「新发现」**，不在本轮修 | 防止改动面失控；跨会话的目标漂移是本项目最大的隐性成本 |
| **R4** | **大文件禁读全文**：`MainController.java` 只允许 grep 定位 + 区间读 | 见 1.5 |
| **R5** | **行为变更必须显式标注**：任何改变用户可见输出的修复，都要在 handoff 里单列「⚠️ 破坏性/行为变更」 | 本计划含至少 2 处（P0-4 正则失效→生效、会话 15 输出名本地化） |
| **R6** | **不新增第三方依赖**：项目要出 GraalVM native EXE | 新增依赖需同步 native-image 元数据，成本高 |

---

## 三、会话 0：定标与基线（必须先做）

**目标**：把 `项目需求文档.md` 变成可执行的验收基线，暴露文档与代码的不一致。

| 步 | 动作 | 命令/方法 |
|---|---|---|
| 0.1 | 读 `项目需求文档.md` 全文，重点 §〇、§二、§三 | Read |
| 0.2 | 核对 §三 的 9 条缺陷行号是否仍准确 | 抽 3–4 条用 `grep -n` 验证；**漂移的行号就地修正文档** |
| 0.3 | 测 `mvn -q -DskipTests package` 是否通过 | Bash（可能需审批） |
| 0.4 | **实测 `mvn test`**，确认 §4.4 D-4（JUnit6/surefire3.2.5）问题真伪 | Bash |
| 0.5 | 跑一次 `test.mode=true` 记录当前通过/失败数**作为回归基线** | Bash |
| 0.6 | 核对 `build-native.cmd` 里的 3 个硬编码路径与实际安装位置 | `grep -n "X:" build-native.cmd` + `ls -d /d/install/*liberica* /d/install/*graalvm* /d/install/*maven*` |
| 0.7 | 建议更新 `.claude/settings.local.json` 的 allow 列表（见 1.3） | 征得用户同意后再改 |
| 0.8 | 产出 `docs/handoff/H0.md` | 见第五章模板 |

**产出**：`docs/handoff/H0.md`（基线报告 + 行号漂移清单 + `mvn test` 结论 + native 路径核对结果）

**若 `mvn test` 跑不起来**：把「修 surefire 版本」并入会话 1，并在 H0 中明确标注——否则会话 1–5 的「每条补测试」无法验证。

---

## 四、会话清单（会话 0–16）

### 会话总览

| # | 会话 | 阶段 | 文件集（禁止越界） | 交付 |
|---|------|------|-------------------|------|
| 0 | 定标与基线 | 启动 | 只读 + `项目需求文档.md` | H0 基线报告 |
| 1 | P0-7/8/9 解析层 | A | `MimeParser.java` + `MimeParserTest.java` | 4 项测试 |
| 2 | P0-4 内容限定 | A | `ContentExtractor.java` + `ContentExtractorTest.java` | 语义一致性测试 |
| 3 | P0-5/6 图片提取 | A | `ImageExtractor.java`、`SanitizeUtils.java` + 测试 | 误删/降级测试 |
| 4 | P0-3/C-6 写入层 | A | `ImageWriter.java`、`FileWriter.java`、`Archiver.java` + 测试 | 崩溃/竞态测试 |
| 5 | P0-1/2/3 编排层 | A | `ImageBatchProcessor.java`、`BatchProcessor.java` + 测试 | 索引/归档测试 |
| 6 | `ImageSource` 抽象 | B | `extractor/ImageSource.java`(新)、`InMemoryImageSource.java`(新)、`ImageWriter.java`、`ImageBatchProcessor.java` | 纯重构 |
| 7 | 参数收敛+指纹 | B | `ParamScope.java`、`AppConfig.java`、`MainController.java`(仅 1 处) | 等价性+指纹测试 |
| 8 | `PreviewCacheStore` | C | `cache/`(新包 4 个类) + 测试 | C4/C9/C11 |
| 9 | 预览落盘+生命周期 | C | `MainController.java`、`cache/PreviewCacheLifecycle.java`(新) | C1/2/3/7/8 |
| 10 | 提取复用 | C | `ImageBatchProcessor.java`、`MainController.java`、`PreviewCacheLifecycle.java` | C5/C6/C10 |
| 11 | 需求 2 回归边界 | C | 只读 + 测试 | C1–C11 全绿 |
| 12 | 错误契约+i18n 基建 | D | `i18n/`(新)、`GuiState.java`、`App.java`、native 元数据 | locale 切换 |
| 13 | UI 文案外置 | D | `MainController.java`、`ParamDialogController.java`、`BrowserLauncher.java`、2 个 FXML | I1–I4 |
| 14 | 产物/core 本地化 | D | `ChapterGenerator.java`、`OutputUnitPlanner.java`、`SanitizeUtils.java`、`ImageBatchProcessor.java`、`TextBatchProcessor.java`、`MainController.java` | I5/I6 |
| 15 | i18n 回归 | D | 只读 + 测试 | I1–I8 |
| 16 | NIK native 验证 | E | `build-native.cmd`(仅路径) | 便携包 |

---

### 阶段 A：P0 缺陷修复（会话 1–5）

> **切分原则**：按子系统切，保证每会话文件集不相交 → 允许并行（见第五章并发规则）。

#### 会话 1 — P0-7/8/9：解析层

**读**：需求文档 §三 的 P0-7/8/9 行。

| 步 | 动作 |
|---|---|
| 1.1 | `MimeParser.countTotalParts`：消除"受保护遍历前先无保护递归"，改为迭代或加深度上限，并捕获 `StackOverflowError` |
| 1.2 | 修 `:164` 守卫条件 `depth > RECURSION_LIMIT && continueCallback != null`；重新设计"用户选继续"后的语义 |
| 1.3 | `getFilenameFromPart` 改为**优先** `part.getFileName()`（Jakarta Mail 会正确解码 RFC 2047）；手写解析降级为兜底 |
| 1.4 | 修 `:179` 固定上报 `0.15` → 改为单调不减（`Math.max(prev, value)`） |
| 1.5 | 新建 `MimeParserTest`，覆盖：深嵌套不抛 `Error`、`filename="a.png"; size=1234` 取值正确、中文文件名（RFC2047）、进度单调性 |

**验证**：`mvn -q test -Dtest=MimeParserTest`
**禁止**：不要顺手做 §4.3 C-1④（字符集去重）——留给后续阶段

#### 会话 2 — P0-4：`ContentExtractor` 语义反转

| 步 | 动作 |
|---|---|
| 2.1 | 删除 `ContentExtractor.java:101-104` 的 `Pattern.quote`，与 `:77-79` 标题提取路径对齐 |
| 2.2 | 新建测试：同一 `useRegex=true` 下，内容限定与标题提取对同一正则**行为一致** |
| 2.3 | 补测试：正则不匹配时返回完整 HTML 且 `lastWarnings` 有记录 |
| 2.4 | 顺带修 `lastWarnings` 在标题路径不清空（§4.3 C-8），**仅限本类内** |

**⚠️ R5**：这是**行为变更**——修复后原本静默失效的内容限定会真正生效，用户输出会变。H2 必须显式标注。

#### 会话 3 — P0-5/6：`ImageExtractor`

| 步 | 动作 |
|---|---|
| 3.1 | `isAdImage` 改词边界/精确匹配（当前含 `"ad"` 子串匹配误删 `header@`/`download`/`shadow`） |
| 3.2 | 统一空字符串 CID 与 `null` CID 的处理（当前一个删一个留） |
| 3.3 | 删除图片时**记入 warnings**（当前静默丢失） |
| 3.4 | `AD_KEYWORDS` 改不可变集合 |
| 3.5 | 区分「区域内无 `<img>`」与「解析失败」：失败记 warning 并**中止**区域限定，不再静默放行全部（`:210-213` + `:219-220`） |
| 3.6 | 新建 `ImageExtractorTest` 覆盖 3.1–3.5 |

**禁止**：**不要**在本会话合并 §4.3 C-1① 的两份重复图片收集循环——会放大改动面。只登记，不修。

#### 会话 4 — P0-3/C-6：写入层

| 步 | 动作 |
|---|---|
| 4.1 | `ImageWriter.computeFileName` 补空值守卫（对齐 `saveImage`） |
| 4.2 | 修 `stripExtension` 对 `null`/`""` 都返回 `"unnamed"` 导致的 `substring(0,-1)` 崩溃路径 |
| 4.3 | 唯一名改为 `Files.createFile` / `StandardOpenOption.CREATE_NEW`，消除 check-then-create 竞态 |
| 4.4 | `Archiver`：子目录静默跳过 → 记 warning；`totalFiles` 只计文件数 |
| 4.5 | `Archiver`：失败时删除已写出的半截归档文件 |
| 4.6 | 新建 `ImageWriterTest` 覆盖 4.1–4.5 |

**注意**：P0-3 的**调用方**修复在会话 5（本会话只修写入层能力）

#### 会话 5 — P0-1/2/3：编排层

| 步 | 动作 |
|---|---|
| 5.1 | **P0-1**：`PreloadedProvider.provide` 改用已存在的 `ParsedFileEntry.fileIndex` 查表（或改 `Map<Integer,ParsedMht>`），修正索引错位 |
| 5.2 | 补测试：故意放入一个不可解析文件 → 后续文件图片归属正确、无越界 |
| 5.3 | **P0-2**：`archiveWithCleanup` 检查 `createZip`/`createCbz` 返回值；任一失败**不删源目录**、记 FAILED、向用户报错 |
| 5.4 | 补测试：归档失败 → 源目录仍存在 |
| 5.5 | **P0-3**：`saveImages` 以 `saved == 0` 判定失败；`incrementSuccess` 改由实际写入数驱动 |

**里程碑**：阶段 A 结束，**输出语义冻结**。

---

### 阶段 B：共享基础设施（会话 6–7）

#### 会话 6 — `ImageSource` 抽象（纯重构，行为必须不变）

| 步 | 动作 |
|---|---|
| 6.1 | 新建 `com.mhtextractor.extractor.ImageSource` 接口：`count()` / `bytes(int)` / `suggestedName(int)` / `detectedExt(int)` / `warnings()` |
| 6.2 | 新建 `InMemoryImageSource` 包装现有 `List<ImageData>` |
| 6.3 | `ImageWriter` 四个 `saveImages*` 重载的参数改为 `ImageSource` |
| 6.4 | `ImageBatchProcessor.ImageOutputHandler` 签名改为 `ImageSource` |
| 6.5 | 跑既有测试 + `test.mode=true` 确认**输出无变化** |

**关键**：本会话**不引入磁盘实现**。保持纯重构，否则无法判断后续 bug 来自重构还是新功能。

#### 会话 7 — 参数解析收敛 + 指纹

| 步 | 动作 |
|---|---|
| 7.1 | 抽出唯一入口 `ParamScope.effectiveImageParams(...)`，让预览（`MainController:4446`）与提取（`OutputUnitPlanner.resolve`）**共用** |
| 7.2 | 补测试锁定：两条路径对同一输入结果**逐字段相等** |
| 7.3 | `AppConfig` 新增 `imageExtractionFingerprint()`（顺序稳定、参与哈希） |
| 7.4 | 补测试：指纹稳定性 + 字段敏感性（改动每个应影响指纹的字段 → 指纹必变） |
| 7.5 | 登记 §4.2 B-3（`AppConfig` 作为哈希键又被原地修改）的隐患点，**不修** |

---

### 阶段 C：需求 2 实现（会话 8–11）

#### 会话 8 — `PreviewCacheStore`（存储层，无 GUI 依赖）

**新建 `com.mhtextractor.cache` 包**：

| 步 | 动作 |
|---|---|
| 8.1 | `PreviewCacheKey`：`record(绝对路径, size, mtime, paramFingerprint, formatVersion)` + `compute(...)` + `toDirName()`（SHA-256） |
| 8.2 | `PreviewCacheMeta`：需求文档 §2.5 的 schema + `matches(key)` 自校验 |
| 8.3 | `PreviewCacheStore`：**构造注入 `Path root`**；原子写入（`.tmp` → 改名 → `.complete`）；读取；按 key 删除；启动扫孤儿 |
| 8.4 | `DirectoryImageSource`：从缓存目录 + `meta.json` 实现 `ImageSource` |
| 8.5 | 测试：写读回环、key 不匹配不命中、无 `.complete` 无效、`.tmp` 被清理、**崩溃模拟**（写完 meta 不写 complete → 被扫掉） |

**验收**：C4 / C9 / C11
**禁止**：本会话**不碰** `MainController`

#### 会话 9 — 预览落盘 + 四条生命周期规则

| 步 | 动作 |
|---|---|
| 9.1 | `onPreviewEffect` 改为：查缓存 → 未命中则提取并落盘 → 内存只留 meta + **从磁盘图片解码的**缩略图 |
| 9.2 | 缩略图来源从内存 `ImageData` 改为磁盘文件 |
| 9.3 | 新建 `PreviewCacheLifecycle`，把四条删除规则实现为**显式谓词**：(a) 参数变更 (b) 提取完成 (c) 移除 (d) 退出 |
| 9.4 | 把四处参数变更点接到 (a)：`applyItemParams` / `applyGroupParams` / `applyConfirmedParams` / 应用到全局 |
| 9.5 | 每项目写锁（`ConcurrentHashMap` + `computeIfAbsent`） |
| 9.6 | (d) 关窗流程：弹提示「关闭程序前需要清空缓存，清空后关闭，请稍后」→ 等停标志 → 清空**应用专属根目录** |
| 9.7 | **预解析路径不写缓存**（只有点预览才建目录） |
| 9.8 | 验收 C1/C2/C3/C7/C8 |

**禁止**：不碰 `ImageBatchProcessor`（留给会话 10）

#### 会话 10 — 提取复用

| 步 | 动作 |
|---|---|
| 10.1 | 在 `processUnified` 的**单元内文件循环**（`:244-285`）逐文件查缓存（**按文件，非按单元**） |
| 10.2 | 命中 → 用 `DirectoryImageSource` 替代 `extractImages()`，跳过 `parse` |
| 10.3 | 输出命名**仍在提取期重算**（合并模式全局序号、单元内位置序） |
| 10.4 | 复用搬运用**复制**到输出，**全部成功后**才删缓存（禁止 move） |
| 10.5 | 规则 (b)：该项目提取完成后删其缓存目录 |
| 10.6 | 未命中路径行为**完全不变** |

**验收**：C5 / C6 / C10

#### 会话 11 — 需求 2 回归与边界验证

| 步 | 动作 |
|---|---|
| 11.1 | **C5 严格验证（必须通过）**：同批 MHT 跑两次（清缓存 / 不清缓存）→ 用 `diff -r` 递归比对输出目录（文件名 + 字节） |
| 11.2 | 边界：0 张图片的 MHT、超大 MHT、含 Data URI、区域限定命中/未命中 |
| 11.3 | 并发：预览中启动提取；并发预览多项目；手工删缓存目录 |
| 11.4 | 崩溃：写入中强杀 → 重启后无残留 |
| 11.5 | 磁盘满模拟（若条件允许）：行为不破坏输出 |
| 11.6 | 跑 `test.mode=true` 全量回归，与 H0 基线对比 |

**里程碑**：🎯 **需求 2 交付**（C1–C11 全绿）

---

### 阶段 D：全量国际化（会话 12–15）

> **前置**：阶段 A、C 已完成。**不要在 C 之前开始 D**。

#### 会话 12 — 错误契约 + ResourceBundle 基建

| 步 | 动作 |
|---|---|
| 12.1 | 设计错误契约：用户可见失败 → 错误码 enum 或结构化结果类型；底层只提供技术细节（记日志） |
| 12.2 | 新建 `com.mhtextractor.i18n.I18n`（`get(key, args...)`）；**禁止**业务代码直接 `ResourceBundle.getBundle` |
| 12.3 | 新建 `resources/i18n/messages_zh_CN.properties` / `messages_en_US.properties` |
| 12.4 | `GuiState` 加 `locale` 字段；`App` 启动时读取并注入 |
| 12.5 | native 元数据补 i18n 资源（`resource-config.json` / `reachability-metadata.json`） |
| 12.6 | 测试：`I18n` 缺失 key 的行为明确（不静默返回 null） |

**禁止**：本会话**不批量替换**文案（与 13 分离，避免一锅乱）

#### 会话 13 — UI 文案外置（含 FXML）

| 步 | 动作 |
|---|---|
| 13.1 | FXML 中文字面量 → `%key`（JavaFX 原生，**不要**搬进 Java） |
| 13.2 | ~65 处 `setText/setTitle/setHeaderText/setContentText/setPromptText/new MenuItem/new Label/new Tooltip` → `I18n.get(...)` |
| 13.3 | **拼接式文案改写为 `MessageFormat` 占位符**（如 `"输出文件：{0}.txt"`）——不可保留字符串拼接 |
| 13.4 | `showAlert` 失败文案改走错误码 + `I18n`（与 §3.4 / §4.2 B-1 合并） |
| 13.5 | **不动** 203 处 `log.*` 中文；**不动**测试/演练代码里的中文 |
| 13.6 | 验收 I1–I4 |

#### 会话 14 — 产物与 `core` 本地化

| 步 | 动作 |
|---|---|
| 14.1 | **先做哨兵改造**：`"unnamed"` → 常量或 `null`/`Optional` 语义（6 处分布在 `SanitizeUtils:32`、`ImageExtractor:190`、`ImageWriter:95`、`OutputUnitPlanner:209/219`、`ImageBatchProcessor:207`、`TextBatchProcessor:95`）；**补测试锁定比较行为** |
| 14.2 | `ChapterGenerator` 抽 `ChapterNamingStrategy`（中文策略迁移现有逻辑 + 西文策略）；**public API 与 0-based 语义不变** |
| 14.3 | 默认输出名本地化：`"组N"`（`OutputUnitPlanner:210`、`MainController:816/3598`）、`"输出文件"`（`:623`）、`All_Images`/`mht_output` |
| 14.4 | 验收 I5/I6 |

**⚠️ R5 关键**：14.1 与 14.3 **必须分两次提交**——否则哨兵破坏与文案变更的故障会纠缠。14.3 是**破坏性变更**（用户既有输出目录名会变）；**建议中文 locale 保留既有行为**，仅 `en_US` 用英文，老用户零影响。

#### 会话 15 — i18n 回归

| 步 | 动作 |
|---|---|
| 15.1 | 双 locale 下跑 `test.mode=true`，两者均须通过（I7） |
| 15.2 | 英文下检查布局不溢出/不截断（I3） |
| 15.3 | grep 确认无硬编码 UI 文案残留（I1） |
| 15.4 | 逐项核对 I1–I8 |

---

### 阶段 E：NIK 验证（会话 16）

**前置**：必须在有 Liberica NIK 25 / GraalVM 25 / VS BuildTools 的机器上执行。

| 步 | 动作 |
|---|---|
| 16.1 | **先核对/修正 `build-native.cmd` 的 3 个硬编码路径**（当前写 `X:\...`，实测本机在 `D:\install\...`） |
| 16.2 | `mvn -q -DskipTests package`（NIK 25）通过 |
| 16.3 | **`mvn test` 全量通过**（确认 §4.4 D-4 已修） |
| 16.4 | 执行 `build-native.cmd` 全流程（Step 1–6） |
| 16.5 | 冒烟：native EXE 启动 → 导入 → 预览（触发缓存落盘）→ 提取（触发复用）→ 退出（触发清空提示） |
| 16.6 | i18n：`-Duser.language=en` 下界面为英文（I8） |
| 16.7 | 确认缓存写入位置正确（**不是 CWD 相对路径**，见 §4.4 D-2） |
| 16.8 | 确认无控制台窗口（editbin 生效） |
| 16.9 | 产出便携包，记录版本号 |

---

## 五、并发规则（多 agent 场景）

### 可以并行的组

| 组 | 会话 | 理由 |
|---|---|---|
| 组 1 | 会话 1、2、3、4 | 文件集两两不相交（`core` / `core` / `extractor` / `output`） |

> ⚠️ 会话 1 与 2 **都改 `core` 包但不同文件**（`MimeParser` vs `ContentExtractor`），可并行。但若发现需要共用工具类，立即改为串行。

### 绝对禁止并行

| 组合 | 原因 |
|---|---|
| 会话 5 与 6/7 | 都改 `ImageBatchProcessor` |
| 会话 9 与 10 | 都改 `MainController` + `PreviewCacheLifecycle` |
| 会话 12/13/14 三者之间 | 都改 `MainController` + 文案表 |
| **会话 9–11 与 12–15** | i18n 与缓存都改 `MainController` 与输出命名（需求文档 §〇 已列此禁令） |

### 子代理使用限制

由于 `CLAUDE_CODE_SUBAGENT_MODEL=deepseek-v4-flash`（弱于主模型）：

| 可用于 subagent | 必须主模型串行 |
|---|---|
| 批量文案替换（会话 13，机械且编译可验证） | P0-1 索引错位（会话 5） |
| 探查「某个方法被谁调用」 | 会话 10 提取复用（多模块语义） |
| 生成测试骨架 | 会话 12 错误契约设计 |
| 并行核对行号漂移 | 会话 14.1 哨兵改造 |

---

## 六、停止条件与异常处理

| 触发条件 | 动作 |
|---|---|
| 单步改动后编译失败且 2 次修复仍失败 | **停下来**，记录到 handoff，不要继续扩大改动；用 `git diff` 展示现状请用户决策 |
| 发现文档与代码矛盾 | 以**代码**为准，修正文档，在 handoff 标注 |
| 发现未列出的 bug | 记入 handoff「新发现」，**不在本轮修**（R3） |
| 需要新增依赖 | **停止**，先问用户（R6） |
| 改动影响面超出本会话文件集 | **停止**，拆到新会话 |
| `mvn test` 无法运行 | 会话 0 发现则并入会话 1 修；否则降级为「编译通过 + 手工验收」，并在 handoff 标注风险 |

---

## 七、文档更新协议

每个会话结束必须做两件事：

**(1) 更新 `项目需求文档.md`**

- 该会话对应的验收项（C*/I*/P0-*）打勾或标注状态
- 修正漂移的行号
- **不要删除已完成内容**，加状态标记即可（历史对后续会话有价值）

**(2) 产出 `docs/handoff/H<n>.md`**

模板：

```markdown
# H<n> — <会话名>（<日期>）

## 状态
- 结果：完成 / 部分完成 / 阻塞
- 提交：<commit hash>

## 完成项
- [ ] 步骤 x.x — <简述> — 验证方式

## 验收结果
| 验收项 | 结果 | 证据 |
|---|---|---|

## ⚠️ 行为变更（R5）
- <如有；无则写「无」>

## 新发现（未修，留待后续）
- <文件:行号> — <问题> — 归属哪个阶段

## 行号漂移修正
- <文件>：<旧行号> → <新行号>

## 下一会话需要知道的
- <关键上下文，控制在 10 行内>
```

---

## 八、验收标准索引

| 类别 | 编号 | 出处 |
|---|---|---|
| P0 缺陷 | P0-1 … P0-9 | 需求文档 §三 |
| 缓存功能 | C1 – C11 | 需求文档 §2.7 |
| 国际化 | I1 – I8 | 需求文档 §3.5 |

---

## 九、本计划的已知限制

1. **会话 16 无法由 agent 独立完成**：`build-native.cmd` 需要 GraalVM + VS BuildTools，且输出到 `X:\123\MHTtoEX`（硬编码），需人工确认路径与权限。
2. **`mvn test` 可用性未验证**：受 §4.4 D-4 影响，会话 0 前无法确定测试是否真能跑。这是本计划最大的不确定项。
3. **手工 UI 验收无法自动化**：`test.mode=true` 覆盖大部分交互，但「关闭程序前清空缓存」弹窗、「大文件预览确认」等模态提示仍需人工点验。
4. **本计划未包含 §4.1 A 类（上帝类拆分）**：那是里程碑后的独立工作，与需求 2/3 无依赖关系。
