#!/usr/bin/env node
// run-scalar-reads-mysql.mjs — project-scalar-only-submission-reads 阶段 2 容器侧执行器。
// 冻结口径见同目录 PREREGISTRATION.md §2.2/§2.3（臂、轮转、字节口径、M1–M3）。
// 只做忠实执行与逐字记录：不挑轮、不改写、失败轮原样保留为 failed 并在同位置补跑一次记 retry。
// 用法（仓库根）：
//   node spec/changes/project-scalar-only-submission-reads/evidence/run-scalar-reads-mysql.mjs \
//     --phase start|setup|seed|rounds|bytes|stop \
//     --capture <capture.json> --out <rawDir> [--shape 200] [--site s1]
// 环境变量：SCALAR_CONTAINER（容器名）、SCALAR_DB（库名）、SCALAR_MYSQL_PWD（root 口令，不入库）。

import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, appendFileSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const arg = (name, dflt) => {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 ? process.argv[i + 1] : dflt;
};
const phase = arg('phase');
const shapeArg = arg('shape') ? parseInt(arg('shape'), 10) : null;
const siteArg = arg('site', null);
const outDir = resolve(arg('out', '.'));
const capturePath = arg('capture');
const container = process.env.SCALAR_CONTAINER;
const dbName = process.env.SCALAR_DB;
const pwd = process.env.SCALAR_MYSQL_PWD;
if (!container || !dbName || !pwd) {
  console.error('need SCALAR_CONTAINER / SCALAR_DB / SCALAR_MYSQL_PWD');
  process.exit(2);
}
mkdirSync(outDir, { recursive: true });
const nowIso = () => new Date().toISOString();
const log = (file, text) => appendFileSync(join(outDir, file), text + '\n');
const sha256 = (s) => createHash('sha256').update(s, 'utf8').digest('hex');

const SIZES = [200, 1000, 3000];
const SITES = ['s1', 's2', 's3', 's4', 's5'];
const S1_BASE = 970100000;          // 站点 1 区块：50 场考试 970100000..970100049（与 IT 捕获 IN 列表同址）
const E_BASE = 970200000;           // 主考 E(n)
const F_BASE = 970300000;           // 补考 F(n)
const S1_STUDENT = 973100000;       // 站点 1 真实学生（本人 45 行）
const FILLER_BASE = 974000000;      // 站点 1 填充行学生段位
const SHARED_STUDENT_BASE = 973200000; // 共享区块应考名单（n+5 人）
const LONG_A = `CONCAT('{"1001":"', REPEAT('A',2037), '"}')`;
const LONG_P = `CONCAT('{"snapshot":"', REPEAT('B',20465), '"}')`;
const T0 = '2026-09-27 12:00:00';
const T0E = '2026-09-27 10:00:00';  // E 上 submit_time（T0 − 2h）
const T0F = '2026-09-27 11:00:00';  // F 上 submit_time（T0 − 1h，晚于 E）
const score = (i) => (((400 + ((i * 7919) % 300)) / 10)).toFixed(1);
const E = (n) => E_BASE + n;
const F = (n) => F_BASE + n;

function mysql(sqlText, { quiet, extra } = {}) {
  const r = spawnSync('docker', ['exec', '-i', '-e', 'MYSQL_PWD=' + pwd, container, 'mysql', '-uroot',
    '--default-character-set=utf8mb4', ...(extra || []), dbName],
    { input: sqlText, encoding: 'utf8', maxBuffer: 1 << 28 });
  if (!quiet && r.status !== 0) {
    console.error(`mysql exit=${r.status} stderr=${r.stderr}`);
  }
  return r;
}

function mysqlNoDb(sqlText) {
  return spawnSync('docker', ['exec', '-i', '-e', 'MYSQL_PWD=' + pwd, container, 'mysql', '-uroot',
    '--default-character-set=utf8mb4'],
    { input: sqlText, encoding: 'utf8', maxBuffer: 1 << 26 });
}

function mysqlContainerSh(script) {
  return spawnSync('docker', ['exec', '-e', 'MYSQL_PWD=' + pwd, container, 'sh', '-c', script],
    { encoding: 'utf8', maxBuffer: 1 << 26 });
}

function uploadSql(containerPath, sqlText) {
  return spawnSync('docker', ['exec', '-i', container, 'sh', '-c', 'cat > ' + containerPath],
    { input: sqlText, encoding: 'utf8' });
}

// ---------- 捕获 SQL 的机械重建（? → 字面量；替换次数必须 == 参数个数，M1） ----------

function loadCapture() {
  if (!capturePath || !existsSync(capturePath)) {
    console.error('capture json missing: ' + capturePath);
    process.exit(2);
  }
  return JSON.parse(readFileSync(capturePath, 'utf8'));
}

function armStatements(cap, site, shape, arm) {
  const shapeNode = cap.shapes.find((x) => x.n === shape);
  if (!shapeNode) throw new Error('shape missing in capture: ' + shape);
  const siteNode = shapeNode.sites[site];
  if (!siteNode) throw new Error('site missing in capture: ' + site + ' n=' + shape);
  const armName = arm === 'OLDrep' ? 'OLD' : arm;
  const armNode = siteNode.arms[armName];
  if (!armNode) throw new Error('arm missing in capture: ' + armName);
  return armNode.targetStatements.map((st) => {
    const vals = Object.values(st.values);
    const qCount = (st.sql.match(/\?/g) || []).length;
    let idx = 0;
    const literal = st.sql.replace(/\?/g, () => (idx < vals.length ? vals[idx++] : '?'));
    const substitution = { placeholderCount: qCount, paramCount: vals.length, replaced: idx };
    return { msId: st.msId, sql: st.sql, values: vals, literal, substitution, captureLiteralSql: st.literalSql };
  });
}

function armSqlText(cap, site, shape, arm) {
  const stmts = armStatements(cap, site, shape, arm);
  for (const st of stmts) {
    if (st.substitution.placeholderCount !== st.substitution.paramCount
        || st.substitution.replaced !== st.substitution.paramCount) {
      throw new Error(`M1 substitution mismatch site=${site} n=${shape} arm=${arm} `
        + `placeholders=${st.substitution.placeholderCount} params=${st.substitution.paramCount}`);
    }
  }
  return { stmts, text: stmts.map((s) => s.literal + ';').join('\n') };
}

function selectListOf(sql) {
  const m = /SELECT\s+(.*?)\s+FROM\s+exam_submissions/is.exec(sql);
  return m ? m[1].trim() : '';
}

function predicateOf(sql) {
  const m = /FROM\s+exam_submissions\s+(.*)$/is.exec(sql);
  return m ? m[1].trim() : '';
}

// ---------- 相位 ----------

if (phase === 'start') {
  const port = arg('port', '13411');
  const run = spawnSync('docker', ['run', '-d', '--name', container, '-p', '127.0.0.1:' + port + ':3306',
    '-e', 'MYSQL_ROOT_PASSWORD=' + pwd, '--tmpfs', '/var/lib/mysql', 'mysql:8.0'],
    { encoding: 'utf8' });
  const startedAtIso = nowIso();
  let ready = false;
  let attempts = 0;
  if (run.status === 0) {
    for (let i = 0; i < 120; i++) {
      attempts = i + 1;
      const r = spawnSync('docker', ['exec', '-e', 'MYSQL_PWD=' + pwd, container,
        'mysqladmin', 'ping', '-uroot', '--silent'], { encoding: 'utf8' });
      if (r.status === 0) { ready = true; break; }
      spawnSync(process.execPath, ['-e', 'setTimeout(()=>{},2000)']);
    }
  }
  const digest = spawnSync('docker', ['image', 'inspect', 'mysql:8.0',
    '--format', '{{index .RepoDigests 0}} {{.Id}}'], { encoding: 'utf8' });
  const id = spawnSync('docker', ['inspect', container, '--format', '{{.Id}}'], { encoding: 'utf8' });
  writeFileSync(join(outDir, 'container-start.log'),
    `startedAtIso=${startedAtIso}\ndocker run exit=${run.status}\nstdout=${run.stdout}\nstderr=${run.stderr}\n`
    + `ready=${ready} pingAttempts=${attempts}\nimage=${digest.stdout.trim()} (exit ${digest.status})\n`
    + `containerId=${id.stdout.trim()} (exit ${id.status})\nport=127.0.0.1:${port}\ndb=${dbName}\ntmpfs=/var/lib/mysql\n`);
  console.log('start exit=' + run.status + ' ready=' + ready);
  process.exit(run.status === 0 && ready ? 0 : 1);
}

if (phase === 'setup') {
  const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..');
  const schemaPath = join(repoRoot, 'src/main/resources/schema.sql');
  const createDb = mysqlNoDb(`CREATE DATABASE IF NOT EXISTS \`${dbName}\` DEFAULT CHARACTER SET utf8mb4;`);
  const version = mysql('SELECT VERSION();');
  const apply = mysql(readFileSync(schemaPath, 'utf8'));
  const showCreate = mysql('SHOW CREATE TABLE exam_submissions\\G');
  const idx = mysql('SHOW INDEX FROM exam_submissions');
  const digest = spawnSync('docker', ['image', 'inspect', 'mysql:8.0',
    '--format', '{{index .RepoDigests 0}} {{.Id}}'], { encoding: 'utf8' });
  writeFileSync(join(outDir, 'container-setup.log'),
    `atIso=${nowIso()}\nimage=${digest.stdout.trim()} (exit ${digest.status})\n`
    + `--- CREATE DATABASE IF NOT EXISTS ${dbName} (exit ${createDb.status}):\n${createDb.stdout}${createDb.stderr}\n`
    + `--- SELECT VERSION() (exit ${version.status}):\n${version.stdout}${version.stderr}\n`
    + `--- schema.sql apply exit=${apply.status}:\n${apply.stdout}${apply.stderr}\n`
    + `--- SHOW CREATE TABLE exam_submissions\\G (exit ${showCreate.status}):\n${showCreate.stdout}\n`
    + `--- SHOW INDEX FROM exam_submissions (exit ${idx.status}):\n${idx.stdout}\n`);
  console.log('setup done, schema apply exit=' + apply.status);
  process.exit(apply.status === 0 ? 0 : 1);
}

if (phase === 'seed') {
  const n = shapeArg;
  if (!n) { console.error('seed needs --shape'); process.exit(2); }
  const t = ['SET autocommit=0;'];
  t.push(`DELETE FROM exam_submissions WHERE exam_id BETWEEN ${S1_BASE} AND ${S1_BASE + 49};`);
  t.push(`DELETE FROM exam_submissions WHERE exam_id = ${E(n)};`);
  t.push(`DELETE FROM exam_submissions WHERE exam_id = ${F(n)};`);
  const row = (examId, sid, status, obj, subj, total, gradingStatus, submitTime) =>
    `(${examId}, ${sid}, '${T0}', '${T0}', ${submitTime}, ${status}, ${obj}, ${subj}, ${total},`
    + ` ${gradingStatus}, 0, 0, ${LONG_A}, ${LONG_P}, NOW(), NOW())`;
  const batch = (rows) => {
    for (let i = 0; i < rows.length; i += 500) {
      t.push(`INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time, submit_time,`
        + ` status, objective_score, subjective_score, total_score, grading_status, partial_graded, version,`
        + ` answers, paper_json, created_time, updated_time) VALUES ${rows.slice(i, i + 500).join(', ')};`);
    }
  };
  // 站点 1 区块：本人 45 行（k=0..44，状态分布照 IT 夹具）+ 填充 n−45 行（考试轮转）
  const own = [];
  for (let k = 0; k < 45; k++) {
    const status = k <= 19 ? 2 : k <= 39 ? (k <= 34 ? 1 : 3) : 1;
    own.push(row(S1_BASE + k, S1_STUDENT, status, 'NULL', 'NULL', 'NULL', 0, 'NULL'));
  }
  batch(own);
  const fillers = [];
  for (let j = 45; j < n; j++) {
    fillers.push(row(S1_BASE + (j % 50), FILLER_BASE + j, 3, score(j), '0.0', score(j), 1, 'NULL'));
  }
  batch(fillers);
  // E(n)：n 行（i=0..n−1，已批改、总分 score(i)、submit_time=T0−2h）
  const eRows = [];
  for (let i = 0; i < n; i++) {
    eRows.push(row(E(n), SHARED_STUDENT_BASE + i, 3, score(i), '0.0', score(i), 1, `'${T0E}'`));
  }
  batch(eRows);
  // F(n)：i=2 一行（total 30.0、submit_time 更晚）
  batch([row(F(n), SHARED_STUDENT_BASE + 2, 3, '30.0', '0.0', '30.0', 1, `'${T0F}'`)]);
  t.push('COMMIT;');
  const seedRun = mysql(t.join('\n'));
  const counts = mysql(`SELECT (SELECT COUNT(*) FROM exam_submissions WHERE exam_id BETWEEN ${S1_BASE}`
    + ` AND ${S1_BASE + 49}) AS s1_block, (SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ${E(n)})`
    + ` AS e_block, (SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ${F(n)}) AS f_block,`
    + ` (SELECT COUNT(*) FROM exam_submissions WHERE exam_id BETWEEN ${S1_BASE} AND ${S1_BASE + 49}`
    + ` OR exam_id IN (${E(n)}, ${F(n)})) AS all_rows,`
    + ` (SELECT MIN(LENGTH(answers)) FROM exam_submissions WHERE exam_id BETWEEN ${S1_BASE} AND ${S1_BASE + 49}`
    + ` OR exam_id IN (${E(n)}, ${F(n)})) AS min_a,`
    + ` (SELECT MAX(LENGTH(answers)) FROM exam_submissions WHERE exam_id BETWEEN ${S1_BASE} AND ${S1_BASE + 49}`
    + ` OR exam_id IN (${E(n)}, ${F(n)})) AS max_a,`
    + ` (SELECT MIN(LENGTH(paper_json)) FROM exam_submissions WHERE exam_id BETWEEN ${S1_BASE} AND ${S1_BASE + 49}`
    + ` OR exam_id IN (${E(n)}, ${F(n)})) AS min_p,`
    + ` (SELECT MAX(LENGTH(paper_json)) FROM exam_submissions WHERE exam_id BETWEEN ${S1_BASE} AND ${S1_BASE + 49}`
    + ` OR exam_id IN (${E(n)}, ${F(n)})) AS max_p,`
    + ` (SELECT COUNT(*) FROM exam_submissions WHERE (exam_id BETWEEN ${S1_BASE} AND ${S1_BASE + 49}`
    + ` OR exam_id IN (${E(n)}, ${F(n)})) AND answers IS NOT NULL AND paper_json IS NOT NULL) AS non_null_rows;`,
    { extra: ['-N', '-B'] });
  const [s1c, ec, fc, allRows, minA, maxA, minP, maxP, nonNull] = counts.stdout.trim().split('\t');
  const ok = seedRun.status === 0 && parseInt(s1c, 10) === n && parseInt(ec, 10) === n
    && parseInt(fc, 10) === 1 && parseInt(allRows, 10) === 2 * n + 1
    && parseInt(minA, 10) === 2048 && parseInt(maxA, 10) === 2048
    && parseInt(minP, 10) === 20480 && parseInt(maxP, 10) === 20480
    && parseInt(nonNull, 10) === 2 * n + 1;
  const entry = { shape: n, atIso: nowIso(), seedExit: seedRun.status, T0, s1Block: parseInt(s1c, 10),
    eBlock: parseInt(ec, 10), fBlock: parseInt(fc, 10), allRows: parseInt(allRows, 10),
    minAnswersLen: parseInt(minA, 10), maxAnswersLen: parseInt(maxA, 10),
    minPaperJsonLen: parseInt(minP, 10), maxPaperJsonLen: parseInt(maxP, 10),
    nonNullRows: parseInt(nonNull, 10), designRows: 2 * n + 1, ok };
  const seedFile = join(outDir, 'seed-counts.json');
  const all = existsSync(seedFile) ? JSON.parse(readFileSync(seedFile, 'utf8')) : [];
  const kept = all.filter((x) => x.shape !== n);
  kept.push(entry);
  kept.sort((a, b) => a.shape - b.shape);
  writeFileSync(seedFile, JSON.stringify(kept, null, 2));
  log('seed.log', `=== seed shape=${n} at=${entry.atIso} exit=${seedRun.status} ok=${ok}\n`
    + `counts: s1=${s1c} e=${ec} f=${fc} all=${allRows}\n`
    + `lengths: answers min/max=${minA}/${maxA} paperJson min/max=${minP}/${maxP} nonNull=${nonNull}\n`
    + `${seedRun.stderr}\n`);
  console.log(`seed shape=${n} exit=${seedRun.status} ok=${ok} counts=${s1c}/${ec}/${fc}`);
  process.exit(ok ? 0 : 1);
}

if (phase === 'rounds') {
  const cap = loadCapture();
  const n = shapeArg;
  if (!n) { console.error('rounds needs --shape'); process.exit(2); }
  const sites = siteArg ? [siteArg] : SITES;
  const startedAtIso = nowIso();
  const index = [];
  for (const site of sites) {
    const armsOrder = ['OLD', 'PROJ', 'OLDrep'];
    const built = {};
    for (const arm of armsOrder) {
      const builtArm = armSqlText(cap, site, n, arm);
      built[arm] = builtArm;
      uploadSql(`/tmp/arm-${site}-${n}-${arm}.sql`, builtArm.text + '\n');
    }
    // M1 证据：前后文本 + 机械替换计数（每站点×形状落一份，OLDrep 与 OLD 同文说明）
    const m1 = {
      site, shape: n, atIso: nowIso(),
      arms: Object.fromEntries(armsOrder.map((arm) => [arm, built[arm].stmts.map((st) => ({
        msId: st.msId, sql: st.sql, literal: st.literal, substitution: st.substitution,
        captureLiteralSql: st.captureLiteralSql,
        captureLiteralMatches: st.captureLiteralSql ? st.captureLiteralSql === st.literal : null,
        sqlSha256: sha256(st.literal),
      }))])),
      oldrepSameTextAsOld: built.OLDrep.text === built.OLD.text,
    };
    log(`arms-sql-${site}-n${n}.log`, JSON.stringify(m1, null, 2));

    const armResults = {};
    for (const arm of armsOrder) {
      // 预热 2 次（不计时）
      for (let w = 0; w < 2; w++) {
        mysqlContainerSh(`timeout 120 mysql -uroot --default-character-set=utf8mb4 ${dbName}`
          + ` < /tmp/arm-${site}-${n}-${arm}.sql > /dev/null 2>/tmp/arm.err || true`);
      }
      armResults[arm] = { warmups: 2, rounds: [] };
    }
    for (let r = 1; r <= 5; r++) {
      const rot = (r - 1) % 3;
      const order = [...armsOrder.slice(rot), ...armsOrder.slice(0, rot)];
      for (const arm of order) {
        let attempt = 1;
        let done = false;
        while (!done && attempt <= 2) {
          const startedRoundIso = nowIso();
          const res = mysqlContainerSh(`t0=$(date +%s%N);`
            + ` timeout 120 mysql -uroot --default-character-set=utf8mb4 ${dbName}`
            + ` < /tmp/arm-${site}-${n}-${arm}.sql > /dev/null 2>/tmp/arm.err; rc=$?;`
            + ` t1=$(date +%s%N); echo NS=$((t1-t0)); echo RC=$rc; tail -c 2000 /tmp/arm.err`);
          const nsMatch = /NS=(\d+)/.exec(res.stdout || '');
          const rcMatch = /RC=(\d+)/.exec(res.stdout || '');
          const entry = {
            round: r, attempt, arm, ns: nsMatch ? parseInt(nsMatch[1], 10) : null,
            exitCode: rcMatch ? parseInt(rcMatch[1], 10) : null,
            atIso: startedRoundIso, execExit: res.status,
            stderrTail: (res.stdout || '').split('\n').filter((l) => !/^(NS|RC)=/.test(l)).join('\n').slice(0, 2000),
          };
          armResults[arm].rounds.push(entry);
          log(`rounds-${site}-n${n}.log`,
            `=== ROUND site=${site} n=${n} arm=${arm} round=${r} attempt=${attempt}`
            + ` ns=${entry.ns} exit=${entry.exitCode} execExit=${entry.execExit} at=${entry.atIso}\n${entry.stderrTail}\n`);
          if (entry.exitCode === 0) {
            done = true;
          } else {
            log(`rounds-${site}-n${n}.log`, `--- attempt ${attempt} failed (exit=${entry.exitCode}), retry once\n`);
            attempt++;
          }
        }
        if (!done) {
          log(`rounds-${site}-n${n}.log`, `--- arm=${arm} round=${r} retry also failed; kept as failed\n`);
        }
      }
    }
    const siteFile = {
      site, shape: n, statementCountOld: built.OLD.stmts.length,
      statementCountProj: built.PROJ.stmts.length,
      oldSqlSha256: sha256(built.OLD.text), projSqlSha256: sha256(built.PROJ.text),
      startedAtIso, finishedAtIso: nowIso(), arms: armResults,
    };
    writeFileSync(join(outDir, `rounds-${site}-n${n}.json`), JSON.stringify(siteFile, null, 2));
    const succ = (arm) => armResults[arm].rounds.filter((x) => x.exitCode === 0).length;
    index.push({ site, shape: n, successful: { OLD: succ('OLD'), PROJ: succ('PROJ'), OLDrep: succ('OLDrep') },
      oldrepSameTextAsOld: m1.oldrepSameTextAsOld, m1SubstitutionOk: Object.values(m1.arms).flat()
        .every((st) => st.substitution.placeholderCount === st.substitution.paramCount) });
    console.log(`rounds site=${site} n=${n}: OLD=${succ('OLD')} PROJ=${succ('PROJ')} OLDrep=${succ('OLDrep')} successful`);
  }
  writeFileSync(join(outDir, `rounds-index-n${n}.json`), JSON.stringify(index, null, 2));
  process.exit(index.every((x) => x.successful.OLD >= 5 && x.successful.PROJ >= 5 && x.successful.OLDrep >= 5) ? 0 : 1);
}

if (phase === 'bytes') {
  const cap = loadCapture();
  const n = shapeArg;
  if (!n) { console.error('bytes needs --shape'); process.exit(2); }
  const sites = siteArg ? [siteArg] : SITES;
  const results = [];
  for (const site of sites) {
    const arms = {};
    for (const arm of ['OLD', 'PROJ', 'OLDrep']) {
      const built = armSqlText(cap, site, n, arm);
      const perStatement = [];
      let bytesSum = 0;
      let rowsSum = 0;
      for (const st of built.stmts) {
        const cols = selectListOf(st.literal).split(',').map((c) => c.trim()).filter(Boolean);
        const predicate = predicateOf(st.literal);
        const sum = cols.map((c) => `COALESCE(LENGTH(CAST(${c} AS CHAR)),0)`).join(' + ');
        // rows 是 MySQL 8 保留字，别名须加反引号；下游按 \\t 解析，别名字面不参与判据
        const carrier = `SELECT COALESCE(SUM(${sum}),0) AS bytes, COUNT(*) AS \`rows\``
          + ` FROM exam_submissions ${predicate};`;
        const r = mysql(carrier, { extra: ['-N', '-B'] });
        const [b, rw] = (r.stdout || '').trim().split('\t');
        const bytes = parseInt(b, 10);
        const rows = parseInt(rw, 10);
        perStatement.push({ cols, predicate, bytes, rows, exit: r.status, carrier });
        bytesSum += bytes;
        rowsSum += rows;
        log(`bytes-${site}-n${n}.log`, `=== site=${site} n=${n} arm=${arm} stmt=${perStatement.length}`
          + ` bytes=${bytes} rows=${rows} exit=${r.status}\ncarrier: ${carrier}\n`);
      }
      // 佐证（不参与裁决）：原样运行臂 SQL，记 mysql -N -B 输出字节数与行数
      const side = mysql(built.text + '\n', { extra: ['-N', '-B'] });
      const sideBytes = Buffer.byteLength(side.stdout || '', 'utf8');
      const sideLines = ((side.stdout || '').match(/\n/g) || []).length;
      arms[arm] = { bytes: bytesSum, rows: rowsSum, perStatement, sideEvidence: {
        stdoutBytes: sideBytes, stdoutLines: sideLines, exit: side.status } };
      log(`bytes-${site}-n${n}.log`, `--- arm=${arm} total bytes=${bytesSum} rows=${rowsSum}`
        + ` side(stdoutBytes=${sideBytes}, stdoutLines=${sideLines}, exit=${side.status})\n`);
    }
    results.push({ site, shape: n, arms });
  }
  writeFileSync(join(outDir, `bytes-n${n}.json`), JSON.stringify(results, null, 2));
  console.log('bytes done n=' + n);
  process.exit(0);
}

if (phase === 'stop') {
  const rm = spawnSync('docker', ['rm', '-f', container], { encoding: 'utf8' });
  const psAfter = spawnSync('docker', ['ps', '-a', '--filter', 'name=' + container,
    '--format', '{{.ID}} {{.Names}} {{.Status}}'], { encoding: 'utf8' });
  const psAll = spawnSync('docker', ['ps', '-a', '--format', '{{.Names}}'], { encoding: 'utf8' });
  writeFileSync(join(outDir, 'container-stop.log'),
    `atIso=${nowIso()}\ndocker rm -f ${container}: exit=${rm.status} stdout=${rm.stdout} stderr=${rm.stderr}\n`
    + `--- docker ps -a --filter name=${container} (零匹配证据):\n`
    + `stdout=[${psAfter.stdout.trim()}]\nstderr=[${psAfter.stderr.trim()}]\n`
    + `--- docker ps -a (全部容器名，供对照):\n${psAll.stdout}\n`);
  console.log(`stop exit=${rm.status} zeroMatch=${psAfter.stdout.trim() === ''}`);
  process.exit(rm.status === 0 && psAfter.stdout.trim() === '' ? 0 : 1);
}

console.error('unknown phase: ' + phase);
process.exit(2);
