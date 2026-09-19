/**
 * 主观题批改提交流转（硬约定 2 的核心，vitest 靶子）。
 *
 * 乐观锁契约（SubjectiveGradingService）：
 * - 提交带 `expectedVersion`，后端 `UPDATE ... WHERE id=? AND version=?`（CAS）；
 * - 影响行数为 0 时抛 409 + 业务码 1012「批改已被他人更新，请刷新后重试」。
 *
 * 本状态机的三条铁律：
 * 1. 冲突**必须可见**：state.conflict 置位并透出文案，界面提示「该答卷已被他人批改」；
 * 2. 冲突后**自动拉取最新行**（refreshRow）让教师重看——但**绝不**用旧分数自动重试提交，
 *    否则等于把先提交者的批改盖掉，抹掉后端做对的事；
 * 3. 冲突与失败都**不算成功**（submit 返回 false），界面不得显示成功提示。
 *
 * 依赖注入设计（对齐 useRandomDraw）：saveScore / refreshRow 由调用方注入，
 * 生产传 gen:api 封装，单测传 mock。
 */
import { reactive } from 'vue';

import { ApiError } from '@/api/types';
import type { SubjectiveGradeRow, SubjectiveScoreRequest } from '@/api/axios';
import { GRADING_CONFLICT_CODE } from '@/constants/postExam';
import { validateSubjectiveScore } from '@/utils/gradingValidation';

/** 后端冲突时的提示文案（与后端消息「批改已被他人更新，请刷新后重试」语义一致）。 */
export const GRADING_CONFLICT_MESSAGE = '该答卷已被他人批改，请查看最新内容后重新打分';

export interface GradingConflictInfo {
  submissionId: number;
  questionId: number;
  message: string;
  /** 冲突后拉回的最新行（含他人已打的分数与新 version），供教师重看 */
  latestRow: SubjectiveGradeRow | null;
}

export interface GradingFlowState {
  /** 正在提交的答卷（同一时间只允许一笔在途，防教师连点造成自我冲突） */
  savingSubmissionId: number | null;
  conflict: GradingConflictInfo | null;
  error: { submissionId: number; message: string } | null;
}

export interface GradingFlowDeps {
  /** POST /api/exams/{examId}/grading/subjective/save（带 expectedVersion） */
  saveScore: (request: SubjectiveScoreRequest) => Promise<SubjectiveGradeRow | undefined>;
  /** 冲突后拉取该行最新内容（重新执行 subjectiveRows 并按 submissionId 取该行） */
  refreshRow: (
    submissionId: number,
    questionId: number
  ) => Promise<SubjectiveGradeRow | null | undefined>;
}

/** 判定一个异常是否是批改乐观锁冲突（后端 409 / 业务码 1012）。 */
export function isGradingConflict(error: unknown): boolean {
  return error instanceof ApiError && error.code === GRADING_CONFLICT_CODE;
}

export function createGradingFlow(deps: GradingFlowDeps) {
  const state = reactive<GradingFlowState>({
    savingSubmissionId: null,
    conflict: null,
    error: null,
  });

  /**
   * 提交一笔打分。返回是否成功（冲突 / 校验失败 / 其它错误均为 false）。
   * 冲突时自动拉取最新行放在 conflict.latestRow，界面据此让教师重看后重新打分。
   */
  async function submit(
    row: SubjectiveGradeRow,
    rawScore: string | number | null | undefined,
    comment: string | undefined,
    maxScore: number
  ): Promise<boolean> {
    const submissionId = row.submissionId;
    const questionId = row.questionId;
    if (submissionId === undefined || questionId === undefined || row.version === undefined) {
      state.error = { submissionId: submissionId ?? -1, message: '答卷行数据不完整，请刷新列表' };
      return false;
    }

    const validation = validateSubjectiveScore(rawScore, maxScore);
    if (!validation.ok) {
      state.error = { submissionId, message: validation.message ?? '分数不合法' };
      return false;
    }

    state.savingSubmissionId = submissionId;
    state.conflict = null;
    state.error = null;

    try {
      await deps.saveScore({
        submissionId,
        questionId,
        score: Number(String(rawScore).trim()),
        comment: comment?.trim() ? comment.trim() : undefined,
        expectedVersion: row.version,
      });
      return true;
    } catch (error) {
      if (isGradingConflict(error)) {
        // 铁律 1+2：冲突可见 + 拉最新行给教师重看；不自动重试
        const latest = (await deps.refreshRow(submissionId, questionId)) ?? null;
        state.conflict = {
          submissionId,
          questionId,
          message: GRADING_CONFLICT_MESSAGE,
          latestRow: latest,
        };
        return false;
      }
      state.error = {
        submissionId,
        message: error instanceof Error ? error.message : '提交失败，请稍后重试',
      };
      return false;
    } finally {
      state.savingSubmissionId = null;
    }
  }

  /** 教师已看过冲突提示（界面在重新渲染最新行后调用）。 */
  function dismissConflict(): void {
    state.conflict = null;
  }

  /** 清除普通错误提示。 */
  function dismissError(): void {
    state.error = null;
  }

  return { state, submit, dismissConflict, dismissError };
}

export type GradingFlow = ReturnType<typeof createGradingFlow>;
