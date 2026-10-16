// Built-in modules only. See backend/IPC.md for launch commands and quantitative gates.
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createInterface } from 'node:readline';
import assert from 'node:assert/strict';
import { performance } from 'node:perf_hooks';
import { exactText, golden, under, utf8 } from './path-assertions.mjs';
import { isEventSummaryValue } from './client-guard.mjs';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const argv = process.argv.slice(2);
const scenarioIndex = argv.indexOf('--scenario-file');
const scenario = scenarioIndex < 0 ? null : JSON.parse(utf8(argv[scenarioIndex + 1]));
const agentIndex = argv.indexOf('--agent-dir');
const agentDir = agentIndex < 0 ? null : path.resolve(argv[agentIndex + 1]);
if (agentDir) {
  assert.ok(!scenario?.executable, 'Tracing agent requires a JVM backend');
  under(agentDir, 'H:/TEST/mht');
  fs.mkdirSync(agentDir, { recursive: true });
}
const stress = argv.includes('--stress');
const count = scenario ? 1 : stress ? 10000 : 3;
const workIndex = argv.indexOf('--work-dir');
const work = scenario?.work ?? (workIndex < 0 ?
  (stress ? 'H:/TEST/mht/p4-2b-stress' : 'H:/TEST/mht/p4-2b-client') : path.resolve(argv[workIndex + 1]));
const input = scenario?.input ?? path.join(work, 'input');
const output = scenario?.output ?? path.join(work, 'output');
const temp = scenario?.temp ?? 'H:/TEST/temp';
for (const dir of [work, input, output]) under(dir, 'H:/TEST/mht');
if (path.resolve(temp) !== path.resolve('H:/TEST/temp')) under(temp, 'H:/TEST/temp');
fs.mkdirSync(input, { recursive: true });
fs.mkdirSync(output, { recursive: true });
const png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aK1sAAAAASUVORK5CYII=';
const fixture = `<html><meta charset="utf-8"><title>IPC fixture</title><body><p>IPC body sentinel</p><img src="data:image/png;base64,${png}"></body></html>`;
if (!scenario) {
  for (let i = 0; i < count; i++) fs.writeFileSync(path.join(input, `item-${String(i).padStart(5, '0')}.html`), fixture);
  assert.equal(fs.readdirSync(input).filter(name => name.endsWith('.html')).length, count);
}

let cp;
if (!scenario?.executable) {
  const cpIndex = argv.indexOf('--classpath');
  if (cpIndex >= 0) cp = argv[cpIndex + 1];
  else {
    const cpFile = argv.includes('--classpath-file') ? argv[argv.indexOf('--classpath-file') + 1]
      : 'H:/TEST/mht/p4-2b-classpath.txt';
    cp = [path.join(repo, 'target/classes'), fs.readFileSync(cpFile, 'utf8').trim()].join(path.delimiter);
  }
  cp = cp.split(path.delimiter).filter(entry => !/^(javafx-|atlantafx|ikonli)/i.test(path.basename(entry)))
    .map(entry => path.resolve(repo, entry)).join(path.delimiter);
}
const java = path.join(process.env.JAVA_HOME ?? 'D:/install/bellsoft-liberica-vm-full-openjdk25-25.0.3', 'bin/java.exe');
const executable = scenario?.executable ?? java;
const command = [ ...(scenario?.executable ? [] : ['--limit-modules', agentDir ? 'java.se,jdk.unsupported' : 'java.se', '-Xmx512m']),
  ...(agentDir ? ['-agentlib:native-image-agent=config-output-dir=' + agentDir] : []), '-Djava.io.tmpdir=' + temp,
  '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Dlogback.configurationFile=logback-headless.xml',
  ...(scenario?.executable ? [] : ['-cp', cp, 'com.mhtextractor.backend.BackendCli']),
  '--serve', '--locale', scenario?.locale ?? 'en' ];
fs.writeFileSync(path.join(work, 'command.json'), JSON.stringify({ executable, command, cwd: work }, null, 2));
console.log('COMMAND ' + JSON.stringify({ executable, command, cwd: work }));
const transcript = path.join(work, 'stdout.jsonl');
const errFile = path.join(work, 'stderr.txt');
fs.writeFileSync(transcript, '');
fs.writeFileSync(errFile, '');
const requestsFile = path.join(work, 'stdin.jsonl');
fs.writeFileSync(requestsFile, '');
const child = spawn(executable, command, { cwd: work, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
const closed = once(child, 'close');
const frames = [];
const eventCounts = {};
const pending = new Map();
let nextId = 0;
let fatal;
let stderrBytes = 0;
let maxFrameBytes = 0;
let peakRequestMs = 0;
let peakPageMs = 0;
let resolveReady;
let rejectReady;
const ready = new Promise((resolve, reject) => { resolveReady = resolve; rejectReady = reject; });
function fail(error) {
  fatal ??= error;
  rejectReady(error);
  for (const entry of pending.values()) entry.reject(error);
  pending.clear();
  child.kill();
}
child.on('error', fail);
child.on('close', (code, signal) => {
  if (pending.size || !frames.some(frame => frame.event === 'ready')) fail(new Error(
    `EOF before workflow completed: exit=${code} signal=${signal}\n${fs.readFileSync(errFile, 'utf8')}`));
});
child.stderr.on('data', bytes => {
  stderrBytes += bytes.length;
  fs.appendFileSync(errFile, bytes); // stderr is never parsed as protocol.
});
// Preserve actual wire bytes, including deliberately invalid encodings in control runs.
child.stdout.on('data', bytes => fs.appendFileSync(transcript, bytes));
const lines = createInterface({ input: child.stdout, crlfDelay: Infinity });
lines.on('line', line => {
  try {
    maxFrameBytes = Math.max(maxFrameBytes, Buffer.byteLength(line));
    const frame = JSON.parse(line);
    assert.equal(frame.v, 1);
    assert.ok(['event', 'response', 'error'].includes(frame.type));
    // IPC.md:342/496/508 permits inline workspacePage results of up to 256 expanded ItemVms
    // plus snapshot references. Responses get 256 KiB; event summaries/references keep 64 KiB.
    const maxBytes = (frame.type === 'response' ? 256 : 64) * 1024;
    assert.ok(Buffer.byteLength(line) <= maxBytes, `${frame.type} frames must remain <=${maxBytes / 1024} KiB`);
    frames.push(frame);
    assert.ok(frames.length <= 256, 'Total protocol frames must remain <=256 for the 10k workflow');
    if (frame.type === 'event') {
      eventCounts[frame.event] = (eventCounts[frame.event] ?? 0) + 1;
      assert.ok(!frame.data?.items && !frame.data?.topLevelItems && !frame.data?.paths && !frame.data?.files,
        'Events must not push full item/path/result arrays');
      if (frame.event !== 'ready') assert.ok(Object.values(frame.data ?? {}).every(isEventSummaryValue),
        'Activity events must contain only scalar summaries, exact file references, or flat string maps');
      if (frame.event === 'ready') resolveReady(frame);
    } else {
      const entry = pending.get(frame.id);
      assert.ok(entry, `Unexpected response id: ${frame.id}`);
      pending.delete(frame.id);
      entry.resolve(frame);
    }
  } catch (error) { fail(error); }
});
async function request(method, params, errorCode) {
  const id = String(++nextId);
  const started = performance.now();
  const promise = new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
  const line = JSON.stringify({ v: 1, type: 'request', id, method, params }) + '\n';
  fs.appendFileSync(requestsFile, line);
  child.stdin.write(line);
  const frame = await promise;
  const elapsed = performance.now() - started;
  peakRequestMs = Math.max(peakRequestMs, elapsed);
  if (method === 'workspacePage') peakPageMs = Math.max(peakPageMs, elapsed);
  if (errorCode) {
    assert.equal(frame.type, 'error');
    assert.equal(frame.error.code, errorCode);
  } else assert.equal(frame.type, 'response', JSON.stringify(frame));
  return frame.result;
}
function readRef(ref) {
  under(ref.path, temp);
  under(fs.realpathSync(ref.path), fs.realpathSync(temp));
  const bytes = fs.readFileSync(ref.path);
  assert.equal(bytes.length, ref.bytes);
  return bytes;
}
const timeout = setTimeout(() => fail(new Error('Workflow exceeded 180 seconds')), 180000);
const started = performance.now();
let cleanupRoot;
try {
  const greeting = await ready;
  assert.ok(greeting.data.methods.includes('previewFiles'));
  assert.ok(greeting.data.methods.includes('hello'), 'Backend must support startup handshake');
  // Expected identity comes from the local UI/build, never from the untrusted backend greeting.
  const identityFile = argv.indexOf('--identity-file');
  const identityText = utf8(identityFile < 0 ? path.join(repo, 'target/classes/backend-build.properties') : argv[identityFile + 1]);
  const identityProperties = Object.fromEntries(identityText.split(/\r?\n/).filter(line => line.includes('='))
    .map(line => { const i = line.indexOf('='); return [line.slice(0, i).trim(), line.slice(i + 1).trim()]; }));
  const expectedIdentity = { protocolVersion: 1, version: identityProperties.version, buildId: identityProperties.buildId };
  assert.ok(expectedIdentity.version && expectedIdentity.buildId && !identityText.includes('${'), 'Filtered build identity required');
  const welcome = await request('hello', expectedIdentity);
  assert.equal(welcome.kind, 'welcome');
  for (const [key, value] of Object.entries(expectedIdentity)) assert.equal(welcome[key], value, `Handshake ${key} mismatch`);
  assert.ok(welcome.capabilities.includes('workspacePage'));
  assert.ok(greeting.data.methods.includes('confirmAnswer'));
  assert.ok(welcome.capabilities.includes('confirmationRoundTrip'));
  assert.equal(welcome.confirmationRoundTrip, true);
  console.log('HANDSHAKE ' + JSON.stringify(welcome));
  const imported = await request('importPaths', { paths: [input] });
  assert.equal(imported.count, count);
  assert.equal(imported.failed, false);
  assert.ok(Object.values(imported).every(value => value === null || typeof value !== 'object'),
    'Import response must contain only scalar summaries, with no full item list');
  assert.equal(frames.filter(frame => frame.type === 'response').length, 2,
    'Import must not trigger an unsolicited workspace response');
  assert.equal(eventCounts.importFinished, 1);
  const first = await request('workspacePage', { offset: 0, limit: 256 });
  assert.equal(first.total, count);
  assert.equal(first.items.length, Math.min(256, count));
  assert.ok(first.items.length < count || !stress);
  const ids = first.items.map(item => item.id);
  for (let offset = 256; offset < count; offset += 256) {
    const page = await request('workspacePage', { offset, limit: 256 });
    assert.equal(page.offset, offset);
    assert.equal(page.total, count);
    assert.ok(page.items.length <= 256);
    ids.push(...page.items.map(item => item.id));
  }
  assert.equal(ids.length, count);
  assert.equal(new Set(ids).size, count);
  if (scenario) assert.deepEqual(ids, [path.resolve(input, scenario.filename)], 'UTF8_PATH_ROUNDTRIP: input id');
  await request('group', { paths: ids, namePrefix: scenario?.groupPrefix ?? 'IPC batch' });
  await request('setParams', { imageMode: scenario?.imageMode ?? false, config: scenario?.config ?? { separateFiles: false,
    enableCustomTemplate: true, startTemplate: 'BEGIN\n', endTemplate: '\nEND' } });
  // Bulk transport and allowlist exercises run in both smoke and stress modes.
  await request('previewFiles', { path: path.join(repo, 'CLAUDE.md') }, 'INVALID_PARAMS');
  await request('importPaths', { paths: [path.join(repo, 'CLAUDE.md')] }, 'INVALID_PARAMS');
  await request('fileInfo', { path: ids[0] }, 'INVALID_PARAMS');
  await request('workspacePage', { offset: 0, limit: 257 }, 'INVALID_PARAMS');
  const preview = await request('previewFiles', { path: ids[0] });
  cleanupRoot = path.dirname(preview.manifest.path);
  const manifest = JSON.parse(readRef(preview.manifest));
  if (!scenario || !scenario.imageMode) assert.ok(readRef(manifest.text).toString('utf8').includes(scenario?.textSentinel ?? 'IPC body sentinel'));
  else readRef(manifest.text);
  assert.ok(readRef(manifest.html).toString('utf8').includes('<html>'));
  assert.equal(preview.imageCount, scenario?.imageCount ?? 1);
  assert.equal(manifest.thumbnails.length, scenario?.imageCount ?? 1);
  if (manifest.thumbnails.length) assert.equal(readRef(manifest.thumbnails[0]).subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
  await request('fileInfo', { path: preview.manifest.path });
  const extracted = await request('extract', { checkedOnly: false, outputFolder: output });
  assert.equal(path.dirname(path.resolve(extracted.outputFolder)), path.resolve(output),
    'DIRECTORY_GUARD: session folder must be an immediate child of the requested root');
  under(fs.realpathSync(extracted.outputFolder), fs.realpathSync(output));
  assert.equal(extracted.end, 'SUCCEEDED');
  assert.equal(extracted.count, count);
  assert.equal(extracted.failCount, 0);
  assert.equal(extracted.skipCount, 0);
  const results = JSON.parse(readRef(extracted.resultsFile));
  assert.equal(results.length, count);
  assert.ok(results.every(result => ['SUCCESS', 'SUCCESS_WARNED'].includes(result.outcome)));
  if (scenario) {
    assert.equal(results[0].path, ids[0], 'UTF8_PATH_ROUNDTRIP: result file');
    fs.writeFileSync(path.join(work, 'bulk-evidence.json'), JSON.stringify({ preview, manifest, extracted, results }, null, 2));
  }
  const products = [];
  function collect(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) collect(full);
      else products.push(full);
    }
  }
  collect(extracted.outputFolder);
  const textOutputs = products.filter(file => file.endsWith('.txt'));
  if (scenario) {
    under(fs.realpathSync(output), 'H:/TEST/mht');
    const actual = scenario.expectedText
      ? exactText(extracted.outputFolder, scenario.expectedText)
      : golden(extracted.outputFolder, scenario.baseline, scenario.mappings);
    console.log((scenario.expectedText ? 'TEXT_EXACT_MATCH ' : 'GOLDEN_SHA256_MATCH ') + JSON.stringify(actual));
  } else {
    assert.equal(textOutputs.length, 1);
    const content = fs.readFileSync(textOutputs[0], 'utf8');
    assert.equal(content.split('IPC body sentinel').length - 1, count);
    assert.equal(content.split('BEGIN').length - 1, count);
    assert.equal(content.split('END').length - 1, count);
  }
  assert.equal(eventCounts.extractionTerminal, 1);
  const progress = frames.filter(frame => frame.event === 'extractionProgress').map(frame => frame.data.overall);
  assert.ok(progress.length > 0 && progress.length <= 101, 'Progress must be coalesced to <=101 frames');
  assert.ok(progress.every((value, i) => value >= 0 && value <= 1 && (!i || value >= progress[i - 1])));
  const metrics = await request('metrics', {});
  assert.equal(metrics.sentProgress, progress.length);
  if (stress) assert.ok(metrics.rawProgress > metrics.sentProgress * 10, '10k progress callbacks must be merged');
  assert.ok(metrics.callbackQueuePeak <= 256);
  assert.ok(metrics.heapPoolPeakBytes < 768 * 1024 * 1024, 'Sum of heap pool peaks must be <768 MiB');
  assert.ok(peakPageMs < 5000, 'Peak page response must be <5s');
  assert.ok(peakRequestMs < 120000, 'Peak command response must be <120s');
  await request('releaseFiles', { paths: [preview.manifest.path, manifest.text.path, manifest.html.path, extracted.resultsFile.path] });
  assert.equal(fs.existsSync(preview.manifest.path), false);
  await request('fileInfo', { path: preview.manifest.path }, 'INVALID_PARAMS');
  child.stdin.end(); // EOF is the normal shutdown protocol.
  const [exitCode, signal] = await closed;
  if (fatal) throw fatal;
  assert.equal(signal, null);
  assert.equal(exitCode, 0);
  assert.equal(fs.existsSync(cleanupRoot), false, 'EOF must remove the session bulk/cache directory');
  utf8(transcript);
  utf8(errFile);
  const summary = { mode: stress ? 'stress' : 'smoke', inputFiles: count, totalFrames: frames.length,
    maxFrameBytes, progressFrames: progress.length, ...metrics, peakRequestMs: +peakRequestMs.toFixed(2),
    peakPageMs: +peakPageMs.toFixed(2), workflowMs: +(performance.now() - started).toFixed(2),
    eventCounts, outputFiles: products.length, outputBytes: products.reduce((n, file) => n + fs.statSync(file).size, 0),
    stderrBytes, exitCode, sessionCleaned: true };
  fs.writeFileSync(path.join(work, 'summary.json'), JSON.stringify(summary, null, 2));
  console.log('STDOUT_PROTOCOL_SUMMARY ' + JSON.stringify(summary));
  console.log('STDERR_SUMMARY ' + JSON.stringify({ bytes: stderrBytes,
    lines: fs.readFileSync(errFile, 'utf8').trim().split(/\r?\n/).filter(Boolean) }));
  const log = path.join(work, 'logs/backend.log');
  console.log('BACKEND_LOG_SUMMARY ' + JSON.stringify({ file: log,
    tail: fs.existsSync(log) ? fs.readFileSync(log, 'utf8').trim().split(/\r?\n/).slice(-4) : [] }));
  console.log('CLIENT_EXIT=0');
} finally {
  clearTimeout(timeout);
  lines.close();
  if (child.exitCode === null) { child.kill(); await closed; }
}
