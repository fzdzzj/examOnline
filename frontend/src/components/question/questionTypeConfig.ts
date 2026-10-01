/**
 * 题型驱动的表单配置（本阶段主要工程质量点）：
 * 四种题型共用一套表单骨架 + 这一份题型字段配置，**禁止四份复制粘贴的表单代码**；
 * 新增题型时只需在 QUESTION_TYPE_CONFIGS 追加一个条目。
 *
 * 职责边界（硬约定 4）：这里只做「录入格式校验」；
 * 答案归一化与判分口径是后端职责（AnswerNormalizer）——
 * 例如判断题前端只提交后端约定的 'T'/'F' 规范格式，
 * 「正确/对/A/1」等别名归一**绝不**搬进前端，防止两端判分口径漂移。
 *
 * 校验规则依据（已核实）：
 * - 后端 QuestionCreateRequest：type 1–4、content ≤5000、choices ≤26、
 *   correctAnswer ≤512、score 0.5–999.9、difficulty 1–3、analysis ≤2000；
 * - 后端 QuestionService.validateChoices：单选/多选须 2–26 个非空选项；
 * - 多选「正确项 ≥2」是 spec 的录入侧产品约束（后端归一化仅要求非空），前端按更严的一侧执行。
 */
import type { QuestionCreateRequest, QuestionResponse } from '@/api/axios';
import type { DifficultyCode, QuestionTypeCode } from '@/utils/questionTypes';

/** 表单会渲染的字段（供表单骨架与单测断言「各题型渲染哪些字段」）。 */
export type QuestionFormField =
  'content' | 'choices' | 'answer' | 'score' | 'difficulty' | 'analysis' | 'tags';

/** 答案录入区形态：客观题勾选项字母 / 判断题二选一 / 简答题参考答案文本。 */
export type AnswerInputKind = 'letters' | 'judge' | 'text';

export interface QuestionTypeConfig {
  type: QuestionTypeCode;
  label: string;
  /** 是否渲染选项编辑区（判断/简答不渲染，后端会忽略并置空选项） */
  showChoices: boolean;
  /** 正确项勾选模式：单选只能勾 1 项，多选可勾多项 */
  correctMode: 'single' | 'multiple';
  /** 客观题选项数量上下限（后端 validateChoices：2–26） */
  minChoices: number;
  maxChoices: number;
  /** 答案录入区形态 */
  answerKind: AnswerInputKind;
  /** 正确项数量下限（单选=1；多选=2，spec「多选至少两项」） */
  minCorrect: number;
  /** 该题型在表单中依次渲染的字段清单 */
  fields: readonly QuestionFormField[];
}

const OBJECTIVE_FIELDS: readonly QuestionFormField[] = [
  'content',
  'choices',
  'answer',
  'score',
  'difficulty',
  'tags',
  'analysis',
];

const SUBJECTIVE_FIELDS: readonly QuestionFormField[] = [
  'content',
  'answer',
  'score',
  'difficulty',
  'tags',
  'analysis',
];

export const QUESTION_TYPE_CONFIGS: Record<QuestionTypeCode, QuestionTypeConfig> = {
  1: {
    type: 1,
    label: '单选',
    showChoices: true,
    correctMode: 'single',
    minChoices: 2,
    maxChoices: 26,
    answerKind: 'letters',
    minCorrect: 1,
    fields: OBJECTIVE_FIELDS,
  },
  2: {
    type: 2,
    label: '多选',
    showChoices: true,
    correctMode: 'multiple',
    minChoices: 2,
    maxChoices: 26,
    answerKind: 'letters',
    minCorrect: 2,
    fields: OBJECTIVE_FIELDS,
  },
  3: {
    type: 3,
    label: '判断',
    showChoices: false,
    correctMode: 'single',
    minChoices: 0,
    maxChoices: 0,
    answerKind: 'judge',
    minCorrect: 1,
    fields: SUBJECTIVE_FIELDS,
  },
  4: {
    type: 4,
    label: '简答',
    showChoices: false,
    correctMode: 'single',
    minChoices: 0,
    maxChoices: 0,
    answerKind: 'text',
    minCorrect: 1,
    fields: SUBJECTIVE_FIELDS,
  },
};

export const QUESTION_TYPE_LIST: readonly QuestionTypeConfig[] = [1, 2, 3, 4].map(
  (type) => QUESTION_TYPE_CONFIGS[type as QuestionTypeCode]
);

/** 选项草稿（UI 状态，不进契约）。 */
export interface ChoiceDraft {
  text: string;
  isCorrect: boolean;
}

/** 题目表单草稿（UI 状态；提交时经 toCreateRequest 收敛为契约请求）。
 *  空「未选」用 undefined 表达（antd Select/InputNumber 的空值语义）。 */
export interface QuestionFormDraft {
  type: QuestionTypeCode;
  content: string;
  choices: ChoiceDraft[];
  /** 判断题答案：只存后端约定的 'T'/'F'（硬约定 4，见文件头注释） */
  judgeAnswer: 'T' | 'F' | '';
  /** 简答题参考答案原文 */
  shortAnswer: string;
  score: number | undefined;
  difficulty: DifficultyCode | undefined;
  analysis: string;
  tagIds: number[];
}

/** 选项字母：按顺序对应 A/B/C…（与后端选项字母约定一致）。 */
export function letterOfIndex(index: number): string {
  return String.fromCharCode(65 + index);
}

/** 新建表单的初始草稿：客观题给两个空选项（后端下限即 2）。 */
export function createEmptyDraft(type: QuestionTypeCode): QuestionFormDraft {
  const config = QUESTION_TYPE_CONFIGS[type];
  return {
    type,
    content: '',
    choices: config.showChoices ? [0, 1].map(() => ({ text: '', isCorrect: false })) : [],
    judgeAnswer: '',
    shortAnswer: '',
    score: undefined,
    difficulty: undefined,
    analysis: '',
    tagIds: [],
  };
}

/** 从契约响应回填编辑草稿（列表项即全量字段，无需再拉详情）。 */
export function draftFromResponse(response: QuestionResponse): QuestionFormDraft {
  const type = (response.type ?? 1) as QuestionTypeCode;
  const config = QUESTION_TYPE_CONFIGS[type];
  const correctLetters = (response.correctAnswer ?? '')
    .split(',')
    .map((letter) => letter.trim().toUpperCase())
    .filter(Boolean);
  return {
    type,
    content: response.content ?? '',
    choices: (response.choices ?? []).map((text, index) => ({
      text,
      isCorrect: correctLetters.includes(letterOfIndex(index)),
    })),
    judgeAnswer:
      response.correctAnswer === 'T' || response.correctAnswer === 'F'
        ? response.correctAnswer
        : '',
    shortAnswer: config.answerKind === 'text' ? (response.correctAnswer ?? '') : '',
    score: response.score ?? undefined,
    difficulty: (response.difficulty ?? undefined) as DifficultyCode | undefined,
    analysis: response.analysis ?? '',
    tagIds: (response.tags ?? [])
      .map((tag) => tag.id)
      .filter((id): id is number => id !== undefined),
  };
}

/**
 * 录入格式校验：返回错误清单（空数组=通过）。
 * 只校验录入格式与 spec 约束，不做任何答案归一化（硬约定 4）。
 */
export function validateQuestionDraft(draft: QuestionFormDraft): string[] {
  const config = QUESTION_TYPE_CONFIGS[draft.type];
  const errors: string[] = [];

  const content = draft.content.trim();
  if (!content) {
    errors.push('题干不能为空');
  } else if (content.length > 5000) {
    errors.push('题干最长 5000 字');
  }

  if (config.showChoices) {
    if (draft.choices.length < config.minChoices) {
      errors.push(`客观题至少需要 ${config.minChoices} 个选项`);
    } else if (draft.choices.length > config.maxChoices) {
      errors.push(`选项最多 ${config.maxChoices} 个`);
    }
    if (draft.choices.some((choice) => !choice.text.trim())) {
      errors.push('选项内容不能为空');
    }
    const correctCount = draft.choices.filter((choice) => choice.isCorrect).length;
    if (config.correctMode === 'single' && correctCount !== 1) {
      errors.push('单选题必须且只能勾选一个正确项');
    }
    if (config.correctMode === 'multiple' && correctCount < config.minCorrect) {
      errors.push(`多选题正确项至少 ${config.minCorrect} 项`);
    }
  }

  if (config.answerKind === 'judge') {
    if (draft.judgeAnswer !== 'T' && draft.judgeAnswer !== 'F') {
      errors.push('请选择判断题答案（正确/错误）');
    }
  } else if (config.answerKind === 'text') {
    const answer = draft.shortAnswer.trim();
    if (!answer) {
      errors.push('请填写参考答案');
    } else if (answer.length > 512) {
      errors.push('答案最长 512 字');
    }
  }

  if (draft.score === undefined || !Number.isFinite(draft.score)) {
    errors.push('分值不能为空');
  } else if (draft.score < 0.5 || draft.score > 999.9) {
    errors.push('分值须在 0.5–999.9 之间');
  }

  if (draft.difficulty === undefined) {
    errors.push('请选择难度');
  }

  if (draft.analysis.trim().length > 2000) {
    errors.push('解析最长 2000 字');
  }

  return errors;
}

/** 草稿 → 契约请求（QuestionCreateRequest，创建与更新共用同一请求结构）。 */
export function toCreateRequest(draft: QuestionFormDraft): QuestionCreateRequest {
  const config = QUESTION_TYPE_CONFIGS[draft.type];

  let correctAnswer: string;
  if (config.answerKind === 'letters') {
    // 勾选产生的选项字母（'A' / 升序 'A,B'）已是后端归一化的目标格式，按原文提交
    correctAnswer = draft.choices
      .map((choice, index) => (choice.isCorrect ? letterOfIndex(index) : null))
      .filter((letter): letter is string => letter !== null)
      .join(',');
  } else if (config.answerKind === 'judge') {
    // 硬约定 4：只提交后端约定的 T/F 原始格式；别名归一（正确/对/A/1…）在后端 AnswerNormalizer
    correctAnswer = draft.judgeAnswer;
  } else {
    correctAnswer = draft.shortAnswer.trim();
  }

  return {
    type: draft.type,
    content: draft.content.trim(),
    ...(config.showChoices ? { choices: draft.choices.map((choice) => choice.text.trim()) } : {}),
    correctAnswer,
    score: draft.score as number,
    difficulty: draft.difficulty as number,
    ...(draft.analysis.trim() ? { analysis: draft.analysis.trim() } : {}),
    // tagIds 恒传（可为空数组=清空关联）；后端 update 语义：null=保留原关联，传数组=全量重建
    tagIds: [...draft.tagIds],
  };
}
