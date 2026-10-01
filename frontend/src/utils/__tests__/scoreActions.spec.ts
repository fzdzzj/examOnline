/**
 * 成绩动作可用性映射：与后端 ScoreService 的状态断言逐条对齐，
 * 前端只按后端状态 + 角色渲染入口，后端未允许的动作绝不出现。
 */
import { describe, expect, it } from 'vitest';

import { EXAM_STATUS } from '@/constants/examStatus';
import { resolveScoreActions } from '../scoreActions';

describe('成绩动作可用性（后端状态权威）', () => {
  it('未开始 / 进行中：任何成绩动作都不可用', () => {
    for (const status of [EXAM_STATUS.NOT_STARTED, EXAM_STATUS.IN_PROGRESS]) {
      const actions = resolveScoreActions(status, true);
      expect(actions).toEqual({
        canGrade: false,
        canSummarize: false,
        canPreview: false,
        canPublish: false,
        canRevoke: false,
        canExport: false,
      });
    }
  });

  it('已结束：可批改 + 可汇总，不可预览/发布（须先汇总）/撤回/导出', () => {
    const actions = resolveScoreActions(EXAM_STATUS.ENDED, true);
    expect(actions.canGrade).toBe(true);
    expect(actions.canSummarize).toBe(true);
    expect(actions.canPreview).toBe(false);
    expect(actions.canPublish).toBe(false);
    expect(actions.canRevoke).toBe(false);
    expect(actions.canExport).toBe(false);
  });

  it('已批改：可汇总 + 可预览 + 可发布 + 可导出，不可撤回', () => {
    const actions = resolveScoreActions(EXAM_STATUS.GRADED, true);
    expect(actions.canSummarize).toBe(true);
    expect(actions.canPreview).toBe(true);
    expect(actions.canPublish).toBe(true);
    expect(actions.canExport).toBe(true);
    expect(actions.canRevoke).toBe(false);
  });

  it('已发布：不可再汇总（须先撤回）、不可再发布；管理员可撤回；仍可看预览与导出', () => {
    const actions = resolveScoreActions(EXAM_STATUS.PUBLISHED, true);
    expect(actions.canSummarize).toBe(false);
    expect(actions.canPublish).toBe(false);
    expect(actions.canRevoke).toBe(true);
    expect(actions.canPreview).toBe(true);
    expect(actions.canExport).toBe(true);
  });

  it('撤回是高权限动作：非管理员绝不给入口（后端 @RequireRole(ADMIN)）', () => {
    expect(resolveScoreActions(EXAM_STATUS.PUBLISHED, false).canRevoke).toBe(false);
    // 其余动作不受角色影响（exam:manage 权限点即可）
    expect(resolveScoreActions(EXAM_STATUS.PUBLISHED, false).canExport).toBe(true);
  });

  it('状态未知（加载中 / 异常）时一律不给动作，fail-closed', () => {
    for (const status of [undefined, -1, 99]) {
      const actions = resolveScoreActions(status, true);
      expect(Object.values(actions).every((v) => v === false)).toBe(true);
    }
  });
});
