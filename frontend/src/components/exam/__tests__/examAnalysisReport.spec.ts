/**
 * 考试数据分析报告组件用例（add-exam-analysis-report，创新点4 ⭐⭐⭐）。
 *
 * 守住 U-1 三态分离口径在「分析报告」页签的落地：
 * - 查询失败 → 错误 Alert 显性呈现后端 message，数据区隐藏（不伪装成业务空态）；
 * - 成功且 hasTagDimension=false → 提示「题库未打知识点标签」（非错误态）；
 * - 成功且返回数据 → 概览统计卡 / 分数段 Progress / 逐题指标 Table / 关注名单 Table 渲染，
 *   不出现错误 Alert、不出现等分提示；
 * - 加载中 → 加载态；成功且无实考答卷 → 业务空态（非错误）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

import * as api from '@/api/axios';
import { ApiError } from '@/api/types';
import ExamAnalysisReport from '@/components/exam/ExamAnalysisReport.vue';

vi.mock('@/api/axios', () => ({
  analysisReport: vi.fn(),
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

/** useQuery mock：queryKey 变化时重新触发（rejection 落进 error ref） */
vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref, watch } = await import('vue');
  return {
    useQuery: (options: { queryKey?: unknown; queryFn?: () => unknown; enabled?: unknown }) => {
      const data = ref<unknown>(undefined);
      const error = ref<unknown>(null);
      const isFetching = ref(false);
      const run = () => {
        isFetching.value = true;
        Promise.resolve()
          .then(options.queryFn as () => unknown)
          .then((value: unknown) => {
            data.value = value;
            isFetching.value = false;
          })
          .catch((reason: unknown) => {
            error.value = reason;
            isFetching.value = false;
          });
      };
      if (options.queryKey) {
        watch(
          () => unref(options.queryKey),
          () => unref(options.enabled) && run(),
          { deep: true }
        );
      }
      if (unref(options.enabled)) run();
      return { data, error, isFetching, refetch: vi.fn(run) };
    },
  };
});

const REPORT = {
  classOverview: {
    expectedCount: 30,
    actualCount: 28,
    absenceCount: 2,
    averageScore: 72.5,
    passRate: 0.7857,
    scoreBands: [
      { band: '0-59', count: 4 },
      { band: '60-69', count: 6 },
      { band: '70-79', count: 8 },
      { band: '80-89', count: 6 },
      { band: '90-100', count: 4 },
    ],
  },
  questionStats: [
    {
      order: 1,
      type: '单选',
      fullScore: 4,
      averageScore: 3,
      scoreRate: 0.75,
      correctRate: 0.75,
      answeredCount: 28,
    },
  ],
  tagWeakness: [{ tagId: 7, tagName: '力学', scoreRate: 0.58 }],
  hasTagDimension: true,
  focusList: [{ studentId: 101, username: 'stu1', studentName: '张一', totalScore: 48 }],
};

function mountReport(enabled = true) {
  return mount(ExamAnalysisReport, {
    props: { examId: 4, enabled },
  });
}

beforeEach(() => {
  vi.mocked(api.analysisReport).mockResolvedValue(REPORT as never);
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe('考试数据分析报告组件', () => {
  it('成功返回数据：概览统计卡/分数段/逐题指标/知识点/关注名单渲染，无错误、无等分提示', async () => {
    const wrapper = mountReport();
    await flushPromises();
    await flushPromises();

    // 概览统计卡
    expect(wrapper.text()).toContain('应考');
    expect(wrapper.text()).toContain('30');
    expect(wrapper.text()).toContain('实考');
    expect(wrapper.text()).toContain('28');
    expect(wrapper.text()).toContain('缺席');
    expect(wrapper.text()).toContain('及格率');
    // 分数段文本
    expect(wrapper.text()).toContain('分数段分布');
    expect(wrapper.text()).toContain('90-100分');
    // 逐题指标
    expect(wrapper.text()).toContain('逐题指标');
    expect(wrapper.text()).toContain('单选');
    // 知识点薄弱
    expect(wrapper.text()).toContain('知识点薄弱');
    expect(wrapper.text()).toContain('力学');
    // 关注名单
    expect(wrapper.text()).toContain('张一');
    // 无错误、无等分提示、无「未打知识点标签」提示
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('题库未打知识点标签');
    expect(wrapper.text()).not.toContain('暂无成绩数据');
  });

  it('知识点维度缺失：hasTagDimension=false 且 tagWeakness 空 → 提示「题库未打知识点标签」，非错误态', async () => {
    vi.mocked(api.analysisReport).mockResolvedValue({
      ...REPORT,
      hasTagDimension: false,
      tagWeakness: [],
    } as never);
    const wrapper = mountReport();
    await flushPromises();
    await flushPromises();

    expect(wrapper.text()).toContain('题库未打知识点标签');
    expect(wrapper.find('[role="alert"]').exists()).toBe(true);
    // 空态文案不属于「查询失败错误」
    expect(wrapper.find('[role="alert"]').text()).toContain('题库未打知识点标签');
  });

  it('查询失败：错误 Alert 显性呈现后端 message，数据区隐藏（不伪装成业务空态）', async () => {
    vi.mocked(api.analysisReport).mockRejectedValue(
      new ApiError(500, '试卷快照解析失败', undefined, 500)
    );
    const wrapper = mountReport();
    await flushPromises();
    await flushPromises();

    const alert = wrapper.find('[role="alert"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('试卷快照解析失败');
    // 数据区隐藏：概览/表格/进度条不渲染
    expect(wrapper.text()).not.toContain('应考');
    expect(wrapper.text()).not.toContain('逐题指标');
    // 不伪装成空态
    expect(wrapper.text()).not.toContain('暂无成绩数据');
  });

  it('加载态：尚未返回时呈现加载中', async () => {
    let resolveFn: (v: unknown) => void;
    vi.mocked(api.analysisReport).mockReturnValue(
      new Promise((resolve) => {
        resolveFn = resolve;
      }) as never
    );
    const wrapper = mountReport();
    await flushPromises();

    expect(wrapper.text()).toContain('加载中');
    resolveFn!(REPORT as never);
    await flushPromises();
    await flushPromises();
    expect(wrapper.text()).not.toContain('加载中');
  });

  it('成功但无实考答卷：actualCount=0 → 业务空态「暂无成绩数据」而非错误', async () => {
    vi.mocked(api.analysisReport).mockResolvedValue({
      ...REPORT,
      classOverview: { ...REPORT.classOverview, actualCount: 0 },
      questionStats: [],
      focusList: [],
    } as never);
    const wrapper = mountReport();
    await flushPromises();
    await flushPromises();

    expect(wrapper.text()).toContain('暂无成绩数据');
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
  });

  it('disabled 时不发起请求', async () => {
    const wrapper = mountReport(false);
    await flushPromises();
    expect(vi.mocked(api.analysisReport)).not.toHaveBeenCalled();
    expect(wrapper.text()).not.toContain('加载中');
  });
});
