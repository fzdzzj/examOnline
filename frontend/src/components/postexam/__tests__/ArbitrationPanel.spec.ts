/**
 * 分差仲裁面板（add-subjective-double-blind-grading）。
 *
 * 红线：
 * - 对比卡片必须并排呈现一评/二评分数与评语（仲裁员唯一可见双方信息的角色）；
 * - 仲裁提交必须走 arbitrate（终审回填），成功后刷新清单（结案离场）；
 * - 后端 400（超满分/非待仲裁）错误必须呈现，不算成功。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { message } from 'ant-design-vue';

import ArbitrationPanel from '@/components/postexam/ArbitrationPanel.vue';

const arbitrationList = vi.hoisted(() => vi.fn());
const arbitrate = vi.hoisted(() => vi.fn());
vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));
vi.mock('@/api/axios', () => ({
  arbitrationList,
  arbitrate,
}));

type Draft = { score: string | null; comment: string };

type PanelVm = {
  items: {
    submissionId: number;
    questionId: number;
    studentName: string;
    firstScore: number;
    firstComment?: string;
    secondScore: number;
    secondComment?: string;
    scoreDiff: number;
    maxScore: number;
    status: string;
  }[];
  loading: boolean;
  drafts: Record<string, Draft>;
  setDraft: (
    submissionId: number,
    questionId: number,
    field: 'score' | 'comment',
    value: unknown
  ) => void;
  arbitrateItem: (item: { submissionId: number; questionId: number }) => Promise<boolean>;
};

const ITEM = {
  submissionId: 3001,
  questionId: 55,
  studentName: '学生-A',
  questionNumber: 3,
  firstScore: 2.0,
  firstComment: '未达要点',
  secondScore: 6.0,
  secondComment: '回答完整',
  scoreDiff: 4.0,
  maxScore: 6,
  status: 'ARBITRATION_PENDING',
};

function mountPanel() {
  return mount(ArbitrationPanel, {
    props: { examId: 9 },
  });
}

function vmOf(wrapper: ReturnType<typeof mountPanel>): PanelVm {
  return wrapper.vm as unknown as PanelVm;
}

beforeEach(() => {
  arbitrationList.mockReset();
  arbitrationList.mockResolvedValue([ITEM]);
  arbitrate.mockReset();
  arbitrate.mockResolvedValue({ status: 'ARBITRATED', finalScore: 4.5 });
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.restoreAllMocks();
});

describe('分差仲裁面板', () => {
  it('渲染对比卡片：一评/二评分数与评语并排可见，分差与满分呈现', async () => {
    const wrapper = mountPanel();
    await flushPromises();
    const text = wrapper.text();
    expect(text).toContain('学生-A');
    expect(text).toContain('2');
    expect(text).toContain('6');
    expect(text).toContain('未达要点');
    expect(text).toContain('回答完整');
    expect(text).toContain('4');
  });

  it('清单为空显示空态，不伪装成加载或错误', async () => {
    arbitrationList.mockResolvedValue([]);
    const wrapper = mountPanel();
    await flushPromises();
    expect(wrapper.text()).toContain('暂无待仲裁');
  });

  it('仲裁提交走 arbitrate：body 携带 submissionId/questionId/score/comment，成功后刷新清单', async () => {
    const wrapper = mountPanel();
    await flushPromises();
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    const vm = vmOf(wrapper);
    vm.setDraft(3001, 55, 'score', '4.5');
    vm.setDraft(3001, 55, 'comment', '取中仲裁');

    const ok = await vm.arbitrateItem({ submissionId: 3001, questionId: 55 });

    expect(ok).toBe(true);
    expect(arbitrate).toHaveBeenCalledWith(
      expect.objectContaining({
        path: { examId: 9 },
        body: expect.objectContaining({
          submissionId: 3001,
          questionId: 55,
          score: 4.5,
          comment: '取中仲裁',
        }),
      })
    );
    expect(successSpy).toHaveBeenCalled();
    // 成功后必须刷新清单（结案离场）
    await vi.waitFor(() => expect(arbitrationList).toHaveBeenCalledTimes(2), { timeout: 5000 });
  });

  it('后端 400（超满分）错误必须呈现：不算成功', async () => {
    arbitrate.mockRejectedValueOnce(new Error('仲裁分不得超过本题满分 6'));
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    const wrapper = mountPanel();
    await flushPromises();
    const vm = vmOf(wrapper);
    vm.setDraft(3001, 55, 'score', '9');

    const ok = await vm.arbitrateItem({ submissionId: 3001, questionId: 55 });

    expect(ok).toBe(false);
    expect(successSpy).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('仲裁分不得超过本题满分 6');
  });
});
