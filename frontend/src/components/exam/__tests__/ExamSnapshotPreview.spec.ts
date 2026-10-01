/**
 * 试卷快照只读预览的空态 / 错误态 / 数据态三分支（阶段 21 缺口 2）。
 *
 * 之所以值得单测：后端对「未发布」用 404 表达（`考试尚未发布或快照不存在`），
 * 若前端把它一律渲染成错误，教师会以为系统坏了；反过来把真错误渲染成空态更糟。
 * 两个方向在这里各断言一次。
 */
import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { Alert, Empty, Table } from 'ant-design-vue';

import type { ExamSnapshotResponse } from '@/api/axios';
import ExamSnapshotPreview from '@/components/exam/ExamSnapshotPreview.vue';

/** 结构与后端 PaperSnapshotService.serialize / ExamSnapshotService.serializeExam 一致。 */
const PAPER = {
  paperId: 4,
  title: '期中卷',
  totalScore: 100,
  questionCount: 2,
  generatedTime: '2026-09-21T10:00:00',
  questions: [
    {
      number: 1,
      questionId: 11,
      type: 1,
      content: '1+1=?',
      choices: ['1', '2', '3'],
      correctAnswer: 'B',
      score: 60,
    },
    {
      number: 2,
      questionId: 12,
      type: 4,
      content: '简述快照的作用',
      choices: null,
      correctAnswer: '略',
      score: 40,
    },
  ],
};

const EXAM = {
  examId: 9,
  title: '期中考试',
  paperId: 4,
  courseId: null,
  classId: 3,
  startTime: '2026-09-22T09:00:00',
  endTime: '2026-09-22T11:00:00',
  durationMinutes: 60,
  allowLateMinutes: 5,
  antiCheatConfig: { switchScreen: true, forbidCopy: false },
  generatedTime: '2026-09-21T10:00:00',
};

const SNAPSHOT: ExamSnapshotResponse = {
  id: 77,
  examId: 9,
  version: 1,
  exam: EXAM,
  paper: PAPER,
  createdTime: '2026-09-21T10:00:00',
};

/** 两张表：第 1 张是考试配置键值对，第 2 张是快照题目（顺序由组件模板决定）。 */
function tablesOf(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAllComponents(Table);
}

function questionRows(wrapper: ReturnType<typeof mount>): Array<Record<string, unknown>> {
  const tables = tablesOf(wrapper);
  expect(tables.length).toBe(2);
  return (tables[1].props('dataSource') ?? []) as Array<Record<string, unknown>>;
}

describe('ExamSnapshotPreview', () => {
  it('有快照：题目按后端行序原样渲染，分值不重算', () => {
    const wrapper = mount(ExamSnapshotPreview, { props: { snapshot: SNAPSHOT } });
    const rows = questionRows(wrapper);

    expect(rows).toHaveLength(2);
    expect(rows[0]).toMatchObject({ number: 1, content: '1+1=?', score: 60, correctAnswer: 'B' });
    // 选项为 null 的简答题不得被前端塞进假选项
    expect(rows[1].choices).toBeUndefined();
    // 快照内总分是后端给的那份，不是前端把 60+40 加出来的
    expect(wrapper.text()).toContain('100');
    expect(wrapper.text()).toContain('快照副本');
  });

  it('考试配置快照原样列成键值对（含 null 值，前端不隐去、不补默认）', () => {
    const wrapper = mount(ExamSnapshotPreview, { props: { snapshot: SNAPSHOT } });
    const fields = (tablesOf(wrapper)[0].props('dataSource') ?? []) as Array<{
      key: string;
      value: string;
    }>;

    expect(fields.map((f) => f.key)).toContain('durationMinutes');
    expect(fields.find((f) => f.key === 'durationMinutes')?.value).toBe('60');
    expect(fields.find((f) => f.key === 'courseId')?.value).toBe('—');
    expect(fields.find((f) => f.key === 'classId')?.value).toBe('3');
  });

  it('未发布（后端 404）：渲染空态说明，且不渲染错误 Alert', () => {
    const wrapper = mount(ExamSnapshotPreview, {
      props: { notFound: true, error: null, snapshot: null },
    });

    expect(wrapper.findComponent(Empty).exists()).toBe(true);
    expect(wrapper.text()).toContain('尚未发布');
    expect(wrapper.findComponent(Alert).exists()).toBe(false);
  });

  it('真错误（如 403/500）：渲染错误 Alert，且不伪装成"尚未发布"的空态', () => {
    const wrapper = mount(ExamSnapshotPreview, {
      props: { error: '没有权限执行该操作（Forbidden）', notFound: false, snapshot: null },
    });

    const alert = wrapper.findComponent(Alert);
    expect(alert.exists()).toBe(true);
    expect(alert.props('type')).toBe('error');
    expect(wrapper.text()).toContain('没有权限执行该操作');
    expect(wrapper.text()).not.toContain('尚未发布');
  });

  it('快照解析不出题目（paper 结构异常）：走"快照内没有题目"分支而不是崩', () => {
    const wrapper = mount(ExamSnapshotPreview, {
      props: { snapshot: { id: 1, examId: 9, version: 1, exam: null, paper: 'not-an-object' } },
    });

    expect(wrapper.findAllComponents(Table)).toHaveLength(0); // 两张表都不该渲染
    expect(wrapper.text()).toContain('快照内没有题目');
    expect(wrapper.text()).toContain('后端 exam 栏为空');
  });
});
