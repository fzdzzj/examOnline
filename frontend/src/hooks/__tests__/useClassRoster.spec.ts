/**
 * 班级学生归属操作（入班 / 转班 / 移出）的分支覆盖。
 *
 * 判据来自后端 `ClassService`：成功只回 `ApiResponse<Void>`（无 data），
 * 失败一律带中文 message——所以这里断言的是
 * ① 成功后通知刷新了**哪些**班级，② 失败原因是否原样透出、且不得触发刷新。
 */
import { describe, expect, it, vi } from 'vitest';

import { createClassRoster } from '@/hooks/useClassRoster';

function backendError(message: string): Error {
  // apiClient 的响应拦截器把后端 envelope.message 拼进 ApiError.message（见 errorMap）
  return Object.assign(new Error(`请求的资源不存在（${message}）`), { name: 'ApiError' });
}

describe('入班', () => {
  it('成功：按 (班级, 学生) 调后端，并只刷新当前班名单', async () => {
    const join = vi.fn().mockResolvedValue(undefined);
    const onChanged = vi.fn();
    const roster = createClassRoster({ join, transfer: vi.fn(), remove: vi.fn(), onChanged });

    await expect(roster.joinStudent(7, 12)).resolves.toBe(true);

    expect(join).toHaveBeenCalledWith(7, 12);
    expect(onChanged).toHaveBeenCalledWith([7]);
    expect(roster.state.error).toBeNull();
    expect(roster.state.pending).toBeNull();
  });

  it('后端拒绝「该学生已在该班级」：透出原因，不刷新、不误报成功', async () => {
    const onChanged = vi.fn();
    const roster = createClassRoster({
      join: vi.fn().mockRejectedValue(backendError('该学生已在该班级')),
      transfer: vi.fn(),
      remove: vi.fn(),
      onChanged,
    });

    await expect(roster.joinStudent(7, 12)).resolves.toBe(false);

    expect(onChanged).not.toHaveBeenCalled();
    expect(roster.state.error).toContain('该学生已在该班级');
  });
});

describe('转班', () => {
  it('成功：路径班级=源班、body 带 targetClassId，且两个班的名单都要刷新', async () => {
    const transfer = vi.fn().mockResolvedValue(undefined);
    const onChanged = vi.fn();
    const roster = createClassRoster({
      join: vi.fn(),
      transfer,
      remove: vi.fn(),
      onChanged,
    });

    await expect(roster.transferStudent(3, 12, 5)).resolves.toBe(true);

    expect(transfer).toHaveBeenCalledWith(3, 12, 5);
    expect(onChanged).toHaveBeenCalledWith([3, 5]);
  });

  it('失败分支「学生不属于原班级」（后端 404 该学生不在当前班级）：不刷新任何班级', async () => {
    const onChanged = vi.fn();
    const roster = createClassRoster({
      join: vi.fn(),
      transfer: vi.fn().mockRejectedValue(backendError('该学生不在当前班级')),
      remove: vi.fn(),
      onChanged,
    });

    await expect(roster.transferStudent(3, 12, 5)).resolves.toBe(false);

    expect(onChanged).not.toHaveBeenCalled();
    expect(roster.state.error).toContain('该学生不在当前班级');
    // pending 按学生维度键控，失败后必须复位，否则该行按钮永久转圈
    expect(roster.state.pending).toBeNull();
  });

  it('失败分支「学生已在目标班级」（后端 1001）：原因原样透出', async () => {
    const roster = createClassRoster({
      join: vi.fn(),
      transfer: vi.fn().mockRejectedValue(backendError('该学生已在目标班级')),
      remove: vi.fn(),
      onChanged: vi.fn(),
    });

    await expect(roster.transferStudent(3, 12, 5)).resolves.toBe(false);
    expect(roster.state.error).toContain('该学生已在目标班级');
  });
});

describe('移出与状态', () => {
  it('成功：DELETE 带 (班级, 学生) 两个路径参数并刷新该班', async () => {
    const remove = vi.fn().mockResolvedValue(undefined);
    const onChanged = vi.fn();
    const roster = createClassRoster({ join: vi.fn(), transfer: vi.fn(), remove, onChanged });

    await expect(roster.removeStudent(7, 12)).resolves.toBe(true);

    expect(remove).toHaveBeenCalledWith(7, 12);
    expect(onChanged).toHaveBeenCalledWith([7]);
  });

  it('pending 按动作+学生键控，供逐行 loading 判定', async () => {
    let release: () => void = () => {};
    const roster = createClassRoster({
      join: vi.fn(),
      transfer: vi.fn(),
      remove: () => new Promise<void>((resolve) => (release = resolve)),
      onChanged: vi.fn(),
    });

    const running = roster.removeStudent(7, 12);
    expect(roster.state.pending).toBe('remove:12');
    release();
    await running;
    expect(roster.state.pending).toBeNull();
  });

  it('clearError：教师关掉提示后不会残留上一次失败原因', async () => {
    const roster = createClassRoster({
      join: vi.fn().mockRejectedValue(new Error('无法连接服务器')),
      transfer: vi.fn(),
      remove: vi.fn(),
      onChanged: vi.fn(),
    });
    await roster.joinStudent(1, 2);
    expect(roster.state.error).toBe('无法连接服务器');

    roster.clearError();
    expect(roster.state.error).toBeNull();
  });
});
