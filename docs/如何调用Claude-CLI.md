# 如何调用本机 Claude CLI 执行任务（DSH 驱动指南）

> 日期：2026-09-26
> 适用：DSH（DeepSeek Harness）会话内，以子进程方式驱动本机 `claude.exe` 完成大批量/大上下文任务
> 本指南是**实战总结**：所有内容都经过实际使用验证，含踩过的坑与规避方法。

---

## 一、为什么这样做

DSH 会话上下文有限（长任务易超 50%），而本机 `claude.exe` 跑 `deepseek-v4-pro[1m]`（1M 上下文）。
把"读大量代码、批量替换、跨文件盘点"这类任务外包给它是**划算的**：不占用 DSH 上下文，
同时它有足够窗口容纳整个项目。

**不适合外包给它的**：需要与用户来回确认的决策、需要 DSH 侧独立验证结论的任务。

---

## 二、前置条件（缺一不可）

### 2.1 会话权限必须是完全权限

**这是最容易踩的坑**。`read-only` / `workspace-write` 模式下 DSH 的 Windows ACL 沙箱
以受限令牌启动子进程，导致**所有子进程启动失败**：

```
pwsh -Command "hello"   →   [exit code: 3221225794]   即 0xC0000142 STATUS_DLL_INIT_FAILED
```

**必须先切到完全权限**（`danger-full-access`），子进程才能启动。切换后立即生效，无需重启。
验证：`pwsh -Command "hello"` 应正常输出。

### 2.2 claude.exe 位置与指纹

| 项 | 值 |
|---|---|
| 路径 | `%APPDATA%\npm\node_modules\@anthropic-ai\claude-code\bin\claude.exe` |
| 形态 | **原生 exe**（非 Node 脚本），约 235 MB |
| 版本 | `2.1.153` |
| 后端 | `~/.claude/settings.json` 配 `ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic` |
| 主模型 | `deepseek-v4-pro[1m]` |
| Subagent 模型 | `deepseek-v4-flash`（**弱于主模型**，见 §五） |

### 2.3 `mvn` 不在 PATH

本机无 PATH mvn，必须用 Maven wrapper + NIK 25：
见 `.claude-work/env.sh`（DSH 已建好，`source` 后可用 `mvnx` / `mvncompile` / `mvntest` / `mvntest1` / `run-test-mode`）。

---

## 三、驱动方式：用 Node 驱动脚本（**不要用 PowerShell**）

### 3.1 为什么不用 PowerShell

| 坑 | 现象 | 结论 |
|---|---|---|
| `$args` 是自动变量 | 脚本里写 `$args = @(...)` 会破坏整个脚本作用域，**静默失效、0 字节输出** | 别在 PS 里做这个 |
| 执行策略 | `CurrentUser=RemoteSigned` 覆盖 `LocalMachine=Bypass`，`.ps1` 无法直接运行 | 需 `-ExecutionPolicy Bypass` 或 `.cmd` 包装 |
| PS 5.1 GBK 读脚本 | 无 BOM 的 UTF-8 脚本被按 GBK 读，中文脚本**解析失败** | 用 `[scriptblock]::Create()` 或写 BOM |
| 中文经 stdin 乱码 | PS 5.1 经 stdin 传中文 → `鏁版嵁瀹夊叏` | 中文放文件或 argv |
| `-Dfoo=bar` 参数被吞 | PS 把 `-Dtest.mode=true` 解析坏 → `ClassNotFoundException: /mode=true` | 用参数数组 `@(...)` 传 |

**结论：用 Node**。Node 直连原生 exe，天然 UTF-8、原生 `spawn`、无上述问题。

### 3.2 驱动脚本

DSH 已建好：`.claude-work/drive-claude.js`（本仓库内，可复用）

```bash
# 基本用法（Node 路径：D:\New Folder\node.exe）
node .claude-work/drive-claude.js \
  --prompt <提示词文件> \
  --label <标签> \
  --budget <美元上限> \
  [--session <uuid>] \
  [--resume] \
  --cwd D:\JAVA\mht-resource-extractor
```

关键实现要点（若需重建）：

```js
const { spawn } = require('child_process');
const child = spawn(CLAUDE_EXE,
  ['--print', '--dangerously-skip-permissions',
   '--settings', <白名单JSON>, '--max-budget-usd', String(budget),
   '--session-id', sessionId],
  { cwd: workDir, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });

child.stdout.on('data', c => { outStream.write(c); process.stdout.write(`[out +${c.length}B]\n`); });
child.stderr.on('data', c => { errStream.write(c); });   // 必须消费，否则可能阻塞
child.stdin.write(promptText, 'utf8');
child.stdin.end();                                        // 必须 end()，否则 --print 一直等
child.on('close', code => { /* 记录 meta：exitCode / elapsedSec / 字节数 */ });
```

**必读注意**：
- 提示词走 **stdin**（`write` + `end`），不要走 argv（长提示词会超命令行长度限制）
- `--print` 是**单轮无状态**；配合 `--session-id <uuid>` 可确定性创建会话，
  `--resume <uuid>` 可续跑（本项目未实测续跑，如需用请先小样验证）
- **stdout 是结束时一次性输出**（非流式）。想要流式加 `--output-format stream-json`
- 结果量与"工具调用次数"不成比例：一个做了 30 次编辑的会话，stdout 可能只有 60 字节
  （因为 `--print` 只输出最终回复）。**判断进度要看文件系统变化与 git log，不要看 stdout**

### 3.3 白名单 settings

DSH 已建好 `.claude-work/settings.workflow.json`，预授权常用命令（`git *` / `mvnx` / `grep` / `Read` 等），
减少审批中断。配合 `--dangerously-skip-permissions` 后可完全无中断运行。

⚠️ **权衡**：`--dangerously-skip-permissions` 让 agent 可无限制改文件。
本项目做法是**每会话结束 git commit** + 开始前打标签，出问题可回滚。

### 3.4 Git 身份（必踩的坑）

**本仓库未配置 git 身份**，`git commit` 会报 `Author identity unknown`。
必须内联身份（**不要** `git config`，那会改用户环境）：

```bash
git -c user.name="Administrator" -c user.email="admin@mht-extractor.local" commit -m "..."
```

或 `git tag -a` 同样需要 `-c` 参数。

---

## 四、提示词设计规范（本项目已验证有效的 7 条）

每个会话的提示词应包含以下段落。缺任一段都出过问题。

| # | 段落 | 作用 | 缺失后果 |
|---|---|---|---|
| 1 | **前置检查** | "读 `docs/handoff/H<n-1>.md`，不存在则输出 `SESSIONn_BLOCKED` 并停止" | agent 会在依赖未就绪时硬干 |
| 2 | **必读清单** | 列出该会话的规格文件（设计文档 / 映射表 / handoff） | 会自己发明方案 |
| 3 | **六条硬规则** | R1 先读后改 / R2 小步可编译 / R3 不扩大范围 / R4 大文件禁读全文 / R5 行为变更标注 / R6 不新增依赖 | 改动面失控、上下文爆掉 |
| 4 | **构建环境** | `source .claude-work/env.sh` + 可用函数 | **全部构建失败**（mvn 不在 PATH） |
| 5 | **文件集白名单** | "允许改动：<列表>；不允许改动：<列表>" | 越界改动难回滚 |
| 6 | **步骤清单** | 编号步骤 + 已核验的真实行号（并注明"自行复核"） | 按陈旧行号改错位置 |
| 7 | **收尾要求** | 生成 handoff + 跑测试 + 更新需求文档 + 精确暂存提交 + **输出一行 `SESSIONn_DONE ...`** | 无法判断完成度 |

### 4.1 关键技巧

- **行号必须让 agent 自行复核**：提示词写"已核验行号 X，请自行 `grep -n` 复核，不要盲信"
  —— 因为会话之间行号会漂移
- **禁止整读大文件**：`MainController.java`（现约 6100 行）明确写"禁止整文件读取，
  必须 grep 定位 + 区间读"，否则上下文被一个文件吃掉
- **并行时禁止 `git add -A`**：多会话并行时会让暂存区互相污染。
  改为精确 `git add <本会话文件>`，并提示"若 `index.lock` 被占用则 sleep 3 重试一次"
- **要求输出结构化结束标记**：`SESSIONn_DONE tests=<PASS|FAIL> ...`，
  便于 DSH 快速判断，无需读全部日志
- **明确"宁登记不猜测"**：对本机无法验证的事（如 native），要求 agent
  **登记待办而非猜一个实现**。这条在本项目挽救了 i18n 的元数据方向（见下）

### 4.2 分工原则（重要）

| 任务类型 | 交给谁 | 理由 |
|---|---|---|
| 读大量代码、批量替换、跨文件盘点 | **Claude CLI** | 不占 DSH 上下文；1M 窗口 |
| 需要独立验证结论 | **DSH** | agent 自述不可信（见 §六） |
| 需要与用户确认决策 | **DSH** | agent 无法问用户 |
| 复杂语义改动（如索引错位修复） | **Claude CLI 主模型**，**禁止 subagent** | subagent 跑 `deepseek-v4-flash`，弱 |

---

## 五、验证纪律：DSH 必须独立复验（本项目核心经验）

**agent 的自述不能作为验收依据**。本项目每次都由 DSH 用**不同手段**复验。
以下复验手段已被证明能发现真实问题：

| 手段 | 发现过的真实问题 |
|---|---|
| 直接聚合 `target/surefire-reports/*.txt` 算测试数（而非读 agent 日志） | 多次确认基线 |
| 直接 `java -Dtest.mode=true` 跑 GUI 自动化（**不用** `mvn javafx:run`，它不传系统属性） | 会话 0 发现的坑 |
| **独立 diff 比对**（如 C5 提取复用：`git diff --no-index` 比对两轮输出目录） | 证明复用无语义偏差 |
| 自写探针程序直连生产路径（如 `<tmpdir>/mht-extractor-preview` 真实 write→load） | 证明缓存 schema 与命中判定正确 |
| 方法/字段级**存量对账**（比对元数据修改前后集合） | 证明去重没丢条目 |
| **包围语句分析**（对"剩余中文"向上回溯判断是否 log/注释/测试） | 证明无 UI 泄漏 |
| `git diff --numstat` 看增删行数 | 发现我的合并**重排整文件**（−606）；对比后来 agent 的纯增量（+61/−0） |

**特别提醒**：agent 报告可能**过度乐观**。本项目出现过：
- 我用 `-q` 跑 `mvn test` 得到"0 测试"，实际是 `-q` 模式吞了报告（改用非 `-q` 得 274 全绿）
- 我用正则 `^\s*([A-Z_]+)\s*[,)]` 提取枚举，漏掉**分号结尾的最后一个常量**，
  误判"ErrorCode 少一个"（实际 20=20 对齐）

**结论：验证脚本自身也可能是错的，对"不一致"要再核一遍再下结论。**

---

## 六、本项目踩过的坑（完整清单，务必规避）

| # | 坑 | 现象 | 规避方法 |
|---|---|---|---|
| 1 | 沙箱导致子进程全灭 | `0xC0000142` | 切完全权限 |
| 2 | PS `$args` 自动变量 | 驱动脚本静默失效 | 用 Node 驱动 |
| 3 | PS 执行策略 | `.ps1` 拒绝运行 | `-ExecutionPolicy Bypass` / `.cmd` 包装 / `scriptblock` |
| 4 | PS 5.1 GBK 读 UTF-8 脚本 | 中文脚本解析失败 | 写 BOM 或用 scriptblock |
| 5 | **`mvn` 不在 PATH** | 所有构建失败 | `source .claude-work/env.sh` |
| 6 | `mvn javafx:run -Dtest.mode=true` 不传系统属性 | GUI 测试没真跑 | 直接用 `java` + 参数数组 |
| 7 | `mvn -q test` 吞 surefire 摘要 | 误判"0 测试" | 看摘要时不要加 `-q` |
| 8 | git 身份未配置 | commit 失败 | 内联 `-c user.name/email`，勿 `git config` |
| 9 | **`src/main/resources` 下放临时文件** | 被 Maven 打进 jar（我的 `.bak` 事故） | 备份放仓库外或 `.claude-work/` |
| 10 | `.gitignore` 的 `config/` 未锚定 | 误伤 `src/test/.../config/`，新测试被静默忽略 | 用 `/config/` 锚定到仓库根 |
| 11 | 并行会话 `git add -A` | 暂存区互相污染 | 精确暂存各自文件 |
| 12 | 并行会话改同一文件 | 出现中间破坏态（如 `ImageWriter` 一度用了不存在的构造器） | 文件集不相交；或串行 |
| 13 | PowerShell 控制台 GBK | 中文/emoji 输出乱码或 `UnicodeEncodeError` | 设 `PYTHONIOENCODING=utf-8`；避免 emoji |
| 14 | 行号跨会话漂移 | 按旧行号改错位置 | 提示词要求 agent 先 `grep -n` 复核 |
| 15 | **手工推断 native 元数据** | 我推断 i18n 需 `ResourceBundle` 反射，实测 **0 条**（真正需要的是资源注册） | **一律用 tracing agent 采集**，不猜 |

---

## 七、可复用资产清单

| 资产 | 路径 | 用途 |
|---|---|---|
| Node 驱动 | `.claude-work/drive-claude.js` | 驱动 claude.exe |
| 命令白名单 | `.claude-work/settings.workflow.json` | 减少审批中断 |
| 构建环境 | `.claude-work/env.sh` | `mvnx`/`mvncompile`/`mvntest`/`run-test-mode` |
| 会话 UUID 映射 | `.claude-work/session-ids.txt` | 确定性会话 ID |
| 历史提示词 | `.claude-work/prompts/s0.txt` … `s16.txt` | 提示词范式参考 |
| 字符串盘点 | `.claude-work/i18n-inventory.py` | 复跑 I1 验收 |
| 缓存检查 | `.claude-work/inspect-cache.ps1` | 独立验证预览缓存 |
| 端到端验证 | `.claude-work/e2e-verify.ps1` | 种样例 + 跑真实程序 |
| native 元数据合并 | `.claude-work/merge-native-metadata.py`、`merge-i18n-native-metadata.py` | agent 采集后合并去重 |
| tracing agent 驱动 | `.claude-work/probe/CacheJacksonAgentDriver.java` | 反射采集范式 |
| 剩余中文归因 | `.claude-work/verify-enclosing.py` | 包围语句分析 |

---

## 八、标准流程（每次外包一个会话）

```
1. 确认完全权限（pwsh hello 通）
2. 写提示词到 .claude-work/prompts/sNN.txt（按 §四 的 7 段结构）
3. 从 session-ids.txt 取该会话 UUID
4. 后台运行：
   node .claude-work/drive-claude.js --prompt .claude-work/prompts/sNN.txt \
        --session <uuid> --label sNN --budget <N> --cwd <仓库根>
5. 等待结束（看 job 状态，不看 stdout 大小）
6. **独立验证**（§五 的手段）
7. 读 docs/handoff/HNN.md，核对是否与独立验证一致
8. 不一致 → 深查；一致 → 进入下一会话
```

### 成本参考（本项目实测）

| 会话类型 | 耗时 | 预算设定 |
|---|---|---|
| 纯盘点/验证（读多改少） | 5–12 分钟 | $8–10 |
| 中等改动（单文件或少量文件） | 6–10 分钟 | $8–12 |
| 大批量外置/多子任务（如 160 处替换 + 3 次提交） | 10–25 分钟 | $20–22 |

**注意**：`--max-budget-usd` 是硬上限，触顶会中断。宁可给足（本项目 17 个会话无一触顶）。
