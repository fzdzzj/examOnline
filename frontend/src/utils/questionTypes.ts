/**
 * 题型 / 难度 / 标签类型的展示映射（纯数据 + 纯函数，便于 vitest 直测）。
 *
 * 题型清单以后端枚举为准（src/main/java/com/exam/question/entity/QuestionType.java）：
 * 仅 4 类——1 单选 / 2 多选 / 3 判断 / 4 简答；需求文档里的填空/公式/代码/听力/口语
 * 已在 v3 方案裁剪，界面不做（硬约定 7：不夹带砍掉项）。
 */
export type QuestionTypeCode = 1 | 2 | 3 | 4;
export type DifficultyCode = 1 | 2 | 3;

export const QUESTION_TYPE_LABELS: Record<QuestionTypeCode, string> = {
  1: '单选',
  2: '多选',
  3: '判断',
  4: '简答',
};

export const QUESTION_TYPE_OPTIONS: Array<{ value: QuestionTypeCode; label: string }> = (
  Object.keys(QUESTION_TYPE_LABELS) as Array<`${QuestionTypeCode}`>
).map((key) => ({
  value: Number(key) as QuestionTypeCode,
  label: QUESTION_TYPE_LABELS[Number(key) as QuestionTypeCode],
}));

export function typeLabelOf(type?: number | null): string {
  if (type === null || type === undefined) return '未知题型';
  return QUESTION_TYPE_LABELS[type as QuestionTypeCode] ?? '未知题型';
}

export const DIFFICULTY_LABELS: Record<DifficultyCode, string> = {
  1: '易',
  2: '中',
  3: '难',
};

export const DIFFICULTY_OPTIONS: Array<{ value: DifficultyCode; label: string }> = (
  Object.keys(DIFFICULTY_LABELS) as Array<`${DifficultyCode}`>
).map((key) => ({
  value: Number(key) as DifficultyCode,
  label: DIFFICULTY_LABELS[Number(key) as DifficultyCode],
}));

export function difficultyLabelOf(difficulty?: number | null): string {
  if (difficulty === null || difficulty === undefined) return '—';
  return DIFFICULTY_LABELS[difficulty as DifficultyCode] ?? '—';
}

/** 标签类型：后端 TagCreateRequest @Pattern(SUBJECT|DIFFICULTY|QUESTION_TYPE|CUSTOM)，不扩表。 */
export const TAG_TYPE_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'SUBJECT', label: '学科' },
  { value: 'DIFFICULTY', label: '难度' },
  { value: 'QUESTION_TYPE', label: '题型' },
  { value: 'CUSTOM', label: '自定义' },
];

export function tagTypeLabelOf(type?: string | null): string {
  return TAG_TYPE_OPTIONS.find((option) => option.value === type)?.label ?? type ?? '—';
}

/** 试卷状态：0=草稿 1=已锁定（后端 Paper.STATUS_*）。 */
export const PAPER_STATUS_DRAFT = 0;
export const PAPER_STATUS_LOCKED = 1;

export function paperStatusLabel(status?: number | null): string {
  if (status === PAPER_STATUS_LOCKED) return '已锁定';
  return '草稿';
}
