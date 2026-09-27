// 归因裁决算子（机械实现 evidence/PREREGISTRATION.md §3/§4，不做任何人工挑选）
// 用法：node analyze-rounds.cjs [label1 label2 ...]   默认 run1 run2 run3
// 输出：stdout 人读表 + rounds-analysis.json 机器可读结论
'use strict';
const fs = require('fs');
const path = require('path');

const dir = __dirname;
const labels = process.argv.slice(2);
const runs = (labels.length ? labels : ['run1', 'run2', 'run3']).map((label) => {
  const file = path.join(dir, `makeup-eligible-attribution-${label}.json`);
  return { label, data: JSON.parse(fs.readFileSync(file, 'utf8')) };
});

const PRIMARY = ['s200-p10-a2k', 's1000-p10-a2k', 's1000-p10-a20k', 's3000-p10-a2k'];
const SENSITIVE = ['s1000-p50-a2k', 's1000-p90-a2k'];
const mean = (xs) => xs.reduce((a, b) => a + b, 0) / xs.length;
const round3 = (x) => Math.round(x * 1000) / 1000;
const median = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  const m = Math.floor(s.length / 2);
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
};

// ---------- G1/G2/G4/G5/G6：有效性闸门（本脚本复核，不依赖工具自报） ----------
const validity = { runs: [], pass: true, checks: [] };
const check = (name, ok, detail) => {
  validity.checks.push({ name, ok, detail });
  if (!ok) validity.pass = false;
};
for (const { label, data } of runs) {
  const comboLabels = data.combos.map((c) => c.combo);
  const roundsPerCombo = data.combos.map((c) => c.rounds.length);
  const semanticsOk = data.semantics.length === 12 && data.semantics.every((s) => s.match === true);
  const oracleOk = data.comboOracle.every((o) => o.oracleMatch === true);
  const rounds = data.combos.flatMap((c) => c.rounds.map((r) => ({ combo: c.combo, ...r })));
  const equivOk = rounds.every((r) => r.equivalence.allEqual === true);
  const armShapeOk = rounds.every((r) => r.armSqlCheck.pushdownOnlyAddsPredicate === true
    && r.armSqlCheck.projectionOnlyNarrowsColumns === true);
  const captureOk = rounds.every((r) => ['OLD', 'PUSHDOWN', 'PROJECTION']
    .every((m) => r.instrumented[m].sql.some((s) => String(s.id).includes('GradingSubmissionMapper')
      && String(s.sql).includes('exam_submissions'))));
  const concOk = rounds.every((r) => r.conc8.OLD && r.conc8.PUSHDOWN && r.conc8.OLD.totalCalls === 64);
  validity.runs.push({
    label, revision: data.revision, jdk: data.jdk, maxHeapMB: data.maxHeapMB, processors: data.processors,
    combos: comboLabels, roundsPerCombo, dataRule: data.dataRule,
    runtimeFrequencyUnknown: data.runtimeFrequencyUnknown,
    semanticsOk, oracleOk, equivOk, armShapeOk, captureOk, concOk
  });
  check(`${label}: 12 条语义用例全绿`, semanticsOk, `cases=${data.semantics.length}`);
  check(`${label}: 6 个 combo 夹具 oracle 全绿`, oracleOk, `oracles=${data.comboOracle.length}`);
  check(`${label}: 全部轮次结果等价（G3）`, equivOk, `rounds=${rounds.length}`);
  check(`${label}: 受控臂 SQL 形状（G4）`, armShapeOk, `rounds=${rounds.length}`);
  check(`${label}: SQL 捕获生效（G5 前置）`, captureOk, `rounds=${rounds.length}`);
  check(`${label}: 并发臂完整（8×8=64 次/臂）`, concOk, '');
  check(`${label}: 形状/轮数完整（6 combo × 4 轮）`,
    comboLabels.length === 6 && roundsPerCombo.every((n) => n === 4), `roundsPerCombo=${roundsPerCombo}`);
}

// ---------- §3 算子：逐轮 prodRef / armRatio / 噪声带 / 顺序漂移 ----------
const perCombo = {};
for (const combo of [...PRIMARY, ...SENSITIVE]) {
  perCombo[combo] = { primary: PRIMARY.includes(combo), samples: [] };
}
for (const { label, data } of runs) {
  for (const combo of data.combos) {
    const bucket = perCombo[combo.combo];
    if (!bucket) continue;
    for (const r of combo.rounds) {
      const prodFirst = r.prodFirst.meanMillis;
      const prodLast = r.prodLast.meanMillis;
      const prodRef = (prodFirst + prodLast) / 2;
      const s = {
        run: label, round: r.round, armOrder: r.armOrder,
        prodFirst, prodLast, prodRef: round3(prodRef),
        orderDrift: r.deltas.prodLastVsFirstRatio,
        prodFirstVsLastAbsMillis: round3(Math.abs(prodLast - prodFirst)),
        OLD: r.arms.OLD.meanMillis, PUSHDOWN: r.arms.PUSHDOWN.meanMillis,
        PROJECTION: r.arms.PROJECTION.meanMillis,
        ratioOLD: round3(prodRef / r.arms.OLD.meanMillis),
        ratioPUSHDOWN: round3(prodRef / r.arms.PUSHDOWN.meanMillis),
        ratioPROJECTION: round3(prodRef / r.arms.PROJECTION.meanMillis),
        concOLD: r.conc8.OLD.throughputRps, concPUSHDOWN: r.conc8.PUSHDOWN.throughputRps,
        eligibleSize: r.equivalence.eligibleSize,
        gradingRowsEliminatedByPushdown: r.equivalence.gradingRowsEliminatedByPushdown,
        gradingCharsEliminatedByPushdown: r.equivalence.gradingCharsEliminatedByPushdown,
        gradingRowsOutOLD: r.instrumented.OLD.gradingRowsOut,
        gradingAnswersCharsTotalOLD: r.instrumented.OLD.gradingAnswersCharsTotal,
        gradingAnswersCharsTotalPUSHDOWN: r.instrumented.PUSHDOWN.gradingAnswersCharsTotal,
        gradingAnswersCharsTotalPROJECTION: r.instrumented.PROJECTION.gradingAnswersCharsTotal,
        // 同窗（同一探针内）相位占比，合法：不相减跨窗口量
        phaseShareSQL: round3(r.instrumented.OLD.phases.reduce((a, p) => a + p.millis, 0)),
        phases: r.instrumented.OLD.phases.map((p) => ({ phase: p.phase, millis: p.millis, allocatedMB: p.allocatedMB })),
        probeWallMillis: { OLD: r.instrumented.OLD.wallMillis, PUSHDOWN: r.instrumented.PUSHDOWN.wallMillis, PROJECTION: r.instrumented.PROJECTION.wallMillis },
        probeAllocatedMB: { OLD: r.instrumented.OLD.allocatedMB, PUSHDOWN: r.instrumented.PUSHDOWN.allocatedMB, PROJECTION: r.instrumented.PROJECTION.allocatedMB },
        absenceRows: r.instrumented.OLD.absenceRows, nameRows: r.instrumented.OLD.nameRows
      };
      bucket.samples.push(s);
    }
  }
}

const verdicts = {};
for (const combo of Object.keys(perCombo)) {
  const b = perCombo[combo];
  const s = b.samples;
  const oldRatios = s.map((x) => x.ratioOLD);
  const pushRatios = s.map((x) => x.ratioPUSHDOWN);
  const projRatios = s.map((x) => x.ratioPROJECTION);
  const drift = s.map((x) => x.orderDrift);
  // D2：每一轮下推比值都高于 OLD 噪声带上界 + 顺序漂移上界
  const noiseMax = Math.max(...oldRatios);
  const driftMax = Math.max(...drift);
  const bar = Math.max(noiseMax, driftMax);
  const pushMin = Math.min(...pushRatios);
  // D1a：同相位组占比最大（gradingSelect + Java 过滤 之和 > 其余每个单相位）
  const d1a = s.every((x) => {
    const get = (n) => x.phases.find((p) => p.phase === n).millis;
    const group = get('gradingSelect') + get('absencePutAndJavaFilter');
    return group > get('ownerLoad') && group > get('absenceLoad') && group > get('nameLoad');
  });
  // D1b：可消除体积大于保留体积（结构量，非时间推断）
  const d1b = s.every((x) => x.gradingCharsEliminatedByPushdown > x.gradingAnswersCharsTotalOLD - x.gradingCharsEliminatedByPushdown);
  const d5Wins = s.filter((x) => x.ratioPROJECTION > x.ratioPUSHDOWN).length;
  verdicts[combo] = {
    primary: b.primary,
    samples: s.length,
    noiseBandOLD: [round3(Math.min(...oldRatios)), round3(noiseMax)],
    orderDriftBand: [round3(Math.min(...drift)), round3(driftMax)],
    pushdownRatio: { min: round3(pushMin), median: round3(median(pushRatios)), max: round3(Math.max(...pushRatios)) },
    projectionRatio: { min: round3(Math.min(...projRatios)), median: round3(median(projRatios)), max: round3(Math.max(...projRatios)) },
    oldRatio: { min: round3(Math.min(...oldRatios)), median: round3(median(oldRatios)), max: round3(noiseMax) },
    decidabilityBar: round3(bar),
    D1a_groupShareLargest: d1a,
    D1b_eliminatedGtRetained: d1b,
    D2_pushBenefitAboveNoiseEveryRound: pushMin > bar,
    D5_projectionDominatesRounds: d5Wins,
    D5_projectionDominates: d5Wins > s.length / 2,
    medianAbsMillis: {
      OLD: round3(median(s.map((x) => x.OLD))),
      PUSHDOWN: round3(median(s.map((x) => x.PUSHDOWN))),
      PROJECTION: round3(median(s.map((x) => x.PROJECTION))),
      prodRef: round3(median(s.map((x) => x.prodRef)))
    },
    structural: {
      eligibleSize: s[0].eligibleSize,
      gradingRowsOutOLD: s[0].gradingRowsOutOLD,
      gradingRowsEliminatedByPushdown: s[0].gradingRowsEliminatedByPushdown,
      gradingCharsEliminatedByPushdown: s[0].gradingCharsEliminatedByPushdown,
      gradingAnswersCharsTotalOLD: s[0].gradingAnswersCharsTotalOLD,
      gradingAnswersCharsTotalPUSHDOWN: s[0].gradingAnswersCharsTotalPUSHDOWN,
      gradingAnswersCharsTotalPROJECTION: s[0].gradingAnswersCharsTotalPROJECTION,
      absenceRows: s[0].absenceRows, nameRows: s[0].nameRows
    },
    phaseMillisMedian: ['ownerLoad', 'absenceLoad', 'gradingSelect', 'absencePutAndJavaFilter', 'nameLoad']
      .reduce((acc, n) => {
        acc[n] = round3(median(s.map((x) => x.phases.find((p) => p.phase === n).millis)));
        return acc;
      }, {}),
    concThroughputMedian: {
      OLD: round3(median(s.map((x) => x.concOLD))), PUSHDOWN: round3(median(s.map((x) => x.concPUSHDOWN))),
      ratio: round3(median(s.map((x) => x.concPUSHDOWN)) / median(s.map((x) => x.concOLD)))
    }
  };
}

const primaryVerdicts = PRIMARY.map((c) => verdicts[c]);
const D1 = primaryVerdicts.every((v) => v.D1a_groupShareLargest && v.D1b_eliminatedGtRetained);
const D2 = primaryVerdicts.every((v) => v.D2_pushBenefitAboveNoiseEveryRound);
const D3 = validity.pass;
const D4 = D2 && PRIMARY.includes('s200-p10-a2k') && PRIMARY.includes('s1000-p10-a20k');
const D5 = primaryVerdicts.some((v) => v.D5_projectionDominates);
const D6 = validity.pass;
const decision = {
  validityPass: validity.pass,
  D1_highestProportionControllableFactor: D1,
  D2_benefitAboveNoiseEveryRound: D2,
  D3_equivalence: D3,
  D4_notOnlyExtremeShapes: D4,
  D5_projectionOrOtherDominates: D5,
  D6_reliable: D6,
  primaryFamilyGO: D1 && D2 && D3 && D4 && !D5 && D6,
  sensitiveFamilyNote: {
    s1000_p50: { D1: verdicts['s1000-p50-a2k'].D1a_groupShareLargest && verdicts['s1000-p50-a2k'].D1b_eliminatedGtRetained, D2: verdicts['s1000-p50-a2k'].D2_pushBenefitAboveNoiseEveryRound, pushdownRatio: verdicts['s1000-p50-a2k'].pushdownRatio },
    s1000_p90: { D1: verdicts['s1000-p90-a2k'].D1a_groupShareLargest && verdicts['s1000-p90-a2k'].D1b_eliminatedGtRetained, D2: verdicts['s1000-p90-a2k'].D2_pushBenefitAboveNoiseEveryRound, pushdownRatio: verdicts['s1000-p90-a2k'].pushdownRatio }
  }
};

const analysis = {
  generatedFrom: runs.map((r) => r.label),
  revisions: runs.map((r) => r.data.revision),
  preregistrationRule: 'evidence/PREREGISTRATION.md（写定于正式测量前，sha256 记录于 evidence-sha256.txt）',
  validity, verdicts, decision
};
fs.writeFileSync(path.join(dir, 'rounds-analysis.json'), JSON.stringify(analysis, null, 2));

// ---------- 人读输出 ----------
console.log('=== 有效性闸门 ===');
for (const c of validity.checks) console.log(` ${c.ok ? 'OK  ' : 'FAIL'} ${c.name} ${c.detail}`.trimEnd());
console.log(`\n=== 逐 combo（${PRIMARY.length + SENSITIVE.length} 个 combo）===`);
for (const combo of [...PRIMARY, ...SENSITIVE]) {
  const v = verdicts[combo];
  console.log(`\n[${v.primary ? '主族' : '敏感族'}] ${combo}  samples=${v.samples} eligible=${v.structural.eligibleSize}`);
  console.log(`  medianMillis prodRef=${v.medianAbsMillis.prodRef} OLD=${v.medianAbsMillis.OLD} PUSH=${v.medianAbsMillis.PUSHDOWN} PROJ=${v.medianAbsMillis.PROJECTION}`);
  console.log(`  ratio OLD[${v.oldRatio.min}..${v.oldRatio.max}] PUSH[${v.pushdownRatio.min}..${v.pushdownRatio.max}] med=${v.pushdownRatio.median} PROJ[${v.projectionRatio.min}..${v.projectionRatio.max}] med=${v.projectionRatio.median}`);
  console.log(`  orderDrift[${v.orderDriftBand[0]}..${v.orderDriftBand[1]}] decidabilityBar=${v.decidabilityBar} D1a=${v.D1a_groupShareLargest} D1b=${v.D1b_eliminatedGtRetained} D2=${v.D2_pushBenefitAboveNoiseEveryRound} D5projWins=${v.D5_projectionDominatesRounds}/${v.samples}`);
  console.log(`  phaseMillis(OLD median) ${JSON.stringify(v.phaseMillisMedian)}`);
  console.log(`  structural rowsOut=${v.structural.gradingRowsOutOLD} rowsEliminated=${v.structural.gradingRowsEliminatedByPushdown} charsElim=${v.structural.gradingCharsEliminatedByPushdown} charsOLD=${v.structural.gradingAnswersCharsTotalOLD} charsPUSH=${v.structural.gradingAnswersCharsTotalPUSHDOWN} charsPROJ=${v.structural.gradingAnswersCharsTotalPROJECTION} absentRows=${v.structural.absenceRows} nameRows=${v.structural.nameRows}`);
  console.log(`  conc8 rps OLD=${v.concThroughputMedian.OLD} PUSH=${v.concThroughputMedian.PUSHDOWN} ratio=${v.concThroughputMedian.ratio}`);
}
console.log('\n=== 裁决 ===');
console.log(JSON.stringify(decision, null, 2));
console.log('\nwrote rounds-analysis.json');
