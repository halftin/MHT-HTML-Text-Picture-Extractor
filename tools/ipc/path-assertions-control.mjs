// Assertion-only controls: exercise the real guard with in-memory file responses.
// This does not verify a backend, console, filesystem, or the --unicode-controls workflow.
import fs from 'node:fs';
import assert from 'node:assert/strict';
import { exactText } from './path-assertions.mjs';

const read = fs.readFileSync, list = fs.readdirSync;
const expected = 'かな カナ 漢字 / 한글 한국어 / 😀 ＡＢＣ（全角） / 中文 空格.点号.v1';
let actual = expected;
const entry = { name: 'unicode.txt', isSymbolicLink: () => false, isDirectory: () => false };
fs.readdirSync = () => [entry];
fs.readFileSync = () => Buffer.from(actual, 'utf8');
try {
  exactText('H:/TEST/mht/p4-10-memory-only', { 'unicode.txt': expected });
  console.log('TEXT ASSERTION-ONLY CONTROL: literal Unicode=PASS');
  actual = expected.replace(/[^\x00-\x7F]/gu, '?');
  assert.throws(() => exactText('H:/TEST/mht/p4-10-memory-only', { 'unicode.txt': expected }), /TEXT_EXACT_GUARD/);
  console.log('TEXT ASSERTION-ONLY CONTROL: injected question marks=FAIL (intended TEXT_EXACT_GUARD)');
  actual = expected;
  exactText('H:/TEST/mht/p4-10-memory-only', { 'unicode.txt': expected });
  console.log('TEXT ASSERTION-ONLY CONTROL: mutation removed=PASS; no files written; backend/filesystem NOT VERIFIED');
} finally {
  fs.readFileSync = read;
  fs.readdirSync = list;
}
