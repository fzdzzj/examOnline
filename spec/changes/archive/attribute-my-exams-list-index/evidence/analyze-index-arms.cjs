#!/usr/bin/env node
/**
 * analyze-index-arms.cjs - Mechanical adjudication script for attribute-my-exams-list-index.
 * Adheres strictly to spec/changes/attribute-my-exams-list-index/evidence/PREREGISTRATION.md §5 and §6.
 *
 * Usage: node analyze-index-arms.cjs <rounds.json> <adjudication.json>
 */

const { readFileSync, writeFileSync } = require('fs');
const { resolve } = require('path');

function parseExplainAnalyze(text) {
  const lines = (text || '').split(/\\n|\r?\n/).map(l => l.trim()).filter(Boolean);
  let rowsSum = 0;
  let rootActualTimeMs = null;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    // Match actual time=.. and rows=..
    const mTime = /actual time=([0-9.]+)\.\.([0-9.]+)/.exec(line);
    const mRows = /actual time=[0-9.]+\.\.[0-9.]+ rows=([0-9]+)/.exec(line);

    if (mTime && rootActualTimeMs === null) {
      rootActualTimeMs = parseFloat(mTime[2]);
    }
    if (mRows) {
      rowsSum += parseInt(mRows[1], 10);
    }
  }

  return { rowsSum, actualTimeMs: rootActualTimeMs };
}

function parseTraditionalExplain(text) {
  const lines = (text || '').split(/\r?\n/).map(l => l.trim()).filter(Boolean);
  if (lines.length >= 2) {
    const headerParts = lines[0].split('\t');
    const typeIdx = headerParts.indexOf('type');
    const keyIdx = headerParts.indexOf('key');
    const rowsIdx = headerParts.indexOf('rows');

    if (typeIdx !== -1 && keyIdx !== -1 && rowsIdx !== -1) {
      const dataParts = lines[1].split('\t');
      const typeVal = dataParts[typeIdx] || null;
      let keyVal = dataParts[keyIdx] || null;
      if (keyVal === 'NULL' || keyVal === '') keyVal = null;
      const rowsVal = parseInt(dataParts[rowsIdx], 10);
      return {
        type: typeVal,
        key: keyVal,
        rows: isNaN(rowsVal) ? null : rowsVal,
        rawLine: lines[1]
      };
    }
  }

  for (const line of lines) {
    if (line.includes('|') && !line.includes('select_type') && !line.startsWith('+')) {
      const parts = line.split('|').map(p => p.trim());
      if (parts.length >= 11) {
        let typeIdx = 5;
        let keyIdx = 7;
        let rowsIdx = 10;
        if (parts[4] === 'ALL' || parts[4] === 'ref' || parts[4] === 'index' || parts[4] === 'range' || parts[4] === 'const') {
          typeIdx = 4;
          keyIdx = 6;
          rowsIdx = 9;
        }
        return {
          type: parts[typeIdx],
          key: parts[keyIdx] === 'NULL' ? null : parts[keyIdx],
          rows: parseInt(parts[rowsIdx], 10),
          rawLine: line
        };
      }
    }
  }
  return { type: null, key: null, rows: null, rawLine: null };
}

function main() {
  const inPath = resolve(process.argv[2] || 'D:/code/examOnline-measure/my-exams-index/stage3/raw/rounds.json');
  const outPath = resolve(process.argv[3] || 'spec/changes/attribute-my-exams-list-index/evidence/adjudication.json');

  console.log(`Reading measurement data from: ${inPath}`);
  const data = JSON.parse(readFileSync(inPath, 'utf8'));

  const adjudication = {
    generatedAtIso: new Date().toISOString(),
    rev: data.rev,
    verdict: 'NO-GO',
    reasons: [],
    adoption: {
      submissions: null,
      candidates: null
    },
    shapes: {},
    perArm: {}
  };

  const shapes = ['n1', 'n2'];
  let allShapesPassSubmissions = true;
  const submissionsArmCandidates = { A1: true, A2: true };
  let candidatesC1Pass = true;

  for (const s of shapes) {
    const shapeData = data.shapes[s];
    if (!shapeData) {
      console.error(`Missing data for shape ${s}`);
      process.exit(1);
    }

    const sPerArm = {};
    const readRounds = shapeData.readRounds;
    const writeRounds = shapeData.writeRounds;

    // Process read rounds
    for (const r of readRounds) {
      if (!sPerArm[r.arm]) {
        sPerArm[r.arm] = { rounds: [] };
      }
      const expParsed = parseTraditionalExplain(r.explainRaw);
      const anaParsed = parseExplainAnalyze(r.explainAnalyzeRaw);
      sPerArm[r.arm].rounds.push({
        round: r.round,
        type: expParsed.type,
        key: expParsed.key,
        explainRows: expParsed.rows,
        rowsSum: anaParsed.rowsSum,
        actualTimeMs: anaParsed.actualTimeMs
      });
    }

    // Process write rounds
    for (const w of writeRounds) {
      if (sPerArm[w.arm]) {
        const rObj = sPerArm[w.arm].rounds.find(x => x.round === w.round);
        if (rObj) {
          rObj.elapsedUs = w.elapsedUs;
        }
      }
    }

    // Compute noise bands
    // Noise band for T1: max(A0 rounds ∪ A0rep rounds) of actualTimeMs
    const a0Times = sPerArm.A0.rounds.map(x => x.actualTimeMs);
    const a0repTimes = sPerArm.A0rep.rounds.map(x => x.actualTimeMs);
    const bandA0 = Math.max(...a0Times, ...a0repTimes);
    sPerArm.A0.band = bandA0;
    sPerArm.A0rep.band = bandA0;

    // Noise band for T2: max(C0 rounds ∪ C0rep rounds) of actualTimeMs
    const c0Times = sPerArm.C0.rounds.map(x => x.actualTimeMs);
    const c0repTimes = sPerArm.C0rep.rounds.map(x => x.actualTimeMs);
    const bandC0 = Math.max(...c0Times, ...c0repTimes);
    sPerArm.C0.band = bandC0;
    sPerArm.C0rep.band = bandC0;

    // Write noise band: max(A0 ∪ A0rep) of elapsedUs
    const a0Elapsed = sPerArm.A0.rounds.map(x => x.elapsedUs);
    const a0repElapsed = sPerArm.A0rep.rounds.map(x => x.elapsedUs);
    const writeBandA0 = Math.max(...a0Elapsed, ...a0repElapsed);
    const writeThreshold = writeBandA0 * 1.05;

    // Evaluate B1: A0 T1 type=ALL, key=null, min(A0 actualTimeMs) >= 5.0ms
    const a0MinTime = Math.min(...a0Times);
    const a0TypeAll = sPerArm.A0.rounds.every(x => x.type === 'ALL');
    const a0KeyNull = sPerArm.A0.rounds.every(x => x.key === null);
    const b1SubmissionsPass = a0TypeAll && a0KeyNull && a0MinTime >= 5.0;

    // Evaluate B1 symmetric for C0/T2
    const c0MinTime = Math.min(...c0Times);
    const c0TypeAll = sPerArm.C0.rounds.every(x => x.type === 'ALL');
    const c0KeyNull = sPerArm.C0.rounds.every(x => x.key === null);
    const b1CandidatesPass = c0TypeAll && c0KeyNull && c0MinTime >= 5.0;

    // Evaluate B2: scan rows ratio >= 5.0 for all 5 rounds
    // A1 vs A0
    const a1Ratios = [];
    for (let i = 0; i < 5; i++) {
      a1Ratios.push(sPerArm.A0.rounds[i].rowsSum / sPerArm.A1.rounds[i].rowsSum);
    }
    const a1B2Pass = a1Ratios.every(r => r >= 5.0);
    sPerArm.A1.ratios = a1Ratios;

    // A2 vs A0
    const a2Ratios = [];
    for (let i = 0; i < 5; i++) {
      a2Ratios.push(sPerArm.A0.rounds[i].rowsSum / sPerArm.A2.rounds[i].rowsSum);
    }
    const a2B2Pass = a2Ratios.every(r => r >= 5.0);
    sPerArm.A2.ratios = a2Ratios;

    // C1 vs C0
    const c1Ratios = [];
    for (let i = 0; i < 5; i++) {
      c1Ratios.push(sPerArm.C0.rounds[i].rowsSum / sPerArm.C1.rounds[i].rowsSum);
    }
    const c1B2Pass = c1Ratios.every(r => r >= 5.0);
    sPerArm.C1.ratios = c1Ratios;

    // Evaluate B3: each round actualTimeMs <= noise band
    const a1B3Pass = sPerArm.A1.rounds.every(x => x.actualTimeMs <= bandA0);
    const a2B3Pass = sPerArm.A2.rounds.every(x => x.actualTimeMs <= bandA0);
    const c1B3Pass = sPerArm.C1.rounds.every(x => x.actualTimeMs <= bandC0);

    // Evaluate B4: elapsedUs <= writeBandA0 * 1.05
    const a1B4Pass = sPerArm.A1.rounds.every(x => x.elapsedUs <= writeThreshold);
    const a2B4Pass = sPerArm.A2.rounds.every(x => x.elapsedUs <= writeThreshold);

    // Evaluate B5:
    const b51Pass = shapeData.b51 && Object.values(shapeData.b51).every(v => typeof v !== 'object' || v.pass === true);
    const b52Pass = (shapeData.analyzeLogs || []).every(l => l.submissionsStatus === 0 && l.candidatesStatus === 0);
    const b53Pass = [sPerArm.A0, sPerArm.A0rep, sPerArm.A1, sPerArm.A2, sPerArm.C0, sPerArm.C0rep, sPerArm.C1]
      .every(arm => arm.rounds.every(r => r.type !== null && !isNaN(r.rowsSum) && !isNaN(r.actualTimeMs)));
    const b5Pass = b51Pass && b52Pass && b53Pass;

    adjudication.shapes[s] = {
      b1: {
        submissions: { typeAll: a0TypeAll, keyNull: a0KeyNull, fastestRoundMs: a0MinTime, pass: b1SubmissionsPass },
        candidates: { typeAll: c0TypeAll, keyNull: c0KeyNull, fastestRoundMs: c0MinTime, pass: b1CandidatesPass }
      },
      b2: {
        A1: { ratios: a1Ratios, pass: a1B2Pass },
        A2: { ratios: a2Ratios, pass: a2B2Pass },
        C1: { ratios: c1Ratios, pass: c1B2Pass }
      },
      b3: {
        bandA0, bandC0,
        A1: { pass: a1B3Pass, maxMs: Math.max(...sPerArm.A1.rounds.map(x => x.actualTimeMs)) },
        A2: { pass: a2B3Pass, maxMs: Math.max(...sPerArm.A2.rounds.map(x => x.actualTimeMs)) },
        C1: { pass: c1B3Pass, maxMs: Math.max(...sPerArm.C1.rounds.map(x => x.actualTimeMs)) }
      },
      b4: {
        writeBandA0, writeThreshold,
        A1: { pass: a1B4Pass, maxElapsedUs: Math.max(...sPerArm.A1.rounds.map(x => x.elapsedUs)) },
        A2: { pass: a2B4Pass, maxElapsedUs: Math.max(...sPerArm.A2.rounds.map(x => x.elapsedUs)) }
      },
      b5: {
        b51_seedData: b51Pass,
        b52_analyzeTables: b52Pass,
        b53_explainParsable: b53Pass,
        pass: b5Pass
      }
    };

    adjudication.perArm[s] = sPerArm;

    // Check arm eligibility across shapes
    if (!b1SubmissionsPass || !b5Pass) {
      allShapesPassSubmissions = false;
    }
    if (!a1B2Pass || !a1B3Pass || !a1B4Pass) submissionsArmCandidates.A1 = false;
    if (!a2B2Pass || !a2B3Pass || !a2B4Pass) submissionsArmCandidates.A2 = false;

    if (!b1CandidatesPass || !c1B2Pass || !c1B3Pass) candidatesC1Pass = false;
  }

  // Determine adoption & overall verdict
  if (allShapesPassSubmissions) {
    if (submissionsArmCandidates.A1) {
      adjudication.adoption.submissions = 'A1';
      adjudication.verdict = 'GO';
      adjudication.reasons.push('A1 (single-column student_id index) passed B1-B5 across both n1 and n2 shapes');
    } else if (submissionsArmCandidates.A2) {
      adjudication.adoption.submissions = 'A2';
      adjudication.verdict = 'GO';
      adjudication.reasons.push('A2 (composite index) passed B1-B5 across both n1 and n2 shapes (A1 did not pass)');
    } else {
      adjudication.verdict = 'NO-GO';
      adjudication.reasons.push('Neither A1 nor A2 passed B2/B3/B4 across both shapes');
    }
  } else {
    adjudication.verdict = 'NO-GO';
    adjudication.reasons.push('B1 or B5 failed for submissions channel in at least one shape');
  }

  if (candidatesC1Pass) {
    adjudication.adoption.candidates = 'C1';
    adjudication.reasons.push('C1 passed B2/B3 and C0 B1 sub-gate across both shapes (adopted)');
  } else {
    adjudication.adoption.candidates = null;
    adjudication.reasons.push('C1 did not pass sub-gate (C0 fastest round < 5ms or B2/B3 failed; does not veto card)');
  }

  if (adjudication.adoption.candidates === null) {
    const c1B2FailedBothShapes = shapes.every(s => !adjudication.shapes[s].b2.C1.pass);
    if (c1B2FailedBothShapes) {
      const ratio = adjudication.shapes[shapes[0]].b2.C1.ratios[0];
      adjudication.reasons.push(`C1 B2 also failed under the frozen parse rule (ratio ${ratio.toFixed(1)} < 5.0 on every round of both shapes); does not veto card`);
    }
  }

  console.log(`\nADJUDICATION COMPLETE: Verdict = ${adjudication.verdict}`);
  console.log(`Adoption: submissions = ${adjudication.adoption.submissions}, candidates = ${adjudication.adoption.candidates}`);
  console.log(`Reasons: ${JSON.stringify(adjudication.reasons, null, 2)}`);

  writeFileSync(outPath, JSON.stringify(adjudication, null, 2), 'utf8');
  console.log(`Adjudication saved to: ${outPath}`);
  process.exit(0);
}

main();
