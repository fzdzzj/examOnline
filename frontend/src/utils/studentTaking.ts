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

/** 导航单元：`number` 用于显示，`answered` 驱动「已答/未答」两色，`current` 高亮当前题。 */
export interface NavItemState {
  index: number;
  number: number;
  answered: boolean;
  current: boolean;
}

/**
 * 逐题导航状态（已答 / 未答 / 当前）。
 * 第 3 片「二次确认提示未答题数」直接复用它的长度差，所以这里不返回计数字段——
 * 计数由调用方从同一份数组算出来，避免两处数字各说各话。
 */
export function navStatesOf(
  questions: readonly QuestionView[],
  answers: AnswerMap,
  currentIndex: number
): NavItemState[] {
  return questions.map((question, index) => ({
    index,
    number: question.number ?? index + 1,
    answered: isAnswered(question.type, answers[answerKeyOf(question)]),
    current: index === currentIndex,
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
