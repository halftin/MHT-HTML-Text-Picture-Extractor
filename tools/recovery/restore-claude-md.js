// 恢复 CLAUDE.md：用 transcript 里那版干净文本覆盖，并剥掉 reminder 尾标记
const fs = require('fs');
const src = process.argv[2];
const dst = process.argv[3];

let text = fs.readFileSync(src, 'utf8');
text = text.replace(/\n?<\/system-reminder>\s*$/, '');   // 去掉 reminder 闭合标记
text = text.replace(/\n*$/, '\n');                       // 统一的单个结尾换行

fs.writeFileSync(dst, text, { encoding: 'utf8' });        // 无 BOM
console.log(`restored ${dst}: ${text.length} chars, ${text.split('\n').length - 1} lines, ` +
            `backticks=${(text.match(/`/g) || []).length}`);
