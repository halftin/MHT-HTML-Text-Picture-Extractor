// P4-10: read-only C temp inventory; evidence is written only to H:/TEST/mht.
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { under, utf8 } from './path-assertions.mjs';

const roots = [(process.env.LOCALAPPDATA || '') + '/Temp', 'C:/Windows/Temp'];
const projectRoot = roots[0];

export function snapshot() {
  const entries = [], errors = [];
  function walk(dir) {
    let children;
    try { children = fs.readdirSync(dir, { withFileTypes: true }); }
    catch (error) { errors.push({ path: dir, code: error.code }); return; }
    for (const child of children) {
      const full = dir + '/' + child.name;
      try {
        const stat = fs.lstatSync(full);
        entries.push({ path: full, directory: child.isDirectory(), bytes: stat.size,
          createdMs: stat.birthtimeMs, modifiedMs: stat.mtimeMs });
        // Inventory other applications at the temp root; recurse only into project artifacts.
        // DSH snapshots can contain a whole workspace and are outside the product assertion.
        if (child.isDirectory() && category(full) === 'project') {
          if (child.isSymbolicLink()) errors.push({ path: full, code: 'PROJECT_LINK_NOT_FOLLOWED' });
          else walk(full);
        }
      } catch (error) { errors.push({ path: full, code: error.code }); }
    }
  }
  for (const root of roots) walk(root);
  entries.sort((a, b) => a.path.localeCompare(b.path));
  return { capturedAt: new Date().toISOString(), roots, entries, errors,
    inventoryScope: 'Temp root entries in both roots; recurse only into mht-extractor-* project artifacts' };
}

export function category(file) {
  const normal = file.replaceAll('\\', '/').toLowerCase();
  for (const root of roots) {
    const prefix = root.toLowerCase() + '/';
    if (!normal.startsWith(prefix)) continue;
    const name = normal.slice(prefix.length).split('/')[0];
    if (root === projectRoot && name.startsWith('mht-extractor-')) return 'project';
    if (name.startsWith('dsh-workspace-changes-')) return 'dshSnapshots';
    if (name.startsWith('dd_backgrounddownload_')) return 'visualStudio';
  }
  return 'other';
}

export function compare(before, after) {
  const old = new Map(before.entries.map(entry => [entry.path, entry]));
  const added = [], modified = [];
  const counts = { project: 0, dshSnapshots: 0, visualStudio: 0, other: 0 };
  const modifiedCounts = { ...counts };
  for (const entry of after.entries) {
    const prior = old.get(entry.path);
    if (!prior) { added.push(entry); counts[category(entry.path)]++; }
    else if (entry.createdMs !== prior.createdMs || entry.modifiedMs !== prior.modifiedMs || entry.bytes !== prior.bytes) {
      modified.push(entry); modifiedCounts[category(entry.path)]++;
    }
  }
  const unreadable = [...before.errors, ...after.errors];
  // Errors in other applications' subdirectories do not hide the project root names.
  // A failure to enumerate Temp itself or any existing mht-extractor-* subtree does.
  const projectUnreadable = unreadable.filter(error =>
    error.path.replaceAll('\\', '/').toLowerCase() === projectRoot.toLowerCase() || category(error.path) === 'project');
  return { before: before.capturedAt, after: after.capturedAt, counts, modifiedCounts, added, modified,
    unreadable, projectUnreadable,
    projectStatus: counts.project ? 'FAIL' : projectUnreadable.length ? 'UNVERIFIED' : 'PASS',
    scope: '%LOCALAPPDATA%/Temp/mht-extractor-* and descendants',
    classificationScope: after.inventoryScope,
    unverified: ['Entire C drive', 'Files created and deleted between snapshots',
      ...(unreadable.length ? ['Unreadable entries listed in unreadable'] : [])] };
}

function controls() {
  const baseline = { capturedAt: 'before', entries: [], errors: [] };
  const project = { path: projectRoot + '/mht-extractor-preview/new.txt', bytes: 1, createdMs: 1, modifiedMs: 1 };
  const unrelated = [
    { ...project, path: projectRoot + '/dsh-workspace-changes-control/new.json' },
    { ...project, path: projectRoot + '/dd_BackgroundDownload_control.log' }
  ];
  const red = compare(baseline, { ...baseline, entries: [project, ...unrelated] });
  assert.equal(red.projectStatus, 'FAIL');
  assert.deepEqual(red.counts, { project: 1, dshSnapshots: 1, visualStudio: 1, other: 0 });
  const green = compare(baseline, { ...baseline, entries: unrelated });
  assert.equal(green.projectStatus, 'PASS');
  assert.equal(compare(baseline, { ...baseline, errors: [{ path: projectRoot, code: 'EACCES' }] }).projectStatus, 'UNVERIFIED');
  console.log('C-AUDIT SNAPSHOT CONTROL: project violation=FAIL unrelated additions=PASS unreadable root=UNVERIFIED');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const argv = process.argv.slice(2);
  if (argv.includes('--controls')) controls();
  else if (argv.includes('--print')) console.log(JSON.stringify(snapshot()));
  else {
    const index = argv.indexOf('--root');
    assert.ok(index >= 0 && argv[index + 1], 'Supply --root H:/TEST/mht/p4-10-...');
    const root = path.resolve(argv[index + 1]);
    under(root, 'H:/TEST/mht');
    assert.ok(path.basename(root).startsWith('p4-10-'), 'P4-10 evidence root required');
    assert.ok(argv.includes('--before') !== argv.includes('--after'), 'Choose exactly one of --before or --after');
    fs.mkdirSync(root, { recursive: true });
    const phase = argv.includes('--before') ? 'before' : 'after';
    const file = path.join(root, 'c-' + phase + '.json');
    assert.ok(!fs.existsSync(file), 'Use fresh evidence; refuse to overwrite ' + file);
    const current = snapshot();
    fs.writeFileSync(file, JSON.stringify(current, null, 2));
    if (phase === 'before') console.log(`C-AUDIT BEFORE: entries=${current.entries.length} unreadable=${current.errors.length}`);
    else {
      const result = compare(JSON.parse(utf8(path.join(root, 'c-before.json'))), current);
      fs.writeFileSync(path.join(root, 'c-audit.json'), JSON.stringify(result, null, 2));
      console.log('C-AUDIT: ' + JSON.stringify(result));
      process.exitCode = result.projectStatus === 'PASS' ? 0 : result.projectStatus === 'FAIL' ? 1 : 2;
    }
  }
}
