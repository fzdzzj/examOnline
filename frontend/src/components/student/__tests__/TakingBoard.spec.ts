/**
 * 答题工作台：逐题导航 + 当前题 + 归零锁定贯通（阶段 22 第 1 片）。
 *
 * 这里验的是**接线**而不是控件本身（控件在 QuestionAnswerCard.spec 里）：
 * 已答/未答/当前三态要能同时区分、上一题/下一题在两端收口、
 * 归零时锁定信号必须一路传到作答控件并且**在容器这一层也拦一次**。
 */
import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';

import type { QuestionView } from '@/api/axios';
import QuestionAnswerCard from '@/components/student/QuestionAnswerCard.vue';
import QuestionNavPanel from '@/components/student/QuestionNavPanel.vue';
import TakingBoard from '@/components/student/TakingBoard.vue';

const QUESTIONS: QuestionView[] = [
  { number: 1, questionId: 11, type: 1, content: '第一题', choices: ['甲', '乙'], score: 5 },
  { number: 2, questionId: 12, type: 4, content: '第二题', choices: null, score: 5 },
  { number: 3, questionId: 13, type: 3, content: '第三题', choices: null, score: 5 },
];

function mountBoard(props: Record<string, unknown> = {}) {
  return mount(TakingBoard, {
    props: { questions: QUESTIONS, answers: {}, locked: false, ...props },
  });
}

function navFlags(wrapper: ReturnType<typeof mountBoard>) {
  return wrapper
    .findComponent(QuestionNavPanel)
    .props('states')
    .map((state) => ({
      answered: state.answered,
      current: state.current,
    }));
}

describe('TakingBoard 导航', () => {
  it('已答/未答/当前三态同时可区分', () => {
    const wrapper = mountBoard({ answers: { 11: 'A' } });
    expect(navFlags(wrapper)).toEqual([
      { answered: true, current: true },
      { answered: false, current: false },
      { answered: false, current: false },
    ]);
  });

  it('上一题/下一题按 index 移动，两端各自收口', async () => {
    const wrapper = mountBoard();
    expect(wrapper.find('[data-test="cursor"]').text()).toContain('当前第 1 / 3 题');
    expect(wrapper.find('[data-test="prev"]').attributes('disabled')).toBeDefined();

    await wrapper.find('[data-test="next"]').trigger('click');
    expect(wrapper.find('[data-test="cursor"]').text()).toContain('当前第 2 / 3 题');
    expect(wrapper.findComponent(QuestionAnswerCard).props('question').questionId).toBe(12);

    await wrapper.find('[data-test="next"]').trigger('click');
    expect(wrapper.find('[data-test="next"]').attributes('disabled')).toBeDefined();

    await wrapper.find('[data-test="prev"]').trigger('click');
    // 两次「下一题」后停在第 3 题，回到第 2 题
    expect(wrapper.findComponent(QuestionAnswerCard).props('question').questionId).toBe(12);
  });

  it('点导航格直接跳题；越界 index 不改变当前题', async () => {
    const wrapper = mountBoard();
    await wrapper.findComponent(QuestionNavPanel).vm.$emit('select', 2);
    expect(wrapper.findComponent(QuestionAnswerCard).props('question').questionId).toBe(13);

    await wrapper.findComponent(QuestionNavPanel).vm.$emit('select', 99);
    expect(wrapper.findComponent(QuestionAnswerCard).props('question').questionId).toBe(13);
    await wrapper.findComponent(QuestionNavPanel).vm.$emit('select', -1);
    expect(wrapper.findComponent(QuestionAnswerCard).props('question').questionId).toBe(13);
  });

  it('已答题数显示的是导航数组里 answered 的计数（与第 3 片"未答题数"同一来源）', () => {
    const wrapper = mountBoard({ answers: { 11: 'A', 12: '  ', 13: 'T' } });
    // 简答题只有空格 → 仍未答；判断题 T → 已答
    expect(wrapper.find('[data-test="progress"]').text()).toContain('已答 2 / 3');
  });

  it('后端没给题目（答卷已封闭）时空态提示，不渲染空白导航', () => {
    const wrapper = mountBoard({ questions: [] });
    expect(wrapper.find('[data-test="board-empty"]').exists()).toBe(true);
    expect(wrapper.findComponent(QuestionAnswerCard).exists()).toBe(false);
  });
});

describe('TakingBoard 归零锁定', () => {
  it('locked 传到作答控件：所有作答入口 disabled', () => {
    const wrapper = mountBoard({ locked: true });
    expect(wrapper.findComponent(QuestionAnswerCard).props('locked')).toBe(true);
    expect(wrapper.find('[data-test="locked-hint"]').exists()).toBe(true);
  });

  it('locked 时即使子组件回抛答案，容器也不改答案状态（锁定不靠一层 disabled）', async () => {
    const wrapper = mountBoard({ locked: true, answers: { 11: 'A' } });
    await wrapper.findComponent(QuestionAnswerCard).vm.$emit('update:answer', 'B');
    expect(wrapper.emitted('update:answer')).toBeUndefined();
    // 导航仍然可用：锁定的是作答，不是回看
    await wrapper.findComponent(QuestionNavPanel).vm.$emit('select', 1);
    expect(wrapper.findComponent(QuestionAnswerCard).props('question').questionId).toBe(12);
  });

  it('未锁定时把答案连同题号键一起回抛给页面（答案只存组件状态）', async () => {
    const wrapper = mountBoard();
    await wrapper.findComponent(QuestionAnswerCard).vm.$emit('update:answer', 'C');
    expect(wrapper.emitted('update:answer')?.[0]).toEqual(['11', 'C']);
  });
});
