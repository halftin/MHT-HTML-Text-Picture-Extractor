// Same unnormalised file-byte SHA-256/tree contract as BaselineHasher.hashTree.
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

export function utf8(file) {
  return new TextDecoder('utf-8', { fatal: true }).decode(fs.readFileSync(file));
}

export function under(file, root) {
  const relative = path.relative(path.resolve(root), path.resolve(file));
  assert.ok(relative && !relative.startsWith('..' + path.sep) && relative !== '..'
    && !path.isAbsolute(relative), `DIRECTORY_GUARD: ${file} outside ${root}`);
}

export function tree(root, sub = '') {
  return fs.readdirSync(path.join(root, sub), { withFileTypes: true }).flatMap(entry => {
    const name = path.join(sub, entry.name);
    assert.ok(!entry.isSymbolicLink(), 'Products must not redirect through links');
    return entry.isDirectory() ? tree(root, name) : [{ path: name.replaceAll('\\', '/'),
      sha256: createHash('sha256').update(fs.readFileSync(path.join(root, name))).digest('hex') }];
  }).sort((a, b) => a.path < b.path ? -1 : a.path > b.path ? 1 : 0);
}

export function golden(root, baseline, mappings = {}) {
  const expected = JSON.parse(utf8(baseline)).products.map(item => ({ ...item,
    path: Object.entries(mappings).reduce((name, [from, to]) => name.replace(from, to), item.path)
  })).sort((a, b) => a.path < b.path ? -1 : a.path > b.path ? 1 : 0);
  assert.ok(expected.length, 'Golden baseline must not be empty');
  const actual = tree(root);
  assert.deepEqual(actual, expected, 'GOLDEN_SHA256_GUARD: full path and byte digest equality');
  return actual;
}

// Literal expectations for the new Unicode fixtures, independent of backend output.
// Compare the whole tree and whole decoded text, not merely a substring or absence of '?'.
export function exactText(root, expected) {
  const actual = tree(root);
  assert.deepEqual(actual.map(item => item.path), Object.keys(expected).sort(), 'TEXT_TREE_GUARD');
  for (const item of actual) {
    assert.equal(utf8(path.join(root, item.path)), expected[item.path], 'TEXT_EXACT_GUARD: ' + item.path);
  }
  return actual;
}
