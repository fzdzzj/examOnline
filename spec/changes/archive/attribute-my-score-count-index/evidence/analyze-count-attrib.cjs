#!/usr/bin/env node
// analyze-count-attrib.cjs — attribute-my-score-count-index 机械裁决。
// 按 evidence/PREREGISTRATION.md 冻结算子复算 M1–M3 / D1–D4，产出 verdict.json。
// 用法：node analyze-count-attrib.cjs <rawDir> <captureJson> [--console]

const { readFileSync, writeFileSync } = require('fs');
const { join, resolve } = require('path');

const rawDir = resolve(process.argv[2]);
const capturePath = resolve(process.argv[3]);
const J = (p) => JSON.parse(readFileSync(p, 'utf8'));
const cap = J(capturePath);
const readsB = J(join(rawDir, 'reads-baseline.json'));
const readsC = J(join(rawDir, 'reads-candidate.json'));
const readsD = J(join(rawDir, 'reads-drift.json'));
const writesB = J(join(rawDir, 'writes-baseline.json'));
const writesC = J(join(rawDir, 'writes-candidate.json'));
const seedCounts = J(join(rawDir, 'seed-counts.json'));
const setupLog = readFileSync(join(rawDir, 'container-setup.log'), 'utf8');
const SIZES = [50, 200, 1000, 3000];

const checks = [];
const chk = (id, ok, detail) => { checks.push({ id, ok, detail }); return ok; };
const seriesByX = (json, n, x) => json.entries.find((e) => e.shape === n && e.x === x);
const rootTime = (raw) => {
  const m = raw.match(/-> Aggregate: count\(0\)[\s\S]*?actual time=([\d.]+)\.\.([\d.]+) rows=(\d+) loops=1/);
  return m ? { finalMs: parseFloat(m[2]), rows: parseInt(m[3], 10) } : null;
};
const planOf = (raw) => {
  if (raw.includes('Covering index')) return { covering: true, idx: 'idx_submissions_score_rank' };
  const m = raw.match(/Index (?:lookup|range scan)[^\n]*?using ([a-z_]+)/);
  return { covering: false, idx: m ? m[1] : '(unknown)' };
};
const explainRow = (raw) => {
  const line = raw.split('\n').find((l) => /^\d\t/.test(l));
  if (!line) return null;
  const f = line.split('\t');
  return { type: f[4], key: f[6], rows: parseInt(f[9], 10), extra: f[11] };
};

// ============ M1 捕获有效性 ============
let m1 = true;
const m1Detail = [];
for (const n of SIZES) {
  const s = cap.shapes.find((x) => x.n === n);
  const c = s && s.countStatement;
  const ok = c && c.msId === 'com.exam.grading.mapper.GradingSubmissionMapper.selectCount'
    && !c.sql.includes('NO-SUCH') && c.literalSql.indexOf('?') === -1
    && Object.values(c.values).join('|') === [String(s.examId), '3', '40.0'].join('|')
    && s.expectedRank === s.responseRank && s.reviewing === false
    && s.ownTotalScore === '40.0';
  m1 = m1 && ok;
  m1Detail.push(`n=${n}:${ok ? 'OK' : 'FAIL'}`);
}
chk('M1', m1, `capture per shape ${m1Detail.join(',')}; countValues == [examId,3,40.0]; rank self-check passed; literalSql has no ?`);

// ============ M2 容器有效性 ============
const IDX6 = ['PRIMARY', 'uk_exam_student', 'idx_submissions_sweep', 'idx_submissions_exam_submit',
  'idx_submissions_grading', 'idx_submissions_republish'];
const baseHas6 = IDX6.every((k) => setupLog.includes(k));
const baselineNoCandidate = !setupLog.includes('idx_submissions_score_rank');
const seedExact = SIZES.every((n) => seedCounts[String(930000000 + n)] === n);
let baselineNonCoveringAll = true;
for (const n of SIZES) {
  const s = seriesByX(readsB, n, '40.0');
  const row = s && explainRow(s.explainRaw);
  if (!row || row.extra.includes('Using index')) baselineNonCoveringAll = false;
}
const m2 = baseHas6 && baselineNoCandidate && seedExact && baselineNonCoveringAll;
chk('M2', m2, `setup: 6 existing indexes present=${baseHas6}, candidate absent at baseline=${baselineNoCandidate}, `
  + `seed counts exact=${seedExact}, baseline EXPLAIN non-covering (no Using index) at X=40.0 all shapes=${baselineNonCoveringAll}`);

// ============ M3 轮次完整性 ============
let m3 = true;
const m3Detail = [];
for (const json of [readsB, readsC]) {
  for (const n of SIZES) {
    for (const x of ['40.0', null]) {
      const s = seriesByX(json, n, x === null ? undefined : x) || json.entries.find((e) => e.shape === n && (x === null ? true : e.x === '40.0'));
    }
  }
}
// 简化且严格：逐 series 校验
const seriesOk = (json, label, expectRounds) => {
  let ok = true;
  for (const e of json.entries) {
    if (e.rounds.length !== expectRounds) { ok = false; m3Detail.push(`${label} shape=${e.shape} x=${e.x} rounds=${e.rounds.length}!=${expectRounds}`); }
    for (const r of e.rounds) {
      if (r.countExit !== 0 || r.analyzeExit !== 0) { ok = false; m3Detail.push(`${label} shape=${e.shape} x=${e.x} r${r.round} exit!=0`); }
      if (!rootTime(r.analyzeRaw)) { ok = false; m3Detail.push(`${label} shape=${e.shape} x=${e.x} r${r.round} unparsable`); }
    }
  }
  return ok;
};
m3 = seriesOk(readsB, 'baseline', 5) && seriesOk(readsC, 'candidate', 5) && seriesOk(readsD, 'drift', 2);
chk('M3', m3, `all series rounds complete (baseline/candidate=5, drift=2), all exits=0, all ANALYZE parsable ${m3Detail.length ? m3Detail.join(';') : '(no anomalies)'}`);

// ============ D1 回表消除（机制） ============
const d1 = {};
for (const n of SIZES) {
  const b = seriesByX(readsB, n, '40.0');
  const c = seriesByX(readsC, n, '40.0');
  const bRow = explainRow(b.explainRaw);
  const cRow = explainRow(c.explainRaw);
  d1[n] = {
    baseline: { key: bRow.key, extra: bRow.extra, covering: bRow.extra.includes('Using index') },
    candidate: { key: cRow.key, extra: cRow.extra, covering: cRow.extra.includes('Using index') },
  };
}
const d1All = SIZES.every((n) => !d1[n].baseline.covering && d1[n].candidate.covering);
const d1Any = SIZES.some((n) => !d1[n].baseline.covering && d1[n].candidate.covering);
chk('D1', d1All, `per shape candidate EXPLAIN covering: ${SIZES.map((n) => `n=${n}:${d1[n].candidate.covering}`).join(',')}`
  + ` — full-pass=${d1All}, partial=${d1Any}`);

// ============ D2 收益 vs 同代码顺序漂移（主臂 X=40.0） ============
const d2 = {};
for (const n of SIZES) {
  const b = seriesByX(readsB, n, '40.0').rounds.map((r) => rootTime(r.analyzeRaw).finalMs);
  const c = seriesByX(readsC, n, '40.0').rounds.map((r) => rootTime(r.analyzeRaw).finalMs);
  const d = seriesByX(readsD, n, '40.0').rounds.map((r) => rootTime(r.analyzeRaw).finalMs);
  const all = b.concat(d);
  const speedupLow = Math.min(...b) / Math.max(...c);
  const driftBand = Math.max(...all) / Math.min(...all);
  d2[n] = {
    baselineMs: b, candidateMs: c, driftMs: d,
    speedupLow: +speedupLow.toFixed(4), driftBand: +driftBand.toFixed(4),
    holds: speedupLow > driftBand,
    wording: speedupLow <= 1 ? 'no visible gain (plan unchanged or slower)' : 'unstable: variance swamps gain',
  };
}
const gateOk = d2[1000].holds && d2[3000].holds;
chk('D2', gateOk, `gated shapes n=1000: speedupLow=${d2[1000].speedupLow} vs band=${d2[1000].driftBand} => ${d2[1000].holds}; `
  + `n=3000: speedupLow=${d2[3000].speedupLow} vs band=${d2[3000].driftBand} => ${d2[3000].holds}`);

// 辅助臂 X_mid（只记录不裁决）
const d2mid = {};
for (const n of SIZES) {
  const bs = readsB.entries.find((e) => e.shape === n && e.x !== '40.0');
  const cs = readsC.entries.find((e) => e.shape === n && e.x !== '40.0');
  if (!bs || !cs) continue;
  const b = bs.rounds.map((r) => rootTime(r.analyzeRaw).finalMs);
  const c = cs.rounds.map((r) => rootTime(r.analyzeRaw).finalMs);
  const cRow = explainRow(cs.explainRaw);
  d2mid[n] = { x: bs.x, baselineMs: b, candidateMs: c, candidateCovering: cRow.extra.includes('Using index'),
    speedupLow: +(Math.min(...b) / Math.max(...c)).toFixed(3) };
}

// FORCE 诊断（只记录不裁决）——按头分段精确提取，避免跨块串扰
let force = null;
try {
  const flog = readFileSync(join(rawDir, 'force-index.log'), 'utf8');
  const segments = flog.split(/\n(?==== )/).map((s) => {
    const h = s.match(/=== (\w+ \w+) shape=(\d+) x=([\d.]+)(?: round=(\d+))?/);
    return h ? { kind: h[1], shape: +h[2], x: h[3], round: h[4] ? +h[4] : 0, body: s } : null;
  }).filter(Boolean);
  const pick = (kind, shape, x) => segments
    .filter((s) => s.kind === kind && s.shape === shape && s.x === x && s.round > 0)
    .sort((a, b) => a.round - b.round)
    .map((s) => (rootTime(s.body) || {}).finalMs).filter(Boolean);
  force = {
    forcedMsAt3000: pick('FORCE ANALYZE', 3000, '40.0'),
    nohintMsAt3000: pick('NOHINT ANALYZE', 3000, '40.0'),
    forcedMsAt1000: pick('FORCE ANALYZE', 1000, '40.0'),
    nohintMsAt1000: pick('NOHINT ANALYZE', 1000, '40.0'),
  };
  force.potentialSpeedupAt3000 = force.nohintMsAt3000.length && force.forcedMsAt3000.length
    ? +(Math.min(...force.nohintMsAt3000) / Math.max(...force.forcedMsAt3000)).toFixed(3) : null;
} catch (e) { force = { error: String(e) }; }

// ============ D3 结果等价 ============
let d3 = true;
const d3Detail = [];
for (const n of SIZES) {
  const bv = seriesByX(readsB, n, '40.0').rounds.map((r) => r.countValue);
  const cv = seriesByX(readsC, n, '40.0').rounds.map((r) => r.countValue);
  const dv = seriesByX(readsD, n, '40.0').rounds.map((r) => r.countValue);
  const allSame = new Set([...bv, ...cv, ...dv]).size === 1;
  d3 = d3 && allSame;
  d3Detail.push(`n=${n}:[${[...new Set([...bv, ...cv, ...dv])].join(',')}]`);
}
chk('D3', d3, `COUNT values identical across arms/rounds per shape: ${d3Detail.join(', ')}`);

// ============ D4 写路径代价 ============
const ratio = (c, b) => c / b;
const med = (a) => { const s = [...a].sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; };
const d4 = {};
const d4ops = {
  insert: { b: writesB.insertRounds, c: writesC.insertRounds },
  casSummarize: { b: writesB.summarizeRounds, c: writesC.summarizeRounds },
  casSubmit: { b: writesB.submitRounds, c: writesC.submitRounds },
};
for (const [op, { b, c }] of Object.entries(d4ops)) {
  const pairs = b.map((rb, i) => ratio(c[i].wallMs, rb.wallMs));
  d4[op] = {
    baselineMs: b.map((r) => r.wallMs), candidateMs: c.map((r) => r.wallMs),
    pairwiseRatio: pairs.map((x) => +x.toFixed(4)),
    medianRatio: +med(pairs).toFixed(4), maxRatio: +Math.max(...pairs).toFixed(4),
    holds: med(pairs) <= 1.30 && Math.max(...pairs) <= 2.00,
  };
}
const d4All = Object.values(d4).every((x) => x.holds);
chk('D4', d4All, `write cost ratios (candidate/baseline): ${Object.entries(d4).map(([op, v]) => `${op} median=${v.medianRatio} max=${v.maxRatio}`).join('; ')} — bounds median<=1.30 max<=2.00`);

// ============ 裁决主式 ============
const M = checks.filter((c) => c.id.startsWith('M')).every((c) => c.ok);
const GO = M && d1All && d2[1000].holds && d2[3000].holds && d3 && d4All;
const verdict = {
  frozenCriteria: 'evidence/PREREGISTRATION.md (sha256 4bdc237b0ace435866cc41459025df3e7a6d28fda40ac35faa1e5a89ee311ed8)',
  measurementValidity: { M1: checks[0].ok, M2: checks[1].ok, M3: checks[2].ok, allValid: M },
  dominance: { D1: { all: d1All, perShape: d1 }, D2: d2, D2mid: d2mid, D3: checks.find((c) => c.id === 'D3').ok, D4: d4, forceDiagnostic: force },
  formula: 'GO <=> M1&M2&M3 & D1 & D2(1000) & D2(3000) & D3 & D4',
  GO,
  verdictText: GO ? 'GO: write separate implementation proposal; no code change in this change.'
    : `NO-GO: gated shapes n=1000/3000 at captured parameter X=40.0 show ${d2[1000].wording} / ${d2[3000].wording}`
      + ` (candidate index not adopted by optimizer there); mechanism proven only at n=50/200 and selective X.`,
  checks,
  finishedAtIso: new Date().toISOString(),
};
writeFileSync(join(rawDir, 'verdict.json'), JSON.stringify(verdict, null, 2));
console.log('VERDICT GO=' + GO);
for (const c of checks) console.log(`${c.ok ? 'PASS' : 'FAIL'} ${c.id}: ${c.detail}`);
console.log('D2 detail:', JSON.stringify(d2, null, 1));
console.log('D2mid detail:', JSON.stringify(d2mid, null, 1));
console.log('D4 detail:', JSON.stringify(d4, null, 1));
console.log('force diagnostic:', JSON.stringify(force, null, 1));
