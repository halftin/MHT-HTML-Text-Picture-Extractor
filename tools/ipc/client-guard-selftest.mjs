// Run with: node tools/ipc/client-guard-selftest.mjs (no backend or filesystem setup).
import assert from 'node:assert/strict';
import { isEventSummaryValue } from './client-guard.mjs';

const ref = { path: '...bulk-*.json', bytes: 247, mediaType: 'application/json' };
// Real P5b-4 workspaceChanged payload; only the generated paths were abbreviated.
const workspaceChanged = {
  kind: 'ITEMS_ADDED', undoKind: null, affected: ref, suggestedSelection: null, revision: 2,
  snapshot: { path: '...bulk-*.json', bytes: 1484, mediaType: 'application/json' }
};
const cases = [
  ['workspaceChanged data', workspaceChanged, true],
  ['null', { a: null }, true],
  ['string', { a: '' }, true],
  ['number', { a: 0 }, true],
  ['boolean', { a: false }, true],
  ['file reference', { a: ref }, true],
  ['file reference with reordered keys', { a: { mediaType: ref.mediaType, bytes: 0, path: '' } }, true],
  ['flat string mapping', { namedParams: { a: 'b' } }, true],
  ['empty string mapping', { namedParams: {} }, true],
  ['backendLog data', { level: 'WARN', key: 'log.imageextractor.01', timestamp: 1,
    namedParams: { '0': '', '$key.0': 'extract.region.r2' }, revision: 8 }, true],
  ['mapping with number value', { namedParams: { a: 1 } }, false],
  ['mapping with object value', { namedParams: { a: { b: 'c' } } }, false],
  ['mapping with array value', { namedParams: { a: ['b'] } }, false],
  ['mapping with mixed value types', { namedParams: { a: 'b', c: false } }, false],
  ['inline array', { a: [1, 2] }, false],
  ['empty array', { a: [] }, false],
  ['arbitrary object', { a: { x: 1 } }, false],
  ['empty object as string mapping', { a: {} }, true],
  ['reference with extra key', { a: { ...ref, extra: 1 } }, false],
  ['reference with wrong types', { a: { path: 1, bytes: 'x', mediaType: null } }, false],
  ['reference with wrong path type', { a: { ...ref, path: 1 } }, false],
  ['reference with wrong bytes type', { a: { ...ref, bytes: '247' } }, false],
  ['reference with string bytes and extra key', { a: { ...ref, bytes: '247', extra: 'x' } }, false],
  ['reference with wrong mediaType type', { a: { ...ref, mediaType: null } }, false],
  ['reference missing path', { a: { bytes: ref.bytes, mediaType: ref.mediaType } }, false],
  ['reference missing bytes', { a: { path: ref.path, mediaType: ref.mediaType } }, false],
  ['reference missing mediaType', { a: { path: ref.path, bytes: ref.bytes } }, false],
  ['reference with three wrong keys', { a: { path: ref.path, bytes: ref.bytes, extra: ref.mediaType } }, false],
  ['nested object', { a: { nested: { x: 1 } } }, false],
  ['nested reference', { a: { nested: ref } }, false],
  ['reference with nested path', { a: { ...ref, path: { x: 1 } } }, false],
  ['undefined', { a: undefined }, false],
  ['function', { a: () => {} }, false],
  ['symbol', { a: Symbol('summary') }, false],
  ['bigint', { a: 1n }, false]
];

let failures = 0;
for (const [name, data, expected] of cases) {
  const actual = Object.values(data).every(isEventSummaryValue);
  try {
    assert.equal(actual, expected, name);
    console.log(`PASS ${name}: expected=${expected ? 'accept' : 'reject'} actual=${actual ? 'accept' : 'reject'}`);
  } catch (error) {
    failures++;
    console.error(`FAIL ${name}: expected=${expected ? 'accept' : 'reject'} actual=${actual ? 'accept' : 'reject'}`);
    console.error(error.stack);
  }
}
console.log(`CLIENT_GUARD_SELFTEST: ${cases.length - failures} passed, ${failures} failed`);
process.exitCode = failures ? 1 : 0;
