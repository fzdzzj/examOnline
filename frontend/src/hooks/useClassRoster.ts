/**
 * 班级学生归属操作（入班 / 转班 / 移出）的状态收敛工厂。
 *
 * 设计成依赖注入（同 `useRandomDraw` 的 `createDrawFlow`）：三个动作由调用方注入
 * （生产传 `gen:api` 生成客户端 + `apiClient`，单测传 mock），这样
 * 「成功 → 通知刷新哪些班」「失败 → 原样透出后端原因」两条分支能被纯逻辑单测覆盖。
 *
 * ⚠️ 三条不变式：
 * 1. **归属裁决在后端**（`ClassService.getOwnedClass` + `OwnershipGuard`）——本文件不判断
 *    "这个班是不是我的"，隐藏按钮不是安全边界，越权点了后端会返 403；
 * 2. **提示文案取后端**：`deps` 抛出的 `ApiError.message` 已由 `errorMap` 把后端
 *    `message`（如「该学生不在当前班级」「该学生已在目标班级」）拼进来，这里只透传不改写；
 * 3. **转班只影响两个班的名单**：`onChanged` 因此收到源班与目标班两个 id
 *    （后端 `transfer` 仅改 `user_class.class_id`，成绩随人，见 §12.6）。
 */
import { reactive } from 'vue';

export interface RosterActionState {
  /** 正在进行的动作 key（`join` / `transfer` / `remove:<userId>`），用于按钮 loading */
  pending: string | null;
  /** 上一次失败的后端原因；成功后清空 */
  error: string | null;
}

export interface RosterDeps {
  /** POST /api/classes/{id}/students */
  join: (classId: number, userId: number) => Promise<unknown>;
  /** PUT /api/classes/{id}/students/{userId}/transfer */
  transfer: (classId: number, userId: number, targetClassId: number) => Promise<unknown>;
  /** DELETE /api/classes/{id}/students/{userId} */
  remove: (classId: number, userId: number) => Promise<unknown>;
  /** 成功后告知需要重新拉取的班级 id 列表（转班为两个班） */
  onChanged: (classIds: number[]) => void;
}

/** 后端 message 优先，兜底文案只说明"哪个动作失败"，不猜测原因。 */
function toMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

export function createClassRoster(deps: RosterDeps) {
  const state = reactive<RosterActionState>({ pending: null, error: null });

  async function run(
    key: string,
    fallback: string,
    action: () => Promise<unknown>
  ): Promise<boolean> {
    state.pending = key;
    state.error = null;
    try {
      await action();
      return true;
    } catch (error) {
      state.error = toMessage(error, fallback);
      return false;
    } finally {
      state.pending = null;
    }
  }

  /** 入班：成功返回 true 并只刷新当前班；「该学生已在该班级」一类拒绝走 error 分支。 */
  async function joinStudent(classId: number, userId: number): Promise<boolean> {
    const ok = await run('join', '入班失败，请稍后重试', () => deps.join(classId, userId));
    if (ok) deps.onChanged([classId]);
    return ok;
  }

  /**
   * 转班：源班级路径 + 目标班级在 body 里（契约 `TransferRequest.targetClassId`）。
   * 成功要刷新**两个班**——源班少一人、目标班多一人，名单都变了。
   */
  async function transferStudent(
    classId: number,
    userId: number,
    targetClassId: number
  ): Promise<boolean> {
    const ok = await run(`transfer:${userId}`, '转班失败，请稍后重试', () =>
      deps.transfer(classId, userId, targetClassId)
    );
    if (ok) deps.onChanged([classId, targetClassId]);
    return ok;
  }

  /** 移出：关联不存在时后端返 404「该学生不在该班级」，同样只透出原因。 */
  async function removeStudent(classId: number, userId: number): Promise<boolean> {
    const ok = await run(`remove:${userId}`, '移出班级失败，请稍后重试', () =>
      deps.remove(classId, userId)
    );
    if (ok) deps.onChanged([classId]);
    return ok;
  }

  function clearError(): void {
    state.error = null;
  }

  return { state, joinStudent, transferStudent, removeStudent, clearError };
}

export type ClassRoster = ReturnType<typeof createClassRoster>;
