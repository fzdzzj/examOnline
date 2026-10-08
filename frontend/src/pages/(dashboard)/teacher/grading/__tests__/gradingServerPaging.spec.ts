/**
 * 主观题批改服务端分页与筛选（add-subjective-grading-pagination）前端护栏与行为用例。
 *
 * 词法护栏（变异还原可红）：
 * - 面板源码不得再含客户端全量筛选链（filteredRows 对 props.rows 的 filter）与静态客户端分页
 *   （pageSize: 10）——大班全量行不再进前端内存；
 * - 面板 refreshRow 冲突回填必须携带 submissionId 查询参数单行取数，不再全量拉取后 find；
 * - 批改页 useQuery queryKey 必须含 page/pageSize/onlyUngraded/nameFilter（服务端驱动）。
 *
 * 行为用例：
 * - 信封驱动计数：已批 N/M 展示用信封 graded/total，而非客户端计数；
 * - 分页交互：表格翻页/改页大小 → update:page / update:pageSize 上抛（服务端驱动）；
 * - 筛选交互：姓名输入/只看未批改 → update:nameFilter / update:onlyUngraded 上抛；
 * - 冲突回填单行取数：409/1012 后 refreshRow 以 submissionId 单行拉取并通知父级刷新；
 * - 批改页 queryFn 携带服务端分页与筛选参数（page/size/onlyUngraded/name），翻页/筛选后参数随动。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Input, InputNumber, Select, Table, message } from 'ant-design-vue';

import { ApiError } from '@/api/types';
import type { SubjectiveGradeRow, SubjectiveQuestionItem } from '@/api/axios';
import { GRADING_CONFLICT_CODE } from '@/constants/postExam';

const here = path.dirname(fileURLToPath(import.meta.url));
const PAGE_SOURCE = path.join(here, '..', 'index.page.vue');
const PANEL_SOURCE = path.join(
  here,
  '..',
  '..',
  '..',
  '..',
  '..',
  'components',
  'postexam',
  'SubjectiveGradingPanel.vue'
);

const queryStore = vi.hoisted(() => ({ data: {} as Record<string, unknown> }));
const capturedQueries = vi.hoisted(() => ({
  options: [] as Array<{ queryKey: unknown; queryFn: () => Promise<unknown> }>,
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'server-paging' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  saveSubjectiveScore: vi.fn(),
  subjectiveRows: vi.fn(),
}));

vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref } = await import('vue');
  return {
    useQuery: (options: { queryKey: unknown; queryFn: () => Promise<unknown> }) => {
      capturedQueries.options.push(options);
      const key = unref(options.queryKey) as unknown[];
      const bucket = key[1] === 'questions' ? 'questions' : key[1] === 'rows' ? 'rows' : 'exams';
      return { data: ref(queryStore.data[bucket]), isFetching: ref(false), refetch: vi.fn() };
    },
    // 页面接入逐卷重判后需要 query client；本文件的既有用例不断言它，属测试基建适配
    useQueryClient: () => ({ invalidateQueries: vi.fn() }),
  };
});

import { unref } from 'vue';

import SubjectiveGradingPanel from '@/components/postexam/SubjectiveGradingPanel.vue';
import TeacherGradingPage from '@/pages/(dashboard)/teacher/grading/index.page.vue';
import * as api from '@/api/axios';

const QUESTION: SubjectiveQuestionItem = {
  questionId: 55,
  number: 1,
  content: '简答题',
  score: 10,
  totalStudents: 25,
  gradedStudents: 17,
};

function row(overrides: Partial<SubjectiveGradeRow>): SubjectiveGradeRow {
  return {
    id: 1,
    submissionId: 1001,
    questionId: 55,
    studentId: 7,
    studentName: '张三',
    studentAnswer: '作答内容',
    score: undefined,
    version: 0,
    graded: false,
    ...overrides,
  };
}

function mountPanel(props: Record<string, unknown> = {}) {
  return mount(SubjectiveGradingPanel, {
    props: {
      examId: 1,
      question: QUESTION,
      rows: [
        row({}),
        row({
          submissionId: 1002,
          studentId: 8,
          studentName: '李四',
          score: 8,
          version: 1,
          graded: true,
        }),
      ],
      total: 25,
      graded: 17,
      loading: false,
      page: 1,
      pageSize: 10,
      nameFilter: '',
      onlyUngraded: false,
      ...props,
    },
  });
}

// ==================== 词法护栏 ====================

describe('主观题批改服务端分页词法护栏', () => {
  it('面板不再含客户端全量筛选链（filteredRows）', () => {
    expect(readFileSync(PANEL_SOURCE, 'utf-8')).not.toContain('filteredRows');
  });

  it('面板不再含静态客户端分页（pageSize: 10）', () => {
    expect(readFileSync(PANEL_SOURCE, 'utf-8')).not.toContain('pageSize: 10');
  });

  it('面板 refreshRow 含 submissionId 查询参数（冲突回填单行取数）', () => {
    expect(readFileSync(PANEL_SOURCE, 'utf-8')).toMatch(/query:\s*\{\s*questionId,\s*submissionId/);
  });

  it('批改页 useQuery queryKey 含 page/pageSize/onlyUngraded/nameFilter（服务端驱动）', () => {
    expect(readFileSync(PAGE_SOURCE, 'utf-8')).toMatch(
      /'rows',[\s\S]{0,200}page\.value,\s*pageSize\.value,\s*onlyUngraded\.value,\s*nameFilter\.value/
    );
  });
});

// ==================== 面板行为（信封驱动计数与分页交互） ====================

describe('批改面板信封驱动计数与分页交互', () => {
  beforeEach(() => {
    vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
    vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
    vi.mocked(api.subjectiveRows).mockReset();
    vi.mocked(api.saveSubjectiveScore).mockReset();
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  // 第三抖专项续：本用例同样在全量并行下越过 5000ms 默认上限（诊断轮实测 3465ms，
  // 后续全量轮越界），与文件末条用例同因同处置——只抬超时上限，断言零改动。
  it('已批进度展示信封 graded/total，而非客户端计数', () => {
    const wrapper = mountPanel();
    expect(wrapper.text()).toContain('已批 17/25');
    // 客户端口径是 1/2（2 行中 1 行已批）——信封驱动后不得再出现
    expect(wrapper.text()).not.toContain('已批 1/2');
  }, 15000);

  it('表格翻页与改页大小上抛 update:page / update:pageSize（服务端驱动）', async () => {
    const wrapper = mountPanel();
    // ant Table change 事件携带 TablePaginationConfig（与真实翻页行为一致）
    wrapper.findComponent(Table).vm.$emit('change', { current: 2, pageSize: 20 });
    await flushPromises();
    expect(wrapper.emitted('update:page')).toEqual([[2]]);
    expect(wrapper.emitted('update:pageSize')).toEqual([[20]]);
  });

  it('姓名筛选与只看未批改上抛服务端筛选状态', async () => {
    const wrapper = mountPanel();
    await wrapper.findComponent(Input).vm.$emit('update:value', '张');
    expect(wrapper.emitted('update:nameFilter')).toEqual([['张']]);
    await wrapper.find('input[type="checkbox"]').setValue(true);
    expect(wrapper.emitted('update:onlyUngraded')).toEqual([[true]]);
  });

  it('409/1012 冲突后 refreshRow 以 submissionId 单行拉取并通知父级刷新', async () => {
    vi.mocked(api.saveSubjectiveScore).mockRejectedValueOnce(
      new ApiError(GRADING_CONFLICT_CODE, '批改已被他人更新，请刷新后重试', undefined, 409)
    );
    const latest = row({ score: 8, comment: '他人已批', version: 1, graded: true });
    vi.mocked(api.subjectiveRows).mockResolvedValueOnce({
      rows: [latest],
      total: 1,
      graded: 1,
    } as never);

    const wrapper = mountPanel({ rows: [row({})] });
    await wrapper.findComponent(InputNumber).vm.$emit('update:value', 9);
    await flushPromises();
    // ant-design 会给两字按钮渲染「提 交」字距，按去空白后的文本匹配
    const submitButton = wrapper
      .findAll('button')
      .find((button) => button.text().replace(/\s/g, '') === '提交');
    await submitButton?.trigger('click');
    await flushPromises();

    expect(api.saveSubjectiveScore).toHaveBeenCalledTimes(1);
    // 单行取数：查询携带 submissionId（替代全量拉取后 find）
    expect(api.subjectiveRows).toHaveBeenCalledTimes(1);
    const call = vi.mocked(api.subjectiveRows).mock.calls[0][0];
    expect(call.query.submissionId).toBe(1001);
    expect(call.query.questionId).toBe(55);
    // 冲突可见（铁律 1）且已通知父级拉最新行（教师重看的载体）
    expect(wrapper.text()).toContain('已被他人批改');
    expect(wrapper.emitted('refreshed')).toBeTruthy();
  });
});

// ==================== 批改页：queryFn 携带服务端分页与筛选参数 ====================

const ENDED_EXAM = { id: 7, title: '待判分考试', status: 2 };

function bodyText(): string {
  return document.body.textContent ?? '';
}

async function selectExam(wrapper: ReturnType<typeof mount>, examId: number): Promise<void> {
  wrapper.findComponent(Select).vm.$emit('update:value', examId);
  await flushPromises();
}

function rowsQueryOptions() {
  return capturedQueries.options.find((options) => {
    const key = unref(options.queryKey) as unknown[];
    return Array.isArray(key) && key[1] === 'rows';
  });
}

describe('批改页服务端分页参数', () => {
  beforeEach(() => {
    vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
    vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
    vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
    vi.spyOn(message, 'info').mockImplementation(() => ({}) as never);
    capturedQueries.options = [];
    vi.mocked(api.subjectiveRows).mockReset();
    vi.mocked(api.saveSubjectiveScore).mockReset();
    queryStore.data = {
      exams: [ENDED_EXAM],
      questions: [QUESTION],
      rows: {
        rows: [
          row({}),
          row({
            submissionId: 1002,
            studentId: 8,
            studentName: '李四',
            score: 8,
            version: 1,
            graded: true,
          }),
        ],
        total: 30,
        graded: 3,
      },
    };
    vi.mocked(api.subjectiveRows).mockResolvedValue({
      rows: [
        row({}),
        row({
          submissionId: 1002,
          studentId: 8,
          studentName: '李四',
          score: 8,
          version: 1,
          graded: true,
        }),
      ],
      total: 30,
      graded: 3,
    } as never);
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  // 第三抖专项（fix-frontend-list-truncation-family）：全量套件并行下本用例实测 5034ms，
  // 贴着 vitest 默认 5000ms 上限（定向单跑约 0.4s）。仅抬高超时上限吸收 CPU 争抢，
  // 断言与交互零改动。
  it('queryFn 携带 page/size/onlyUngraded/name，翻页与筛选后参数随动且切筛选回第 1 页', async () => {
    const wrapper = mount(TeacherGradingPage, { attachTo: document.body });
    await selectExam(wrapper, ENDED_EXAM.id);
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '去批改')
      ?.trigger('click');
    await flushPromises();

    const options = rowsQueryOptions();
    expect(options).toBeTruthy();
    await options?.queryFn();
    expect(api.subjectiveRows).toHaveBeenCalledWith(
      expect.objectContaining({
        path: { examId: ENDED_EXAM.id },
        query: expect.objectContaining({
          questionId: 55,
          page: 1,
          size: 10,
          onlyUngraded: false,
          name: undefined,
        }),
      })
    );

    // 信封驱动计数（端到端）：已批 3/30 来自信封，而非客户端对 2 行计数
    expect(bodyText()).toContain('已批 3/30');

    // 面板上抛翻页 → 服务端参数随动
    wrapper.findComponent(SubjectiveGradingPanel).vm.$emit('update:page', 2);
    await flushPromises();
    await options?.queryFn();
    expect(vi.mocked(api.subjectiveRows).mock.calls[1]?.[0]).toMatchObject({
      query: expect.objectContaining({ page: 2 }),
    });

    // 面板上抛筛选 → 参数随动且翻回第 1 页（换筛选看第 2 页没有意义）
    wrapper.findComponent(SubjectiveGradingPanel).vm.$emit('update:nameFilter', '张');
    await flushPromises();
    await options?.queryFn();
    expect(vi.mocked(api.subjectiveRows).mock.calls[2]?.[0]).toMatchObject({
      query: expect.objectContaining({ name: '张', page: 1 }),
    });
  }, 15000);
});
