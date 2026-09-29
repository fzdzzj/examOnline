#!/usr/bin/env node
// run-monitor-projection-mysql.mjs — reattribute-monitor-overview-projection 阶段 2 执行器。
// 冻结口径见同目录 PREREGISTRATION.md §2.0/§2.2（一次性容器配方、三臂、轮转、字节算子、M1–M3）。
// 只做忠实执行与逐字记录：不挑轮、不改写、失败轮由 IT 原样保留并同位补跑记 retry。
// 用法（仓库根）：
//   node spec/changes/reattribute-monitor-overview-projection/evidence/run-monitor-projection-mysql.mjs \
//     --phase start|start-redis|setup|rounds|bytes|stop [--out <rawDir>] [--shape 200] \
//     [--port 13319] [--port-redis 6390] [--rev <sha7>] [--label run1]
// 环境变量：MONITOR_MYSQL_PWD（root 口令，不入库、不进日志，日志中以 *** 代替）；
//           可选 MONITOR_CONTAINER / MONITOR_REDIS_CONTAINER / MONITOR_DB 覆盖默认名。

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
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..');
const outDir = resolve(arg('out', 'D:/code/examOnline-measure/monitor-projection/raw'));
const container = process.env.MONITOR_CONTAINER || 'monitor-projection-mysql';
const redisContainer = process.env.MONITOR_REDIS_CONTAINER || 'monitor-projection-redis';
const dbName = process.env.MONITOR_DB || 'monitor_projection_measure';
const pwd = process.env.MONITOR_MYSQL_PWD;
const mysqlPort = arg('port', '13319');
const redisPort = arg('port-redis', '6390');
const rev = arg('rev', 'unknown');
const label = arg('label', 'run1');
const SIZES = [200, 1000, 3000];
const TARGET_MS_ID = 'com.exam.submission.mapper.ExamSubmissionMapper.selectList';
const FROZEN_PROJ_SELECT = 'student_id,status';
const MYSQL_URL = 'jdbc:mysql://127.0.0.1:' + mysqlPort + '/' + dbName
  + '?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
  + '&rewriteBatchedStatements=true&useSSL=false&allowPublicKeyRetrieval=true';

mkdirSync(outDir, { recursive: true });
const nowIso = () => new Date().toISOString();
const log = (file, text) => appendFileSync(join(outDir, file), text + '\n');
const sha256 = (s) => createHash('sha256').update(s, 'utf8').digest('hex');
const redactedCmd = (s) => s.replace(/MONITOR_MYSQL_PWD=\S+/g, 'MONITOR_MYSQL_PWD=***');

if (!pwd) {
  console.error('need MONITOR_MYSQL_PWD (root 口令经环境变量注入，不落盘)');
  process.exit(2);
}

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

function redisCli(args) {
  return spawnSync('docker', ['exec', redisContainer, 'redis-cli', ...args], { encoding: 'utf8' });
}

// ---------- 捕获 JSON 的机械重建（? → 字面量；替换次数必须 == 参数个数，M1） ----------

const sqlLiteral = (v) => (/^-?\d+$/.test(String(v)) ? String(v)
  : (/^-?\d+\.\d+$/.test(String(v)) ? String(v) : `'${String(v).replace(/'/g, "''")}'`));

function literalize(sql, values) {
  const qCount = (sql.match(/\?/g) || []).length;
  let idx = 0;
  const literal = sql.replace(/\?/g, () => (idx < values.length ? sqlLiteral(values[idx++]) : '?'));
  return {
    sql, values, literal,
    substitution: { placeholderCount: qCount, paramCount: values.length, replaced: idx },
  };
}

function selectListOf(sql) {
  const norm = sql.replace(/\s+/g, ' ').trim();
  const u = norm.toUpperCase();
  const s = u.indexOf('SELECT ');
  const f = u.indexOf(' FROM ');
  return s >= 0 && f > s ? norm.substring(s + 7, f).replace(/\s+/g, '') : '';
}

function loadJson(path) {
  if (!existsSync(path)) {
    console.error('missing json: ' + path);
    process.exit(2);
  }
  return JSON.parse(readFileSync(path, 'utf8'));
}

/** 从 IT 捕获节点取目标语句（msId 过滤；期望恰一条）。 */
function targetStatement(armNode, { where }) {
  const caps = (armNode.captures || []).filter((c) => c.msId === TARGET_MS_ID);
  if (caps.length !== 1) {
    console.error(`expect exactly 1 target capture for ${where}, got ${caps.length}`);
    process.exit(2);
  }
  return literalize(caps[0].sql, Object.values(caps[0].values));
}

function imageDigest(image) {
  return spawnSync('docker', ['image', 'inspect', image, '--format', '{{index .RepoDigests 0}} {{.Id}}'],
    { encoding: 'utf8' });
}

// ---------- 相位 ----------

if (phase === 'start') {
  const run = spawnSync('docker', ['run', '-d', '--name', container, '-p', '127.0.0.1:' + mysqlPort + ':3306',
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
  const digest = imageDigest('mysql:8.0');
  const id = spawnSync('docker', ['inspect', container, '--format', '{{.Id}}'], { encoding: 'utf8' });
  writeFileSync(join(outDir, 'container-start.log'),
    `mysql startedAtIso=${startedAtIso}\ndocker run exit=${run.status}\nstdout=${run.stdout}\nstderr=${run.stderr}\n`
    + `ready=${ready} pingAttempts=${attempts}\nimage=${digest.stdout.trim()} (exit ${digest.status})\n`
    + `containerId=${id.stdout.trim()} (exit ${id.status})\nport=127.0.0.1:${mysqlPort}\ndb=${dbName}\ntmpfs=/var/lib/mysql\n`
    + `url=${MYSQL_URL.replace('MONITOR_MYSQL_PWD', '***')}\n`);
  console.log('mysql start exit=' + run.status + ' ready=' + ready);
  process.exit(run.status === 0 && ready ? 0 : 1);
}

if (phase === 'start-redis') {
  const run = spawnSync('docker', ['run', '-d', '--name', redisContainer,
    '-p', '127.0.0.1:' + redisPort + ':6379', '--tmpfs', '/data', 'redis:7.2-alpine'],
    { encoding: 'utf8' });
  let ready = false;
  let pong = '';
  if (run.status === 0) {
    for (let i = 0; i < 60; i++) {
      const r = redisCli(['ping']);
      pong = (r.stdout || '').trim();
      if (r.status === 0 && pong === 'PONG') { ready = true; break; }
      spawnSync(process.execPath, ['-e', 'setTimeout(()=>{},1000)']);
    }
  }
  const digest = imageDigest('redis:7.2-alpine');
  const info = ready ? redisCli(['-n', '15', 'info', 'server']) : { stdout: '' };
  const serverLine = (info.stdout || '').split('\n').filter((l) => /^redis_version/.test(l))[0] || '';
  appendFileSync(join(outDir, 'container-start.log'),
    `\nredis startedAtIso=${nowIso()}\ndocker run exit=${run.status}\nstdout=${run.stdout}\nstderr=${run.stderr}\n`
    + `ready=${ready} ping=${pong}\nimage=${(digest.stdout || '').trim()} (exit ${digest.status})\n`
    + `port=127.0.0.1:${redisPort} db=15 tmpfs=/data\n${serverLine}\n`);
  console.log('redis start exit=' + run.status + ' ready=' + ready);
  process.exit(run.status === 0 && ready ? 0 : 1);
}

if (phase === 'setup') {
  const schemaPath = join(repoRoot, 'src/main/resources/schema.sql');
  const createDb = mysqlNoDb(`CREATE DATABASE IF NOT EXISTS \`${dbName}\` DEFAULT CHARACTER SET utf8mb4;`);
  const version = mysql('SELECT VERSION();');
  const apply = mysql(readFileSync(schemaPath, 'utf8'));
  const showCreate = mysql('SHOW CREATE TABLE exam_submissions\\G');
  const idx = mysql('SHOW INDEX FROM exam_submissions');
  const tables = mysql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE();",
    { extra: ['-N', '-B'] });
  const digest = imageDigest('mysql:8.0');
  writeFileSync(join(outDir, 'container-setup.log'),
    `atIso=${nowIso()}\nimage=${digest.stdout.trim()} (exit ${digest.status})\n`
    + `url=${MYSQL_URL} (口令经 MONITOR_MYSQL_PWD=*** 注入)\n`
    + `--- CREATE DATABASE IF NOT EXISTS ${dbName} (exit ${createDb.status}):\n${createDb.stdout}${createDb.stderr}\n`
    + `--- SELECT VERSION() (exit ${version.status}):\n${version.stdout}${version.stderr}\n`
    + `--- schema.sql apply exit=${apply.status}:\n${apply.stdout}${apply.stderr}\n`
    + `--- table count (exit ${tables.status}): ${(tables.stdout || '').trim()}\n`
    + `--- SHOW CREATE TABLE exam_submissions\\G (exit ${showCreate.status}):\n${showCreate.stdout}\n`
    + `--- SHOW INDEX FROM exam_submissions (exit ${idx.status}):\n${idx.stdout}\n`);
  console.log('setup done, schema apply exit=' + apply.status);
  process.exit(apply.status === 0 ? 0 : 1);
}

if (phase === 'rounds') {
  const cmd = [
    'mvnw.cmd',
    'test',
    '-Dtest=MonitorOverviewProjectionMeasureIT',
    '-DfailIfNoTests=false',
    `-Dmeasure.rev=${rev}`,
    `-Dmeasure.label=${label}`,
    `"-Dmeasure.out=${outDir}"`,
    `"-Dmeasure.mysql.url=${MYSQL_URL}"`,
    '-Dmeasure.mysql.user=root',
  ].join(' ');
  const startedAtIso = nowIso();
  const r = spawnSync(cmd, {
    shell: true,
    cwd: repoRoot,
    encoding: 'utf8',
    maxBuffer: 1 << 28,
    env: { ...process.env, REDIS_HOST: '127.0.0.1', REDIS_PORT: redisPort, MONITOR_MYSQL_PWD: pwd },
  });
  const finishedAtIso = nowIso();
  const outText = (r.stdout || '') + (r.stderr || '');
  writeFileSync(join(outDir, 'it-run.log'),
    redactedCmd(`command: ${cmd}\ncwd: ${repoRoot}\nstartedAtIso=${startedAtIso}\n`
      + `exit=${r.status}\nfinishedAtIso=${finishedAtIso}\n--- stdout+stderr:\n${outText}\n`));

  const manifestPath = join(outDir, 'capture-manifest.json');
  const manifest = existsSync(manifestPath) ? JSON.parse(readFileSync(manifestPath, 'utf8')) : null;
  const problemsEmpty = !!(manifest && manifest.problemsEmpty === true);
  const shapesDone = SIZES.every((n) => existsSync(join(outDir, `capture-n${n}.json`))
    && existsSync(join(outDir, `rounds-n${n}.json`)));
  console.log(`rounds: itExit=${r.status} problemsEmpty=${problemsEmpty} shapesDone=${shapesDone}`
    + ` out=${outDir}`);
  if (manifest && !problemsEmpty) {
    console.log('problems: ' + JSON.stringify(manifest.problems));
  }
  process.exit(r.status === 0 && problemsEmpty && shapesDone ? 0 : 1);
}

if (phase === 'bytes') {
  const shapes = shapeArg ? [shapeArg] : SIZES;
  const results = [];
  for (const n of shapes) {
    const cap = loadJson(join(outDir, `capture-n${n}.json`));
    const rounds = loadJson(join(outDir, `rounds-n${n}.json`));
    const examId = cap.examId;
    const inProgress = Math.floor(6 * n / 10);
    let logs = 0;
    for (let i = 0; i < n; i++) {
      logs += (i % 10 === 0) ? 3 : (i % 5 === 0) ? 2 : 0;
    }

    const build = (armNode, where) => targetStatement(armNode, { where });
    const oldStmt = build(cap.arms.OLD, `n=${n} OLD`);
    const projMain = build(cap.arms.PROJ, `n=${n} PROJ-main`);
    const projGuard = cap.arms.PROJ.guard;
    const narrowStmt = literalize(projGuard.narrowSql, projGuard.narrowParams);
    const oldrepRound = (rounds.arms.OLDrep.rounds || []).find((x) => x.exitCode === 0);
    if (!oldrepRound) {
      console.error(`no successful OLDrep round for n=${n}`);
      process.exit(2);
    }
    const oldrepStmt = build(oldrepRound, `n=${n} OLDrep`);
    const oldRound = (rounds.arms.OLD.rounds || []).find((x) => x.exitCode === 0);
    if (!oldRound) {
      console.error(`no successful OLD round for n=${n}`);
      process.exit(2);
    }
    const oldRoundStmt = build(oldRound, `n=${n} OLD(rounds)`);

    const carrierOf = (st) => {
      const cols = selectListOf(st.literal).split(',').filter(Boolean);
      const sum = cols.map((c) => `COALESCE(LENGTH(CAST(${c} AS CHAR)),0)`).join(' + ');
      return `SELECT COALESCE(SUM(${sum}),0) AS bytes, COUNT(*) AS \`rows\` FROM (${st.literal}) t;`;
    };
    const measure = (st, whereWhat) => {
      if (st.substitution.placeholderCount !== st.substitution.paramCount
          || st.substitution.replaced !== st.substitution.paramCount) {
        console.error(`M1 substitution mismatch ${whereWhat}`);
        process.exit(2);
      }
      const carrier = carrierOf(st);
      const res = mysql(carrier, { extra: ['-N', '-B'] });
      const [b, rw] = (res.stdout || '').trim().split('\t');
      const entry = {
        msId: TARGET_MS_ID, sql: st.sql, values: st.values, literal: st.literal,
        substitution: st.substitution, selectList: selectListOf(st.literal),
        bytes: parseInt(b, 10), rows: parseInt(rw, 10), exit: res.status, carrier,
        sqlSha256: sha256(st.literal), atIso: nowIso(),
      };
      log(`bytes-n${n}.log`, `=== n=${n} ${whereWhat} bytes=${entry.bytes} rows=${entry.rows}`
        + ` exit=${entry.exit} select=${entry.selectList}\n${carrier}\n`);
      return entry;
    };

    const oldMain = measure(oldStmt, 'OLD main');
    const projMainEntry = measure(projMain, 'PROJ main');
    const narrow = measure(narrowStmt, 'PROJ narrow');
    const oldrep = measure(oldrepStmt, 'OLDrep main');
    const oldRoundEntry = measure(oldRoundStmt, 'OLD main(from rounds)');

    const verify = mysql(`SELECT (SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ${examId}) AS rows_total,`
      + ` (SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ${examId} AND paper_json IS NOT NULL) AS paper_nn,`
      + ` (SELECT MIN(LENGTH(paper_json)) FROM exam_submissions WHERE exam_id = ${examId}) AS paper_min,`
      + ` (SELECT MAX(LENGTH(paper_json)) FROM exam_submissions WHERE exam_id = ${examId}) AS paper_max,`
      + ` (SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ${examId} AND answers IS NOT NULL) AS ans_nn,`
      + ` (SELECT MIN(LENGTH(answers)) FROM exam_submissions WHERE exam_id = ${examId} AND answers IS NOT NULL) AS ans_min,`
      + ` (SELECT MAX(LENGTH(answers)) FROM exam_submissions WHERE exam_id = ${examId} AND answers IS NOT NULL) AS ans_max,`
      + ` (SELECT COUNT(*) FROM exam_behavior_logs WHERE exam_id = ${examId}) AS logs;`,
      { extra: ['-N', '-B'] });
    const [rowsTotal, paperNn, paperMin, paperMax, ansNn, ansMin, ansMax, logsActual] =
      (verify.stdout || '').trim().split('\t');
    const verification = {
      examId, designRows: n, rowsTotal: parseInt(rowsTotal, 10),
      designPaperJsonNonNull: n, paperJsonNonNull: parseInt(paperNn, 10),
      paperJsonLenMin: parseInt(paperMin, 10), paperJsonLenMax: parseInt(paperMax, 10),
      designAnswersNonNull: n - inProgress, answersNonNull: parseInt(ansNn, 10),
      answersLenMin: parseInt(ansMin, 10), answersLenMax: parseInt(ansMax, 10),
      designLogs: logs, logsActual: parseInt(logsActual, 10), exit: verify.status,
    };
    const checks = {
      oldSelectContainsLongFields: oldMain.selectList.includes('paper_json')
        && oldMain.selectList.includes('answers') && oldMain.selectList !== FROZEN_PROJ_SELECT,
      projSelectEqualsFrozen: projMainEntry.selectList === FROZEN_PROJ_SELECT,
      narrowSelectOk: narrow.selectList === 'paper_json' && /LIMIT\s+1/i.test(narrow.literal),
      oldrepSameTextAsOld: oldrep.literal === oldMain.literal,
      oldrepSameTextAsRoundsOld: oldRoundEntry.literal === oldMain.literal,
      mainRowsOk: oldMain.rows === n && projMainEntry.rows === n && oldrep.rows === n,
      narrowRowsOk: narrow.rows === 1,
      verifyRowsOk: verification.rowsTotal === n && verification.paperJsonNonNull === n
        && verification.paperJsonLenMin === 20480 && verification.paperJsonLenMax === 20480,
      verifyAnswersOk: verification.answersNonNull === n - inProgress
        && verification.answersLenMin === 2048 && verification.answersLenMax === 2048,
      verifyLogsOk: verification.logsActual === logs,
      exitsOk: [oldMain, projMainEntry, narrow, oldrep, oldRoundEntry].every((x) => x.exit === 0),
    };
    const ok = Object.values(checks).every(Boolean);
    results.push({
      shape: n, atIso: nowIso(), ok, checks, verification,
      arms: {
        OLD: { bytes: oldMain.bytes, rows: oldMain.rows, statements: [oldMain] },
        PROJ: {
          bytes: projMainEntry.bytes + narrow.bytes, mainBytes: projMainEntry.bytes,
          narrowBytes: narrow.bytes, mainRows: projMainEntry.rows, narrowRows: narrow.rows,
          statements: [projMainEntry, narrow],
        },
        OLDrep: { bytes: oldrep.bytes, rows: oldrep.rows, statements: [oldrep] },
      },
    });
    writeFileSync(join(outDir, `bytes-n${n}.json`), JSON.stringify(results[results.length - 1], null, 2));
    const ratio = projMainEntry.bytes + narrow.bytes > 0
      ? oldMain.bytes / (projMainEntry.bytes + narrow.bytes) : Infinity;
    console.log(`bytes n=${n}: OLD=${oldMain.bytes} PROJ=${projMainEntry.bytes}+${narrow.bytes}`
      + ` ratio=${ratio.toFixed ? ratio.toFixed(2) : ratio} checksOk=${ok}`);
  }
  process.exit(results.every((x) => x.ok) ? 0 : 1);
}

if (phase === 'stop') {
  const rmMysql = spawnSync('docker', ['rm', '-f', container], { encoding: 'utf8' });
  const rmRedis = spawnSync('docker', ['rm', '-f', redisContainer], { encoding: 'utf8' });
  const psMysql = spawnSync('docker', ['ps', '-a', '--filter', 'name=' + container,
    '--format', '{{.ID}} {{.Names}} {{.Status}}'], { encoding: 'utf8' });
  const psRedis = spawnSync('docker', ['ps', '-a', '--filter', 'name=' + redisContainer,
    '--format', '{{.ID}} {{.Names}} {{.Status}}'], { encoding: 'utf8' });
  const psAll = spawnSync('docker', ['ps', '-a', '--format', '{{.Names}}'], { encoding: 'utf8' });
  writeFileSync(join(outDir, 'container-stop.log'),
    `atIso=${nowIso()}\ndocker rm -f ${container}: exit=${rmMysql.status} stdout=${rmMysql.stdout} stderr=${rmMysql.stderr}\n`
    + `docker rm -f ${redisContainer}: exit=${rmRedis.status} stdout=${rmRedis.stdout} stderr=${rmRedis.stderr}\n`
    + `--- 零匹配证据 docker ps -a --filter name=${container}:\n`
    + `stdout=[${psMysql.stdout.trim()}]\nstderr=[${psMysql.stderr.trim()}]\n`
    + `--- 零匹配证据 docker ps -a --filter name=${redisContainer}:\n`
    + `stdout=[${psRedis.stdout.trim()}]\nstderr=[${psRedis.stderr.trim()}]\n`
    + `--- docker ps -a 全部容器名（供对照，含本机其他项目容器）：\n${psAll.stdout}\n`);
  const zero = psMysql.stdout.trim() === '' && psRedis.stdout.trim() === '';
  console.log(`stop mysql=${rmMysql.status} redis=${rmRedis.status} zeroMatch=${zero}`);
  process.exit(rmMysql.status === 0 && rmRedis.status === 0 && zero ? 0 : 1);
}

console.error('unknown phase: ' + phase);
process.exit(2);
