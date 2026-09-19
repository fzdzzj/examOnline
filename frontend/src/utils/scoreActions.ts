/**
 * 成绩管理动作可用性映射（纯函数，vitest 靶子）。
 *
 * ⚠️ 权威在后端（ScoreService 逐条断言），这里只是**入口渲染**的镜像，不得放宽：
 * - summarize：status ≥ ENDED 且 ≠ PUBLISHED（已发布须先撤回再汇总）；
 * - publishPreview / export：status ≥ GRADED（PUBLISHED 也可看，用于复核当前榜单）；
 * - publish：status === GRADED（PUBLISHED 重复调用是幂等跳过，无需入口）；
 * - revoke：status === PUBLISHED **且 ADMIN**（@RequireRole + LoginRoleCheck.assertAdmin 双重校验）；
 * - 批改工作台：status ≥ ENDED（结束后才可批改；GRADED 仍可改分）。
 *
 * 后端若返回意外状态，动作一律不显示（fail-closed），点击越界动作会被后端拒绝。
 */
import { EXAM_STATUS, type ExamStatus } from '@/constants/examStatus';

export interface ScoreActionAvailability {
  /** 教师批改工作台入口 */
  canGrade: boolean;
  /** 汇总客观 + 主观成绩 */
  canSummarize: boolean;
  /** 发布前预览（PUBLISHED 状态下也可看当前榜单） */
  canPreview: boolean;
  /** 批量发布（仅 GRADED → PUBLISHED） */
  canPublish: boolean;
  /** 撤回（仅 PUBLISHED + 管理员，高权限动作） */
  canRevoke: boolean;
  /** 导出成绩单（与预览同前置：≥ GRADED） */
  canExport: boolean;
}

export function resolveScoreActions(
  status: number | undefined,
  isAdmin: boolean
): ScoreActionAvailability {
  // status 未知的考试（列表加载中/异常）一律不给动作
  if (typeof status !== 'number') {
    return {
      canGrade: false,
      canSummarize: false,
      canPreview: false,
      canPublish: false,
      canRevoke: false,
      canExport: false,
    };
  }
  const s = status as ExamStatus;
  const ended = s >= EXAM_STATUS.ENDED;
  const graded = s >= EXAM_STATUS.GRADED;
  return {
    canGrade: ended,
    canSummarize: ended && s !== EXAM_STATUS.PUBLISHED,
    canPreview: graded,
    canPublish: s === EXAM_STATUS.GRADED,
    canRevoke: s === EXAM_STATUS.PUBLISHED && isAdmin,
    canExport: graded,
  };
}
