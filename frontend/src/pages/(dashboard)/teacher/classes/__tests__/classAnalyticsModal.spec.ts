/**
 * 教师班级学情分析弹层用例（add-student-learning-analytics 前端交互）。
 *
 * 覆盖：班级学情入口打开 → 渲染概览（学生数/考试数）、班级均分走势、知识点掌握度进度、
 * Top 薄弱知识点 Tag；查询失败显性 Alert（三态分离）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { message } from 'ant-design-vue';

import * as api from '@/api/axios';
import ClassesPage from '@/pages/(dashboard)/teacher/classes/index.page.vue';

const invalidateQueries = vi.hoisted(() => vi.fn());

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: invalidateQueries },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  classAnalytics: vi.fn(),
  create4: vi.fn(),
  delete3: vi.fn(),
  joinStudent: vi.fn(),
  listStudents: vi.fn(),
  page3: vi.fn(),
  removeStudent: vi.fn(),
  transfer: vi.fn(),
  update3: vi.fn(),
}));

vi.mock('@tanstack/vue-query', async () => {
  const { ref } = await import('vue');
  return {
    useQuery: (options: { queryKey: unknown; queryFn: () => unknown; enabled?: unknown }) => {
      const data = ref<unknown>(undefined);
      const error = ref<unknown>(null);
      const run = () => {
        if (options.enabled && typeof options.enabled === 'object' && 'value' in options.enabled) {
          // enabled computed ref——仅在打开弹层（true）时取数
          const enabled = options.enabled as { value: boolean };
          if (!enabled.value) return;
        }
        Promise.resolve()
          .then(options.queryFn)
          .then((value: unknown) => {
            data.value = value;
          })
          .catch((reason: unknown) => {
            error.value = reason;
          });
      };
      run();
      return {
        data,
        error,
        isFetching: ref(false),
        refetch: vi.fn().mockImplementation(run),
      };
    },
  };
});

const mocked = vi.mocked(api);
const mockedClassAnalytics = vi.mocked(api.classAnalytics);

const sampleClass = [
  { id: 7, name: '高一(1)班', courseId: 1, createdBy: 3, createdTime: '2026-10-01T10:00:00' },
];

const sampleAnalytics = {
  classId: 7,
  className: '高一(1)班',
  studentCount: 30,
  examCount: 2,
  trend: [
    { examId: 1, examTitle: '月考一', examTime: '2026-10-01T09:00:00', averageScore: 60.5 },
    { examId: 2, examTitle: '月考二', examTime: '2026-10-08T09:00:00', averageScore: 72 },
  ],
  tagMastery: [
    { tagId: 1, tagName: '物理', masteryRate: 0.5 },
    { tagId: 2, tagName: '数学', masteryRate: 0.9 },
  ],
  weakTags: [{ tagId: 1, tagName: '物理', masteryRate: 0.5 }],
} as never;

beforeEach(() => {
  mocked.page3.mockResolvedValue({ list: sampleClass } as never);
  mockedClassAnalytics.mockReset();
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

async function openAnalytics(wrapper: ReturnType<typeof mount>): Promise<void> {
  mockedClassAnalytics.mockResolvedValueOnce(sampleAnalytics);
  const btns = wrapper.findAll('button');
  const btn = btns.find((b) => b.text() === '学情分析');
  expect(btn).toBeDefined();
  await btn!.trigger('click');
  await flushPromises();
}

/** 直接读取组件 vm 的公开数据字段（Modal 内容经 teleport 不在 wrapper DOM 内，按其数据断言渲染口径）。 */
function dataOf<T>(wrapper: ReturnType<typeof mount>, key: string): T {
  return (wrapper.vm as unknown as Record<string, T>)[key];
}

describe('班级学情分析弹层（teacher/classes）', () => {
  it('打开学情分析：请求班级学情并装载 data（学生数/考试数/走势/掌握度/薄弱）', async () => {
    const wrapper = mount(ClassesPage);
    await flushPromises();
    await openAnalytics(wrapper);

    // 请求已发出（Modal 打开触发取数）
    expect(mockedClassAnalytics).toHaveBeenCalled();
    const data = dataOf<{
      classId?: number;
      studentCount?: number;
      examCount?: number;
      trend?: { examTitle?: string }[];
      tagMastery?: { tagName?: string }[];
      weakTags?: { tagName?: string }[];
    }>(wrapper, 'classAnalyticsData');
    expect(data).toBeDefined();
    expect(data.classId).toBe(7);
    expect(data.studentCount).toBe(30);
    expect(data.examCount).toBe(2);
    expect(data.trend?.length).toBe(2);
    expect(data.trend?.[0].examTitle).toBe('月考一');
    expect(data.tagMastery?.length).toBe(2);
    expect(data.weakTags?.[0].tagName).toBe('物理');
  });

  it('查询失败：错误态字段非空（三态分离），不装载数据', async () => {
    mockedClassAnalytics.mockRejectedValueOnce(new Error('班级学情查询失败'));
    const wrapper = mount(ClassesPage);
    await flushPromises();
    const btns = wrapper.findAll('button');
    const btn = btns.find((b) => b.text() === '学情分析');
    await btn!.trigger('click');
    await flushPromises();

    const errText = dataOf<string | null>(wrapper, 'analyticsErrorText');
    expect(errText).toContain('班级学情查询失败');
    expect(dataOf<unknown>(wrapper, 'classAnalyticsData')).toBeUndefined();
  });
});
