/**
 * 考试快照本地存储端口（创新点 5 二期 · 阶段 2）。
 *
 * 设计约定：
 * 1. 端口隔离：可注入的存储端口把纯逻辑与真 IndexedDB 适配层分开；
 * 2. 内存降级：单测与无 IndexedDB 环境（如 jsdom）自动使用内存存储；
 * 3. 原生实现：生产使用原生 IndexedDB 薄封装，零第三方依赖（硬约定 14）；
 * 4. 脱敏纪律：仅存储题目视图与服务端时间字段，绝对不存储 answers / marked；
 * 5. 损坏容忍：结构损坏按无记录处理（null），不抛错阻断作答；
 * 6. remove 幂等：交卷后删除快照行，多次调用不报错。
 */
import type { EnterExamResponse, QuestionView } from '@/api/axios';

export interface ExamSnapshotPayload {
  examId?: number;
  examTitle?: string;
  submissionId?: number;
  status?: number;
  startTime?: string;
  deadlineTime?: string;
  serverTime?: string;
  remainingSeconds?: number;
  questions?: QuestionView[];
  version?: number | null;
  draftVersion?: number;
}

export interface StoredExamSnapshotRow {
  examId: number;
  capturedWallClock: number;
  payload: ExamSnapshotPayload;
}

export interface ExamSnapshotStorage {
  load(examId: number): Promise<StoredExamSnapshotRow | null>;
  save(
    examId: number,
    source: Partial<EnterExamResponse> | ExamSnapshotPayload,
    capturedWallClock?: number
  ): Promise<void>;
  remove(examId: number): Promise<void>;
}

export interface ExamSnapshotStorageOptions {
  now?: () => number;
}

/** 脱敏提取：仅提取白名单内的非草稿字段，绝对剥离 answers / marked */
export function sanitizeSnapshotPayload(
  source: Partial<EnterExamResponse> | ExamSnapshotPayload
): ExamSnapshotPayload {
  const version =
    typeof (source as Partial<EnterExamResponse>).draftVersion === 'number'
      ? (source as Partial<EnterExamResponse>).draftVersion
      : typeof (source as ExamSnapshotPayload).version === 'number'
        ? (source as ExamSnapshotPayload).version
        : null;

  return {
    examId: typeof source.examId === 'number' ? source.examId : undefined,
    examTitle: typeof source.examTitle === 'string' ? source.examTitle : undefined,
    submissionId: typeof source.submissionId === 'number' ? source.submissionId : undefined,
    status: typeof source.status === 'number' ? source.status : undefined,
    startTime: typeof source.startTime === 'string' ? source.startTime : undefined,
    deadlineTime: typeof source.deadlineTime === 'string' ? source.deadlineTime : undefined,
    serverTime: typeof source.serverTime === 'string' ? source.serverTime : undefined,
    remainingSeconds:
      typeof source.remainingSeconds === 'number' ? source.remainingSeconds : undefined,
    questions: Array.isArray(source.questions)
      ? JSON.parse(JSON.stringify(source.questions))
      : undefined,
    version,
    draftVersion: typeof version === 'number' ? version : undefined,
  };
}

/** 结构校验：损坏数据返回 null */
function toSnapshotRow(row: unknown): StoredExamSnapshotRow | null {
  if (typeof row !== 'object' || row === null) return null;
  const candidate = row as Partial<StoredExamSnapshotRow>;
  if (typeof candidate.examId !== 'number' || typeof candidate.capturedWallClock !== 'number') {
    return null;
  }
  if (typeof candidate.payload !== 'object' || candidate.payload === null) {
    return null;
  }
  return {
    examId: candidate.examId,
    capturedWallClock: candidate.capturedWallClock,
    payload: sanitizeSnapshotPayload(candidate.payload),
  };
}

/** 内存快照仓库实现（单测与无 IndexedDB 降级共用） */
export function createMemoryExamSnapshotStorage(
  options: ExamSnapshotStorageOptions = {}
): ExamSnapshotStorage & { snapshot(): Map<number, StoredExamSnapshotRow> } {
  const store = new Map<number, StoredExamSnapshotRow>();
  const now = options.now ?? (() => Date.now());

  return {
    async load(examId: number): Promise<StoredExamSnapshotRow | null> {
      const row = store.get(examId);
      return toSnapshotRow(row);
    },
    async save(
      examId: number,
      source: Partial<EnterExamResponse> | ExamSnapshotPayload,
      capturedWallClock?: number
    ): Promise<void> {
      const row: StoredExamSnapshotRow = {
        examId,
        capturedWallClock: capturedWallClock ?? now(),
        payload: sanitizeSnapshotPayload(source),
      };
      store.set(examId, row);
    },
    async remove(examId: number): Promise<void> {
      store.delete(examId);
    },
    snapshot() {
      return store;
    },
  };
}

export const STUDENT_DRAFT_DB_NAME = 'exam-online-student-drafts';
export const SNAPSHOTS_STORE_NAME = 'exam_snapshots';
export const STUDENT_DRAFT_DB_VERSION = 2;

/** 原生 IndexedDB 薄封装 */
export function createStudentExamSnapshotStorage(
  options: ExamSnapshotStorageOptions = {}
): ExamSnapshotStorage {
  if (typeof indexedDB === 'undefined') {
    console.warn('[examSnapshotStorage] 当前环境没有 IndexedDB，快照存储退化为内存（刷新即失）');
    return createMemoryExamSnapshotStorage(options);
  }

  const now = options.now ?? (() => Date.now());

  const ready: Promise<IDBDatabase> = new Promise((resolve, reject) => {
    const request = indexedDB.open(STUDENT_DRAFT_DB_NAME, STUDENT_DRAFT_DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains('drafts')) {
        db.createObjectStore('drafts', { keyPath: 'examId' });
      }
      if (!db.objectStoreNames.contains(SNAPSHOTS_STORE_NAME)) {
        db.createObjectStore(SNAPSHOTS_STORE_NAME, { keyPath: 'examId' });
      }
      if (!db.objectStoreNames.contains('exam_list_cache')) {
        db.createObjectStore('exam_list_cache', { keyPath: 'cacheKey' });
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
          const tx = db.transaction(SNAPSHOTS_STORE_NAME, mode);
          const request = run(tx.objectStore(SNAPSHOTS_STORE_NAME));
          request.onsuccess = () => resolve(request.result);
          request.onerror = () => reject(request.error ?? new Error('IndexedDB request failed'));
        })
    );
  }

  return {
    async load(examId: number): Promise<StoredExamSnapshotRow | null> {
      return withStore('readonly', (store) => store.get(examId) as IDBRequest<unknown>).then(
        (row) => toSnapshotRow(row),
        () => null
      );
    },
    async save(
      examId: number,
      source: Partial<EnterExamResponse> | ExamSnapshotPayload,
      capturedWallClock?: number
    ): Promise<void> {
      const row: StoredExamSnapshotRow = {
        examId,
        capturedWallClock: capturedWallClock ?? now(),
        payload: sanitizeSnapshotPayload(source),
      };
      return withStore('readwrite', (store) => store.put(row)).then(
        () => undefined,
        () => undefined
      );
    },
    async remove(examId: number): Promise<void> {
      return withStore('readwrite', (store) => store.delete(examId)).then(
        () => undefined,
        () => undefined
      );
    },
  };
}
