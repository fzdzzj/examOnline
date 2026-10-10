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

/**
 * 自动保存周期（阶段 22 第 2 片）。
 *
 * 30s 来自**后端设计假设**，不是前端拍脑袋的值：
 * - `ExamDraftService` javadoc：「前端每 30s 推送一次答案到 Redis」；
 * - 30s 自动保存同时是监考在线心跳的数据源（`presenceService.touch`）——
 *   周期拉长会让监考大屏把在线学生误判成离线；
 * - 5000 并发交卷压测是按这个负载模型做的，缩短周期等于给后端造一个
 *   它没被压测过的负载模式（硬约定 6）。
 */
export const AUTOSAVE_INTERVAL_MS = 30_000;

/**
 * 输入防抖窗口（阶段 22 第 2 片）。
 *
 * 硬约定 6：「输入用防抖，防抖间隔不得小于后端设计假设」——后端假设就是 30s，
 * 所以防抖窗口与 `AUTOSAVE_INTERVAL_MS` 同值：学生停止输入 30s 后才允许触发
 * 一次保存请求。两者共同保证**任何两次相邻的后端草稿保存之间至少间隔 30s**：
 * 防抖挡住「输入触发」的高频路径，定时器兜底「连续输入不断重置防抖」的长尾。
 * 想改这两个值，先去读后端 `ExamDraftService` 与压测容量模型。
 */
export const AUTOSAVE_DEBOUNCE_MS = 30_000;

/**
 * 交卷来源（阶段 22 第 3 片）。
 *
 * 取值逐字对齐后端 `SubmitRequest.TYPE_MANUAL / TYPE_COUNTDOWN_ZERO`
 * （`src/main/java/com/exam/taking/dto/SubmitRequest.java`）：手动交卷传 MANUAL，
 * 倒计时归零自动交卷传 COUNTDOWN_ZERO——后端据此区分 submitType 落库，
 * 第三路（后端兜底扫描）不走这个端点，前端不模拟它。
 *
 * 手动与归零**共用同一个提交函数**（硬约定 4）：这里只是传给后端的来源标记，
 * 不是两条提交路径。
 */
export const SUBMIT_TYPE = {
  MANUAL: 'MANUAL',
  COUNTDOWN_ZERO: 'COUNTDOWN_ZERO',
} as const;

export type SubmitType = (typeof SUBMIT_TYPE)[keyof typeof SUBMIT_TYPE];

/**
 * 前端可上报的行为事件（阶段 22 第 3 片）。
 *
 * **只登记后端已注册采集策略的两个值**（`anticheat/collector/BehaviorEventTypes.java`）：
 * SWITCH_SCREEN（切屏）与 WINDOW_BLUR（失焦）。后端另有 DRAFT_CONFLICT /
 * PAGE_REFRESH / SUBMIT_ANOMALY / 兜底 UNKNOWN，但那些由后端自产或走兜底，
 * 前端不上报、不发明第三种类型（硬约定 9）。
 *
 * severity **不在前端上报**：后端 `BehaviorReportRequest` 根本没有该字段，
 * 严重度由后端采集策略（`SwitchScreenEventCollector` 等）判定——前端想「调高」
 * 也没有入口，这正是硬约定 9 要的形态。
 */
export const BEHAVIOR_EVENT = {
  SWITCH_SCREEN: 'SWITCH_SCREEN',
  WINDOW_BLUR: 'WINDOW_BLUR',
} as const;

export type BehaviorEventType = (typeof BEHAVIOR_EVENT)[keyof typeof BEHAVIOR_EVENT];

/**
 * 离线保护与重入显性文案常量（创新点 5 二期）。
 *
 * 措辞纪律：
 * 严禁出现「离线考试 / 离线作答 / offline exam / offline answer」字样；
 * 统一表述为「离线保护中 / 本地保存 / 离线重入中 / 题目来自本机缓存 / 离线缓存」。
 */
export const OFFLINE_REENTRY_NOTICE = {
  TITLE: '离线重入中，题目来自本机缓存',
  RISK_WARNING:
    '若超过考试截止时间仍未恢复网络，断网期间新保存的答案可能无法补交，以系统收卷为准。',
  DESCRIPTION:
    '当前网络已断开，正以本机缓存的试卷快照继续作答。若超过考试截止时间仍未恢复网络，断网期间新保存的答案可能无法补交，以系统收卷为准。',
} as const;

export const OFFLINE_STATUS_BANNER_TEXT = {
  MESSAGE: '离线保护中，答案已本地保存',
  DESCRIPTION:
    '当前网络连接已断开或草稿同步未送达服务器。您的答案已实时保存在本机，请继续作答；恢复网络后系统将自动同步。若超过考试截止时间仍未恢复网络，断网期间新保存的答案可能无法补交，以系统收卷为准。',
} as const;

export const OFFLINE_LIST_NOTICE = {
  BADGE: '离线缓存',
  ALERT_MESSAGE: '当前处于离线状态，展示本机缓存的考试列表',
  ALERT_DESCRIPTION:
    '若超过考试截止时间仍未恢复网络，断网期间新保存的答案可能无法补交，以系统收卷为准。',
} as const;
