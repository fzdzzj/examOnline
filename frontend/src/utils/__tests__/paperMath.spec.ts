import { describe, expect, it } from 'vitest';

import { effectiveScore, moveItem, scoreDistribution, sumPaperScores } from '@/utils/paperMath';

describe('试卷总分汇总（含分值覆盖场景）', () => {
  it('覆盖分优先生效：score 存在时忽略 defaultScore', () => {
    expect(
      sumPaperScores([
        { score: 3, defaultScore: 2 },
        { score: 5.5, defaultScore: 2 },
      ])
    ).toBe(8.5);
  });

  it('未覆盖时回退题目默认分', () => {
    expect(
      sumPaperScores([
        { score: null, defaultScore: 2.5 },
        { score: undefined, defaultScore: 1.5 },
      ])
    ).toBe(4);
  });

  it('空试卷合计为 0', () => {
    expect(sumPaperScores([])).toBe(0);
  });

  it('1 位小数精度：0.7 + 0.8 = 1.5，不出现浮点漂移', () => {
    expect(sumPaperScores([{ score: 0.7 }, { score: 0.8 }])).toBe(1.5);
    expect(sumPaperScores([{ score: 0.1 }, { score: 0.2 }])).toBe(0.3);
  });

  it('单题生效分值：覆盖分缺省且无默认分时按 0 计', () => {
    expect(effectiveScore({ score: null, defaultScore: null })).toBe(0);
    expect(effectiveScore({ score: 2, defaultScore: 1 })).toBe(2);
    expect(effectiveScore({})).toBe(0);
  });
});

describe('题号排序 moveItem', () => {
  const list = [1, 2, 3, 4];

  it('上移（delta=-1）：与相邻位交换', () => {
    expect(moveItem(list, 2, -1)).toEqual([1, 3, 2, 4]);
  });

  it('下移（delta=1）：与相邻位交换', () => {
    expect(moveItem(list, 1, 1)).toEqual([1, 3, 2, 4]);
  });

  it('首项上移 / 末项下移越界：返回同序新数组', () => {
    expect(moveItem(list, 0, -1)).toEqual([1, 2, 3, 4]);
    expect(moveItem(list, 3, 1)).toEqual([1, 2, 3, 4]);
  });

  it('不改入参、delta=0 返回同序副本', () => {
    const source = ['a', 'b'];
    const result = moveItem(source, 0, 0);
    expect(result).toEqual(['a', 'b']);
    expect(result).not.toBe(source);
    expect(source).toEqual(['a', 'b']);
  });
});

describe('分值分布聚合', () => {
  it('按题型聚合数量与小计', () => {
    const rows = scoreDistribution([
      { score: 2, questionType: 1 },
      { score: 3, questionType: 1 },
      { score: 5, questionType: 4 },
    ]);
    expect(rows).toEqual([
      { type: 1, typeName: '单选', count: 2, subtotal: 5 },
      { type: 4, typeName: '简答', count: 1, subtotal: 5 },
    ]);
  });

  it('已删除题目（questionType 为空）归入未知题型且仍计入分值', () => {
    const rows = scoreDistribution([
      { score: 2, questionType: null },
      { score: 1.5, questionType: 3 },
    ]);
    expect(rows).toContainEqual({ type: -1, typeName: '未知题型', count: 1, subtotal: 2 });
    expect(rows).toContainEqual({ type: 3, typeName: '判断', count: 1, subtotal: 1.5 });
  });
});
