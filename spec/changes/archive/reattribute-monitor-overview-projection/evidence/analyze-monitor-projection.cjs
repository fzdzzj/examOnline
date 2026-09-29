#!/usr/bin/env node
// analyze-monitor-projection.cjs — reattribute-monitor-overview-projection 阶段 2 机械复算器。
// 冻结口径见同目录 PREREGISTRATION.md §3（算子）与 §4（输入/输出）。裁决由脚本按算子输出，不人脑算。
// 输入 = IT 捕获 JSON（capture-manifest / capture-n* / rounds-n* / seed-counts / capture-degenerates）
//        + 容器侧产物（bytes-n* / container-setup.log / container-start.log / container-stop.log / it-run.log）。
// 输出 = adjudication.json 与 verdict-console.txt。
// 用法：
//   node analyze-monitor-projection.cjs --rawdir <rawDir> --out <outDir>

const { readFileSync, writeFileSync, existsSync } = require('node:fs');
const { join, resolve } = require('node:path');

const arg = (name, dflt) => {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 ? process.argv[i + 1] : dflt;
};
const rawDir = resolve(arg('rawdir', '.'));
const outDir = resolve(arg('out', rawDir));
const SIZES = [200, 1000, 3000];
const RATIO_MIN = 5.0;
const FROZEN_PROJ_SELECT = 'student_id,status';

const j = (f) => JSON.parse(readFileSync(join(rawDir, f), 'utf8'));
const exists = (f) => existsSync(join(rawDir, f));
const norm = (s) => (s || '').replace(/\s+/g, '');
const consoleLines = [];

// ---------- 全局输入 ----------
const manifest = j('capture-manifest.json');
const setupLog = exists('container-setup.log') ? readFileSync(join(rawDir, 'container-setup.log'), 'utf8') : '';
const startLog = exists('container-start.log') ? readFileSync(join(rawDir, 'container-start.log'), 'utf8') : '';
const stopLog = exists('container-stop.log') ? readFileSync(join(rawDir, 'container-stop.log'), 'utf8') : '';
const itRunLog = exists('it-run.log') ? readFileSync(join(rawDir, 'it-run.log'), 'utf8') : '';
const seedCounts = j('seed-counts.json');
const degenerates = j('capture-degenerates.json');

const preregOk = manifest.preregMatch === true
  && manifest.preregRecordedSha256 === manifest.preregRecomputedSha256;
const itProblemsEmpty = manifest.problemsEmpty === true;
const itExitMatch = /exit=(\d+)/.exec(itRunLog);
const itExit = itExitMatch ? parseInt(itExitMatch[1], 10) : null;
const schemaApplyOk = /schema\.sql apply exit=0/.test(setupLog);
const digestLogged = /image=\S+@sha256:[0-9a-f]{64}/.test(setupLog) || /image=\S+ [0-9a-f]{12,}/.test(setupLog);
const showCreateLogged = /SHOW CREATE TABLE exam_submissions/.test(setupLog);
const showIndexLogged = /SHOW INDEX FROM exam_submissions/.test(setupLog);
const containersDestroyed = /零匹配证据/.test(stopLog)
  && /stdout=\[\]\s*\nstderr=\[\]/.test(stopLog.replace(/\r/g, ''));

consoleLines.push('监考总览答卷取数再归因（站点 s6）— 阶段 2 机械复算');
consoleLines.push(`rev=${manifest.rev} label=${manifest.label} generatedAtIso=${manifest.generatedAtIso}`);
consoleLines.push(`preregOk=${preregOk} (recorded=${manifest.preregRecordedSha256} recomputed=${manifest.preregRecomputedSha256})`);
consoleLines.push(`itExit=${itExit} problemsEmpty=${itProblemsEmpty} schemaApplyOk=${schemaApplyOk}`);
consoleLines.push('判据：M1 捕获有效 ∧ M2 容器有效 ∧ M3 轮次完整 ∧ S1 两臂语义等价 ∧ S2 ratio≥5.0 ∧ S3 PROJ 每轮 ≤ 噪声带上界 ∧ S4 形状账目（含窄读）');
consoleLines.push('');

const shapeVerdicts = {};

for (const n of SIZES) {
  const cap = j(`capture-n${n}.json`);
  const rounds = j(`rounds-n${n}.json`);
  const bytes = j(`bytes-n${n}.json`);
  const seed = seedCounts.find((x) => x.shape === n);
  const reasons = [];

  // ---- M1 捕获有效性 ----
  const projSel = cap.arms.PROJ.guard.targetSelectLists || [];
  const oldSel = cap.arms.OLD.guard.targetSelectLists || [];
  const substitutionsOk = bytes.arms.OLD.statements.concat(bytes.arms.PROJ.statements, bytes.arms.OLDrep.statements)
    .every((st) => st.substitution.placeholderCount === st.substitution.paramCount
      && st.substitution.replaced === st.substitution.paramCount);
  const m1Parts = {
    projSelectEqualsFrozen: projSel.length === 1 && norm(projSel[0]) === FROZEN_PROJ_SELECT,
    oldSelectDiffersFromProj: oldSel.length === 1 && norm(oldSel[0]) !== FROZEN_PROJ_SELECT,
    oldSelectContainsLongFields: oldSel.every((s) => s.includes('paper_json') && s.includes('answers')),
    narrowSelectOk: bytes.arms.PROJ.statements.length === 2
      && norm(bytes.arms.PROJ.statements[1].selectList) === 'paper_json'
      && /LIMIT\s+1/i.test(bytes.arms.PROJ.statements[1].literal),
    substitutionOk: substitutionsOk,
    oldrepSameTextAsOld: bytes.checks.oldrepSameTextAsOld === true
      && bytes.checks.oldrepSameTextAsRoundsOld === true,
    itProblemsEmpty,
    preregOk,
  };
  const M1 = Object.values(m1Parts).every(Boolean);
  if (!M1) reasons.push('M1 ' + JSON.stringify(m1Parts));

  // ---- M2 容器有效性 ----
  const m2Parts = {
    schemaApplyOk, digestLogged, showCreateLogged, showIndexLogged,
    seedOk: !!(seed && seed.ok === true),
    containerVerifyRows: bytes.checks.verifyRowsOk === true,
    containerVerifyAnswers: bytes.checks.verifyAnswersOk === true,
    containerVerifyLogs: bytes.checks.verifyLogsOk === true,
    containerExitsOk: bytes.checks.exitsOk === true,
  };
  const M2 = Object.values(m2Parts).every(Boolean);
  if (!M2) reasons.push('M2 ' + JSON.stringify(m2Parts));

  // ---- M3 轮次完整性 ----
  const succRounds = (arm) => {
    const rs = (rounds.arms[arm] && rounds.arms[arm].rounds) || [];
    const okRounds = new Set();
    for (const x of rs) if (x.exitCode === 0) okRounds.add(x.round);
    return okRounds.size;
  };
  const attemptsComplete = ['OLD', 'PROJ', 'OLDrep'].every((arm) =>
    ((rounds.arms[arm] && rounds.arms[arm].rounds) || []).every((x) =>
      typeof x.round === 'number' && typeof x.attempt === 'number'
      && typeof x.atIso === 'string' && x.atIso.length > 0));
  const m3succ = { OLD: succRounds('OLD'), PROJ: succRounds('PROJ'), OLDrep: succRounds('OLDrep') };
  const M3 = m3succ.OLD >= 5 && m3succ.PROJ >= 5 && m3succ.OLDrep >= 5 && attemptsComplete;
  if (!M3) reasons.push('M3 successful rounds ' + JSON.stringify(m3succ) + ' attemptsComplete=' + attemptsComplete);

  // ---- S1 语义等价 ----
  const guardOk = (node) => !!(node && node.guard && node.guard.ok === true);
  const roundGuardsOk = ['OLD', 'PROJ', 'OLDrep'].every((arm) =>
    ((rounds.arms[arm] && rounds.arms[arm].rounds) || [])
      .filter((x) => x.exitCode === 0).every((x) => guardOk(x)));
  const degOk = (d) => !!(d && d.ok === true && d.s1Equal === true);
  const s1Parts = {
    ownerEqual: cap.s1OwnerEqual === true,
    adminEqual: cap.s1AdminEqual === true,
    nonOwnerSame: cap.s1NonOwnerSame === true && cap.nonOwnerStatus === 403,
    captureGuardsOk: ['OLD', 'PROJ', 'adminOLD', 'adminPROJ', 'nonOwnerOLD', 'nonOwnerPROJ']
      .every((k) => guardOk(cap.arms[k])),
    roundGuardsOk,
    envChecksOk: !!(cap.envChecks && cap.envChecks.ok === true),
    degenerateD1: degOk(degenerates.D1),
    degenerateD2: degOk(degenerates.D2),
    degenerateD3: degOk(degenerates.D3),
    degenerateNarrowAccounting: degenerates.D1.PROJ.guard.narrowQueries === 0
      && degenerates.D2.PROJ.guard.narrowQueries === 1 && degenerates.D2.PROJ.guard.narrowRows === 0
      && degenerates.D3.PROJ.guard.narrowQueries === 1 && degenerates.D3.PROJ.guard.narrowRows === 1,
  };
  const S1 = Object.values(s1Parts).every(Boolean);
  if (!S1) reasons.push('S1 ' + JSON.stringify(s1Parts));

  // ---- S2 字节收益 ----
  const bOld = bytes.arms.OLD.bytes;
  const bProj = bytes.arms.PROJ.bytes;
  const ratio = bProj > 0 ? bOld / bProj : Infinity;
  const S2 = ratio >= RATIO_MIN;
  const s2detail = {
    bytesOld: bOld, bytesProj: bProj,
    bytesProjMain: bytes.arms.PROJ.mainBytes, bytesProjNarrow: bytes.arms.PROJ.narrowBytes,
    ratio: Number(ratio.toFixed(2)), threshold: RATIO_MIN, pass: S2,
  };
  if (!S2) reasons.push(`S2 字节收益不成立 ratio=${s2detail.ratio} < ${RATIO_MIN}`);

  // ---- S3 单侧无回归（每一轮） ----
  const okNs = (arm) => ((rounds.arms[arm] && rounds.arms[arm].rounds) || [])
    .filter((x) => x.exitCode === 0).map((x) => x.ns);
  const noise = [...okNs('OLD'), ...okNs('OLDrep')];
  const projNs = okNs('PROJ');
  const noiseUpper = noise.length ? Math.max(...noise) : null;
  const violations = (noiseUpper == null) ? null : projNs.filter((x) => x > noiseUpper);
  const S3 = !!(noiseUpper != null && projNs.length && violations.length === 0);
  const s3detail = {
    noiseUpperNs: noiseUpper, projRoundsNs: projNs,
    oldRoundsNs: okNs('OLD'), oldrepRoundsNs: okNs('OLDrep'),
    violations: violations || [], pass: S3,
  };
  if (!S3) reasons.push(`S3 不稳定/无净收益 proj=[${projNs.join(',')}] noiseUpper=${noiseUpper}`);

  // ---- S4 形状账目（本站点专用：主语句 1/1 与 n/n；窄读记账在 PROJ 同轮内） ----
  const gOld = cap.arms.OLD.guard;
  const gProj = cap.arms.PROJ.guard;
  const projRoundNarrowOk = ((rounds.arms.PROJ && rounds.arms.PROJ.rounds) || [])
    .filter((x) => x.exitCode === 0)
    .every((x) => x.guard && x.guard.narrowQueries === 1 && x.guard.narrowRows === 1);
  const s4Parts = {
    itMainOld: gOld.mainQueries === 1 && gOld.mainRows === n,
    itMainProj: gProj.mainQueries === 1 && gProj.mainRows === n,
    projNarrow: gProj.narrowQueries === 1 && gProj.narrowRows === 1,
    projRoundNarrowAccounting: projRoundNarrowOk,
    containerMainRows: bytes.checks.mainRowsOk === true,
    containerNarrowRows: bytes.checks.narrowRowsOk === true,
  };
  const S4 = Object.values(s4Parts).every(Boolean);
  if (!S4) reasons.push('S4 ' + JSON.stringify(s4Parts));

  shapeVerdicts[n] = {
    M1, M2, M3, S1, S2, S3, S4,
    verdict: (M1 && M2 && M3 && S1 && S2 && S3 && S4) ? 'GO' : 'NO-GO',
    reasons,
    detail: {
      m1Parts, m2Parts, m3successfulRounds: m3succ, s1Parts, s2: s2detail, s3: s3detail, s4: s4Parts,
      seed, guards: { old: gOld, proj: gProj },
      containerRows: { oldMain: bytes.arms.OLD.rows, projMain: bytes.arms.PROJ.mainRows, narrow: bytes.arms.PROJ.narrowRows },
    },
  };

  consoleLines.push(`n=${n}: ${shapeVerdicts[n].verdict}`);
  consoleLines.push(`  M1=${M1 ? 'P' : 'F'} M2=${M2 ? 'P' : 'F'} M3=${M3 ? 'P' : 'F'}`
    + ` S1=${S1 ? 'P' : 'F'} S2=${S2 ? 'P' : 'F'} S3=${S3 ? 'P' : 'F'} S4=${S4 ? 'P' : 'F'}`);
  consoleLines.push(`  S2 bytes: OLD=${bOld} PROJ=${bProj}(main=${bytes.arms.PROJ.mainBytes}+narrow=${bytes.arms.PROJ.narrowBytes})`
    + ` ratio=${s2detail.ratio} threshold=${RATIO_MIN}`);
  consoleLines.push(`  S3 ns: noiseUpper=${noiseUpper} proj=[${projNs.join(',')}]`);
  consoleLines.push(`  S3 额外往返是否抵消字段节省：${S2 && S3 ? '未抵消（S2 ∧ S3 均通过）' : (S2 ? '未成立——S3 不通过：不稳定/无净收益' : '未成立——S2 字节收益不成立')}`);
  consoleLines.push(`  S4 账目：主语句 OLD ${gOld.mainQueries}条/${gOld.mainRows}行，PROJ ${gProj.mainQueries}条/${gProj.mainRows}行；`
    + `窄读 PROJ ${gProj.narrowQueries}条/${gProj.narrowRows}行（同轮记账）`);
  if (reasons.length) {
    consoleLines.push('  failures: ' + reasons.join('；'));
  }
  consoleLines.push('');
}

const allGo = SIZES.every((n) => shapeVerdicts[n].verdict === 'GO');
const verdict = allGo ? 'GO' : 'NO-GO';
consoleLines.push(`裁决：${verdict}（${SIZES.map((n) => `n=${n}:${shapeVerdicts[n].verdict}`).join('，')}）`);
consoleLines.push(`容器销毁证据（零匹配）：${containersDestroyed}`);
consoleLines.push('边界：一次性本地容器、单机、空并发、MockMvc 测试上下文；结论不外推生产 MySQL/Tomcat，不构成交卷或监考 P99 结论。');

writeFileSync(join(outDir, 'adjudication.json'), JSON.stringify({
  generatedAtIso: new Date().toISOString(),
  rev: manifest.rev, label: manifest.label,
  preregOk, itProblemsEmpty, itExit, schemaApplyOk, containersDestroyed,
  verdict, shapes: shapeVerdicts,
}, null, 2));
writeFileSync(join(outDir, 'verdict-console.txt'), consoleLines.join('\n') + '\n');
console.log(consoleLines.join('\n'));
