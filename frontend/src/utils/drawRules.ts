/**
 * 抽题规则草稿 → 契约请求的收敛（纯函数，便于 vitest 直测）。
 * UI 侧用 undefined 表示「不限」（对齐 antd Select 清空后的值）；契约侧字段缺省即后端跳过该条件。
 * 随机算法在后端（硬约定 5），这里只做参数形状转换与录入校验。
 */
import type { RandomDrawRequest } from '@/api/axios';

export type DrawRuleDraft = {
  type?: number | undefined;
  difficulty?: number | undefined;
  tagIds?: number[];
  count?: number | undefined;
};

export type DrawRule = RandomDrawRequest['rules'][number];

/** 转换为契约规则：空值字段不下发（后端视为不限），tagIds 空数组不下发。
 * type/difficulty 的 null/undefined 都视为不限制。
 */
export function toDrawRules(drafts: readonly DrawRuleDraft[]): DrawRule[] {
  return drafts.map((draft) => {
    const rule: DrawRule = { count: draft.count as number };
    if (draft.type != null) {
      // null/undefined 都不下发
      rule.type = draft.type;
    }
    if (draft.difficulty != null) {
      rule.difficulty = draft.difficulty;
    }
    if (draft.tagIds && draft.tagIds.length > 0) {
      rule.tagIds = [...draft.tagIds];
    }
    return rule;
  });
}

/** 录入校验：至少一条规则；每条规则数量为 ≥1 的整数。 */
export function drawRulesErrors(drafts: readonly DrawRuleDraft[]): string[] {
  const errors: string[] = [];
  if (drafts.length === 0) {
    errors.push('请至少配置一条抽题规则');
    return errors;
  }
  drafts.forEach((draft, index) => {
    if (draft.count === undefined || !Number.isInteger(draft.count) || draft.count < 1) {
      errors.push(`第 ${index + 1} 条规则的抽取数量须为不小于 1 的整数`);
    }
  });
  return errors;
}
