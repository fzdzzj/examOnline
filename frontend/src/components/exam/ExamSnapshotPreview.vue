<template>
  <Spin :spinning="!!loading">
    <Alert v-if="error" type="error" show-icon :message="error" class="mb-3" />

    <!-- 未发布 = 后端 404（"考试尚未发布或快照不存在"）。这是空态，不是错误态：
         措辞说明"还没生成"，并点明发布是快照生成的唯一时机。 -->
    <Empty
      v-else-if="notFound"
      description="暂无试卷快照：考试尚未发布（发布时才生成快照）"
      class="my-6"
    />

    <template v-else-if="snapshot">
      <Alert
        type="info"
        show-icon
        class="mb-3"
        message="以下为发布时固化的快照副本：试卷或题目此后被修改都不影响这里的内容"
      />

      <Descriptions bordered size="small" :column="2" class="mb-4">
        <DescriptionsItem label="快照 ID">{{ snapshot.id ?? '—' }}</DescriptionsItem>
        <DescriptionsItem label="版本">{{ snapshot.version ?? '—' }}</DescriptionsItem>
        <DescriptionsItem label="生成时间">{{ formatTime(snapshot.createdTime) }}</DescriptionsItem>
        <DescriptionsItem label="快照内试卷总分">
          {{ paperView?.totalScore ?? '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="快照内题数">
          {{ paperView?.questionCount ?? '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="快照内试卷">
          {{ paperView?.title ?? '—' }}
        </DescriptionsItem>
      </Descriptions>

      <p class="mb-2 text-sm font-semibold">考试配置快照</p>
      <Table
        v-if="examFields.length"
        :columns="fieldColumns"
        :data-source="examFields"
        :pagination="false"
        size="small"
        row-key="key"
        class="mb-4"
      />
      <p v-else class="mb-4 text-gray-500">快照未带考试配置内容（后端 exam 栏为空）。</p>

      <p class="mb-2 text-sm font-semibold">试卷内容快照（只读）</p>
      <Table
        v-if="paperView && paperView.questions.length"
        :columns="questionColumns"
        :data-source="paperView.questions"
        :pagination="false"
        size="small"
        :row-key="(row: SnapshotQuestion) => `${row.number}-${row.questionId}`"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'type'">
            <Tag>{{ typeLabelOf((record as SnapshotQuestion).type) }}</Tag>
          </template>
          <template v-else-if="column.key === 'choices'">
            <span v-if="(record as SnapshotQuestion).choices?.length">
              {{ (record as SnapshotQuestion).choices?.join(' / ') }}
            </span>
            <span v-else class="text-gray-400">—</span>
          </template>
          <template v-else-if="column.key === 'correctAnswer'">
            <span class="answer-text">{{ (record as SnapshotQuestion).correctAnswer ?? '—' }}</span>
          </template>
        </template>
      </Table>
      <p v-else class="mb-0 text-gray-500">快照内没有题目（后端 paper.questions 为空）。</p>
    </template>

    <Empty v-else description="暂无快照数据" class="my-6" />
  </Spin>
</template>

<script setup lang="ts">
import {
  Alert,
  Descriptions,
  DescriptionsItem,
  Empty,
  Spin,
  Table,
  Tag,
  type TableColumnsType,
} from 'ant-design-vue';
import dayjs from 'dayjs';
import { computed } from 'vue';

import type { ExamSnapshotResponse } from '@/api/axios';
import {
  parseSnapshotExamFields,
  parseSnapshotPaper,
  type SnapshotQuestion,
} from '@/utils/examSnapshot';
import { typeLabelOf } from '@/utils/questionTypes';

/**
 * 试卷快照只读预览（阶段 21 缺口 2 的一部分）。
 * 数据全部来自 `GET /api/exams/{id}/snapshot`：题数、分值、答案一律按快照渲染，
 * 前端不重算总分、不补齐题号（快照是判分与回看的唯一口径，见 §10.10）。
 */

const props = defineProps<{
  snapshot?: ExamSnapshotResponse | null;
  loading?: boolean;
  /** 取快照失败的可读原因（网络/权限等，后端 message 优先） */
  error?: string | null;
  /** 后端以 404 表达"尚未发布/无快照"——按空态而非错误态呈现 */
  notFound?: boolean;
}>();

const paperView = computed(() => parseSnapshotPaper(props.snapshot?.paper));
const examFields = computed(() => parseSnapshotExamFields(props.snapshot?.exam));

const fieldColumns: TableColumnsType = [
  { title: '配置项', key: 'key', dataIndex: 'key', width: 200 },
  { title: '快照内的值', key: 'value', dataIndex: 'value' },
];

const questionColumns: TableColumnsType = [
  { title: '题号', key: 'number', dataIndex: 'number', width: 70 },
  { title: '题型', key: 'type', width: 90 },
  { title: '题干', key: 'content', dataIndex: 'content' },
  { title: '选项', key: 'choices', width: 220 },
  { title: '答案', key: 'correctAnswer', width: 140 },
  { title: '卷内分值', key: 'score', dataIndex: 'score', width: 90 },
];

function formatTime(value: string | undefined | null): string {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—';
}
</script>

<style scoped>
.mb-2 {
  margin-bottom: 0.5rem;
}
.mb-3 {
  margin-bottom: 0.75rem;
}
.mb-4 {
  margin-bottom: 1rem;
}
.mb-0 {
  margin-bottom: 0;
}
.my-6 {
  margin-top: 1.5rem;
  margin-bottom: 1.5rem;
}
.text-sm {
  font-size: 0.875rem;
}
.text-gray-400 {
  color: #9ca3af;
}
.text-gray-500 {
  color: #6b7280;
}
.font-semibold {
  font-weight: 600;
}
.answer-text {
  word-break: break-all;
}
</style>
