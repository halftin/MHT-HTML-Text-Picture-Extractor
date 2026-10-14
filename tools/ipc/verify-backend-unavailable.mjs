// Reproducible D1 evidence and mutation controls. No source mutation without SHA256 checks.
import fs from 'node:fs';
import path from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { snapshot, compare } from './project-temp-audit.mjs';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const out = 'H:/TEST/temp/p5a-4-evidence';
const jdk = 'D:/install/bellsoft-liberica-vm-full-openjdk25-25.0.3';
fs.mkdirSync(out, { recursive: true });
const env = { ...process.env, JAVA_HOME: jdk, MAVEN_USER_HOME: 'H:/TEST/m2',
  JAVA_TOOL_OPTIONS: '-Djava.io.tmpdir=H:/TEST/temp -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8' };
const phase = process.argv[2] || 'targeted';
const before = snapshot();
const evidence = [];
async function run(label, exe, args, cwd = repo, expected = 0, timeout = 600000) {
  console.log(`RUN ${label} (exact command in ${phase}-commands.json)`);
  const chunks = [];
  fs.writeFileSync(`${out}/${label}.log`, '');
  const child = spawn(exe, args, { cwd, env, windowsHide: true });
  let nativeTail='',abortedReason=null;
  const capture=b=>{
    chunks.push(b);fs.appendFileSync(`${out}/${label}.log`,b);
    if(label==='native-build'&&!abortedReason){
      nativeTail=(nativeTail+b.toString('utf8')).slice(-16000);
      if((nativeTail.match(/is not recognized as an internal or external command/g)||[]).length>=8){
        abortedReason='CMD_PARSE_LOOP';child.kill();
      }
    }
  };
  child.stdout.on('data', capture); child.stderr.on('data', capture);
  let expired = false;
  const timer = setTimeout(() => { expired = true; spawn('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true }); }, timeout);
  const outcome = await new Promise((resolve, reject) => { child.on('error', reject); child.on('close', (code,signal)=>resolve({code,signal})); });
  const {code,signal}=outcome;
  clearTimeout(timer);
  const raw = Buffer.concat(chunks).toString('utf8');
  fs.writeFileSync(`${out}/${label}.log`, raw);
  const record = { label, command: [exe, ...args], cwd, exit: code, signal, expired, abortedReason, log: `${out}/${label}.log` };
  evidence.push(record);fs.writeFileSync(`${out}/${phase}-commands.json`, JSON.stringify(evidence, null, 2));
  console.log(`EXIT ${label}=${code}${expired ? ' DEADLINE' : ''}`);
  console.log(raw.split(/\r?\n/).filter(l => /Tests run:|BUILD (SUCCESS|FAILURE)|D1_|FAIL|ALL GREEN|tests passed|RESULTS|results:|PASS.*34/.test(l)).slice(-18).join('\n'));
  assert.equal(expired, false, label + ' exceeded deadline');assert.equal(code, expected, label + ' unexpected exit');
  return raw;
}
async function mvn(label, args, expected = 0) {
  return run(label, 'cmd.exe', ['/d', '/c', 'mvnw.cmd -Dmaven.repo.local=H:/TEST/m2/repository ' + args], repo, expected);
}
function paths() {
  const lib = path.join(repo, 'target/lib');
  const jars = fs.readdirSync(lib).filter(n => n.endsWith('.jar')).map(n => path.join(lib, n));
  return { modules: jars.filter(p => /javafx-.*-win\.jar$/.test(p)).join(';'),
    cp: [path.join(repo, 'target/classes'), path.join(repo, 'target/test-classes'), ...jars].join(';') };
}
async function probe(label, locale, expected = 0, name = 'BackendUnavailableProbe') {
  const { modules, cp } = paths();
  const classes = `${out}/probe-classes`;fs.mkdirSync(classes, { recursive: true });
  await run(label + '-javac', `${jdk}/bin/javac.exe`, ['--module-path', modules, '--add-modules', 'javafx.controls,javafx.fxml',
    '-cp', cp, '-d', classes, path.join(repo, `tools/probes/${name}.java`), path.join(repo, 'tools/probes/support/ProbeRuntime.java')]);
  const cwd = `H:/TEST/mht/p5a-4-${label}`;fs.mkdirSync(cwd, { recursive: true });
  return run(label, `${jdk}/bin/java.exe`, ['--module-path', modules, '--add-modules', 'javafx.controls,javafx.fxml',
    `-Dprobe.locale=${locale}`, `-Dprobe.png=${out}/${label}.png`, '-cp', `${classes};${cp}`, name], cwd, expected, 150000);
}
const hash = data => createHash('sha256').update(data).digest('hex');
async function mutation(label, relative, edit, verify) {
  const file = path.join(repo, relative), original = fs.readFileSync(file), originalHash = hash(original);
  assert.equal(hash(fs.readFileSync(file)), originalHash, 'Source changed before injection');
  const changed = Buffer.from(edit(original.toString('utf8')));assert.notEqual(hash(changed), originalHash, 'Mutation did not change source');
  const backup = `${out}/${label}.original`;fs.writeFileSync(backup, original);
  assert.equal(hash(fs.readFileSync(backup)), originalHash);
  fs.writeFileSync(file, changed);
  assert.equal(hash(fs.readFileSync(file)), hash(changed));
  fs.utimesSync(file, new Date(), new Date());
  console.log(`INJECT ${label} original=${originalHash} mutated=${hash(changed)} SHA256 checked before touch`);
  try { await verify(); }
  finally {
    assert.equal(hash(fs.readFileSync(file)), hash(changed), 'Source changed during control; refusing overwrite');
    fs.writeFileSync(file, original);
    assert.equal(hash(fs.readFileSync(file)), originalHash, 'Restoration SHA256 mismatch');
    fs.utimesSync(file, new Date(), new Date());
    console.log(`RESTORE ${label} SHA256=${originalHash} checked before touch`);
  }
}
try {
  if (phase === 'targeted') {
    await mvn('targeted', '-Dtest=BackendUnavailableTest,ArchitectureTest,WiringSwitchTest,I18nTest test');
  } else if (phase === 'probes') {
    for (const locale of ['zh_CN', 'en_US']) await probe('d1-' + locale, locale);
  } else if (phase === 'full') {
    const raw = await run('full-clean-test','cmd.exe',['/d','/c','mvnw.cmd -Dmaven.repo.local=H:/TEST/m2/repository clean test'],repo,0,1200000);
    const results = [...raw.matchAll(/\[INFO\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\r?\n/g)];
    fs.writeFileSync(`${out}/maven-totals.json`, JSON.stringify(results.map(m => m.slice(1).map(Number)), null, 2));
    // Independent report aggregation, independent of Maven's printed totals.
    const reports = fs.readdirSync(path.join(repo, 'target/surefire-reports')).filter(n => n.endsWith('.txt'));
    const totals = [0, 0, 0, 0];
    for (const name of reports) {
      const text = fs.readFileSync(path.join(repo, 'target/surefire-reports', name), 'utf8');
      const m = text.match(/Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)/);
      if (m) m.slice(1).forEach((n, i) => totals[i] += Number(n));
    }
    fs.writeFileSync(`${out}/independent-report-totals.json`, JSON.stringify({ files: reports.length, totals }, null, 2));
    console.log('INDEPENDENT_REPORT_TOTALS ' + JSON.stringify(totals));
    await mvn('package', '-DskipTests package');
    await run('anti-fallback', 'node.exe', [path.join(repo, '.claude-work/verify/p5a-3-anti-fallback-check.mjs')]);
  } else if (phase === 'finish-full') {
    const raw=fs.readFileSync(`${out}/full-clean-test.log`,'utf8');assert.match(raw,/BUILD SUCCESS/);
    const summary=[...raw.matchAll(/\[INFO\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\r?\n/g)].map(m=>m.slice(1).map(Number));
    assert.deepEqual(summary,[[1058,0,0,0],[73,0,0,0]]);
    const totals=[0,0,0,0], reports=fs.readdirSync(path.join(repo,'target/surefire-reports')).filter(n=>n.endsWith('.txt'));
    for(const name of reports){const m=fs.readFileSync(path.join(repo,'target/surefire-reports',name),'utf8').match(/Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)/);if(m)m.slice(1).forEach((v,i)=>totals[i]+=Number(v));}
    assert.deepEqual(totals,[1058,0,0,0]);
    fs.writeFileSync(`${out}/independent-report-totals.json`,JSON.stringify({files:reports.length,totals,mavenSummary:summary},null,2));
    console.log('INDEPENDENT_REPORT_TOTALS '+JSON.stringify(totals));
    console.log('FIRST_FULL_WRAPPER_OVERRAN_10_MINUTES; raw Maven exit and reports independently checked');
    await mvn('package','-DskipTests package');
  } else if (phase === 'final-test') {
    const raw=await run('final-test','cmd.exe',['/d','/c','mvnw.cmd -Dmaven.repo.local=H:/TEST/m2/repository test'],repo,0,2400000);
    const summary=[...raw.matchAll(/\[INFO\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\r?\n/g)].map(m=>m.slice(1).map(Number));
    assert.deepEqual(summary,[[1059,0,0,0],[73,0,0,0]]);
    const totals=[0,0,0,0];
    for(const name of fs.readdirSync(path.join(repo,'target/surefire-reports')).filter(n=>n.endsWith('.txt'))){const m=fs.readFileSync(path.join(repo,'target/surefire-reports',name),'utf8').match(/Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)/);if(m)m.slice(1).forEach((v,i)=>totals[i]+=Number(v));}
    assert.deepEqual(totals,[1059,0,0,0]);fs.writeFileSync(`${out}/final-independent-totals.json`,JSON.stringify({summary,totals},null,2));
    console.log('FINAL_INDEPENDENT_TOTALS '+JSON.stringify(totals));
  } else if (phase === 'controls') {
    await mutation('red-replay', 'src/main/java/com/mhtextractor/uicore/FrontendPort.java', text => text
      .replace('private volatile Uiport backend;', 'private volatile Uiport backend;\n    private Runnable forbiddenReplay=()->{};')
      .replace('subscription.close();backend=replacement;', 'subscription.close();backend=replacement;forbiddenReplay.run();')
      .replace('}catch(Throwable error){if(version==connectionVersion.get())reportFailure(error,requested,unavailableForRequest);}',
        '}catch(Throwable error){forbiddenReplay=()->{operation.apply(backend);};if(version==connectionVersion.get())reportFailure(error,requested,unavailableForRequest);}'), async () => {
      const raw = await mvn('red-replay', '-Dtest=BackendUnavailableTest#successfulRebuildReplacesTheBackendWithoutReplayingTheOriginalAction test', 1);
      assert.match(raw, /D1a: original action must never be replayed/);
    });
    await mvn('green-replay', '-Dtest=BackendUnavailableTest#successfulRebuildReplacesTheBackendWithoutReplayingTheOriginalAction test');
    await mutation('red-confirm', 'src/main/java/com/mhtextractor/gui/component/NotificationHost.java', text => text.replace(
      'confirmCancellable(anchor,message,FontAwesomeSolid.EXCLAMATION_CIRCLE,onDecision,"backend.rebuild.yes","backend.rebuild.no",Styles.DANGER);',
      'error(anchor,message,FontAwesomeSolid.EXCLAMATION_CIRCLE);'), async () => {
      await mvn('red-confirm-compile', '-DskipTests compile');
      const raw = await probe('red-confirm', 'zh_CN', 1);assert.match(raw, /card-actions-exact/);
    });
    await mvn('green-confirm-compile', '-DskipTests compile');
    await probe('green-confirm', 'zh_CN');
    await mutation('red-anti-fallback', 'src/main/java/com/mhtextractor/gui/component/BackendConnectionPresenter.java',
      text => text.replace('public final class BackendConnectionPresenter', '// InProcessUiport forbidden injection\npublic final class BackendConnectionPresenter'), async () => {
        const raw = await run('red-anti-fallback', 'node.exe', [path.join(repo, '.claude-work/verify/p5a-3-anti-fallback-check.mjs')], repo, 1);
        assert.match(raw, /ANTI-FALLBACK: 1 FAILED/);
      });
    await run('green-anti-fallback', 'node.exe', [path.join(repo, '.claude-work/verify/p5a-3-anti-fallback-check.mjs')]);
    await mutation('red-selection','src/main/java/com/mhtextractor/uicore/FrontendPort.java',text=>text.replace(
      'request(p->{p.commands().cancelPreviewLoad();return null;},ignored->{},false,false,false);',
      'request(p->{p.commands().cancelPreviewLoad();return null;},ignored->{},false,false,true);'),async()=>{
      const raw=await mvn('red-selection','-Dtest=BackendUnavailableTest#selectionHighlightCleanupCannotTriggerD1 test',1);
      assert.match(raw,/Pure selection highlight is not a backend-dependent user action/);
    });
    await mvn('green-selection','-Dtest=BackendUnavailableTest#selectionHighlightCleanupCannotTriggerD1 test');
  } else if (phase === 'gui') {
    const { modules, cp } = paths();
    for (const locale of ['zh_CN', 'en_US']) {
      const cwd = `H:/TEST/mht/p5a-4-gui-${locale}`;fs.mkdirSync(`${cwd}/logs`, { recursive: true });
      const result = `${cwd}/logs/test-results.txt`;fs.rmSync(result, { force: true });
      fs.writeFileSync(`${cwd}/logs/gui-state.json`, JSON.stringify({ locale }));
      const started = Date.now();
      const raw = await run('gui-' + locale, `${jdk}/bin/java.exe`, ['--module-path', modules, '--add-modules', 'javafx.controls,javafx.fxml',
        '-Dmht.uiport=ipc', '-Dmht.backend.transport=jvm', '-Dtest.mode=true', '-cp', cp, 'com.mhtextractor.gui.acceptance.TestMain'], cwd, 0, 540000);
      assert.ok(fs.statSync(result).mtimeMs >= started);assert.doesNotMatch(raw, /Exception in Application start method/);
      const text = fs.readFileSync(result, 'utf8');fs.writeFileSync(`${out}/gui-${locale}-results.txt`, text);console.log(text.slice(-600));
      assert.match(text, /=== RESULTS: 34 passed, 0 failed ===/);assert.doesNotMatch(text, /\[FAIL\]/);
      if(locale==='en_US'){
        const applicationLog=fs.readFileSync(`${cwd}/logs/mht-extractor.log`,'utf8');
        const cjk=applicationLog.match(/[\u3400-\u9fff]/g)||[];
        console.log('EN_APPLICATION_LOG_CJK='+cjk.length);assert.equal(cjk.length,0);
      }
    }
  } else if (phase === 'facts') {
    const properties=text=>new Map(text.split(/\r?\n/).filter(l=>l.trim()&&!l.startsWith('#')).map(l=>{const i=l.indexOf('=');return [l.slice(0,i),l.slice(i+1)];}));
    const bundles=[];
    for(const locale of ['zh_CN','en_US']){
      const relative=`src/main/resources/i18n/messages_${locale}.properties`;
      const current=properties(fs.readFileSync(path.join(repo,relative),'utf8'));
      const original=properties(execFileSync('git.exe',['show','HEAD:'+relative],{cwd:repo,encoding:'utf8'}));
      const added=[...current.keys()].filter(k=>!original.has(k));
      assert.equal(added.length,6);
      for(const [key,value] of original)assert.equal(current.get(key),value,'Existing key changed: '+key);
      console.log(`BUNDLE ${locale} keys=${current.size} added=${added.join(',')} existingChanged=0`);
      bundles.push(current);
    }
    assert.deepEqual([...bundles[0].keys()].sort(),[...bundles[1].keys()].sort());
    for(const key of bundles[0].keys())assert.deepEqual((bundles[0].get(key).match(/\{[^}]+\}/g)||[]).sort(),(bundles[1].get(key).match(/\{[^}]+\}/g)||[]).sort(),key);
    const mc='src/main/java/com/mhtextractor/gui/controller/MainController.java';
    const content=fs.readFileSync(path.join(repo,mc));
    const lines=content.toString('utf8').trimEnd().split('\n').length;
    assert.ok(lines<=1627);
    console.log(`MAINCONTROLLER lines=${lines} SHA256=${hash(content)}`);
    console.log('BUNDLE_KEYS_PLACEHOLDERS_MATCH existing-log-values-unchanged');
    await run('facts-anti-fallback','node.exe',[path.join(repo,'.claude-work/verify/p5a-3-anti-fallback-check.mjs')]);
    await run('diff-check','git.exe',['diff','--check']);
  } else if (phase === 'native') {
    Object.assign(env,{BUILD_JAVA_HOME:jdk,GRAALVM_HOME:jdk,NATIVE_RECIPE:'static',
      MAVEN_CMD:path.join(repo,'mvnw.cmd'),MAVEN_OPTS:'-Dmaven.repo.local=H:/TEST/m2/repository',
      TEMP:'H:\\TEST\\temp',TMP:'H:\\TEST\\temp',OUTPUT_DIR:'H:\\TEST\\mht\\p5a-4-native-dist',
      LOG_DIR:'H:\\TEST\\mht\\p5a-4-native-logs',NATIVE_SMOKE_DIR:'H:\\TEST\\mht\\p5a-4-native-smoke'});
    // Fixed fresh task directories, all resolved inside the explicitly named H:\TEST\mht root.
    for(const key of ['OUTPUT_DIR','LOG_DIR','NATIVE_SMOKE_DIR'])assert.ok(path.resolve(env[key]).toLowerCase().startsWith('h:\\test\\mht\\'));
    await run('native-build','cmd.exe',['/d','/c','chcp 65001 >nul && build-native.cmd --skip-package'],repo,0,1200000);
  } else if (phase === 'existing-probes') {
    for (const name of ['NotificationAcceptance', 'BackendConnectionProbe'])
      for (const locale of ['zh_CN', 'en_US']) await probe(`${name}-${locale}`, locale, 0, name);
  } else { throw new Error('Unknown phase: ' + phase); }
} finally {
  const audit = compare(before, snapshot());fs.writeFileSync(`${out}/${phase}-c-temp-audit.json`, JSON.stringify(audit, null, 2));
  console.log('C_TEMP_PROJECT_STATUS ' + audit.projectStatus);
}
