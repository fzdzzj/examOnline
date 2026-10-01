/**
 * 打分输入校验（提交前拦截，后端同样有校验，这里是即时反馈而非替代）。
 */
import { describe, expect, it } from 'vitest';

import { validateSubjectiveScore } from '../gradingValidation';

describe('主观题打分校验', () => {
  it('正常分数（含 0 分与满分、一位小数）通过', () => {
    expect(validateSubjectiveScore(8, 10).ok).toBe(true);
    expect(validateSubjectiveScore(8, 10).message).toBeNull();
    expect(validateSubjectiveScore('0', 10).ok).toBe(true);
    expect(validateSubjectiveScore('10', 10).ok).toBe(true);
    expect(validateSubjectiveScore('7.5', 10).ok).toBe(true);
  });

  it('空值 / 空串 / 非数字被拦下', () => {
    expect(validateSubjectiveScore(null, 10).ok).toBe(false);
    expect(validateSubjectiveScore(undefined, 10).ok).toBe(false);
    expect(validateSubjectiveScore('   ', 10).ok).toBe(false);
    expect(validateSubjectiveScore('abc', 10).ok).toBe(false);
    expect(validateSubjectiveScore('abc', 10).message).toContain('数字');
  });

  it('负数被拦下', () => {
    expect(validateSubjectiveScore('-1', 10).ok).toBe(false);
    expect(validateSubjectiveScore(-0.5, 10).message).toContain('不能为负');
  });

  it('超过本题满分被拦下，文案带满分值', () => {
    const result = validateSubjectiveScore('11', 10);
    expect(result.ok).toBe(false);
    expect(result.message).toContain('10');
  });

  it('超过一位小数被拦下（后端 @Digits(integer=5, fraction=1)）', () => {
    expect(validateSubjectiveScore('7.55', 10).ok).toBe(false);
    expect(validateSubjectiveScore('7.55', 10).message).toContain('1 位小数');
  });
});
