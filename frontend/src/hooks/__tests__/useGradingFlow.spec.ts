/**
 * 批改冲突分支（硬约定 2 的可视化证明，本阶段最重要的用例）。
 *
 * 断言三件事：
 * 1. 后端 409 / 业务码 1012 冲突 → state.conflict 置位且文案含「已被他人批改」；
 * 2. 冲突后**自动拉取最新行**给教师重看（refreshRow 被调用），但**不自动重试提交**
 *    （saveScore 只有 1 次调用——重试会盖掉先提交者的批改）；
 * 3. 冲突**不算成功**（submit 返回 false），界面因此不得显示成功提示。
 */
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/types';
import type { SubjectiveGradeRow } from '@/api/axios';
import { GRADING_CONFLICT_CODE } from '@/constants/postExam';
import {
  createGradingFlow,
  isGradingConflict,
  GRADING_CONFLICT_MESSAGE,
  type GradingFlowDeps,
} from '../useGradingFlow';

const BASE_ROW: SubjectiveGradeRow = {
  id: 11,
  submissionId: 1001,
  questionId: 55,
  studentId: 7,
  studentName: '张三',
  studentAnswer: '作答内容',
  score: undefined,
  version: 0,
  graded: false,
};

/** 后端真实冲突响应：STATE_CONFLICT(1012) + HTTP 409（SubjectiveGradingService.casSaveScore 影响 0 行）。 */
function conflictError(): ApiError {
  return new ApiError(GRADING_CONFLICT_CODE, '批改已被他人更新，请刷新后重试', undefined, 409);
}

type SaveScoreFn = GradingFlowDeps['saveScore'];
type RefreshRowFn = GradingFlowDeps['refreshRow'];

function depsWith(saveScore: SaveScoreFn, refreshRow: RefreshRowFn): GradingFlowDeps {
  return { saveScore, refreshRow };
}

describe('批改提交流转（乐观锁）', () => {
  it('冲突可见：提示「已被他人批改」并拉取最新行给教师重看', async () => {
    const saveScore = vi.fn().mockRejectedValue(conflictError());
    const latest: SubjectiveGradeRow = {
      ...BASE_ROW,
      score: 8,
      comment: '他人已批',
      version: 1,
      graded: true,
    };
    const refreshRow = vi.fn().mockResolvedValue(latest);
    const flow = createGradingFlow(depsWith(saveScore, refreshRow));

    const ok = await flow.submit(BASE_ROW, '9', '我的评语', 10);

    expect(ok).toBe(false);
    expect(flow.state.conflict).not.toBeNull();
    expect(flow.state.conflict?.message).toContain('已被他人批改');
    expect(flow.state.conflict?.message).toBe(GRADING_CONFLICT_MESSAGE);
    // 教师重看的对象：后端最新行（含他人分数与新 version）
    expect(refreshRow).toHaveBeenCalledWith(1001, 55);
    expect(flow.state.conflict?.latestRow?.score).toBe(8);
    expect(flow.state.conflict?.latestRow?.version).toBe(1);
  });

  it('冲突绝不自动重试：saveScore 只调用一次（重试会覆盖他人批改）', async () => {
    const saveScore = vi.fn().mockRejectedValue(conflictError());
    const refreshRow = vi.fn().mockResolvedValue({ ...BASE_ROW, version: 1 });
    const flow = createGradingFlow(depsWith(saveScore, refreshRow));

    await flow.submit(BASE_ROW, '9', '', 10);

    expect(saveScore).toHaveBeenCalledTimes(1);
    // 提交时带上的是教师读到的旧 version，后端据此 CAS
    expect(saveScore.mock.calls[0]?.[0]).toEqual({
      submissionId: 1001,
      questionId: 55,
      score: 9,
      comment: undefined,
      expectedVersion: 0,
    });
  });

  it('冲突不算成功，也不进普通错误通道：界面不会显示「保存成功」', async () => {
    const saveScore = vi.fn().mockRejectedValue(conflictError());
    const refreshRow = vi.fn().mockResolvedValue(BASE_ROW);
    const flow = createGradingFlow(depsWith(saveScore, refreshRow));

    const ok = await flow.submit(BASE_ROW, '5', undefined, 10);

    expect(ok).toBe(false);
    expect(flow.state.error).toBeNull();
    expect(flow.state.savingSubmissionId).toBeNull();
  });

  it('成功路径：返回 true 且无冲突/错误，提交体带 expectedVersion', async () => {
    const saveScore = vi.fn().mockResolvedValue({ ...BASE_ROW, score: 7.5, version: 1 });
    const refreshRow = vi.fn();
    const flow = createGradingFlow(depsWith(saveScore, refreshRow));

    const ok = await flow.submit(BASE_ROW, '7.5', '还不错', 10);

    expect(ok).toBe(true);
    expect(flow.state.conflict).toBeNull();
    expect(flow.state.error).toBeNull();
    expect(refreshRow).not.toHaveBeenCalled();
    expect(saveScore.mock.calls[0]?.[0]).toMatchObject({
      score: 7.5,
      comment: '还不错',
      expectedVersion: 0,
    });
  });

  it('本地校验失败不发起请求（超满分 / 负数 / 空值）', async () => {
    const saveScore = vi.fn();
    const flow = createGradingFlow(depsWith(saveScore, vi.fn()));

    expect(await flow.submit(BASE_ROW, '11', undefined, 10)).toBe(false);
    expect(await flow.submit(BASE_ROW, '-1', undefined, 10)).toBe(false);
    expect(await flow.submit(BASE_ROW, '', undefined, 10)).toBe(false);
    expect(saveScore).not.toHaveBeenCalled();
  });

  it('非冲突错误落到普通错误通道（如后端「分数不得超过满分」400）', async () => {
    const saveScore = vi.fn().mockRejectedValue(new ApiError(400, '批改分数不得超过本题满分 10'));
    const flow = createGradingFlow(depsWith(saveScore, vi.fn()));

    const ok = await flow.submit(BASE_ROW, '9', undefined, 10);

    expect(ok).toBe(false);
    expect(flow.state.error?.message).toBe('批改分数不得超过本题满分 10');
    expect(flow.state.conflict).toBeNull();
  });

  it('isGradingConflict 只认 1012：其它码（如 1001 重复提交）不算批改冲突', () => {
    expect(isGradingConflict(new ApiError(GRADING_CONFLICT_CODE, '冲突', undefined, 409))).toBe(
      true
    );
    expect(isGradingConflict(new ApiError(1001, '已申请过', undefined, 400))).toBe(false);
    expect(isGradingConflict(new Error('网络错误'))).toBe(false);
  });

  it('行数据缺 version 时直接报错，不带空 version 去提交', async () => {
    const saveScore = vi.fn();
    const flow = createGradingFlow(depsWith(saveScore, vi.fn()));

    const ok = await flow.submit({ ...BASE_ROW, version: undefined }, '5', undefined, 10);

    expect(ok).toBe(false);
    expect(saveScore).not.toHaveBeenCalled();
    expect(flow.state.error?.message).toContain('答卷行数据不完整');
  });
});
