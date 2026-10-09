/**
 * 切屏事件离线持久化队列单测（阶段 4 · 先红后绿）。
 *
 * 核心功能：
 * 1. 入队 enqueue、查看 peek、出队 dequeue、清空 clear；
 * 2. 同 examId 隔离；
 * 3. 内存实现与原生降级一致。
 */
import { describe, expect, it } from 'vitest';

import { createMemoryBehaviorQueue } from '@/utils/behaviorQueue';

describe('behaviorQueue 存储行为', () => {
  it('enqueue 与 peek：先进先出返回当前考试队列', async () => {
    const queue = createMemoryBehaviorQueue();
    await queue.enqueue({
      examId: 1,
      eventType: 'SWITCH_SCREEN',
      durationMs: 1500,
    });
    await queue.enqueue({
      examId: 1,
      eventType: 'WINDOW_BLUR',
      durationMs: 800,
    });

    const items = await queue.peek(1);
    expect(items).toHaveLength(2);
    expect(items[0].eventType).toBe('SWITCH_SCREEN');
    expect(items[0].durationMs).toBe(1500);
    expect(items[1].eventType).toBe('WINDOW_BLUR');
    expect(items[1].durationMs).toBe(800);
  });

  it('dequeue：上报成功后从队列移除指定记录', async () => {
    const queue = createMemoryBehaviorQueue();
    await queue.enqueue({
      examId: 1,
      eventType: 'SWITCH_SCREEN',
      durationMs: 1200,
    });

    const items = await queue.peek(1);
    expect(items).toHaveLength(1);

    await queue.dequeue(1, items[0]);
    const after = await queue.peek(1);
    expect(after).toHaveLength(0);
  });

  it('examId 隔离：不同考试互不影响', async () => {
    const queue = createMemoryBehaviorQueue();
    await queue.enqueue({
      examId: 1,
      eventType: 'SWITCH_SCREEN',
      durationMs: 1000,
    });
    await queue.enqueue({
      examId: 2,
      eventType: 'WINDOW_BLUR',
      durationMs: 2000,
    });

    expect(await queue.peek(1)).toHaveLength(1);
    expect(await queue.peek(2)).toHaveLength(1);
    expect((await queue.peek(1))[0].durationMs).toBe(1000);
    expect((await queue.peek(2))[0].durationMs).toBe(2000);
  });
});
