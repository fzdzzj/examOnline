/**
 * 切屏 / 失焦离线队列存储端口（阶段 4）。
 *
 * 设计约定：
 * 1. 端口隔离：可注入的存储端口把纯逻辑与原生 IndexedDB 适配层分开；
 * 2. 内存降级：单测与无 IndexedDB 环境（如 jsdom）自动使用内存队列；
 * 3. 原生实现：生产使用原生 IndexedDB 薄封装，零第三方依赖（硬约定 14）；
 * 4. 去重防重：入队时若相同 durationMs 已在队列中，自动去重，不重复入队。
 */
import type { BehaviorEventType } from '@/utils/behaviorEvents';

export interface QueuedBehaviorEvent {
  id?: number;
  examId: number;
  eventType: BehaviorEventType;
  durationMs: number | null;
  incomplete?: boolean;
}

export interface BehaviorQueue {
  enqueue(event: QueuedBehaviorEvent): Promise<void>;
  peek(examId: number): Promise<QueuedBehaviorEvent[]>;
  dequeue(examId: number, event: QueuedBehaviorEvent): Promise<void>;
  clear(examId: number): Promise<void>;
}

/** 内存队列实现：单测与无 IndexedDB 环境降级使用。 */
export function createMemoryBehaviorQueue(): BehaviorQueue {
  const store = new Map<number, QueuedBehaviorEvent[]>();
  let nextId = 1;

  return {
    async enqueue(event: QueuedBehaviorEvent): Promise<void> {
      const list = store.get(event.examId) ?? [];
      // 去重：若队列中已有相同 durationMs 的同类型事件，不重复添加
      if (
        event.durationMs !== null &&
        list.some((e) => e.eventType === event.eventType && e.durationMs === event.durationMs)
      ) {
        return;
      }
      const item: QueuedBehaviorEvent = { ...event, id: nextId++ };
      list.push(item);
      store.set(event.examId, list);
    },
    async peek(examId: number): Promise<QueuedBehaviorEvent[]> {
      const list = store.get(examId) ?? [];
      return [...list];
    },
    async dequeue(examId: number, event: QueuedBehaviorEvent): Promise<void> {
      const list = store.get(examId) ?? [];
      const filtered = list.filter((e) => e.id !== event.id);
      store.set(examId, filtered);
    },
    async clear(examId: number): Promise<void> {
      store.delete(examId);
    },
  };
}

const DB_NAME = 'exam-online-student-behaviors';
const STORE_NAME = 'behavior_queue';
const DB_VERSION = 1;

/** 浏览器原生 IndexedDB 薄封装（零第三方依赖）。 */
export function createStudentBehaviorQueue(): BehaviorQueue {
  if (typeof indexedDB === 'undefined') {
    return createMemoryBehaviorQueue();
  }

  const ready: Promise<IDBDatabase> = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        const os = db.createObjectStore(STORE_NAME, { keyPath: 'id', autoIncrement: true });
        os.createIndex('examId', 'examId', { unique: false });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error('IndexedDB open failed'));
  });

  function withStore<T>(
    mode: IDBTransactionMode,
    run: (store: IDBObjectStore) => IDBRequest<T>
  ): Promise<T> {
    return ready.then(
      (db) =>
        new Promise<T>((resolve, reject) => {
          const tx = db.transaction(STORE_NAME, mode);
          const request = run(tx.objectStore(STORE_NAME));
          request.onsuccess = () => resolve(request.result);
          request.onerror = () => reject(request.error ?? new Error('IndexedDB request failed'));
        })
    );
  }

  return {
    async enqueue(event: QueuedBehaviorEvent): Promise<void> {
      const existing = await this.peek(event.examId);
      if (
        event.durationMs !== null &&
        existing.some((e) => e.eventType === event.eventType && e.durationMs === event.durationMs)
      ) {
        return;
      }
      return withStore('readwrite', (store) => store.add(event)).then(
        () => undefined,
        () => undefined
      );
    },
    async peek(examId: number): Promise<QueuedBehaviorEvent[]> {
      return ready
        .then(
          (db) =>
            new Promise<QueuedBehaviorEvent[]>((resolve, reject) => {
              const tx = db.transaction(STORE_NAME, 'readonly');
              const store = tx.objectStore(STORE_NAME);
              const index = store.index('examId');
              const request = index.getAll(examId);
              request.onsuccess = () => resolve(request.result as QueuedBehaviorEvent[]);
              request.onerror = () => reject(request.error ?? new Error('IndexedDB getAll failed'));
            })
        )
        .catch(() => []);
    },
    async dequeue(_examId: number, event: QueuedBehaviorEvent): Promise<void> {
      if (event.id === undefined) return;
      return withStore('readwrite', (store) => store.delete(event.id!)).then(
        () => undefined,
        () => undefined
      );
    },
    async clear(examId: number): Promise<void> {
      const items = await this.peek(examId);
      for (const item of items) {
        await this.dequeue(examId, item);
      }
    },
  };
}
