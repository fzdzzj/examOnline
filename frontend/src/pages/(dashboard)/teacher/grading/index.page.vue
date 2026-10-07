<template>
  <div>
    <Card title="批改工作台" class="mb-4">
      <template #extra>
        <span class="hint">
          并发批改由后端乐观锁保护：同一份答卷被他人先保存时，后提交方会收到冲突提示，绝不静默覆盖。
        </span>
      </template>

      <div class="mb-3 flex flex-wrap items-center gap-3">
        <Select
          v-model:value="selectedExamId"
          :options="examOptions"
          placeholder="选择考试（已结束 / 已批改状态）"
          class="w-96"
          show-search
          :filter-option="examFilterOption"
          :loading="examsFetching"
        />
      </div>

      <Alert
        v-if="selectedExam && !canGradeSelected"
        type="info"
        show-icon
        message="该考试尚未结束，暂不可批改。批改入口在考试结束（自然到点或强制结束）后开放。"
        class="mb-3"
      />

      <div v-if="canRunGrading" class="run-grading-panel mb-3">
        <Alert type="info" show-icon>
          <template #message>运行判分</template>
          <template #description>
            <div class="run-grading-description">
              <span>
                仅对已结束且尚未汇总的考试开放；会重新计算客观题分数，不覆盖教师已保存的主观题分数。
              </span>
              <Button type="primary" :loading="gradingButtonLoading" @click="onRunGrading">
                运行判分
              </Button>
            </div>
          </template>
        </Alert>
      </div>

      <Alert
        v-if="gradingRunError"
        type="error"
        show-icon
        :message="gradingRunError"
        class="mb-3"
      />

      <div v-if="gradingRunResult" class="grading-run-result mb-3">
        <div class="result-title">最近一次判分结果</div>
        <div class="result-stats">
          <span>总数：{{ gradingRunResult.total ?? '未返回' }}</span>
          <span>成功：{{ gradingRunResult.success ?? '未返回' }}</span>
          <span>失败：{{ gradingRunResult.failed ?? '未返回' }}</span>
        </div>
        <Alert
          v-if="gradingRunResult.total === 0"
          type="info"
          show-icon
          message="无已交卷答卷，本次没有可判分的答卷。"
          class="mt-2"
        />
        <Alert
          v-else-if="(gradingRunResult.failed ?? 0) > 0"
          type="warning"
          show-icon
          message="部分答卷判分失败，请处理失败清单后再汇总成绩。"
          class="mt-2"
        />
        <Alert
          v-else-if="gradingRunResult.total !== undefined && gradingRunResult.failed === 0"
          type="success"
          show-icon
          message="判分完成，可继续检查主观题批改进度。"
          class="mt-2"
        />
        <ul v-if="(gradingRunResult.failures?.length ?? 0) > 0" class="failure-list">
          <li
            v-for="failure in gradingRunResult.failures"
            :key="failure.submissionId ?? failure.studentId"
          >
            答卷 {{ failure.submissionId ?? '未知' }}（学生 {{ failure.studentId ?? '未知' }}）：
            {{ failure.error ?? '未知错误' }}
          </li>
        </ul>
      </div>
    </Card>

    <template v-if="selectedExamId !== undefined && canGradeSelected">
      <Card title="主观题进度" class="mb-4" :loading="questionsFetching">
        <template #extra>
          <Button size="small" @click="() => void refetchQuestions()">刷新进度</Button>
        </template>
        <Table
          v-if="questions.length > 0"
          :columns="questionColumns"
          :data-source="questions"
          :pagination="false"
          :row-key="(q: SubjectiveQuestionItem) => q.questionId as number"
          size="middle"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'progress'">
              <span>
                {{ (record as SubjectiveQuestionItem).gradedStudents ?? 0 }} /
                {{ (record as SubjectiveQuestionItem).totalStudents ?? 0 }}
              </span>
            </template>
            <template v-else-if="column.key === 'actions'">
              <Button
                type="link"
                size="small"
                @click="selectQuestion(record as SubjectiveQuestionItem)"
              >
                {{
                  selectedQuestionId === (record as SubjectiveQuestionItem).questionId
                    ? '批改中'
                    : '去批改'
                }}
              </Button>
            </template>
          </template>
        </Table>
        <Alert
          v-else-if="!questionsFetching"
          type="info"
          show-icon
          message="该试卷没有主观题，客观题由后端自动判分，无需人工批改。"
        />
      </Card>

      <Card v-if="selectedQuestion" :title="`批改：第 ${selectedQuestion.number} 题`" class="mb-4">
        <SubjectiveGradingPanel
          :exam-id="selectedExamId"
          :question="selectedQuestion"
          :rows="rows"
          :total="rowsTotal"
          :graded="gradedCount"
          :loading="rowsFetching"
          v-model:page="page"
          v-model:page-size="pageSize"
          v-model:name-filter="nameFilter"
          v-model:only-ungraded="onlyUngraded"
          @refreshed="onRowRefreshed"
        />
      </Card>
    </template>
  </div>
</template>

<script setup lang="ts">
import { Alert, Button, Card, Select, Table, message, type TableColumnsType } from 'ant-design-vue';
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';

import {
  page2 as pageExams,
  subjectiveQuestions,
  subjectiveRows,
  run,
  type ExamResponse,
  type GradingRunResponse,
  type SubjectiveGradePageResponse,
  type SubjectiveGradeRow,
  type SubjectiveQuestionItem,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { EXAM_STATUS } from '@/constants/examStatus';
import { createTeacherExamsQueryOptions } from '@/hooks/useTeacherExams';
import { resolveScoreActions } from '@/utils/scoreActions';
import SubjectiveGradingPanel from '@/components/postexam/SubjectiveGradingPanel.vue';

/**
 * 教师批改工作台（阶段 23）：
 * 考试选择 → 主观题进度（subjectiveQuestions）→ 同题学生行（subjectiveRows）→ 逐行打分。
 * 批改入口只按后端返回的考试状态渲染（≥ ENDED），冲突处理见 SubjectiveGradingPanel。
 */

const questionColumns: TableColumnsType = [
  { title: '题号', key: 'number', dataIndex: 'number', width: 80 },
  { title: '题干', key: 'content', dataIndex: 'content' },
  { title: '满分', key: 'score', dataIndex: 'score', width: 80 },
  { title: '批改进度', key: 'progress', width: 110 },
  { title: '操作', key: 'actions', width: 100 },
];

// ===== 考试选择（教师自己的考试：统一走多页累积收口，见 useTeacherExams）=====
const { data: examsData, isFetching: examsFetching } = useQuery(
  createTeacherExamsQueryOptions((page, size) =>
    unwrap<ExamResponse[]>(pageExams({ client, throwOnError: true, query: { page, size } }))
  )
);
const exams = computed<ExamResponse[]>(() => examsData.value ?? []);

const examOptions = computed(() =>
  exams.value.map((e) => ({
    value: e.id as number,
    label: `#${e.id} ${e.title}（${getExamStatusLabel(e.status)}）`,
  }))
);

function getExamStatusLabel(status: number | undefined): string {
  const map: Record<number, string> = {
    [EXAM_STATUS.NOT_STARTED]: '未开始',
    [EXAM_STATUS.IN_PROGRESS]: '进行中',
    [EXAM_STATUS.ENDED]: '已结束',
    [EXAM_STATUS.GRADED]: '已批改',
    [EXAM_STATUS.PUBLISHED]: '成绩已发布',
  };
  return (status !== undefined && map[status]) || '未知';
}

function examFilterOption(input: string, option?: unknown): boolean {
  const label = (option as { label?: unknown } | undefined)?.label;
  return String(label ?? '')
    .toLowerCase()
    .includes(input.toLowerCase());
}

const selectedExamId = ref<number | undefined>(undefined);
const selectedExam = computed<ExamResponse | undefined>(() =>
  exams.value.find((e) => e.id === selectedExamId.value)
);
// 批改入口按后端状态渲染：≥ ENDED 才开放（scoreActions 的 canGrade）
const canGradeSelected = computed(
  () => resolveScoreActions(selectedExam.value?.status, false).canGrade
);
// 只有后端状态仍为 ENDED 时才提供“整场运行判分”；GRADED/PUBLISHED 不冒称可整场重判。
const canRunGrading = computed(() => selectedExam.value?.status === EXAM_STATUS.ENDED);

const gradingRunning = ref(false);
const gradingRunExamId = ref<number | undefined>(undefined);
const gradingButtonLoading = computed(
  () => gradingRunning.value && gradingRunExamId.value === selectedExamId.value
);
const gradingRunResult = ref<GradingRunResponse | null>(null);
const gradingRunError = ref<string | null>(null);

watch(selectedExamId, () => {
  // 结果属于所选考试；切换考试时清掉旧结果，避免跨考试误读。
  gradingRunResult.value = null;
  gradingRunError.value = null;
  selectedQuestionId.value = undefined;
});

async function onRunGrading(): Promise<void> {
  const examId = selectedExamId.value;
  if (
    examId === undefined ||
    !canRunGrading.value ||
    (gradingRunning.value && gradingRunExamId.value === examId)
  ) {
    return;
  }

  gradingRunning.value = true;
  gradingRunExamId.value = examId;
  gradingRunResult.value = null;
  gradingRunError.value = null;
  try {
    const result = await unwrap<GradingRunResponse>(
      run({
        client,
        throwOnError: true,
        path: { examId },
      })
    );
    // 运行期间切换了考试：旧请求的结果不能显示，也不能刷新新考试的查询。
    if (selectedExamId.value !== examId) return;
    if (!result) {
      gradingRunError.value = '判分接口未返回结果，未能确认本次判分状态。';
      message.error(gradingRunError.value);
      return;
    }

    gradingRunResult.value = result;
    void refetchQuestions();
    if (selectedQuestionId.value !== undefined) {
      void refetchRows();
    }

    if (result.total === 0) {
      message.info('无已交卷答卷，本次没有可判分的答卷');
    } else if ((result.failed ?? 0) > 0) {
      message.warning('部分答卷判分失败，请处理失败清单后再汇总成绩');
    } else if (result.total !== undefined && result.failed === 0) {
      message.success('判分完成');
    }
  } catch (error) {
    if (selectedExamId.value !== examId) return;
    gradingRunError.value = error instanceof Error ? error.message : '运行判分失败';
    message.error(gradingRunError.value);
  } finally {
    if (gradingRunExamId.value === examId) {
      gradingRunning.value = false;
      gradingRunExamId.value = undefined;
    }
  }
}

// ===== 主观题进度 =====
const {
  data: questionsData,
  isFetching: questionsFetching,
  refetch: refetchQuestions,
} = useQuery({
  queryKey: computed(() => ['grading', 'questions', selectedExamId.value] as const),
  queryFn: () =>
    unwrap<SubjectiveQuestionItem[]>(
      subjectiveQuestions({
        client,
        throwOnError: true,
        path: { examId: selectedExamId.value as number },
      })
    ),
  enabled: computed(() => selectedExamId.value !== undefined && canGradeSelected.value),
});

const questions = computed<SubjectiveQuestionItem[]>(() => questionsData.value ?? []);

const selectedQuestionId = ref<number | undefined>(undefined);
const selectedQuestion = computed<SubjectiveQuestionItem | undefined>(() =>
  questions.value.find((q) => q.questionId === selectedQuestionId.value)
);

function selectQuestion(question: SubjectiveQuestionItem): void {
  selectedQuestionId.value = question.questionId;
}

// ===== 同题学生行 =====
// 服务端分页与筛选（add-subjective-grading-pagination）：page/size/筛选全部入 queryKey，
// 由服务端过滤与分页（信封 {rows,total,graded}），客户端不再做全量筛选链。
const page = ref(1);
const pageSize = ref(10);
const onlyUngraded = ref(false);
const nameFilter = ref('');

const {
  data: rowsData,
  isFetching: rowsFetching,
  refetch: refetchRows,
} = useQuery({
  queryKey: computed(
    () =>
      [
        'grading',
        'rows',
        selectedExamId.value,
        selectedQuestionId.value,
        page.value,
        pageSize.value,
        onlyUngraded.value,
        nameFilter.value,
      ] as const
  ),
  queryFn: () =>
    unwrap<SubjectiveGradePageResponse>(
      subjectiveRows({
        client,
        throwOnError: true,
        path: { examId: selectedExamId.value as number },
        query: {
          questionId: selectedQuestionId.value as number,
          page: page.value,
          size: pageSize.value,
          onlyUngraded: onlyUngraded.value,
          // 空白姓名等价于不过滤（对齐后端语义）
          name: nameFilter.value.trim() ? nameFilter.value.trim() : undefined,
        },
      })
    ),
  enabled: computed(() => selectedQuestionId.value !== undefined),
});

const rowsEnvelope = computed<SubjectiveGradePageResponse | undefined>(() => rowsData.value);
const rows = computed<SubjectiveGradeRow[]>(() => rowsEnvelope.value?.rows ?? []);
const rowsTotal = computed(() => rowsEnvelope.value?.total ?? 0);
const gradedCount = computed(() => rowsEnvelope.value?.graded ?? 0);

// 切换题目或筛选条件时翻回第 1 页（换筛选看第 2 页没有意义）
watch([selectedQuestionId, onlyUngraded, nameFilter], () => {
  page.value = 1;
});

// 面板提交成功或冲突后：行列表和题级进度都要重拉，否则同一屏会出现 1/1 与 0/1 并存
function onRowRefreshed(): void {
  void refetchRows();
  void refetchQuestions();
}
</script>

<style scoped>
.mb-3 {
  margin-bottom: 0.75rem;
}
.mb-4 {
  margin-bottom: 1rem;
}
.run-grading-description {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.result-title {
  font-weight: 600;
}
.result-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 8px;
}
.failure-list {
  margin: 8px 0 0;
  padding-left: 20px;
}
.mt-2 {
  margin-top: 0.5rem;
}
.hint {
  color: #999;
  font-size: 12px;
  font-weight: normal;
  max-width: 420px;
}
</style>
