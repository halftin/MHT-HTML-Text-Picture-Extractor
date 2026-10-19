// Second frontend: protocol JSON only, using the established client's transport and leases.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { createIpcClient, encodeRequest, decodeFrame, readFileRef } from './client.mjs';
import { under } from './path-assertions.mjs';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const args = process.argv.slice(2);
function option(name, fallback) {
  const index = args.indexOf(name);
  if (index < 0) return fallback;
  assert.ok(args[index + 1] && !args[index + 1].startsWith('--'), `${name} requires a value`);
  return args[index + 1];
}
function tree(root) {
  const entries = [];
  function walk(dir) {
    for (const item of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, item.name);
      if (item.isDirectory()) walk(full);
      else {
        assert.ok(!/\.(zip|cbz)$/i.test(item.name), 'This byte contract uses unarchived products');
        entries.push({ path: path.relative(root, full).split(path.sep).join('/'), sha256: hash(fs.readFileSync(full)) });
      }
    }
  }
  walk(root);
  return entries.sort((a, b) => a.path < b.path ? -1 : a.path > b.path ? 1 : 0);
}
export function revisionObserver() {
  let revision = -1;
  return frame => {
    if (frame.type === 'error') return; // Error frames have no revision in protocol 2.
    const next = frame.type === 'event' ? frame.data.revision : frame.revision;
    assert.ok(Number.isSafeInteger(next) && next >= 0, 'Exact, safe revision required');
    assert.ok(next >= revision, `Revision regressed: ${next} < ${revision}`);
    if (frame.type === 'event' && frame.event !== 'ready') assert.ok(next > revision, 'Each event consumes a revision');
    revision = next;
  };
}
export function assertExtractionOrder(frames, generation) {
  const events = frames.filter(f => f.type === 'event' && f.data.generation === generation);
  const running = events.filter(f => f.event === 'runningChanged');
  assert.deepEqual(running.map(f => f.data.running), [true, false]);
  const terminal = events.filter(f => f.event === 'extractionTerminal');
  assert.equal(terminal.length, 1);
  assert.equal(terminal[0].data.end, 'SUCCEEDED');
  assert.equal(events.filter(f => f.event === 'extractionResult').length, 0, 'Success carries stats in terminal');
  assert.ok(events.indexOf(running[0]) < events.indexOf(terminal[0]));
  // running=false and terminal belong to the same host callback; their relative order is not specified.
  const progress = events.filter(f => f.event === 'extractionProgress');
  assert.ok(progress.length > 0);
  for (const f of progress) {
    // Async IPC preserves backend callback order; unlike legacy extract it does not coalesce progress.
    assert.ok(f.data.overall >= 0 && f.data.overall <= 1);
    assert.ok(events.indexOf(f) > events.indexOf(running[0]) && events.indexOf(f) < events.indexOf(terminal[0]));
  }
}
function selfTest() {
  const wire = encodeRequest('中文-1', 'collectParams', { values: { text: 'a\n"中' }, imageMode: false });
  assert.equal(wire.split('\n').length, 2);
  assert.deepEqual(JSON.parse(wire).params.values, { text: 'a\n"中' });
  assert.throws(() => encodeRequest('', 'workspace', {}));
  assert.throws(() => decodeFrame('{'));
  assert.throws(() => decodeFrame('{"v":2,"type":"response","id":"1"}'));
  assert.throws(() => decodeFrame('{"v":1,"type":"request","id":"1"}'));
  for (const code of ['BAD_JSON', 'INVALID_REQUEST', 'INVALID_PARAMS', 'UNKNOWN_METHOD', 'INTERNAL_ERROR',
    'HANDSHAKE_FAILED', 'CONFIG_IO_ERROR']) {
    assert.equal(decodeFrame(JSON.stringify({ v: 1, type: 'error', id: '1', error: { code } })).error.code, code);
  }
  assert.equal(decodeFrame('{"v":1,"type":"error","id":null,"error":{"code":"BAD_JSON"}}').id, null);
  assert.throws(() => decodeFrame('{"v":1,"type":"error","id":"1","error":{"code":"MADE_UP"}}'));
  const temp = option('--temp', process.platform === 'win32' ? 'H:/TEST/temp' : os.tmpdir());
  fs.mkdirSync(temp, { recursive: true });
  const dir = fs.mkdtempSync(path.join(temp, 'dual-self-test-'));
  try {
    const file = path.join(dir, 'ref.json');
    const data = Buffer.from('{"正文":"中"}');
    fs.writeFileSync(file, data);
    const ref = { path: file, bytes: data.length, mediaType: 'application/json' };
    assert.deepEqual(readFileRef(ref, dir), data);
    assert.throws(() => readFileRef({ ...ref, bytes: data.length + 1 }, dir));
    assert.throws(() => readFileRef({ ...ref, bytes: -1 }, dir));
    assert.throws(() => readFileRef({ ...ref, extra: true }, dir));
    assert.throws(() => readFileRef(ref, path.join(dir, 'other')));
    fs.unlinkSync(file);
    assert.throws(() => readFileRef(ref, dir));
  } finally { fs.rmSync(dir, { recursive: true }); }
  const observe = revisionObserver();
  observe({ type: 'event', event: 'ready', data: { revision: 0 } });
  observe({ type: 'response', revision: 0 });
  observe({ type: 'event', event: 'changed', data: { revision: 1 } });
  assert.throws(() => observe({ type: 'response', revision: 0 }));
  assert.throws(() => observe({ type: 'event', event: 'changed', data: { revision: 1 } }));
  assert.throws(() => observe({ type: 'response', revision: Number.MAX_SAFE_INTEGER + 1 }));
  const ordered = [
    ['runningChanged', { running: true }], ['extractionProgress', { overall: 1 }],
    ['runningChanged', { running: false }], ['extractionTerminal', { end: 'SUCCEEDED' }]
  ].map(([event, data]) => ({ type: 'event', event, data: { generation: 1, ...data } }));
  assertExtractionOrder(ordered, 1);
  assert.throws(() => assertExtractionOrder([...ordered, ordered.at(-1)], 1));
  assert.throws(() => assertExtractionOrder([ordered[1], ordered[0], ...ordered.slice(2)], 1));
  console.log('DUAL_CLIENT_SELF_TEST_OK frames/FileRef/error-codes/revisions/event-order; negative controls passed');
}

async function run() {
  const work = path.resolve(option('--out', 'H:/TEST/mht/dual-client'));
  const temp = path.resolve(option('--temp', 'H:/TEST/temp'));
  under(work, 'H:/TEST/mht');
  if (temp !== path.resolve('H:/TEST/temp')) under(temp, 'H:/TEST/temp');
  fs.mkdirSync(work, { recursive: true });
  fs.mkdirSync(temp, { recursive: true });
  const scenario = option('--scenario-file') ? JSON.parse(fs.readFileSync(option('--scenario-file'), 'utf8')) : {
    paths: ['data-uri.mht', 'single-image.mht'].map(name => path.join(repo, 'src/test/resources/fixtures', name)),
    groupName: 'Dual contract', textParams: { separateFiles: false, enableCustomTemplate: true,
      startTemplate: 'DUAL-BEGIN\n', endTemplate: '\nDUAL-END' },
    imageParams: { imageRenameEnabled: true }
  };
  const executable = option('--backend', path.join(process.env.JAVA_HOME ??
    'D:/install/bellsoft-liberica-vm-full-openjdk25-25.0.3', 'bin/java.exe'));
  const native = args.includes('--backend');
  const cp = option('--classpath', [path.join(repo, 'target/classes'), path.join(repo, 'target/lib/*')].join(path.delimiter));
  const command = [...(native ? [] : ['--limit-modules', 'java.se', '-Xmx512m']),
    '-Djava.io.tmpdir=' + temp, '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
    '-Dlogback.configurationFile=logback-headless.xml',
    ...(native ? [] : ['-cp', cp, 'com.mhtextractor.backend.BackendCli']), '--serve', '--locale', 'en'];
  fs.writeFileSync(path.join(work, 'command.json'), JSON.stringify({ executable, command, cwd: work }, null, 2));
  const client = createIpcClient({ executable, command, work, temp, observeFrame: revisionObserver() });
  const values = new Map();
  let processed = 0, released = 0;
  async function consume(value) {
    if (!value || typeof value !== 'object') return value;
    if (typeof value.path === 'string' && 'bytes' in value && 'mediaType' in value
      && (Object.keys(value).length === 3 || value.imagePath === value.path)) {
      if (values.has(value.path) && !fs.existsSync(value.path)) return values.get(value.path);
      const bytes = client.readRef({ path: value.path, bytes: value.bytes, mediaType: value.mediaType });
      const content = value.mediaType === 'application/json' ? JSON.parse(bytes.toString('utf8')) : bytes.toString('utf8');
      await client.request('releaseFiles', { paths: [value.path] });
      await client.request('fileInfo', { path: value.path }, 'INVALID_PARAMS');
      if (value.mediaType.startsWith('image/')) assert.ok(fs.existsSync(value.path), 'Release must retain borrowed cache images');
      else assert.equal(fs.existsSync(value.path), false, 'Release must delete owned bulk files');
      released++;
      const hydrated = await consume(content);
      values.set(value.path, hydrated);
      return hydrated;
    }
    if (Array.isArray(value)) {
      const result = [];
      for (const entry of value) result.push(await consume(entry));
      return result;
    }
    const result = {};
    for (const [key, entry] of Object.entries(value)) result[key] = await consume(entry);
    return result;
  }
  async function drain() {
    while (processed < client.frames.length) {
      const frame = client.frames[processed++];
      await consume(frame.type === 'event' ? frame.data : frame.result);
    }
  }
  async function call(method, params = {}, code) {
    const result = await client.request(method, params, code);
    await drain();
    return consume(result);
  }
  async function event(name, after = 0) {
    const frame = await client.waitFor(f => f.type === 'event' && f.event === name, after);
    await drain();
    return consume(frame.data);
  }
  try {
    const welcome = await client.handshake(option('--identity-file'));
    const methods = ['setGlobalParams', 'paramValues', 'collectParams', 'startPreparse', 'loadPreview',
      'awaitPreviewInFlight', 'startExtraction', 'workspace', 'releaseFiles'];
    const ready = await client.ready;
    for (const method of methods) assert.ok(ready.data.methods.includes(method), method);
    const imported = await call('importPaths', { paths: scenario.paths });
    assert.equal(imported.count, scenario.paths.length);
    assert.equal(imported.failed, false);
    const importEvents = client.frames.filter(f => f.type === 'event' && ['importFinished', 'importStateChanged'].includes(f.event));
    assert.deepEqual(importEvents.map(f => [f.event, f.data.importing ?? null]),
      [['importStateChanged', true], ['importFinished', null], ['importStateChanged', false]]);
    // Flatten the importer's optional source-folder groups, then apply an explicit frontend grouping command.
    const initial = await call('workspace');
    for (const group of initial.groups) await call('ungroup', { groupNo: group.no });
    await call('setChecked', { paths: scenario.paths, checked: false });
    await call('group', { paths: scenario.paths, namePrefix: scenario.groupName });
    await call('setChecked', { paths: scenario.paths, checked: true });
    const workspace = await call('workspace');
    assert.equal(workspace.groups.length, 1);
    assert.equal(workspace.topLevelItems.length, 0);
    assert.deepEqual(workspace.groups[0].members.map(m => m.id), scenario.paths);
    assert.ok(workspace.groups[0].members.every(m => m.checked));
    for (const [mode, imageMode] of [['text', false], ['image', true]]) {
      const config = scenario[mode + 'Params'];
      const form = await call('paramValues', { config, imageMode });
      const collected = await call('collectParams', { values: form, imageMode });
      assert.deepEqual(await call('paramValues', { config: collected, imageMode }), form, mode + ' form round trip');
      await call('setGlobalParams', { imageMode, params: collected });
    }
    const runs = {};
    for (const [mode, imageMode] of [['text', false], ['image', true]]) {
      const runtime = { inputFolder: path.dirname(scenario.paths[0]), outputFolder: path.join(work, mode),
        imageModeEnabled: imageMode, openFolderAfterComplete: false };
      const preparseStart = client.frames.length;
      await call('startPreparse', { runtime });
      await event('preparseFinished', preparseStart);
      const activity = client.frames.slice(preparseStart).filter(f => f.type === 'event');
      assert.equal(activity.filter(f => f.event === 'preparseStarted').length, 1);
      assert.ok(activity.findIndex(f => f.event === 'preparseStarted') < activity.findIndex(f => f.event === 'preparseFinished'));
      const previewStart = client.frames.length;
      await call('loadPreview', { path: scenario.paths[0], largeFileConfirmed: true,
        runtime: { ...runtime, imageModeEnabled: true } });
      const preview = await event('previewLoaded', previewStart);
      assert.ok(preview.manifest?.text && preview.manifest?.html, 'Preview must export both text and HTML');
      if (imageMode) assert.ok(preview.manifest.thumbnails.length > 0);
      await call('awaitPreviewInFlight', { millis: 10000 });
      const extractionStart = client.frames.length;
      await call('startExtraction', { checkedOnly: true, runtime });
      const terminal = await event('extractionTerminal', extractionStart);
      await call('metrics'); // Host barrier after terminal and running projection.
      assertExtractionOrder(client.frames.slice(extractionStart), terminal.generation);
      const stats = terminal.stats;
      assert.ok(stats && stats.successCount > 0 && stats.failCount === 0);
      const counts = Object.fromEntries(['successCount', 'failCount', 'skipCount', 'warnCount'].map(key => [key, stats[key]]));
      for (const [key, summaryKey] of [['successCount', 'success'], ['failCount', 'failed'],
        ['skipCount', 'skipped'], ['warnCount', 'warned']]) assert.equal(String(counts[key]), stats.summaryParams[summaryKey]);
      under(terminal.sessionOutputFolder, runtime.outputFolder);
      const products = tree(terminal.sessionOutputFolder);
      assert.ok(products.length > 0);
      runs[mode] = { tree: products, counts, stats, outputFolder: terminal.sessionOutputFolder };
    }
    await drain();
    const issued = [...values.keys()];
    for (const file of issued) await client.request('fileInfo', { path: file }, 'INVALID_PARAMS');
    await client.finish();
    assert.equal(fs.existsSync(welcome.bulkRoot), false, 'EOF must delete the session root');
    const report = { workspace, runs, releasedFiles: released, exitCode: 0, sessionCleaned: true };
    fs.writeFileSync(path.join(work, 'tree.json'), JSON.stringify(report, null, 2));
    for (const [mode, run] of Object.entries(runs)) console.log('DUAL_TREE ' + JSON.stringify({ mode, tree: run.tree, counts: run.counts }));
    console.log('DUAL_CLIENT_OK ' + JSON.stringify({ treeFile: path.join(work, 'tree.json'), releasedFiles: released,
      frames: client.frames.length, sessionCleaned: true }));
  } finally { await client.dispose(); }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (args.includes('--self-test')) selfTest();
  else await run();
}
