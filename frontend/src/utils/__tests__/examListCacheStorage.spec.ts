import { describe, expect, it, vi } from 'vitest';

import type { ExamListItem } from '@/api/axios';
import {
  createMemoryExamListCacheStorage,
  createStudentExamListCacheStorage,
  type ExamListCacheStorage,
  type StoredExamListCacheRow,
} from '@/utils/examListCacheStorage';

describe('examListCacheStorage 列表缓存仓库（端口模式与换号防串）', () => {
  const SAMPLE_ITEMS: ExamListItem[] = [
    {
      examId: 10,
      title: '高数期中',
      group: 'ONGOING',
      canEnter: true,
      remainingSeconds: 3600,
      startTime: '2026-10-10T09:00:00Z',
      endTime: '2026-10-10T11:00:00Z',
    },
    {
      examId: 11,
      title: '大学物理',
      group: 'UPCOMING',
      canEnter: false,
      remainingSeconds: undefined,
      startTime: '2026-10-11T09:00:00Z',
      endTime: '2026-10-11T11:00:00Z',
    },
  ];

  it('① 读写覆盖：save 列表后以相同 userId load 能正确读回 items 与 capturedWallClock', async () => {
    const fixedNow = 1_700_000_123_000;
    const storage: ExamListCacheStorage = createMemoryExamListCacheStorage({ now: () => fixedNow });

    await storage.save(1001, SAMPLE_ITEMS);
    const loaded = await storage.load(1001);

    expect(loaded).not.toBeNull();
    expect(loaded?.userId).toBe(1001);
    expect(loaded?.capturedWallClock).toBe(fixedNow);
    expect(loaded?.items).toHaveLength(2);
    expect(loaded?.items[0].title).toBe('高数期中');
    expect(loaded?.items[0].canEnter).toBe(true);
  });

  it('② 换号弃用（防串号）：缓存的 userId 与当前用户不一致时返回 null', async () => {
    const storage = createMemoryExamListCacheStorage();
    await storage.save(1001, SAMPLE_ITEMS);

    // 学生 A (1001) 登录缓存后，学生 B (1002) 访问
    const loadedByOther = await storage.load(1002);
    expect(loadedByOther).toBeNull();

    // 学生 A 再次访问依然有效
    const loadedByOwner = await storage.load(1001);
    expect(loadedByOwner).not.toBeNull();
    expect(loadedByOwner?.items).toHaveLength(2);
  });

  it('③ 结构损坏容忍：损坏数据行按无记录处理，不抛异常', async () => {
    const storage = createMemoryExamListCacheStorage();
    const store = storage.snapshot();

    // 损坏 A: items 非数组
    store.set('student_exams', {
      cacheKey: 'student_exams',
      userId: 1001,
      capturedWallClock: Date.now(),
      items: 'not_an_array',
    } as unknown as StoredExamListCacheRow);
    expect(await storage.load(1001)).toBeNull();

    // 损坏 B: 缺少 userId
    store.set('student_exams', {
      cacheKey: 'student_exams',
      capturedWallClock: Date.now(),
      items: [],
    } as unknown as StoredExamListCacheRow);
    expect(await storage.load(1001)).toBeNull();

    // 损坏 C: 存储非对象
    store.set('student_exams', null as unknown as StoredExamListCacheRow);
    expect(await storage.load(1001)).toBeNull();
  });

  it('④ clear 幂等：clear 后 load 为 null，再次清除不报错', async () => {
    const storage = createMemoryExamListCacheStorage();
    await storage.save(1001, SAMPLE_ITEMS);
    expect(await storage.load(1001)).not.toBeNull();

    await storage.clear();
    expect(await storage.load(1001)).toBeNull();

    // 重复清除不报错
    await expect(storage.clear()).resolves.toBeUndefined();
  });

  it('⑤ 原生 IndexedDB 降级：无 indexedDB 环境平稳退化为内存实现并警告', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const fallbackStorage = createStudentExamListCacheStorage();

    await fallbackStorage.save(2001, SAMPLE_ITEMS);
    const loaded = await fallbackStorage.load(2001);
    expect(loaded?.userId).toBe(2001);
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('[examListCacheStorage]'));

    warnSpy.mockRestore();
  });
});
