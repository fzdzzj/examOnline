/**
 * 主观题 Enter 流水线打分 + 快捷给分/评语模板（polish 特性 3）。
 *
 * 红线：
 * - Enter 提交必须复用 createGradingFlow 提交流（含乐观锁冲突、行级错误归位），
 *   不得绕过 flow 直调 saveSubjectiveScore（m3a）——
 *   冲突/失败后焦点必须停留当前行，不移动（m3b）；
 * - 提交成功后焦点自动移至下一行分数框（按当前 rows 行序）。
 * - 快捷给分 / 评语模板只填入输入框，不自动提交。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';

import type { SubjectiveGradeRow, SubjectiveQuestionItem } from '@/api/axios';
import { ApiError } from '@/api/types';
import SubjectiveGradingPanel from '@/components/postexam/SubjectiveGradingPanel.vue';

const saveSubjectiveScore = vi.hoisted(() => vi.fn());
vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));
vi.mock('@/api/axios', () => ({
  saveSubjectiveScore,
  subjectiveRows: vi.fn(),
}));

type PanelVm = {
  onScoreEnter: (row: SubjectiveGradeRow) => Promise<boolean>;
  onSubmit: (row: SubjectiveGradeRow) => Promise<boolean>;
  focusScoreInput: (id: number | undefined) => void;
  setEditing: (row: SubjectiveGradeRow, field: 'score' | 'comment', value: unknown) => void;
  scoreInputRefs: Record<number, HTMLElement | null>;
  quickScoreTags: { label: string; value: string }[];
  commentTemplates: { key: string; label: string; value: string }[];
  drafts: Record<number, { score: string | null; comment: string }>;
};

const QUESTION: SubjectiveQuestionItem = { questionId: 55, number: 3, score: 10 };

function rowOf(submissionId: number, studentId: number, studentName: string): SubjectiveGradeRow {
  return {
    id: submissionId,
    submissionId,
    questionId: 55,
    studentId,
    studentName,
    studentAnswer: '作答',
    version: 0,
    graded: false,
  };
}

const ROWS: SubjectiveGradeRow[] = [
  rowOf(1001, 1, '张三'),
  rowOf(1002, 2, '李四'),
  rowOf(1003, 3, '王五'),
];

function mountPanel(rows: SubjectiveGradeRow[] = ROWS) {
  return mount(SubjectiveGradingPanel, {
    props: { examId: 9, question: QUESTION, rows, total: rows.length, graded: 0 },
  });
}

/** <script setup> 非显式 defineExpose 的绑定经 wrapper.vm 取需按 PanelVm 收窄（仅测试用）。 */
function vmOf(wrapper: ReturnType<typeof mountPanel>): PanelVm {
  return wrapper.vm as unknown as PanelVm;
}

/** 拦截真实分数框 input 的 focus，记录被聚焦的 DOM 元素（用途见下方 focus 用例）。 */
function recordFocusCalls(): HTMLElement[] {
  const calls: HTMLElement[] = [];
  vi.spyOn(HTMLElement.prototype, 'focus').mockImplementation(function (this: HTMLElement) {
    calls.push(this);
  });
  return calls;
}

/** 按顺序取各行的分数框 input（v-for 渲染顺序即 rows 顺序）。 */
function scoreInputs(wrapper: ReturnType<typeof mount>): Element[] {
  return wrapper.findAll('.ant-input-number-input').map((w) => w.element);
}

beforeEach(() => {
  saveSubjectiveScore.mockReset();
  saveSubjectiveScore.mockResolvedValue(undefined);
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.restoreAllMocks();
});

describe('Enter 流水线打分', () => {
  it('Enter 提交复用 createGradingFlow：走 saveSubjectiveScore（带 expectedVersion），不绕过 flow', async () => {
    const vm = vmOf(mountPanel());
    vm.setEditing(ROWS[0], 'score', '9');

    await vm.onScoreEnter(ROWS[0]);

    expect(saveSubjectiveScore).toHaveBeenCalledTimes(1);
    expect(saveSubjectiveScore).toHaveBeenCalledWith(
      expect.objectContaining({
        body: expect.objectContaining({ submissionId: 1001, questionId: 55, expectedVersion: 0 }),
      })
    );
  });

  it('提交成功后焦点自动移至下一行分数框（按当前行序 1001 → 1002）', async () => {
    const wrapper = mountPanel();
    const vm = vmOf(wrapper);
    const focusCalls = recordFocusCalls();
    vm.setEditing(ROWS[0], 'score', '9');

    await vm.onScoreEnter(ROWS[0]);

    // 成功 → 焦点落在第 2 行（row1，submissionId=1002）的分数框
    expect(focusCalls).toHaveLength(1);
    expect(focusCalls[0]).toBe(scoreInputs(wrapper)[1]);
  });

  it('提交失败（普通错误）时焦点停留当前行，不前进到下一行', async () => {
    saveSubjectiveScore.mockRejectedValueOnce(new Error('批改分数不得超过本题满分 10'));
    const wrapper = mountPanel();
    const vm = vmOf(wrapper);
    const focusCalls = recordFocusCalls();
    vm.setEditing(ROWS[0], 'score', '9');

    await vm.onScoreEnter(ROWS[0]);

    // 失败 → 焦点停留在第 1 行（当前行）的分数框
    expect(focusCalls).toHaveLength(1);
    expect(focusCalls[0]).toBe(scoreInputs(wrapper)[0]);
  });

  it('乐观锁冲突时焦点停留当前行，且复用 flow 冲突语义（不自动重试、不算成功）', async () => {
    // 用 1012 业务码模拟后端乐观锁冲突（GRADING_CONFLICT_CODE）
    saveSubjectiveScore.mockRejectedValueOnce(conflict1012());
    const wrapper = mountPanel();
    const vm = vmOf(wrapper);
    const focusCalls = recordFocusCalls();
    vm.setEditing(ROWS[0], 'score', '9');

    const ok = await vm.onScoreEnter(ROWS[0]);

    expect(ok).toBe(false);
    expect(saveSubjectiveScore).toHaveBeenCalledTimes(1);
    expect(focusCalls).toHaveLength(1);
    expect(focusCalls[0]).toBe(scoreInputs(wrapper)[0]);
  });
});

/** 构造 1012 业务码的 ApiError（后端批改乐观锁冲突：409 + 业务码 1012）。 */
function conflict1012(): Error {
  return new ApiError(1012, '批改已被他人更新，请刷新后重试', undefined, 409);
}

describe('快捷给分 Tag 与评语模板', () => {
  it('满分 / 及格 / 零分按本题满分派生（满分=10、及格=60%四舍五入到0.5、零分=0）', () => {
    const vm = vmOf(mountPanel());
    expect(vm.quickScoreTags).toEqual([
      { label: '满分', value: '10' },
      { label: '及格', value: '6' },
      { label: '零分', value: '0' },
    ]);
  });

  it('点击快捷给分只填入分数输入框，不自动触发提交', () => {
    const vm = vmOf(mountPanel());
    const before = saveSubjectiveScore.mock.calls.length;

    vm.setEditing(ROWS[0], 'score', '10');

    expect(vm.drafts[1001].score).toBe('10');
    expect(saveSubjectiveScore).toHaveBeenCalledTimes(before);
  });

  it('评语模板至少含肯定/部分正确/未达要点三类，点击只填评语框不提交', () => {
    const vm = vmOf(mountPanel());
    const labels = vm.commentTemplates.map((t: { label: string }) => t.label);
    expect(labels).toContain('肯定');
    expect(labels).toContain('部分正确');
    expect(labels).toContain('未达要点');

    const before = saveSubjectiveScore.mock.calls.length;
    vm.setEditing(ROWS[1], 'comment', '回答完整，要点齐全');

    expect(vm.drafts[1002].comment).toBe('回答完整，要点齐全');
    expect(saveSubjectiveScore).toHaveBeenCalledTimes(before);
  });
});
