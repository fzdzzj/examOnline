#!/usr/bin/env node
// analyze-scalar-reads.cjs — 阶段 2 机械复算器（PREREGISTRATION.md §3 的算子；裁决由脚本输出，不人脑算）。
// 输入 = IT 捕获 JSON（S1/S4 语义层）+ 容器逐轮 JSON/种子 JSON/安装日志（S2/S3/S4 引擎层）。
// 输出 = adjudication.json 与 verdict-console.txt。
// 用法：
//   node analyze-scalar-reads.cjs --capture <cap.json> --rawdir <rawDir> --out <outDir>

const { readFileSync, writeFileSync, existsSync } = require('node:fs');
const { join, resolve } = require('node:path');

const arg = (name, dflt) => {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 ? process.argv[i + 1] : dflt;
};
const capturePath = resolve(arg('capture'));
const rawDir = resolve(arg('rawdir', '.'));
const outDir = resolve(arg('out', rawDir));

const SITES = ['s1', 's2', 's3', 's4', 's5'];
const SIZES = [200, 1000, 3000];
const FROZEN_PROJ_COLUMNS = {
  s1: 'exam_id,status,deadline_time',
  s2: 'student_id',
  s3: 'status,objective_score,subjective_score,total_score,partial_graded',
  s4: 'id',
  s5: 'total_score,submit_time',
};
const EXPECTED_QUERIES = { s1: 1, s2: 1, s3: 1, s4: 1, s5: 2 };
const expectRows = (site, n) => (site === 's1' ? 45 : site === 's2' ? n : site === 's3' ? 1 : site === 's4' ? 1 : 2);
const RATIO_MIN = 5.0;

const j = (f) => JSON.parse(readFileSync(f, 'utf8'));
const cap = j(capturePath);
const norm = (s) => (s || '').replace(/\s+/g, '');

const siteVerdicts = {};
const consoleLines = [];
consoleLines.push('标量只读站点列投影归因 — 阶段 2 逐站点裁决（机械复算）');
consoleLines.push('rev=' + cap.rev + ' label=' + cap.label + ' generatedAtIso=' + cap.generatedAtIso);
consoleLines.push('判据：S1 语义等价 ∧ S2 bytesOLD/bytesPROJ ≥ 5.0(冻结) ∧ S3 PROJ 每轮 ≤ 噪声带上界 ∧ S4 条数/行数不变；M 组全成立');
consoleLines.push('');

const preregOk = cap.preregistration && cap.preregistration.match === true
  && cap.preregistration.recordedSha256 === cap.preregistration.recomputedSha256;
const itProblems = (cap.problems || []).length === 0;
const setupLog = existsSync(join(rawDir, 'container-setup.log'))
  ? readFileSync(join(rawDir, 'container-setup.log'), 'utf8') : '';
const schemaApplyOk = /schema\.sql apply exit=0/.test(setupLog);
const seedCounts = existsSync(join(rawDir, 'seed-counts.json')) ? j(join(rawDir, 'seed-counts.json')) : [];

for (const site of SITES) {
  const perShape = {};
  const siteReasons = [];
  for (const n of SIZES) {
    const failures = [];
    const shapeNode = (cap.shapes || []).find((x) => x.n === n);
    const siteNode = shapeNode && shapeNode.sites ? shapeNode.sites[site] : null;
    if (!siteNode) {
      perShape[n] = { M1: false, M2: false, M3: false, S1: false, S2: false, S3: false, S4: false,
        failures: ['capture missing site node'] };
      failures.push('capture missing site node');
      siteReasons.push(`n=${n}: capture 缺站点节点`);
      continue;
    }
    // ---- M1：捕获有效性（SQL 原文、替换计数、PROJ SELECT == 冻结列、OLD ≠ PROJ） ----
    const roundsFile = join(rawDir, `rounds-${site}-n${n}.json`);
    const rounds = existsSync(roundsFile) ? j(roundsFile) : null;
    const bytesFile = join(rawDir, `bytes-n${n}.json`);
    const bytesAll = existsSync(bytesFile) ? j(bytesFile) : null;
    const bytes = bytesAll ? bytesAll.find((x) => x.site === site) : null;
    const armsSqlFile = join(rawDir, `arms-sql-${site}-n${n}.log`);
    const armsSql = existsSync(armsSqlFile) ? j(armsSqlFile) : null;

    const projSelectList = norm(siteNode.projSelectList);
    const oldSelectList = norm(siteNode.oldSelectList);
    const m1Parts = {
      projSelectEqualsFrozen: projSelectList === norm(FROZEN_PROJ_COLUMNS[site]),
      oldSelectDiffersFromProj: oldSelectList !== projSelectList,
      substitutionOk: !!(armsSql && Object.values(armsSql.arms).flat()
        .every((st) => st.substitution.placeholderCount === st.substitution.paramCount
          && st.substitution.replaced === st.substitution.paramCount)),
      oldrepSameTextAsOld: !!(armsSql && armsSql.oldrepSameTextAsOld),
    };
    const M1 = Object.values(m1Parts).every(Boolean);
    if (!M1) failures.push('M1 ' + JSON.stringify(m1Parts));

    // ---- M2：容器有效性（schema exit 0、区块计数、长字段长度） ----
    const seed = seedCounts.find((x) => x.shape === n);
    const M2 = !!(schemaApplyOk && seed && seed.ok === true);
    if (!M2) failures.push('M2 schemaApplyOk=' + schemaApplyOk + ' seed=' + JSON.stringify(seed || null));

    // ---- M3：轮次完整性（每臂 ≥5 个成功轮） ----
    const succRounds = (f) => {
      if (!rounds || !rounds.arms || !rounds.arms[f]) return 0;
      const okRounds = new Set();
      for (const x of rounds.arms[f].rounds) if (x.exitCode === 0) okRounds.add(x.round);
      return okRounds.size;
    };
    const m3succ = { OLD: succRounds('OLD'), PROJ: succRounds('PROJ'), OLDrep: succRounds('OLDrep') };
    const M3 = m3succ.OLD >= 5 && m3succ.PROJ >= 5 && m3succ.OLDrep >= 5;
    if (!M3) failures.push('M3 successful rounds ' + JSON.stringify(m3succ));

    // ---- S1：IT 语义等价 + 护栏 ----
    const guard = siteNode.arms.PROJ.guard || {};
    const oldGuard = siteNode.arms.OLD.guard || {};
    const s1Parts = {
      equivalentAllShapes: siteNode.s1Equivalent === true,
      projAnswersNull: guard.answersNonNullTotal === 0,
      projPaperJsonNull: guard.paperJsonNonNullTotal === 0,
      dbLongRowsPositive: (guard.dbLongRows || 0) > 0,
      oldAnswersNonNullPositive: (oldGuard.answersNonNullTotal || 0) > 0,
      preregOk, itProblemsEmpty: itProblems,
    };
    const S1 = Object.values(s1Parts).every(Boolean);
    if (!S1) failures.push('S1 ' + JSON.stringify(s1Parts));

    // ---- S2：字节收益（ratio = bytes_OLD / bytes_PROJ ≥ 5.0） ----
    let S2 = false;
    let s2detail = null;
    if (bytes && bytes.arms.OLD && bytes.arms.PROJ) {
      const bOld = bytes.arms.OLD.bytes;
      const bProj = bytes.arms.PROJ.bytes;
      const ratio = bProj > 0 ? bOld / bProj : Infinity;
      S2 = ratio >= RATIO_MIN;
      s2detail = { bytesOld: bOld, bytesProj: bProj, ratio: Number(ratio.toFixed(4)),
        threshold: RATIO_MIN, pass: S2 };
      if (!S2) failures.push(`S2 字节收益不成立 ratio=${s2detail.ratio} < ${RATIO_MIN}`);
    } else {
      failures.push('S2 missing bytes data');
    }

    // ---- S3：单侧无回归（PROJ 每一轮 ≤ max(OLD∪OLDrep 成功轮)） ----
    let S3 = false;
    let s3detail = null;
    if (rounds) {
      const okNs = (f) => (rounds.arms[f] ? rounds.arms[f].rounds.filter((x) => x.exitCode === 0) : []);
      const noise = [...okNs('OLD'), ...okNs('OLDrep')].map((x) => x.ns).filter((x) => x != null);
      const proj = okNs('PROJ');
      if (noise.length && proj.length) {
        const noiseUpper = Math.max(...noise);
        const projNs = proj.map((x) => x.ns);
        S3 = projNs.every((x) => x <= noiseUpper);
        s3detail = { noiseUpperNs: noiseUpper, projRoundsNs: projNs,
          violations: projNs.filter((x) => x > noiseUpper), pass: S3 };
        if (!S3) failures.push(`S3 不稳定/无净收益 proj=${JSON.stringify(projNs)} > noiseUpper=${noiseUpper}`);
      } else {
        failures.push('S3 missing rounds data');
      }
    } else {
      failures.push('S3 rounds file missing');
    }

    // ---- S4：形状不变（IT 条数/行数 + 容器行数/条数双侧一致） ----
    const msId = siteNode.targetMsId;
    const acc = (arm) => (siteNode.arms[arm] && siteNode.arms[arm].accounting[msId]) || {};
    const expQ = EXPECTED_QUERIES[site];
    const expR = expectRows(site, n);
    const s4Parts = {
      itQueriesOld: acc('OLD').queries === expQ,
      itQueriesProj: acc('PROJ').queries === expQ,
      itRowsOld: acc('OLD').rows === expR,
      itRowsProj: acc('PROJ').rows === expR,
      ctrRowsOld: !!(bytes && bytes.arms.OLD && bytes.arms.OLD.rows === expR),
      ctrRowsProj: !!(bytes && bytes.arms.PROJ && bytes.arms.PROJ.rows === expR),
      stmtCountEqual: !!(rounds && rounds.statementCountOld === rounds.statementCountProj),
    };
    const S4 = Object.values(s4Parts).every(Boolean);
    if (!S4) failures.push('S4 ' + JSON.stringify(s4Parts));

    perShape[n] = { M1, M2, M3, S1, S2, S3, S4, failures, detail: {
      m1Parts, m3successfulRounds: m3succ, site1: s1Parts, s2: s2detail, s3: s3detail, s4: s4Parts,
      seed, itAccounting: { old: acc('OLD'), proj: acc('PROJ') },
      containerBytes: bytes ? { OLD: bytes.arms.OLD.bytes, PROJ: bytes.arms.PROJ.bytes } : null,
    } };
    if (failures.length) siteReasons.push(`n=${n}: ` + failures.join('；'));
  }
  const allShapesPass = SIZES.every((n) => perShape[n]
    && perShape[n].M1 && perShape[n].M2 && perShape[n].M3
    && perShape[n].S1 && perShape[n].S2 && perShape[n].S3 && perShape[n].S4);
  siteVerdicts[site] = { verdict: allShapesPass ? 'GO' : 'NO-GO', shapes: perShape, reasons: siteReasons };

  const line = (sh) => `  n=${sh}: ${['M1', 'M2', 'M3', 'S1', 'S2', 'S3', 'S4']
    .map((k) => `${k}=${perShape[sh][k] ? 'P' : 'F'}`).join(' ')}`;
  consoleLines.push(`${site}: ${allShapesPass ? 'GO（M1∧M2∧M3∧S1∧S2∧S3∧S4 三形状全成立）' : 'NO-GO'}`);
  for (const n of SIZES) {
    consoleLines.push(line(n));
    const d = perShape[n].detail;
    if (d.s2) consoleLines.push(`    S2 bytes: OLD=${d.s2.bytesOld} PROJ=${d.s2.bytesProj} ratio=${d.s2.ratio}`);
    if (d.s3) consoleLines.push(`    S3 ns: noiseUpper=${d.s3.noiseUpperNs} proj=[${d.s3.projRoundsNs.join(',')}]`);
    if (perShape[n].failures.length) consoleLines.push('    failures: ' + perShape[n].failures.join('；'));
  }
  consoleLines.push('');
}

const goCount = SITES.filter((s) => siteVerdicts[s].verdict === 'GO').length;
consoleLines.push(`裁决汇总：${SITES.map((s) => s + '=' + siteVerdicts[s].verdict).join('，')}（GO ${goCount}/${SITES.length}）`);
consoleLines.push('边界：一次性本地容器、单机、空并发、H2 测试上下文（MockMvc 层）；结论不外推生产 MySQL/Tomcat，不构成交卷或监考 P99 结论。');

writeFileSync(join(outDir, 'adjudication.json'), JSON.stringify({
  generatedAtIso: new Date().toISOString(), rev: cap.rev, preregOk, itProblems,
  schemaApplyOk, sites: siteVerdicts,
}, null, 2));
writeFileSync(join(outDir, 'verdict-console.txt'), consoleLines.join('\n') + '\n');
console.log(consoleLines.join('\n'));
