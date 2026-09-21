/**
 * 草稿保守合并纯函数单测（阶段 22 第 2 片，`tasks.json` 任务 3 第 4 条的验收口子）。
 *
 * 三分支是判据：后端较新 / 本地较新 / 二者相同；外加「无法判定」的 conflict 分支
 * （硬约定 7：保留两份、绝不静默覆盖）。
 */
import { describe, expect, it } from 'vitest';

import {
  hasDraftContent,
  mergeDrafts,
  resolveSeed,
  sameAnswers,
  serverDraftOf,
  type DraftRecord,
} from '@/utils/draftMerge';
import { answerMapOf } from '@/utils/studentTaking';

function draft(version: number | null, answers: Record<string, string>): DraftRecord {
  return { version, savedAt: null, answers };
}

describe('mergeDrafts 三分支 + 冲突', () => {
  it('后端较新：version 更大的一方取胜，本地不覆盖', () => {
    const server = draft(5, { '1': 'A' });
    const local = draft(3, { '1': 'B' });
    const outcome = mergeDrafts(server, local);
    expect(outcome.kind).toBe('server');
    if (outcome.kind === 'server') {
      expect(outcome.answers['1']).toBe('A');
      expect(outcome.version).toBe(5);
    }
  });

  it('本地较新：version 更大的一方取胜，后端不覆盖', () => {
    const server = draft(3, { '1': 'A' });
    const local = draft(5, { '1': 'B' });
    const outcome = mergeDrafts(server, local);
    expect(outcome.kind).toBe('local');
    if (outcome.kind === 'local') {
      expect(outcome.answers['1']).toBe('B');
      expect(outcome.version).toBe(5);
    }
  });

  it('二者相同：version 相同且内容一致 → identical（同一状态，任取一份）', () => {
    const server = draft(4, { '1': 'A', '2': 'T' });
    const local = draft(4, { '1': 'A', '2': 'T' });
    const outcome = mergeDrafts(server, local);
    expect(outcome.kind).toBe('identical');
    if (outcome.kind === 'identical') {
      expect(outcome.answers).toEqual({ '1': 'A', '2': 'T' });
      expect(outcome.version).toBe(4);
    }
  });

  it('version 相同但内容不同 → conflict：两份原样保留，绝不静默覆盖', () => {
    const server = draft(4, { '1': 'A' });
    const local = draft(4, { '1': 'C' });
    const outcome = mergeDrafts(server, local);
    expect(outcome.kind).toBe('conflict');
    if (outcome.kind === 'conflict') {
      expect(outcome.reason).toBe('version-tied-content-differs');
      expect(outcome.server.answers['1']).toBe('A');
      expect(outcome.local.answers['1']).toBe('C');
    }
  });

  it('version 不可比（一方为 null）且内容不同 → conflict：不拿 savedAt 猜新旧', () => {
    const server = draft(null, { '1': 'A' });
    const local = draft(7, { '1': 'B' });
    const outcome = mergeDrafts(server, local);
    expect(outcome.kind).toBe('conflict');
    if (outcome.kind === 'conflict') {
      expect(outcome.reason).toBe('version-not-comparable');
    }
  });

  it('version 不可比但内容一致 → identical，version 取较大者（对齐后端状态）', () => {
    const server = draft(null, { '1': 'A' });
    const local = draft(3, { '1': 'A' });
    const outcome = mergeDrafts(server, local);
    expect(outcome.kind).toBe('identical');
    if (outcome.kind === 'identical') {
      expect(outcome.version).toBe(3);
    }
  });

  it('只有一方有内容 → 用那一方（另一方没得争，不构成冲突）', () => {
    expect(mergeDrafts(draft(2, { '1': 'A' }), null).kind).toBe('server');
    expect(mergeDrafts(null, draft(2, { '1': 'B' })).kind).toBe('local');
    // 空 answers 视为没有草稿
    expect(mergeDrafts(draft(2, { '1': 'A' }), draft(9, {})).kind).toBe('server');
  });

  it('双方都空 → identical 且 version 为 null（首次保存从 1 起）', () => {
    const outcome = mergeDrafts(null, null);
    expect(outcome.kind).toBe('identical');
    if (outcome.kind === 'identical') {
      expect(outcome.answers).toEqual({});
      expect(outcome.version).toBeNull();
    }
  });

  it('护栏：合并不修改入参记录（纯函数）', () => {
    const server = draft(5, { '1': 'A' });
    const local = draft(3, { '1': 'B' });
    mergeDrafts(server, local);
    expect(server).toEqual({ version: 5, savedAt: null, answers: { '1': 'A' } });
    expect(local).toEqual({ version: 3, savedAt: null, answers: { '1': 'B' } });
  });
});

describe('resolveSeed 播种决策', () => {
  it('conflict：默认播种后端那份，本地那份交给界面提示（不静默覆盖）', () => {
    const outcome = mergeDrafts(draft(4, { '1': 'A' }), draft(4, { '1': 'C' }));
    const seed = resolveSeed(outcome);
    expect(seed.answers).toEqual({ '1': 'A' });
    expect(seed.conflict?.answers).toEqual({ '1': 'C' });
    expect(seed.version).toBe(4);
  });

  it('非 conflict：带出采用方的 version、无冲突提示', () => {
    const seed = resolveSeed(mergeDrafts(draft(1, { '1': 'A' }), draft(6, { '1': 'B' })));
    expect(seed.answers).toEqual({ '1': 'B' });
    expect(seed.conflict).toBeNull();
    expect(seed.version).toBe(6);
  });
});

describe('serverDraftOf 契约窄化', () => {
  it('draftVersion 数字、answers 经 answerMapOf 窄化', () => {
    const record = serverDraftOf({ answers: { '1': 'A', '2': 3 }, draftVersion: 5 }, answerMapOf);
    expect(record.version).toBe(5);
    expect(record.answers).toEqual({ '1': 'A', '2': '3' });
  });

  it('后端无草稿：draftVersion 缺失 → null', () => {
    const record = serverDraftOf({ answers: null, draftVersion: null }, answerMapOf);
    expect(record.version).toBeNull();
    expect(hasDraftContent(record)).toBe(false);
  });
});

describe('sameAnswers / hasDraftContent 辅助', () => {
  it('键集合不同即不等；值不同即不等', () => {
    expect(sameAnswers({ '1': 'A' }, { '1': 'A' })).toBe(true);
    expect(sameAnswers({ '1': 'A' }, { '1': 'A', '2': 'B' })).toBe(false);
    expect(sameAnswers({ '1': 'A' }, { '1': 'B' })).toBe(false);
  });

  it('空 answers 不算有草稿内容', () => {
    expect(hasDraftContent(null)).toBe(false);
    expect(hasDraftContent(draft(1, {}))).toBe(false);
    expect(hasDraftContent(draft(1, { '1': 'A' }))).toBe(true);
  });
});
