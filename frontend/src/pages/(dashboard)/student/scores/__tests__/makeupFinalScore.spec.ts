/**
 * 学生侧补考最终成绩三态用例（add-makeup-final-score-frontend 阶段 4）。
 *
 * 口径同 myScore：正常 / 待发布（后端 400「成绩待发布」）/ 复核中（reviewing=true 隐藏分数）。
 * 关键负向断言：即使后端响应残留分数（违反契约的越权形态），reviewing=true 时前端
 * 也不得渲染任何分数字——证明「复核中藏分」是后端裁决，前端零本地推断、零本地合并。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { RadioGroup, message } from 'ant-design-vue';

import StudentScoresPage from '@/pages/(dashboard)/student/scores/index.page.vue';
import { ApiError } from '@/api/types';

const invalidateQueries = vi.hoisted(() => vi.fn());

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: invalidateQueries },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  apply: vi.fn(),
  myExams: vi.fn(),
  myMakeupFinalScore: vi.fn(),
  myScore: vi.fn(),
}));

import { myMakeupFinalScore, myScore } from '@/api/axios';

/**
 * useQuery mock：真实调用页面传入的 queryFn（含 isNotPublishedError 归一分支），
 * 而不是像 applyReviewAlert.spec 那样按 key 塞静态数据——三态里的「待发布」
 * 必须走「后端 400 → 页面 catch → null → not-published」这条真实链路。
 */
vi.mock('@tanstack/vue-query', async () => {
  const { ref } = await import('vue');
  return {
    useQuery: (options: { queryFn?: () => unknown }) => {
      const data = ref<unknown>(undefined);
      // fix-frontend-query-failure-states：页面解构 error 做三态分离，mock 须提供该字段
      //（待发布走 queryFn 内 isNotPublishedError 归一，不会落进 error）
      const error = ref<unknown>(null);
      if (options.queryFn) {
        Promise.resolve()
          .then(options.queryFn)
          .then((value: unknown) => {
            data.value = value;
          })
          .catch((reason: unknown) => {
            error.value = reason;
          });
      }
      return { data, error, isFetching: ref(false), refetch: vi.fn() };
    },
  };
});

const mockedMakeupFinal = vi.mocked(myMakeupFinalScore);
const mockedMyScore = vi.mocked(myScore);

/** 切到「补考最终成绩」口径 */
async function switchToMakeup(wrapper: ReturnType<typeof mount>): Promise<void> {
  const radioGroup = wrapper.findComponent(RadioGroup);
  expect(radioGroup.exists()).toBe(true);
  radioGroup.vm.$emit('update:value', 'makeup');
  await flushPromises();
}

beforeEach(() => {
  // hey-api SDK 函数的返回类型是客户端响应联合，测试桩统一按仓库惯例 as never 收窄
  mockedMyScore.mockResolvedValue({ examId: 3, totalScore: 61, reviewing: false } as never);
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

describe('student/scores 补考最终成绩三态', () => {
  it('正常：后端返回 finalScore 原样渲染（前端只透传，不本地合并）', async () => {
    mockedMakeupFinal.mockResolvedValue({
      examId: 7,
      studentId: 21,
      finalScore: 77.5,
      reviewing: false,
    } as never);
    const wrapper = mount(StudentScoresPage);
    await flushPromises();
    await switchToMakeup(wrapper);

    const text = wrapper.text();
    expect(text).toContain('最终成绩（后端沿主考家族合并）');
    expect(text).toContain('77.5');
    // 口径说明如实呈现：合并在后端、历史成绩保留
    expect(text).toContain('后端沿主考家族按考试配置规则合并，历史成绩保留不覆盖');
  });

  it('待发布：后端 400「成绩待发布」→ 复用「成绩未发布」态，不弹错误、不显示 0 分', async () => {
    mockedMakeupFinal.mockRejectedValue(new ApiError(400, '成绩待发布', undefined, 400));
    const wrapper = mount(StudentScoresPage);
    await flushPromises();
    await switchToMakeup(wrapper);

    const text = wrapper.text();
    expect(text).toContain('成绩未发布');
    expect(text).not.toContain('77.5');
    expect(text).toContain('不显示 0 分或空白分数');
  });

  it('复核中：reviewing=true 隐藏分数；响应残留分数也不渲染；复核申请入口不出现（仅常规口径提供）', async () => {
    // 越权形态：reviewing=true 却带着 finalScore —— 前端只看 reviewing，绝不渲染该值
    mockedMakeupFinal.mockResolvedValue({
      examId: 7,
      studentId: 21,
      finalScore: 88,
      reviewing: true,
    } as never);
    const wrapper = mount(StudentScoresPage);
    await flushPromises();
    await switchToMakeup(wrapper);

    const text = wrapper.text();
    expect(text).toContain('成绩复核中，暂不可见');
    expect(text).toContain('复核进行中，处理完成后成绩恢复显示');
    // 关键负向断言：残留的 88 与任何分数都不得出现
    expect(text).not.toContain('88');
    expect(text).not.toContain('61');
    // 补考口径只做展示：申请复核入口不出现
    const applyButton = wrapper.findAll('button').find((b) => b.text() === '申请成绩复核');
    expect(applyButton).toBeUndefined();
  });
});
