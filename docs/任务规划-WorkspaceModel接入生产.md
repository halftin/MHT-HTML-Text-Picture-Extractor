# 任务规划：把 `WorkspaceModel` 接入生产（消除 workspace 两套实现）

> ✅ **本规划已执行完毕（2026-09-27）**。结果与**偏差**见 `docs/handoff/H33.md`：
> 阶段 1b 实际采用了规划**未考虑到**的"**共享存储**"路线（把数据序列表作为 `java.util.List` 注入模型），
> 它直接消除了规划所称的"本任务唯一的结构性难点"（别名 / 双状态）；阶段 2.5（撤销应用）与阶段 3 也已完成。
> **唯一未做的**是拖拽（REORDER / MERGE_GROUPS）—— 那是"给模型补能力"而非"消除重复"，已决策不做。
> 本文保留为**当时的计划与决策依据**；其中"顶格行数""4435"等表述反映的是当时状态，**已过时**。

> 生成：2026-09-26（会话末尾）　|　**状态：✅ 已完成（2026-09-27）** —— 结果与偏差见 `docs/handoff/H33.md`
> 前置阅读：`docs/handoff/H31.md`（**全部结论已实测，勿重新推导**）、`docs/架构重构-最终报告.md` §九
> 目标一句话：**让 `WorkspaceModel` 成为生产路径的 workspace 唯一真值源，消除与 `MainController` 的重复实现。**

---

## 一、为什么做这件事（三个收益同时兑现）

1. **消除真重复**：目前 workspace 状态有**两套实现**（H31 §三 实测）——
   `MainController` 里 `items` + `rebuildDisplay`(9 处) + `allVisibleItems`(17) + `visibleCount`(18) **在生产用**，
   `WorkspaceModel.group/ungroup/addItems/undo/clear` **只有测试用**。任何语义变更都要改两处。
2. **兑现 `app` 层承诺**：`WorkspaceModel` 是 app 层 5 个类里**仅 2 个没进生产**的之一（另一个是 `MhtExtractorApp`）。
   接进去后，"业务可脱离 UI 复用"在 workspace 这一环才不是空头支票（目标②）。
3. **自然给 `MainController` 减重**：状态与操作搬走后，`rebuildDisplay`/`allVisibleItems`/`visibleCount`
   等变成对模型的委托查询，行数随之下降（预计 **−200~400**）。

---

## 二、⚠️ 先说清楚风险：一个真实的"别名（aliasing）"问题

这是本任务**唯一的结构性难点**，必须先想清楚，否则会做出"看起来对、实际状态会分叉"的改动。

### 2.1 事实（已实测）

| 项 | 现状 |
|---|---|
| GUI 的**数据序** | `MainController:306` `/* test-support */ final ObservableList<Object> items = FXCollections.observableArrayList();` |
| GUI 的**显示序** | `MainController:308` `private final ObservableList<Object> displayItems = ...`（**ListView 直接绑定它**：`:460 fileListView.setItems(displayItems)`） |
| 模型内部 | `WorkspaceModel:41` `private final List<Object> topLevel = new ArrayList<>();` |

**关键点**：
- **ListView 绑定的是 `displayItems`（显示序），不是 `items`（数据序）** → 把数据序搬进模型**不会**破坏 ListView 绑定。
- 但 `items` 是**包级可见**的，被 `PreviewListCell`（14 处）与 `FeatureExercise`（125 处 lookup 之后的断言）读取。
- 模型内部是普通 `ArrayList`，**不可观察**。

### 2.2 四条可选路线（**已按架构红线筛过一遍**）

> ⚠️ **先说结论：任何"让 `WorkspaceModel` 持有 `ObservableList`"的方案都不可行。**
> `javafx.collections.ObservableList` 是 JavaFX 类型，而 `app` 包按**字节码常量池**被
> `ArchitectureTest.businessPackagesHaveNoUiFrameworkDependency` 强制零 javafx 依赖
> → 那样会**立刻打红不变式**。因此下表的"路线 A"已被否决，真正可行的是 **A′**。

| 路线 | 做法 | 可行性 | 说明 |
|---|---|---|---|
| ~~A~~ | 模型持有 `ObservableList`，`items` 指向同一实例 | ❌ **不可行** | 违反 app 包零 javafx（见上） |
| **A′（推荐）** | 模型**保持 `List<Object>`**；模型是**唯一真值源**；`MainController.items` 降级为**单向投影**（模型操作后由 `syncItemsFromModel()` 刷一次） | ✅ | 绑定与包级可见性都保留；**逻辑只剩一套**（一次投影不是第二套逻辑） |
| **B** | 模型保持 `List`，`items` 由模型**同步镜像**，两边都可写 | ⚠️ 不推荐 | **两套状态仍并存** → 没消除重复，只是加了同步代码 |
| **C** | 模型持有 `ObservableList`，`displayItems` 成为它的过滤视图 | ❌ 不可行 | 同 A（且显示序的分组展平用 `FilteredList` 复现风险高） |

> **决策：路线 A′。**
> 先只搬"数据序 + 分组/撤销/移除/添加"；
> **`displayItems` 与 `rebuildDisplay` 留在 GUI**（它是纯视图展平，属 GUI 职责，不搬）。

---

## 三、阶段划分（每阶段可独立验证）

> **铁律**：每个阶段结束都必须全绿（见 §五 回归门槛）+ 精确 `git add`（**不要 `git add -A`**）+ 提交。
> **行数红线**：`MainController` **只减不增**（当前 4435/4435，顶格！）。
> **标记红线**：`/* test-support */` ≤ **44**（顶格）。
> ⚠️ 因此**任何"先加后减"都会被不变式立刻拒绝** —— 必须净减，或先删后加。

### 阶段 0：建立"差分测试"作为安全网（**必须先做**）

**为什么先做**：现在的风险是"接错了但 30 项 GUI 断言仍通过"。需要一条**专门证明两套实现语义一致**的测试。

**做什么**：在 `src/test/java/com/mhtextractor/app/` 新增 `WorkspaceModelParityTest`：
对同一串操作序列（add → group → ungroup → remove → undo → clear），
分别驱动**真实 GUI 路径**（需 JavaFX 运行时，放 `test.mode` 侧）与 `WorkspaceModel`，断言 `displayOrder/topLevel/allItems/visibleCount` 一致。

**简化替代（更低成本，推荐先做这个）**：不引入 JavaFX，改为**把现有 GUI 侧断言数值化**：
在 `FeatureExercise.runAutomatedTests()` 的"Unified import / Remove+undo / Group+rename+ungroup"
三项里，**额外**用 `WorkspaceModel` 重放同样的操作并比对结果 —— 但这要求 GUI 侧与模型侧状态同步，
属"鸡生蛋"问题。**故阶段 0 实际应做**：

- ✅ 在 `labs` 意义上先**只读**：写一个测试，**用 `WorkspaceModel` 独自**跑一遍操作序列并固化期望值
  （等价于给模型补行为测试，**现在它只有 15 项基本测试**）。
- 这一步**零生产改动**，纯增测试，可安全提交。

**验证**：`mvn test` 384 → 384+N，全绿；`MainController` 行数**不变**。

---

### 阶段 1：让模型成为"数据序"的唯一真值源（路线 A′）

**做什么**（**不碰 `WorkspaceModel` 的字段类型**，它以 `List<Object>` 为准）：
1. `MainController` 所有"改数据序"的操作改为**先调模型**，再经**唯一入口** `syncItemsFromModel()` 投影：
   ```java
   /** 唯一写 items 的地方：把模型的数据序投影到 GUI 的 ObservableList（路线 A′） */
   private void syncItemsFromModel() {
       items.setAll(ws.topLevel());     // ws = WorkspaceModel；setAll 保留 ListView 的绑定
   }
   ```
2. **关键纪律**：`syncItemsFromModel()` 是**唯一**写 `items` 的地方。
   搬完后 `MainController` 里**不得再直接** `items.add/remove/set/clear`（用 `grep -n 'items\.'` 自查并记录）。
   > 这一步必须**先删旧写、再加新写**（因为行数顶格，不可"先加后减"）。

**为什么这样就算"消除重复"**：`items` 不再有自己的逻辑，只是模型状态的**只读投影**；
所有 workspace 语义（分组、撤销记录、可见数）只在模型里实现一处。

> ⚠️ **两个已踩过的坑，务必注意**：
> 1. **`WorkspaceModel.topLevel()` 返回的是内部列表的实时视图，不是快照**
>    （`docs/handoff/H17.md` 记录过：`assertEquals(initialTop, ws.topLevel())` 等于"拿列表和自己比"，**永远通过**）。
>    → 断言前必须 `List.copyOf(...)` 取快照；`syncItemsFromModel()` 里用 `items.setAll(ws.topLevel())` 是安全的
>    （`setAll` 会立即复制元素），但**不要在别处长期持有 `topLevel()` 的返回值**。
> 2. **`items.setAll(...)` vs `items.clear() + addAll(...)`**：前者是**一次**变更事件，
>    后者会产生**两次** → ListView 会闪两次、`SelectionState`/徽标可能瞬时错乱。
>    **统一用 `setAll`**。

**验证**：`mvn test` 全绿；`ArchitectureTest` 的 `businessPackagesHaveNoUiFrameworkDependency` **必须仍为 0 泄漏**（关键！）；
GUI 30/30（zh/en）；`git diff --numstat` 确认 `MainController` **净减**。

---

### 阶段 2：把无绑定冲突的操作切到模型

按**风险从低到高**逐个切，**一次只切一个**、各自提交：

| 序 | 操作 | 现状 | 切法 |
|---|---|---|---|
| 2.1 | `clear()` | `MainController` 不清模型 | 清空 → 调 `WorkspaceModel.clear()` + 同步 |
| 2.2 | `addItems` | 导入时直接 `items.add(...)` | → `ws.addItems(...)` + 同步 |
| 2.3 | `removeItems` | 已有 `MainController.removeItems`（2 处调用） | → `ws.removeItems(...)` + 同步 |
| 2.4 | `group` / `ungroup` | GUI 直接用 `GroupStructure` | → `ws.group/ungroup(...)` + 同步 |
| 2.5 | `undo()` | `onUndoClicked`(233 行) 自己按记录刷 UI | → `ws.undo()` + 按**返回的记录**刷 UI（**最复杂，放最后**） |

**每一切完后必须**：`FeatureExercise` 里对应的 GUI 断言仍 30/30；相关 workspace 语义测试仍绿。

---

### 阶段 3：把只读查询改为对模型委托（自然减行）

`allVisibleItems`(17 处调用) / `visibleCount`(18) / `topLevelGroups` → 改为 `ws.allItems()` 等委托。
这一步**纯减行**，风险低，但调用点多，建议**一次性完成并单独提交**。

---

### 阶段 4（可选）：清理与固化

- 若 `MainController.items` 已无外部直接写者，评估是否可把 `/* test-support */` 标记摘掉（**标记只减不增**，
  摘掉是允许的）；`PreviewListCell` 改用 `ctl.items` 的读取（读不改）。
- 收紧 `ArchitectureTest` 两个顶格常量到新的实测值。
- 写交接 `H32`。

---

## 四、给下一会话的**启动提示词**（整段复制）

```
我在 D:\JAVA\mht-resource-extractor 继续工作。上一会话完成了架构重构（步骤 0-7），
现在要做的是把 WorkspaceModel 接入生产，消除 workspace 的两套实现。

【第一步：不要改代码，先建立认知】
按顺序读：
1. docs/handoff/H31.md   ← **本次勘察的全部实测结论**（app 层落地情况、重复清单、
                            native 元数据反直觉事实、启动链、功能执行链）—— 直接引用，**不要重新推导**
2. docs/任务规划-WorkspaceModel接入生产.md ← 本任务的分阶段计划与提示词
3. CLAUDE.md             ← 构建命令、10 条不变式、环境陷阱、回归门槛
然后向我汇报：你理解的难点（特别是 app 包不得依赖 JavaFX 这一条对"路线 A"的否决）、
阶段划分与每阶段验证方式、以及你识别出的最大风险。不要改代码。

【环境（必须先做，否则所有构建失败）】
export JAVA_HOME='D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3'
MVN='/c/Users/Admin/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn.cmd'
"$MVN" test          # 不要加 -q（会吞 surefire 摘要）
"$MVN" -q -Dmaven.compiler.release=21 -DskipTests test-compile    # 快速语法检查

GUI 自动化必须直接起 java（**不要用 mvn javafx:run -Dtest.mode=true**）：
# ⚠️ mvn clean 会删掉 target/cp.txt，先重建：
"$MVN" dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java --module-path <jfx-win-jars> --add-modules javafx.controls,javafx.fxml \
     -Duser.language=zh -Duser.country=CN -Dtest.mode=true \
     -cp "target/classes;target/test-classes;<cp.txt>" com.mhtextractor.App
# 期望 logs/test-results.txt：30 passed / 0 failed
# ⚠️ 跑 en 前先移走 logs/gui-state.json（其持久化 locale 会覆盖 -Duser.language）
# ⚠️ PowerShell 会吞 -D 参数：必须用参数数组 @(...) 传

行数一律用 java .claude-work/tools/Extent.java <file>（**不要用 PowerShell 数行**，两者差 193 行）

git 提交必须内联身份（仓库未配置）：
git -c user.name="Administrator" -c user.email="admin@mht-extractor.local" commit -m "..."

【当前基线（必须不退化）】
单测 384/34 类（0 失败）；headless 44/0；GUI zh 30/0、en 30/0；产物基线 32 份；
架构不变式 10 条；业务层 JavaFX 泄漏 0。
**两条顶格红线（只减不增）**：MainController ≤ 4435 行；/* test-support */ ≤ 44 个。
业务层 I18n. 引用 ≤ 37。

【本任务的铁律】
1. **app 包不得依赖 javafx.***（按字节码常量池扫描）→ 这否决了"让 WorkspaceModel 持有 ObservableList"
   的方案，必须让模型保持 List、由 MainController 做单向投影。
2. 先做阶段 0（只增测试、零生产改动）再动生产代码。
3. 一次只切一个操作，各自提交、各自全量验证。
4. MainController 行数**只减不增** → 不可"先加后减"，要么净减，要么先删后加。
5. 每阶段结束：全量验证 + 写交接 + 精确 git add（不要 git add -A）+ commit。
6. 验证必须独立复验（不采信自己或 agent 的自述）。
7. 新写的断言提交前做一次**变异测试**（把被测行为改坏，确认它会失败）。

现在开始第一步：读文档并汇报理解与计划。不要改任何代码。
```

---

## 五、每阶段的验证清单（照抄执行）

| 验证项 | 命令 / 手段 | 期望 |
|---|---|---|
| 编译 | `"$MVN" -q -Dmaven.compiler.release=21 -DskipTests test-compile` | COMPILE OK |
| 全量单测 | `"$MVN" test` | 默认 **384(+N)/0**；headless **44/0**；BUILD SUCCESS |
| 独立复算 | `powershell -File .claude-work/s0-verify-reports.ps1` | 报告数=类数，0 失败 0 错误 |
| 产物基线 | 同上（`ProductBaselineTest`） | **32 份** |
| 架构不变式 | 同上（`ArchitectureTest`） | **10/0**；JavaFX 泄漏 **0** |
| `MainController` 行数 | `java .claude-work/tools/Extent.java src/main/java/com/mhtextractor/gui/controller/MainController.java` | ≤ **4435**（本任务应下降） |
| 标记数 | 同上 | ≤ **44** |
| GUI zh / en | 见 §四 命令 | 各 **30/0**，`[FAIL]` 计数 0 |
| 变更面 | `git diff --numstat` | 只含预期文件；无整文件重排 |

---

## 六、若阶段 2 风险超预期的退路

若发现"模型做真值源"会显著扰动现有语义（例如 `PreviewItem.parentGroup` 的维护时机、
`removed` 隐藏式移除与 `visibleCount` 的交互），则**收缩范围**：

- **只做阶段 1（投影）**：让模型持有数据序的**只读镜像**用于断言与复用，
  不改生产写入路径 → 收益是"模型能反映真实状态"，但仍有两处写入。
- 或**只做阶段 3（只读委托）**：`allVisibleItems`/`visibleCount` 改为委托，
  纯减行、零语义风险，但**不消除写入侧的重复**。

**宁可小步达成一半，也不要大步引入状态分叉。**（本项目的验证手段只能证明"没变"，不能证明"对"。）
