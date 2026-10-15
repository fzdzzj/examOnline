/**
 * 学生端作答的纯函数层（阶段 22 第 1 片）。
 *
 * 全部是**无副作用、无计时器、无 DOM** 的函数，之所以把契约窄化与状态计算都塞在这里：
 * 1. 契约里 `answers` / `choices` 是 `JsonNode`（生成类型 `unknown`），必须一次性窄化，
 *    组件里再各自 `as` 一遍就会四处撒 `any`（硬约定 15）；
 * 2. 「已答 / 未答」与倒计时格式化是**交卷确认与导航的唯一算法来源**（第 3 片的
 *    「未答题数」提示要复用 `navStatesOf`），写成纯函数才谈得上单测。
 *
 * ⚠️ 两条不变式：
 * - 状态与剩余时间**只读后端字段**，这里没有任何一个函数接受 `now: Date` 之类的入参去推断；
 * - 答案格式只按后端 `AnswerNormalizer` 的**规范形态**产出（单选 `A`、多选升序 `A,C`、
 *   判断 `T`/`F`、简答原文），别名归一是后端职责，前端不复刻（阶段 20 硬约定 4 同源）。
 */
import type { JsonNode, QuestionView } from '@/api/axios';
import { EXAM_GROUP_LABELS, UNKNOWN_GROUP_LABEL, type ExamGroup } from '@/constants/studentTaking';

/** 后端 `ExamListItem.group` → 展示文案；未知值不猜状态。 */
export function groupLabelOf(group: string | null | undefined): string {
  if (group === null || group === undefined) return UNKNOWN_GROUP_LABEL;
  return EXAM_GROUP_LABELS[group as ExamGroup] ?? UNKNOWN_GROUP_LABEL;
}

/** questionId → 答案原文（后端草稿就是这一层映射，键为字符串化的 questionId）。 */
export type AnswerMap = Record<string, string>;

/**
 * 疑难标记集合（Mark for Review 🚩，阶段 polish 变更）。
 *
 * 用 **questionId** 作为标记主键：它与答案 `answerKeyOf` 同源，是作答与判分的真实
 * 主键（`QuestionView` 注释）。内部状态用 `Set<number>` 表达（去重、快速判含），
 * 而 `DraftRecord.marked` / `SaveDraftRequest.marked` 的持久化形态是 `number[]`，
 * 由 `markedIdsOf` / `markedOf` 负责双向转换——状态与持久化分离，纯函数可测。
 */
export type MarkedIds = ReadonlySet<number>;

/**
 * 标记集合（Set 或持久化数组任一形态）→ 升序数字数组（稳定可比较；与后端 `marked`
 * 契约的数组形态一致）。接受 `number[]` 是为了让 `DraftRecord.marked` 与内部 Set 都能直接过。
 */
export function markedOf(ids: MarkedIds | readonly number[] | null | undefined): number[] {
  return Array.from(ids ?? []).sort((a, b) => a - b);
}

/** 持久化数组（如后端 `EnterExamResponse.marked` / 恢复的 `DraftRecord.marked`）→ 内部集合。 */
export function markedIdsOf(
  list: Array<number | null | undefined> | null | undefined
): Set<number> {
  const ids = new Set<number>();
  for (const item of list ?? []) {
    if (typeof item === 'number' && Number.isFinite(item)) ids.add(item);
  }
  return ids;
}

/**
 * 切换某题标记：返回**新** Set（不可变更新，供组件触发状态变更）。
 * questionId 缺失（无法稳定持久化的题）时不产生标记——不能拿可能漂移的题号当主键。
 */
export function toggleMarked(ids: MarkedIds, questionId: number | undefined): Set<number> {
  const next = new Set<number>(ids);
  if (questionId !== undefined) {
    if (next.has(questionId)) next.delete(questionId);
    else next.add(questionId);
  }
  return next;
}

/**
 * 草稿 `answers`（JsonNode）→ `AnswerMap`。
 * 只认「对象 + 值为字符串/数字」两种标量；结构不符就当没有草稿，
 * **不**把非法值塞进答案区（那会让下一片的合并纯函数拿到脏数据）。
 */
export function answerMapOf(node: JsonNode | null | undefined): AnswerMap {
  if (typeof node !== 'object' || node === null || Array.isArray(node)) return {};
  const result: AnswerMap = {};
  for (const [key, value] of Object.entries(node as Record<string, unknown>)) {
    if (typeof value === 'string') result[key] = value;
    else if (typeof value === 'number' && Number.isFinite(value)) result[key] = String(value);
  }
  return result;
}

/** 答案键：`questionId` 是作答与判分的真实主键（`QuestionView` 注释），缺失时退回题号。 */
export function answerKeyOf(question: QuestionView): string {
  return question.questionId !== undefined
    ? String(question.questionId)
    : `#${question.number ?? '?'}`;
}

/**
 * 选项窄化：个人快照里的 `choices` 是**已乱序并锁定**的字符串数组
 * （`PersonalPaperService.shuffleChoicesAndRemapAnswer`），判断题与简答题为 `null`。
 *
 * ⚠️ 渲染顺序即数组顺序——这里绝不排序、绝不洗牌。前端一旦重排，
 * 选项字母与后端重映射过的 `correctAnswer` 就对不上了，学生看到的 A 不是系统认的 A。
 */
export function choicesOf(question: QuestionView): string[] {
  const node = question.choices;
  if (!Array.isArray(node)) return [];
  return node.map((item) => (typeof item === 'string' ? item : JSON.stringify(item)));
}

/** 选项字母：第 i 个选项 → 'A' + i（与后端 `letterOfIndex` 约定一致，最多 26 项）。 */
export function letterOfChoiceIndex(index: number): string {
  return String.fromCharCode(65 + index);
}

/** 已选字母集合：`'A,C'` → `['A','C']`；空串/null → `[]`。 */
export function lettersOfAnswer(answer: string | null | undefined): string[] {
  return (answer ?? '')
    .split(',')
    .map((letter) => letter.trim().toUpperCase())
    .filter(Boolean);
}

/** 选择结果 → 提交值：去重 + 升序，与后端 `normalizeMultiple` 的存储口径一致。 */
export function answerOfLetters(letters: string[]): string {
  return Array.from(new Set(letters.map((letter) => letter.trim().toUpperCase()).filter(Boolean)))
    .sort()
    .join(',');
}

/**
 * 该答案算不算「已作答」。
 * 判断题要求落在 T/F 上，其余题型要求非空白——与后端「未作答」的口径对齐：
 * 只填了个空格不算答过。
 */
export function isAnswered(type: number | undefined, answer: string | undefined): boolean {
  const value = (answer ?? '').trim();
  if (!value) return false;
  if (type === 3) return value.toUpperCase() === 'T' || value.toUpperCase() === 'F';
  return true;
}

/** 导航单元：`number` 用于显示，`answered` 驱动「已答/未答」两色，`current` 高亮当前题，`marked` 呈 🚩 角标。 */
export interface NavItemState {
  index: number;
  number: number;
  answered: boolean;
  current: boolean;
  marked: boolean;
}

/**
 * 逐题导航状态（已答 / 未答 / 当前 / 标记）。
 * 第 3 片「二次确认提示未答题数」「跳转首道未答题」直接复用它的数组——
 * 所以这里不返回计数字段，计数与跳转均由调用方从同一份数组算，避免口径各说各话。
 *
 * `markedIds` 是可选的标记集合（questionId 主键）；缺省按空集处理（向后兼容
 * 既有第 1/3 片调用方，它们不传也要能正常工作）。
 */
export function navStatesOf(
  questions: readonly QuestionView[],
  answers: AnswerMap,
  currentIndex: number,
  markedIds: MarkedIds = new Set<number>()
): NavItemState[] {
  return questions.map((question, index) => ({
    index,
    number: question.number ?? index + 1,
    answered: isAnswered(question.type, answers[answerKeyOf(question)]),
    current: index === currentIndex,
    marked: question.questionId !== undefined && markedIds.has(question.questionId),
  }));
}

/**
 * 倒计时展示文案（mm:ss，超过一小时补成 h:mm:ss）。
 *
 * `null` 表示「后端没给剩余时间」——此时显示占位而不是 `00:00`，
 * 因为 `00:00` 会被读成「时间到了」，而那是后端才能宣布的事（硬约定 2）。
 */
export function formatCountdown(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || !Number.isFinite(seconds)) return '—';
  const total = Math.max(0, Math.floor(seconds));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const mmss = `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  return h > 0 ? `${h}:${mmss}` : mmss;
}

/**
 * 后端 `remainingSeconds` 的窄化：只有**非负有限数**才可信。
 * 其它一律 null —— 由倒计时 hook 呈现为「无服务端时间，不自行推算」。
 */
export function serverRemainingOf(value: number | null | undefined): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null;
}

/**
 * 离线倒计时估算校正（创新点 5 二期 · 阶段 3）。
 *
 * 规则：
 * remainingSeconds' = max(0, remainingSeconds − (当前墙钟 − capturedWallClock) / 1000)
 *
 * 关键约束：
 * - 纯数据层校正，不修改 useServerCountdown 本体；
 * - 仅影响前端倒计时显示，归零锁定作答，超时裁决 100% 在服务端。
 */
export function computeOfflineRemainingSeconds(
  remainingSeconds: number | null | undefined,
  capturedWallClock: number,
  nowWallClock: number = Date.now()
): number | null {
  if (typeof remainingSeconds !== 'number' || !Number.isFinite(remainingSeconds)) return null;
  const elapsedSeconds = Math.max(0, Math.floor((nowWallClock - capturedWallClock) / 1000));
  return Math.max(0, remainingSeconds - elapsedSeconds);
}
