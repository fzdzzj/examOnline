import { describe, expect, it } from 'vitest';

import {
  draftFromResponse,
  letterOfIndex,
  QUESTION_TYPE_CONFIGS,
  QUESTION_TYPE_LIST,
  toCreateRequest,
  validateQuestionDraft,
  type ChoiceDraft,
  type QuestionFormDraft,
} from '@/components/question/questionTypeConfig';
import type { QuestionResponse } from '@/api/axios';

/** 构造一份可通过校验的草稿，再按用例覆盖差异字段。
 * 客观题默认勾选第 1 个选项为正确（满足单选最低要求）；多选题补第 3 个为正确（满足≥2）。
 *
 * 策略：先设好基线值，然后仅应用 overrides 中显式提供的字段（避免 ...overrides 全量覆盖）。
 */
function validDraft(
  overrides: Partial<QuestionFormDraft> & { type: 1 | 2 | 3 | 4 }
): QuestionFormDraft {
  const type = overrides.type;
  const objective = QUESTION_TYPE_CONFIGS[type].showChoices;

  // 基线值：通过校验的最小合法配置
  const draft: QuestionFormDraft = {
    type,
    content: '题干',
    choices: objective
      ? [
          { text: '选项甲', isCorrect: true },
          { text: '选项乙', isCorrect: false },
          { text: '选项丙', isCorrect: type === 2 }, // 多选需要至少 2 个正确项
        ]
      : [],
    judgeAnswer: type === 3 ? 'T' : '',
    shortAnswer: type === 4 ? '参考答案' : '',
    score: 2,
    difficulty: 2,
    analysis: '',
    tagIds: [],
  };

  // 仅覆盖 overrides 中显式提供的字段（排除 type，已在上面使用）
  for (const key of Object.keys(overrides) as (keyof QuestionFormDraft)[]) {
    if (key === 'type') continue; // type 已用于初始化
    const value = overrides[key];
    if (key === 'choices' && value) {
      // choices 需要深拷贝
      draft.choices = JSON.parse(JSON.stringify(value));
    } else if (value !== undefined || key in draft) {
      // 即使值为 undefined 也要覆盖（用于测试缺失字段的校验）。
      // key 来自 keyof QuestionFormDraft，这里只是绕开联合键索引的窄化限制，并非引入 any。
      (draft as unknown as Record<string, unknown>)[key] = value;
    }
  }

  return draft;
}

describe('题型配置：各题型渲染字段与校验形态（配置驱动）', () => {
  it('共 4 种题型，与后端 QuestionType 枚举一一对应（1单选 2多选 3判断 4简答）', () => {
    expect(QUESTION_TYPE_LIST.map((config) => config.type)).toEqual([1, 2, 3, 4]);
    expect(QUESTION_TYPE_LIST.map((config) => config.label)).toEqual([
      '单选',
      '多选',
      '判断',
      '简答',
    ]);
  });

  it('客观题（单选/多选）渲染选项区，答案录入为勾选字母；选项上下限 2–26', () => {
    for (const type of [1, 2] as const) {
      const config = QUESTION_TYPE_CONFIGS[type];
      expect(config.showChoices).toBe(true);
      expect(config.answerKind).toBe('letters');
      expect(config.fields).toContain('choices');
      expect(config.minChoices).toBe(2);
      expect(config.maxChoices).toBe(26);
    }
  });

  it('判断/简答不渲染选项区；判断答案为 T/F 二选一，简答为参考答案文本', () => {
    expect(QUESTION_TYPE_CONFIGS[3].showChoices).toBe(false);
    expect(QUESTION_TYPE_CONFIGS[3].answerKind).toBe('judge');
    expect(QUESTION_TYPE_CONFIGS[3].fields).not.toContain('choices');
    expect(QUESTION_TYPE_CONFIGS[4].showChoices).toBe(false);
    expect(QUESTION_TYPE_CONFIGS[4].answerKind).toBe('text');
    expect(QUESTION_TYPE_CONFIGS[4].fields).not.toContain('choices');
  });

  it('多选正确项下限为 2（spec 录入约束），单选为 1', () => {
    expect(QUESTION_TYPE_CONFIGS[1].minCorrect).toBe(1);
    expect(QUESTION_TYPE_CONFIGS[2].minCorrect).toBe(2);
  });

  it('选项字母按顺序 A/B/C…', () => {
    expect([0, 1, 2, 25].map(letterOfIndex)).toEqual(['A', 'B', 'C', 'Z']);
  });
});

describe('题型校验规则', () => {
  it('合法草稿：四种题型均通过', () => {
    for (const type of [1, 2, 3, 4] as const) {
      expect(validateQuestionDraft(validDraft({ type }))).toEqual([]);
    }
  });

  it('公共校验：题干为空、分值缺失、分值越界、难度缺失均报错', () => {
    const errors = validateQuestionDraft(
      validDraft({ type: 1, content: '   ', score: undefined, difficulty: undefined })
    );
    expect(errors).toContain('题干不能为空');
    expect(errors).toContain('分值不能为空');
    expect(errors).toContain('请选择难度');

    const outOfRange = validateQuestionDraft(validDraft({ type: 1, score: 0.2 }));
    expect(outOfRange).toContain('分值须在 0.5–999.9 之间');
  });

  it('单选：必须且只能勾选一个正确项（0 项与 2 项都报错）', () => {
    const noCorrect: ChoiceDraft[] = [
      { text: '选项甲', isCorrect: false },
      { text: '选项乙', isCorrect: false },
      { text: '选项丙', isCorrect: false },
    ];
    const none = validDraft({ type: 1, choices: noCorrect });
    expect(validateQuestionDraft(none)).toContain('单选题必须且只能勾选一个正确项');

    const conflict = validDraft({ type: 1 });
    conflict.choices[2].isCorrect = true; // 默认已勾第 1 项，再勾第 3 项即 2 项
    expect(validateQuestionDraft(conflict)).toContain('单选题必须且只能勾选一个正确项');

    const exactlyOne = validDraft({
      type: 1,
      choices: [
        { text: '选项甲', isCorrect: false },
        { text: '选项乙', isCorrect: true },
        { text: '选项丙', isCorrect: false },
      ],
    });
    expect(validateQuestionDraft(exactlyOne)).toEqual([]);
  });

  it('多选：正确项少于 2 项报错；勾 2 项通过', () => {
    const one = validDraft({
      type: 2,
      choices: [
        { text: '选项甲', isCorrect: true },
        { text: '选项乙', isCorrect: false },
        { text: '选项丙', isCorrect: false },
      ],
    });
    expect(validateQuestionDraft(one)).toContain('多选题正确项至少 2 项');

    const two = validDraft({ type: 2 }); // 默认已勾第 1、3 两项
    expect(validateQuestionDraft(two)).toEqual([]);
  });

  it('客观题：选项不足 2 个 / 选项内容为空报错', () => {
    const tooFew = validDraft({ type: 1, choices: [{ text: '甲', isCorrect: true }] });
    expect(validateQuestionDraft(tooFew)).toContain('客观题至少需要 2 个选项');

    const blank = validDraft({ type: 2 });
    blank.choices[1].text = ' ';
    expect(validateQuestionDraft(blank)).toContain('选项内容不能为空');
  });

  it('判断：未选择 T/F 报错（提交格式仅为后端约定的 T/F）', () => {
    const unset = validDraft({ type: 3, judgeAnswer: '' });
    expect(validateQuestionDraft(unset)).toContain('请选择判断题答案（正确/错误）');
  });

  it('简答：参考答案为空报错', () => {
    const empty = validDraft({ type: 4, shortAnswer: '  ' });
    expect(validateQuestionDraft(empty)).toContain('请填写参考答案');
  });
});

describe('草稿 ↔ 契约请求转换', () => {
  it('单选提交：correctAnswer 为单个大写字母，choices 为选项文本数组', () => {
    const draft = validDraft({ type: 1 });
    draft.choices[0].isCorrect = false; // 清掉默认勾选，改为第 3 项
    draft.choices[2].isCorrect = true;
    const request = toCreateRequest(draft);
    expect(request.type).toBe(1);
    expect(request.choices).toEqual(['选项甲', '选项乙', '选项丙']);
    expect(request.correctAnswer).toBe('C');
  });

  it('多选提交：正确项按题号顺序输出字母（A,B 格式，后端再归一化排序）', () => {
    const draft = validDraft({ type: 2 });
    draft.choices[2].isCorrect = true;
    draft.choices[0].isCorrect = true;
    expect(toCreateRequest(draft).correctAnswer).toBe('A,C');
  });

  it('判断提交：只提交后端约定的 T/F 原始格式；不携带 choices 字段', () => {
    const request = toCreateRequest(validDraft({ type: 3, judgeAnswer: 'F' }));
    expect(request.correctAnswer).toBe('F');
    expect('choices' in request).toBe(false);
  });

  it('简答提交：参考答案原文；不携带 choices 字段；空解析不下发', () => {
    const request = toCreateRequest(validDraft({ type: 4, shortAnswer: '  参考答案  ' }));
    expect(request.correctAnswer).toBe('参考答案');
    expect('choices' in request).toBe(false);
    expect('analysis' in request).toBe(false);
  });

  it('tagIds 恒传（空数组=清空关联；后端 null 才是保留原关联）', () => {
    const withTags = validDraft({ type: 3, tagIds: [7, 9] });
    expect(toCreateRequest(withTags).tagIds).toEqual([7, 9]);
    expect(toCreateRequest(validDraft({ type: 3 })).tagIds).toEqual([]);
  });
});

describe('编辑回填 draftFromResponse', () => {
  it('客观题：按 correctAnswer 字母还原正确项勾选', () => {
    const response: QuestionResponse = {
      id: 11,
      type: 2,
      content: '多选题干',
      choices: ['甲', '乙', '丙'],
      correctAnswer: 'A,C',
      score: 3,
      difficulty: 3,
      analysis: '解析',
      tags: [{ id: 7, name: '函数', type: 'SUBJECT' }],
    };
    const draft = draftFromResponse(response);
    expect(draft.type).toBe(2);
    expect(draft.choices.map((choice) => choice.isCorrect)).toEqual([true, false, true]);
    expect(draft.tagIds).toEqual([7]);
    expect(draft.score).toBe(3);
  });

  it('判断题：T/F 还原为二选一值', () => {
    const draft = draftFromResponse({ id: 12, type: 3, correctAnswer: 'F' });
    expect(draft.judgeAnswer).toBe('F');
  });

  it('简答题：correctAnswer 原文进 shortAnswer', () => {
    const draft = draftFromResponse({ id: 13, type: 4, correctAnswer: '参考答案原文' });
    expect(draft.shortAnswer).toBe('参考答案原文');
  });
});
