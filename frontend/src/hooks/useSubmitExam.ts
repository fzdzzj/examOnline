/**
 * 交卷 hook（阶段 22 第 3 片，tasks.json 任务 4 全部）。
 *
 * ## 分工写清楚（硬约定 3 / 4 的代码化）
 *
 * - **权威在后端**：后端三重幂等（SETNX 锁 + 防重表 + 状态机 CAS）保证「只提交一次，
 *   重复请求返回首次结果」（`ExamSubmitService.doSubmit` 幂等快速路径）。
 *   本 hook **不做**「本地标记已交卷就不再发请求」的替代逻辑——页面刷新 / 重进后
 *   再点交卷，请求照发，后端幂等返回首次结果，这就是配合而非替代。
 * - **前端只做体验层防重**：`submitting` 期间再点/再触发直接忽略（连点只发一次，
 *   见单测），按钮由页面按 `phase` 禁用。这层挡的是 UI 抖动，不是幂等的实现。
 * - **手动与超时自动共用同一个函数**（硬约定 4）：`submitExam(SUBMIT_TYPE.MANUAL)`
 *   与 `submitExam(SUBMIT_TYPE.COUNTDOWN_ZERO)` 走同一段代码，后端以 submitType
 *   区分来源。**归零判定只发生在「本地倒计时走完」这个体验层**——最终超时判定
 *   在后端（`ExamSweepService` 定时扫描 + `buildAnsweringContext` 就地兜底），
 *   前端自动交卷失败也不影响后端兜底收卷（草稿已在，扫描从 Redis 草稿取答案）。
 * - **失败绝不丢答案**：本 hook 不持有、不清空任何答案——答案永远活在页面的
 *   `answers` ref 与 IndexedDB 里，交卷失败后学生可直接重试（`retry()`）。
 */
import { computed, ref, toValue } from 'vue';
import type { ComputedRef, MaybeRefOrGetter, Ref } from 'vue';

import { SUBMIT_TYPE } from '@/constants/studentTaking';
import { ApiError } from '@/api/types';
import type { SubmitResponse } from '@/api/axios';
import type { AnswerMap } from '@/utils/studentTaking';

export type SubmitPhase = 'idle' | 'submitting' | 'submitted' | 'failed';

/** 后端交卷错误的展示语义（码值对齐 `errorMap.ts` 的 BizCode，不发明新码）。 */
export type SubmitErrorKind = 'conflict' | 'rate_limited' | 'server' | 'network' | 'unknown';

export interface SubmitError {
  kind: SubmitErrorKind;
  message: string;
  /** 后端业务码（网络错误时缺省），演示与排查用。 */
  code?: number;
}

export interface UseSubmitExamOptions {
  examId: MaybeRefOrGetter<number>;
  /** 交卷时的最终答案快照（页面持有所有权，本 hook 只读、绝不写回）。 */
  answersSource: MaybeRefOrGetter<AnswerMap>;
  /** 后端已封闭（已交卷/已收卷）或 examId 非法 → 不再发请求（重复交卷无意义）。 */
  suspended: MaybeRefOrGetter<boolean>;
  deps: {
    /** 生产绑生成的 `submit`；单测绑 mock。 */
    submit: (
      examId: number,
      body: { answers: AnswerMap; submitType: string }
    ) => Promise<SubmitResponse | undefined>;
  };
}

export interface SubmitExamView {
  phase: Ref<SubmitPhase>;
  result: Ref<SubmitResponse | null>;
  error: Ref<SubmitError | null>;
  /** 提交在途（页面据此禁用按钮 + 二次确认弹窗防重入）。 */
  submitting: ComputedRef<boolean>;
  /** 唯一提交入口：手动与倒计时归零都调它，只有 submitType 不同。 */
  submitExam(submitType: string): Promise<void>;
  /** 失败后的显式重试入口（学生点按钮触发；不搞自动重试风暴）。 */
  retry(): Promise<void>;
}

/** 后端业务码 → 交卷错误语义（码值来源见 `errorMap.ts`）。 */
export function classifySubmitError(
  code: number | undefined,
  isNetworkLike: boolean
): SubmitErrorKind {
  if (isNetworkLike && code === undefined) return 'network';
  switch (code) {
    case 1012: // STATE_CONFLICT：SETNX 锁竞争等 2s 未收敛（三路竞态极拥挤时）
      return 'conflict';
    case 1008: // TOO_MANY_REQUESTS：交卷限流（500/s）被整流拒绝
      return 'rate_limited';
    case 500: // INTERNAL_ERROR：MQ confirm 失败——后端已把答案暂存草稿等补发
      return 'server';
    case undefined:
      return 'unknown';
    default:
      return 'unknown';
  }
}

export function useSubmitExam(options: UseSubmitExamOptions): SubmitExamView {
  const { examId, answersSource, suspended, deps } = options;

  const phase = ref<SubmitPhase>('idle');
  const result = ref<SubmitResponse | null>(null);
  const error = ref<SubmitError | null>(null);
  const submitting = computed(() => phase.value === 'submitting');

  async function submitExam(submitType: string): Promise<void> {
    // 体验层防重：在途即忽略（连点只发一次）；已成功则直接复述既有结果，
    // 不再发请求——但这不是「本地标记代替后端」：刷新页面后 phase 归零，
    // 再交卷请求照发，后端幂等返回首次结果。
    if (phase.value === 'submitting') return;
    if (phase.value === 'submitted') return;
    const id = toValue(examId);
    if (!(Number.isInteger(id) && id > 0) || toValue(suspended)) return;

    phase.value = 'submitting';
    error.value = null;
    try {
      const data = await deps.submit(id, {
        answers: { ...toValue(answersSource) },
        submitType,
      });
      if (data === undefined || data === null || data.submissionId === undefined) {
        // 信封里 data 缺失属异常路径：不当成功，答案保留可重试
        phase.value = 'failed';
        error.value = { kind: 'unknown', message: '交卷响应缺少结果数据，请重试' };
        return;
      }
      result.value = data;
      phase.value = 'submitted';
    } catch (caught) {
      phase.value = 'failed';
      if (caught instanceof ApiError) {
        // 业务错误：拦截器已把信封转成 ApiError，code 是后端业务码
        error.value = {
          kind: classifySubmitError(caught.code, false),
          code: caught.code,
          message: caught.message || '交卷失败，请重试',
        };
      } else if (caught instanceof Error) {
        // 网络层错误（axios 断线 / 超时）：没有业务码，如实标 network
        error.value = {
          kind: 'network',
          message: caught.message || '网络异常，交卷未送达，请重试',
        };
      } else {
        error.value = { kind: 'unknown', message: '交卷失败，请重试' };
      }
    }
  }

  async function retry(): Promise<void> {
    if (phase.value !== 'failed') return;
    await submitExam(SUBMIT_TYPE.MANUAL);
  }

  return { phase, result, error, submitting, submitExam, retry };
}
