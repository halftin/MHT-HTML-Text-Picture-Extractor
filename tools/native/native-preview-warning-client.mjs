// Real BackendCli IPC preview; built-in Node modules only. Exit status comes from child close.
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { once } from 'node:events';

const [executable, outDir, tempDir] = process.argv.slice(2);
assert.ok(executable && outDir && tempDir, 'Usage: client <backend.exe> <fresh-run-dir> <temp-dir>');
for (const dir of [outDir, tempDir]) assert.ok(path.isAbsolute(dir));
assert.ok(!fs.existsSync(outDir), 'Use a fresh run directory');
fs.mkdirSync(outDir, {recursive: true});
// Isolate the cache root so cleanup assertions cannot read an unrelated or older session.
const runtimeTemp = path.join(tempDir, path.basename(path.dirname(outDir)) + '-' + path.basename(outDir) + '-warning-runtime');
assert.ok(!fs.existsSync(runtimeTemp), 'Use a fresh runtime temp directory');
fs.mkdirSync(runtimeTemp, {recursive: true});
const source = path.join(outDir, 'warning.html');
fs.writeFileSync(source, '<html><meta charset="utf-8"><title>Warning preview</title><body><p>Body outside region</p></body></html>');
const command = ['-Djava.io.tmpdir=' + runtimeTemp, '-Dlogback.configurationFile=logback-headless.xml', '--serve', '--locale', 'en'];
console.log('COMMAND ' + JSON.stringify({executable, command, cwd: outDir}));
fs.writeFileSync(path.join(outDir, 'command.json'), JSON.stringify({executable, command, cwd: outDir}, null, 2));
const child = spawn(executable, command, {cwd: outDir, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe']});
const closed = once(child, 'close');
const requests = path.join(outDir, 'stdin.jsonl');
fs.writeFileSync(requests, '');
let stderr = '', stdout = '', readyResolve, readyReject;
let failure, nextId = 0;
const pending = new Map();
const ready = new Promise((resolve, reject) => {readyResolve = resolve; readyReject = reject;});
function fail(error) {
  failure ??= error;
  readyReject(error);
  for (const entry of pending.values()) entry.reject(error);
  pending.clear();
}
child.on('error', fail);
child.stdin.on('error', fail);
child.on('close', (code, signal) => {
  fs.writeFileSync(path.join(outDir, 'backend.exit.json'), JSON.stringify({code, signal}));
  if (pending.size) fail(new Error('Backend EOF while request pending: ' + code));
});
child.stdout.on('data', b => {stdout += b; fs.appendFileSync(path.join(outDir, 'stdout.jsonl'), b);});
child.stderr.on('data', b => {stderr += b; fs.appendFileSync(path.join(outDir, 'stderr.log'), b);});
createInterface({input: child.stdout, crlfDelay: Infinity}).on('line', line => {
  try {
    const frame = JSON.parse(line);
    if (frame.event === 'ready') readyResolve(frame);
    if (pending.has(frame.id)) {
      const entry = pending.get(frame.id);
      pending.delete(frame.id);
      entry.resolve(frame);
    }
  } catch (e) {fail(e);}
});
function call(method, params) {
  const id = String(++nextId);
  const frame = {v: 1, type: 'request', id, method, params};
  const wire = JSON.stringify(frame) + '\n';
  fs.appendFileSync(requests, wire);
  return new Promise((resolve, reject) => {pending.set(id, {resolve, reject}); child.stdin.write(wire);});
}
function walk(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, {withFileTypes: true}).flatMap(e => {
    const file = path.join(dir, e.name);
    return e.isDirectory() ? walk(file) : [file];
  });
}
const timer = setTimeout(() => {fail(new Error('IPC smoke timeout after 60s')); child.kill();}, 60000);
let result = {};
try {
  await ready;
  // Expected identity is local build input, never copied from the peer's ready frame.
  const identityFile = process.argv[5];
  const properties = Object.fromEntries(fs.readFileSync(identityFile, 'utf8').split(/\r?\n/)
    .filter(l => l.includes('=') && !l.startsWith('#')).map(l => {const i = l.indexOf('='); return [l.slice(0, i), l.slice(i + 1)];}));
  assert.equal((await call('hello', {protocolVersion: 1, version: properties.version, buildId: properties.buildId})).type, 'response');
  const imported = await call('importPaths', {paths: [source]});
  assert.equal(imported.result?.count, 1);
  assert.equal(imported.result?.failed, false);
  assert.equal((await call('setParams', {imageMode: true, config: {
    imageRegionExtractEnabled: true, imageRegionStartMarker: 'MISSING_START', imageRegionEndMarker: 'MISSING_END'
  }})).type, 'response');
  const preview = await call('previewFiles', {path: source});
  fs.writeFileSync(path.join(outDir, 'preview-response.json'), JSON.stringify(preview, null, 2));
  assert.equal(preview.type, 'response', 'previewFiles must succeed');
  assert.equal(preview.result.imageCount, 0);
  const metaFiles = walk(runtimeTemp).filter(f => path.basename(f) === 'meta.json');
  assert.equal(metaFiles.length, 1, 'Exactly one freshly written meta.json');
  const metaFile = metaFiles[0];
  const meta = JSON.parse(fs.readFileSync(metaFile, 'utf8'));
  assert.ok(meta.warnings?.some(w => w.key === 'extract.region.r2'), 'Expected real missing-region warning');
  assert.deepEqual(meta.warnings.find(w => w.key === 'extract.region.r2').args, {}, 'R2 warning must serialize its empty args map');
  assert.ok(fs.existsSync(path.join(path.dirname(metaFile), '.complete')), 'Cache write must commit');
  // Check read/write access and rename while the backend is still alive, before EOF cleanup.
  const fd = fs.openSync(metaFile, 'r+');
  fs.closeSync(fd);
  fs.renameSync(metaFile, metaFile + '.handle-check');
  fs.renameSync(metaFile + '.handle-check', metaFile);
  fs.copyFileSync(metaFile, path.join(outDir, 'meta.json'));
  result = {previewSucceeded: true, warningCount: meta.warnings.length, metaFile};
  console.log('[PASS] native preview wrote warnings=' + meta.warnings.length + ' key=extract.region.r2');
} catch (e) {failure ??= e;}
finally {
  child.stdin.end();
  const [code, signal] = await closed;
  clearTimeout(timer);
  console.log('BACKEND_EXIT=' + JSON.stringify({code, signal}));
  console.log('BACKEND_STDOUT_BEGIN\n' + stdout + 'BACKEND_STDOUT_END');
  console.log('BACKEND_STDERR_BEGIN\n' + stderr + 'BACKEND_STDERR_END');
  const logFiles = walk(path.join(outDir, 'logs'));
  for (const file of logFiles) console.log('BACKEND_LOG ' + file + '\n' + fs.readFileSync(file, 'utf8'));
  const remaining = walk(runtimeTemp);
  const remainingEntries = fs.readdirSync(runtimeTemp);
  result.backendExit = code;
  result.remainingTempFiles = remaining;
  result.remainingTempEntries = remainingEntries;
  fs.writeFileSync(path.join(outDir, 'result.json'), JSON.stringify(result, null, 2));
  if (!failure) {
    try {
      assert.equal(code, 0, 'Normal backend EOF must exit 0');
      assert.deepEqual(remaining, [], 'Session must remove cache and IPC files before process exit');
      assert.deepEqual(remainingEntries, [], 'Session directories must also be removed');
      console.log('[PASS] native session cleanup: 0 remaining temp files; meta.json removed');
    } catch (e) {failure = e;}
  }
  if (failure) {console.error(failure.stack); process.exitCode = 1;}
}
