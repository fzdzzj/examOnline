#!/usr/bin/env node
// diagnose-optimizer.mjs — 诊断（非预登记裁决臂）：解释 n=1000/3000 + X=40.0 下优化器
// 为何拒绝 idx_submissions_score_rank，并测「若强制走覆盖索引」的潜力。
// 产出 optimizer-trace.log 与 force-index.log（原始逐字留证）。环境变量同 run-mysql-attrib.mjs。

import { spawnSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';

const container = process.env.ATTR_CONTAINER;
const dbName = process.env.ATTR_DB;
const pwd = process.env.ATTR_MYSQL_PWD;
const outDir = process.argv[2] || '.';

const mysql = (sqlText) => spawnSync('docker', ['exec', '-i', '-e', 'MYSQL_PWD=' + pwd, container,
  'mysql', '-uroot', '--default-character-set=utf8mb4', dbName],
  { input: sqlText, encoding: 'utf8', maxBuffer: 1 << 28 });

const countSql = (examId, x, force) =>
  `SELECT COUNT( * ) AS total FROM exam_submissions ${force ? `FORCE INDEX (idx_submissions_score_rank)` : ``}`
  + ` WHERE (exam_id = ${examId} AND status = 3 AND total_score IS NOT NULL AND total_score > ${x});`;

const trace1 = mysql(
  `SET optimizer_trace="enabled=on";\n` + countSql(930003000, '40.0', false) + `\n`
  + `SELECT TRACE FROM information_schema.OPTIMIZER_TRACE\\G\n` + `SET optimizer_trace="enabled=off";`);
writeFileSync(`${outDir}/optimizer-trace.log`,
  `atIso=${new Date().toISOString()} shape=3000 x=40.0 (no hint)\nexit=${trace1.status}\n${trace1.stdout}${trace1.stderr}\n`);

const diag = [];
for (const [n, x] of [[1000, '40.0'], [3000, '40.0'], [3000, '51.9']]) {
  const examId = 930000000 + n;
  const e1 = mysql('EXPLAIN ' + countSql(examId, x, true));
  diag.push(`=== FORCE EXPLAIN shape=${n} x=${x} exit=${e1.status}\n${e1.stdout}`);
  const rounds = [];
  for (let r = 1; r <= 3; r++) {
    const a = mysql('EXPLAIN ANALYZE ' + countSql(examId, x, true));
    rounds.push(a.stdout);
    diag.push(`=== FORCE ANALYZE shape=${n} x=${x} round=${r} exit=${a.status} at=${new Date().toISOString()}\n${a.stdout}`);
  }
}
const nohint = [];
for (const [n, x] of [[1000, '40.0'], [3000, '40.0']]) {
  const examId = 930000000 + n;
  for (let r = 1; r <= 3; r++) {
    const a = mysql('EXPLAIN ANALYZE ' + countSql(examId, x, false));
    nohint.push(`=== NOHINT ANALYZE shape=${n} x=${x} round=${r} exit=${a.status} at=${new Date().toISOString()}\n${a.stdout}`);
  }
}
writeFileSync(`${outDir}/force-index.log`, diag.join('\n') + '\n' + nohint.join('\n') + '\n');
console.log('diagnostics done');
