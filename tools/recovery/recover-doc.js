// 从 DSH 会话 transcript 恢复文档（会话文件 = 多个独立 zstd 帧拼接）
// 用法: node recover-doc.js <session.v4.jsonl.zstd> <输出文件> <识别串> [识别串...]
const fs = require('fs');
const zlib = require('zlib');

const [src, out, ...markers] = process.argv.slice(2);
if (!src || !out || markers.length === 0) {
  console.error('usage: node recover-doc.js <session.zstd> <out> <marker1> [marker2...]');
  process.exit(2);
}

const buf = fs.readFileSync(src);
const MAGIC = Buffer.from([0x28, 0xB5, 0x2F, 0xFD]);   // zstd frame magic (little-endian 0xFD2FB528)

const offsets = [];
for (let i = buf.indexOf(MAGIC); i !== -1; i = buf.indexOf(MAGIC, i + 4)) offsets.push(i);
console.log(`file=${buf.length}B frames(magic hits)=${offsets.length} firstMagicAt=${offsets[0]}`);

/** 解一帧；若该处是"压缩数据里恰好出现魔数"造成的假边界，就把后续几帧并进来重试 */
function decodeFrom(k) {
  for (let span = 1; span <= 4 && k + span <= offsets.length; span++) {
    const s = offsets[k];
    const e = (k + span < offsets.length) ? offsets[k + span] : buf.length;
    try { return { text: zlib.zstdDecompressSync(buf.slice(s, e)), span }; } catch { /* try wider span */ }
  }
  return null;
}

const parts = [];
let k = 0, ok = 0, skipped = 0;
while (k < offsets.length) {
  const r = decodeFrom(k);
  if (r) { parts.push(r.text); ok++; k += r.span; } else { skipped++; k++; }
}
const raw = Buffer.concat(parts).toString('utf8');
console.log(`decodedFrames=${ok} skipped=${skipped} text=${raw.length} chars`);
if (raw.length < 1000) { console.error('DECODED TOO LITTLE'); process.exit(1); }

function collectStrings(v, acc) {
  if (typeof v === 'string') acc.push(v);
  else if (v && typeof v === 'object') for (const key of Object.keys(v)) collectStrings(v[key], acc);
  return acc;
}

const lines = raw.split('\n');
console.log('text lines:', lines.length);

const candidates = [];
let lineNo = 0;
for (const line of lines) {
  lineNo++;
  if (line.length < 5000) continue;
  if (!markers.every(m => line.includes(m))) continue;
  let obj;
  try { obj = JSON.parse(line); } catch { continue; }
  for (const s of collectStrings(obj, [])) {
    if (!markers.every(m => s.includes(m))) continue;
    if (!s.includes('# CLAUDE.md') || !s.includes('验证纪律')) continue;
    const start = s.indexOf('# CLAUDE.md');
    let body = s.slice(start);
    for (const end of ['</instructions>', '\nInstructions from:', 'This file changed after it was loaded']) {
      const i = body.indexOf(end, 20);
      if (i > 0) body = body.slice(0, i);
    }
    candidates.push({ lineNo, backticks: (body.match(/`/g) || []).length, chars: body.length, body });
  }
}

console.log('candidates:', candidates.length);
candidates.forEach((c, i) => console.log(`  #${i} line=${c.lineNo} backticks=${c.backticks} chars=${c.chars}`));
if (candidates.length === 0) { console.error('NO CANDIDATE FOUND'); process.exit(1); }

candidates.sort((a, b) => b.backticks - a.backticks);
const best = candidates[0];
fs.writeFileSync(out, best.body, 'utf8');
console.log(`wrote ${out}: backticks=${best.backticks} chars=${best.chars} transcriptLine=${best.lineNo}`);
