// Raw-byte controls for assert-gui-mirror-clean.ps1. No native rebuild required.
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const argv = process.argv.slice(2);
const option = name => argv[argv.indexOf(name) + 1];
assert.ok(argv.includes('--dist') && argv.includes('--out'), 'Supply --dist <paired dist> --out <fresh evidence directory>');
const dist = path.resolve(option('--dist'));
const out = path.resolve(option('--out'));
assert.ok(!fs.existsSync(out), 'Use a fresh control directory');
assert.ok(!out.startsWith(dist + path.sep) && out !== dist, 'Evidence must be outside the distribution');
fs.mkdirSync(out, { recursive: true });
const script = fileURLToPath(new URL('./assert-gui-mirror-clean.ps1', import.meta.url));
const hash = file => createHash('sha256').update(fs.readFileSync(file)).digest('hex');
let passed = 0;
function run(name, args, expected, check = () => {}) {
  const command = ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', script, ...args];
  const result = spawnSync('powershell.exe', command, { encoding: 'utf8', timeout: 60000, windowsHide: true });
  fs.writeFileSync(path.join(out, name + '.log'), JSON.stringify({ command: ['powershell.exe', ...command], exit: result.status, error: result.error?.message }) + '\n' + result.stdout + result.stderr);
  assert.ifError(result.error);
  assert.equal(result.status, expected, name + '\n' + result.stdout + result.stderr);
  check(result.stdout);
  passed++;
  console.log(`${name}: exit=${result.status} expected=${expected} PASS`);
}

const gui = path.join(dist, 'MhtExtractor.exe');
const backend = path.join(dist, 'Backend', 'MhtExtractorBackend.exe');
const before = { gui: hash(gui), backend: hash(backend) };
run('gui-clean', ['-DistDir', dist], 0);
run('gui-inverted-red', ['-DistDir', dist, '-ExpectBusiness'], 1);
run('backend-teeth', ['-DistDir', dist, '-Exe', 'Backend/MhtExtractorBackend.exe', '-ExpectBusiness'], 0);
run('backend-normal-red', ['-Exe', backend], 1);

// Mutate a copy, never the delivered executable. Original bytes are restored in finally.
const copy = path.join(out, 'MhtExtractor.exe');
fs.copyFileSync(gui, copy);
const copyBefore = hash(copy);
try {
  fs.appendFileSync(copy, Buffer.from('\0com/mhtextractor/processor/BatchProcessor\0', 'ascii'));
  run('appended-class-red', ['-DistDir', out], 1, text => assert.ok(text.includes('SNIPPET=com/mhtextractor/processor/BatchProcessor')));
} finally {
  fs.copyFileSync(gui, copy);
}
assert.equal(hash(copy), copyBefore);
run('restored-copy-green', ['-DistDir', out], 0);
fs.unlinkSync(copy);

// High bytes and NULs catch the original object[]/string coercion false negative.
const fixtureDir = path.join(out, 'fixtures');
fs.mkdirSync(fixtureDir);
const fixture = path.join(fixtureDir, 'MhtExtractor.exe');
const markers = ['core', 'extractor', 'processor', 'output', 'cache', 'util', 'config', 'app', 'engine', 'backend']
  .map(pkg => `com/mhtextractor/${pkg}/`).concat('org/jsoup/');
for (const [index, marker] of markers.entries()) {
  fs.writeFileSync(fixture, Buffer.concat([Buffer.from([0, 128, 255]), Buffer.from(marker + 'Control\0'), Buffer.from([255])]));
  run(`marker-${index}-red`, ['-DistDir', fixtureDir], 1, text => assert.ok(text.includes('HITS=1') && text.includes('SNIPPET=' + marker + 'Control')));
}
fs.writeFileSync(fixture, Buffer.from('com/mhtextractor/processor/A\0'.repeat(5) + 'com/mhtextractor/processor/B\0com/mhtextractor/processor/C\0'));
run('bounded-unique-snippets', ['-DistDir', fixtureDir, '-MaxSnippets', '2'], 1, text => {
  assert.equal((text.match(/SNIPPET=/g) || []).length, 2);
  assert.ok(text.includes('processor/ x7'));
});
fs.writeFileSync(fixture, Buffer.from(['uiport', 'uiclient', 'uicore', 'model', 'i18n', 'platform'].map(pkg => `com/mhtextractor/${pkg}/Allowed\0`).join('')));
run('allowed-packages', ['-DistDir', fixtureDir], 0);
run('allowed-inverted-red', ['-DistDir', fixtureDir, '-ExpectBusiness'], 1);
fs.writeFileSync(fixture, Buffer.alloc(0));
run('empty-input', ['-DistDir', fixtureDir], 2);
run('missing-input', ['-DistDir', fixtureDir, '-Exe', 'missing.exe'], 2);
const after = { gui: hash(gui), backend: hash(backend) };
assert.deepEqual(after, before);
const encoding = fs.readFileSync(script);
assert.ok(encoding.subarray(0, 3).equals(Buffer.from([239, 187, 191])), 'UTF-8 BOM required');
assert.ok(!encoding.toString('utf8').replaceAll('\r\n', '').includes('\n'), 'CRLF required');
const evidence = { passed, before, after, restoredCopy: { before: copyBefore, after: hash(gui) }, encoding: 'UTF-8 BOM + CRLF' };
fs.writeFileSync(path.join(out, 'controls.json'), JSON.stringify(evidence, null, 2));
console.log(JSON.stringify(evidence, null, 2));
