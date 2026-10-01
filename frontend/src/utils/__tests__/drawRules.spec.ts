import { describe, expect, it } from 'vitest';

import { drawRulesErrors, toDrawRules } from '@/utils/drawRules';

describe('抽题规则草稿 → 契约请求', () => {
  it('空值字段不下发：后端视为不限条件', () => {
    expect(toDrawRules([{ type: undefined, difficulty: undefined, tagIds: [], count: 5 }])).toEqual(
      [{ count: 5 }]
    );
  });

  it('题型/难度/标签（OR 语义）/数量全量下发', () => {
    expect(toDrawRules([{ type: 2, difficulty: 1, tagIds: [3, 7], count: 10 }])).toEqual([
      { type: 2, difficulty: 1, tagIds: [3, 7], count: 10 },
    ]);
  });

  it('多规则保持顺序（与后端 rules 下标一一对应）', () => {
    const rules = toDrawRules([
      { type: 1, count: 5 },
      { type: 3, tagIds: [9], count: 3 },
    ]);
    expect(rules).toEqual([
      { type: 1, count: 5 },
      { type: 3, tagIds: [9], count: 3 },
    ]);
  });

  it('录入校验：数量须为 ≥1 整数、至少一条规则', () => {
    expect(drawRulesErrors([{ count: 0 }])).toEqual(['第 1 条规则的抽取数量须为不小于 1 的整数']);
    expect(drawRulesErrors([{ count: 1.5 }])).toEqual(['第 1 条规则的抽取数量须为不小于 1 的整数']);
    expect(drawRulesErrors([])).toEqual(['请至少配置一条抽题规则']);
    expect(drawRulesErrors([{ count: 5 }, { count: 2 }])).toEqual([]);
  });
});
