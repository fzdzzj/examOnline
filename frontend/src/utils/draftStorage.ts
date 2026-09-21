/**
 * 草稿本地缓存端口（阶段 22 第 2 片）。
 *
 * ## 为什么要一层端口
 *
 * 开工前已实测：vitest 的 jsdom 环境 `window.indexedDB === undefined`（探针用例
 * `console.log(typeof window.indexedDB)` 输出 `undefined`，跑完即删）。jsdom 官方
 * 不实现 IndexedDB，所以单测**不可能**打到真 IndexedDB——这正是「追补分片状态」
 * 要求的解法：**可注入的存储端口把纯逻辑与真 IndexedDB 适配层分开**：
 * - 单测注入 `createMemoryDraftStorage()`（内存 Map，同步可控）；
 * - 生产用 `createStudentDraftStorage()`（浏览器原生 API 手写薄封装，零依赖——
 *   硬约定 14：不装 idb / localforage / dexie）；
 * - 真机行为（含 IndexedDB 分支）由第 3 片的手动演示脚本覆盖（`student-taking-demo.md`
 *   的「断网续答」一条）。
 *
 * ## 能力边界（不声称离线考试）
 *
 * 这层缓存只服务一件事：**断线期间不丢答案 + 恢复后同步**。它不是离线作答模式——
 * 没有 Service Worker、不缓存题目、断线时也拿不到新快照（spec-delta「不声称离线考试」场景）。
 */
import type { DraftRecord } from '@/utils/draftMerge';
import type { AnswerMap } from '@/utils/studentTaking';

/** 存储端口：读写「examId → 草稿记录」。实现必须容忍并发调用（内部自行排队）。 */
export interface DraftStorage {
  load(examId: number): Promise<DraftRecord | null>;
  save(examId: number, record: DraftRecord): Promise<void>;
}

/** 内存实现：单测与「环境无 IndexedDB」的降级共用。 */
export function createMemoryDraftStorage(): DraftStorage & {
  snapshot(): Map<number, DraftRecord>;
} {
  const store = new Map<number, DraftRecord>();
  return {
    async load(examId: number) {
      return store.get(examId) ?? null;
    },
    async save(examId: number, record: DraftRecord) {
      store.set(examId, { ...record, answers: { ...record.answers } });
    },
    snapshot() {
      return store;
    },
  };
}

/** IndexedDB 库名 / 仓库名。就一个仓库一个键，不存在版本迁移问题（版本号固定 1）。 */
const DB_NAME = 'exam-online-student-drafts';
const STORE_NAME = 'drafts';
const DB_VERSION = 1;

/** 落库记录形状（IndexedDB structured clone 存的就是这个普通对象）。 */
interface StoredDraftRow {
  examId: number;
  version: number | null;
  savedAt: string | null;
  answers: AnswerMap;
}

/** 读出的行 → `DraftRecord`；结构损坏（旧格式/手改库）按无记录处理，不抛错阻断作答。 */
function toRecord(row: unknown): DraftRecord | null {
  if (typeof row !== 'object' || row === null) return null;
  const candidate = row as Partial<StoredDraftRow>;
  if (typeof candidate.examId !== 'number' || typeof candidate.answers !== 'object') return null;
  const answers: AnswerMap = {};
  for (const [key, value] of Object.entries(candidate.answers)) {
    if (typeof value === 'string') answers[key] = value;
  }
  return {
    version: typeof candidate.version === 'number' ? candidate.version : null,
    savedAt: typeof candidate.savedAt === 'string' ? candidate.savedAt : null,
    answers,
  };
}

/**
 * 浏览器原生 IndexedDB 薄封装（硬约定 14：不用第三方库）。
 *
 * - 懒打开：首个读写请求才 `open`，之后的请求在同一个连接上执行；
 * - 排队：连接就绪前的调用挂到 promise 链上，调用方不需要关心初始化时序；
 * - 降级：`indexedDB` 全局不存在（或 open 被拒，如隐私模式全禁存储）时退化为
 *   内存实现并 `console.warn` 一次——本地缓存是断线恢复的增强，**不是作答的前提**，
 *   它坏了不该让学生答不了题（答案仍在组件状态里，30s 保存照常走后端）。
 */
export function createStudentDraftStorage(): DraftStorage {
  if (typeof indexedDB === 'undefined') {
    console.warn('[draftStorage] 当前环境没有 IndexedDB，本地草稿缓存退化为内存（刷新即失）');
    const memory = createMemoryDraftStorage();
    return {
      load: (examId) => memory.load(examId),
      save: (examId, record) => memory.save(examId, record),
    };
  }

  /** 连接就绪的 promise：所有操作都链在它后面（排队语义）。 */
  const ready: Promise<IDBDatabase> = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME, { keyPath: 'examId' });
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
    load(examId: number) {
      return withStore('readonly', (store) => store.get(examId) as IDBRequest<unknown>).then(
        (row) => toRecord(row),
        () => null
      );
    },
    save(examId: number, record: DraftRecord) {
      const row: StoredDraftRow = {
        examId,
        version: record.version,
        savedAt: record.savedAt,
        answers: { ...record.answers },
      };
      return withStore('readwrite', (store) => store.put(row)).then(
        () => undefined,
        () => undefined
      );
    },
  };
}
