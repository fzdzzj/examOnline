/**
 * 学生端作答纯函数（阶段 22 第 1 片）。
 *
 * 这里守的是三条"前端不许自己算"的边界：
 * 1. **状态只读后端 `group`**：未知/缺失值不许被翻译成一个看起来合理的状态；
 * 2. **选项顺序即后端顺序**：个人快照的选项已被后端洗牌并据此重映射过答案字母，
 *    前端排序＝把学生看到的 A 变成系统认的 B；
 * 3. **答案格式只到后端规范形态为止**：升序去重的字母串，别名归一留在后端。
 */
import { describe, expect, it } from 'vitest';

import type { QuestionView } from '@/api/axios';
import { EXAM_GROUP_LABELS, UNKNOWN_GROUP_LABEL } from '@/constants/studentTaking';
import {
  answerKeyOf,
  answerMapOf,
  answerOfLetters,
  choicesOf,
  formatCountdown,
  groupLabelOf,
  isAnswered,
  letterOfChoiceIndex,
  lettersOfAnswer,
  navStatesOf,
  serverRemainingOf,
} from '@/utils/studentTaking';

const QUESTIONS: QuestionView[] = [
  { number: 1, questionId: 11, type: 1, content: '单选', choices: ['甲', '乙', '丙'], score: 5 },
  { number: 2, questionId: 12, type: 2, content: '多选', choices: ['A1', 'B1'], score: 5 },
  { number: 3, questionId: 13, type: 3, content: '判断', choices: null, score: 5 },
  { number: 4, questionId: 14, type: 4, content: '简答', choices: null, score: 10 },
];

describe('groupLabelOf —— 三态一律取后端返回', () => {
  it('三个后端分组各自映射到独立文案，互不合并', () => {
    expect(groupLabelOf('UPCOMING')).toBe(EXAM_GROUP_LABELS.UPCOMING);
    expect(groupLabelOf('ONGOING')).toBe(EXAM_GROUP_LABELS.ONGOING);
    expect(groupLabelOf('FINISHED')).toBe(EXAM_GROUP_LABELS.FINISHED);
    expect(new Set(Object.values(EXAM_GROUP_LABELS)).size).toBe(3);
  });

  it('后端没给分组或给了契约外的值 → 显示"未返回分组"，绝不猜一个状态', () => {
    expect(groupLabelOf(undefined)).toBe(UNKNOWN_GROUP_LABEL);
    expect(groupLabelOf(null)).toBe(UNKNOWN_GROUP_LABEL);
    expect(groupLabelOf('IN_PROGRESS')).toBe(UNKNOWN_GROUP_LABEL);
    // 猜出来的文案会让学生以为系统认得这个状态，比留白更糟
    expect(groupLabelOf('IN_PROGRESS')).not.toContain('进行中');
  });
});

describe('answerMapOf —— 草稿窄化', () => {
  it('只收字符串/数字标量，其它类型不进答案区', () => {
    expect(answerMapOf({ 11: 'A', 12: 'A,C', 13: 1, 14: null, 15: { x: 1 }, 16: ['A'] })).toEqual({
      11: 'A',
      12: 'A,C',
      13: '1',
    });
  });

  it('非对象（null / 数组 / 字符串）一律当没有草稿', () => {
    expect(answerMapOf(null)).toEqual({});
    expect(answerMapOf(undefined)).toEqual({});
    expect(answerMapOf(['A', 'B'])).toEqual({});
    expect(answerMapOf('oops')).toEqual({});
  });
});

describe('答案键与选项字母', () => {
  it('questionId 是主键；缺失才退回题号（而不是按数组下标，下标会随导航变化）', () => {
    expect(answerKeyOf({ questionId: 11, number: 3 })).toBe('11');
    expect(answerKeyOf({ number: 3 })).toBe('#3');
  });

  it('字母按数组序生成，与后端 letterOfIndex 同序', () => {
    expect(letterOfChoiceIndex(0)).toBe('A');
    expect(letterOfChoiceIndex(2)).toBe('C');
    expect(letterOfChoiceIndex(25)).toBe('Z');
  });

  it('choicesOf 原样保留后端顺序（个人快照已洗牌锁定，前端不许重排）', () => {
    const shuffled = ['丁', '甲', '丙', '乙'];
    const view = choicesOf({ questionId: 1, type: 1, choices: shuffled });
    expect(view).toEqual(shuffled);
    expect(view).not.toEqual([...shuffled].sort());
  });

  it('判断/简答的 choices 为 null → 空数组，不伪造选项', () => {
    expect(choicesOf({ questionId: 1, type: 3, choices: null })).toEqual([]);
    expect(choicesOf({ questionId: 1, type: 4 })).toEqual([]);
  });
});

describe('字母 ↔ 答案串', () => {
  it('解析：逗号分隔、大小写容忍、空白剔除', () => {
    expect(lettersOfAnswer('A,C')).toEqual(['A', 'C']);
    expect(lettersOfAnswer(' c , a ')).toEqual(['C', 'A']);
    expect(lettersOfAnswer('')).toEqual([]);
    expect(lettersOfAnswer(undefined)).toEqual([]);
  });

  it('回写：升序去重，与后端 normalizeMultiple 的存储口径一致', () => {
    expect(answerOfLetters(['C', 'A', 'B'])).toBe('A,B,C');
    expect(answerOfLetters(['B', 'B', 'a'])).toBe('A,B');
    expect(answerOfLetters([])).toBe('');
  });
});

describe('isAnswered —— 已答/未答判定', () => {
  it('空白与只有空格都算未答', () => {
    expect(isAnswered(1, '')).toBe(false);
    expect(isAnswered(4, '   ')).toBe(false);
    expect(isAnswered(1, undefined)).toBe(false);
  });

  it('判断题必须落在 T/F 上：填了别的东西不能算已答（那是后端归一化的活）', () => {
    expect(isAnswered(3, 'T')).toBe(true);
    expect(isAnswered(3, 'f')).toBe(true);
    expect(isAnswered(3, '正确')).toBe(false);
    expect(isAnswered(3, '对')).toBe(false);
  });

  it('简答题非空即已答', () => {
    expect(isAnswered(4, '略')).toBe(true);
  });
});

describe('navStatesOf —— 逐题导航三态', () => {
  it('已答/未答/当前三种标记同时可区分', () => {
    const states = navStatesOf(QUESTIONS, { 11: 'A', 13: 'T' }, 1);
    expect(states.map((s) => s.answered)).toEqual([true, false, true, false]);
    expect(states.map((s) => s.current)).toEqual([false, true, false, false]);
    expect(states.map((s) => s.number)).toEqual([1, 2, 3, 4]);
  });

  it('题号缺失时按数组序补 display 序号，但作答键仍按 questionId', () => {
    const states = navStatesOf([{ questionId: 99, type: 1 }], { 99: 'B' }, 0);
    expect(states[0].number).toBe(1);
    expect(states[0].answered).toBe(true);
  });

  it('返回的是逐题数组而不是计数——第 3 片的"未答题数"必须从同一份数组算', () => {
    expect(navStatesOf(QUESTIONS, {}, 0)).toHaveLength(4);
  });
});

describe('formatCountdown / serverRemainingOf', () => {
  it('秒 → mm:ss，超过一小时带小时', () => {
    expect(formatCountdown(0)).toBe('00:00');
    expect(formatCountdown(65)).toBe('01:05');
    expect(formatCountdown(3600)).toBe('1:00:00');
    expect(formatCountdown(3661)).toBe('1:01:01');
  });

  it('后端没给时间显示占位，绝不显示 00:00（那会被读成"时间到了"）', () => {
    expect(formatCountdown(null)).toBe('—');
    expect(formatCountdown(undefined)).toBe('—');
  });

  it('负数与 NaN 视为后端未给（不 clamp 成 0，那等于前端宣布归零）', () => {
    expect(serverRemainingOf(-1)).toBeNull();
    expect(serverRemainingOf(Number.NaN)).toBeNull();
    expect(serverRemainingOf(undefined)).toBeNull();
    expect(serverRemainingOf(0)).toBe(0);
    expect(serverRemainingOf(120)).toBe(120);
  });
});
