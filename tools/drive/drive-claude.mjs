#!/usr/bin/env node
/**
 * drive-claude.mjs — 驱动本机 Claude Code CLI 执行单个会话（DSH 统筹方使用）
 *
 * 这是**权威实现**（随仓库分发给打包机/后续会话）。本机驱动路径曾写死为
 * %APPDATA%\npm\...\claude.exe，而实际安装位置是 %USERPROFILE%\.local\bin\claude.exe
 * （2026-10-03 实测：APPDATA 路径 MISSING，where claude -> C:\Users\Admin\.local\bin\claude.exe）。
 * 因此这里做**多候选解析**：CLAUDE_EXE 环境变量 → %USERPROFILE%\.local\bin → APPDATA npm → where.exe。
 *
 * 用法:
 *   node tools/drive/drive-claude.mjs --prompt <file> [--session <uuid>] [--resume]
 *                                     [--budget 8] [--label <name>] [--cwd <dir>]
 *
 * 设计要点（沿用既有实现）:
 *  - claude.exe 是原生 exe，直连，不用 shell:true / .cmd shim
 *  - 提示词走 stdin（Node 写原始 UTF-8 字节，规避 PS 5.1 中文转码问题）
 *  - stdout/stderr 全部落盘到 <cwd>/.claude-work/logs/<label>-<stamp>.log / .err.log
 *    并写 .meta.json（exitCode / elapsedSec / 字节数），供 DSH 轮询与独立核对
 *  - 每收到一段 stdout 就往父进程打一行心跳 [out +NB total=NB]
 */
import { spawn, execSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

function parseArgv(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith('--')) continue;
    const key = a.slice(2);
    if (key === 'resume') { out.resume = true; continue; }
    const val = argv[i + 1];
    if (val === undefined || val.startsWith('--')) { out[key] = true; }
    else { out[key] = val; i++; }
  }
  return out;
}

function resolveClaudeExe() {
  const candidates = [
    process.env.CLAUDE_EXE,
    path.join(os.homedir(), '.local', 'bin', 'claude.exe'),
    'C:\\Users\\Admin\\.local\\bin\\claude.exe',
    path.join(os.homedir(), 'AppData', 'Roaming', 'npm',
      'node_modules', '@anthropic-ai', 'claude-code', 'bin', 'claude.exe'),
  ];
  for (const c of candidates) {
    if (c && fs.existsSync(c)) return c;
  }
  try {
    const out = execSync('where.exe claude', { encoding: 'utf8', windowsHide: true });
    const hit = out.split(/\r?\n/).map((s) => s.trim())
      .find((p) => /claude\.exe$/i.test(p) && fs.existsSync(p));
    if (hit) return hit;
  } catch (_) { /* where.exe missing or no claude on PATH */ }
  return null;
}

const opt = parseArgv(process.argv.slice(2));
const workDir = opt.cwd || process.cwd();
const label = opt.label || 'session';
const budget = opt.budget || '8';

const CLAUDE_EXE = resolveClaudeExe();
if (!CLAUDE_EXE) {
  console.error('[drive] claude.exe not found (set CLAUDE_EXE, or install to %USERPROFILE%\\.local\\bin)');
  process.exit(2);
}
if (!opt.prompt) { console.error('[drive] --prompt <file> is required'); process.exit(2); }
if (!fs.existsSync(opt.prompt)) { console.error('[drive] prompt not found: ' + opt.prompt); process.exit(2); }

const workRoot = path.join(workDir, '.claude-work');
const logDir = path.join(workRoot, 'logs');
fs.mkdirSync(logDir, { recursive: true });

const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
const logFile = path.join(logDir, label + '-' + stamp + '.log');
const errFile = path.join(logDir, label + '-' + stamp + '.err.log');
const metaFile = path.join(logDir, label + '-' + stamp + '.meta.json');

const settings = path.join(workRoot, 'settings.workflow.json');

const claudeArgs = ['--print', '--dangerously-skip-permissions',
  '--settings', settings, '--max-budget-usd', String(budget)];

if (opt.session) { claudeArgs.push('--session-id', opt.session); }
if (opt.resume && opt.session) { claudeArgs.push('--resume', opt.session); }

const promptText = fs.readFileSync(opt.prompt, 'utf8');

const meta = {
  label, prompt: opt.prompt, sessionId: opt.session || null, resume: !!opt.resume,
  budgetUsd: Number(budget), cwd: workDir, start: new Date().toISOString(),
  claudeExe: CLAUDE_EXE, args: claudeArgs, log: logFile,
  promptBytes: Buffer.byteLength(promptText, 'utf8'),
};
fs.writeFileSync(metaFile, JSON.stringify(meta, null, 2), 'utf8');

console.log('[drive] label=' + label + ' session=' + (opt.session || '(none)') + ' budget=$' + budget);
console.log('[drive] exe=' + CLAUDE_EXE);
console.log('[drive] prompt=' + opt.prompt + ' (' + meta.promptBytes + ' bytes)');
console.log('[drive] log=' + logFile);
console.log('[drive] starting...');

const t0 = Date.now();
const child = spawn(CLAUDE_EXE, claudeArgs, {
  cwd: workDir,
  stdio: ['pipe', 'pipe', 'pipe'],
  windowsHide: true,
});

const outStream = fs.createWriteStream(logFile, { encoding: 'utf8' });
const errStream = fs.createWriteStream(errFile, { encoding: 'utf8' });

let outBytes = 0, errBytes = 0;

child.stdout.on('data', (chunk) => {
  outBytes += chunk.length;
  outStream.write(chunk);
  process.stdout.write('[out +' + chunk.length + 'B total=' + outBytes + 'B]\n');
});

child.stderr.on('data', (chunk) => {
  errBytes += chunk.length;
  errStream.write(chunk);
  process.stderr.write(chunk);
});

child.stdin.write(promptText, 'utf8');
child.stdin.end();

child.on('error', (e) => {
  errStream.write('\n[drive] spawn error: ' + e.message + '\n');
  meta.error = e.message;
  fs.writeFileSync(metaFile, JSON.stringify(meta, null, 2), 'utf8');
  process.exit(3);
});

child.on('close', (code, signal) => {
  outStream.end();
  errStream.end();
  const elapsed = Math.round((Date.now() - t0) / 100) / 10;
  meta.exitCode = code;
  meta.signal = signal || null;
  meta.elapsedSec = elapsed;
  meta.stdoutBytes = outBytes;
  meta.stderrBytes = errBytes;
  meta.end = new Date().toISOString();
  fs.writeFileSync(metaFile, JSON.stringify(meta, null, 2), 'utf8');

  console.log('[drive] exit=' + code + ' signal=' + (signal || 'none') +
    ' elapsed=' + elapsed + 's out=' + outBytes + 'B err=' + errBytes + 'B');
  console.log('[drive] log=' + logFile);
  console.log('[drive] meta=' + metaFile);
  if (errBytes > 0) {
    console.log('[drive] stderr tail:');
    try {
      const t = fs.readFileSync(errFile, 'utf8').split('\n').slice(-15).join('\n');
      console.log(t);
    } catch (_) { /* ignore */ }
  }
  process.exit(code === 0 ? 0 : 1);
});
