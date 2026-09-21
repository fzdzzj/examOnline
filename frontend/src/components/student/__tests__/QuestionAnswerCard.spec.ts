/**
 * 四种题型的作答控件与归零锁定（阶段 22 第 1 片）。
 *
 * 两条最容易做错的点各断一次：
 * 1. **选项顺序**：个人快照里的选项已被后端 `shuffleChoicesAndRemapAnswer` 洗过，
 *    并把 `correctAnswer` 的字母按新序重映射。前端再排一次序，学生看到的 A 就不是系统认的 A；
 * 2. **归零锁定不能只靠 disabled**：`locked` 时 emit 路径本身也要断掉——
 *    浏览器层面挡一下不构成约束。
 */
import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { Alert, CheckboxGroup, RadioGroup, Textarea } from 'ant-design-vue';
import type { Component } from 'vue';

import type { QuestionView } from '@/api/axios';
import QuestionAnswerCard from '@/components/student/QuestionAnswerCard.vue';

/** 选项故意逆字典序：任何"顺手 sort 一下"的实现都会被这里抓到。 */
const SINGLE: QuestionView = {
  number: 1,
  questionId: 11,
  type: 1,
  content: '下列哪个是快照？',
  choices: ['锁定副本', '一致性读', '即时拷贝'],
  score: 5,
};
const MULTIPLE: QuestionView = { ...SINGLE, number: 2, questionId: 12, type: 2 };
const JUDGE: QuestionView = {
  number: 3,
  questionId: 13,
  type: 3,
  content: '快照进入即锁定',
  choices: null,
  score: 5,
};
const SHORT: QuestionView = {
  number: 4,
  questionId: 14,
  type: 4,
  content: '简述三重幂等',
  choices: null,
  score: 10,
};

function mountCard(question: QuestionView, props: Record<string, unknown> = {}) {
  return mount(QuestionAnswerCard, { props: { question, ...props } });
}

describe('QuestionAnswerCard 题型分支', () => {
  it('单选：渲染 Radio 组，选项顺序与文案字母严格按快照', () => {
    const wrapper = mountCard(SINGLE);
    const group = wrapper.findComponent(RadioGroup);
    expect(group.exists()).toBe(true);
    expect(wrapper.findComponent(CheckboxGroup).exists()).toBe(false);
    expect(group.props('options')).toEqual([
      { value: 'A', label: 'A. 锁定副本' },
      { value: 'B', label: 'B. 一致性读' },
      { value: 'C', label: 'C. 即时拷贝' },
    ]);
  });

  it('单选：点选后按后端规范形态（单字母）回抛', () => {
    const wrapper = mountCard(SINGLE);
    wrapper.findComponent(RadioGroup).vm.$emit('update:value', 'B');
    expect(wrapper.emitted('update:answer')).toEqual([['B']]);
  });

  it('多选：渲染 Checkbox 组，回抛时升序去重（与后端 normalizeMultiple 同口径）', () => {
    const wrapper = mountCard(MULTIPLE);
    const group = wrapper.findComponent(CheckboxGroup);
    expect(group.exists()).toBe(true);
    expect(wrapper.findComponent(RadioGroup).exists()).toBe(false);

    group.vm.$emit('update:value', ['C', 'A', 'A']);
    expect(wrapper.emitted('update:answer')?.[0]).toEqual(['A,C']);
  });

  it('判断：只有"正确/错误"两项，值取后端约定的 T/F，不提交中文别名', () => {
    const wrapper = mountCard(JUDGE);
    const group = wrapper.findComponent(RadioGroup);
    expect(group.props('options')).toEqual([
      { value: 'T', label: '正确' },
      { value: 'F', label: '错误' },
    ]);
    group.vm.$emit('update:value', 'F');
    expect(wrapper.emitted('update:answer')?.[0]).toEqual(['F']);
  });

  it('简答：渲染文本域，原文回抛（不前端截断、不 trim 掉学生写的内容）', () => {
    const wrapper = mountCard(SHORT);
    const box = wrapper.findComponent(Textarea);
    expect(box.exists()).toBe(true);
    expect(wrapper.findComponent(RadioGroup).exists()).toBe(false);
    box.vm.$emit('update:value', '  防重表 + 唯一索引 + SETNX  ');
    expect(wrapper.emitted('update:answer')?.[0]).toEqual(['  防重表 + 唯一索引 + SETNX  ']);
  });

  it('契约外的题型值：明确告知不支持，不悄悄按单选题渲染（那会答出一个后端认不了的答案）', () => {
    const wrapper = mountCard({ number: 5, questionId: 15, type: 9, content: '听力' });
    expect(wrapper.findComponent(Alert).exists()).toBe(true);
    expect(wrapper.find('[data-test="unsupported"]').exists()).toBe(true);
    expect(wrapper.findComponent(RadioGroup).exists()).toBe(false);
    expect(wrapper.findComponent(CheckboxGroup).exists()).toBe(false);
    expect(wrapper.findComponent(Textarea).exists()).toBe(false);
  });

  it('回显：已有答案按题型还原选中态（续答/刷新后要看得见）', () => {
    expect(
      mountCard(MULTIPLE, { answer: 'A,C' }).findComponent(CheckboxGroup).props('value')
    ).toEqual(['A', 'C']);
    expect(mountCard(SINGLE, { answer: 'B' }).findComponent(RadioGroup).props('value')).toBe('B');
    expect(mountCard(SHORT, { answer: '略' }).findComponent(Textarea).props('value')).toBe('略');
  });
});

describe('QuestionAnswerCard 归零锁定', () => {
  it('locked 时四种控件全部 disabled', () => {
    const cases: Array<[QuestionView, Component]> = [
      [SINGLE, RadioGroup],
      [MULTIPLE, CheckboxGroup],
      [JUDGE, RadioGroup],
      [SHORT, Textarea],
    ];
    for (const [question, component] of cases) {
      const wrapper = mountCard(question, { locked: true });
      // findComponent 拿到的是抽象 Component，props 类型退化为 never，这里显式窄化一次
      const props = wrapper.findComponent(component).props() as unknown as Record<string, unknown>;
      expect(props.disabled, `type=${question.type}`).toBe(true);
    }
  });

  it('locked 时即使控件层漏过事件，也不回抛答案（锁定不依赖浏览器行为）', () => {
    const wrapper = mountCard(SINGLE, { locked: true, answer: 'A' });
    wrapper.findComponent(RadioGroup).vm.$emit('update:value', 'B');
    expect(wrapper.emitted('update:answer')).toBeUndefined();

    const multi = mountCard(MULTIPLE, { locked: true });
    multi.findComponent(CheckboxGroup).vm.$emit('update:value', ['A']);
    expect(multi.emitted('update:answer')).toBeUndefined();

    const text = mountCard(SHORT, { locked: true });
    text.findComponent(Textarea).vm.$emit('update:value', '补一句');
    expect(text.emitted('update:answer')).toBeUndefined();
  });

  it('锁定态给出可见提示，措辞不宣称"已超时"（那是后端的判定权）', () => {
    const wrapper = mountCard(SINGLE, { locked: true });
    const hint = wrapper.find('[data-test="locked-hint"]').text();
    expect(hint).toContain('锁定');
    expect(hint).not.toContain('已超时');
  });

  it('不锁定：正常回抛', () => {
    const wrapper = mountCard(SINGLE);
    expect(wrapper.findComponent(RadioGroup).props('disabled')).toBe(false);
    wrapper.findComponent(RadioGroup).vm.$emit('update:value', 'C');
    expect(wrapper.emitted('update:answer')?.[0]).toEqual(['C']);
  });
});
