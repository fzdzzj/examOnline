/**
 * 标签随机抽题的状态流转（硬约定 5：随机算法在后端——
 * 前端只配置规则、展示抽中结果、允许重抽或确认入卷，绝不复现随机逻辑）。
 *
 * 设计成依赖注入的状态机工厂：previewDraw / commitDraw 由调用方注入
 * （生产传 gen:api 生成客户端封装，单测传 mock），便于对「预览 → 重抽 → 确认/失败」
 * 的流转做纯逻辑单测。
 */
import { reactive } from 'vue';

import type { RandomDrawPreviewResponse, RandomDrawRequest } from '@/api/axios';

export type DrawRule = RandomDrawRequest['rules'];

export type DrawPhase = 'idle' | 'previewing' | 'previewed' | 'committing' | 'committed';

export interface DrawFlowState {
  phase: DrawPhase;
  preview: RandomDrawPreviewResponse | null;
  /** 后端拒绝原因（如「满足抽题条件的题目不足…」）或网络错误文案 */
  error: string | null;
}

export interface DrawFlowDeps {
  /** 预览抽题（POST /api/papers/random-draw/preview，不落库） */
  previewDraw: (rules: DrawRule) => Promise<RandomDrawPreviewResponse | undefined>;
  /** 确认入卷（POST /api/papers/{id}/questions/random，后端重抽并落库） */
  commitDraw: (paperId: number, rules: DrawRule) => Promise<unknown>;
}

function toMessage(error: unknown): string {
  return error instanceof Error ? error.message : '抽题失败，请稍后重试';
}

export function createDrawFlow(deps: DrawFlowDeps) {
  const state = reactive<DrawFlowState>({ phase: 'idle', preview: null, error: null });

  /** 最近一次成功预览所用的规则：重抽与确认入卷都基于它，避免预览/入卷规则漂移。 */
  let lastRules: DrawRule | null = null;

  async function preview(rules: DrawRule): Promise<void> {
    lastRules = rules;
    state.phase = 'previewing';
    state.error = null;
    try {
      state.preview = (await deps.previewDraw(rules)) ?? null;
      state.phase = state.preview ? 'previewed' : 'idle';
    } catch (error) {
      state.preview = null;
      state.error = toMessage(error);
      state.phase = 'idle';
    }
  }

  /** 重抽：用上一次的规则再请求一次后端（随机性在后端，每次结果都可能不同）。 */
  async function redraw(): Promise<void> {
    if (lastRules) {
      await preview(lastRules);
    }
  }

  /**
   * 确认入卷：把同一份规则交给后端重新抽取并落库。
   * 失败时回到 previewed（预览仍在，教师可调整规则或重试）。
   */
  async function commit(paperId: number): Promise<boolean> {
    if (!lastRules) {
      return false;
    }
    state.phase = 'committing';
    state.error = null;
    try {
      await deps.commitDraw(paperId, lastRules);
      state.phase = 'committed';
      state.preview = null;
      lastRules = null;
      return true;
    } catch (error) {
      state.error = toMessage(error);
      state.phase = 'previewed';
      return false;
    }
  }

  /** 教师修改规则后由页面调用：作废旧预览与旧规则。 */
  function invalidate(): void {
    lastRules = null;
    state.preview = null;
    state.error = null;
    state.phase = 'idle';
  }

  return { state, preview, redraw, commit, invalidate };
}

export type DrawFlow = ReturnType<typeof createDrawFlow>;
