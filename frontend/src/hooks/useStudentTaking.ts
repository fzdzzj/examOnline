/**
 * 学生端「考试列表 + 进入考试」的查询选项工厂（阶段 22 第 1 片）。
 *
 * 为什么单独成文件（沿用 `hooks/useExamMonitor.ts` 的做法）：
 * 「进入考试走哪个端点」「examId 非法时不发请求」「入口可用性只读后端 `canEnter`」
 * 这三条必须能被单测直接断言；写在页面里就得 mount + 真 QueryClient 才看得见。
 *
 * ⚠️ 三条不变式：
 * 1. **进入即拉个人快照**走 `POST /api/exam-taking/exams/{examId}/enter`——后端对同一答卷
 *    重复进入是幂等的（`ExamTakingService.enter`：撞 `uk_exam_student` 唯一索引时回读原答卷），
 *    所以刷新页面重发这个请求**不会换题**，这正是 spec「进入即锁定个人快照」的实现前提。
 *    前端不自建第二份快照缓存去跟它比。
 * 2. **会话唯一性不在前端**：同一学生同一考试只有一个进行中答卷由后端唯一索引保证，
 *    冲突时后端抛业务码，`apiClient` 拦截器已把它转成 `ApiError`。这里不加任何本地锁。
 * 3. `canEnter` 缺失即视为不可进入（fail-closed），**不**按时间窗推断能不能交卷。
 */
import type { EnterExamResponse, ExamListItem } from '@/api/axios';

/** 考试列表查询：后端 `GET /api/exam-taking/exams` 已经分好组，前端不再按时间重排或过滤。 */
export interface StudentExamsQueryOptions {
  queryKey: readonly ['student', 'exams'];
  queryFn: () => Promise<ExamListItem[] | undefined>;
}

export function createStudentExamsQueryOptions(
  fetchExams: () => Promise<ExamListItem[] | undefined>
): StudentExamsQueryOptions {
  return {
    queryKey: ['student', 'exams'] as const,
    queryFn: fetchExams,
  };
}

/** 进入/续答查询：`fetchPaper` 在生产里绑 `enter`（POST，幂等），单测里绑 mock。 */
export interface EnterExamQueryOptions {
  queryKey: readonly ['student', 'exam', number, 'enter'];
  queryFn: () => Promise<EnterExamResponse | undefined>;
  /** 路由参数缺失或非法时不发请求（也不渲染答题区） */
  enabled: boolean;
}

export function createEnterExamQueryOptions(
  examId: number,
  fetchPaper: (examId: number) => Promise<EnterExamResponse | undefined>
): EnterExamQueryOptions {
  return {
    queryKey: ['student', 'exam', examId, 'enter'] as const,
    queryFn: () => fetchPaper(examId),
    enabled: Number.isInteger(examId) && examId > 0,
  };
}

/** 路由参数字符串 → examId；解析不出正整数返回 NaN，由 `enabled` 挡掉请求。 */
export function examIdOf(raw: string | string[] | undefined): number {
  const value = Array.isArray(raw) ? raw[0] : raw;
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : Number.NaN;
}

/**
 * 「进入考试」按钮的唯一判据：后端 `canEnter` 原值。
 *
 * 写成显式 `=== true` 而不是 `!!`，是为了让 `undefined`（后端没给）落到 false 上——
 * 契约字段在生成的类型里全是可选的，缺字段时把入口开出来等于前端替后端做了许可决定。
 */
export function canEnterOf(item: ExamListItem): boolean {
  return item.canEnter === true;
}

/** 后端 `status`：1 进行中 / 2 已交卷。已交卷时后端把 `questions` 置空、`remainingSeconds` 置 0。 */
export function isClosedByBackend(snapshot: EnterExamResponse | null | undefined): boolean {
  return snapshot?.status === 2;
}
