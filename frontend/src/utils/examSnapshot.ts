/**
 * 考试快照（`GET /api/exams/{id}/snapshot`）的只读解析。
 *
 * 快照的 `exam` / `paper` 两栏在契约里是 `JsonNode`（生成类型为 `unknown`），
 * 所以任何消费方都得先做一次性窄化——这里集中做，页面与组件只拿已解析的视图模型。
 *
 * ⚠️ 结构出处（不是猜的）：
 * - `paper` 与试卷快照同结构，由 `PaperSnapshotService.serialize` 写出
 *   （`paperId/title/totalScore/questionCount/generatedTime/questions[]`，
 *   每题 `number/questionId/type/content/choices/correctAnswer/score`）；
 * - `exam` 由 `ExamSnapshotService.serializeExam` 写出
 *   （`examId/title/paperId/courseId/classId/startTime/endTime/durationMinutes/`
 *   `allowLateMinutes/antiCheatConfig/generatedTime`）。
 * 缺字段一律当"后端没给"处理（渲染为 —），**不补默认值**，也不在此重算任何分值。
 */
import type { JsonNode } from '@/api/axios';

export interface SnapshotQuestion {
  number?: number;
  questionId?: number;
  type?: number;
  content?: string;
  choices?: string[];
  correctAnswer?: string;
  score?: number;
}

export interface SnapshotPaperView {
  paperId?: number;
  title?: string;
  totalScore?: number;
  questionCount?: number;
  generatedTime?: string;
  questions: SnapshotQuestion[];
}

/** 防作弊配置等自由 JSON 对象 → 键值对列表：后端有什么就展示什么，前端不加字段、不翻译语义。 */
export interface JsonField {
  key: string;
  value: string;
}

function asRecord(node: JsonNode | undefined): Record<string, unknown> | null {
  if (typeof node !== 'object' || node === null || Array.isArray(node)) return null;
  return node as Record<string, unknown>;
}

function numOf(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function strOf(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

/** 选项可能是字符串数组，也可能是历史形态的 `{text}` 对象数组——两者都取可读文本。 */
function choiceTexts(node: unknown): string[] | undefined {
  if (!Array.isArray(node)) return undefined;
  const texts = node.map((item) => {
    if (typeof item === 'string') return item;
    const record = asRecord(item);
    return strOf(record?.text) ?? strOf(record?.label) ?? JSON.stringify(item);
  });
  return texts.length ? texts : undefined;
}

export function parseSnapshotPaper(paper: JsonNode | undefined): SnapshotPaperView | null {
  const root = asRecord(paper);
  if (!root) return null;
  const rows = Array.isArray(root.questions) ? root.questions : [];
  return {
    paperId: numOf(root.paperId),
    title: strOf(root.title),
    totalScore: numOf(root.totalScore),
    questionCount: numOf(root.questionCount),
    generatedTime: strOf(root.generatedTime),
    questions: rows.map((row) => {
      const item = asRecord(row) ?? {};
      return {
        number: numOf(item.number),
        questionId: numOf(item.questionId),
        type: numOf(item.type),
        content: strOf(item.content),
        choices: choiceTexts(item.choices),
        correctAnswer: strOf(item.correctAnswer),
        score: numOf(item.score),
      };
    }),
  };
}

/** 考试配置快照 → 键值对（时间窗/时长/迟到/防作弊等），顺序与后端 JSON 一致。 */
export function parseSnapshotExamFields(exam: JsonNode | undefined): JsonField[] {
  const root = asRecord(exam);
  if (!root) return [];
  return Object.entries(root).map(([key, value]) => ({ key, value: displayOf(value) }));
}

/** 防作弊配置：原样列出后端存的键值，不映射成开关名（那是前端自造语义）。 */
export function parseAntiCheatFields(config: JsonNode | undefined): JsonField[] {
  const root = asRecord(config);
  if (!root) return [];
  return Object.entries(root).map(([key, value]) => ({ key, value: displayOf(value) }));
}

function displayOf(value: unknown): string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}
