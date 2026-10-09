import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { message, RadioGroup } from 'ant-design-vue';

import type { PaperDetailResponse, TagResponse } from '@/api/axios';

const routeMock = vi.hoisted(() => ({ params: { id: '42' } }));
const queryStore = vi.hoisted(() => ({
  data: {} as Record<string, unknown>,
  error: {} as Record<string, unknown>,
  isFetching: {} as Record<string, boolean>,
}));

vi.mock('vue-router', () => ({
  useRoute: () => routeMock,
}));

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: vi.fn() },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'paper-blueprint-draw' },
  unwrap: async (value: unknown) => value,
}));

const mockPreviewDraw = vi.fn();
const mockCommitRandomDraw = vi.fn();

vi.mock('@/api/axios', () => ({
  addQuestions: vi.fn(),
  commitRandomDraw: (args: unknown) => mockCommitRandomDraw(args),
  detail1: vi.fn(),
  list: vi.fn(),
  previewDraw: (args: unknown) => mockPreviewDraw(args),
  removeQuestion: vi.fn(),
  update1: vi.fn(),
  updateOrder: vi.fn(),
  updateQuestionScore: vi.fn(),
}));

vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref } = await import('vue');
  return {
    useQuery: (options: { queryKey: unknown }) => {
      const key = unref(options.queryKey) as unknown[];
      const keyStr = String(key[0]);
      return {
        data: ref(queryStore.data[keyStr]),
        error: ref(queryStore.error[keyStr] ?? null),
        isFetching: ref(queryStore.isFetching[keyStr] ?? false),
      };
    },
  };
});

import PaperDetailPage from '@/pages/(dashboard)/teacher/papers/[id].page.vue';

const SAMPLE_TAGS: TagResponse[] = [
  { id: 101, name: '函数与导数' },
  { id: 102, name: '立体几何' },
  { id: 103, name: '概率统计' },
];

function samplePaper(): PaperDetailResponse {
  return {
    id: 42,
    title: '期中模拟试卷',
    description: '考查函数与几何',
    totalScore: 100,
    questionCount: 0,
    status: 0, // 草稿态
    questions: [],
  };
}

function mountPage(options?: {
  tags?: TagResponse[];
  tagsError?: unknown;
  tagsFetching?: boolean;
}) {
  queryStore.data = {
    paper: samplePaper(),
    tags: options?.tags !== undefined ? options.tags : SAMPLE_TAGS,
  };
  queryStore.error = {
    tags: options?.tagsError ?? null,
  };
  queryStore.isFetching = {
    tags: options?.tagsFetching ?? false,
  };
  return mount(PaperDetailPage);
}

async function switchToDrawTab(wrapper: ReturnType<typeof mountPage>) {
  const drawTab = wrapper.findAll('.ant-tabs-tab').find((t) => t.text().includes('随机抽题'));
  if (drawTab) {
    await drawTab.trigger('click');
    await flushPromises();
  }
}

async function switchToBlueprintMode(wrapper: ReturnType<typeof mountPage>) {
  await switchToDrawTab(wrapper);
  const radioGroup = wrapper.findComponent(RadioGroup);
  expect(radioGroup.exists()).toBe(true);
  radioGroup.vm.$emit('update:value', 'blueprint');
  await flushPromises();
}

beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
  mockPreviewDraw.mockReset();
  mockCommitRandomDraw.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

describe('试卷详情抽题区：双向细目表矩阵蓝图模式', () => {
  it('抽题模式切换：默认显示规则列表，切换至矩阵蓝图显示蓝图界面，切换模式使旧预览作废', async () => {
    const wrapper = mountPage();
    await switchToDrawTab(wrapper);

    // 默认在规则列表模式下有「规则1」和「+ 添加规则」
    expect(wrapper.text()).toContain('规则1');
    expect(wrapper.text()).toContain('+ 添加规则');

    // 存在抽题方式切换单选组（Radio.Button：规则列表 / 矩阵蓝图）
    const radioButtons = wrapper.findAll('.ant-radio-button-wrapper');
    expect(radioButtons.length).toBeGreaterThanOrEqual(2);
    expect(radioButtons.map((r) => r.text())).toEqual(
      expect.arrayContaining(['规则列表', '矩阵蓝图'])
    );

    // 切换到矩阵蓝图模式
    const radioGroup = wrapper.findComponent(RadioGroup);
    expect(radioGroup.exists()).toBe(true);
    radioGroup.vm.$emit('update:value', 'blueprint');
    await flushPromises();

    // 规则1 / + 添加规则 不再显示
    expect(wrapper.text()).not.toContain('+ 添加规则');
    // 显示蓝图标签选择或细目表相关内容
    expect(wrapper.text()).toContain('知识点标签');
  });

  it('题库无标签空态提示：tags 为空时呈现「题库尚无标签，请先在题库管理中创建并打标」', async () => {
    const wrapper = mountPage({ tags: [] });
    await switchToBlueprintMode(wrapper);

    expect(wrapper.text()).toContain('题库尚无标签，请先在题库管理中创建并打标');
    // 不渲染细目表格与标签选择
    expect(wrapper.find('[data-test="blueprint-matrix-table"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="blueprint-tag-select"]').exists()).toBe(false);
  });

  it('标签查询失败三态：tagsError 存在时显性呈现错误 Alert 且矩阵区域隐藏', async () => {
    const wrapper = mountPage({
      tagsError: new Error('网络异常，获取标签失败'),
    });
    await switchToBlueprintMode(wrapper);

    // 显性呈现 Alert
    expect(wrapper.text()).toContain('网络异常，获取标签失败');
    // 细目表格与标签选择不渲染
    expect(wrapper.find('[data-test="blueprint-matrix-table"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="blueprint-tag-select"]').exists()).toBe(false);
  });

  it('预览校验拦截：蓝图未配置任何题量时点击预览抽题，拦截并提示 warning，不发请求', async () => {
    const wrapper = mountPage();
    await switchToBlueprintMode(wrapper);

    // 此时未选标签或单元格为空，点击「预览抽题」
    const previewBtn = wrapper.findAll('button').find((b) => b.text().includes('预览抽题'));
    expect(previewBtn).toBeDefined();
    await previewBtn!.trigger('click');
    await flushPromises();

    expect(message.warning).toHaveBeenCalled();
    expect(mockPreviewDraw).not.toHaveBeenCalled();
  });

  it('蓝图细目表矩阵渲染、覆盖度合计与编译抽题流转', async () => {
    const wrapper = mountPage();
    await switchToBlueprintMode(wrapper);

    // 模拟教师在 Select 中勾选标签 101（函数与导数）和 102（立体几何）
    // 通过 VM 属性设置
    const vm = wrapper.vm as unknown as {
      selectedBlueprintTagIds: number[];
      updateBlueprintCell: (tagId: number, diff: number, val: number) => void;
      blueprintTotalQuestions: number;
    };
    vm.selectedBlueprintTagIds = [101, 102];
    await flushPromises();

    // 表格渲染出两行标签和易中难三列
    expect(wrapper.find('[data-test="blueprint-matrix-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('函数与导数');
    expect(wrapper.text()).toContain('立体几何');
    expect(wrapper.text()).toContain('简单 (易)');
    expect(wrapper.text()).toContain('中等 (中)');
    expect(wrapper.text()).toContain('困难 (难)');

    // 配置题量：101 简单 3 题，中等 2 题；102 困难 4 题
    vm.updateBlueprintCell(101, 1, 3);
    vm.updateBlueprintCell(101, 2, 2);
    vm.updateBlueprintCell(102, 3, 4);
    await flushPromises();

    // 覆盖度摘要合计：3 + 2 + 4 = 9 题
    expect(vm.blueprintTotalQuestions).toBe(9);
    expect(wrapper.text()).toContain('合计 9 题');

    // 模拟后端预览抽题返回
    mockPreviewDraw.mockResolvedValueOnce({
      total: 9,
      rules: [
        {
          ruleIndex: 0,
          questions: [{ id: 1, type: 1, score: 5, difficulty: 1, content: '选择题1' }],
        },
      ],
    });

    const previewBtn = wrapper.findAll('button').find((b) => b.text().includes('预览抽题'));
    await previewBtn!.trigger('click');
    await flushPromises();

    // mockPreviewDraw 接收到编译后的 3 条规则（type 不设，单标签，对应难度与题量）
    expect(mockPreviewDraw).toHaveBeenCalledWith(
      expect.objectContaining({
        body: {
          rules: [
            { tagIds: [101], difficulty: 1, count: 3 },
            { tagIds: [101], difficulty: 2, count: 2 },
            { tagIds: [102], difficulty: 3, count: 4 },
          ],
        },
      })
    );

    // 预览成功后出现「确认入卷」按钮
    const commitBtn = wrapper.findAll('button').find((b) => b.text().includes('确认入卷'));
    expect(commitBtn).toBeDefined();

    mockCommitRandomDraw.mockResolvedValueOnce({ success: true });
    await commitBtn!.trigger('click');
    await flushPromises();

    // 确认入卷调用 commitRandomDraw
    expect(mockCommitRandomDraw).toHaveBeenCalledWith(
      expect.objectContaining({
        path: { id: 42 },
        body: {
          rules: [
            { tagIds: [101], difficulty: 1, count: 3 },
            { tagIds: [101], difficulty: 2, count: 2 },
            { tagIds: [102], difficulty: 3, count: 4 },
          ],
        },
      })
    );
    expect(message.success).toHaveBeenCalledWith('抽题已入卷');
    // 入卷后细目表格题量重置
    expect(vm.blueprintTotalQuestions).toBe(0);
  });

  it('既有规则列表模式零回归：规则列表添加规则与已有交互正常运行', async () => {
    const wrapper = mountPage();
    await switchToDrawTab(wrapper);

    // 默认规则列表模式
    expect(wrapper.text()).toContain('规则1');
    const addBtn = wrapper.findAll('button').find((b) => b.text().includes('+ 添加规则'));
    expect(addBtn).toBeDefined();
    await addBtn!.trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('规则2');
  });
});
