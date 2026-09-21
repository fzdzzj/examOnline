/**
 * 草稿保守合并纯函数（阶段 22 第 2 片）。
 *
 * ## 为什么必须是纯函数
 *
 * 硬约定 7：后端草稿与 IndexedDB 本地缓存冲突时「以时间戳 / version 较新的一方为准；
 * **无法判定新旧时，保留两份并提示学生，绝不静默覆盖**」。合并规则若混在组件里，
 * 就没法对三个分支（后端较新 / 本地较新 / 二者相同）直接做单测——那正是
 * `tasks.json` 任务 3 第 4 条的验收口子。
 *
 * ## 为什么不用时间戳比较
 *
 * 后端 `savedTime` 是 `LocalDateTime.toString()`（无时区），本地写入时刻来自学生本机
 * 时钟——**学生可改表**，前端任何用本地时刻参与判定的路径都是防线漏洞（硬约定 2 同源）。
 * 所以版本判定只认 `version`（后端 `ExamDraftService` 单调受理的业务版本），
 * `savedAt` 只做展示与日志，**不参与任何比较**。
 *
 * ## version 的语义（读 `ExamDraftService` 核实）
 *
 * - 后端只拒 `incoming < stored.version` 的写入（多端冲突，`accepted=false`）；
 * - `version` 的递增是**保存成功方**的责任：本模块的调用方（`useAutoSaveDraft`）
 *   每次成功保存后 `version+1`，IndexedDB 里存的是「最后一次与后端对齐的版本」。
 */
import type { AnswerMap } from '@/utils/studentTaking';

/** 一份草稿：version 为 null 表示「从未被后端确认过」（本地-only 内容）。 */
export interface DraftRecord {
  version: number | null;
  /** 展示/日志用（后端 savedTime 或本地写入的 ISO 时刻），不参与新旧比较。 */
  savedAt: string | null;
  answers: AnswerMap;
}

/** 合并结果：`conflict` 时两份原样保留在 `server` / `local` 字段里，由界面提示学生裁决。 */
export type DraftMergeOutcome =
  | { kind: 'server'; answers: AnswerMap; version: number | null }
  | { kind: 'local'; answers: AnswerMap; version: number | null }
  | { kind: 'identical'; answers: AnswerMap; version: number | null }
  | {
      kind: 'conflict';
      server: DraftRecord;
      local: DraftRecord;
      /** version 相同但内容不同（最典型：本地改了没保存成功，另一端又存了同版本）/ version 不可比且内容不同 */
      reason: 'version-tied-content-differs' | 'version-not-comparable';
    };

/** 判断一份草稿是否「有内容」（空 answers 视为没有草稿，不构成冲突方）。 */
export function hasDraftContent(record: DraftRecord | null | undefined): boolean {
  return record !== null && record !== undefined && Object.keys(record.answers).length > 0;
}

/** AnswerMap 浅比较：键集合一致且每个值全等（答案值一律是 string，无嵌套）。 */
export function sameAnswers(a: AnswerMap, b: AnswerMap): boolean {
  const keysA = Object.keys(a);
  const keysB = Object.keys(b);
  if (keysA.length !== keysB.length) return false;
  return keysA.every((key) => a[key] === b[key]);
}

/**
 * 保守合并：后端草稿 × IndexedDB 本地缓存 → 播种答案。
 *
 * 判定顺序（每一步都先问「能不能确定谁新」，确定不了就走 `conflict`）：
 * 1. 只有一方有内容 → 用那一方（另一方没得争）；
 * 2. 双方 version 可比（都是非 null）→ 取较大者；相等时内容一致算同一状态，
 *   内容不同 → **conflict**（本地改了没存上 vs 另一端存了同版本，无法分辨）；
 * 3. version 不可比（任一为 null）且内容不同 → **conflict**（没有可信依据选边，
 *   也不拿 savedAt 猜——见文件头「为什么不用时间戳比较」）。
 *
 * `conflict` 的消费约定：两份记录**都不删**（后端那份在 Redis、本地那份在 IndexedDB），
 * 界面提示学生并允许显式选择；默认播种后端那份（后端是超时兜底收卷的答案来源，
 * `ExamSweepService` 从 Redis 草稿取答案）。
 */
export function mergeDrafts(
  server: DraftRecord | null,
  local: DraftRecord | null
): DraftMergeOutcome {
  const serverHas = hasDraftContent(server);
  const localHas = hasDraftContent(local);

  if (!serverHas && !localHas) {
    return { kind: 'identical', answers: {}, version: null };
  }
  if (!localHas) {
    const s = server as DraftRecord;
    return { kind: 'server', answers: { ...s.answers }, version: s.version };
  }
  if (!serverHas) {
    const l = local as DraftRecord;
    return { kind: 'local', answers: { ...l.answers }, version: l.version };
  }

  const s = server as DraftRecord;
  const l = local as DraftRecord;

  if (s.version !== null && l.version !== null) {
    if (s.version > l.version)
      return { kind: 'server', answers: { ...s.answers }, version: s.version };
    if (l.version > s.version)
      return { kind: 'local', answers: { ...l.answers }, version: l.version };
    // version 相同：内容一致 → 同一状态；不一致 → 无法判定谁新（保守：都不覆盖）
    if (sameAnswers(s.answers, l.answers)) {
      return { kind: 'identical', answers: { ...s.answers }, version: s.version };
    }
    return { kind: 'conflict', server: s, local: l, reason: 'version-tied-content-differs' };
  }

  if (sameAnswers(s.answers, l.answers)) {
    // 内容一致但 version 不可比（一方为 null）：取较大者，理由见 resolveSeed 注释
    const version = Math.max(s.version ?? 0, l.version ?? 0);
    return { kind: 'identical', answers: { ...s.answers }, version: version > 0 ? version : null };
  }
  return { kind: 'conflict', server: s, local: l, reason: 'version-not-comparable' };
}

/** 后端 `EnterExamResponse`（进入考试响应）里能构成服务端草稿的字段。 */
export interface ServerDraftSource {
  answers?: unknown;
  draftVersion?: number | null;
}

/**
 * 进入考试的响应 → 服务端草稿记录。
 * `answers` 经 `answerMapOf` 窄化（结构不符当无草稿，不把脏数据放进合并）。
 */
export function serverDraftOf(
  source: ServerDraftSource | null | undefined,
  answerMapOf: (node: unknown) => AnswerMap
): DraftRecord {
  return {
    version: typeof source?.draftVersion === 'number' ? source.draftVersion : null,
    savedAt: null,
    answers: answerMapOf(source?.answers ?? null),
  };
}

/**
 * 播种决策：合并结果 → 最终答案、冲突提示与起始 version。
 *
 * 页面挂载时调一次：`mergeDrafts` 出 `conflict` 时默认播种后端草稿（保守，理由见
 * `mergeDrafts` 注释），同时把本地那份原样返回给界面做「改用本地答案」的提示——
 * 学生主动点按钮换成本地，不属于静默覆盖。
 *
 * `version` 是播种后自动保存的起始版本：采用哪份内容就用哪份的 version；
 * `identical`（内容一致，含双方皆空）取较大者——内容相同时版本号不引入内容偏差，
 * 而较大者更接近后端当前状态（比如服务端草稿 TTL 过期丢了，本地还留着 v3）。
 */
export function resolveSeed(outcome: DraftMergeOutcome): {
  answers: AnswerMap;
  conflict: DraftRecord | null;
  version: number | null;
} {
  if (outcome.kind === 'conflict') {
    return {
      answers: { ...outcome.server.answers },
      conflict: outcome.local,
      version: outcome.server.version,
    };
  }
  return { answers: { ...outcome.answers }, conflict: null, version: outcome.version };
}
