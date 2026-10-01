#!/usr/bin/env node
// analyze-grading-score-projection.cjs — the sole producer of adjudication.json
// (project-grading-score-scalar-projection; frozen operators in evidence/PREREGISTRATION.md §3).
//
// Usage: node analyze-grading-score-projection.cjs <measureDir> [outDir]
//   measureDir: D:/code/examOnline-measure/grading-score-projection (rounds-n*.json, bytes-n*.json,
//               capture-manifest.json, seed-counts-n*.json)
//   outDir:     where adjudication.json + verdict-console.txt are written (default: measureDir)
//
// Exit codes: 0 = adjudication complete (GO/NO-GO per unit is a valid outcome);
//             2 = measurement invalid (M-group failure: stop, keep the scene, report).
// Reading rules: per-unit/per-shape/per-round, exactly the frozen operators; no median
// substitution, no round picking, no threshold lowering; failed rounds kept as recorded.

'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const measureDir = process.argv[2] || 'D:/code/examOnline-measure/grading-score-projection';
const outDir = process.argv[3] || measureDir;
const SIZES = [200, 1000, 3000];
const ENDPOINTS = { progress: ['M1', 'M2'], summarize: ['M3', 'M4'], preview: ['M5'] };
const UNIT_ENDPOINT = { M1: 'progress', M2: 'progress', M3: 'summarize', M4: 'summarize', M5: 'preview' };
const UNIT_IS_GRADING = { M1: true, M2: false, M3: true, M4: false, M5: true };
const FROZEN = {
  M1: 'id,grading_status',
  M2: 'submission_id,score',
  M3: 'id,grading_status,objective_score',
  M4: 'submission_id,question_id,score',
  M5: 'student_id,objective_score,subjective_score,total_score,partial_graded',
};
const UNIT_ROWS = (unit, n) => (UNIT_IS_GRADING[unit] ? n : 2 * n);
const ARMS = ['OLD', 'PROJ', 'OLDrep'];

const readJson = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));
const sha256 = (p) => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');

const manifest = readJson(path.join(measureDir, 'capture-manifest.json'));
const problems = Array.isArray(manifest.problems) ? manifest.problems : [];
const rev = manifest.rev || 'unknown';

// ---------- M2 container validity ----------
const setupLogPath = 'D:/code/examOnline-verify/grading-score-projection/container-setup.log';
const setupLog = fs.existsSync(setupLogPath) ? fs.readFileSync(setupLogPath, 'utf8') : '';
const schemaExitOk = /SCHEMA_EXIT=0/.test(setupLog);
let m2 = { schemaExitOk, perShape: {} };
for (const n of SIZES) {
  const seed = readJson(path.join(measureDir, `seed-counts-n${n}.json`));
  const bytes = readJson(path.join(measureDir, `bytes-n${n}.json`));
  m2.perShape[n] = {
    seedOk: seed.ok === true,
    verifyChecks: bytes.verify.checks,
    verifyOk: Object.values(bytes.verify.checks).every(Boolean),
  };
}
const m2Ok = schemaExitOk && Object.values(m2.perShape).every((x) => x.seedOk && x.verifyOk);

// ---------- gather per-shape facts ----------
const shapes = {};
for (const n of SIZES) {
  const rounds = readJson(path.join(measureDir, `rounds-n${n}.json`));
  const bytes = readJson(path.join(measureDir, `bytes-n${n}.json`));
  const shape = { endpoints: {}, problemsRelated: [] };

  for (const [endpoint, units] of Object.entries(ENDPOINTS)) {
    const epNode = rounds.endpoints[endpoint];
    const roundNodes = Object.values(epNode.rounds);
    const byArm = {};
    for (const arm of ARMS) {
      byArm[arm] = roundNodes.filter((r) => r.arm === arm);
    }
    const successCount = (arm) => byArm[arm].filter((r) => r.exit === 0).length;
    const successful = (arm) => byArm[arm].filter((r) => r.exit === 0);

    // M3 round completeness
    const m3 = { perArm: {}, ok: true };
    for (const arm of ARMS) {
      m3.perArm[arm] = { attempts: byArm[arm].map((r) => r.attempt), success: successCount(arm) };
      if (successCount(arm) < 5) m3.ok = false;
    }

    // S3 noise band (endpoint-level)
    const oldNs = successful('OLD').map((r) => r.ns);
    const oldrepNs = successful('OLDrep').map((r) => r.ns);
    const noiseUpper = Math.max(...oldNs, ...oldrepNs);
    const projNs = successful('PROJ').map((r) => r.ns);
    const s3 = {
      noiseUpper,
      projRoundsNs: projNs,
      ok: m3.ok && projNs.length >= 5 && projNs.every((x) => x <= noiseUpper),
    };

    // S1 (endpoint-level)
    const s1 = { ok: true, detail: {} };
    if (endpoint === 'summarize') {
      const roundsById = {};
      for (const arm of ARMS) {
        for (const r of successful(arm)) {
          roundsById[r.round] = roundsById[r.round] || {};
          roundsById[r.round][r.arm] = r;
        }
      }
      const perRound = {};
      let ok = Object.keys(roundsById).length >= 5;
      for (const [rid, armsMap] of Object.entries(roundsById)) {
        const armsPresent = ARMS.filter((a) => armsMap[a]);
        const outs = armsPresent.map((a) => JSON.stringify(armsMap[a].output));
        const cass = armsPresent.map((a) => JSON.stringify(armsMap[a].casSummarize));
        const casCounts = armsPresent.map((a) => (armsMap[a].casSummarize || []).length);
        const equalOut = outs.every((x) => x === outs[0]);
        const equalCas = cass.every((x) => x === cass[0]) && casCounts.every((c) => c === n);
        perRound[rid] = { equalOut, equalCas, casCounts };
        if (!equalOut || !equalCas) ok = false;
      }
      s1.detail.perRound = perRound;
      s1.ok = ok;
    } else {
      const oracle = epNode.s1Oracle || {};
      s1.detail.equal = oracle.equal === true;
      s1.ok = oracle.equal === true;
    }
    // guard (both arms, every successful round + oracle arms)
    const guardOk = { proj: true, old: true, dbLong: true };
    for (const r of successful('PROJ')) {
      if (r.guard.answersNonNullTotal !== 0 || r.guard.studentAnswerNonNullTotal !== 0
        || r.guard.commentNonNullTotal !== 0) guardOk.proj = false;
    }
    // PREREG 2.2 OLD-arm guard requirement is on `answers` non-null > 0 only. The SubjectiveGrade
    // long fields (studentAnswer/comment) only exist for endpoints whose target set actually returns
    // SubjectiveGrade rows (progress=M2, summarize=M4); preview (M5) returns no subjective rows, so
    // requiring studentAnswer/comment > 0 there is structurally unsatisfiable and is not in the frozen text.
    const hasSubjectiveTarget = endpoint === 'progress' || endpoint === 'summarize';
    for (const arm of ['OLD', 'OLDrep']) {
      for (const r of successful(arm)) {
        if (r.guard.answersNonNullTotal <= 0) guardOk.old = false;
        if (hasSubjectiveTarget && (r.guard.studentAnswerNonNullTotal <= 0
          || r.guard.commentNonNullTotal <= 0)) guardOk.old = false;
      }
    }
    if (endpoint !== 'summarize') {
      const projOracle = (epNode.arms.ORACLE_PROJ || {}).guard || {};
      if (projOracle.answersNonNullTotal !== 0 || projOracle.studentAnswerNonNullTotal !== 0) {
        guardOk.proj = false;
      }
    }
    const dbLong = successful('PROJ').map((r) => r.guard.dbLongRows);
    guardOk.dbLong = dbLong.length > 0 && dbLong.every((x) => x > 0);
    s1.detail.guard = guardOk;
    s1.ok = s1.ok && guardOk.proj && guardOk.old && guardOk.dbLong;

    shape.endpoints[endpoint] = { m3, s3, s1 };
  }

  // per-unit M1/S2/S4 from bytes JSON + rounds accounting
  shape.units = {};
  for (const unit of Object.keys(FROZEN)) {
    const endpoint = UNIT_ENDPOINT[unit];
    const b = bytes.units[unit];
    const m1 = {
      projSelectList: b.projSelectList,
      oldSelectList: b.oldSelectList,
      frozen: FROZEN[unit],
      checks: b.checks,
      ok: b.checks.projSelectEqualsFrozen && b.checks.oldDiffersFromProj
        && b.checks.oldContainsLongField && b.checks.oldrepSameTextAsOld
        && b.checks.rowsEqual,
    };
    const s2 = {
      oldBytes: b.oldBytes, projBytes: b.projBytes, ratio: b.ratio,
      ok: b.ratio >= 5.0,
    };
    const epNode = rounds.endpoints[endpoint];
    const roundNodes = Object.values(epNode.rounds);
    const targetMsId = UNIT_IS_GRADING[unit]
      ? 'com.exam.grading.mapper.GradingSubmissionMapper.selectList'
      : 'com.exam.grading.mapper.SubjectiveGradeMapper.selectList';
    const s4 = { perRound: {}, ok: true };
    for (let r = 1; r <= 5; r++) {
      const armsMap = {};
      for (const arm of ARMS) {
        const node = roundNodes.find((x) => x.arm === arm && x.round === r);
        if (!node) { s4.ok = false; continue; }
        const acc = (node.accounting[targetMsId] || { queries: -1, rows: -1 });
        armsMap[arm] = {
          totalStatements: node.totalStatements,
          queries: acc.queries,
          rows: acc.rows,
          targetCountsOk: node.targetCountsOk === true,
          casCount: (node.casSummarize || []).length,
        };
      }
      const vals = ARMS.map((a) => armsMap[a]).filter(Boolean);
      const sameTotal = vals.every((x) => x.totalStatements === vals[0].totalStatements);
      const sameQ = vals.every((x) => x.queries === 1);
      const sameR = vals.every((x) => x.rows === UNIT_ROWS(unit, n));
      const countsOk = vals.every((x) => x.targetCountsOk);
      const casOk = endpoint !== 'summarize' || vals.every((x) => x.casCount === n);
      s4.perRound[r] = { ...armsMap, sameTotal, sameQ, sameR, countsOk, casOk };
      if (!(sameTotal && sameQ && sameR && countsOk && casOk)) s4.ok = false;
    }
    shape.units[unit] = { m1, s2, s4 };
  }
  shapes[n] = shape;
}

// ---------- verdicts ----------
const unitsOut = {};
for (const unit of Object.keys(FROZEN)) {
  const endpoint = UNIT_ENDPOINT[unit];
  const reasons = [];
  let go = true;
  const perShape = {};
  if (!m2Ok) { go = false; reasons.push('M2 container validity failed'); }
  for (const n of SIZES) {
    const sh = shapes[n];
    const u = sh.units[unit];
    const ep = sh.endpoints[endpoint];
    const shapeOkM = u.m1.ok && m2Ok && ep.m3.ok;
    const shapeOkS = ep.s1.ok && ep.s3.ok && u.s2.ok && u.s4.ok;
    perShape[n] = {
      M1: u.m1.ok, M3: ep.m3.ok, S1: ep.s1.ok,
      S2: { ratio: u.s2.ratio, oldBytes: u.s2.oldBytes, projBytes: u.s2.projBytes, pass: u.s2.ok },
      S3: { noiseUpperNs: ep.s3.noiseUpper, projRoundsNs: ep.s3.projRoundsNs, pass: ep.s3.ok },
      S4: { pass: u.s4.ok, perRound: u.s4.perRound },
      shapePass: shapeOkM && shapeOkS,
    };
    if (!u.m1.ok) { go = false; reasons.push(`M1 capture validity failed at n=${n}`); }
    if (!ep.m3.ok) { go = false; reasons.push(`M3 round completeness failed at n=${n}`); }
    if (!ep.s1.ok) { go = false; reasons.push(`S1 semantic equivalence failed at n=${n}`); }
    if (!u.s2.ok) {
      go = false;
      reasons.push(`S2 byte benefit not established at n=${n} (ratio ${u.s2.ratio} < 5.0)`);
    }
    if (!ep.s3.ok) {
      go = false;
      reasons.push(`S3 unstable/no net gain at n=${n} (some PROJ round above OLD∪OLDrep band)`);
    }
    if (!u.s4.ok) { go = false; reasons.push(`S4 shape accounting failed at n=${n}`); }
  }
  unitsOut[unit] = {
    endpoint, verdict: go ? 'GO' : 'NO-GO', reasons,
    frozenColumns: FROZEN[unit], perShape,
  };
}

const measurementInvalid = !m2Ok || problems.length > 0
  || SIZES.some((n) => Object.values(shapes[n].endpoints)
    .some((e) => !e.m3.ok || !schemaExitOk));

const adjudication = {
  rev,
  generatedAtIso: new Date().toISOString(),
  preregistration: manifest.preregistration,
  guardCanary: manifest.guardCanary,
  measurementInvalid,
  problems,
  m2,
  units: unitsOut,
  excludedUnits: {
    M6: {
      unit: 'ScoreExportService.forEachSubmissionPage', verdict: 'EXCLUDE',
      reason: 'pageConsumer paths exportQuestionDetail/exportQuestionStats feed '
        + 'QuestionScoreResolver.resolveBatch which reads getAnswers() for every submission '
        + 'in the page; full-row consumption is legitimate, projection would break two exports.',
    },
    M7: {
      unit: 'ObjectiveGradingService.loadSubjectiveGrades', verdict: 'EXCLUDE',
      reason: 'grading hot-write path pre-read; card default excludes; consumed fields are only '
        + 'submission_id/question_id/id but write-path correctness takes precedence.',
    },
  },
  boundary: 'one-shot local MySQL 8 container (tmpfs, high port, dedicated database, destroyed after '
    + 'use) + single machine + empty concurrency + in-process test context; results are NOT '
    + 'extrapolable to production MySQL/Tomcat and do not constitute grading or score-publish P99 '
    + 'conclusions.',
  inputs: {}, // filled below
};

for (const f of ['capture-manifest.json', ...SIZES.flatMap((n) => [`rounds-n${n}.json`, `bytes-n${n}.json`, `seed-counts-n${n}.json`])]) {
  const p = path.join(measureDir, f);
  if (fs.existsSync(p)) adjudication.inputs[f] = sha256(p);
}

const outJson = path.join(outDir, 'adjudication.json');
fs.writeFileSync(outJson, JSON.stringify(adjudication, null, 2));

const lines = [];
lines.push(`ADJUDICATION rev=${rev} generatedAt=${adjudication.generatedAtIso}`);
lines.push(`measurementInvalid=${measurementInvalid} problems=${problems.length}`);
for (const unit of Object.keys(FROZEN)) {
  const u = unitsOut[unit];
  lines.push(`UNIT ${unit} (${u.endpoint}, cols=${u.frozenColumns}): ${u.verdict}`);
  for (const r of u.reasons) lines.push(`  - ${r}`);
  for (const n of SIZES) {
    const s = u.perShape[n];
    lines.push(`  n=${n} M1=${s.M1} M3=${s.M3} S1=${s.S1} S2=${s.S2.pass}`
      + `(ratio=${Number(s.S2.ratio).toFixed(2)}) S3=${s.S3.pass} S4=${s.S4.pass}`
      + ` shapePass=${s.shapePass}`);
  }
}
lines.push('EXCLUDED M6 forEachSubmissionPage: EXCLUDE (consumers read answers via resolveBatch)');
lines.push('EXCLUDED M7 loadSubjectiveGrades: EXCLUDE (grading hot-write path, card default)');
lines.push(`BOUNDARY ${adjudication.boundary}`);
fs.writeFileSync(path.join(outDir, 'verdict-console.txt'), lines.join('\n') + '\n');
console.log(lines.join('\n'));

process.exit(measurementInvalid ? 2 : 0);
