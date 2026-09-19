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
          :loading="rowsFetching"
          @refreshed="onRowRefreshed"
        />
      </Card>
    </template>
  </div>
</template>

<script setup lang="ts">
import { Alert, Button, Card, Select, Table, type TableColumnsType } from 'ant-design-vue';
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';

import {
  page2 as pageExams,
  subjectiveQuestions,
  subjectiveRows,
  type ExamResponse,
  type SubjectiveGradeRow,
  type SubjectiveQuestionItem,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { EXAM_STATUS } from '@/constants/examStatus';
import { resolveScoreActions } from '@/utils/scoreActions';
import SubjectiveGradingPanel from '@/components/postexam/SubjectiveGradingPanel.vue';

/**
 * 教师批改工作台（阶段 23）：
 * 考试选择 → 主观题进度（subjectiveQuestions）→ 同题学生行（subjectiveRows）→ 逐行打分。
 * 批改入口只按后端返回的考试状态渲染（≥ ENDED），冲突处理见 SubjectiveGradingPanel。
 */

const PAGE_SIZE = 50;

const questionColumns: TableColumnsType = [
  { title: '题号', key: 'number', dataIndex: 'number', width: 80 },
  { title: '题干', key: 'content', dataIndex: 'content' },
  { title: '满分', key: 'score', dataIndex: 'score', width: 80 },
  { title: '批改进度', key: 'progress', width: 110 },
  { title: '操作', key: 'actions', width: 100 },
];

// ===== 考试选择（教师自己的考试，翻页拉够常用量级）=====
const examPage = ref(1);
const { data: examsData, isFetching: examsFetching } = useQuery({
  queryKey: computed(() => ['exams', 'grading', examPage.value] as const),
  queryFn: () =>
    unwrap<ExamResponse[]>(
      pageExams({
        client,
        throwOnError: true,
        query: { page: examPage.value, size: PAGE_SIZE },
      })
    ),
});

const exams = computed<ExamResponse[]>(() => examsData.value ?? []);
// 分页信封无 total：当页满页就再拉一页，攒出完整候选（教师考试数量级有限）
watch(exams, (list) => {
  if (list.length === examPage.value * PAGE_SIZE) {
    examPage.value += 1;
  }
});

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
const {
  data: rowsData,
  isFetching: rowsFetching,
  refetch: refetchRows,
} = useQuery({
  queryKey: computed(
    () => ['grading', 'rows', selectedExamId.value, selectedQuestionId.value] as const
  ),
  queryFn: () =>
    unwrap<SubjectiveGradeRow[]>(
      subjectiveRows({
        client,
        throwOnError: true,
        path: { examId: selectedExamId.value as number },
        query: { questionId: selectedQuestionId.value as number },
      })
    ),
  enabled: computed(() => selectedQuestionId.value !== undefined),
});

const rows = computed<SubjectiveGradeRow[]>(() => rowsData.value ?? []);

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
.hint {
  color: #999;
  font-size: 12px;
  font-weight: normal;
  max-width: 420px;
}
</style>
