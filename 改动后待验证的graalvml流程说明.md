# 改动后待验证的 GraalVM 打包流程说明

> 本文档面向**打包电脑上的智能体/操作者**：本次"提取前预览与调整"功能改动对 GraalVM native-image 打包的影响、需要执行的步骤与验收清单。
> 打包总体流程以《基于graalvm的MHT资源提取器项目打包工作流程.md》为准，本文只补充本次改动相关项。

---

## 一、本次改动对打包的影响总览

| 项 | 影响 | 需要做的 |
|----|------|---------|
| 新依赖 `org.glavo:webp:0.2.0`（JWebP） | 纯 Java WebP 解码库，MPL-2.0，仅依赖 `java.base`，**无 JNI、无 System.load、不注册 ImageIO SPI**，全部直接 API 调用——native-image 最友好形态，**无需 JNI 配置** | 无（mvn 自动下载，自动进 target/lib） |
| 新控制器 `PreviewDialogController` + `PreviewDialog.fxml` | FXML 经反射加载，控制器被 agent 演练覆盖 | FXML 资源已写入 resource-config；反射条目已手工写入 reflect-config（见下） |
| 新资源 `sample-preview.webp` | 仅 agent 演练生成样例 MHT 时从类路径读取，native EXE 正常运行不依赖 | 无（不打包也可） |
| 手工 reflect-config 新增条目 | 见 `reflect-config.json` 中本次新增：PreviewDialogController、ListView/ListViewSkin/ListCell、TextArea/TextAreaSkin、CheckBox/CheckBoxSkin、ImageView/Image、**StackPane**（FXML 必需）、DragEvent/Dragboard/ClipboardContent/DataFormat/TransferMode | **打包时勿用 agent 输出覆盖这些手工条目**（合并时保留） |
| 拖拽动画（快照覆盖层 + TranslateTransition） | 动画类全部直接实例化（静态可达），**无需反射条目**；`Node.snapshot()` 若在 native 下异常，代码内置兜底自动退化为瞬时重排 | 无 |

**结论：`build-native.cmd` 不需要任何修改；无 JNI 配置；无新增反射手工条目需求（均已写入）。**

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
   本次演练新增阶段（Phase 7.5）自动覆盖：预览窗口打开、文本渲染、章节徽标、信息区、图片"查看预览效果"按钮、**WebP 缩放解码（JWebP + WebPFXImage）**、取消关闭。可由此捕获 JWebP 内部类的反射/资源需求；
3. **合并 reachability-metadata.json** 到 `src/main/resources/META-INF/native-image/com.mhtextractor/`，**保留** reflect-config.json / resource-config.json 中的手工条目（agent 输出与手工条目做并集，勿直接覆盖）；
4. 运行 `build-native.cmd`；
5. 按第四节清单在 native EXE 上真机验收。

## 三、agent 无法覆盖、需真机手工验证的项

- **拖拽 DnD**：无法脚本化。相关反射条目（DragEvent/Dragboard/ClipboardContent/DataFormat/TransferMode）已手工写入 reflect-config；
- **拖拽滑动动画**：`Node.snapshot()` 在 native 下的表现需真机确认。若拖拽时**没有滑动动画但排序功能正常**，说明快照兜底生效（可接受，不影响功能）；若动画正常则最优；
- **WebP 缩略图**：agent 演练已覆盖解码路径，但真机仍需确认（见清单）。

## 四、native EXE 验收清单

| # | 验收项 |
|---|--------|
| 1 | 主界面出现开关"开始提取前预览并调整提取效果"，随模式选择启用/禁用，配置保存/加载后状态恢复 |
| 2 | 文本模式：预览窗口打开，条目状态 ⏳→✅，选中文件显示 模板开头+正文首尾+模板结尾 |
| 3 | 文本模式：章节徽标列与列头显示，勾选"添加章节序号"徽标实时重算，取消勾选徽标消失 |
| 4 | 文本模式：开启标题提取时信息区显示"提取标题：xxx" |
| 5 | 图片模式：信息区第三行显示"输出结果：文件夹/ZIP/CBZ/ZIP + CBZ" |
| 6 | 图片模式：点击"查看预览效果"后 **WebP 缩略图逐张渐进显示**（123 张大图场景 1 分钟内完成） |
| 7 | 拖拽排序：蓝线指示、提起瞬间消失+下方上滑 0.2s 补位、松手条目瞬间出现+下方下滑 0.2s 让位、Esc 取消回原位 |
| 8 | 点击【提取】按拖拽后的顺序执行；点击【取消】无残留状态 |
| 9 | 拖拽中无异常日志；关闭窗口后台线程正常退出 |

## 五、故障排查速查

| 症状 | 排查方向 |
|------|---------|
| 打包失败：javafx 控件类反射缺失（ListView/TextArea/CheckBox 等） | 手工 reflect-config 条目被 agent 输出覆盖/丢失，按 §一 表格补回 |
| 缩略图显示"无法加载" | JWebP 类不可达：检查 reachability-metadata 是否包含 `org.glavo.webp` 条目（演练应已捕获）；或该图不是 WebP/PNG/JPEG/GIF（AVIF 不支持属预期） |
| 预览窗口打开报 FXML 加载失败 | 检查 resource-config 中 `PreviewDialog.fxml` 条目 |
| 拖拽无动画但功能正常 | `Node.snapshot()` 兜底生效（预期行为，可接受） |
| 拖拽/预览卡死 | 查看日志中"拖拽快照失败"警告，确认滚动守卫与取消路径正常 |
