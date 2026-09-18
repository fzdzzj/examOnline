import { describe, expect, it, vi } from 'vitest';

import { createDrawFlow } from '@/hooks/useRandomDraw';
import type { RandomDrawPreviewResponse, RandomDrawRequest } from '@/api/axios';

const RULES: RandomDrawRequest['rules'] = [
  { type: 1, count: 2 },
  { tagIds: [7], count: 1 },
];

function previewPayload(total: number): RandomDrawPreviewResponse {
  return {
    rules: [{ ruleIndex: 0, count: total, questions: [] }],
    total,
  };
}

describe('随机抽题状态流转（mock 生成客户端；随机算法在后端，前端只管流转）', () => {
  it('预览成功：idle → previewing → previewed，结果落 state', async () => {
    const previewDraw = vi.fn().mockResolvedValue(previewPayload(3));
    const flow = createDrawFlow({
      previewDraw,
      commitDraw: vi.fn(),
    });

    expect(flow.state.phase).toBe('idle');
    const promise = flow.preview([...RULES]);
    expect(flow.state.phase).toBe('previewing');
    await promise;

    expect(flow.state.phase).toBe('previewed');
    expect(flow.state.preview?.total).toBe(3);
    expect(flow.state.error).toBeNull();
    expect(previewDraw).toHaveBeenCalledWith([...RULES]);
  });

  it('预览被后端拒绝（如题量不足 400）：回到 idle 并透出错误文案', async () => {
    const flow = createDrawFlow({
      previewDraw: vi
        .fn()
        .mockRejectedValue(
          new Error('满足抽题条件的题目不足：第 1 条规则需要 2 题，仅匹配到 1 题')
        ),
      commitDraw: vi.fn(),
    });

    await flow.preview([...RULES]);
    expect(flow.state.phase).toBe('idle');
    expect(flow.state.preview).toBeNull();
    expect(flow.state.error).toContain('题目不足');
  });

  it('重抽：使用与上一次完全相同的规则再次请求后端', async () => {
    const previewDraw = vi.fn().mockResolvedValue(previewPayload(2));
    const flow = createDrawFlow({ previewDraw, commitDraw: vi.fn() });

    await flow.preview([...RULES]);
    await flow.redraw();

    expect(previewDraw).toHaveBeenCalledTimes(2);
    expect(previewDraw).toHaveBeenNthCalledWith(2, [...RULES]);
    expect(flow.state.phase).toBe('previewed');
  });

  it('确认入卷成功：previewed → committing → committed，预览清空、规则作废', async () => {
    const commitDraw = vi.fn().mockResolvedValue(undefined);
    const flow = createDrawFlow({
      previewDraw: vi.fn().mockResolvedValue(previewPayload(2)),
      commitDraw,
    });

    await flow.preview([...RULES]);
    const promise = flow.commit(42);
    expect(flow.state.phase).toBe('committing');
    const ok = await promise;

    expect(ok).toBe(true);
    expect(commitDraw).toHaveBeenCalledWith(42, [...RULES]);
    expect(flow.state.phase).toBe('committed');
    expect(flow.state.preview).toBeNull();
    expect(flow.state.error).toBeNull();
  });

  it('确认入卷失败：回到 previewed（预览保留，教师可重抽或改规则）', async () => {
    const flow = createDrawFlow({
      previewDraw: vi.fn().mockResolvedValue(previewPayload(2)),
      commitDraw: vi.fn().mockRejectedValue(new Error('试卷已锁定（快照已生成），不允许修改')),
    });

    await flow.preview([...RULES]);
    const ok = await flow.commit(42);

    expect(ok).toBe(false);
    expect(flow.state.phase).toBe('previewed');
    expect(flow.state.error).toContain('已锁定');
    expect(flow.state.preview).not.toBeNull();
  });

  it('未预览直接确认：拒绝提交（不允许跳过预览入卷）', async () => {
    const commitDraw = vi.fn();
    const flow = createDrawFlow({ previewDraw: vi.fn(), commitDraw });

    const ok = await flow.commit(42);
    expect(ok).toBe(false);
    expect(commitDraw).not.toHaveBeenCalled();
  });

  it('教师修改规则后 invalidate：旧预览与旧规则作废，回到 idle', async () => {
    const flow = createDrawFlow({
      previewDraw: vi.fn().mockResolvedValue(previewPayload(2)),
      commitDraw: vi.fn(),
    });

    await flow.preview([...RULES]);
    flow.invalidate();

    expect(flow.state.phase).toBe('idle');
    expect(flow.state.preview).toBeNull();
    // 作废后重抽不再发起请求（无 lastRules）
    await flow.redraw();
    expect(flow.state.phase).toBe('idle');
  });
});
