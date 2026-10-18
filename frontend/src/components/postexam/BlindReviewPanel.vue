<template>
  <div>
    <Alert
      v-if="loadError"
      type="error"
      show-icon
      class="mb-3"
      :message="loadError"
      data-test="blind-load-error"
    />
    <Alert
      type="info"
      show-icon
      class="mb-3"
      message="双评盲评：考生身份已打码、他人评分不可见。同一题一评二评背靠背独立打分，分差超容差将进入仲裁。"
    />

    <Table
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="false"
      :row-key="(row: SubjectiveGradeRow) => row.submissionId as number"
      size="middle"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'student'">
          <!-- 脱敏由服务端强制（blind=true），面板只渲染打码名，绝不展示真实姓名 -->
          <span>{{ (record as SubjectiveGradeRow).studentName ?? '考生 ***' }}</span>
        </template>
        <template v-else-if="column.key === 'answer'">
          <TypographyParagraph
            class="answer-cell"
            :content="(record as SubjectiveGradeRow).studentAnswer || '（未作答）'"
            :ellipsis="{ rows: 2, expandable: true }"
          />
        </template>
        <template v-else-if="column.key === 'blindState'">
          <Tag v-if="isRowDone(record as SubjectiveGradeRow)" color="green">已盲评</Tag>
          <Tag v-else color="default">待盲评</Tag>
          <div v-if="statusTextOf(record as SubjectiveGradeRow)" class="blind-status">
            {{ statusTextOf(record as SubjectiveGradeRow) }}
          </div>
          <div
            v-if="errorOf(record as SubjectiveGradeRow)"
            class="row-error"
            data-test="blind-row-error"
          >
            {{ errorOf(record as SubjectiveGradeRow) }}
          </div>
        </template>
        <template v-else-if="column.key === 'grading'">
          <div class="grading-cell">
            <InputNumber
              :value="draftOf(record as SubjectiveGradeRow).score ?? undefined"
              class="score-input"
              :min="0"
              :max="question.score"
              :step="0.5"
              placeholder="分数"
              :disabled="isRowDone(record as SubjectiveGradeRow)"
              :status="errorOf(record as SubjectiveGradeRow) ? 'error' : undefined"
              @update:value="
                (v) => setDraft((record as SubjectiveGradeRow).submissionId as number, 'score', v)
              "
            />
            <Input
              :value="draftOf(record as SubjectiveGradeRow).comment"
              placeholder="盲评评语（可空）"
              class="comment-input"
              :disabled="isRowDone(record as SubjectiveGradeRow)"
              @update:value="
                (v) => setDraft((record as SubjectiveGradeRow).submissionId as number, 'comment', v)
              "
            />
            <Button
              type="primary"
              size="small"
              data-test="blind-submit"
              :loading="savingSubmissionId === (record as SubjectiveGradeRow).submissionId"
              :disabled="isRowDone(record as SubjectiveGradeRow) || savingSubmissionId !== null"
              @click="onSubmit(record as SubjectiveGradeRow)"
            >
              盲评提交
            </Button>
          </div>
        </template>
      </template>
    </Table>
  </div>
</template>

<script setup lang="ts">
import {
  Alert,
  Button,
  Input,
  InputNumber,
  Table,
  Tag,
  Typography,
  message,
  type TableColumnsType,
} from 'ant-design-vue';
import { onMounted, reactive, ref } from 'vue';

import {
  blindSubmit,
  subjectiveRows,
  type SubjectiveGradePageResponse,
  type SubjectiveGradeRow,
  type SubjectiveQuestionItem,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { validateSubjectiveScore } from '@/utils/gradingValidation';

/**
 * 双评盲评面板（add-subjective-double-blind-grading）：
 * 行查询走 blind=true 脱敏视图（考生打码、教师评分不可见，服务端强制），
 * 提交走 blindSubmit（一评/二评自动分流 + 同教师互斥 + 分差裁决）。
 * blindDone 行禁止重复盲评；后端 400（同教师互斥等）错误行内呈现，绝不伪装成功。
 */

const TypographyParagraph = Typography.Paragraph;

const props = defineProps<{
  examId: number;
  question: SubjectiveQuestionItem;
}>();

const columns: TableColumnsType = [
  { title: '考生', key: 'student', width: 110 },
  { title: '作答内容', key: 'answer' },
  { title: '盲评状态', key: 'blindState', width: 170 },
  { title: '打分', key: 'grading', width: 330 },
];

const rows = ref<SubjectiveGradeRow[]>([]);
const loading = ref(false);
const loadError = ref<string | null>(null);
const savingSubmissionId = ref<number | null>(null);
const statusBySubmission = ref<Record<number, string>>({});
const errorBySubmission = ref<Record<number, string>>({});

/** 盲评行查询必须带 blind=true：脱敏由服务端执行，本面板拿到的行已无真实身份与教师评分。 */
async function loadRows(): Promise<void> {
  loading.value = true;
  loadError.value = null;
  try {
    const page = (await unwrap(
      subjectiveRows({
        client,
        throwOnError: true,
        path: { examId: props.examId },
        query: { questionId: props.question.questionId as number, blind: true },
      })
    )) as SubjectiveGradePageResponse | undefined;
    rows.value = page?.rows ?? [];
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '盲评行加载失败，请稍后重试';
  } finally {
    loading.value = false;
  }
}

onMounted(() => void loadRows());

function isRowDone(row: SubjectiveGradeRow): boolean {
  return row.blindDone === true;
}

function statusTextOf(row: SubjectiveGradeRow): string {
  const status = statusBySubmission.value[row.submissionId as number];
  if (status === 'PENDING_SECOND') return '已提交，等待二评';
  if (status === 'RESOLVED') return '分差在容差内，已按均分定分';
  if (status === 'ARBITRATION_PENDING') return '分差超标，已转入仲裁';
  return '';
}

function errorOf(row: SubjectiveGradeRow): string | null {
  return errorBySubmission.value[row.submissionId as number] ?? null;
}

// ===== 行内编辑草稿（key = submissionId） =====
interface Draft {
  score: string | null;
  comment: string;
}
const drafts = reactive<Record<number, Draft>>({});

function draftOf(row: SubjectiveGradeRow): Draft {
  return drafts[row.submissionId as number] ?? { score: null, comment: '' };
}

function setDraft(submissionId: number, field: 'score' | 'comment', value: unknown): void {
  const current = drafts[submissionId] ?? { score: null, comment: '' };
  if (field === 'score') {
    // InputNumber 的 null/number 都收敛成字符串，交给校验函数统一判定
    current.score = value === null || value === undefined ? null : String(value);
  } else {
    current.comment = typeof value === 'string' ? value : '';
  }
  drafts[submissionId] = current;
}

/**
 * 提交一笔盲评：校验分数 → blindSubmit（自动分流）→ 行级状态呈现裁决结果。
 * 返回是否成功；失败时行内呈现原因，输入保留供修正。
 */
async function submitBlind(row: { submissionId?: number; questionId?: number }): Promise<boolean> {
  const id = row.submissionId as number;
  const questionId = row.questionId as number;
  const draft = drafts[id] ?? { score: null, comment: '' };
  const validation = validateSubjectiveScore(draft.score, props.question.score ?? 0);
  if (!validation.ok) {
    errorBySubmission.value[id] = validation.message ?? '分数不合法';
    return false;
  }
  savingSubmissionId.value = id;
  delete errorBySubmission.value[id];
  try {
    const response = await unwrap(
      blindSubmit({
        client,
        throwOnError: true,
        path: { examId: props.examId },
        body: {
          submissionId: id,
          questionId,
          score: Number(String(draft.score).trim()),
          comment: draft.comment?.trim() ? draft.comment.trim() : undefined,
        },
      })
    );
    statusBySubmission.value[id] = response?.status ?? '';
    const target = rows.value.find((r) => r.submissionId === id);
    if (target) {
      target.blindDone = true;
    }
    message.success('盲评已提交');
    return true;
  } catch (error) {
    errorBySubmission.value[id] =
      error instanceof Error ? error.message : '盲评提交失败，请稍后重试';
    return false;
  } finally {
    savingSubmissionId.value = null;
  }
}

async function onSubmit(row: SubjectiveGradeRow): Promise<void> {
  await submitBlind(row);
}

defineExpose({
  rows,
  loading,
  drafts,
  statusBySubmission,
  errorBySubmission,
  setDraft,
  submitBlind,
});
</script>

<style scoped>
.mb-3 {
  margin-bottom: 0.75rem;
}
.answer-cell {
  margin-bottom: 0 !important;
  max-width: 420px;
}
.blind-status {
  color: #1677ff;
  font-size: 12px;
  margin-top: 2px;
}
.row-error {
  color: #cf1322;
  font-size: 12px;
  margin-top: 4px;
}
.grading-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.score-input {
  width: 90px;
}
.comment-input {
  flex: 1;
  min-width: 140px;
}
</style>
