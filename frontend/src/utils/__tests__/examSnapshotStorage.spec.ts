import { describe, expect, it, vi } from 'vitest';

import type { EnterExamResponse, QuestionView } from '@/api/axios';
import {
  createMemoryExamSnapshotStorage,
  createStudentExamSnapshotStorage,
  type ExamSnapshotStorage,
  type StoredExamSnapshotRow,
} from '@/utils/examSnapshotStorage';

describe('examSnapshotStorage 快照仓库（端口模式与脱敏纪律）', () => {
  const SAMPLE_QUESTIONS: QuestionView[] = [
    { number: 1, questionId: 1001, type: 1, content: '题目1', score: 5 },
    { number: 2, questionId: 1002, type: 2, content: '题目2', score: 5 },
  ];

  const SAMPLE_RESPONSE: EnterExamResponse = {
    examId: 42,
    examTitle: '2026 期末考试',
    submissionId: 999,
    status: 1,
    startTime: '2026-10-10T10:00:00Z',
    deadlineTime: '2026-10-10T12:00:00Z',
    serverTime: '2026-10-10T10:05:00Z',
    remainingSeconds: 6900,
    questions: SAMPLE_QUESTIONS,
    answers: { 1001: 'A' },
    marked: [1001],
    draftVersion: 3,
  };

  it('① 读写覆盖：save 保存快照后 load 能正确读回脱敏数据与 capturedWallClock', async () => {
    const fixedNow = 1_700_000_000_000;
    const storage: ExamSnapshotStorage = createMemoryExamSnapshotStorage({ now: () => fixedNow });

    await storage.save(42, SAMPLE_RESPONSE);
    const loaded = await storage.load(42);

    expect(loaded).not.toBeNull();
    expect(loaded?.examId).toBe(42);
    expect(loaded?.capturedWallClock).toBe(fixedNow);
    expect(loaded?.payload.examTitle).toBe('2026 期末考试');
    expect(loaded?.payload.submissionId).toBe(999);
    expect(loaded?.payload.status).toBe(1);
    expect(loaded?.payload.remainingSeconds).toBe(6900);
    expect(loaded?.payload.questions).toHaveLength(2);
    expect(loaded?.payload.version).toBe(3);
  });

  it('② 脱敏形状断言：持久化行与 payload 严格不含 answers 与 marked 键', async () => {
    const memory = createMemoryExamSnapshotStorage();
    await memory.save(42, SAMPLE_RESPONSE);

    const snapshot = memory.snapshot();
    const storedRow = snapshot.get(42) as StoredExamSnapshotRow;

    expect(storedRow).toBeDefined();
    // 根对象不含 answers / marked
    expect('answers' in storedRow).toBe(false);
    expect('marked' in storedRow).toBe(false);

    // payload 对象也不含 answers / marked
    expect('answers' in storedRow.payload).toBe(false);
    expect('marked' in storedRow.payload).toBe(false);

    // 读取出来的对象同样脱敏
    const loaded = await memory.load(42);
    expect(loaded).not.toBeNull();
    expect('answers' in (loaded as unknown as Record<string, unknown>)).toBe(false);
    expect('answers' in (loaded?.payload as unknown as Record<string, unknown>)).toBe(false);
    expect('marked' in (loaded?.payload as unknown as Record<string, unknown>)).toBe(false);
  });

  it('③ 结构损坏容忍：损坏数据行按无记录处理，不抛异常阻断作答', async () => {
    const memory = createMemoryExamSnapshotStorage();
    const store = memory.snapshot();

    // 损坏形式 A: 缺少 capturedWallClock
    store.set(1, { examId: 1, payload: {} } as unknown as StoredExamSnapshotRow);
    expect(await memory.load(1)).toBeNull();

    // 损坏形式 B: payload 非对象
    store.set(2, {
      examId: 2,
      capturedWallClock: Date.now(),
      payload: null,
    } as unknown as StoredExamSnapshotRow);
    expect(await memory.load(2)).toBeNull();

    // 损坏形式 C: 存储值为非对象原始值
    store.set(3, 'corrupted_string' as unknown as StoredExamSnapshotRow);
    expect(await memory.load(3)).toBeNull();
  });

  it('④ remove 幂等：删除已存快照后 load 为 null，删除不存在记录不报错', async () => {
    const memory = createMemoryExamSnapshotStorage();
    await memory.save(42, SAMPLE_RESPONSE);
    expect(await memory.load(42)).not.toBeNull();

    await memory.remove(42);
    expect(await memory.load(42)).toBeNull();

    // 再次删除不存在记录，不报错
    await expect(memory.remove(42)).resolves.toBeUndefined();
    await expect(memory.remove(9999)).resolves.toBeUndefined();
  });

  it('⑤ 覆盖写入：同一 examId 重复保存时覆盖旧快照与墙钟时间', async () => {
    let clock = 1_000_000;
    const memory = createMemoryExamSnapshotStorage({ now: () => clock });

    await memory.save(42, { ...SAMPLE_RESPONSE, remainingSeconds: 5000 });
    let loaded = await memory.load(42);
    expect(loaded?.payload.remainingSeconds).toBe(5000);
    expect(loaded?.capturedWallClock).toBe(1_000_000);

    // 更新时间与数据后覆盖
    clock = 1_000_500;
    await memory.save(42, { ...SAMPLE_RESPONSE, remainingSeconds: 4500 });
    loaded = await memory.load(42);
    expect(loaded?.payload.remainingSeconds).toBe(4500);
    expect(loaded?.capturedWallClock).toBe(1_000_500);
  });

  it('⑥ 原生 IndexedDB 降级：无 indexedDB 环境平稳退化为内存实现并输出警告', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const fallbackStorage = createStudentExamSnapshotStorage();

    await fallbackStorage.save(88, SAMPLE_RESPONSE);
    const loaded = await fallbackStorage.load(88);
    expect(loaded?.examId).toBe(88);
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('[examSnapshotStorage]'));

    warnSpy.mockRestore();
  });
});
