// Phase-1 capture summarizer (read-only over the capture JSON).
const fs = require('fs');
const p = process.argv[2];
const root = JSON.parse(fs.readFileSync(p, 'utf8'));

console.log('rev=' + root.rev + ' label=' + root.label + ' generated=' + root.generatedAtIso);
const pre = root.preregistration || {};
console.log('prereg.match=' + pre.match + ' recorded=' + String(pre.recordedSha256).slice(0, 12) +
    ' recomputed=' + String(pre.recomputedSha256).slice(0, 12));
console.log('problems=' + JSON.stringify(root.problems));
console.log('');

for (const shape of root.shapes) {
    console.log('=== n=' + shape.n + ' ===');
    for (const site of ['s1', 's2', 's3', 's4', 's5']) {
        const s = shape.sites[site];
        const msId = s.targetMsId;
        const short = msId.split('.').slice(-2).join('.');
        const old = s.arms.OLD, proj = s.arms.PROJ;
        const acc = (arm) => {
            const a = arm.accounting[msId] || {};
            return 'q=' + a.queries + ',r=' + a.rows;
        };
        const g = (arm, k) => (arm.guard || {})[k];
        const oldStmts = (old.targetStatements || []).length;
        const projStmts = (proj.targetStatements || []).length;
        console.log(site + ' ms=' + short + ' status(o/p)=' + old.status + '/' + proj.status +
            ' expQ=' + s.expectedQueries + ' expR=' + s.expectedRows);
        console.log('    OLD ' + acc(old) + ' guard(ans=' + g(old, 'answersNonNullTotal') +
            ',paper=' + g(old, 'paperJsonNonNullTotal') + ',rows=' + g(old, 'rowsTotal') +
            ',dbLong=' + g(old, 'dbLongRows') + ') targetStmts=' + oldStmts);
        console.log('    PROJ ' + acc(proj) + ' guard(ans=' + g(proj, 'answersNonNullTotal') +
            ',paper=' + g(proj, 'paperJsonNonNullTotal') + ',rows=' + g(proj, 'rowsTotal') +
            ',dbLong=' + g(proj, 'dbLongRows') + ') targetStmts=' + projStmts);
        console.log('    oldSelectList=[' + s.oldSelectList + ']');
        console.log('    projSelectList=[' + s.projSelectList + ']');
        console.log('    equivalent=' + s.s1Equivalent +
            (s.s1Diff !== undefined ? ' diff=' + String(s.s1Diff).slice(0, 160) : ''));
    }
}
