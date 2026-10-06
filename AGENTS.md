# AGENTS.md —— 被指挥方（Codex CLI）入口

> 本文件是**短指针**，只做一件事：把硬约束指到唯一权威处。**不要**把规则复制到这里。
> 仓库约定的唯一权威是 **`CLAUDE.md`**（工程约定）与 **《基于graalvm的MHT资源提取器项目打包工作流程.md》**（native/打包）。

## 动手前必须读（按序）
1. **`CLAUDE.md`** —— 尤其：**§3 架构红线（16 条不变式）**、**§4 关键约定**、**§5 环境陷阱**、**§6 验证纪律（三档闸门 A/B/C）**。
2. 你被指派的任务提示词文件本身。
3. 需要时：`遗留目标与未完成事项.md`（台账）、`UI可切换重构方案.md`（P0–P5 方案）。

## 不可协商的几条（详见 CLAUDE.md，此处仅索引）
- **分层单向**：`gui → app → 业务包`；`app`/业务包/`model`/`platform`/`uicore`/`uiport` **零 JavaFX**。
- **不要提交、不要推送**：`git commit` / `git push` / `git tag` 一律由指挥方做。
- **产物落位**：运行时/测试产物 `H:\TEST\mht\`，临时 `H:\TEST\temp\`；跑 mvn 用 `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=H:\TEST\temp`；收尾断言 C 盘无新增。
- **不要与 GUI 自动化 / native 构建并发跑 `mvn clean`**。
- **自述不算验收依据**：给原始命令与原始输出，并显式列出未验证项；新断言要能人为造违规跑红（控制组）。
- **不要增加第三方依赖**；**不要引入 `WebView`**（仅约束 JavaFX 前端）；不要新增事件总线 / DI 容器 / 工作流引擎。

## 本机环境要点
- `mvn` 不在 PATH：用仓库自带 `mvnw.cmd`；`JAVA_HOME=D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3`。
- `MainController.java` 不要整文件读：`grep -n` 定位 + 区间读。
- 文档里的行号会漂移，引用前先复核。
