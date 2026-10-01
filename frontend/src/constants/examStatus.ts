// 考试状态映射（权威在后端，前端只读展示）
// 状态迁移规则：0(未开始) → 1(进行中) → 2(已结束) → 3(已批改) → 4(已发布)
// 前端严禁自行推算状态迁移，一切以接口返回为准

export const EXAM_STATUS = {
  NOT_STARTED: 0,
  IN_PROGRESS: 1,
  ENDED: 2,
  GRADED: 3,
  PUBLISHED: 4,
} as const;

export type ExamStatus = (typeof EXAM_STATUS)[keyof typeof EXAM_STATUS];

export interface ExamStatusConfig {
  label: string;
  color: 'default' | 'processing' | 'error' | 'warning' | 'success';
}

/**
 * 考试状态显示映射
 * @param status 后端返回的状态值
 * @returns 对应的显示配置（label + 颜色）
 */
export function getExamStatusConfig(status: ExamStatus): ExamStatusConfig {
  const configMap: Record<number, ExamStatusConfig> = {
    [EXAM_STATUS.NOT_STARTED]: { label: '未开始', color: 'default' },
    [EXAM_STATUS.IN_PROGRESS]: { label: '进行中', color: 'processing' },
    [EXAM_STATUS.ENDED]: { label: '已结束', color: 'error' },
    [EXAM_STATUS.GRADED]: { label: '已批改', color: 'warning' },
    [EXAM_STATUS.PUBLISHED]: { label: '已发布', color: 'success' },
  };

  // 兜底：未知状态显示为默认
  return configMap[status] || { label: `未知 (${status})`, color: 'default' };
}
