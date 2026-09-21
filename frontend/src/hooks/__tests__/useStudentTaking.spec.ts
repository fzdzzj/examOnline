/**
 * 「进入考试」链路的查询选项与入口判据（阶段 22 第 1 片）。
 *
 * 断言重心不是"前端算得对"，而是**前端没有替后端做决定**：
 * `canEnter` 缺失时关入口而不是开入口、examId 非法时不发请求、
 * 列表原样透出后端分组结果而不二次过滤。
 */
import { describe, expect, it, vi } from 'vitest';

import type { EnterExamResponse, ExamListItem } from '@/api/axios';
import {
  canEnterOf,
  createEnterExamQueryOptions,
  createStudentExamsQueryOptions,
  examIdOf,
  isClosedByBackend,
} from '@/hooks/useStudentTaking';

const SNAPSHOT: EnterExamResponse = {
  examId: 9,
  submissionId: 77,
  status: 1,
  remainingSeconds: 1800,
  questions: [{ number: 1, questionId: 11, type: 1, content: '1+1=?', choices: ['1', '2'] }],
};

describe('考试列表查询', () => {
  it('queryKey 固定为 ["student","exams"]：后端已分组，不需要按时间窗派生出第二套 key', () => {
    const options = createStudentExamsQueryOptions(vi.fn());
    expect(options.queryKey).toEqual(['student', 'exams']);
  });

  it('queryFn 原样透出后端返回的分组结果，不排序、不过滤、不补字段', async () => {
    const rows: ExamListItem[] = [
      { examId: 1, title: '早考试', group: 'FINISHED', canEnter: false },
      { examId: 2, title: '进行中考试', group: 'ONGOING', canEnter: true, remainingSeconds: 900 },
    ];
    const options = createStudentExamsQueryOptions(vi.fn().mockResolvedValue(rows));

    await expect(options.queryFn()).resolves.toBe(rows);
  });

  it('后端报错时异常照原样抛出，页面才有机会区分"没考试"和"拉不到考试"', async () => {
    const options = createStudentExamsQueryOptions(
      vi.fn().mockRejectedValue(new Error('无法连接服务器'))
    );
    await expect(options.queryFn()).rejects.toThrow('无法连接服务器');
  });
});

describe('进入考试（拉个人快照）查询', () => {
  it('queryKey 按考试维度隔离，切考试不会复用上一场的快照', () => {
    expect(createEnterExamQueryOptions(1, vi.fn()).queryKey).toEqual([
      'student',
      'exam',
      1,
      'enter',
    ]);
    expect(createEnterExamQueryOptions(2, vi.fn()).queryKey).not.toEqual(
      createEnterExamQueryOptions(1, vi.fn()).queryKey
    );
  });

  it('examId 非法（NaN / 0 / 负数 / 小数）时 enabled=false，不发无意义请求', () => {
    for (const bad of [Number.NaN, 0, -3, 1.5]) {
      expect(createEnterExamQueryOptions(bad, vi.fn()).enabled).toBe(false);
    }
    expect(createEnterExamQueryOptions(9, vi.fn()).enabled).toBe(true);
  });

  it('queryFn 把 examId 传给注入的端点并原样返回快照', async () => {
    const enter = vi.fn().mockResolvedValue(SNAPSHOT);
    await expect(createEnterExamQueryOptions(9, enter).queryFn()).resolves.toBe(SNAPSHOT);
    expect(enter).toHaveBeenCalledWith(9);
  });
});

describe('examIdOf —— 路由参数窄化', () => {
  it('只认正整数字符串，其它一律 NaN', () => {
    expect(examIdOf('9')).toBe(9);
    expect(examIdOf(['9'])).toBe(9);
    expect(Number.isNaN(examIdOf('abc'))).toBe(true);
    expect(Number.isNaN(examIdOf('0'))).toBe(true);
    expect(Number.isNaN(examIdOf('-2'))).toBe(true);
    expect(Number.isNaN(examIdOf(undefined))).toBe(true);
  });
});

describe('canEnterOf / isClosedByBackend —— 只认后端返回', () => {
  it('canEnter 只有显式 true 才放开入口（fail-closed）', () => {
    expect(canEnterOf({ examId: 1, canEnter: true })).toBe(true);
    expect(canEnterOf({ examId: 1, canEnter: false })).toBe(false);
    // 后端没给这个字段时把入口开出来，等于前端替后端批了许可
    expect(canEnterOf({ examId: 1 })).toBe(false);
    expect(canEnterOf({ examId: 1, canEnter: undefined })).toBe(false);
  });

  it('答卷是否封闭按后端 status=2 判定，不看本地倒计时', () => {
    expect(isClosedByBackend({ status: 2 })).toBe(true);
    expect(isClosedByBackend({ status: 1 })).toBe(false);
    expect(isClosedByBackend({})).toBe(false);
    expect(isClosedByBackend(null)).toBe(false);
    expect(isClosedByBackend(undefined)).toBe(false);
  });
});
