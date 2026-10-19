// Run from the repository root. Optional args: external-fixture-directory evidence-directory.
// Mutations are restored byte for byte in finally; never run concurrently with another build.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';

const fixtures = process.argv[2];
if (fixtures && !fs.statSync(fixtures).isDirectory()) throw new Error('External fixture directory is missing');
const evidence = path.resolve(process.argv[3] ?? 'H:/TEST/mht/pf12-13/controls');
fs.mkdirSync(evidence, { recursive: true });
const env = { ...process.env, TEMP: 'H:/TEST/temp', TMP: 'H:/TEST/temp', JAVA_HOME: process.env.JAVA_HOME ?? 'D:/install/bellsoft-liberica-vm-full-openjdk25-25.0.3',
  JAVA_TOOL_OPTIONS: '-Djava.io.tmpdir=H:/TEST/temp -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8' };
const content = 'src/main/java/com/mhtextractor/core/ContentExtractor.java';
const processor = 'src/main/java/com/mhtextractor/processor/TextBatchProcessor.java';
const originals = new Map([content, processor].map(p => [p, fs.readFileSync(p)]));
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const manifest = { commands: [], sourceBefore: Object.fromEntries([...originals].map(([p,b]) => [p,sha(b)])) };
function run(label, expectedFailure) {
  const reportPath = 'target/surefire-reports/com.mhtextractor.engine.TextStructureTest.txt';
  if (fs.existsSync(reportPath)) fs.unlinkSync(reportPath);
  const product = path.join(evidence,label,'real-merged.txt');
  if (fs.existsSync(product)) fs.unlinkSync(product);
  const args = ['test-compile', 'surefire:test@default-test', '-Dtest=BlockTextBuilderTest,ChapterNumbersTest,TextStructureTest,ContentExtractorTest,FileWriterTemplateTest',
    '-Djava.io.tmpdir=H:/TEST/temp', `-Dmht.gui.logs.root=${evidence}/logs-${label}`, `-Dpf12.evidence=${evidence}/${label}`];
  if (fixtures) args.push(`-Dpf12.fixtures=${fixtures}`);
  const command = `"${path.resolve('mvnw.cmd')}" ${args.map(a => '"'+a+'"').join(' ')}`;
  console.log(`${label}: ${command}`);
  const result = spawnSync(process.env.ComSpec ?? 'cmd.exe', ['/d','/s','/c',`"${command}"`], { env, windowsVerbatimArguments: true, encoding: 'utf8', maxBuffer: 32*1024*1024 });
  fs.writeFileSync(path.join(evidence, label+'.log'), (result.stdout??'')+(result.stderr??''));
  manifest.commands.push({ label, command, status: result.status });
  if (!Number.isInteger(result.status) || (expectedFailure ? result.status === 0 : result.status !== 0)) throw new Error(`${label}: unexpected exit ${result.status}; see raw log`);
  const report = fs.readFileSync(reportPath, 'utf8');
  if (fixtures && !report.includes('Skipped: 0')) throw new Error(`${label}: fixture test was skipped`);
  if (expectedFailure) {
    fs.writeFileSync(path.join(evidence,label+'-structure.txt'),report);
    const names = label === 'wholeText-red' ? ['syntheticStructureAllFiveRequirements'] : ['naturalOrderUnknownTiesSeparateAndGroupBoundaries'];
    if (fixtures) names.push('realMhtStructureAndOrder');
    for (const name of names) if (!report.includes(name+' --') || !report.split(name+' --')[1].split('\n')[0].includes('FAILURE'))
      throw new Error(`${label}: required assertion did not fail: ${name}`);
    if (label === 'wholeText-red') for (const n of [1,2,3,4,5])
      if (!report.includes(`${n}: `)) throw new Error(`wholeText-red: requirement ${n} did not fail`);
  }
  if (fixtures && !expectedFailure && !fs.existsSync(product)) throw new Error(`${label}: no fresh product`);
  return fs.existsSync(product) ? sha(fs.readFileSync(product)) : null;
}
try {
  manifest.greenSha = run('green-before', false);
  fs.writeFileSync(content, originals.get(content).toString('utf8').replace('BlockTextBuilder.build(doc.body())','doc.body().wholeText()'));
  run('wholeText-red', true);
  fs.writeFileSync(content, originals.get(content));
  manifest.afterTextSource = Object.fromEntries([...originals].map(([p]) => [p,sha(fs.readFileSync(p))]));
  manifest.afterTextSha = run('green-after-text', false);
  fs.writeFileSync(processor, originals.get(processor).toString('utf8').replace('return processFiles(sorted, stats,','return processFiles(orderedFiles, stats,'));
  run('sort-red', true);
} finally {
  for (const [p,b] of originals) fs.writeFileSync(p,b);
  manifest.sourceAfter = Object.fromEntries([...originals].map(([p]) => [p,sha(fs.readFileSync(p))]));
  fs.writeFileSync(path.join(evidence,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
}
manifest.afterSortSha = run('green-after-sort', false);
if (JSON.stringify(manifest.sourceBefore) !== JSON.stringify(manifest.sourceAfter)) throw new Error('Source restoration differs');
if (JSON.stringify(manifest.sourceBefore) !== JSON.stringify(manifest.afterTextSource)) throw new Error('Text source restoration differs');
if (fixtures && !(manifest.greenSha === manifest.afterTextSha && manifest.greenSha === manifest.afterSortSha)) throw new Error('Restored product SHA differs');
fs.writeFileSync(path.join(evidence,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
console.log('PF12_13_CONTROLS_OK '+JSON.stringify(manifest));
