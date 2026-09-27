// 口径敏感性检查：预登记文本有歧义的两处（D1a 逐轮 vs 逐 combo 聚合；D2 的「稳定超过」用极值统计还是中位统计）
// 把四种读法的结论都算出来，用于证明 NO-GO 不依赖事后挑选的算子。
// 用法：node sensitivity-check.cjs [label1 label2 ...]  默认 run1 run2 run3
'use strict';
const fs = require('fs');
const path = require('path');
const dir = __dirname;
const labels = process.argv.slice(2);
const runs = (labels.length ? labels : ['run1', 'run2', 'run3'])
  .map((l) => JSON.parse(fs.readFileSync(path.join(dir, `makeup-eligible-attribution-${l}.json`), 'utf8')));
const PRIMARY = ['s200-p10-a2k', 's1000-p10-a2k', 's1000-p10-a20k', 's3000-p10-a2k'];
const round3 = (x) => Math.round(x * 1000) / 1000;
const median = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  const m = Math.floor(s.length / 2);
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
};

const samples = {};
for (const combo of PRIMARY) samples[combo] = [];
for (const data of runs) {
  for (const c of data.combos) {
    if (!samples[c.combo]) continue;
    for (const r of c.rounds) {
      const ref = (r.prodFirst.meanMillis + r.prodLast.meanMillis) / 2;
      samples[c.combo].push({
        ref, ratioOLD: ref / r.arms.OLD.meanMillis, ratioPUSH: ref / r.arms.PUSHDOWN.meanMillis,
        ratioPROJ: ref / r.arms.PROJECTION.meanMillis, drift: r.deltas.prodLastVsFirstRatio,
        group: r.instrumented.OLD.phases.find((p) => p.phase === 'gradingSelect').millis
          + r.instrumented.OLD.phases.find((p) => p.phase === 'absencePutAndJavaFilter').millis,
        phases: r.instrumented.OLD.phases.reduce((a, p) => (a[p.phase] = p.millis, a), {})
      });
    }
  }
}

const d1aPerSample = {};
const d1aAggregate = {};
const bars = {};
for (const combo of PRIMARY) {
  const s = samples[combo];
  d1aPerSample[combo] = s.every((x) => x.group > x.phases.ownerLoad && x.group > x.phases.absenceLoad && x.group > x.phases.nameLoad);
  const g = {};
  for (const k of ['ownerLoad', 'absenceLoad', 'gradingSelect', 'absencePutAndJavaFilter', 'nameLoad']) {
    g[k] = median(s.map((x) => x.phases[k]));
  }
  d1aAggregate[combo] = (g.gradingSelect + g.absencePutAndJavaFilter) > g.ownerLoad
    && (g.gradingSelect + g.absencePutAndJavaFilter) > g.absenceLoad
    && (g.gradingSelect + g.absencePutAndJavaFilter) > g.nameLoad;
  bars[combo] = {
    oldBandMax: Math.max(...s.map((x) => x.ratioOLD)),
    driftMax: Math.max(...s.map((x) => x.drift)),
    pushMin: Math.min(...s.map((x) => x.ratioPUSH)),
    pushMedian: median(s.map((x) => x.ratioPUSH)),
    projWins: s.filter((x) => x.ratioPROJ > x.ratioPUSH).length,
    samples: s.length,
    aggregatePhases: g
  };
}

const readings = [
  {
    name: 'A 冻结读法（D1a 逐样本 + D2 极值：min(下推) > max(OLD带, 顺序漂移）)',
    d1: PRIMARY.every((c) => d1aPerSample[c]),
    d2: PRIMARY.every((c) => bars[c].pushMin > Math.max(bars[c].oldBandMax, bars[c].driftMax))
  },
  {
    name: 'B D1a 聚合 + D2 极值（只松开 D1a 的实现口径）',
    d1: PRIMARY.every((c) => d1aAggregate[c]),
    d2: PRIMARY.every((c) => bars[c].pushMin > Math.max(bars[c].oldBandMax, bars[c].driftMax))
  },
  {
    name: 'C D1a 聚合 + D2 只用 OLD 等价臂噪声带（去掉顺序漂移这一项）',
    d1: PRIMARY.every((c) => d1aAggregate[c]),
    d2: PRIMARY.every((c) => bars[c].pushMin > bars[c].oldBandMax)
  },
  {
    name: 'D D1a 聚合 + D2 中位统计（pushMedian > max(OLD带, 顺序漂移）)',
    d1: PRIMARY.every((c) => d1aAggregate[c]),
    d2: PRIMARY.every((c) => bars[c].pushMedian > Math.max(bars[c].oldBandMax, bars[c].driftMax))
  }
];
const d5 = bars[PRIMARY[0]].samples ? PRIMARY.some((c) => bars[c].projWins > bars[c].samples / 2) : false;
for (const r of readings) {
  r.d5_projectionDominates = d5;
  r.verdict = r.d1 && r.d2 && !d5 ? 'GO' : 'NO-GO';
}

const out = { primaryFamily: PRIMARY, perComboBars: bars, d1aPerSample, d1aAggregate, readings };
fs.writeFileSync(path.join(dir, 'sensitivity-analysis.json'), JSON.stringify(out, null, 2));
console.log('combo                        oldBandMax driftMax pushMin pushMedian projWins D1aPerSample D1aAggregate');
for (const c of PRIMARY) {
  const b = bars[c];
  console.log(`${c.padEnd(28)} ${String(round3(b.oldBandMax)).padEnd(10)} ${String(round3(b.driftMax)).padEnd(8)} ${String(round3(b.pushMin)).padEnd(7)} ${String(round3(b.pushMedian)).padEnd(10)} ${String(b.projWins + '/' + b.samples).padEnd(8)} ${String(d1aPerSample[c]).padEnd(12)} ${d1aAggregate[c]}`);
}
console.log('\nreadings:');
for (const r of readings) console.log(` ${r.verdict.padEnd(6)} ${r.name}  D1=${r.d1} D2=${r.d2} D5=${r.d5_projectionDominates}`);
