/**
 * 双评盲评面板（add-subjective-double-blind-grading）。
 *
 * 红线：
 * - 行查询必须带 blind=true（脱敏视图由服务端强制，考生姓名与教师评分不可见）；
 * - 提交必须走 blindSubmit（一评/二评自动分流），不得绕过到 saveSubjectiveScore；
 * - blindDone 行禁止重复盲评；后端 400（同教师互斥）错误必须呈现，不算成功。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { message } from 'ant-design-vue';

import BlindReviewPanel from '@/components/postexam/BlindReviewPanel.vue';

const subjectiveRows = vi.hoisted(() => vi.fn());
const blindSubmit = vi.hoisted(() => vi.fn());
vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));
vi.mock('@/api/axios', () => ({
  subjectiveRows,
  blindSubmit,
}));

type Draft = { score: string | null; comment: string };

type PanelVm = {
  rows: { submissionId: number; studentName: string; studentAnswer: string; blindDone?: boolean }[];
  loading: boolean;
  drafts: Record<number, Draft>;
  statusBySubmission: Record<number, string>;
  errorBySubmission: Record<number, string>;
  setDraft: (submissionId: number, field: 'score' | 'comment', value: unknown) => void;
  submitBlind: (row: { submissionId: number; questionId: number }) => Promise<boolean>;
};

/** 服务端脱敏后的行形态（考生 ***、无教师评分）——面板只负责渲染，打码在后端。 */
const MASKED_ROWS = [
  {
    id: 1,
    submissionId: 2001,
    questionId: 55,
    studentName: '考生 ***',
    studentAnswer: 'HTTP 是无状态的协议',
    blindDone: false,
    graded: false,
  },
  {
    id: 2,
    submissionId: 2002,
    questionId: 55,
    studentName: '考生 ***',
    studentAnswer: 'HTTP 是协议',
    blindDone: true,
    graded: false,
  },
];

const QUESTION = { questionId: 55, number: 3, score: 6 };

function mountPanel() {
  return mount(BlindReviewPanel, {
    props: { examId: 9, question: QUESTION },
  });
}

function vmOf(wrapper: ReturnType<typeof mountPanel>): PanelVm {
  return wrapper.vm as unknown as PanelVm;
}

beforeEach(() => {
  subjectiveRows.mockReset();
  subjectiveRows.mockResolvedValue({ rows: MASKED_ROWS, total: MASKED_ROWS.length, graded: 0 });
  blindSubmit.mockReset();
  blindSubmit.mockResolvedValue({ roundType: 'FIRST', status: 'PENDING_SECOND' });
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.restoreAllMocks();
});

describe('双评盲评面板', () => {
  it('行查询必须带 blind=true（脱敏视图由服务端强制，不得漏传）', async () => {
    mountPanel();
    await flushPromises();
    expect(subjectiveRows).toHaveBeenCalledTimes(1);
    expect(subjectiveRows).toHaveBeenCalledWith(
      expect.objectContaining({
        path: { examId: 9 },
        query: expect.objectContaining({ questionId: 55, blind: true }),
      })
    );
  });

  it('渲染打码行：考生 *** 与作答内容可见；blindDone 行禁止重复盲评', async () => {
    const wrapper = mountPanel();
    await flushPromises();
    const text = wrapper.text();
    expect(text).toContain('考生 ***');
    expect(text).toContain('HTTP 是无状态的协议');
    const buttons = wrapper.findAll('button');
    expect(buttons.length).toBeGreaterThanOrEqual(2);
    // 第 1 行可提交，第 2 行 blindDone 禁用
    expect((buttons[0].attributes('disabled') ?? '') === '').toBe(true);
    expect(buttons[1].attributes('disabled')).toBeDefined();
  });

  it('提交走 blindSubmit：body 携带 submissionId/questionId/score/comment', async () => {
    const wrapper = mountPanel();
    await flushPromises();
    const vm = vmOf(wrapper);
    vm.setDraft(2001, 'score', '5');
    vm.setDraft(2001, 'comment', '要点完整');

    const ok = await vm.submitBlind({ submissionId: 2001, questionId: 55 });

    expect(ok).toBe(true);
    await vi.waitFor(() => expect(blindSubmit).toHaveBeenCalledTimes(1));
    expect(blindSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        path: { examId: 9 },
        body: expect.objectContaining({
          submissionId: 2001,
          questionId: 55,
          score: 5,
          comment: '要点完整',
        }),
      })
    );
  });

  it('提交成功后行级状态展示后端裁决结果（PENDING_SECOND/RESOLVED/ARBITRATION_PENDING）', async () => {
    const wrapper = mountPanel();
    await flushPromises();
    const vm = vmOf(wrapper);
    vm.setDraft(2001, 'score', '5');

    await vm.submitBlind({ submissionId: 2001, questionId: 55 });

    expect(vm.statusBySubmission[2001]).toBe('PENDING_SECOND');
    expect(vm.errorBySubmission[2001]).toBeUndefined();
  });

  it('后端 400（同教师互斥）错误必须呈现：不算成功、不清输入', async () => {
    blindSubmit.mockRejectedValueOnce(new Error('同一教师不能同时担任一评与二评'));
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    const wrapper = mountPanel();
    await flushPromises();
    const vm = vmOf(wrapper);
    vm.setDraft(2001, 'score', '5');

    const ok = await vm.submitBlind({ submissionId: 2001, questionId: 55 });

    expect(ok).toBe(false);
    expect(vm.errorBySubmission[2001]).toContain('同一教师不能同时担任一评与二评');
    expect(successSpy).not.toHaveBeenCalled();
  });
});
