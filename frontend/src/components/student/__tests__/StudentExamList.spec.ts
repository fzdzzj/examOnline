/**
 * 学生考试列表的三态渲染（阶段 22 第 1 片）。
 *
 * 最重要的一条用例是「时间窗明明还没到、后端说 FINISHED，界面就必须显示已完成」：
 * 后端 `myExams` 的分组同时掺了答卷状态、个人截止与考试状态机，
 * 前端只要拿 `startTime/endTime` 二次推断，就会在后端判"已结束"的窗口里给出"待考"，
 * 学生据此反复点进入。这条断言就是防止那种实现溜回来。
 */
import { describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { Alert, Button, Table } from 'ant-design-vue';

import type { ExamListItem } from '@/api/axios';
import { EXAM_GROUP_LABELS, UNKNOWN_GROUP_LABEL } from '@/constants/studentTaking';
import StudentExamList from '@/components/student/StudentExamList.vue';

const ONGOING: ExamListItem = {
  examId: 2,
  title: '进行中考试',
  startTime: '2026-09-22T09:00:00',
  endTime: '2026-09-22T11:00:00',
  durationMinutes: 60,
  examStatus: 1,
  group: 'ONGOING',
  canEnter: true,
  submissionStatus: 1,
  remainingSeconds: 900,
};

const UPCOMING: ExamListItem = { ...ONGOING, examId: 3, title: '待考考试', group: 'UPCOMING' };

/** 后端把一场试判成"已完成"，但时间窗看着还远在前头——这正是前端最容易判错的一类。 */
const FINISHED_WITH_FUTURE_WINDOW: ExamListItem = {
  ...ONGOING,
  examId: 4,
  title: '已交卷的考试',
  group: 'FINISHED',
  canEnter: false,
  submissionStatus: 2,
  remainingSeconds: undefined,
  startTime: '2099-01-01T09:00:00',
  endTime: '2099-01-01T11:00:00',
};

function mountList(items: ExamListItem[], extra: Record<string, unknown> = {}) {
  return mount(StudentExamList, { props: { items, ...extra } });
}

function cellTexts(wrapper: ReturnType<typeof mountList>, selector: string): string[] {
  return wrapper.findAll(selector).map((node) => node.text());
}

describe('StudentExamList', () => {
  it('三个后端分组各渲染自己的文案，顺序与后端一致', () => {
    const wrapper = mountList([UPCOMING, ONGOING, FINISHED_WITH_FUTURE_WINDOW]);
    expect(cellTexts(wrapper, '[data-test="group-tag"]')).toEqual([
      EXAM_GROUP_LABELS.UPCOMING,
      EXAM_GROUP_LABELS.ONGOING,
      EXAM_GROUP_LABELS.FINISHED,
    ]);
  });

  it('状态只取后端 group：时间窗在未来的行后端说已完成，界面就不许显示待考/进行中', () => {
    const wrapper = mountList([FINISHED_WITH_FUTURE_WINDOW]);
    const tag = wrapper.find('[data-test="group-tag"]').text();
    expect(tag).toBe(EXAM_GROUP_LABELS.FINISHED);
    expect(tag).not.toContain('待考');
    expect(tag).not.toContain('进行中');
    // 前端也不许自己把"已完成"改判成"可以重考"
    expect(wrapper.find('[data-test="enter-button"]').attributes('disabled')).toBeDefined();
  });

  it('契约外的分组值显示"后端未返回分组"，不套一个看起来合理的状态', () => {
    const wrapper = mountList([{ ...UPCOMING, group: 'RUNNING' }]);
    expect(wrapper.find('[data-test="group-tag"]').text()).toBe(UNKNOWN_GROUP_LABEL);
  });

  it('剩余时长就是后端给的 remainingSeconds：没有则占位，不用 endTime 减本地时间补', () => {
    const wrapper = mountList([ONGOING, { ...UPCOMING, remainingSeconds: undefined }]);
    expect(cellTexts(wrapper, '[data-test="remaining"]')).toEqual(['15:00', '—']);
  });

  it('入口可点性只认 canEnter：true 可点、false 与缺失都禁用', async () => {
    const emit = vi.fn();
    const wrapper = mountList(
      [
        ONGOING,
        { ...UPCOMING, canEnter: false },
        { ...UPCOMING, examId: 5, canEnter: undefined },
        { ...UPCOMING, examId: 6, canEnter: true },
      ],
      { onEnter: emit }
    );
    const buttons = wrapper
      .findAllComponents(Button)
      .filter((node) => node.attributes('data-test') === 'enter-button');
    expect(buttons.map((node) => node.attributes('disabled') !== undefined)).toEqual([
      false,
      true,
      true,
      false,
    ]);

    await buttons[0].trigger('click');
    expect(emit).toHaveBeenCalledTimes(1);
    expect(emit.mock.calls[0][0]).toMatchObject({ examId: 2, group: 'ONGOING' });
  });

  it('进行中的行文案是"继续作答"，其余是"进入考试"——措辞跟着后端分组走', () => {
    const wrapper = mountList([UPCOMING, ONGOING, FINISHED_WITH_FUTURE_WINDOW]);
    const labels = wrapper.findAll('[data-test="enter-button"]').map((node) => node.text().trim());
    expect(labels).toEqual(['进入考试', '继续作答', '进入考试']);
  });

  it('拉取失败渲染错误 Alert，不伪装成"暂无可参加的考试"', () => {
    const wrapper = mountList([], { error: '无法连接服务器，请确认后端已启动' });
    expect(wrapper.findComponent(Alert).exists()).toBe(true);
    expect(wrapper.text()).toContain('无法连接服务器');
    expect(wrapper.find('[data-test="empty"]').exists()).toBe(false);
  });

  it('没有考试（后端返回空数组）才显示空态', () => {
    const wrapper = mountList([]);
    expect(wrapper.find('[data-test="empty"]').exists()).toBe(true);
    expect(wrapper.findComponent(Alert).exists()).toBe(false);
  });

  it('列表仍交给 Table 渲染（分页/排序交给后端与表格，不自己拼 DOM）', () => {
    const wrapper = mountList([ONGOING]);
    expect(wrapper.findComponent(Table).props('dataSource')).toHaveLength(1);
  });
});
