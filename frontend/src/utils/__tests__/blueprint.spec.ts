import { describe, expect, it } from 'vitest';

import {
  compileBlueprintToRules,
  totalBlueprintCount,
  validateBlueprint,
  type BlueprintMatrixState,
} from '@/utils/blueprint';

describe('矩阵蓝图（双向细目表）编译与校验', () => {
  it('多格编译：生成契约规则列表（单标签数组、难度对应、题量对应、type 不设）', () => {
    const selectedTagIds = [10, 20];
    const matrix: BlueprintMatrixState = {
      10: { 1: 3, 2: 5 },
      20: { 3: 2 },
    };

    const rules = compileBlueprintToRules(selectedTagIds, matrix);

    expect(rules).toEqual([
      { tagIds: [10], difficulty: 1, count: 3 },
      { tagIds: [10], difficulty: 2, count: 5 },
      { tagIds: [20], difficulty: 3, count: 2 },
    ]);
  });

  it('空格与 0 跳过：未填、undefined、0 的单元格不生成规则', () => {
    const selectedTagIds = [10];
    const matrix: BlueprintMatrixState = {
      10: { 1: 0, 2: undefined, 3: 4 },
    };

    const rules = compileBlueprintToRules(selectedTagIds, matrix);

    expect(rules).toEqual([{ tagIds: [10], difficulty: 3, count: 4 }]);
  });

  it('规则顺序确定性：严格按勾选标签顺序及难度升序（易1 → 中2 → 难3）生成', () => {
    const selectedTagIds = [30, 10];
    const matrix: BlueprintMatrixState = {
      10: { 3: 1, 1: 2 },
      30: { 2: 4, 1: 3 },
    };

    const rules = compileBlueprintToRules(selectedTagIds, matrix);

    expect(rules).toEqual([
      { tagIds: [30], difficulty: 1, count: 3 },
      { tagIds: [30], difficulty: 2, count: 4 },
      { tagIds: [10], difficulty: 1, count: 2 },
      { tagIds: [10], difficulty: 3, count: 1 },
    ]);
  });

  it('覆盖度题数合计：准确累加所有非空正数单元格', () => {
    const selectedTagIds = [10, 20];
    const matrix: BlueprintMatrixState = {
      10: { 1: 3, 2: 5, 3: 0 },
      20: { 1: 0, 2: 2, 3: 4 },
    };

    expect(totalBlueprintCount(selectedTagIds, matrix)).toBe(14);
  });

  it('未选标签在矩阵中的残留数据不参与编译与合计', () => {
    const selectedTagIds = [10]; // 20 未选中
    const matrix: BlueprintMatrixState = {
      10: { 1: 2 },
      20: { 1: 99, 2: 99 },
    };

    expect(totalBlueprintCount(selectedTagIds, matrix)).toBe(2);
    expect(compileBlueprintToRules(selectedTagIds, matrix)).toEqual([
      { tagIds: [10], difficulty: 1, count: 2 },
    ]);
  });

  it('录入校验：未选标签时拦截', () => {
    const errors = validateBlueprint([], {});
    expect(errors).toEqual(['请至少选择一个知识点标签']);
  });

  it('录入校验：所有单元格为空或 0 时拦截', () => {
    const errors1 = validateBlueprint([10], {});
    expect(errors1).toEqual(['请在细目表矩阵中至少配置一道抽题数量']);

    const errors2 = validateBlueprint([10, 20], {
      10: { 1: 0, 2: 0 },
      20: { 3: 0 },
    });
    expect(errors2).toEqual(['请在细目表矩阵中至少配置一道抽题数量']);
  });

  it('录入校验：题量为负数或非整数时拦截', () => {
    const errorsNegative = validateBlueprint([10], {
      10: { 1: -2 },
    });
    expect(errorsNegative).toEqual(['题量须为不小于 1 的整数']);

    const errorsDecimal = validateBlueprint([10], {
      10: { 2: 2.5 },
    });
    expect(errorsDecimal).toEqual(['题量须为不小于 1 的整数']);
  });

  it('录入校验：合法配置返回空错误数组', () => {
    const errors = validateBlueprint([10], {
      10: { 1: 3, 2: 0 },
    });
    expect(errors).toEqual([]);
  });
});
