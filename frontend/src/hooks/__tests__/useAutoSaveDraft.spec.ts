/**
 * 30s 自动保存 + IndexedDB 引擎单测（阶段 22 第 2 片）。
 *
 * 必须覆盖的行为（对应 `tasks.json` 任务 3 与任务 2 接缝 step）：
 * - 保存频率纪律：定时 30s、防抖 30s，「连续作答只产生一次请求」（不做按键即保存）；
 * - 每次作答同步写 IndexedDB（本地写入不受 30s 节流）；
 * - 保存失败（断线）→ unsynced + dirty 保留 → 恢复后自动同步；
 * - 版本冲突（accepted=false）→ 学服务端版本再保存；
 * - 归零锁定 + 有未同步内容 → pendingSync（「待同步」），网络恢复即保存——
 *   这是把最终答案推成服务器草稿、交给后端超时扫描收卷的路径（不在此发交卷请求）。
 *
 * 全部用 `vi.useFakeTimers()` 推进时间，**禁止真 sleep**（agent-prompt 实施第 6 步同款纪律）。
 * 存储注入内存实现（jsdom 无 IndexedDB，探针已核实 `window.indexedDB === undefined`）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { effectScope, ref } from 'vue';
import type { Ref } from 'vue';

import { AUTOSAVE_DEBOUNCE_MS, AUTOSAVE_INTERVAL_MS } from '@/constants/studentTaking';
import { useAutoSaveDraft } from '@/hooks/useAutoSaveDraft';
import type { AutoSavePayload, AutoSaveResult } from '@/hooks/useAutoSaveDraft';
import { createMemoryDraftStorage } from '@/utils/draftStorage';
import type { AnswerMap } from '@/utils/studentTaking';

interface Harness {
  answers: Ref<AnswerMap>;
  saveDraft: ReturnType<typeof vi.fn>;
  storageSave: ReturnType<typeof vi.spyOn>;
  locked: Ref<boolean>;
  suspended: Ref<boolean>;
  hook: ReturnType<typeof useAutoSaveDraft>;
  scope: ReturnType<typeof effectScope>;
  dispose(): void;
}

function setup(
  saveDraftImpl?: (examId: number, payload: AutoSavePayload) => Promise<AutoSaveResult | undefined>
): Harness {
  const answers = ref<AnswerMap>({});
  const locked = ref(false);
  const suspended = ref(false);
  const saveDraft = vi.fn(
    saveDraftImpl ??
      (async (_examId: number, payload: AutoSavePayload): Promise<AutoSaveResult> => ({
        accepted: true,
        version: payload.version,
        savedTime: '2026-09-21T10:00:00',
      }))
  );
  const storage = createMemoryDraftStorage();
  const storageSave = vi.spyOn(storage, 'save');
  const scope = effectScope();
  let hook!: ReturnType<typeof useAutoSaveDraft>;
  scope.run(() => {
    hook = useAutoSaveDraft({
      examId: () => 1,
      answersSource: () => answers.value,
      locked: () => locked.value,
      suspended: () => suspended.value,
      deps: { saveDraft, storage },
    });
  });
  return {
    answers,
    saveDraft,
    storageSave,
    locked,
    suspended,
    hook,
    scope,
    dispose: () => scope.stop(),
  };
}

/** 播种 + 作答一步到位：answerEntries 逐条作答（模拟学生每次按键换一个新对象）。 */
function answer(h: Harness, key: string, value: string): void {
  h.answers.value = { ...h.answers.value, [key]: value };
  h.hook.notifyAnswered();
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('常量护栏（防止退化成高频保存）', () => {
  it('定时周期与防抖窗口都不得小于后端 30s 设计假设', () => {
    expect(AUTOSAVE_INTERVAL_MS).toBeGreaterThanOrEqual(30_000);
    expect(AUTOSAVE_DEBOUNCE_MS).toBeGreaterThanOrEqual(30_000);
  });
});

describe('保存频率纪律（硬约定 6）', () => {
  it('作答后 30s 定时器触发保存一次，version = 播种版本 + 1', async () => {
    const h = setup();
    h.hook.seed({ version: 3, savedAt: null, answers: {} });
    answer(h, '101', 'A');

    expect(h.saveDraft).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);

    expect(h.saveDraft).toHaveBeenCalledTimes(1);
    expect(h.saveDraft.mock.calls[0][1].version).toBe(4);
    expect(h.hook.status.value).toBe('synced');
  });

  it('防抖不产生高频请求：连续作答 5 次，30s 防抖到期只发一次保存', async () => {
    const h = setup();
    h.hook.seed({ version: null, savedAt: null, answers: {} });
    for (let i = 0; i < 5; i++) {
      answer(h, `q${i}`, 'A');
      // 每秒作答一次，防抖计时器不断被重置
      await vi.advanceTimersByTimeAsync(1_000);
    }

    // 防抖窗口未满：一次请求都没有
    expect(h.saveDraft).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);
    expect(h.saveDraft).toHaveBeenCalledTimes(1);
    // 请求体带的是全部答案（防抖期间最后一次作答的内容）
    expect(h.saveDraft.mock.calls[0][1].answers).toEqual({
      q0: 'A',
      q1: 'A',
      q2: 'A',
      q3: 'A',
      q4: 'A',
    });
  });

  it('连续输入不断重置防抖时，30s 定时器兜底保存（12 次作答 85s 内只有 2 次请求）', async () => {
    const h = setup();
    h.hook.seed({ version: null, savedAt: null, answers: {} });
    // 每 5s 作答一次共 12 次（t=0..55），防抖永远到不了期，只能靠定时器
    for (let i = 0; i < 12; i++) {
      answer(h, `q${i}`, 'A');
      await vi.advanceTimersByTimeAsync(5_000);
    }
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS);

    // t=30 与 t=60 两拍定时器各保存一次；防抖在 t=85 触发时已不脏
    expect(h.saveDraft.mock.calls.length).toBe(2);
  });

  it('后端请求频率上限与输入次数无关：作答只写 IndexedDB，不发请求', async () => {
    const h = setup();
    h.hook.seed({ version: 1, savedAt: null, answers: {} });
    answer(h, 'a', 'A');
    answer(h, 'b', 'B');
    answer(h, 'c', 'C');

    // 每次作答都同步写 IndexedDB（本地操作不受 30s 节流）：
    // seed 对齐一次 + 每次作答一次 = 4
    expect(h.storageSave).toHaveBeenCalledTimes(4);
    expect(h.saveDraft).not.toHaveBeenCalled();
  });
});

describe('保存结果与 version 协议', () => {
  it('保存成功：version 对齐响应、IndexedDB 收到后端确认状态', async () => {
    const h = setup(async (_id, payload) => ({
      accepted: true,
      version: payload.version + 10,
      savedTime: '2026-09-21T11:22:33',
    }));
    h.hook.seed({ version: 1, savedAt: null, answers: {} });
    answer(h, '101', 'A');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);

    expect(h.hook.lastSavedAt.value).toBe('2026-09-21T11:22:33');
    // IndexedDB 最后一次写入带的是后端确认的 version（12：发出 2，mock 回 2+10）与 savedTime
    const lastCall = h.storageSave.mock.calls.at(-1);
    expect(lastCall?.[1].version).toBe(12);
    expect(lastCall?.[1].savedAt).toBe('2026-09-21T11:22:33');

    // 下一拍：从确认版本继续递增（12 + 1）
    answer(h, '102', 'B');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
    expect(h.saveDraft.mock.calls[1][1].version).toBe(13);
  });

  it('保存失败（断线）：unsynced + dirty 保留，下一定时拍自动重试成功', async () => {
    let failFirst = true;
    const h = setup(async (_id, payload) => {
      if (failFirst) {
        failFirst = false;
        throw new Error('network down');
      }
      return { accepted: true, version: payload.version, savedTime: 't' };
    });
    h.hook.seed({ version: null, savedAt: null, answers: {} });
    answer(h, '101', 'A');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);

    expect(h.hook.status.value).toBe('unsynced');

    // 答案没有被清掉；第二拍重试成功
    await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
    expect(h.hook.status.value).toBe('synced');
    expect(h.saveDraft).toHaveBeenCalledTimes(2);
    // 重试用同一 version（后端语义：同版本重复保存视为客户端重试，允许覆盖）
    expect(h.saveDraft.mock.calls[0][1].version).toBe(1);
    expect(h.saveDraft.mock.calls[1][1].version).toBe(1);
  });

  it('版本冲突（accepted=false）：学服务端存量版本，下次保存从服务端版本 + 1 收敛', async () => {
    const h = setup(async (_id, payload) => {
      if (payload.version < 7) {
        return { accepted: false, version: 7, savedTime: '2026-09-21T12:00:00' };
      }
      return { accepted: true, version: payload.version, savedTime: '2026-09-21T12:00:30' };
    });
    h.hook.seed({ version: 3, savedAt: null, answers: {} });
    answer(h, '101', 'A');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);

    expect(h.hook.status.value).toBe('conflict');
    // 学生内容没被覆盖：继续作答仍会走保存
    answer(h, '102', 'B');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);

    expect(h.hook.status.value).toBe('synced');
    expect(h.saveDraft.mock.calls[1][1].version).toBe(8);
    expect(h.saveDraft.mock.calls[1][1].answers).toEqual({ '101': 'A', '102': 'B' });
  });
});

describe('断线恢复与归零待同步（任务 2 接缝 step）', () => {
  it('unsynced 期间网络恢复（online 事件）→ 立即同步', async () => {
    let offline = true;
    const h = setup(async (_id, payload) => {
      if (offline) throw new Error('network down');
      return { accepted: true, version: payload.version, savedTime: 't' };
    });
    h.hook.seed({ version: null, savedAt: null, answers: {} });
    answer(h, '101', 'A');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
    expect(h.hook.status.value).toBe('unsynced');

    offline = false;
    window.dispatchEvent(new Event('online'));
    await vi.advanceTimersByTimeAsync(0);

    expect(h.hook.status.value).toBe('synced');
    expect(h.saveDraft).toHaveBeenCalledTimes(2);
  });

  it('归零锁定且有未同步内容 → pendingSync（待同步）；网络恢复即保存草稿', async () => {
    let offline = true;
    const h = setup(async (_id, payload) => {
      if (offline) throw new Error('network down');
      return { accepted: true, version: payload.version, savedTime: '2026-09-21T13:00:00' };
    });
    h.hook.seed({ version: null, savedAt: null, answers: {} });
    answer(h, '101', 'A');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
    expect(h.hook.status.value).toBe('unsynced');

    // 倒计时归零：锁定
    h.locked.value = true;
    expect(h.hook.pendingSync.value).toBe(true);

    // 网络恢复：把最终答案保存为服务器草稿（后端超时扫描从草稿取答案收卷）
    offline = false;
    window.dispatchEvent(new Event('online'));
    await vi.advanceTimersByTimeAsync(0);

    expect(h.hook.status.value).toBe('synced');
    expect(h.hook.pendingSync.value).toBe(false);
    expect(h.saveDraft.mock.calls.at(-1)?.[1].answers).toEqual({ '101': 'A' });
  });

  it('锁定且已同步 → 不亮「待同步」', async () => {
    const h = setup();
    h.hook.seed({ version: null, savedAt: null, answers: {} });
    answer(h, '101', 'A');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
    h.locked.value = true;
    expect(h.hook.status.value).toBe('synced');
    expect(h.hook.pendingSync.value).toBe(false);
  });
});

describe('挂空挡与清理', () => {
  it('suspended（后端已封闭）：作答通知被忽略，不发请求不写存储', async () => {
    const h = setup();
    h.hook.seed({ version: null, savedAt: null, answers: {} });
    h.suspended.value = true;
    const before = h.storageSave.mock.calls.length;

    answer(h, '101', 'A');
    await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS * 3);

    expect(h.saveDraft).not.toHaveBeenCalled();
    expect(h.storageSave.mock.calls.length).toBe(before);
    expect(h.hook.status.value).toBe('idle');
  });

  it('作用域销毁后定时器不再触发保存', async () => {
    const h = setup();
    h.hook.seed({ version: null, savedAt: null, answers: {} });
    answer(h, '101', 'A');
    h.dispose();
    await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS * 3);
    expect(h.saveDraft).not.toHaveBeenCalled();
  });
});
