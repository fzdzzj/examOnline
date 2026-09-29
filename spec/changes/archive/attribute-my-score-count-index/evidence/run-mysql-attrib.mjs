#!/usr/bin/env node
// run-mysql-attrib.mjs — attribute-my-score-count-index B1/B2/B3 容器侧执行器。
// 只做忠实记录：每条 docker exec 的原始输出、退出码、ISO 时间戳逐字落盘；不挑轮、不改写。
// 用法（仓库根）：
//   node spec/changes/attribute-my-score-count-index/evidence/run-mysql-attrib.mjs \
//     --phase setup|seed|reads|writes|index|postcheck \
//     --arm baseline|candidate|drift \
//     --capture <capture.json> --out <rawDir> [--rounds N] [--warmup N]
// 环境变量：ATTR_CONTAINER（容器名）、ATTR_DB（库名）、ATTR_MYSQL_PWD（root 口令，不入库）。

import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, appendFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const arg = (name, dflt) => {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 ? process.argv[i + 1] : dflt;
};
const phase = arg('phase');
const arm = arg('arm', 'baseline');
const outDir = resolve(arg('out', '.'));
const rounds = parseInt(arg('rounds', '5'), 10);
const warmup = parseInt(arg('warmup', '2'), 10);
const capturePath = arg('capture');
const container = process.env.ATTR_CONTAINER;
const dbName = process.env.ATTR_DB;
const pwd = process.env.ATTR_MYSQL_PWD;
if (!container || !dbName || !pwd) {
  console.error('need ATTR_CONTAINER / ATTR_DB / ATTR_MYSQL_PWD');
  process.exit(2);
}
mkdirSync(outDir, { recursive: true });
const log = (file, text) => appendFileSync(join(outDir, file), text + '\n');

const SIZES = [50, 200, 1000, 3000];
const EXAM_ID_BASE = 930000000;
const STUDENT_ID_BASE = 930000000;
const WRITE_EXAM_SUMMARIZE = 950000001;
const WRITE_EXAM_SUBMIT = 950000002;
const INSERT_EXAM_BASE = { baseline: 941000000, candidate: 942000000 };
const idxName = 'idx_submissions_score_rank';

const score = (i) => (400 + ((i * 7919) % 300)) / 10; // = BigDecimal.valueOf(400+(i*7919)%300, 1)
const fmt1 = (x) => x.toFixed(1);
const nowIso = () => new Date().toISOString();

function mysql(sqlText, { quiet } = {}) {
  const r = spawnSync('docker', ['exec', '-i', '-e', 'MYSQL_PWD=' + pwd, container, 'mysql', '-uroot',
    '--default-character-set=utf8mb4', dbName],
    { input: sqlText, encoding: 'utf8', maxBuffer: 1 << 28 });
  if (!quiet && r.status !== 0) {
    console.error(`mysql exit=${r.status} stderr=${r.stderr}`);
  }
  return r;
}

// 从捕获 JSON 机械重建字面量 SQL：按 ? 顺序替换 [examId, status, X]。
function literalCountSql(shape, X) {
  const cap = JSON.parse(readFileSync(capturePath, 'utf8'));
  const s = cap.shapes.find((x) => x.n === shape);
  if (!s) throw new Error('shape missing in capture: ' + shape);
  const vals = [String(s.examId), '3', X];
  let idx = 0;
  const out = s.countStatement.sql.replace(/\?/g, () => vals[idx++]);
  if (idx !== 3) throw new Error('expected 3 placeholders, replaced ' + idx);
  return out;
}

function runRounded(file, shape, X, nRounds, nWarmup, withExplain) {
  const sql = literalCountSql(shape, X);
  const entry = { shape, x: X, startedAtIso: nowIso(), warmup: nWarmup, rounds: [] };
  for (let w = 0; w < nWarmup; w++) {
    mysql(sql, { quiet: true });
  }
  if (withExplain) {
    const e = mysql('EXPLAIN ' + sql);
    entry.explainRaw = e.stdout;
    log(file, `=== EXPLAIN shape=${shape} x=${X} at=${nowIso()} exit=${e.status}\n${e.stdout}`);
  }
  for (let r = 1; r <= nRounds; r++) {
    const c = mysql(sql);
    const a = mysql('EXPLAIN ANALYZE ' + sql);
    const countValue = (c.stdout.trim().split('\n').pop() || '').trim();
    entry.rounds.push({
      round: r,
      countExit: c.status,
      analyzeExit: a.status,
      countValue,
      countRaw: c.stdout,
      analyzeRaw: a.stdout,
      atIso: nowIso(),
    });
    log(file, `=== ROUND shape=${shape} x=${X} round=${r} count=${countValue} at=${entry.rounds[r - 1].atIso}`
      + `\n--- COUNT (exit ${c.status}):\n${c.stdout}\n--- EXPLAIN ANALYZE (exit ${a.status}):\n${a.stdout}`);
  }
  return entry;
}

const summary = {};

if (phase === 'setup') {
  const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..');
  const schemaPath = join(repoRoot, 'src/main/resources/schema.sql');
  console.log('waiting for mysql ready...');
  let ready = false;
  for (let i = 0; i < 120; i++) {
    const r = spawnSync('docker', ['exec', '-e', 'MYSQL_PWD=' + pwd, container,
      'mysqladmin', 'ping', '-uroot', '--silent'], { encoding: 'utf8' });
    if (r.status === 0) { ready = true; break; }
    spawnSync('node', ['-e', 'setTimeout(()=>{},2000)']);
  }
  if (!ready) { console.error('mysql never became ready'); process.exit(1); }
  const digest = spawnSync('docker', ['image', 'inspect', 'mysql:8.0',
    '--format', '{{index .RepoDigests 0}}'], { encoding: 'utf8' });
  const version = mysql('SELECT VERSION();');
  const apply = mysql(readFileSync(schemaPath, 'utf8'));
  const showCreate = mysql('SHOW CREATE TABLE exam_submissions\\G');
  const idx = mysql('SHOW INDEX FROM exam_submissions');
  summary.schemaApplyExit = apply.status;
  writeFileSync(join(outDir, 'container-setup.log'),
    `startedAtIso=${nowIso()}\nimageDigest=${digest.stdout.trim()} (exit ${digest.status})\n`
    + `--- SELECT VERSION():\n${version.stdout}\n--- schema.sql apply exit=${apply.status}\n${apply.stdout}\n${apply.stderr}\n`
    + `--- SHOW CREATE TABLE exam_submissions:\\G\n${showCreate.stdout}\n`
    + `--- SHOW INDEX FROM exam_submissions:\n${idx.stdout}\n`);
  console.log('setup done, schema apply exit=' + apply.status);
  process.exit(apply.status === 0 ? 0 : 1);
}

if (phase === 'seed') {
  const t = [];
  const T0 = '2026-09-29 00:00:00';
  const T1 = '2026-09-29 01:00:00';
  t.push('SET autocommit=0;');
  for (const n of SIZES) {
    const examId = EXAM_ID_BASE + n;
    t.push(`DELETE FROM exam_submissions WHERE exam_id = ${examId};`);
    t.push(`DELETE FROM exams WHERE id = ${examId};`);
    t.push(`INSERT INTO exams (id, title, paper_id, start_time, end_time, duration_minutes, status, published,`
      + ` created_by, version, created_time, updated_time) VALUES (${examId}, 'myscore-count-attr-n${n}',`
      + ` 930000001, '${T0}', '${T1}', 60, 4, 1, 930000999, 0, NOW(), NOW());`);
    for (let start = 0; start < n; start += 500) {
      const rows = [];
      for (let i = start; i < Math.min(start + 500, n); i++) {
        const sid = STUDENT_ID_BASE + n * 100000 + i;
        const s = fmt1(score(i));
        rows.push(`(${examId}, ${sid}, '${T0}', '${T1}', 3, ${s}, 0.0, ${s}, 1, 0, 0, NOW(), NOW())`);
      }
      t.push(`INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time, status,`
        + ` objective_score, subjective_score, total_score, grading_status, partial_graded, version,`
        + ` created_time, updated_time) VALUES ${rows.join(', ')};`);
    }
  }
  // 写路径专用造数：A=汇总 CAS（3000 行 status=2、total_score NULL 待首写）；B=交卷 CAS（3000 行 status=1）
  for (const [examId, status, withScore] of [[WRITE_EXAM_SUMMARIZE, 2, true], [WRITE_EXAM_SUBMIT, 1, false]]) {
    t.push(`DELETE FROM exam_submissions WHERE exam_id = ${examId};`);
    for (let start = 0; start < 3000; start += 500) {
      const rows = [];
      for (let i = start; i < Math.min(start + 500, 3000); i++) {
        const sid = STUDENT_ID_BASE + 500000 + examId % 10 * 1000000 + i;
        const obj = withScore ? fmt1(score(i)) : 'NULL';
        rows.push(`(${examId}, ${sid}, '${T0}', '${T1}', ${status}, ${obj}, NULL, NULL, ${withScore ? 1 : 0}, 0, 0, NOW(), NOW())`);
      }
      t.push(`INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time, status,`
        + ` objective_score, subjective_score, total_score, grading_status, partial_graded, version,`
        + ` created_time, updated_time) VALUES ${rows.join(', ')};`);
    }
  }
  t.push('COMMIT;');
  const r = mysql(t.join('\n'));
  const counts = mysql(`SELECT exam_id, COUNT(*) AS c FROM exam_submissions WHERE exam_id IN`
    + ` (${SIZES.map((n) => EXAM_ID_BASE + n).join(',')},${WRITE_EXAM_SUMMARIZE},${WRITE_EXAM_SUBMIT})`
    + ` GROUP BY exam_id ORDER BY exam_id;`);
  writeFileSync(join(outDir, 'seed.log'),
    `atIso=${nowIso()} seedExit=${r.status}\n${r.stderr}\n--- counts:\n${counts.stdout}`);
  const parsed = {};
  for (const line of counts.stdout.trim().split('\n').slice(1)) {
    const [examId, c] = line.split('\t');
    parsed[examId] = parseInt(c, 10);
  }
  writeFileSync(join(outDir, 'seed-counts.json'), JSON.stringify(parsed, null, 2));
  console.log('seed exit=' + r.status + '\n' + counts.stdout);
  process.exit(r.status === 0 ? 0 : 1);
}

if (phase === 'reads') {
  const file = `reads-${arm}.log`;
  const entries = [];
  const nRounds = arm === 'drift' ? 2 : rounds;
  const withExplain = true;
  for (const n of SIZES) {
    entries.push(runRounded(file, n, '40.0', nRounds, warmup, withExplain));
  }
  // 辅助臂 X_mid（只记录不裁决）：drift 阶段不跑
  if (arm !== 'drift') {
    for (const n of SIZES) {
      entries.push(runRounded(file, n, fmt1(score(Math.floor(n / 2) + 1)), nRounds, warmup, withExplain));
    }
  }
  writeFileSync(join(outDir, `reads-${arm}.json`), JSON.stringify({ arm, finishedAtIso: nowIso(), entries }, null, 2));
  console.log(`reads(${arm}) done: ${entries.length} series`);
  process.exit(0);
}

if (phase === 'writes') {
  // 生产原文语句（与 PREREGISTRATION.md §3 逐字一致；客户端占位由脚本按轮机械填充）
  const casSummarize = (id, subj, total, partial) =>
    `UPDATE exam_submissions SET status = 3, subjective_score = ${subj}, total_score = ${total},`
    + ` partial_graded = ${partial}, version = version + 1, updated_time = CURRENT_TIMESTAMP`
    + ` WHERE id = ${id} AND status IN (2, 3);`;
  const casSubmit = (id) =>
    `UPDATE exam_submissions SET status = 2, submit_time = '2026-09-29 00:30:00', submit_type = 1,`
    + ` version = version + 1, updated_time = CURRENT_TIMESTAMP`
    + ` WHERE id = ${id} AND status = 1;`;
  const results = { arm, finishedAtIso: '', insertRounds: [], summarizeRounds: [], submitRounds: [] };

  const ids = (examId) => {
    const ids = mysql(`SELECT id FROM exam_submissions WHERE exam_id = ${examId} ORDER BY id;`);
    return ids.stdout.trim().split('\n').slice(1).map((l) => l.split('\t')[0]);
  };

  // INSERT：每轮 3000 行新 exam_id，单事务；轮序固定 1→3
  for (let r = 1; r <= 3; r++) {
    const examId = INSERT_EXAM_BASE[arm] + r;
    const lines = ['SET autocommit=0;'];
    for (let start = 0; start < 3000; start += 500) {
      const rows = [];
      for (let i = start; i < Math.min(start + 500, 3000); i++) {
        const sid = STUDENT_ID_BASE + 900000 + (examId % 1000) * 1000000 + i;
        rows.push(`(${examId}, ${sid}, '2026-09-29 00:00:00', '2026-09-29 01:00:00', 1, NULL, NULL, NULL, 0, 0, 0, NOW(), NOW())`);
      }
      lines.push(`INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time, status,`
        + ` objective_score, subjective_score, total_score, grading_status, partial_graded, version,`
        + ` created_time, updated_time) VALUES ${rows.join(', ')};`);
    }
    lines.push('COMMIT;');
    const t0 = Date.now();
    const r0 = mysql(lines.join('\n'));
    const wallMs = Date.now() - t0;
    results.insertRounds.push({ round: r, examId, exit: r0.status, wallMs, atIso: nowIso() });
    log(`writes-${arm}.log`, `=== INSERT round=${r} examId=${examId} exit=${r0.status} wallMs=${wallMs} at=${nowIso()}`);
  }
  // 臂末非计时清理本臂 INSERT 行（恢复两臂同一起测行数）
  const lo = INSERT_EXAM_BASE[arm] + 1;
  mysql(`DELETE FROM exam_submissions WHERE exam_id BETWEEN ${lo} AND ${lo + 2};`);

  // 汇总 CAS：A 场 3000 行 2→3（total_score 首写），轮间非计时复位
  const sumIds = ids(WRITE_EXAM_SUMMARIZE);
  if (sumIds.length !== 3000) { console.error('summarize ids != 3000'); process.exit(1); }
  for (let r = 1; r <= 3; r++) {
    const lines = ['SET autocommit=0;'];
    for (let i = 0; i < sumIds.length; i++) {
      lines.push(casSummarize(sumIds[i], '0.0', fmt1(score(i)), 0));
    }
    lines.push('COMMIT;');
    const t0 = Date.now();
    const r0 = mysql(lines.join('\n'));
    const wallMs = Date.now() - t0;
    const affected = (r0.stdout.match(/(\d+) rows affected/) || [])[1];
    results.summarizeRounds.push({ round: r, exit: r0.status, wallMs, rowsAffectedHint: affected || null, atIso: nowIso() });
    log(`writes-${arm}.log`, `=== casSummarize round=${r} exit=${r0.status} wallMs=${wallMs} at=${nowIso()} stdout:\n${r0.stdout}`);
    mysql(`UPDATE exam_submissions SET status = 2, subjective_score = NULL, total_score = NULL,`
      + ` partial_graded = 0, version = 0 WHERE exam_id = ${WRITE_EXAM_SUMMARIZE};`, { quiet: true });
  }

  // 交卷 CAS：B 场 3000 行 1→2，轮间非计时复位
  const subIds = ids(WRITE_EXAM_SUBMIT);
  if (subIds.length !== 3000) { console.error('submit ids != 3000'); process.exit(1); }
  for (let r = 1; r <= 3; r++) {
    const lines = ['SET autocommit=0;'];
    for (const id of subIds) lines.push(casSubmit(id));
    lines.push('COMMIT;');
    const t0 = Date.now();
    const r0 = mysql(lines.join('\n'));
    const wallMs = Date.now() - t0;
    results.submitRounds.push({ round: r, exit: r0.status, wallMs, atIso: nowIso() });
    log(`writes-${arm}.log`, `=== casSubmit round=${r} exit=${r0.status} wallMs=${wallMs} at=${nowIso()} stdout:\n${r0.stdout}`);
    mysql(`UPDATE exam_submissions SET status = 1, submit_time = NULL, submit_type = NULL,`
      + ` version = 0 WHERE exam_id = ${WRITE_EXAM_SUBMIT};`, { quiet: true });
  }

  results.finishedAtIso = nowIso();
  writeFileSync(join(outDir, `writes-${arm}.json`), JSON.stringify(results, null, 2));
  console.log(`writes(${arm}) done`);
  process.exit(0);
}

if (phase === 'index') {
  const sql = arm === 'candidate'
    ? `ALTER TABLE exam_submissions ADD INDEX ${idxName} (exam_id, status, total_score);`
    : `ALTER TABLE exam_submissions DROP INDEX ${idxName};`;
  const r2 = mysql(sql);
  const show = mysql('SHOW CREATE TABLE exam_submissions\\G');
  writeFileSync(join(outDir, `index-${arm}.log`),
    `atIso=${nowIso()} exit=${r2.status}\n${sql}\n${r2.stdout}${r2.stderr}\n--- SHOW CREATE TABLE:\n${show.stdout}\n`);
  console.log(`index(${arm}) exit=${r2.status}`);
  process.exit(r2.status === 0 ? 0 : 1);
}

if (phase === 'postcheck') {
  const counts = mysql(`SELECT exam_id, COUNT(*) AS c FROM exam_submissions WHERE exam_id IN`
    + ` (${SIZES.map((n) => EXAM_ID_BASE + n).join(',')}) GROUP BY exam_id ORDER BY exam_id;`);
  const idx = mysql('SHOW INDEX FROM exam_submissions');
  writeFileSync(join(outDir, 'postcheck.log'),
    `atIso=${nowIso()}\n--- counts:\n${counts.stdout}\n--- SHOW INDEX:\n${idx.stdout}\n`);
  console.log(counts.stdout);
  process.exit(0);
}

console.error('unknown phase: ' + phase);
process.exit(2);
