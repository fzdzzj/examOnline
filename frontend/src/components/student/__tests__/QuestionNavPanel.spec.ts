/**
 * 导航格：已标记题呈 🚩 角标 + 橙色样式（三态叠加，spec-delta「答题卡疑难标记」）。
 *
 * 判据：NavItemState.marked 为 true 的格子渲染 🚩 角标并带橙色 class；
 * 已答/未答/当前三态与标记态叠加不冲突。
 */
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';

import QuestionNavPanel from '@/components/student/QuestionNavPanel.vue';
import type { NavItemState } from '@/utils/studentTaking';

function statesOf(
  markedIndexes: number[],
  base: Array<Pick<NavItemState, 'answered' | 'current'>> = [
    { answered: true, current: true },
    { answered: false, current: false },
    { answered: false, current: false },
    { answered: true, current: false },
  ]
): NavItemState[] {
  return base.map((b, index) => ({
    index,
    number: index + 1,
    answered: b.answered,
    current: b.current,
    marked: markedIndexes.includes(index),
  }));
}

function mountPanel(states: NavItemState[]) {
  return mount(QuestionNavPanel, { props: { states } });
}

describe('QuestionNavPanel 标记角标', () => {
  it('已标记格渲染 🚩 角标并带橙色标记 class', () => {
    const wrapper = mountPanel(statesOf([1, 3]));
    const cell0 = wrapper.find('[data-test="nav-0"]');
    const cell1 = wrapper.find('[data-test="nav-1"]');
    // 未标记格子不带角标
    expect(cell0.find('[data-test^="mark-badge"]').exists()).toBe(false);
    // 已标记格子带角标与橙色 class
    expect(cell1.find('[data-test="mark-badge"]').exists()).toBe(true);
    expect(cell1.text()).toContain('🚩');
    expect(cell1.classes()).toContain('nav-marked');
  });

  it('未答已标记与已答已标记格子都渲染角标（数组层面对称覆盖）', () => {
    const wrapper = mountPanel(statesOf([1, 2]));
    expect(wrapper.find('[data-test="nav-1"] [data-test="mark-badge"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="nav-2"] [data-test="mark-badge"]').exists()).toBe(true);
  });

  it('图例展示「已标记」橙色说明', () => {
    const wrapper = mountPanel(statesOf([]));
    expect(wrapper.text()).toContain('已标记');
  });
});
