/**
 * 监考视图的渲染用例（阶段 21 缺口 3）。
 *
 * ⚠️ 断言口径：这里**只断言"界面等于后端返回"**，绝不断言前端推算值——
 * 一旦写成"前端把 online+offline 加起来应等于 total"，就把不该前端负责的计算固化成了契约
 * （`MonitorService` 的在线判定来自 Redis 心跳，前端无从复算）。
 */
import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Table, Tag } from 'ant-design-vue';

import type { MonitorOverviewResponse } from '@/api/axios';
import ExamMonitorPanel from '@/components/exam/ExamMonitorPanel.vue';
import { MONITOR_POLLING_HINT, MONITOR_POLLING_INTERVAL_MS } from '@/constants/monitor';

const PANEL_PATH = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../ExamMonitorPanel.vue'
);

const OVERVIEW: MonitorOverviewResponse = {
  examId: 9,
  examTitle: '期中考试',
  examStatus: 1,
  totalStudents: 10,
  onlineCount: 3,
  offlineCount: 4,
  submittedCount: 3,
  abnormalCount: 2,
  totalQuestions: 20,
  students: [
    {
      studentId: 21,
      studentName: '张三',
      status: 'ONLINE',
      answeredCount: 6,
      progressPercent: 30,
      abnormal: true,
      abnormalEventCount: 4,
      maxSeverity: 3,
      lastAbnormalTime: '2026-09-21T10:05:00',
    },
    {
      studentId: 22,
      studentName: '李四',
      status: 'SUBMITTED',
      answeredCount: 20,
      progressPercent: 100,
      abnormal: false,
    },
  ],
};

function dataSource(wrapper: ReturnType<typeof mount>): Array<Record<string, unknown>> {
  return wrapper.findComponent(Table).props('dataSource') as Array<Record<string, unknown>>;
}

describe('ExamMonitorPanel', () => {
  it('四个计数与题目总数一律照后端显示，界面不做加总', () => {
    const wrapper = mount(ExamMonitorPanel, { props: { examIdLabel: 9, overview: OVERVIEW } });
    const text = wrapper.text();

    expect(text).toContain('已进入考试（有答卷）10');
    expect(text).toContain('在线3');
    expect(text).toContain('离线（心跳过期）4');
    expect(text).toContain('异常（严重度≥中）2');
    expect(text).toContain('题目总数（进度分母）20');
    // 提交进度用后端的 submittedCount/totalStudents，此处即 3/10 → 30%
    expect(text).toContain('已交卷 3 / 已进入 10');
  });

  it('学生行保持后端顺序（排序在后端：异常优先），前端不再排一次', () => {
    const wrapper = mount(ExamMonitorPanel, { props: { examIdLabel: 9, overview: OVERVIEW } });

    expect(dataSource(wrapper).map((row) => row.studentId)).toEqual([21, 22]);
    // 反序传入也必须反序显示，证明这里没有本地 sort
    const reversed = mount(ExamMonitorPanel, {
      props: {
        examIdLabel: 9,
        overview: { ...OVERVIEW, students: [...(OVERVIEW.students ?? [])].reverse() },
      },
    });
    expect(dataSource(reversed).map((row) => row.studentId)).toEqual([22, 21]);
  });

  it('考试状态标签取后端 examStatus（1=进行中），文案来自 constants/examStatus', () => {
    const wrapper = mount(ExamMonitorPanel, { props: { examIdLabel: 9, overview: OVERVIEW } });
    const colors = wrapper.findAllComponents(Tag).map((tag) => tag.props('color'));

    expect(wrapper.text()).toContain('进行中');
    expect(colors).toContain('processing');
  });

  it('逐学生进度显示后端的 progressPercent / answeredCount，不换算题数', () => {
    const wrapper = mount(ExamMonitorPanel, { props: { examIdLabel: 9, overview: OVERVIEW } });

    expect(dataSource(wrapper)[0].progressPercent).toBe(30);
    expect(wrapper.text()).toContain('已答 6 / 20');
  });

  it('异常学生按后端 maxSeverity 上色，并透出异常次数', () => {
    const wrapper = mount(ExamMonitorPanel, { props: { examIdLabel: 9, overview: OVERVIEW } });

    expect(wrapper.text()).toContain('最高 高');
    expect(wrapper.text()).toContain('异常 4 次');
    // 直断：`.abnormal-row` 行类绑定（被测行为）必须真实存在，不做字符串兜底
    expect(wrapper.find('.abnormal-row').exists()).toBe(true);
  });

  it('轮询拿到新数据后界面随后端变，不残留上一场快照（props 驱动，无本地副本）', async () => {
    const wrapper = mount(ExamMonitorPanel, { props: { examIdLabel: 9, overview: OVERVIEW } });
    expect(wrapper.text()).toContain('已交卷 3 / 已进入 10');

    await wrapper.setProps({
      overview: { ...OVERVIEW, submittedCount: 9, totalStudents: 10 },
    });

    expect(wrapper.text()).toContain('已交卷 9 / 已进入 10');
    expect(wrapper.text()).not.toContain('已交卷 3 / 已进入 10');
  });

  it('后端返回 0 条答卷：说明是"还没有人进入"，不是取数失败', () => {
    const wrapper = mount(ExamMonitorPanel, {
      props: { examIdLabel: 9, overview: { ...OVERVIEW, totalStudents: 0, students: [] } },
    });

    expect(wrapper.text()).toContain('还没有答卷行');
    expect(wrapper.text()).not.toContain('加载失败');
  });

  it('刷新间隔措辞用「准实时轮询」，不声称实时（spec「不夸大数据新鲜度」）', () => {
    const wrapper = mount(ExamMonitorPanel, { props: { examIdLabel: 9, overview: OVERVIEW } });

    expect(wrapper.text()).toContain(MONITOR_POLLING_HINT);
    expect(wrapper.text().replace(/准实时/g, '')).not.toMatch(/实时/);
  });

  it('点「行为轨迹」把该学生交给页面（监考 → 时间线的跳转契约）', async () => {
    const wrapper = mount(ExamMonitorPanel, { props: { examIdLabel: 9, overview: OVERVIEW } });

    await wrapper
      .findAll('button')
      .find((b) => b.text() === '行为轨迹')
      ?.trigger('click');

    const emitted = wrapper.emitted('view-log');
    expect(emitted).toBeTruthy();
    expect((emitted?.[0]?.[0] as { studentId: number }).studentId).toBe(21);
  });
});

describe('ExamMonitorPanel 本地过滤与手动刷新（polish 特性 2）', () => {
  const FILTER_OVERVIEW: MonitorOverviewResponse = {
    ...OVERVIEW,
    students: [
      { studentId: 21, studentName: '张三', status: 'ONLINE', abnormal: true },
      { studentId: 22, studentName: '李四', status: 'OFFLINE', abnormal: false },
      { studentId: 23, studentName: '王五', status: 'ONLINE', abnormal: false },
    ],
  };

  function mountFiltered(overview: MonitorOverviewResponse = FILTER_OVERVIEW) {
    return mount(ExamMonitorPanel, { props: { examIdLabel: 9, overview } });
  }

  it('姓名 / 学生 ID 搜索框本地过滤学生行（不发起后端请求）', async () => {
    const wrapper = mountFiltered();
    const search = wrapper
      .findAll('input')
      .find((i) => (i.attributes('placeholder') ?? '').includes('姓名 / 学生 ID'));

    await search!.setValue('李四');
    expect(dataSource(wrapper).map((r) => r.studentId)).toEqual([22]);

    await search!.setValue('21'); // 按学生 ID
    expect(dataSource(wrapper).map((r) => r.studentId)).toEqual([21]);

    await search!.setValue('不存在');
    expect(dataSource(wrapper)).toEqual([]);
  });

  it('点「在线」卡过滤 status=ONLINE，再次点击取消过滤（计数仍原样来自 overview）', async () => {
    const wrapper = mountFiltered();
    await wrapper.find('[data-test="stat-card-online"]').trigger('click');
    expect(dataSource(wrapper).map((r) => r.studentId)).toEqual([21, 23]);

    await wrapper.find('[data-test="stat-card-online"]').trigger('click');
    expect(dataSource(wrapper).map((r) => r.studentId)).toEqual([21, 22, 23]);
    // 计数来自 overview，不受过滤影响（前端不推算不变式）
    expect(wrapper.text()).toContain('在线3');
  });

  it('点「异常」卡过滤 abnormal=true 的学生行', async () => {
    const wrapper = mountFiltered();
    await wrapper.find('[data-test="stat-card-abnormal"]').trigger('click');
    expect(dataSource(wrapper).map((r) => r.studentId)).toEqual([21]);
    expect(wrapper.text()).toContain('异常（严重度≥中）2');
  });

  it('点「立即刷新」上抛 refresh 事件（页面据此触发 useQuery refetch）', async () => {
    const wrapper = mountFiltered();
    await wrapper.find('[data-test="refresh-now"]').trigger('click');
    expect(wrapper.emitted('refresh')).toHaveLength(1);
  });

  it('倒计时初始为 MONITOR_POLLING_INTERVAL_MS/1000 秒，显示「下次刷新 Ns」', async () => {
    const wrapper = mountFiltered();
    expect(wrapper.find('[data-test="poll-countdown"]').text()).toContain(
      `${MONITOR_POLLING_INTERVAL_MS / 1000}s`
    );
  });
});

describe('ExamMonitorPanel 词法护栏（m2 红线：间隔不许写字面量）', () => {
  it('倒计时间隔取自 constants/monitor.ts 的 MONITOR_POLLING_INTERVAL_MS，页面不写字面量', () => {
    const source = readFileSync(PANEL_PATH, 'utf-8');
    const clean = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
    expect(clean).toMatch(/MONITOR_POLLING_INTERVAL_MS/);
    // 不得出现 10000 之类的裸字面量秒间隔（m2 变异：若被替换成字面量即红线击红）
    expect(clean).not.toMatch(/refetchInterval\s*[:=]\s*10000\b/);
    expect(clean).not.toMatch(/(\/\/\s*)?10000\s*[};]/);
  });
});
