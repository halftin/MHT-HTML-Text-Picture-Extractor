# 改动后待验证的 GraalVM 打包流程说明

> 本文档面向**打包电脑上的智能体/操作者**：功能完善需求（阶段 1–8：增量导入/复选框/移除撤销/分组/组参数/拆分组/拖拽升级/输出单元拆分）与"提取前预览与调整"功能改动对 GraalVM native-image 打包的影响、需要执行的步骤与验收清单。
> 打包总体流程以《基于graalvm的MHT资源提取器项目打包工作流程.md》为准，本文只补充本次改动相关项。
>
> **最后更新：2026-09-07**（阶段 9 演练扩展完成后）。

---

## 一、本次改动对打包的影响总览

| 项 | 影响 | 需要做的 |
|----|------|---------|
| 新依赖 `org.glavo:webp:0.2.0`（JWebP） | 纯 Java WebP 解码库，MPL-2.0，仅依赖 `java.base`，**无 JNI、无 System.load、不注册 ImageIO SPI**，全部直接 API 调用——native-image 最友好形态，**无需 JNI 配置** | 无（mvn 自动下载，自动进 target/lib） |
| 新控制器 `ParamDialogController` + `ParamDialog.fxml`（`PreviewDialogController` 已删除） | FXML 经反射加载，控制器被 agent 演练覆盖 | FXML 资源已写入 resource-config；反射条目已手工写入 reflect-config（见下） |
| 阶段 1–8 新增 `util` 类：IncrementalImport / SelectionState / UndoManager / GroupItem / GroupStructure / DropResolver / OutputUnitPlanner / UnitProgressAggregator / ParamScope | 全部为**直接 new / 静态调用**（静态可达），无 Class.forName、无 Jackson 序列化参与、无动态代理——**无需任何反射条目**（agent 演练已实测确认，与 8.4 预期一致） | 无 |
| 新资源 `sample-preview.webp` | 仅 agent 演练生成样例 MHT 时从类路径读取，native EXE 正常运行不依赖 | 无（不打包也可） |
| 手工 reflect-config 新增条目 | 见 `reflect-config.json` 中本次新增：ParamDialogController、GuiState/GuiStateManager、ListView/ListViewSkin/ListCell、TextArea/TextAreaSkin、CheckBox/CheckBoxSkin、ImageView/Image、**StackPane**（FXML 必需）、DragEvent/Dragboard/ClipboardContent/DataFormat/TransferMode | **打包时勿用 agent 输出覆盖这些手工条目**（合并时保留） |
| `reachability-metadata.json` | 已在本机（NIK 25.0.3 + tracing agent）重新演练并按**并集合并**（旧条目 + 新演练输出，reflection 201 条 / resources 2901 条）。特别保留了旧版中的 `com.sun.prism.shader.DrawRoundRect_Color_Loader`、`sun.launcher.LauncherHelper$FXHelper` 等新演练未捕获但运行必需的条目 | 拉取最新代码即可；若打包电脑重新演练，**按并集合并，勿直接覆盖**（见 §2 步骤 3） |
| 拖拽动画（快照覆盖层 + TranslateTransition） | 动画类全部直接实例化（静态可达），**无需反射条目**；`Node.snapshot()` 若在 native 下异常，代码内置兜底自动退化为瞬时重排 | 无 |
| `runAutomatedTests()` 自动化测试 | 已改为**临时目录驱动**（不再依赖 `Y:\MHTtest` 固定路径），输出到 `logs/test-results.txt`；`App.java` test.mode 分支已修为在 FX 线程执行（原来在 Timer 线程直接操作控件会抛 "Not on FX application thread" 崩溃） | 打包电脑可直接 `java -Dtest.mode=true ...` 复验 |

**结论：`build-native.cmd` 不需要任何修改；无 JNI 配置；无新增反射手工条目需求（均已写入）。**

---

## 二、打包电脑上的操作流程

1. **拉取最新 master01**（包含本说明的 commit）；
2. **常规 agent 演练**（见打包工作流程文档 §4.3.3）：
   ```
   java -agentlib:native-image-agent=config-output-dir=agent-config \
     -Dagent.exit.delay=95000 \
     --module-path target/lib \
     --add-modules=javafx.controls,javafx.fxml,javafx.graphics \
     -cp "target/classes;target/lib/*" \
     com.mhtextractor.App
   ```
   演练脚本 `exerciseAllFeatures()` 已扩展覆盖全部新功能：**增量导入、复选框、移除、撤销（含撤销拖拽/整组/拆分）、合并为组、组名编辑（重名自动追加序号）、组参数设置与继承、拆分组、拖拽（组内排序/跨组/组行移动/两组合并，performDrop 程序化驱动）、输出单元拆分（未分组按来源+各组）、背景色三态**，以及原有预览窗口/WebP 解码演练。
   > 已知坑（本机 NIK 环境实测）：若 `-agentlib:native-image-agent` 报 "Can't find dependent libraries"，改用 `-agentpath=<JDK>/bin/native-image-agent.dll=config-output-dir=agent-config` 全路径形式。
   > 演练开始会自动**清场**（移除 GUI 状态恢复导入的既有文件并重置撤销栈），结束时日志应有 "Agent exercise complete - all UI features exercised" 且无 "演练失败" 警告；
3. **合并 reachability-metadata.json** 到 `src/main/resources/META-INF/native-image/com.mhtextractor/`。**规则：按并集合并（agent 输出 ∪ 仓库现有），保留** reflect-config.json / resource-config.json 中的手工条目，**勿直接覆盖**。至少确认以下条目未丢失：`com.sun.prism.shader.DrawRoundRect_Color_Loader`、`javafx.scene.input.DragEvent/Dragboard`、`StackPane`、ParamDialogController；
4. 运行 `build-native.cmd`；
5. 按第四节清单在 native EXE 上真机验收；
6. （可选）`java -Dtest.mode=true --module-path target/lib --add-modules=javafx.controls,javafx.fxml,javafx.graphics -cp "target/classes;target/lib/*" com.mhtextractor.App` 复验自动化测试（预期 `logs/test-results.txt` 中 17 passed, 0 failed）。

---

## 三、agent 无法覆盖、需真机手工验证的项

- **拖拽 DnD**：无法脚本化（演练中的拖拽为 `performDrop` 程序化驱动，覆盖解析/执行/撤销逻辑；真实手势的 DragEvent 路径需真机验证）。相关反射条目（DragEvent/Dragboard/ClipboardContent/DataFormat/TransferMode）已手工写入 reflect-config；
- **拖拽滑动动画**：`Node.snapshot()` 在 native 下的表现需真机确认。若拖拽时**没有滑动动画但排序功能正常**，说明快照兜底生效（可接受，不影响功能）；若动画正常则最优；
- **WebP 缩略图**：agent 演练已覆盖解码路径，但真机仍需确认（见清单）。

---

## 四、native EXE 验收清单

### A. 功能完善需求（阶段 1–8，本次重点）

| # | 验收项 |
|---|--------|
| 1 | 增量导入：先选 A 目录（2 个 mht）→ 再选 B 目录（1 个 mht）→ 列表 [A1,A2,B1] 不重建；重复导入去重；撤销可整体移除本次导入 |
| 2 | 复选框：最左列 CheckBox；列头三态全选/全不选（含组内成员）；勾选不影响提取名单 |
| 3 | 移除：勾选移除/整组移除；仅"移除后列表为空"弹确认框；空组自动销毁 |
| 4 | 撤销：撤销移除/导入/建组/拆分/整组移除/拖拽排序/组合并，35 步深度；栈空按钮禁用 |
| 5 | 合并为组：组条目+组内缩进、展开/折叠、组内序号列；单项目建组；重名自动追加序号并日志提示 |
| 6 | 组参数：选中组 → 参数设置 → 确定后只作用于该组；未设置继承全局；组参数确认后组内重解析 |
| 7 | 背景色三态：现状色（无生效参数）/浅绿（全局参数）/深绿（组参数），随参数确认与入组/出组实时刷新 |
| 8 | 拆分组：成员按组内顺序回顶层原组位置；撤销重建（组名与参数恢复） |
| 9 | 拖拽：组内排序、跨组入组（含末尾/指定落点）、出组到顶层、组行移动、两组合并（成员按原顺序，目标组名/参数保留）；拖拽致空组销毁；撤销恢复拖拽前结构 |
| 10 | 输出单元拆分：文本合并输出未分组按来源拆分、每组独立输出（组名命名）；图片合并输出目录名=组名/来源名；进度跨单元聚合；取消不启动后续单元 |
| 11 | 章号按单元重算：组内独立编号、顶层连续编号、每文件章节覆盖优先 |

### B. 提取前预览与调整（上一期）

| # | 验收项 |
|---|--------|
| 1 | 主界面开关"开始提取前预览并调整提取效果"，随模式选择启用/禁用，配置保存/加载后状态恢复 |
| 2 | 文本模式：预览窗口打开，条目状态 ⏳→✅，选中文件显示 模板开头+正文首尾+模板结尾 |
| 3 | 文本模式：章节徽标列与列头显示，勾选"添加章节序号"徽标实时重算，取消勾选徽标消失 |
| 4 | 文本模式：开启标题提取时信息区显示"提取标题：xxx" |
| 5 | 图片模式：信息区第三行显示"输出结果：文件夹/ZIP/CBZ/ZIP + CBZ" |
| 6 | 图片模式：点击"查看预览效果"后 **WebP 缩略图逐张渐进显示**（123 张大图场景 1 分钟内完成） |
| 7 | 拖拽排序：蓝线指示、提起瞬间消失+下方上滑 0.2s 补位、松手条目瞬间出现+下方下滑 0.2s 让位、Esc 取消回原位 |
| 8 | 点击【提取】按拖拽后的顺序执行；点击【取消】无残留状态 |
| 9 | 拖拽中无异常日志；关闭窗口后台线程正常退出 |

---

## 五、故障排查速查

| 症状 | 排查方向 |
|------|---------|
| 打包失败：javafx 控件类反射缺失（ListView/TextArea/CheckBox 等） | 手工 reflect-config 条目被 agent 输出覆盖/丢失，按 §一 表格补回 |
| 渲染异常/黑屏：shader 加载失败 | 检查 reachability-metadata 是否包含 `com.sun.prism.shader.*_Loader` 条目（合并时勿丢失旧条目） |
| 缩略图显示"无法加载" | JWebP 类不可达：检查 reachability-metadata 是否包含 `org.glavo.webp` 条目（演练应已捕获）；或该图不是 WebP/PNG/JPEG/GIF（AVIF 不支持属预期） |
| 参数窗口打开报 FXML 加载失败 | 检查 resource-config 中 `ParamDialog.fxml` 条目 |
| 拖拽无动画但功能正常 | `Node.snapshot()` 兜底生效（预期行为，可接受） |
| 拖拽/预览卡死 | 查看日志中"拖拽快照失败"警告，确认滚动守卫与取消路径正常 |
| `-agentlib:native-image-agent` 报 "Can't find dependent libraries" | 改用 `-agentpath=<JDK>/bin/native-image-agent.dll=config-output-dir=agent-config` 全路径（NIK 环境实测） |
| test.mode 抛 "Not on FX application thread" | App.java test.mode 分支必须走 `Platform.runLater`（已修）；若回退到旧代码请重打补丁 |
