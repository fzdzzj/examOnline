/**
 * 考试列表本地缓存存储端口（创新点 5 二期 · 阶段 2）。
 *
 * 设计约定：
 * 1. 端口隔离：可注入的存储端口把纯逻辑与真 IndexedDB 适配层分开；
 * 2. 内存降级：单测与无 IndexedDB 环境（如 jsdom）自动使用内存存储；
 * 3. 换号防串：缓存带当前用户 userId，读取时若与当前登录用户不一致则弃用返回 null；
 * 4. 损坏容忍：结构损坏按无记录处理（null），不抛错；
 * 5. 幂等清理：clear 方法幂等清除缓存行。
 */
import type { ExamListItem } from '@/api/axios';

export interface StoredExamListCacheRow {
  cacheKey: string;
  userId: number;
  capturedWallClock: number;
  items: ExamListItem[];
}

export interface ExamListCacheStorage {
  load(currentUserId: number, cacheKey?: string): Promise<StoredExamListCacheRow | null>;
  save(
    userId: number,
    items: ExamListItem[],
    cacheKey?: string,
    capturedWallClock?: number
  ): Promise<void>;
  clear(cacheKey?: string): Promise<void>;
}

export interface ExamListCacheStorageOptions {
  now?: () => number;
}

export const DEFAULT_EXAM_LIST_CACHE_KEY = 'student_exams';

/** 结构校验与换号防串检验 */
function toListCacheRow(row: unknown, currentUserId: number): StoredExamListCacheRow | null {
  if (typeof row !== 'object' || row === null) return null;
  const candidate = row as Partial<StoredExamListCacheRow>;
  if (
    typeof candidate.cacheKey !== 'string' ||
    typeof candidate.userId !== 'number' ||
    typeof candidate.capturedWallClock !== 'number' ||
    !Array.isArray(candidate.items)
  ) {
    return null;
  }
  // 换号防串：用户不一致直接弃用
  if (candidate.userId !== currentUserId) {
    return null;
  }
  return {
    cacheKey: candidate.cacheKey,
    userId: candidate.userId,
    capturedWallClock: candidate.capturedWallClock,
    items: JSON.parse(JSON.stringify(candidate.items)),
  };
}

/** 内存列表缓存仓库实现（单测与降级共用） */
export function createMemoryExamListCacheStorage(
  options: ExamListCacheStorageOptions = {}
): ExamListCacheStorage & { snapshot(): Map<string, StoredExamListCacheRow> } {
  const store = new Map<string, StoredExamListCacheRow>();
  const now = options.now ?? (() => Date.now());

  return {
    async load(
      currentUserId: number,
      cacheKey = DEFAULT_EXAM_LIST_CACHE_KEY
    ): Promise<StoredExamListCacheRow | null> {
      const row = store.get(cacheKey);
      return toListCacheRow(row, currentUserId);
    },
    async save(
      userId: number,
      items: ExamListItem[],
      cacheKey = DEFAULT_EXAM_LIST_CACHE_KEY,
      capturedWallClock?: number
    ): Promise<void> {
      const row: StoredExamListCacheRow = {
        cacheKey,
        userId,
        capturedWallClock: capturedWallClock ?? now(),
        items: JSON.parse(JSON.stringify(items)),
      };
      store.set(cacheKey, row);
    },
    async clear(cacheKey = DEFAULT_EXAM_LIST_CACHE_KEY): Promise<void> {
      store.delete(cacheKey);
    },
    snapshot() {
      return store;
    },
  };
}

export const STUDENT_DRAFT_DB_NAME = 'exam-online-student-drafts';
export const LIST_CACHE_STORE_NAME = 'exam_list_cache';
export const STUDENT_DRAFT_DB_VERSION = 2;

/** 原生 IndexedDB 薄封装 */
export function createStudentExamListCacheStorage(
  options: ExamListCacheStorageOptions = {}
): ExamListCacheStorage {
  if (typeof indexedDB === 'undefined') {
    console.warn('[examListCacheStorage] 当前环境没有 IndexedDB，列表缓存退化为内存（刷新即失）');
    return createMemoryExamListCacheStorage(options);
  }

  const now = options.now ?? (() => Date.now());

  const ready: Promise<IDBDatabase> = new Promise((resolve, reject) => {
    const request = indexedDB.open(STUDENT_DRAFT_DB_NAME, STUDENT_DRAFT_DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains('drafts')) {
        db.createObjectStore('drafts', { keyPath: 'examId' });
      }
      if (!db.objectStoreNames.contains('exam_snapshots')) {
        db.createObjectStore('exam_snapshots', { keyPath: 'examId' });
      }
      if (!db.objectStoreNames.contains(LIST_CACHE_STORE_NAME)) {
        db.createObjectStore(LIST_CACHE_STORE_NAME, { keyPath: 'cacheKey' });
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
          const tx = db.transaction(LIST_CACHE_STORE_NAME, mode);
          const request = run(tx.objectStore(LIST_CACHE_STORE_NAME));
          request.onsuccess = () => resolve(request.result);
          request.onerror = () => reject(request.error ?? new Error('IndexedDB request failed'));
        })
    );
  }

  return {
    async load(
      currentUserId: number,
      cacheKey = DEFAULT_EXAM_LIST_CACHE_KEY
    ): Promise<StoredExamListCacheRow | null> {
      return withStore('readonly', (store) => store.get(cacheKey) as IDBRequest<unknown>).then(
        (row) => toListCacheRow(row, currentUserId),
        () => null
      );
    },
    async save(
      userId: number,
      items: ExamListItem[],
      cacheKey = DEFAULT_EXAM_LIST_CACHE_KEY,
      capturedWallClock?: number
    ): Promise<void> {
      const row: StoredExamListCacheRow = {
        cacheKey,
        userId,
        capturedWallClock: capturedWallClock ?? now(),
        items: JSON.parse(JSON.stringify(items)),
      };
      return withStore('readwrite', (store) => store.put(row)).then(
        () => undefined,
        () => undefined
      );
    },
    async clear(cacheKey = DEFAULT_EXAM_LIST_CACHE_KEY): Promise<void> {
      return withStore('readwrite', (store) => store.delete(cacheKey)).then(
        () => undefined,
        () => undefined
      );
    },
  };
}
