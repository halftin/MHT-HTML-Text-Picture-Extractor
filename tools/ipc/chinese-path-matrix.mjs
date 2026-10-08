// P4-4 black-box matrix. Only Node built-ins; invoke after compile/package.
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { exactText, golden, under, utf8 } from './path-assertions.mjs';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const argv = process.argv.slice(2);
function option(name, fallback) { const index = argv.indexOf(name); return index < 0 ? fallback : argv[index + 1]; }
const root = path.resolve(option('--root', '') || fs.mkdtempSync('H:/TEST/mht/p4-4-matrix-'));
under(root, 'H:/TEST/mht');
fs.mkdirSync(root, { recursive: true });
const native = path.resolve(option('--native', 'H:/TEST/mht/p4-3-headless-dist/MhtExtractorBackend.exe'));
const cpFile = path.resolve(option('--classpath-file', 'H:/TEST/mht/p4-2b-classpath.txt'));
const cp = [path.join(repo, 'target/classes'), utf8(cpFile).trim()].join(path.delimiter)
  .split(path.delimiter).filter(entry => !/^(javafx-|atlantafx|ikonli)/i.test(path.basename(entry)))
  .map(entry => path.resolve(repo, entry)).join(path.delimiter);
const java = path.join(process.env.JAVA_HOME ?? 'D:/install/bellsoft-liberica-vm-full-openjdk25-25.0.3', 'bin/java.exe');
const modes = option('--modes', 'jvm-once,native-once,jvm-serve,native-serve').split(',');
const scenes = option('--scenes', 'input,output,both,nested,filename,temp,executable,text').split(',');
const locales = option('--locales', 'zh,en').split(',');
const knownModes = ['jvm-once', 'native-once', 'jvm-serve', 'native-serve'];
const unicodeScenes = {
  japanese: 'かな カナ 漢字',
  korean: '한글 한국어',
  symbols: '😀 ＡＢＣ（全角）',
  mixed: '中文 空格.点号.v1'
};
// Explicit sample classification for the command-line -> JVM argv boundary on this cp936 host.
// This is not a general Unicode/OS capability test or a claim about other host code pages.
const SYSCP_SAFE = 'SYSCP_SAFE';
const OUTSIDE_SYSCP = 'OUTSIDE_SYSCP';
const sceneClasses = { japanese: SYSCP_SAFE, mixed: SYSCP_SAFE,
  korean: OUTSIDE_SYSCP, symbols: OUTSIDE_SYSCP };
const knownScenes = ['input', 'output', 'both', 'nested', 'filename', 'temp', 'executable', 'text',
  ...Object.keys(unicodeScenes)];
for (const mode of modes) assert.ok(knownModes.includes(mode), mode);
for (const scene of scenes) assert.ok(knownScenes.includes(scene), scene);
for (const locale of locales) assert.ok(['zh', 'en'].includes(locale), locale);
const records = [];
let sequence = 0;

function setup(scene, mode, locale, suffix = '') {
  const id = `${scene}-${mode}-${locale}${suffix}`;
  const cell = path.join(root, id);
  // A rerun uses a fresh root; stale products must never count as successful extraction.
  assert.ok(!fs.existsSync(cell), `Cell already exists: ${cell}. Use a fresh --root.`);
  fs.mkdirSync(cell, { recursive: true });
  const unicode = unicodeScenes[scene];
  const input = path.join(cell, unicode ? unicode + '/input' : ['input', 'both'].includes(scene) ? '中文输入' :
    scene === 'nested' ? '中文 上层/全角（样本） & 数据/输入 空格' : 'input');
  const output = path.join(cell, unicode ? unicode + '/output' : ['output', 'both'].includes(scene) ? '中文输出' :
    scene === 'nested' ? '中文 上层/全角（产物） & 数据/输出 空格' : 'output');
  const temp = unicode || scene === 'temp' ? path.join('H:/TEST/temp', path.basename(root), id,
    unicode ?? '中文 临时（目录）') : 'H:/TEST/temp';
  const isText = scene === 'text' || !!unicode;
  const filename = unicode ? unicode + '.html' : scene === 'filename' ? '中文 文件（样本）.mht' :
    isText ? 'simple-text.mht' : 'cn-filename.mht';
  for (const dir of [input, output, temp, path.join(cell, 'logs')]) fs.mkdirSync(dir, { recursive: true });
  const source = path.join(input, filename);
  if (unicode) fs.writeFileSync(source,
    `<html><head><meta charset="utf-8"><title>Unicode fixture</title></head><body>正文:${unicode}</body></html>`);
  else fs.copyFileSync(path.join(repo, 'src/test/resources/fixtures', isText ? 'simple-text.mht' : 'cn-filename.mht'), source);
  fs.utimesSync(source, new Date(1767294245000), new Date(1767294245000));
  let executable = native;
  if (scene === 'executable' && mode.startsWith('native')) {
    const dist = path.join(cell, '中文 程序（目录）');
    fs.mkdirSync(dist);
    for (const entry of fs.readdirSync(path.dirname(native), { withFileTypes: true })) {
      if (entry.isFile() && /\.(exe|dll)$/i.test(entry.name)) fs.copyFileSync(path.join(path.dirname(native), entry.name), path.join(dist, entry.name));
    }
    executable = path.join(dist, path.basename(native));
    assert.deepEqual(fs.readFileSync(executable), fs.readFileSync(native), 'Relocated exe must be byte-identical');
  }
  const baseline = path.join(repo, 'src/test/resources/baselines', `${isText ? 'text-template-vars' : 'image-cn-filename'}.${locale}.json`);
  const stem = path.parse(filename).name;
  const startTemplate = unicode ? 'BEGIN:{filename}\\n' : '=== {filename} @ {date} {time} ===\\n';
  const endTemplate = unicode ? '\\nEND:{filename}' : '\\n=== {filename} 结束 ===\\n';
  const expectedText = unicode ? { [mode.endsWith('serve') ? stem + '1.txt' : 'merged.txt']:
    `BEGIN:${filename}\n正文:${unicode}\nEND:${filename}` } : undefined;
  return { id, cell, input, output, temp, filename, source, executable, baseline, stem, isText,
    startTemplate, endTemplate, expectedText, mode, locale, scene };
}

function execute(c, alter) {
  const log = [];
  const say = value => log.push(typeof value === 'string' ? value : JSON.stringify(value));
  try {
    under(fs.realpathSync(c.cell), 'H:/TEST/mht');
    const serve = c.mode.endsWith('serve');
    const base = c.mode.startsWith('native') ? [c.executable] : [java, '--limit-modules', 'java.se', '-Xmx512m'];
    const command = [...base, '-Djava.io.tmpdir=' + c.temp, '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
      ...(c.mode.startsWith('native') ? [] : ['-cp', cp, 'com.mhtextractor.backend.BackendCli'])];
    let program;
    let args;
    if (serve) {
      const scenario = { work: c.cell, input: c.input, output: c.output, temp: c.temp, filename: c.filename,
        executable: c.mode.startsWith('native') ? c.executable : undefined, locale: c.locale,
        imageMode: !c.isText, imageCount: c.isText ? 0 : 2, baseline: c.baseline, groupPrefix: c.stem,
        config: c.isText ? { separateFiles: false, enableCustomTemplate: true,
          startTemplate: c.startTemplate, endTemplate: c.endTemplate } : {},
        expectedText: c.expectedText,
        textSentinel: unicodeScenes[c.scene] ? '正文:' + unicodeScenes[c.scene] : '这是第一段正文。',
        mappings: c.isText ? { 'merged.txt': c.stem + '1.txt' } : { 'cn-filename/': c.stem + '1/' + c.stem + '/' } };
      const scenarioFile = path.join(c.cell, 'scenario.json');
      fs.writeFileSync(scenarioFile, JSON.stringify(scenario, null, 2));
      program = process.execPath;
      args = [path.join(repo, 'tools/ipc/client.mjs'), '--scenario-file', scenarioFile, '--classpath-file', cpFile];
    } else {
      command.push('--once', '--locale', c.locale, '--input', c.input, '--output', c.output);
      if (c.isText) command.push('--group', '--template-start', c.startTemplate,
        '--template-end', c.endTemplate);
      else command.push('--extract', 'images');
      if (alter) alter(command, c);
      [program, ...args] = command;
    }
    say('COMMAND ' + JSON.stringify({ program, args, cwd: c.cell }));
    const result = spawnSync(program, args, { cwd: c.cell, windowsHide: true, timeout: 180000,
      maxBuffer: 16 * 1024 * 1024, env: { ...process.env, TEMP: c.temp, TMP: c.temp,
        // Explicit argv already carries java.io.tmpdir; option env strings split paths with spaces.
        JAVA_TOOL_OPTIONS: '' } });
    fs.writeFileSync(path.join(c.cell, 'capture.stdout'), result.stdout ?? Buffer.alloc(0));
    fs.writeFileSync(path.join(c.cell, 'capture.stderr'), result.stderr ?? Buffer.alloc(0));
    say('EXIT=' + result.status + ' SIGNAL=' + result.signal);
    say((result.stdout ?? Buffer.alloc(0)).toString('utf8'));
    say((result.stderr ?? Buffer.alloc(0)).toString('utf8'));
    assert.ifError(result.error);
    if (sceneClasses[c.scene] === OUTSIDE_SYSCP) {
      // Match captured process diagnostics, never COMMAND text or a setup exception.
      const diagnostics = Buffer.concat([result.stdout ?? Buffer.alloc(0),
        Buffer.from('\n'), result.stderr ?? Buffer.alloc(0)]).toString('utf8');
      assert.ok(Number.isInteger(result.status) && result.status !== 0 && result.signal === null,
        'SYSCP_LIMITATION_GUARD: expected a completed subprocess with nonzero exit');
      assert.match(diagnostics, /InvalidPathException:[^\r\n]*Illegal char <\?>/,
        'SYSCP_LIMITATION_GUARD: expected cp936 argv replacement with illegal question mark');
      say('CELL=LIMITATION(expected) reason=HOST_CP936_ARGV');
      return { passed: null, status: 'LIMITATION(expected)', c, log: path.join(c.cell, 'assertions.log') };
    }
    assert.equal(result.status, 0, 'EXIT_GUARD');
    const stdout = utf8(path.join(c.cell, 'capture.stdout'));
    utf8(path.join(c.cell, 'capture.stderr'));
    const backendLog = utf8(path.join(c.cell, 'logs/backend.log'));
    assert.ok(backendLog.includes(serve ? 'serve mode' : 'once mode'), 'Real backend log required');
    if (serve) {
      const frames = utf8(path.join(c.cell, 'stdout.jsonl')).trim().split('\n').map(line => JSON.parse(line));
      const requests = utf8(path.join(c.cell, 'stdin.jsonl')).trim().split('\n').map(line => JSON.parse(line));
      assert.ok(frames.every(frame => frame.v === 1 && ['event', 'response', 'error'].includes(frame.type)));
      const response = method => requests.filter(req => req.method === method)
        .map(req => frames.find(frame => frame.id === req.id)).find(frame => frame.type === 'response').result;
      assert.equal(response('workspacePage').items[0].id, c.source, 'UTF8_PATH_ROUNDTRIP');
      assert.equal(path.dirname(response('extract').outputFolder), c.output, 'DIRECTORY_GUARD');
      under(fs.realpathSync(response('extract').outputFolder), fs.realpathSync(c.output));
      under(response('previewFiles').manifest.path, c.temp);
      const bulk = JSON.parse(utf8(path.join(c.cell, 'bulk-evidence.json')));
      assert.equal(bulk.results[0].path, c.source, 'UTF8_PATH_ROUNDTRIP: bulk result');
      say('IPC_UTF8_ROUNDTRIP=PASS STDOUT_PROTOCOL_ONLY=PASS frames=' + frames.length);
    } else {
      // Exact full root/name comparisons detect valid-UTF8 mojibake and '?' replacement too.
      assert.ok(stdout.includes(' -> ' + c.output), 'DIRECTORY_GUARD: exact output root echo');
      assert.ok(stdout.includes('[1/1] ' + c.filename), 'UTF8_PATH_ROUNDTRIP: filename');
    }
    under(fs.realpathSync(c.output), 'H:/TEST/mht');
    const mappings = c.isText ? (serve ? { 'merged.txt': c.stem + '1.txt' } : {}) :
      { 'cn-filename/': serve ? c.stem + '1/' + c.stem + '/' : c.stem + '/' };
    const goldenRoot = serve ? JSON.parse(utf8(path.join(c.cell, 'bulk-evidence.json'))).extracted.outputFolder : c.output;
    const products = c.expectedText ? exactText(goldenRoot, c.expectedText) : golden(goldenRoot, c.baseline, mappings);
    say((c.expectedText ? 'TEXT_EXACT_MATCH ' : 'GOLDEN_SHA256_MATCH ') + JSON.stringify(products));
    say('PATH_UTF8=PASS DIRECTORY_GUARD=PASS CELL=PASS');
    return { passed: true, c, log: path.join(c.cell, 'assertions.log') };
  } catch (error) {
    say(error.stack);
    say('CELL=FAIL');
    return { passed: false, c, log: path.join(c.cell, 'assertions.log') };
  } finally {
    fs.writeFileSync(path.join(c.cell, 'assertions.log'), log.join('\n') + '\n');
  }
}

for (const scene of scenes) for (const mode of modes) for (const locale of locales) {
  let record;
  try { record = execute(setup(scene, mode, locale)); }
  catch (error) {
    // Keep filesystem errors as evidence and continue other cells. Do not classify an untested
    // character as an OS limitation merely because setup failed under a restricted token.
    const evidence = path.join(root, `${scene}-${mode}-${locale}-setup-error.log`);
    fs.writeFileSync(evidence, error.stack + '\n');
    record = { passed: false, log: evidence };
  }
  const status = record.status ?? (record.passed ? 'PASS' : 'FAIL');
  records.push({ scene, mode, locale, sceneClass: sceneClasses[scene] ?? SYSCP_SAFE,
    status, passed: record.passed, evidence: record.log });
  console.log(`CELL ${++sequence} ${scene} ${mode} ${locale} ${status} ${record.log}`);
}
if (argv.includes('--unicode-controls')) {
  const red = execute(setup('mixed', 'jvm-once', 'en', '-CG-3-red'), (command) => {
    // Actual template mutation creates wrong output bytes while the literal expectation stays fixed.
    command[command.indexOf('--template-start') + 1] = 'BEGIN:???\\n';
  });
  assert.equal(red.passed, false, 'CG-3 must actually fail');
  assert.ok(utf8(red.log).includes('TEXT_EXACT_GUARD'), 'CG-3 must fail whole-text equality');
  const green = execute(setup('mixed', 'jvm-once', 'en', '-CG-3-green'));
  assert.equal(green.passed, true, 'CG-3 must return to green after removing template mutation');
  console.log(`CG-3 RED=FAIL GREEN=PASS red=${red.log} green=${green.log}`);
  fs.writeFileSync(path.join(root, 'CG-3.json'), JSON.stringify({ red: red.log, green: green.log }, null, 2));
}
if (argv.includes('--controls')) {
  for (const control of ['CG-1', 'CG-2']) {
    const red = execute(setup('both', 'jvm-once', 'zh', '-' + control + '-red'), (command, c) => {
      if (control === 'CG-1') command[command.indexOf('-Dstdout.encoding=UTF-8')] = '-Dstdout.encoding=GBK';
      else command[command.indexOf('--output') + 1] = path.join(c.cell, '错误中文根');
    });
    const redText = utf8(red.log);
    assert.equal(red.passed, false, `${control} must actually fail`);
    assert.ok(redText.includes(control === 'CG-1' ? 'encoded data was not valid' : 'DIRECTORY_GUARD'), 'Must fail the intended guard');
    const green = execute(setup('both', 'jvm-once', 'zh', '-' + control + '-green'));
    assert.equal(green.passed, true, `${control} must return to green after removing command mutation`);
    console.log(`${control} RED=FAIL GREEN=PASS red=${red.log} green=${green.log}`);
    fs.writeFileSync(path.join(root, control + '.json'), JSON.stringify({ red: red.log, green: green.log }, null, 2));
  }
}
fs.writeFileSync(path.join(root, 'matrix.json'), JSON.stringify(records, null, 2));
fs.writeFileSync(path.join(root, 'matrix.md'), '| Scenario | Class (cp936) | Mode | Locale | Result | Raw evidence |\n|---|---|---|---|---|---|\n' +
  records.map(r => `| ${r.scene} | ${r.sceneClass} | ${r.mode} | ${r.locale} | ${r.status} | [log](${r.evidence.replaceAll('\\', '/')}) |`).join('\n') + '\n');
console.log(`MATRIX: ${records.filter(r => r.status === 'PASS').length} passed, ${records.filter(r => r.status === 'FAIL').length} unexpected failures, ${records.filter(r => r.status === 'LIMITATION(expected)').length} expected limitations; root=${root}`);
// Known limitations are neither extraction passes nor unexpected failures.
process.exitCode = records.some(r => r.status === 'FAIL') ? 1 : 0;
