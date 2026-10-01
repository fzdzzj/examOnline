#!/usr/bin/env node
// run-grading-score-projection-mysql.mjs — one-shot measurement apparatus driver
// (project-grading-score-scalar-projection; frozen recipe in evidence/PREREGISTRATION.md §2.0).
//
// Phases:
//   node run-grading-score-projection-mysql.mjs --phase start        # one-shot mysql:8.0 (tmpfs, 127.0.0.1:13321)
//   node run-grading-score-projection-mysql.mjs --phase start-redis  # one-shot redis:7-alpine (127.0.0.1:16379)
//   node run-grading-score-projection-mysql.mjs --phase setup        # create db + apply repo schema.sql (exit 0 evidence)
//   node run-grading-score-projection-mysql.mjs --phase rounds       # run GradingScoreProjectionMeasureIT via mvnw.cmd
//   node run-grading-score-projection-mysql.mjs --phase bytes        # container-side deterministic byte accounting + V1 verify
//   node run-grading-score-projection-mysql.mjs --phase stop         # flush redis db15 + destroy containers + zero-match evidence
//
// Rules honored here: ASCII-only output; password never written to logs (redacted as ***);
// raw artifacts go to D:/code/examOnline-measure/grading-score-projection/ and logs to
// D:/code/examOnline-verify/grading-score-projection/; no repo-root scattering; no remote ops.

import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync, appendFileSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = 'D:/code/examOnline';
const VERIFY_DIR = 'D:/code/examOnline-verify/grading-score-projection';
const MEASURE_DIR = 'D:/code/examOnline-measure/grading-score-projection';
const MYSQL_NAME = 'gsproj_mysql_measure';
const REDIS_NAME = 'gsproj_redis_measure';
const MYSQL_PORT = '13321';
const REDIS_PORT = '16379';
const DB_NAME = 'grading_score_projection_measure';
const MYSQL_PWD = 'GsProj_Measure_2026_key';
const MYSQL_IMAGE = 'mysql:8.0';
const REDIS_IMAGE = 'redis:7-alpine';
// tmpfs resized 3g -> 8g on 2026-10-01 (apparatus-level adjustment, disclosed in
// evidence/apparatus-correction-note.md): 3g filled to 100% during the n=3000 seed of the
// third attempt because aborted earlier runs had accumulated binlog/undo in tmpfs.
const TMPFS = '/var/lib/mysql:rw,size=8g';
const JDBC_PARAMS = 'useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
  + '&rewriteBatchedStatements=true&useSSL=false&allowPublicKeyRetrieval=true';

mkdirSync(VERIFY_DIR, { recursive: true });
mkdirSync(MEASURE_DIR, { recursive: true });

function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { encoding: 'utf8', ...opts });
  return { status: r.status, stdout: r.stdout || '', stderr: r.stderr || '' };
}

function docker(args, opts = {}) {
  return run('docker', args, opts);
}

function log(file, text) {
  const redacted = String(text).split(MYSQL_PWD).join('***');
  appendFileSync(join(VERIFY_DIR, file), redacted);
  process.stdout.write(redacted.slice(0, 4000));
}

function revShort() {
  const r = run('git', ['rev-parse', '--short', 'HEAD'], { cwd: ROOT });
  return (r.stdout || 'unknown').trim();
}

function fail(phase, msg) {
  console.error(`PHASE ${phase} FAILED: ${msg}`);
  process.exit(1);
}

const phase = (process.argv[process.argv.indexOf('--phase') + 1] || '').toLowerCase();

if (phase === 'start') {
  log('container-start.log', `=== start ${new Date().toISOString()} ===\n`);
  docker(['rm', '-f', MYSQL_NAME]);
  const r = docker(['run', '-d', '--name', MYSQL_NAME,
    '-p', `127.0.0.1:${MYSQL_PORT}:3306`,
    '--tmpfs', TMPFS,
    '-e', `MYSQL_ROOT_PASSWORD=${MYSQL_PWD}`,
    '-e', `MYSQL_DATABASE=${DB_NAME}`,
    MYSQL_IMAGE, '--skip-log-bin']);
  log('container-start.log', `docker run exit=${r.status}\n${r.stdout}${r.stderr}`);
  // mysqld args must follow the image name (docker parses flags only before IMAGE):
  // --skip-log-bin is the apparatus capacity fix disclosed in apparatus-correction-note.md §4:
  // with binlog_format=ROW every reset/casSummarize UPDATE logs a full row image
  // (paper_json 20480B) and fills the one-shot container's tmpfs mid-run, while this
  // single-node container has no replication consumer at all.
  if (r.status !== 0) fail('start', 'docker run mysql failed');
  const containerId = r.stdout.trim();
  let ready = false;
  for (let i = 0; i < 90; i++) {
    // real readiness: an authenticated query, not mysqladmin ping (the temp init server
    // answers ping before MYSQL_ROOT_PASSWORD is applied)
    const probe = docker(['exec', '-e', `MYSQL_PWD=${MYSQL_PWD}`, MYSQL_NAME,
      'mysql', '-uroot', '-N', '-B', '-e', 'SELECT 1']);
    if (probe.status === 0 && probe.stdout.trim() === '1') { ready = true; break; }
    spawnSync('cmd.exe', ['/c', 'timeout', '/t', '2', '/nobreak'], { shell: false });
  }
  if (!ready) fail('start', 'mysql never became ready');
  const digest = docker(['image', 'inspect', '--format', '{{join .RepoDigests ","}}', MYSQL_IMAGE]);
  const version = docker(['exec', '-e', `MYSQL_PWD=${MYSQL_PWD}`, MYSQL_NAME,
    'mysql', '-uroot', '-N', '-B', '-e', 'SELECT VERSION()']);
  // Real apparatus facts, not script constants: read the applied container config back
  // (docker inspect) plus the server variable (SELECT @@log_bin). This closes the
  // constant-fake-green hole where a run flag silently lost or relocated (port, tmpfs,
  // --skip-log-bin) would still print a green line: see apparatus-correction-note.md 1/4.
  const inspectPorts = docker(['inspect', MYSQL_NAME, '--format', '{{json .HostConfig.PortBindings}}']);
  const inspectTmpfs = docker(['inspect', MYSQL_NAME, '--format', '{{json .HostConfig.Tmpfs}}']);
  const inspectCmd = docker(['inspect', MYSQL_NAME, '--format', '{{json .Config.Cmd}}']);
  const logBin = docker(['exec', '-e', `MYSQL_PWD=${MYSQL_PWD}`, MYSQL_NAME,
    'mysql', '-uroot', '-N', '-B', '-e', 'SELECT @@log_bin, @@log_bin_basename']);
  log('container-start.log',
    `containerId=${containerId}\nimageDigest=${digest.stdout.trim()}\n`
    + `version=${version.stdout.trim()}\ndb=${DB_NAME}\n`
    + `requestedPort=127.0.0.1:${MYSQL_PORT}\nrequestedTmpfs=${TMPFS}\n`
    + `inspect.PortBindings=${inspectPorts.stdout.trim()}\n`
    + `inspect.Tmpfs=${inspectTmpfs.stdout.trim()}\n`
    + `inspect.Config.Cmd=${inspectCmd.stdout.trim()}\n`
    + `SELECT @@log_bin,@@log_bin_basename=[${logBin.stdout.trim()}]\n`);
  // Hard guards: inspected/polled facts must match the requested apparatus; any mismatch
  // fails START instead of letting a lost parameter pass as green.
  let boundPorts = {};
  let tmpfsMap = {};
  try { boundPorts = JSON.parse(inspectPorts.stdout.trim() || '{}'); }
  catch (e) { fail('start', 'inspect PortBindings not JSON: ' + inspectPorts.stdout.trim()); }
  try { tmpfsMap = JSON.parse(inspectTmpfs.stdout.trim() || '{}'); }
  catch (e) { fail('start', 'inspect Tmpfs not JSON: ' + inspectTmpfs.stdout.trim()); }
  const pb = ((boundPorts['3306/tcp'] || [])[0]) || {};
  if (pb.HostIp !== '127.0.0.1' || String(pb.HostPort) !== MYSQL_PORT) {
    fail('start', `inspected PortBindings ${inspectPorts.stdout.trim()} != requested 127.0.0.1:${MYSQL_PORT}`);
  }
  if (tmpfsMap['/var/lib/mysql'] === undefined) {
    fail('start', `inspected Tmpfs ${inspectTmpfs.stdout.trim()} missing /var/lib/mysql`);
  }
  const logBinFields = logBin.stdout.trim().split('\t');
  if (logBinFields[0] !== '0') {
    fail('start', `@@log_bin=${logBinFields[0]} (expected 0): --skip-log-bin did not take effect`);
  }
  console.log('PHASE start OK');
} else if (phase === 'start-redis') {
  log('container-start.log', `=== start-redis ${new Date().toISOString()} ===\n`);
  docker(['rm', '-f', REDIS_NAME]);
  const r = docker(['run', '-d', '--name', REDIS_NAME,
    '-p', `127.0.0.1:${REDIS_PORT}:6379`, REDIS_IMAGE]);
  log('container-start.log', `docker run redis exit=${r.status}\n${r.stdout}${r.stderr}`);
  if (r.status !== 0) fail('start-redis', 'docker run redis failed');
  const digest = docker(['image', 'inspect', '--format', '{{join .RepoDigests ","}}', REDIS_IMAGE]);
  const ping = docker(['exec', REDIS_NAME, 'redis-cli', 'ping']);
  log('container-start.log',
    `redisContainerId=${r.stdout.trim()}\nredisImageDigest=${digest.stdout.trim()}\n`
    + `redisPort=127.0.0.1:${REDIS_PORT}\nredisPing=${ping.stdout.trim()}\n`);
  console.log('PHASE start-redis OK');
} else if (phase === 'setup') {
  log('container-setup.log', `=== setup ${new Date().toISOString()} ===\n`);
  const schemaPath = join(ROOT, 'src/main/resources/schema.sql');
  if (!existsSync(schemaPath)) fail('setup', 'schema.sql missing');
  const schemaSql = readFileSync(schemaPath, 'utf8');
  const create = docker(['exec', '-e', `MYSQL_PWD=${MYSQL_PWD}`, MYSQL_NAME, 'mysql', '-uroot',
    '--default-character-set=utf8mb4', '-e',
    `DROP DATABASE IF EXISTS ${DB_NAME}; CREATE DATABASE ${DB_NAME} CHARACTER SET utf8mb4;`]);
  log('container-setup.log', `create-db exit=${create.status} ${create.stderr}`);
  if (create.status !== 0) fail('setup', 'create db failed');
  const apply = docker(['exec', '-i', '-e', `MYSQL_PWD=${MYSQL_PWD}`, MYSQL_NAME, 'mysql', '-uroot',
    '--default-character-set=utf8mb4', DB_NAME], { input: schemaSql });
  log('container-setup.log', `SCHEMA_EXIT=${apply.status}\n${apply.stderr}`);
  if (apply.status !== 0) fail('setup', 'schema.sql apply failed (exit != 0)');
  const version = docker(['exec', '-e', `MYSQL_PWD=${MYSQL_PWD}`, MYSQL_NAME, 'mysql', '-uroot', '-N', '-B',
    '-e', 'SELECT VERSION()']);
  const tables = docker(['exec', '-e', `MYSQL_PWD=${MYSQL_PWD}`, MYSQL_NAME, 'mysql', '-uroot', '-N', '-B',
    DB_NAME, '-e', 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '
      + `'${DB_NAME}'`]);
  const showCreate1 = docker(['exec', '-e', `MYSQL_PWD=${MYSQL_PWD}`, MYSQL_NAME, 'mysql', '-uroot',
    DB_NAME, '-e', 'SHOW CREATE TABLE exam_submissions\\G']);
  const showCreate2 = docker(['exec', '-e', `MYSQL_PWD=${MYSQL_PWD}`, MYSQL_NAME, 'mysql', '-uroot',
    DB_NAME, '-e', 'SHOW CREATE TABLE subjective_grades\\G']);
  const showIdx1 = docker(['exec', '-e', `MYSQL_PWD=${MYSQL_PWD}`, MYSQL_NAME, 'mysql', '-uroot', '-B',
    DB_NAME, '-e', 'SHOW INDEX FROM exam_submissions']);
  const showIdx2 = docker(['exec', '-e', `MYSQL_PWD=${MYSQL_PWD}`, MYSQL_NAME, 'mysql', '-uroot', '-B',
    DB_NAME, '-e', 'SHOW INDEX FROM subjective_grades']);
  log('container-setup.log',
    `SELECT VERSION()=${version.stdout.trim()}\ntableCount=${tables.stdout.trim()}\n`
    + `${showCreate1.stdout}${showCreate2.stdout}${showIdx1.stdout}${showIdx2.stdout}\n`);
  console.log('PHASE setup OK (SCHEMA_EXIT=0)');
} else if (phase === 'rounds') {
  const rev = revShort();
  const out = join(MEASURE_DIR, 'it-run.log');
  writeFileSync(out, `=== rounds rev=${rev} ${new Date().toISOString()} ===\n`);
  const mvnArgs = ['/c', 'mvnw.cmd', 'test', '-Dtest=GradingScoreProjectionMeasureIT',
    '-DfailIfNoTests=false', '-DargLine=-Xmx6g',
    `-Dmeasure.rev=${rev}`, '-Dmeasure.label=rounds1', `-Dmeasure.out=${MEASURE_DIR}`,
    // no '&' on the command line: the IT assembles the JDBC URL from these (E2/cmd safety)
    `-Dmeasure.mysql.port=${MYSQL_PORT}`, `-Dmeasure.mysql.database=${DB_NAME}`];
  const child = spawn('cmd.exe', mvnArgs, {
    cwd: ROOT,
    env: { ...process.env, JAVA_HOME: 'D:\\develop1\\jdk21', REDIS_PORT,
      MEASURE_MYSQL_PWD: MYSQL_PWD },
  });
  const code = await new Promise((resolve) => {
    child.stdout.on('data', (d) => appendFileSync(out, String(d)));
    child.stderr.on('data', (d) => appendFileSync(out, String(d)));
    child.on('close', resolve);
  });
  log('it-run-summary.log', `itExit=${code} log=${out}\n`);
  if (code !== 0) fail('rounds', `mvnw exit ${code}; see ${out}`);
  console.log('PHASE rounds OK');
} else if (phase === 'bytes') {
  const FROZEN = {
    M1: { endpoint: 'progress', grading: true, cols: 'id,grading_status' },
    M2: { endpoint: 'progress', grading: false, cols: 'submission_id,score' },
    M3: { endpoint: 'summarize', grading: true, cols: 'id,grading_status,objective_score' },
    M4: { endpoint: 'summarize', grading: false, cols: 'submission_id,question_id,score' },
    M5: { endpoint: 'preview', grading: true,
      cols: 'student_id,objective_score,subjective_score,total_score,partial_graded' },
  };
  const UNITS_PER_ENDPOINT = {
    progress: ['M1', 'M2'], summarize: ['M3', 'M4'], preview: ['M5'],
  };
  const SIZES = [200, 1000, 3000];

  function selectListOf(sql) {
    const from = sql.indexOf(' FROM ');
    if (!sql.startsWith('SELECT ') || from < 0) return '';
    return sql.substring('SELECT '.length, from);
  }

  function normalize(csv) {
    return csv.replace(/\s+/g, '').toLowerCase();
  }

  function containerQuery(q) {
    const r = docker(['exec', '-e', `MYSQL_PWD=${MYSQL_PWD}`, MYSQL_NAME, 'mysql', '-uroot',
      '--default-character-set=utf8mb4', '-N', '-B', DB_NAME, '-e', q]);
    if (r.status !== 0) fail('bytes', `query failed: ${r.stderr}\n${q}`);
    return r.stdout.trim();
  }

  function bytesOf(literalSql) {
    const cols = selectListOf(literalSql).split(',').map((c) => c.trim()).filter(Boolean);
    const sums = cols.map((c, i) => `COALESCE(SUM(COALESCE(LENGTH(CAST(${c} AS CHAR)),0)),0) AS b${i}`);
    const q = `SELECT ${sums.join(',')},COUNT(*) AS cnt FROM (${literalSql}) AS t`;
    const out = containerQuery(q);
    const parts = out.split('\t').map((x) => parseInt(x, 10));
    const cnt = parts[parts.length - 1];
    const bytes = parts.slice(0, parts.length - 1).reduce((a, b) => a + b, 0);
    return { bytes, cnt, perCol: parts.slice(0, parts.length - 1), cols };
  }

  const bytesLog = [];
  for (const n of SIZES) {
    const rounds = JSON.parse(readFileSync(join(MEASURE_DIR, `rounds-n${n}.json`), 'utf8'));
    const result = { n, units: {}, verify: {} };
    for (const [endpoint, units] of Object.entries(UNITS_PER_ENDPOINT)) {
      const epNode = rounds.endpoints[endpoint];
      const roundKeys = Object.keys(epNode.rounds);
      for (const unit of units) {
        const spec = FROZEN[unit];
        const grading = spec.grading;
        const pick = (armWanted) => {
          for (const key of roundKeys) {
            const node = epNode.rounds[key];
            if (node.arm !== armWanted || node.exit !== 0) continue;
            const stmt = (node.targets.statements || []).find((s) => s.unit === unit);
            if (stmt) return stmt;
          }
          return null;
        };
        const oldStmt = pick('OLD');
        const projStmt = pick('PROJ');
        const oldrepStmt = pick('OLDrep');
        if (!oldStmt || !projStmt || !oldrepStmt) fail('bytes', `missing capture for ${unit} n=${n}`);
        const oldBytes = bytesOf(oldStmt.literalSql);
        const projBytes = bytesOf(projStmt.literalSql);
        const unitNode = {
          endpoint,
          frozenCols: spec.cols,
          oldSelectList: normalize(selectListOf(oldStmt.sql)),
          projSelectList: normalize(selectListOf(projStmt.sql)),
          oldrepSelectListEqualsOld:
            normalize(selectListOf(oldrepStmt.sql)) === normalize(selectListOf(oldStmt.sql)),
          oldLiteralEqualsOldrepLiteral: oldStmt.literalSql === oldrepStmt.literalSql,
          oldBytes: oldBytes.bytes,
          projBytes: projBytes.bytes,
          oldRows: oldBytes.cnt,
          projRows: projBytes.cnt,
          oldPerCol: oldBytes.perCol,
          oldCols: oldBytes.cols,
          projPerCol: projBytes.perCol,
          projCols: projBytes.cols,
          checks: {
            projSelectEqualsFrozen: projBytes.cols.join(',') === spec.cols,
            oldDiffersFromProj:
              normalize(selectListOf(oldStmt.sql)) !== normalize(selectListOf(projStmt.sql)),
            oldContainsLongField: grading
              ? normalize(selectListOf(oldStmt.sql)).includes('answers')
              : normalize(selectListOf(oldStmt.sql)).includes('student_answer'),
            oldrepSameTextAsOld: oldStmt.literalSql === oldrepStmt.literalSql,
            rowsEqual: oldBytes.cnt === projBytes.cnt,
          },
        };
        unitNode.ratio = unitNode.projBytes > 0 ? unitNode.oldBytes / unitNode.projBytes : -1;
        result.units[unit] = unitNode;
      }
    }
    // V1 verify (container side, independent of the IT JVM)
    const p = 981_100_000 + n;
    const s = 981_200_000 + n;
    const v = 981_300_000 + n;
    const one = (q) => parseInt(containerQuery(q), 10);
    result.verify = {
      progressRows: one(`SELECT COUNT(*) FROM exam_submissions WHERE exam_id=${p}`),
      summarizeRows: one(`SELECT COUNT(*) FROM exam_submissions WHERE exam_id=${s}`),
      previewRows: one(`SELECT COUNT(*) FROM exam_submissions WHERE exam_id=${v}`),
      subjectiveRows: one(`SELECT COUNT(*) FROM subjective_grades WHERE exam_id IN (${p},${s})`),
      answersNonNull: one(`SELECT COUNT(*) FROM exam_submissions WHERE exam_id IN (${p},${s},${v})`
        + ` AND answers IS NOT NULL`),
      answersMax: one(`SELECT COALESCE(MAX(LENGTH(answers)),0) FROM exam_submissions`
        + ` WHERE exam_id IN (${p},${s},${v})`),
      paperJsonMax: one(`SELECT COALESCE(MAX(LENGTH(paper_json)),0) FROM exam_submissions`
        + ` WHERE exam_id IN (${p},${s},${v})`),
      studentAnswerNonNull: one(`SELECT COUNT(*) FROM subjective_grades`
        + ` WHERE exam_id IN (${p},${s}) AND student_answer IS NOT NULL`),
      studentAnswerMax: one(`SELECT COALESCE(MAX(LENGTH(student_answer)),0) FROM subjective_grades`
        + ` WHERE exam_id IN (${p},${s})`),
    };
    result.verify.checks = {
      rowsOk: result.verify.progressRows === n && result.verify.summarizeRows === n
        && result.verify.previewRows === n,
      subjectiveRowsOk: result.verify.subjectiveRows === 4 * n,
      answersOk: result.verify.answersNonNull === 3 * n && result.verify.answersMax === 2048,
      paperOk: result.verify.paperJsonMax === 20480,
      studentAnswerOk: result.verify.studentAnswerNonNull === 4 * n
        && result.verify.studentAnswerMax === 512,
    };
    writeFileSync(join(MEASURE_DIR, `bytes-n${n}.json`),
      JSON.stringify(result, null, 2));
    bytesLog.push(`n=${n} ` + Object.entries(result.units).map(([u, x]) =>
      `${u} old=${x.oldBytes} proj=${x.projBytes} ratio=${x.ratio.toFixed(2)}`
      + ` checks=${JSON.stringify(x.checks)}`).join(' | '));
    bytesLog.push(`n=${n} verify=${JSON.stringify(result.verify)}`);
  }
  writeFileSync(join(VERIFY_DIR, 'bytes-accounting.log'), bytesLog.join('\n') + '\n');
  console.log('PHASE bytes OK');
} else if (phase === 'stop') {
  log('container-stop.log', `=== stop ${new Date().toISOString()} ===\n`);
  const sizeBefore = docker(['exec', REDIS_NAME, 'redis-cli', '-n', '15', 'dbsize']);
  const flush = docker(['exec', REDIS_NAME, 'redis-cli', '-n', '15', 'flushdb']);
  const sizeAfter = docker(['exec', REDIS_NAME, 'redis-cli', '-n', '15', 'dbsize']);
  log('container-stop.log', `redisDb15SizeBefore=${sizeBefore.stdout.trim()}`
    + ` flushExit=${flush.status} sizeAfter=${sizeAfter.stdout.trim()}\n`);
  const rm1 = docker(['rm', '-f', MYSQL_NAME]);
  const rm2 = docker(['rm', '-f', REDIS_NAME]);
  log('container-stop.log', `docker rm -f mysql exit=${rm1.status} out=${rm1.stdout.trim()}`
    + ` ${rm1.stderr.trim()}\ndocker rm -f redis exit=${rm2.status} out=${rm2.stdout.trim()}`
    + ` ${rm2.stderr.trim()}\n`);
  const psFilter = docker(['ps', '-a', '--filter', `name=gsproj`, '--format', '{{.Names}}']);
  const psAll = docker(['ps', '-a', '--format', '{{.Names}}\t{{.Ports}}']);
  log('container-stop.log', `gsproj filter zero-match stdout=[${psFilter.stdout.trim()}]`
    + ` exit=${psFilter.status}\nremaining containers (other projects, untouched):\n${psAll.stdout}\n`);
  console.log('PHASE stop OK');
} else {
  console.error('usage: node run-grading-score-projection-mysql.mjs --phase '
    + 'start|start-redis|setup|rounds|bytes|stop');
  process.exit(1);
}
