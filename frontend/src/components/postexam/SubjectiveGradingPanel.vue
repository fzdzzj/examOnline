<template>
  <div>
    <!-- 乐观锁冲突提示（硬约定 2）：绝不静默覆盖，教师必须看到并重看最新内容 -->
    <Alert
      v-if="flow.state.conflict"
      type="warning"
      show-icon
      closable
      class="mb-3"
      @close="flow.dismissConflict()"
    >
      <template #message>
        {{ flow.state.conflict.message }}
      </template>
      <template #description>
        已为你刷新该生的最新批改内容（他人已打的分数与评语见下表），请重看后重新打分提交。
        <span class="conflict-note">系统不会用你刚才的分数覆盖他人的批改。</span>
      </template>
    </Alert>

    <Alert
      v-if="flow.state.error"
      type="error"
      show-icon
      class="mb-3"
      :message="flow.state.error.message"
      closable
      @close="flow.dismissError()"
    />

    <Alert
      v-if="lastSuccess"
      type="success"
      show-icon
      class="mb-3"
      :message="`已保存：${lastSuccess.studentName ?? lastSuccess.studentId ?? ''} 第 ${question.number} 题 ${lastSuccess.score} 分`"
      closable
      @close="lastSuccessId = null"
    />

    <div class="mb-3 flex flex-wrap items-center gap-3">
      <span class="question-meta">
        第 {{ question.number }} 题 · 满分 {{ question.score }} 分 · 已批 {{ graded ?? 0 }}/{{
          total ?? 0
        }}
      </span>
      <Input
        v-model:value="nameFilter"
        placeholder="按学生姓名筛选（当前题）"
        allow-clear
        class="w-56"
      />
      <label class="flex items-center gap-1 text-sm">
        <input v-model="onlyUngraded" type="checkbox" />
        只看未批改
      </label>
    </div>

    <Table
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="pagination"
      :row-key="(row: SubjectiveGradeRow) => row.submissionId as number"
      size="middle"
      @change="onTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'student'">
          <div class="student-cell">
            <span>{{ (record as SubjectiveGradeRow).studentName ?? '-' }}</span>
            <span class="student-id">ID {{ (record as SubjectiveGradeRow).studentId }}</span>
          </div>
        </template>
        <template v-else-if="column.key === 'answer'">
          <TypographyParagraph
            class="answer-cell"
            :content="(record as SubjectiveGradeRow).studentAnswer || '（未作答）'"
            :ellipsis="{ rows: 2, expandable: true }"
          />
          <div v-if="(record as SubjectiveGradeRow).suggestedDetail" class="suggested">
            参考评分建议：{{ (record as SubjectiveGradeRow).suggestedScore }} 分
          </div>
        </template>
        <template v-else-if="column.key === 'current'">
          <template v-if="(record as SubjectiveGradeRow).graded">
            <Tag color="green">{{ (record as SubjectiveGradeRow).score }} 分</Tag>
            <div v-if="(record as SubjectiveGradeRow).comment" class="current-comment">
              {{ (record as SubjectiveGradeRow).comment }}
            </div>
          </template>
          <Tag v-else color="default">未批</Tag>
        </template>
        <template v-else-if="column.key === 'grading'">
          <div class="grading-cell">
            <InputNumber
              :value="editingOf(record as SubjectiveGradeRow).score ?? undefined"
              class="score-input"
              :min="0"
              :max="question.score"
              :step="0.5"
              placeholder="分数"
              :status="rowErrorOf(record as SubjectiveGradeRow) ? 'error' : undefined"
              @update:value="(v) => setEditing(record as SubjectiveGradeRow, 'score', v)"
            />
            <Input
              :value="editingOf(record as SubjectiveGradeRow).comment"
              placeholder="评语（可空）"
              class="comment-input"
              @update:value="(v) => setEditing(record as SubjectiveGradeRow, 'comment', v)"
            />
            <Button
              type="primary"
              size="small"
              :loading="
                flow.state.savingSubmissionId === (record as SubjectiveGradeRow).submissionId
              "
              :disabled="flow.state.savingSubmissionId !== null"
              @click="onSubmit(record as SubjectiveGradeRow)"
            >
              提交
            </Button>
          </div>
          <div v-if="rowErrorOf(record as SubjectiveGradeRow)" class="row-error">
            {{ rowErrorOf(record as SubjectiveGradeRow) }}
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
  type TablePaginationConfig,
} from 'ant-design-vue';
import { computed, reactive, ref, watch } from 'vue';

import {
  saveSubjectiveScore,
  subjectiveRows,
  type SubjectiveGradeRow,
  type SubjectiveQuestionItem,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { createGradingFlow } from '@/hooks/useGradingFlow';

/**
 * 逐题打分面板：同题全部学生行（subjectiveRows）+ 逐行打分提交（saveSubjectiveScore 带 expectedVersion）。
 * 冲突处理是本组件的灵魂：后端 409/1012 → 提示「已被他人批改」→ 拉最新行回填 → 教师重看重打。
 */

const TypographyParagraph = Typography.Paragraph;

const props = defineProps<{
  examId: number;
  question: SubjectiveQuestionItem;
  rows: SubjectiveGradeRow[];
  loading?: boolean;
  /** 信封 total/graded（add-subjective-grading-pagination）：服务端口径的行总数与已批数 */
  total?: number;
  graded?: number;
}>();

const emit = defineEmits<{ refreshed: [] }>();

const columns: TableColumnsType = [
  { title: '学生', key: 'student', width: 130 },
  { title: '作答内容', key: 'answer' },
  { title: '当前批改', key: 'current', width: 120 },
  { title: '打分', key: 'grading', width: 330 },
];

// ===== 服务端分页与筛选状态（add-subjective-grading-pagination）=====
// 状态由父级 useQuery 持有（page/size/筛选入 queryKey），面板只做输入并上抛，
// 客户端不再对 props.rows 做全量筛选链或客户端分页——大班全量行不进前端内存。
const page = defineModel<number>('page', { default: 1 });
const pageSize = defineModel<number>('pageSize', { default: 10 });
const nameFilter = defineModel<string>('nameFilter', { default: '' });
const onlyUngraded = defineModel<boolean>('onlyUngraded', { default: false });

const pagination = computed(() => ({
  current: page.value,
  pageSize: pageSize.value,
  total: props.total ?? 0,
  showSizeChanger: false,
}));

function onTableChange(pagination: TablePaginationConfig): void {
  if (pagination.current !== undefined && pagination.current !== page.value) {
    page.value = pagination.current;
  }
  if (pagination.pageSize !== undefined && pagination.pageSize !== pageSize.value) {
    pageSize.value = pagination.pageSize;
  }
}

// ===== 编辑状态（key = submissionId）=====
interface Draft {
  score: string | null;
  comment: string;
}
const drafts = reactive<Record<number, Draft>>({});

function editingOf(row: SubjectiveGradeRow): Draft {
  return drafts[row.submissionId as number] ?? { score: null, comment: '' };
}

function setEditing(row: SubjectiveGradeRow, field: keyof Draft, value: unknown): void {
  const id = row.submissionId as number;
  const current = drafts[id] ?? { score: null, comment: '' };
  if (field === 'score') {
    // InputNumber 的 null/number 都收敛成字符串，交给校验函数统一判定
    current.score = value === null || value === undefined ? null : String(value);
  } else {
    current.comment = typeof value === 'string' ? value : '';
  }
  drafts[id] = current;
}

function clearDraft(row: SubjectiveGradeRow): void {
  delete drafts[row.submissionId as number];
}

// ===== 提交流（依赖注入：saveScore 走真实契约，refreshRow 冲突后拉最新行）=====
const flow = createGradingFlow({
  saveScore: (request) =>
    unwrap(
      saveSubjectiveScore({
        client,
        throwOnError: true,
        path: { examId: props.examId },
        body: request,
      })
    ),
  refreshRow: async (submissionId, questionId) => {
    // 单行取数（add-subjective-grading-pagination）：submissionId 查询参数直达该行，
    // 替代「全量拉取后 find」；409/1012 冲突回填语义（拉最新行回填、教师重看重打）不变
    const pageData = await unwrap(
      subjectiveRows({
        client,
        throwOnError: true,
        path: { examId: props.examId },
        query: { questionId, submissionId },
      })
    );
    return pageData?.rows?.[0] ?? null;
  },
});

const lastSuccessId = ref<number | null>(null);
// 成功提示按 submissionId 从最新行取分数：直接存下点击时的 row 会显示保存前的旧分数
const lastSuccess = computed<SubjectiveGradeRow | undefined>(() =>
  props.rows.find((r) => r.submissionId === lastSuccessId.value)
);

async function onSubmit(row: SubjectiveGradeRow): Promise<void> {
  const draft = editingOf(row);
  const ok = await flow.submit(row, draft.score, draft.comment, props.question.score ?? 0);
  if (ok) {
    clearDraft(row);
    lastSuccessId.value = row.submissionId ?? null;
    message.success('批改已保存');
    // 通知父级刷新行列表（拿到新 version / graded 状态），单测断言的是 flow 层行为
    emit('refreshed');
  }
  // 失败/冲突：不提示成功、不清输入，界面由 flow.state 驱动展示原因
}

// 冲突发生后：把后端最新行回填到展示（教师重看的对象），并清掉旧输入防误提交
watch(
  () => flow.state.conflict,
  (conflict) => {
    if (!conflict?.latestRow) return;
    clearDraft(conflict.latestRow);
    // 表格数据来自父级的 rows 查询，不重拉则「见下表」里的他人分数永远显示不出来
    emit('refreshed');
  }
);

// 行内错误文案：flow 的校验/提交错误按 submissionId 归位到对应行
function rowErrorOf(row: SubjectiveGradeRow): string | null {
  const error = flow.state.error;
  if (error && error.submissionId === row.submissionId) return error.message;
  return null;
}
</script>

<style scoped>
.mb-3 {
  margin-bottom: 0.75rem;
}
.question-meta {
  color: #555;
  font-size: 13px;
}
.conflict-note {
  color: #d46b08;
}
.student-cell {
  display: flex;
  flex-direction: column;
}
.student-id {
  color: #999;
  font-size: 12px;
}
.answer-cell {
  margin-bottom: 0 !important;
  max-width: 420px;
}
.suggested {
  color: #999;
  font-size: 12px;
  margin-top: 2px;
}
.current-comment {
  color: #999;
  font-size: 12px;
  margin-top: 2px;
  max-width: 110px;
  word-break: break-all;
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
.row-error {
  color: #cf1322;
  font-size: 12px;
  margin-top: 4px;
}
</style>
