/**
 * 30s 自动保存 + IndexedDB 本地缓存（阶段 22 第 2 片）。
 *
 * ## 它守的三条硬约定
 *
 * 1. **保存频率不高于后端设计假设**（硬约定 6）：定时器 30s 一拍、输入防抖 30s 一窗，
 *    两条触发路径共同保证相邻两次后端保存之间至少间隔 30s；**没有**「每次按键即保存」
 *    的路径——学生每敲一下键盘只写 IndexedDB（本地操作，无网络负载），后端请求
 *    只从定时器 / 防抖 / 网络恢复三个入口发出。后端把 30s 保存同时当监考在线心跳
 *    （`presenceService.touch`）与压测负载模型用，擅自加密频率两头都伤。
 * 2. **草稿合并保守**（硬约定 7）：本 hook 不管合并——播种走 `mergeDrafts` 纯函数
 *    （页面在挂载时调 `seed()`），冲突时两份都不删、界面提示学生裁决。
 * 3. **归零只锁定 + 待同步，不判定超时**（硬约定 2 / 任务 2 接缝 step）：倒计时归零后
 *    `pendingSync` 亮起，网络恢复（`online` 事件）即把最终答案**保存为后端草稿**——
 *    注意是草稿不是交卷（交卷是第 3 片）；后端超时收卷时从 Redis 草稿取答案
 *    （`ExamSweepService` 扫描 → `draftService.get`），这正是「按锁定状态提交」
 *    落到服务端的路径。最终超时判定始终在后端。
 *
 * ## version 协议（读 `ExamDraftService.save` 核实）
 *
 * 后端只拒 `incoming < stored.version`；`version` 递增是保存成功方的责任。本 hook：
 * 首发版本 = 播种记录的 version + 1（无草稿则 1）；成功后对齐响应的 version；
 * 冲突（`accepted=false`）时**学服务端存量版本**（后端 javadoc：「客户端应以返回的
 * version 为准重新拉取/合并后再保存」），学生继续作答产生的下次保存即可收敛。
 */
import { computed, getCurrentScope, onScopeDispose, ref, toValue } from 'vue';
import type { ComputedRef, MaybeRefOrGetter, Ref } from 'vue';

import { AUTOSAVE_DEBOUNCE_MS, AUTOSAVE_INTERVAL_MS } from '@/constants/studentTaking';
import type { DraftRecord } from '@/utils/draftMerge';
import type { DraftStorage } from '@/utils/draftStorage';
import type { AnswerMap } from '@/utils/studentTaking';

/** 同步状态：`unsynced` = 上次保存尝试失败（网络或后端拒绝），答案只在本机。 */
export type DraftSyncStatus = 'idle' | 'pending' | 'saving' | 'synced' | 'unsynced' | 'conflict';

/** 后端 `AutoSaveResponse` 的窄化（契约字段全可选）。 */
export interface AutoSaveResult {
  accepted?: boolean | null;
  version?: number | null;
  savedTime?: string | null;
}

/** 发往后端的保存请求体（`AutoSaveRequest`：version 必填，marked 恒空——标记备注不做）。 */
export interface AutoSavePayload {
  version: number;
  answers: AnswerMap;
  marked: number[];
}

export interface UseAutoSaveDraftOptions {
  /** 考试 ID；非正整数时整个引擎挂空挡（不发请求、不写存储）。 */
  examId: MaybeRefOrGetter<number>;
  /** 答案只读来源：保存时取当前值，作答通知走 `notifyAnswered()`（显式、可测）。 */
  answersSource: MaybeRefOrGetter<AnswerMap>;
  /** 归零锁定（倒计时归零）。只影响 `pendingSync` 展示，不改变保存行为。 */
  locked: MaybeRefOrGetter<boolean>;
  /** 后端已封闭（已交卷/已收卷）或考试 ID 非法 → 停止一切保存动作。 */
  suspended: MaybeRefOrGetter<boolean>;
  deps: {
    saveDraft: (examId: number, payload: AutoSavePayload) => Promise<AutoSaveResult | undefined>;
    storage: DraftStorage;
    /** 定时保存周期，默认 `AUTOSAVE_INTERVAL_MS`（30s 后端设计假设）。 */
    intervalMs?: number;
    /** 输入防抖窗口，默认 `AUTOSAVE_DEBOUNCE_MS`（不小于后端 30s 假设）。 */
    debounceMs?: number;
    /** 单调时钟（测「距上次保存过了多久」这个时长）；默认 `performance.now()`，单测注入。 */
    now?: () => number;
  };
}

export interface AutoSaveDraftView {
  status: Ref<DraftSyncStatus>;
  /** 最近一次后端确认保存的时刻（后端 `savedTime` 原文），仅展示。 */
  lastSavedAt: Ref<string | null>;
  /** 归零锁定且仍有未同步内容——「待同步」标志（任务 2 接缝 step）。 */
  pendingSync: ComputedRef<boolean>;
  /** 播种：挂载时按保守合并结果初始化 version，并对齐本地缓存（不产生后端请求）。 */
  seed(record: DraftRecord): void;
  /** 学生作答（页面 onAnswer 时调）：标脏 + 写 IndexedDB + 重置防抖。 */
  notifyAnswered(): void;
  /** 立即保存（若脏且未挂起）；网络恢复/组件卸载前由内部或页面触发。 */
  flush(): Promise<void>;
}

export function useAutoSaveDraft(options: UseAutoSaveDraftOptions): AutoSaveDraftView {
  const { examId, answersSource, locked, suspended, deps } = options;
  const intervalMs = deps.intervalMs ?? AUTOSAVE_INTERVAL_MS;
  const debounceMs = deps.debounceMs ?? AUTOSAVE_DEBOUNCE_MS;
  // 只测「距上次保存过了多久」这个时长，不测「现在是几点」（useServerCountdown 同款纪律）。
  // performance.now() 是单调时钟，不受学生改本机时间影响；Date.now 会被改表拨动。
  const nowFn =
    deps.now ?? ((): number => (typeof performance !== 'undefined' ? performance.now() : 0));

  const status = ref<DraftSyncStatus>('idle');
  const lastSavedAt = ref<string | null>(null);
  const dirty = ref(false);
  const saving = ref(false);

  /** 最后一次与后端对齐的版本；null = 后端从未有过草稿。 */
  let knownVersion: number | null = null;
  /** 最近一次 flush 完成的时刻（防抖让路用）；-Infinity = 还没保存过，永不拦截首次。 */
  let lastFlushDoneAt = -Infinity;

  const active = () => {
    const id = toValue(examId);
    return Number.isInteger(id) && id > 0 && !toValue(suspended);
  };

  async function persistLocal(): Promise<void> {
    if (!active()) return;
    const id = toValue(examId);
    const record: DraftRecord = {
      version: knownVersion,
      savedAt: lastSavedAt.value,
      answers: { ...toValue(answersSource) },
    };
    await deps.storage.save(id, record);
  }

  async function flush(): Promise<void> {
    if (!active() || !dirty.value || saving.value) return;
    saving.value = true;
    status.value = 'saving';
    const id = toValue(examId);
    const snapshot = toValue(answersSource);
    const nextVersion = (knownVersion ?? 0) + 1;
    try {
      const result = await deps.saveDraft(id, {
        version: nextVersion,
        answers: { ...snapshot },
        marked: [],
      });
      if (result === undefined || result === null) {
        // 契约里 data 缺失属于异常路径：按未同步处理，答案与 dirty 都保留
        status.value = 'unsynced';
        return;
      }
      if (result.accepted === false) {
        // 多端版本冲突：学服务端存量版本，dirty 保留（学生当前作答没有丢，也未覆盖别端）
        knownVersion = typeof result.version === 'number' ? result.version : knownVersion;
        status.value = 'conflict';
        return;
      }
      knownVersion = typeof result.version === 'number' ? result.version : nextVersion;
      lastSavedAt.value = typeof result.savedTime === 'string' ? result.savedTime : null;
      // 保存期间学生又作答过（answers 引用已换）→ dirty 保持，交给下一拍
      if (toValue(answersSource) === snapshot) {
        dirty.value = false;
        status.value = 'synced';
      } else {
        status.value = 'pending';
      }
      // 本地缓存对齐到后端确认状态（version/savedAt 与后端一致，断线重进才有得合并）
      await deps.storage.save(id, {
        version: knownVersion,
        savedAt: lastSavedAt.value,
        answers: { ...snapshot },
      });
    } catch {
      // 断线 / 后端拒绝（如「答卷已提交，草稿不再受理」）：如实标未同步，绝不静默丢弃
      status.value = 'unsynced';
    } finally {
      saving.value = false;
      lastFlushDoneAt = nowFn();
    }
  }

  let debounceTimer: ReturnType<typeof setTimeout> | null = null;

  function armDebounce(): void {
    if (debounceTimer !== null) clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => {
      debounceTimer = null;
      // 防抖与定时器同刻双发的让路规则：距上次保存完成不足一个周期就把这次让给
      // 下一定时拍——保证「相邻两次后端保存至少间隔 30s」在双路径下仍然成立
      //（online 恢复路径不受此限制，见 onOnline）。
      if (nowFn() - lastFlushDoneAt < intervalMs) return;
      void flush();
    }, debounceMs);
  }

  function notifyAnswered(): void {
    if (!active()) return;
    if (status.value !== 'unsynced' && status.value !== 'conflict') {
      status.value = 'pending';
    }
    dirty.value = true;
    // 「每次作答同步写 IndexedDB」：本地写入不受 30s 节流（无网络负载），
    // 后端请求频率只由防抖 + 定时器控制
    void persistLocal();
    armDebounce();
  }

  function seed(record: DraftRecord): void {
    knownVersion = record.version;
    lastSavedAt.value = record.savedAt;
    dirty.value = false;
    status.value = 'idle';
    // 本地缓存对齐到播种采用的记录（比如后端较新被采用时，别让 IndexedDB 留着旧份）
    void persistLocal();
  }

  // 定时兜底：连续输入不断重置防抖时，30s 一拍仍会把脏草稿推上去
  const interval = setInterval(() => {
    if (dirty.value && !saving.value) void flush();
  }, intervalMs);

  // 网络恢复：立即冲一次脏草稿。归零锁定场景这就是「网络恢复后按锁定状态提交」——
  // 把最终答案推成后端 Redis 草稿，后端超时扫描从草稿取答案收卷
  function onOnline(): void {
    if (dirty.value && !saving.value) void flush();
  }
  window.addEventListener('online', onOnline);

  if (getCurrentScope()) {
    onScopeDispose(() => {
      clearInterval(interval);
      if (debounceTimer !== null) clearTimeout(debounceTimer);
      window.removeEventListener('online', onOnline);
    });
  }

  const pendingSync = computed(
    () => toValue(locked) && active() && dirty.value && status.value !== 'synced'
  );

  return { status, lastSavedAt, pendingSync, seed, notifyAnswered, flush };
}
