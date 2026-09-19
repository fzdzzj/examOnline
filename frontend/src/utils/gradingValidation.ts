/**
 * 主观题打分输入校验（纯函数，vitest 靶子）。
 *
 * 与后端 SubjectiveScoreRequest 的校验逐条对齐（GradingController 入参）：
 * - score 必填、非负、不得超过该题满分（后端 400「批改分数不得超过本题满分 X」）；
 * - 最多 1 位小数（后端 @Digits(integer, fraction = 1)）。
 * 前端先拦一道是体验问题，**后端仍是权威**——绕过前端直调接口依然会被拒绝。
 */

export interface ScoreValidationResult {
  ok: boolean;
  /** 不通过时的可读提示；通过时为 null */
  message: string | null;
}

/** 最多 1 位小数（后端 @Digits 约束）。 */
const ONE_DECIMAL_RE = /^-?\d+(\.\d{1})?$/;

export function validateSubjectiveScore(
  raw: string | number | null | undefined,
  maxScore: number
): ScoreValidationResult {
  if (raw === null || raw === undefined || String(raw).trim() === '') {
    return { ok: false, message: '请填写分数' };
  }
  const text = String(raw).trim();
  if (!ONE_DECIMAL_RE.test(text)) {
    return { ok: false, message: '分数必须是数字，且最多 1 位小数' };
  }
  const score = Number(text);
  if (Number.isNaN(score)) {
    return { ok: false, message: '分数必须是数字' };
  }
  if (score < 0) {
    return { ok: false, message: '分数不能为负数' };
  }
  if (score > maxScore) {
    return { ok: false, message: `分数不得超过本题满分 ${maxScore}` };
  }
  return { ok: true, message: null };
}
