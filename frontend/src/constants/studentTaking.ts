/**
 * 学生端作答常量（阶段 22 第 1 片）。
 *
 * ⚠️ 这里只登记**后端已经给出的枚举值**，不新增任何前端侧状态：
 * - `group` 的取值来自 `ExamListItem.GROUP_*`（`src/main/java/com/exam/taking/dto/ExamListItem.java`）；
 * - `status` / `submissionStatus` 的取值来自 `ExamSubmission.STATUS_*`（1 进行中、2 已交卷）；
 * - 题型的取值来自 `QuestionType`（1 单选 2 多选 3 判断 4 简答），与阶段 20 的
 *   `components/question/questionTypeConfig.ts` 同源，但那是**编辑**表单配置，
 *   这里是**作答**控件配置，两者职责不同，不复用同一份 `fields`/校验规则。
 *
 * 三态一律以后端返回的 `group` 为准：前端**不**用 `startTime` / `endTime` / `Date.now()`
 * 推断"现在到底算不算进行中"（spec-delta「状态一律取后端返回」，也见硬约定 2）。
 */

/** 后端 `ExamListItem.group` 的三个取值（其它值按「未知分组」原样呈现，不做兜底推断）。 */
export const EXAM_GROUP = {
  UPCOMING: 'UPCOMING',
  ONGOING: 'ONGOING',
  FINISHED: 'FINISHED',
} as const;

export type ExamGroup = (typeof EXAM_GROUP)[keyof typeof EXAM_GROUP];

/**
 * 分组标签：措辞照搬 `ExamListItem` 类注释里的分组口径（待考 / 进行中 / 已完成）。
 * 后端没给或给了未知值时返回 `—`，**不**猜一个状态出来。
 */
export const EXAM_GROUP_LABELS: Record<ExamGroup, string> = {
  [EXAM_GROUP.UPCOMING]: '待考（未开始/尚未进入）',
  [EXAM_GROUP.ONGOING]: '进行中（可续答）',
  [EXAM_GROUP.FINISHED]: '已完成（已交卷或已结束）',
};

/** 未知分组的显示占位——与「已完成」区分开，避免把契约漂移伪装成正常状态。 */
export const UNKNOWN_GROUP_LABEL = '后端未返回分组';

/** 个人答卷状态（`ExamSubmission.STATUS_*`）：已交卷时后端把 questions 置空、remainingSeconds 置 0。 */
export const SUBMISSION_STATUS = {
  IN_PROGRESS: 1,
  SUBMITTED: 2,
} as const;

/**
 * 倒计时 tick 周期。**这不是自动保存周期**——30s 草稿周期属第 2 片，
 * 在这里写它只会诱使下一片复用同一个常量而改掉后端假设。
 */
export const COUNTDOWN_TICK_MS = 1000;

/**
 * 「最后 5 分钟警告」阈值（`tasks.json` 阶段 2 第 3 条）。
 *
 * 纯**展示**阈值：只决定要不要多提示一句，不参与任何判定——
 * 是否超时、能否交卷、锁定作答都仍由后端说了算。
 */
export const NEAR_END_WARNING_THRESHOLD_SECONDS = 300;
