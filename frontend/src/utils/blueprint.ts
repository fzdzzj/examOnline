import type { DrawRule } from './drawRules';

export const BLUEPRINT_DIFFICULTIES = [1, 2, 3] as const;
export type BlueprintDifficulty = (typeof BLUEPRINT_DIFFICULTIES)[number];

export const BLUEPRINT_DIFFICULTY_LABELS: Record<BlueprintDifficulty, string> = {
  1: '简单 (易)',
  2: '中等 (中)',
  3: '困难 (难)',
};

/**
 * 细目表矩阵存储结构：
 * 外层 key 为 tagId，内层 key 为 difficulty（1/2/3），value 为配置的抽题数量（空为 undefined 或 0）
 */
export type BlueprintMatrixState = Record<number, Record<number, number | undefined>>;

/**
 * 矩阵蓝图 → 契约抽题规则编译
 * - 仅编译选中标签（selectedTagIds）在矩阵中的数据；
 * - 规则顺序确定性：按勾选标签顺序外层遍历、难度 1→2→3 升序内层遍历；
 * - 每个非空且 count > 0 的单元格编译为 Rule{tagIds: [tagId], difficulty: 列值, count: 格值}；
 * - type 字段不设置（题型维度不进矩阵，严格对齐规格书知识点×难度口径）。
 */
export function compileBlueprintToRules(
  selectedTagIds: readonly number[],
  matrix: BlueprintMatrixState
): DrawRule[] {
  const rules: DrawRule[] = [];
  for (const tagId of selectedTagIds) {
    const row = matrix[tagId];
    if (!row) continue;
    for (const diff of BLUEPRINT_DIFFICULTIES) {
      const count = row[diff];
      if (count !== undefined && count !== null && Number.isInteger(count) && count > 0) {
        rules.push({
          tagIds: [tagId],
          difficulty: diff,
          count,
        });
      }
    }
  }
  return rules;
}

/**
 * 蓝图录入校验：
 * 1. 至少勾选一个知识点标签；
 * 2. 检查是否有非法数值（非整数或负数）；
 * 3. 至少配置一个有效正整数题量单元格（全部为空或 0 时拦截）。
 */
export function validateBlueprint(
  selectedTagIds: readonly number[],
  matrix: BlueprintMatrixState
): string[] {
  const errors: string[] = [];
  if (selectedTagIds.length === 0) {
    errors.push('请至少选择一个知识点标签');
    return errors;
  }

  let totalValidCount = 0;
  let hasInvalid = false;

  for (const tagId of selectedTagIds) {
    const row = matrix[tagId];
    if (!row) continue;
    for (const diff of BLUEPRINT_DIFFICULTIES) {
      const count = row[diff];
      if (count === undefined || count === null || count === 0) {
        continue;
      }
      if (!Number.isInteger(count) || count < 0) {
        hasInvalid = true;
      } else {
        totalValidCount += count;
      }
    }
  }

  if (hasInvalid) {
    errors.push('题量须为不小于 1 的整数');
    return errors;
  }

  if (totalValidCount === 0) {
    errors.push('请在细目表矩阵中至少配置一道抽题数量');
    return errors;
  }

  return errors;
}

/**
 * 覆盖度摘要合计：已选标签行中所有有效正数单元格题数之和
 */
export function totalBlueprintCount(
  selectedTagIds: readonly number[],
  matrix: BlueprintMatrixState
): number {
  let total = 0;
  for (const tagId of selectedTagIds) {
    const row = matrix[tagId];
    if (!row) continue;
    for (const diff of BLUEPRINT_DIFFICULTIES) {
      const count = row[diff];
      if (count !== undefined && count !== null && Number.isInteger(count) && count > 0) {
        total += count;
      }
    }
  }
  return total;
}
