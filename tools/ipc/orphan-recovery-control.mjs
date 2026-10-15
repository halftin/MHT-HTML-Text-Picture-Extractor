// Reproducible destructive-predicate control. Run with no other Maven/GUI/native jobs.
// Evidence and runtime/test data use H. No external modules.
// Override MHT_ORPHAN_EVIDENCE_ROOT / MHT_ORPHAN_TEMP_ROOT / MAVEN_USER_HOME /
// MHT_ORPHAN_MAVEN_REPO / JAVA_HOME when replaying on another Windows machine.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const source = path.join(repo, 'src/main/java/com/mhtextractor/backend/IpcFiles.java');
const evidence = process.env.MHT_ORPHAN_EVIDENCE_ROOT ?? 'H:/TEST/mht/p5a-9-r/control';
fs.mkdirSync(evidence, { recursive: true });
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const original = fs.readFileSync(source);
const originalHash = sha(original);
fs.writeFileSync(path.join(evidence, 'IpcFiles.original.java'), original);
const raw = original.toString('utf8');
const newline = raw.includes('\r\n') ? '\r\n' : '\n';
const lines = raw.split(newline);
const start = lines.findIndex(line => line.includes('Path lock = directory.resolve(SESSION_LOCK);'));
const end = lines.findIndex((line, index) => index > start && line.trim() === 'recovered++;');
assert.ok(start >= 0 && end > start, 'mutation anchors must exist');
const mutated = Buffer.from([
  ...lines.slice(0, start),
  '                    // RED CONTROL: deliberately ignore owner locks and delete every candidate.',
  '                    deleteTree(directory, false);',
  ...lines.slice(end),
].join(newline));
const mutatedHash = sha(mutated);
const temporaryRoot = process.env.MHT_ORPHAN_TEMP_ROOT ?? 'H:\\TEST\\temp';
const mavenHome = process.env.MAVEN_USER_HOME ?? 'H:\\TEST\\m2';
const repository = process.env.MHT_ORPHAN_MAVEN_REPO ?? path.join(mavenHome, 'repository');
const environment = {
  ...process.env,
  JAVA_HOME: process.env.JAVA_HOME ?? 'D:\\install\\bellsoft-liberica-vm-full-openjdk25-25.0.3',
  JAVA_TOOL_OPTIONS: `-Djava.io.tmpdir="${temporaryRoot}" -Dmht.gui.logs.root="${path.join(evidence, 'gui-logs')}"`,
  MAVEN_USER_HOME: mavenHome,
};
const metadata = { originalHash, mutatedHash, commands: [] };
const baseCommand = ['mvnw.cmd', '-Dmaven.repo.local=' + repository];
function checkedWrite(expectedHash, bytes, phase) {
  const actual = sha(fs.readFileSync(source));
  console.log(`${phase} PRE_TOUCH_SHA256=${actual} EXPECTED=${expectedHash}`);
  assert.equal(actual, expectedHash, 'source changed concurrently; refusing overwrite');
  fs.writeFileSync(source, bytes);
  console.log(`${phase} POST_TOUCH_SHA256=${sha(fs.readFileSync(source))}`);
}
function run(label) {
  const selected = label === 'RED'
    ? 'BackendOrphanRecoveryTest#concurrentServersPreserveLiveSessionAndItsFiles'
    : 'IpcOrphanRecoveryTest,BackendOrphanRecoveryTest,BackendEventsTest#cleanupTimeoutIsBoundedAndFailureDoesNotEscapeSessionClose';
  const command = [...baseCommand, '-Dtest=' + selected, 'test'];
  console.log(`${label} COMMAND=${JSON.stringify(['cmd.exe', '/d', '/c', ...command])}`);
  const result = spawnSync('cmd.exe', ['/d', '/c', ...command], {
    cwd: repo, env: environment, windowsHide: true, maxBuffer: 32 * 1024 * 1024,
  });
  fs.writeFileSync(path.join(evidence, `${label}.stdout.log`), result.stdout ?? '');
  fs.writeFileSync(path.join(evidence, `${label}.stderr.log`), result.stderr ?? '');
  metadata.commands.push({ label, command, status: result.status, signal: result.signal });
  const stdout = result.stdout?.toString('utf8') ?? '';
  console.log(stdout);
  console.log(result.stderr?.toString('utf8') ?? '');
  console.log(`${label} EXIT_CODE=${result.status}`);
  if (result.error) throw result.error;
  return { ...result, stdout };
}
let injected = false;
try {
  checkedWrite(originalHash, mutated, 'INJECT');
  injected = true;
  const red = run('RED');
  assert.notEqual(red.status, 0, 'blind-delete predicate falsely passed concurrent control');
  assert.match(red.stdout, /Later backend deleted live session (directory|contents)/,
    'red must be the live-session assertion, never a compile/runtime setup failure');
  assert.doesNotMatch(red.stdout, /COMPILATION ERROR/);
} finally {
  if (injected) checkedWrite(mutatedHash, original, 'RESTORE');
  fs.writeFileSync(path.join(evidence, 'commands.json'), JSON.stringify(metadata, null, 2));
}
const green = run('GREEN');
fs.writeFileSync(path.join(evidence, 'commands.json'), JSON.stringify(metadata, null, 2));
assert.equal(green.status, 0, 'restored source must pass concurrent control');
assert.match(green.stdout, /ORPHAN CONCURRENCY GREEN/);
assert.equal(sha(fs.readFileSync(source)), originalHash, 'restoration hash mismatch');
console.log('RED_ASSERTION_THEN_GREEN=PASS; SOURCE_RESTORED=YES');
