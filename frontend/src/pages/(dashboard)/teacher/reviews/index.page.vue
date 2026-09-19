<template>
  <div>
    <Card title="成绩复核处理" class="mb-4">
      <template #extra>
        <span class="hint">
          复核列表来自后端 GET /api/exams/{examId}/score-reviews（exam:manage），处理结论由后端落
          result 字段。
        </span>
      </template>

      <div class="mb-3 flex flex-wrap items-center gap-3">
        <Select
          v-model:value="selectedExamId"
          :options="examOptions"
          placeholder="选择考试"
          class="w-96"
          show-search
          :filter-option="examFilterOption"
          :loading="examsFetching"
        />
        <Button @click="() => void refetch()">刷新列表</Button>
      </div>

      <Table
        :columns="columns"
        :data-source="reviews"
        :loading="isFetching"
        :pagination="{ pageSize: 10, showSizeChanger: false }"
        :row-key="(row: ScoreReview) => row.id as number"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <Tag :color="getReviewStatusConfig((record as ScoreReview).status ?? -1).color">
              {{ getReviewStatusConfig((record as ScoreReview).status ?? -1).label }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'actions'">
            <Space>
              <Button type="link" size="small" @click="openDetail(record as ScoreReview)">
                查看
              </Button>
              <Button
                v-if="getReviewStatusConfig((record as ScoreReview).status ?? -1).ongoing"
                type="link"
                size="small"
                @click="openHandle(record as ScoreReview)"
              >
                处理
              </Button>
              <span v-else class="done-hint">已出结论</span>
            </Space>
          </template>
        </template>
        <template #emptyText>该考试暂无复核申请</template>
      </Table>
    </Card>

    <Modal v-model:open="detailOpen" title="复核申请详情" :footer="null">
      <ReviewTimeline :review="current" />
    </Modal>

    <ReviewHandleModal v-model:open="handleOpen" :review="current" @handled="onHandled" />
  </div>
</template>

<script setup lang="ts">
import {
  Button,
  Card,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  type TableColumnsType,
} from 'ant-design-vue';
import { computed, ref } from 'vue';
import { useQuery } from '@tanstack/vue-query';

import { listByExam, page2 as pageExams, type ExamResponse, type ScoreReview } from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { queryClient } from '@/api/queryClient';
import { getReviewStatusConfig } from '@/constants/postExam';
import ReviewHandleModal from '@/components/postexam/ReviewHandleModal.vue';
import ReviewTimeline from '@/components/postexam/ReviewTimeline.vue';

/**
 * 教师复核处理（阶段 23）：
 * GET /api/exams/{examId}/score-reviews 列表；POST /api/score-reviews/{reviewId}/handle 处理。
 * 「待处理 / 处理中」才可处理，已出结论（已同意 / 已驳回）不给入口——判定依据后端 status。
 */

const PAGE_SIZE = 50;

const columns: TableColumnsType = [
  { title: '申请 ID', key: 'id', width: 90 },
  { title: '学生 ID', key: 'studentId', width: 100 },
  { title: '申请理由', key: 'reason' },
  { title: '状态', key: 'status', width: 100 },
  { title: '申请时间', key: 'applyTime', width: 180 },
  { title: '处理说明', key: 'result', width: 200 },
  { title: '操作', key: 'actions', width: 160 },
];

const examPage = ref(1);
const { data: examsData, isFetching: examsFetching } = useQuery({
  queryKey: computed(() => ['exams', 'reviews', examPage.value] as const),
  queryFn: () =>
    unwrap<ExamResponse[]>(
      pageExams({ client, throwOnError: true, query: { page: examPage.value, size: PAGE_SIZE } })
    ),
});
const exams = computed<ExamResponse[]>(() => examsData.value ?? []);
const examOptions = computed(() =>
  exams.value.map((e) => ({ value: e.id as number, label: `#${e.id} ${e.title}` }))
);
function examFilterOption(input: string, option?: unknown): boolean {
  const label = (option as { label?: unknown } | undefined)?.label;
  return String(label ?? '')
    .toLowerCase()
    .includes(input.toLowerCase());
}

const selectedExamId = ref<number | undefined>(undefined);

const { data, isFetching, refetch } = useQuery({
  queryKey: computed(() => ['score-reviews', selectedExamId.value] as const),
  queryFn: () =>
    unwrap<ScoreReview[]>(
      listByExam({ client, throwOnError: true, path: { examId: selectedExamId.value as number } })
    ),
  enabled: computed(() => selectedExamId.value !== undefined),
});

const reviews = computed<ScoreReview[]>(() => data.value ?? []);

const current = ref<ScoreReview | null>(null);
const detailOpen = ref(false);
const handleOpen = ref(false);

function openDetail(review: ScoreReview): void {
  current.value = review;
  detailOpen.value = true;
}

function openHandle(review: ScoreReview): void {
  current.value = review;
  handleOpen.value = true;
}

function onHandled(): void {
  void queryClient.invalidateQueries({
    queryKey: ['score-reviews', selectedExamId.value] as const,
  });
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
  max-width: 460px;
}
.done-hint {
  color: #999;
  font-size: 12px;
}
</style>
