# claudecode 提示词

> 配套文件：`claudecode实施计划.md`（执行说明）、`项目需求文档.md`（需求与验收标准）
> **用法**：每次会话开始，从本文件以下小节复制对应提示词粘贴到 claudecode：
> §二 公共前缀（**每个**会话都要带）→ §三 会话 0 → §四 会话 1–5 → §五 会话 6–16 模板（按模板套用）。
> **文档引用约定**：下文 `§N` 指 `项目需求文档.md` 的章节；`实施计划 §N` 指 `claudecode实施计划.md` 的章节。**不要混用。**

---

## 一、使用须知（先读这一节）

### 1.1 提示词为什么这样写

针对本机 claudecode 实测特性（详见实施计划 §一）：

| 特性 | 提示词中的应对 |
|---|---|
| Subagent 跑 `deepseek-v4-flash`（弱于主模型） | 提示词显式声明「哪些任务不许交给 subagent」 |
| 上下文按工具结果累积，自动压缩有损 | 显式要求「大文件禁读全文」「一个会话只做一件事」 |
| Shell 是 Git Bash | 验证命令用 POSIX 语法（`diff -r`、`grep -rn`、`find`） |
| 任务列表不跨会话继承 | 强制「会话开始先读上一个 handoff 文件」 |
| 白名单审批制 | 提示词末尾列出需预授权的命令，减少中断 |

### 1.2 三条使用纪律

1. **一次只粘贴一个会话的提示词**。不要把会话 3 的提示词和会话 4 的一起粘——那正是实施计划 §二 R3 要防的「改动面失控」。
2. **会话结束后不要立刻开新会话**，先确认 `docs/handoff/H<n>.md` 已生成、`项目需求文档.md` 状态已更新。
3. **看到 agent 想「顺手修」别的东西时，立刻打断**。提示词已含 R3（不扩大范围），但需要你盯着。

---

## 二、公共前缀（每个会话提示词都要带这一段）

> **粘贴位置**：每个会话提示词的最前面。

```
你在 D:\JAVA\mht-resource-extractor（JavaFX 25 桌面应用，.mht 资源提取器）工作。

【必读】
1. 先读 claudecode实施计划.md 的 §一（本机 claudecode 运行原理）与 §二（六条硬规则）。
2. 若存在 docs/handoff/H<n-1>.md，先读它，再开始工作。
3. 需求与验收标准以 项目需求文档.md 为准。

【六条硬规则，违反即失败】
R1 先读后改：改任何文件前用 grep -n 定位行号，再按区间读它当前内容。文档里的行号会漂移，不要直接信。
R2 小步可编译：每步改动后跑 mvn -q -Dmaven.compiler.release=21 -DskipTests compile 验证。
R3 不扩大范围：只做本次列出的步骤。发现别的问题 → 记进 handoff 的「新发现」，本轮不修。
R4 大文件禁读全文：MainController.java（5775 行）禁止整文件读取，必须 grep 定位 + 区间读。
R5 行为变更显式标注：任何改变用户可见输出的改动，必须在 handoff 里单列「⚠️ 行为变更」。
R6 不新增第三方依赖：项目要出 GraalVM native EXE。

【环境】
- Shell 是 Git Bash，用 POSIX 语法（find / grep -rn / diff -r），不要用 PowerShell。
- 编译必须用 Liberica NIK 25；快速语法检查可用 -Dmaven.compiler.release=21。
- 没有配置 MCP 服务器，只有内置工具。

【收尾（必须做）】
1. 更新 项目需求文档.md 中本会话对应的验收项状态与漂移的行号。
2. 生成 docs/handoff/H<n>.md，按 实施计划 §七 的模板。
3. git commit，提交信息形如 "session <n>: <简述>"。
```

---

## 三、会话 0 提示词（定标与基线）

```
<粘贴公共前缀，n=0>

【本会话任务】定标与基线。只做勘察与验证，不做任何代码修改。

步骤：
0.1 通读 项目需求文档.md，重点 §〇（纪律）、§二（计划）、§三（缺陷清单）。
0.2 核对 §三 的 9 条缺陷行号是否仍准确。至少抽查 P0-1、P0-2、P0-4、P0-8 四条，
    用 grep -n 在对应文件里定位，把漂移的行号就地修正在文档中。
0.3 跑 mvn -q -DskipTests package，记录是否通过。
0.4 跑 mvn test，实测能否执行。这是关键：pom.xml:130 声明 JUnit 6.0.3，
    但 pom.xml:176 的 surefire 是 3.2.5（JUnit 6 需要 surefire >= 3.5）。
    明确结论：测试是能跑、还是被跳过、还是报错。
0.5 若测试能跑，跑一次 mvn javafx:run -Dtest.mode=true，记录通过/失败数作为回归基线。
0.6 核对 build-native.cmd 的硬编码路径。用 grep -n "X:" build-native.cmd 读出 X: 路径，
    再用 ls -d /d/install/*liberica* /d/install/*graalvm* /d/install/*maven* 对比实际位置。
    列出不一致清单。
0.7 读 .claude/settings.local.json，建议需要追加的 allow 条目（见实施计划 §1.3），
    但先征求我同意再改。

产出 docs/handoff/H0.md，必须包含：
- 构建状态（0.3）
- mvn test 的明确结论（0.4）—— 若跑不起来，明确说明「会话 1 必须并入修 surefire」
- 回归基线数值（0.5）
- 行号漂移清单（0.2）
- native 路径不一致清单（0.6）

不要修改任何 Java 源码。本会话只允许改 项目需求文档.md 的行号。
```

---

## 四、会话 1–5 提示词（P0 缺陷修复）

> 会话 2、3、4 的文件集互不相交，**可以并行**（但要各开一个独立会话/终端）。会话 5 必须在会话 4 之后。

### 会话 1 — P0-7/8/9 解析层

```
<粘贴公共前缀，n=1>

【先读】docs/handoff/H0.md、项目需求文档.md §三 的 P0-7/P0-8/P0-9 三行。

【本会话任务】修复 MimeParser 的三个解析层缺陷。文件集仅限：
  src/main/java/com/mhtextractor/core/MimeParser.java
  src/test/java/com/mhtextractor/core/MimeParserTest.java（新建）
不要碰其他文件。

步骤（每步后跑编译验证）：
1.1 P0-7 修 countTotalParts（约 :95, :109-124）：它目前在任何受保护的遍历之前
    就先完整递归了一遍 MIME 树，且只 catch Exception（catch 不住 StackOverflowError）。
    改为迭代实现，或加深度上限，并确保 StackOverflowError 不会穿透 parse()。
1.2 P0-7 修深度守卫条件（约 :164）：当前是 depth > RECURSION_LIMIT && continueCallback != null，
    回调为 null 时限制完全关闭。重新设计，使限制不依赖回调是否存在。
    同时确认 :172 在用户选"继续"后把回调置 null 的行为是否需要保留。
1.3 P0-8 修 getFilenameFromPart（约 :312-325）：当前优先用手写的 substring 解析
    （:330-341），导致 RFC 2047 编码词（=?utf-8?B?...?=）不解码、中文文件名损坏，
    且 filename="a.png"; size=1234 会把 '"; size=1234' 一起当文件名。
    改为优先 part.getFileName()（Jakarta Mail 会正确解码），手写解析降级为兜底或删除。
1.4 P0-9 修进度倒退（约 :179）：它无条件上报固定值 0.15，
    而 :193-194 的循环可能已推进到约 0.33。改为单调不减（Math.max(prev, value)）。
1.5 新建 MimeParserTest，覆盖：
    - 深层嵌套/畸形 MIME 不抛 Error
    - 含额外参数的 filename 取值正确
    - RFC 2047 中文文件名正确解码
    - 进度回调单调不减
    测试必须保持纯逻辑风格（不需要 JavaFX 运行时），沿用现有 src/test 的风格。

【禁止】
- 不要顺手做 §四 4.3 C-1④ 的字符集去重（三层重复），那是后续阶段的事。
- 不要重构 MimeParser 的职责划分（4.1 A 类）。

【验收】mvn -q test -Dtest=MimeParserTest 全绿；4 项测试各对应上述一条缺陷。
【报告】H1.md 中说明每条缺陷的修复方式与测试证据。
```

### 会话 2 — P0-4 内容限定语义反转

```
<粘贴公共前缀，n=2>

【先读】docs/handoff/H0.md、项目需求文档.md §三 P0-4 行、§3.3 中关于 lastWarnings 的描述。

【本会话任务】修复「内容限定」的正则开关语义反转。文件集仅限：
  src/main/java/com/mhtextractor/core/ContentExtractor.java
  src/test/java/com/mhtextractor/core/ContentExtractorTest.java（新建）

步骤：
2.1 先读 ContentExtractor.java 的 extractByMarker（约 :99-126）与 extractChapterTitle（约 :73-94）。
    确认现状：extractChapterTitle 在 useRegex=true 时用原始正则（正确），
    而 extractByMarker 在 useRegex=true 时却用 Pattern.quote（把正则当字面量，错误）。
2.2 删除 extractByMarker 中的 Pattern.quote，使其与标题提取路径语义一致。
2.3 补测试 A：同一 useRegex=true 下，内容限定与标题提取对同一正则的行为一致。
2.4 补测试 B：正则不匹配时返回完整 HTML，且 lastWarnings 有记录。
2.5 顺带修 lastWarnings 在标题路径不清空的问题（§四 4.3 C-8），仅限本类内。

【⚠️ 这是行为变更，必须在 H2.md 里单列】
修复后，原本静默失效的内容限定会真正生效 —— 用户的输出内容会改变。
请在 H2.md 的「⚠️ 行为变更」段明确写出这一影响，并提示需要重跑相关手工验收。

【禁止】不要把 lastWarnings 改成返回副本或引入新类型（那是 4.2 B-1 错误契约的范畴，留给阶段 D）。
【验收】mvn -q test -Dtest=ContentExtractorTest 全绿。
```

### 会话 3 — P0-5/6 图片提取

```
<粘贴公共前缀，n=3>

【先读】docs/handoff/H0.md、项目需求文档.md §三 P0-5/P0-6 行。

【本会话任务】修复 ImageExtractor 的两处静默错误。文件集仅限：
  src/main/java/com/mhtextractor/extractor/ImageExtractor.java
  src/main/java/com/mhtextractor/util/SanitizeUtils.java（仅在必要时）
  src/test/java/com/mhtextractor/extractor/ImageExtractorTest.java（新建）

步骤：
3.1 P0-5 先读 isAdImage（约 :281-285）与 AD_KEYWORDS（约 :23-26）。
    确认：关键词含 "ad" 且用子串匹配，导致 header@…、download…、shadow… 等
    Content-ID 被判为广告删除。改为精确匹配或词边界匹配。
3.2 P0-5 统一空值语义：当前空字符串 CID 被删（:142 路径）而 null CID 被保留，
    同一过滤器行为不一致。统一处理。
3.3 P0-5 删除图片时记入 warnings（当前完全静默，用户无从知晓丢失）。
3.4 P0-5 把 AD_KEYWORDS 改为不可变集合（当前是 Arrays.asList 当静态常量）。
3.5 P0-6 修区域限定静默降级：extractImageReferencesInOrder（约 :210-213）宽泛 catch 后
    返回空列表，而 isInfoAllowed（约 :219-220）在集合为空时对一切返回 true，
    导致解析失败或区域内无 <img> 时区域限制整个失效、区域外图片被提取。
    改为区分「区域内无引用」与「解析失败」；失败时记 warning 并中止该文件的区域限定。
3.6 新建 ImageExtractorTest 覆盖 3.1–3.5。
    测试可直接构造 ParsedMht（参考 HtmlExportBuilderTest:40 的用法），保持无 GUI 依赖。

【禁止】
- 绝对不要合并 §四 4.3 C-1① 的两份重复图片收集循环（:101-117 与 :137-155）。
  这两份的漂移正是 P0-5/P0-6 的成因，但合并会大幅放大改动面。只登记，不修。
- 不要动 ImageData 的 @Data 注解（那是 4.3 C-7）。

【验收】mvn -q test -Dtest=ImageExtractorTest 全绿；期间不得改变正常图片（非广告）的提取结果。
```

### 会话 4 — P0-3/C-6 写入层

```
<粘贴公共前缀，n=4>

【先读】docs/handoff/H0.md、项目需求文档.md §三 P0-3 行、§四 4.3 C-6 行。

【本会话任务】加固写入层，消除崩溃路径与竞态。文件集仅限：
  src/main/java/com/mhtextractor/output/ImageWriter.java
  src/main/java/com/mhtextractor/output/FileWriter.java
  src/main/java/com/mhtextractor/output/Archiver.java
  src/test/java/com/mhtextractor/output/ImageWriterTest.java（新建）

步骤：
4.1 先读 ImageWriter.java 全文（227 行，可整读）。
4.2 C-6 补 computeFileName 的空值守卫：saveImage 有 imageData == null || getData() == null 守卫，
    但公开的 computeFileName（约 :70-89）没有，会走到 detectExtensionByMagicNumber(imageData.getData())。
4.3 C-6 修崩溃路径：stripExtension 对 null 和 "" 都返回 "unnamed"（约 :95），
    于是空扩展名拼接出无点文件名，导致 :46 的 substring(0, -1) 抛 StringIndexOutOfBoundsException。
4.4 C-6 消除 check-then-create 竞态：dest.exists() 判断后用 new FileOutputStream 会截断已存在文件。
    改用 Files.createFile / StandardOpenOption.CREATE_NEW。
4.5 修 Archiver 静默跳过子目录（约 :47-63）却不记 warning、且 totalFiles（:40）把它们计入，
    导致进度永不到 100%：改为子目录记 warning，totalFiles 只计文件数。
4.6 修 Archiver 失败时留下半截归档文件：IOException 分支（约 :68-71）返回 false 前删除已写出的目标文件。
4.7 新建 ImageWriterTest 覆盖 4.2–4.6（空名不崩、并发同名不覆盖、含子目录有 warning、失败不留残file）。

【注意】P0-3 的「调用方」修复在会话 5（saved == 0 判定失败），本会话只修写入层自身能力。
【禁止】不要改 ImageWriter 的 4 个 saveImages* 重载签名（那是会话 6 的 ImageSource 抽象）。
【验收】mvn -q test -Dtest=ImageWriterTest 全绿。
```

### 会话 5 — P0-1/2/3 编排层

```
<粘贴公共前缀，n=5>

【先读】docs/handoff/H4.md（会话 4 的改动会影响本会话）、项目需求文档.md §三 P0-1/P0-2/P0-3 行。

【前置检查】确认会话 4 已完成。若未完成，停止并告知我。

【本会话任务】修复批量图片处理的编排层缺陷。文件集仅限：
  src/main/java/com/mhtextractor/processor/ImageBatchProcessor.java
  src/main/java/com/mhtextractor/processor/BatchProcessor.java
  src/test/java/com/mhtextractor/processor/ImageBatchProcessorTest.java（新建或扩展）

步骤：
5.1 P0-1 这是本次最重要的缺陷，请先完整读懂再改。
    读 preloadAll（约 :127-167）、PreloadedProvider（约 :359-370）、processUnified（约 :237-285）。
    现状：preloadAll 只把解析成功的文件加进 parsedList（:144-146），
    但 PreloadedProvider.provide 按循环位置取 entries.get(index)（:368），
    而 processUnified 传入的是完整文件列表下标（:259）。
    后果：任一文件解析失败后，此后每个文件的图片被存到下一个文件的名字与统计下；
    列表尾部抛 IndexOutOfBoundsException 被 :317 吞成"失败"；合并目录名也可能取自错误文件。
    修法：ParsedFileEntry 已经记录了原始下标（fileIndex 字段，当前只写不读），
    用它建立 Map<Integer, ParsedMht> 查表，或让 provide 接收原始下标。
5.2 补测试：构造一批 MHT，其中故意有一个不可解析文件，
    断言后续文件的图片归属正确、无越界、无错位。
5.3 P0-2 修归档失败删源：读 archiveWithCleanup（约 :562-590）。
    当前 Archiver.createZip/createCbz 的返回值（:569、:579）被丢弃，
    紧接着 :588 无条件 FileUtils.deleteDirectory(dir)。
    改为：检查两个返回值；任一失败则不删目录、记 FAILED、向用户报错。
5.4 补测试：模拟归档失败 → 断言源目录仍存在、状态为 FAILED。
5.5 P0-3 修全失败仍 SUCCESS：saveImages 无条件 incrementSuccess（:437、:486），
    且 processUnified 记录 SUCCESS 而不看实际写入数（:311-315）。
    改为以 saved == 0 判定失败，incrementSuccess 由实际写入数驱动。

【禁止】不要做 §四 4.1 A-4 的策略轴提取（那是阶段 F）。只修缺陷。
【验收】mvn -q test 全绿（含既有测试，确认无回归）。
【里程碑】本会话结束即「输出语义冻结」，请在 H5.md 中明确声明。
```

---

## 五、会话 6–16 提示词模板

> 会话 6 之后的提示词按下方模板套用：把 `<n>`、`<会话名>`、`<步骤清单>` 替换为
> `claudecode实施计划.md` §四（会话清单）对应会话的表格内容。

```
<粘贴公共前缀，n=<?>>

【先读】
- docs/handoff/H<n-1>.md
- 项目需求文档.md 的 <相关章节>
- claudecode实施计划.md §四「会话 <n>」

【本会话任务】<会话名>。文件集仅限：
  <从实施计划 §四 表格抄录文件集>
超出这个文件集的改动一律不做（R3）。

步骤：
<从实施计划 §四 对应会话的步骤表逐条抄录>

【验收】<从实施计划 §四 的「验收」行抄录>

【禁止】<从实施计划 §四 的「禁止」行抄录；若无则删掉本段>
```

### 三个需要特别加料的会话

以下会话有额外风险，提示词里必须补上对应段落：

**会话 10（提取复用）—— 补这段**：

```
【特别提醒 · 必须主模型完成，不许交给 subagent】
你在做的是跨模块语义改动（ImageExtractor + PreviewCacheStore + ImageBatchProcessor 三处协同），
subagent 跑在更弱的模型上，容易做出看似正确但语义错误的重构。

【三条不可违反的约束】
1. 复用粒度是「按文件」不是「按输出单元」：
   OutputUnitPlanner 会把多个 MHT 分到同一输出单元（按"来源+参数内容"分桶），
   所以缓存查找必须发生在 processUnified 的单元内文件循环里（约 :244-285），逐文件查。
2. 输出命名必须在提取期重新计算，不能从缓存元数据取：
   合并模式下有跨文件的全局序号（globalIndex），单元内也有位置序。
   缓存元数据里存的是「图片字节 + 来源标识 + 检测到的扩展名」，不含最终文件名。
3. 复用搬运必须用「复制」，全部成功后才删缓存。禁止 move。
   原因：move 会在归档/提取失败时同时破坏缓存与输出（P0-2 就是这么来的）。

【必须验证】C5：同批 MHT 跑两次（一次清空缓存、一次保留缓存），
用 diff -r 递归比对两个输出目录（文件名 + 字节）。必须完全一致。
```

**会话 14（产物/core 本地化）—— 补这段**：

```
【特别提醒 · 哨兵值陷阱】
"unnamed" 在本项目里是内部哨兵值（sentinel），不是可翻译的文案。
它出现在 6 处、语义被混用：
  SanitizeUtils:32、ImageExtractor:190、ImageWriter:95  —— 作为返回值
  OutputUnitPlanner:209、OutputUnitPlanner:219、ImageBatchProcessor:207、TextBatchProcessor:95 —— 作为 equals 比较
如果你把它翻译成"未命名"，这 6 处 equals 会全部失效，输出命名逻辑静默走错分支。

因此：
- 步骤 14.1（哨兵改造）与 14.3（默认名本地化）必须分成两次 git commit。
- 14.1 完成后必须有测试锁定这 6 处比较行为。
- 不要为了 i18n 顺手把哨兵改成中文。

【破坏性变更提示】
14.3 会改变用户既有的输出目录名/组名。建议：中文 locale 保留既有行为（"组N"、"输出文件"），
仅 en_US 用英文，这样老用户零影响。请在 H14.md 中说明你的取舍。
```

**会话 16（NIK 验证）—— 补这段**：

```
【前置】本会话需要 GraalVM 25 + VS BuildTools，且 build-native.cmd 当前硬编码了 X:\ 路径
（X:\bellsoft-liberica-vm-full-openjdk25-25.0.3、X:\graalvm-jdk-25_...、X:\apache-maven-3.9.9），
而本机实际安装位置可能在 D:\install\ 下。

步骤 16.1 必须先做：核对并修正这 3 个路径。改完先让我确认，再往下跑。
不要直接执行 build-native.cmd，因为它的输出目录是 X:\123\MHTtoEX（硬编码）。

【验证清单】逐条执行并记录证据：
- mvn -q test 全量通过
- build-native.cmd Step 1–6 完成
- native EXE 冒烟：启动 → 导入 → 预览（确认缓存目录生成）→ 提取（确认命中缓存）→ 退出（确认清空提示）
- -Duser.language=en 下界面为英文
- 确认缓存写入位置正确（不是 CWD 相对路径）
- 确认无控制台窗口（editbin 生效）
```

---

## 六、收尾与恢复提示词

### 6.1 会话收尾（每个会话最后一句）

```
本会话到此结束。请执行收尾：
1. 更新 项目需求文档.md 中本会话对应的验收项状态；修正你发现的行号漂移。
2. 生成 docs/handoff/H<n>.md（按 实施计划 §七 模板）。
3. git add + commit，提交信息 "session <n>: <简述>"。
4. 给我一份 5 行以内的总结：完成了什么、验收结果、下个会话的第一件事。
不要开始下一个会话的工作。
```

### 6.2 会话恢复（agent 中途跑偏时）

```
停。你现在超出了本会话的范围。

请立即：
1. 用 git diff --stat 告诉我你改了哪些文件。
2. 对照 claudecode实施计划.md §四「会话 <n>」的文件集，指出哪些改动越界了。
3. 先用 git stash 把越界的改动收起来（不要直接删），然后回到本会话的步骤 <x>。

越界的原因如果是「发现了必须现在修的问题」，请写进 H<n>.md 的「新发现」段，而不是直接修。
```

### 6.3 会话被打断后恢复（新会话继续旧工作）

```
上一个会话（会话 <n>）被中断了，没有生成 H<n>.md。

请先做 damage assessment，不要直接继续改代码：
1. git status 和 git diff --stat，看有哪些未提交的改动。
2. 读 claudecode实施计划.md §四「会话 <n>」的步骤清单。
3. 逐条判断：哪些步骤已完成、哪些做了一半、哪些没动。给出判断依据（不要猜）。
4. 跑 mvn -q -Dmaven.compiler.release=21 -DskipTests compile，确认当前代码是否可编译。
5. 把结论告诉我，等我确认后再继续。
```

### 6.4 编译失败求助（agent 连续修不好时）

```
停。请不要继续尝试修复。

现在做三件事：
1. 把完整的编译错误原样贴出来（不要摘要）。
2. 把你的改动与原始代码用 git diff 展示，标出你认为最可疑的一处。
3. 给出 2 个候选修复方向，各自说明风险。

然后等我决定。不要自己选一个继续试。
```

---

## 七、需要预授权的命令（建议一次性放行）

为避免逐条审批中断，建议在会话 0 时把以下条目加入 `.claude/settings.local.json` 的 `permissions.allow`：

```json
"Bash(grep -rn *)",
"Bash(grep -n *)",
"Bash(find src *)",
"Bash(diff -r *)",
"Bash(mvn -q -DskipTests compile)",
"Bash(mvn -q -Dmaven.compiler.release=21 -DskipTests compile)",
"Bash(mvn -q -DskipTests package)",
"Bash(mvn -q test)",
"Bash(mvn -q test -Dtest=*)",
"Bash(mvn javafx:run *)",
"Bash(git status *)",
"Bash(git diff *)",
"Bash(git stash *)",
"Bash(git show *)",
"Bash(git blame *)"
```

**注意**：
- 用 `mvn -q` 而非裸 `mvn`——减少输出量，避免工具结果吃掉上下文（实施计划 §1.5）。
- **不要**放行 `rm`/`del` 类命令，缓存清理相关的删除操作应保持逐次审批。
- 已有的白名单里 `Bash(git add *)`、`Bash(git commit *)` 已允许，无需重复添加。

---

## 八、提示词效果自查清单

每完成一个会话，用这份清单检查 agent 是否真的按规矩做了：

| 检查项 | 怎么看 |
|---|---|
| 是否先读后改 | 看它的工具调用序列：是否有 `grep -n` 定位，而不是直接 Edit |
| 是否小步编译 | 是否有多次 `mvn -q ... compile`，而不是只在最后编译一次 |
| 是否越界 | `git diff --stat` 的文件清单是否超出该会话的文件集 |
| 是否读了 handoff | 会话开头是否有 Read `docs/handoff/H<n-1>.md` 的调用 |
| 是否交接到位 | `docs/handoff/H<n>.md` 是否按模板生成、是否含「新发现」段 |
| 行为变更是否标注 | 会话 2、14 的 handoff 里是否有「⚠️ 行为变更」段 |
| 大文件是否整读 | 是否有对 `MainController.java` 的整文件 Read 调用（有则违反 R4） |

**最容易被忽略的两项**：越界（R3）和大文件整读（R4）。这两项直接决定后续会话的成败——上下文被污染后，agent 会开始"忘记"精确约束。
