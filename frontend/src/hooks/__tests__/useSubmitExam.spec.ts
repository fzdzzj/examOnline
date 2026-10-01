/**
 * 交卷 hook 单测（阶段 22 第 3 片，tasks.json 任务 4）。
 *
 * 必须覆盖（agent-prompt 回报第 6 段点名）：
 * - **连点只发一次**：submitting 期间再触发被忽略；
 * - **失败不清空答案**：hook 不持有答案，失败后页面 answers 原样、retry 可重发；
 * - **超时与手动走同一提交路径**：两者都调 deps.submit，仅 submitType 不同；
 * - **重复交卷语义**：后端幂等返回首次结果（无专门错误码）；前端在已成功后
 *   不再发请求（体验层），STATE_CONFLICT(1012) 时按码给出「稍候重试」语义。
 *
 * 全部假 promise / 微任务驱动，无真 sleep。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { effectScope, ref } from 'vue';
import type { Ref } from 'vue';

import { useSubmitExam } from '@/hooks/useSubmitExam';
import type { SubmitExamView } from '@/hooks/useSubmitExam';
import { ApiError } from '@/api/types';
import type { SubmitResponse } from '@/api/axios';
import type { AnswerMap } from '@/utils/studentTaking';

const successResponse: SubmitResponse = {
  submissionId: 501,
  examId: 1,
  studentId: 9,
  status: 2,
  submitTime: '2026-09-22T21:00:00',
  submitType: 1,
};

interface Harness {
  answers: Ref<AnswerMap>;
  suspended: Ref<boolean>;
  submit: ReturnType<typeof vi.fn>;
  hook: SubmitExamView;
  scope: ReturnType<typeof effectScope>;
}

function setup(submitImpl?: (...args: unknown[]) => Promise<SubmitResponse | undefined>): Harness {
  const answers = ref<AnswerMap>({ '11': 'A', '12': 'T' });
  const suspended = ref(false);
  const submit = vi.fn(submitImpl ?? (async (): Promise<SubmitResponse> => successResponse));
  const scope = effectScope();
  let hook!: SubmitExamView;
  scope.run(() => {
    hook = useSubmitExam({
      examId: () => 1,
      answersSource: () => answers.value,
      suspended: () => suspended.value,
      deps: { submit: submit as never },
    });
  });
  return { answers, suspended, submit, hook, scope };
}

beforeEach(() => {
  vi.restoreAllMocks();
});
afterEach(() => {
  vi.useRealTimers();
});

describe('useSubmitExam：连点与路径同源', () => {
  it('连点只发一次：submitting 期间第二次触发被忽略', async () => {
    let resolveFirst!: (v: SubmitResponse) => void;
    const h = setup(
      () =>
        new Promise<SubmitResponse>((resolve) => {
          resolveFirst = resolve;
        })
    );

    const first = h.hook.submitExam('MANUAL');
    expect(h.hook.submitting.value).toBe(true);
    // 连点 / 超时触发同时到达：都被在途守卫挡下
    await h.hook.submitExam('MANUAL');
    expect(h.submit).toHaveBeenCalledTimes(1);

    resolveFirst(successResponse);
    await first;
    expect(h.hook.phase.value).toBe('submitted');
    expect(h.hook.result.value?.submissionId).toBe(501);
    expect(h.submit).toHaveBeenCalledTimes(1);
  });

  it('手动与超时自动走同一提交路径：都经 deps.submit，仅 submitType 不同', async () => {
    // 同一路径的结构证明：两次 setup 用同一个 mock 函数，手动与 COUNTDOWN_ZERO
    // 都只能从 submitExam 进（hook 没有第二个请求入口），仅 body.submitType 不同。
    const sharedSubmit = vi.fn(async (): Promise<SubmitResponse> => successResponse);
    const make = (suspended = false) => {
      const scope = effectScope();
      let hook!: SubmitExamView;
      scope.run(() => {
        hook = useSubmitExam({
          examId: () => 1,
          answersSource: () => ({ '11': 'A' }),
          suspended: () => suspended,
          deps: { submit: sharedSubmit as never },
        });
      });
      return hook;
    };

    await make().submitExam('MANUAL');
    await make().submitExam('COUNTDOWN_ZERO');
    expect(sharedSubmit).toHaveBeenCalledTimes(2);
    const calls = sharedSubmit.mock.calls as unknown as Array<[number, { submitType: string }]>;
    expect(calls.map((c) => c[1].submitType)).toEqual(['MANUAL', 'COUNTDOWN_ZERO']);
  });

  it('已成功后再触发不发请求（体验层复述既有结果；跨会话重进仍由后端幂等兜底）', async () => {
    const h = setup();
    await h.hook.submitExam('MANUAL');
    expect(h.hook.phase.value).toBe('submitted');

    await h.hook.submitExam('MANUAL');
    expect(h.submit).toHaveBeenCalledTimes(1);
    expect(h.hook.result.value?.submissionId).toBe(501);
  });
});

describe('useSubmitExam：失败保留答案、按码提示', () => {
  it('STATE_CONFLICT(1012)：kind=conflict，答案原样保留，retry 重发请求', async () => {
    let calls = 0;
    const h = setup(async () => {
      calls += 1;
      if (calls === 1) throw new ApiError(1012, '正在提交中，请稍候重试');
      return successResponse;
    });

    await h.hook.submitExam('MANUAL');
    expect(h.hook.phase.value).toBe('failed');
    expect(h.hook.error.value?.kind).toBe('conflict');
    expect(h.hook.error.value?.code).toBe(1012);
    // 硬约定 5：答案不被 hook 改写（内容与引用都在页面手里）
    expect(h.answers.value).toEqual({ '11': 'A', '12': 'T' });

    await h.hook.retry();
    expect(h.submit).toHaveBeenCalledTimes(2);
    expect(h.hook.phase.value).toBe('submitted');
  });

  it('网络错误：kind=network，可重试', async () => {
    let calls = 0;
    const h = setup(async () => {
      calls += 1;
      if (calls === 1) throw new Error('network down');
      return successResponse;
    });

    await h.hook.submitExam('MANUAL');
    expect(h.hook.error.value?.kind).toBe('network');

    await h.hook.retry();
    expect(h.hook.phase.value).toBe('submitted');
  });

  it('重复交卷的呈现：后端幂等（返回首次结果）在前端表现为同一份 result，不发新请求', async () => {
    // 说明：后端没有「重复交卷」错误码（已核实 ExamSubmitService 幂等快速路径），
    // 重复请求会拿到首次结果。同一会话里前端用 pending/submitted 状态挡住重复点击
    // （上面的连点用例）；跨会话（刷新重进）phase 归零，请求照发、后端幂等兜底。
    const h = setup();
    await h.hook.submitExam('MANUAL');
    await h.hook.submitExam('MANUAL');
    expect(h.submit).toHaveBeenCalledTimes(1);
    expect(h.hook.result.value?.submissionId).toBe(501);
    expect(h.hook.error.value).toBeNull();
  });

  it('suspended（已封闭 / examId 非法）时不发请求', async () => {
    const h = setup();
    h.suspended.value = true;
    await h.hook.submitExam('MANUAL');
    expect(h.submit).not.toHaveBeenCalled();
    expect(h.hook.phase.value).toBe('idle');
  });

  it('响应缺 data：不当成功，标记失败可重试', async () => {
    const h = setup(async () => undefined);
    await h.hook.submitExam('MANUAL');
    expect(h.hook.phase.value).toBe('failed');
    expect(h.hook.result.value).toBeNull();
  });
});
