# DSH 子进程能力与 Claude Code CLI 驱动验证报告

> 验证日期：2026-09-25
> 验证会话：`session-b7fb8fa3-c039-4fde-b13e-875e0e7236dc`（工作区 `D:\JAVA\mht-resource-extractor`）
> 验证时权限：文件策略 `danger-full-access` + 审批策略 `never`
> 状态：**全部通过**（结论与可复现步骤见下）

本文件是那次验证的**持久化成果**。会话历史本身以 zstd 压缩存放在
`~/.dsh/sessions/--D-JAVA-mht-resource-extractor--/<session-id>/session.v4.jsonl.zstd`，
不适合直接检索，因此关键结论固化到这里。

---

## 1. 环境事实（实测）

| 项 | 值 |
|---|---|
| DSH_HOME | `C:\Users\Admin\.dsh` |
| DSH_PROFILE | `desktop`（profile 目录 `~/.dsh/profiles/desktop`） |
| DSH_WEB_URL | `http://127.0.0.1:19387` |
| 操作系统 | Windows 11 (10.0.22631.3737) |
| Shell 实际版本 | **Windows PowerShell 5.1.22621.3672**（`C:\windows\System32\WindowsPowerShell\v1.0\powershell.exe`） |
| pwsh 7 | **未安装**（`pwsh.exe` → `ENOENT`） |
| Node | `D:\New Folder\node.exe` — v24.19.0 |
| Claude Code | `2.1.153` |
| Claude 可执行文件 | **原生二进制** `%APPDATA%\npm\node_modules\@anthropic-ai\claude-code\bin\claude.exe`（235,564,192 字节） |
| `ANTHROPIC_API_KEY` | 未设置（认证来自 `~/.claude/`） |

---

## 2. 结论摘要

修复前，本机 DSH 会话中所有子进程启动均失败：

```
pwsh -Command "hello"   →   [exit code: 3221225794]   即 0xC0000142 STATUS_DLL_INIT_FAILED
```

**根因**：`workspace-write` / `read-only` 模式下 DSH 的 Windows ACL 沙箱
（`dsh-sandbox-windows-acl`）以**写受限 + Low 完整性令牌**启动子进程。受限令牌下
`powershell.exe` 初始化期加载 DLL 被拒，于是进程在能执行任何代码之前就死掉。
DSH 自身文档原文（`app.asar` 内于 `resources\app.asar:842367` 附近）：

> 保活组（登录 SID + Everyone）在两种模式下都存在：**没有它，早期 DLL 初始化会以
> `0xC0000142` 死亡**、CNG 会让 pwsh 以 `0xE0434352` 崩溃。

**修复**：把会话权限切到**完全权限 / `danger-full-access`**，即不再使用受限令牌。
切换后立刻恢复，无需重启、无需插件。

---

## 3. 能力验证矩阵（全部通过）

| 能力 | 结果 | 实测证据 |
|---|---|---|
| 启动 pwsh / cmd | 通过 | `0xC0000142` 消失，`$ExecutionContext.SessionState.LanguageMode` = `FullLanguage` |
| 启动任意可执行文件 | 通过 | `cmd.exe /c ver` → `Microsoft Windows [Version 10.0.22631.3737]` |
| 启动 Claude Code 进程 | 通过 | `claude --version` → `2.1.153 (Claude Code)`，退出码 0 |
| 向子进程 stdin 写数据 | 通过 | 提示词经 stdin（非 argv）送达 → 模型返回 `PONG` |
| 读取子进程 stdout | 通过 | `--output-format json` 返回可解析结构体，含 `session_id`、`total_cost_usd` |
| 等待 / 轮询增量输出 | 通过 | 3 次 `TICK` 于 +418ms / +718ms / +1018ms 到达 |
| stdin EOF 语义 | 通过 | 关闭 stdin 后子进程立即以 `EOF-GOT:"line1\nline2\n"` 结束 |
| UTF-8 中文保真 | 通过 | `数据安全-中文测试-回车换行` 经 DSH→子进程 stdin 往返零损坏 |
| argv 保真（含空格） | 通过 | `["a b","c"]` 完整保留 |

### 各 shell 的中文 stdin 实测

| 通道 | 中文结果 |
|---|---|
| Node（读原始字节，UTF-8 解码） | ✅ 完好 |
| `cmd.exe /u`（UTF-16LE） | ✅ 完好 |
| **`powershell.exe`（PS 5.1）脚本内 `[Console]::In`** | ❌ 乱码 `鏁版嵁瀹夊叏` |
| `pwsh.exe` | — 不存在 |

---

## 4. 推荐调用形态（关键结论）

`claude` 是**原生 exe**，不是 Node 脚本。因此**不要**用 `shell: true`——那会触发
Node 的 `DEP0190`（参数未转义，可导致注入），而且完全不必要。

```js
const { spawn } = require('child_process');
const os = require('os');
const path = require('path');

const CLAUDE_EXE = path.join(
  os.homedir(), 'AppData', 'Roaming', 'npm',
  'node_modules', '@anthropic-ai', 'claude-code', 'bin', 'claude.exe'
);

// 一次性任务：提示词走 stdin，stdout 可流式轮询
const child = spawn(CLAUDE_EXE, ['--print'], {
  cwd: workDir,                       // 用临时目录可避开项目指令文件影响
  stdio: ['pipe', 'pipe', 'pipe'],    // 全管道，可写可读可轮询
});
child.stdin.write(prompt, 'utf8');
child.stdin.end();                    // 必须 end()，否则 --print 一直等
child.stdout.on('data', (chunk) => { /* 增量处理，实现轮询 */ });
child.stderr.on('data', (chunk) => { /* 必须消费，否则可能阻塞 */ });
child.on('close', (code) => { /* code === 0 为成功 */ });
```

结构化输出（便于程序解析）：

```js
spawn(CLAUDE_EXE, ['--print', '--output-format', 'json'], { ... });
// 返回 { type:"result", subtype:"success", result:"...", session_id:"...",
//         duration_ms, num_turns, total_cost_usd, usage:{...} }
```

### 实测性能/成本（供容量规划）

| 调用 | 首字节 | 总耗时 | 备注 |
|---|---|---|---|
| `--print`（回 `PONG`） | 3395 ms | 3471 ms | |
| `--print` 直连原生 exe（回 `NATIVE`） | 3904 ms | 3940 ms | stderr 全空、无警告 |
| `--print --output-format json`（答 `2+2`） | 7188 ms | 7229 ms | **24413 输入 token / $0.1225** |

> `--print` 是**单轮无状态**的。多轮续跑需用返回的 `session_id` 配 `--resume`，此组合尚未实测。

---

## 5. 踩坑清单（实测，非推测）

1. **DSH 传给 pwsh 的 stdin 是空的**（实测 `stdinBytes: 0`）。因此
   `& $node x.js` 后面靠管道喂数据给 Node 是不通的。脚本必须自包含，或把输入写成文件 / 走 argv。
2. **`pwsh.exe` 不存在**；工具描述里的 `pwsh -Command` 实际由 Windows PowerShell 5.1 执行。
3. **PS 5.1 经 stdin 传中文会乱码**（`数据安全` → `鏁版嵁瀹夊叏`）。
4. **`Set-Content -Encoding UTF8` 会写 BOM**，Node 直接报 `SyntaxError`。要无 BOM 须用：
   ```powershell
   [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
   ```
5. **argv 传中文是完好的**（`argvExact: true`）——DSH 把脚本写入文件再执行，不走 stdin。
   所以中文字面量放脚本里是安全的。
6. 用 `shell: true` 调 `.cmd` shim 会触发 `DEP0190` 并污染 stderr；直连原生 exe 可完全避免。
7. PowerShell 里 `& $node script.js 2>&1 | Out-String` 若子进程写了 stderr，
   `$LASTEXITCODE` 会变成 1（`NativeCommandError`）——退出码 1 未必代表失败。

---

## 6. 数据安全评估

- **验证期间未向外部模型发送任何项目代码或环境变量**；所有提示词均为合成文本。
- **成本敏感**：单次 `--print` 调用即消耗 24413 输入 token（$0.1225）。批量处理 MHT 正文前先估成本。
- **`claude.exe` 未签名且可写**，任何同用户进程都能替换它。留档指纹：
  - SHA256 `8bda00dba0e8b44e67966a07ee32cf23032f7ebb90e77d4f82ab2e39b1118623`
  - 大小 `235564192`，修改时间 `2026-06-02T00:54:30.295Z`
  - 安装清单版本 `2.1.153`，`bin\` 下仅此一个文件
- **prompt injection 是首要风险**：MHT 内的网页内容属不可信数据。若被拼进提示词交给本机
  `claude`，它可能带着本机凭据去调用本机工具。驱动时建议**禁用工具/权限**，并在隔离工作目录内运行。
- **审批 `never` 意味着失去第二道防线**：所有调用不再确认，文件沙箱也不再限制范围。
  日常开发建议回落到「工作区内修改」，仅在确需时切到「完全权限」。
- 本机存在代理/证书拦截迹象（`203.0.113.10` 出现在代理环境变量中）。若 `claude` 的 HTTPS
  被中间人解密，响应内容对代理可见——需要保密性时请先确认代理未做 TLS 检查。

---

## 7. 复现步骤

1. 将会话权限设为 **完全权限**（composer 权限选择器，或 `/permission danger-full-access`）。
2. 验证子进程恢复：
   ```powershell
   pwsh -Command "hello"     # 应正常输出 hello；若仍为 3221225794 则权限未生效
   ```
3. 验证 Claude CLI 可驱动（提示词走 stdin）：
   ```powershell
   $exe = Join-Path $env:APPDATA 'npm\node_modules\@anthropic-ai\claude-code\bin\claude.exe'
   'Reply with exactly one word: PONG' | & $exe --print
   ```
4. 验证双向 stdio 与轮询：用第 4 节的 Node 片段启动一个本地子进程，确认
   `stdin 写入 → stdout 分块到达 → close(code=0)`。

> 注：第 3 步的 `|` 管道由 PowerShell 建立，与第 5 节第 1 条（DSH 不提供 stdin 给 pwsh 本身）不矛盾。

---

## 8. 跨会话可见性说明

| 机制 | 路径 / 用法 | 特点 |
|---|---|---|
| **本报告（推荐）** | 工作区内 `dsh子进程与claude-cli验证报告.md` | 任何会话用 `read` 即可读，可靠、可 diff、可进 git |
| 自动注入的工作区指令 | 仓库内 `CLAUDE.md` / `AGENTS.md`（从项目根到 cwd 的链）、以及用户全局 `~/.dsh/AGENTS.md` | 新会话首次请求自动加载；**只有这两个文件名被识别** |
| 会话原生日志 | `~/.dsh/sessions/<workspace-slug>/<session-id>/session.v4.jsonl.zstd` | zstd 压缩，不便直接读 |
| DSH 全文检索 | `session_search` 工具 / GUI 侧栏搜索 / `/resume` | 依赖 `session_query` 后端（SQLite FTS5）；未挂载时返回 `SESSION_QUERY_SEARCH_DISABLED` |
| 会话记录查看 | GUI 侧栏 → 打开会话 `b7fb8fa3-c039-4fde-b13e-875e0e7236dc` | 仅同工作区；人可读，机器不便 |
