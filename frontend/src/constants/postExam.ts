// 考后闭环的状态映射（权威在后端，前端只读展示）。
// 状态与复核状态映射统一放 constants（写入边界约定），禁止在页面里散落魔法数字。

/** 后端 ResponseCode.STATE_CONFLICT：批改乐观锁冲突（HTTP 409）。 */
export const GRADING_CONFLICT_CODE = 1012;

/** 后端 ResponseCode.DATA_ALREADY_EXISTS：复核已提交过（HTTP 400）。 */
export const REVIEW_ALREADY_APPLIED_CODE = 1001;

/** score_review.status（后端 ScoreReview.STATUS_*，与成绩发布状态机正交）。 */
export const REVIEW_STATUS = {
  /** 待处理：申请刚提交，教师尚未处理（进行中 → 触发隐藏成绩） */
  PENDING: 0,
  /** 处理中：教师已受理但未给结论（进行中 → 触发隐藏成绩） */
  PROCESSING: 1,
  /** 已同意：教师认可并可调分，学生端恢复显示（新）成绩 */
  AGREED: 2,
  /** 已驳回：教师不同意，学生端恢复原成绩显示 */
  REJECTED: 3,
} as const;

export type ReviewStatus = (typeof REVIEW_STATUS)[keyof typeof REVIEW_STATUS];

export interface ReviewStatusConfig {
  label: string;
  color: 'default' | 'processing' | 'warning' | 'success' | 'error';
  /** 是否「进行中」——进行中的申请会触发学生端成绩隐藏（后端裁决） */
  ongoing: boolean;
}

export function getReviewStatusConfig(status: number): ReviewStatusConfig {
  const map: Record<number, ReviewStatusConfig> = {
    [REVIEW_STATUS.PENDING]: { label: '待处理', color: 'processing', ongoing: true },
    [REVIEW_STATUS.PROCESSING]: { label: '处理中', color: 'warning', ongoing: true },
    [REVIEW_STATUS.AGREED]: { label: '已同意', color: 'success', ongoing: false },
    [REVIEW_STATUS.REJECTED]: { label: '已驳回', color: 'error', ongoing: false },
  };
  return map[status] ?? { label: `未知 (${status})`, color: 'default', ongoing: false };
}

/** 补考成绩合并规则（后端 Exam.MAKEUP_TAKE_*，默认 takeHighest）。 */
export const MAKEUP_RULE_OPTIONS = [
  { value: 'takeHighest', label: '取最高分（默认，对学生最有利）' },
  { value: 'takeLatest', label: '取最近一次' },
  { value: 'takeAverage', label: '取平均分' },
] as const;

export function makeupRuleLabel(rule?: string): string {
  return MAKEUP_RULE_OPTIONS.find((o) => o.value === rule)?.label ?? `规则 ${rule ?? '未设置'}`;
}
