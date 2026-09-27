// 恢复前核对：把候选恢复文本的每个反引号换成 't'，应当与"被损坏的文件"逐字符相同
// 用法: node verify-recovery.js <候选干净文本> <被损坏的文件> [--strip-marker]
const fs = require('fs');

const [cleanPath, brokenPath, ...flags] = process.argv.slice(2);

/** 去掉 BOM、统一换行（PS 管道导出会带 BOM/CRLF；仓库内是 LF 无 BOM） */
function normalize(s) {
  return s.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n');
}

let clean = normalize(fs.readFileSync(cleanPath, 'utf8'));
const broken = normalize(fs.readFileSync(brokenPath, 'utf8'));

if (flags.includes('--strip-marker')) {
  clean = clean.replace(/\n?<\/system-reminder>\s*$/, '');
}
clean = clean.replace(/\n*$/, '\n');

const asBroken = clean.replace(/`/g, 't');
const same = asBroken === broken;

console.log(`clean=${clean.length} chars   broken=${broken.length} chars`);
console.log(`backticks in clean = ${(clean.match(/`/g) || []).length}`);
console.log(`IDENTICAL after backtick->t : ${same}`);

if (!same) {
  const n = Math.min(asBroken.length, broken.length);
  let i = 0;
  while (i < n && asBroken[i] === broken[i]) i++;
  const from = Math.max(0, i - 60);
  console.log(`first difference at index ${i}`);
  console.log('  clean->t : ' + JSON.stringify(asBroken.slice(from, i + 60)));
  console.log('  broken   : ' + JSON.stringify(broken.slice(from, i + 60)));
  const lineOf = (s, idx) => s.slice(0, idx).split('\n').length;
  console.log(`  line: clean ${lineOf(asBroken, i)} / broken ${lineOf(broken, i)}`);
}
