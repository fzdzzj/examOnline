<template>
  <div>
    <Card title="考试管理" class="mb-4">
      <template #extra>
        <Button type="primary" @click="router.push('/teacher/exams/create')">+ 新建考试</Button>
      </template>

      <div class="mb-3 flex flex-wrap items-center gap-2">
        <Input
          v-model:value="searchTitle"
          placeholder="搜索考试标题（当前页）"
          allow-clear
          class="w-64"
        />
        <Select
          v-model:value="statusFilter"
          :options="statusOptions"
          placeholder="全部状态"
          allow-clear
          class="w-36"
        />
      </div>

      <Table
        :columns="columns"
        :data-source="filteredRows"
        :loading="isFetching"
        :pagination="tablePagination"
        :row-key="(row: ExamResponse) => row.id as number"
        size="middle"
        @change="onTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'startTime'">
            {{ formatTime((record as ExamResponse).startTime) }}
          </template>
          <template v-else-if="column.key === 'endTime'">
            {{ formatTime((record as ExamResponse).endTime) }}
          </template>
          <template v-else-if="column.key === 'status'">
            <Tag :color="getExamStatusConfig((record as ExamResponse).status as ExamStatus).color">
              {{ getExamStatusConfig((record as ExamResponse).status as ExamStatus).label }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'published'">
            <Tag :color="(record as ExamResponse).published ? 'green' : 'default'">
              {{ (record as ExamResponse).published ? '已发布考试' : '未发布考试' }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'actions'">
            <Space>
              <!-- 详情：快照预览 / 考生名单 / 提交进度 / 监考 / 行为日志的入口（阶段 21 缺口 2） -->
              <Button
                type="link"
                size="small"
                :disabled="(record as ExamResponse).id === undefined"
                @click="router.push(`/teacher/exams/${(record as ExamResponse).id}`)"
              >
                详情
              </Button>
              <!-- 编辑：与「发布考试」同源的乐观口径（未开始+未发布），复用创建页；
                   最终裁决在后端 ExamService.assertEditable，越权请求会被拒绝并原文呈现 -->
              <Button
                v-if="
                  !(record as ExamResponse).published &&
                  (record as ExamResponse).status === EXAM_STATUS.NOT_STARTED
                "
                type="link"
                size="small"
                @click="router.push(`/teacher/exams/create?examId=${(record as ExamResponse).id}`)"
              >
                编辑
              </Button>
              <!-- 发布考试：仅「未开始 + 未发布」可发布（后端 ExamController.publish 裁决，
                   前端只按状态渲染入口，越权点击会被后端拒绝） -->
              <Button
                v-if="
                  !(record as ExamResponse).published &&
                  (record as ExamResponse).status === EXAM_STATUS.NOT_STARTED
                "
                type="link"
                size="small"
                @click="openPublishConfirm(record as ExamResponse)"
              >
                发布考试
              </Button>
              <!-- 强制结束：仅进行中可结束（触发缺考标记，不可逆） -->
              <Button
                v-if="(record as ExamResponse).status === EXAM_STATUS.IN_PROGRESS"
                type="link"
                size="small"
                danger
                @click="openForceEndConfirm(record as ExamResponse)"
              >
                强制结束
              </Button>
              <!-- 删除：乐观口径放宽为「仅未发布」即显示（含进行中/已结束的未发布考试）——
                   后端 assertEditable（未发布且未开始）才是裁决者，非未开始的删除会被 400
                   拒绝，失败 message 原文呈现，前端不拦截不编造 -->
              <Button
                v-if="!(record as ExamResponse).published"
                type="link"
                size="small"
                danger
                @click="openDeleteConfirm(record as ExamResponse)"
              >
                删除
              </Button>
            </Space>
          </template>
        </template>
      </Table>
    </Card>

    <!-- 发布确认弹窗 -->
    <Modal
      v-model:open="publishModalOpen"
      title="确认发布考试"
      :confirm-loading="publishing"
      @ok="confirmPublish"
    >
      <!-- ant-design-vue 4 的 Alert 只渲染 message/description 插槽，正文必须走具名插槽
           （默认插槽会被静默丢弃，见 classes 页同类注释） -->
      <Alert type="info" show-icon>
        <template #message>
          <p>确定要发布该考试吗？发布后将会：</p>
          <ul class="list-disc pl-5">
            <li>生成试卷快照（唯一时机），学生侧可见</li>
            <li>绑定试卷被锁定（只读），禁止改题 / 删题</li>
            <li>考试状态仍为「未开始」，等待定时开考</li>
          </ul>
        </template>
      </Alert>
    </Modal>

    <!-- force-end 确认弹窗 -->
    <Modal
      v-model:open="forceEndModalOpen"
      title="确认强制结束"
      :confirm-loading="forceEnding"
      ok-text="强制结束"
      @ok="confirmForceEnd"
    >
      <!-- 同上：正文走 #message 具名插槽，否则默认插槽被静默丢弃 -->
      <Alert type="warning" show-icon>
        <template #message>
          <p class="font-semibold">警告：此操作将立即结束考试！</p>
          <ul class="list-disc pl-5">
            <li>考试状态从「进行中」改为「已结束」</li>
            <li>
              <span class="font-semibold text-red-600">触发缺考标记</span>
              （未交卷学生自动记缺考）
            </li>
            <li>锁定当前答卷（按最后自动保存）</li>
          </ul>
          <p class="mt-2 text-sm">这是不可逆操作，请谨慎执行。</p>
        </template>
      </Alert>
    </Modal>

    <!-- 删除确认弹窗（形态对齐「强制结束」：warning Alert 明示不可逆） -->
    <Modal
      v-model:open="deleteModalOpen"
      title="确认删除考试"
      :confirm-loading="deleting"
      ok-text="删除"
      @ok="confirmDelete"
    >
      <!-- 同上：正文走 #message 具名插槽，否则默认插槽被静默丢弃 -->
      <Alert type="warning" show-icon>
        <template #message>
          <p class="font-semibold">警告：此操作将删除该考试！</p>
          <ul class="list-disc pl-5">
            <li>考试软删除，列表中不再可见</li>
            <li>
              <span class="font-semibold text-red-600">删除后不可恢复</span>
              （能否删除以后端裁决为准，失败原因原文提示）
            </li>
          </ul>
          <p class="mt-2 text-sm">这是不可逆操作，请谨慎执行。</p>
        </template>
      </Alert>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import {
  Alert,
  Button,
  Card,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  message,
  type TableColumnsType,
} from 'ant-design-vue';
import dayjs from 'dayjs';
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useQuery } from '@tanstack/vue-query';

import {
  delete2 as deleteExamContract,
  forceEnd as forceEndContract,
  page2 as pageExams,
  publish1 as publishExam,
  type ExamResponse,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { queryClient } from '@/api/queryClient';
import { EXAM_STATUS, getExamStatusConfig, type ExamStatus } from '@/constants/examStatus';

/**
 * 考试列表页面（阶段 21 考务，修复版）。
 * 后端契约：GET /api/exams 分页（教师仅见自己的考试）、
 * POST /api/exams/{id}/publish（生成快照）、POST /api/exams/{id}/force-end（触发缺考标记）、
 * PUT /api/exams/{id}（编辑，跳创建页）与 DELETE /api/exams/{id}（软删）。
 * 按钮可见性按后端返回的 status/published 渲染，前端不自行推算状态机：
 * 「编辑」与发布同源（未开始+未发布）、「删除」乐观放宽为仅未发布——
 * 能否编辑/删除的最终裁决在后端 ExamService.assertEditable，失败 message 原文呈现。
 */

const router = useRouter();

const columns: TableColumnsType = [
  { title: 'ID', key: 'id', dataIndex: 'id', width: 70 },
  { title: '考试标题', key: 'title', dataIndex: 'title' },
  { title: '试卷 ID', key: 'paperId', dataIndex: 'paperId', width: 100 },
  { title: '班级 ID', key: 'classId', dataIndex: 'classId', width: 100 },
  { title: '状态', key: 'status', width: 100 },
  { title: '考试发布', key: 'published', width: 110 },
  { title: '开始时间', key: 'startTime', width: 170 },
  { title: '结束时间', key: 'endTime', width: 170 },
  { title: '个人时长(分)', key: 'durationMinutes', dataIndex: 'durationMinutes', width: 110 },
  { title: '操作', key: 'actions', width: 320 },
];

function formatTime(value: string | undefined | null): string {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—';
}

const statusOptions = [
  { value: EXAM_STATUS.NOT_STARTED, label: '未开始' },
  { value: EXAM_STATUS.IN_PROGRESS, label: '进行中' },
  { value: EXAM_STATUS.ENDED, label: '已结束' },
  { value: EXAM_STATUS.GRADED, label: '已批改' },
  { value: EXAM_STATUS.PUBLISHED, label: '成绩已发布' },
];

const pageNum = ref(1);
const pageSize = ref(10);
const statusFilter = ref<number | undefined>(undefined);
const searchTitle = ref('');

const { data, isFetching, refetch } = useQuery({
  queryKey: computed(() => ['exams', pageNum.value, pageSize.value] as const),
  queryFn: () =>
    unwrap<ExamResponse[]>(
      pageExams({
        client,
        throwOnError: true,
        query: { page: pageNum.value, size: pageSize.value },
      })
    ),
});

const rows = computed<ExamResponse[]>(() => data.value ?? []);

const filteredRows = computed<ExamResponse[]>(() => {
  let result = rows.value;
  if (searchTitle.value.trim()) {
    result = result.filter((e) => e.title?.includes(searchTitle.value.trim()));
  }
  if (statusFilter.value !== undefined && statusFilter.value !== null) {
    result = result.filter((e) => e.status === statusFilter.value);
  }
  return result;
});

const tablePagination = computed(() => ({
  current: pageNum.value,
  pageSize: pageSize.value,
  // GET /api/exams 返回当页数组（无 total 信封）：是否还有下一页以「当页满页」推断，
  // 只影响分页器显示，不影响数据正确性（越界页后端返回空数组）
  total: pageNum.value * pageSize.value,
  showSizeChanger: false,
}));

function onTableChange(pag: { current?: number; pageSize?: number }): void {
  if (typeof pag.current === 'number') pageNum.value = pag.current;
  if (typeof pag.pageSize === 'number') pageSize.value = pag.pageSize;
  void refetch();
}

// ===== 发布考试 =====
const publishModalOpen = ref(false);
const publishingExamId = ref<number | undefined>(undefined);
const publishing = ref(false);

function openPublishConfirm(exam: ExamResponse): void {
  publishingExamId.value = exam.id;
  publishModalOpen.value = true;
}

async function confirmPublish(): Promise<void> {
  if (publishingExamId.value === undefined) return;
  publishing.value = true;
  try {
    await unwrap(publishExam({ client, throwOnError: true, path: { id: publishingExamId.value } }));
    message.success('考试已发布，试卷快照已生成');
    publishModalOpen.value = false;
    void queryClient.invalidateQueries({ queryKey: ['exams'] });
  } catch (error) {
    message.error(error instanceof Error ? error.message : '发布考试失败，请稍后重试');
  } finally {
    publishing.value = false;
  }
}

// ===== 强制结束 =====
const forceEndModalOpen = ref(false);
const forceEndingExamId = ref<number | undefined>(undefined);
const forceEnding = ref(false);

function openForceEndConfirm(exam: ExamResponse): void {
  forceEndingExamId.value = exam.id;
  forceEndModalOpen.value = true;
}

async function confirmForceEnd(): Promise<void> {
  if (forceEndingExamId.value === undefined) return;
  forceEnding.value = true;
  try {
    await unwrap(
      forceEndContract({ client, throwOnError: true, path: { id: forceEndingExamId.value } })
    );
    message.success('考试已强制结束，缺考标记已触发');
    forceEndModalOpen.value = false;
    void queryClient.invalidateQueries({ queryKey: ['exams'] });
  } catch (error) {
    message.error(error instanceof Error ? error.message : '强制结束失败，请稍后重试');
  } finally {
    forceEnding.value = false;
  }
}

// ===== 删除考试 =====
const deleteModalOpen = ref(false);
const deletingExamId = ref<number | undefined>(undefined);
const deleting = ref(false);

function openDeleteConfirm(exam: ExamResponse): void {
  deletingExamId.value = exam.id;
  deleteModalOpen.value = true;
}

async function confirmDelete(): Promise<void> {
  if (deletingExamId.value === undefined) return;
  deleting.value = true;
  try {
    await unwrap(
      deleteExamContract({ client, throwOnError: true, path: { id: deletingExamId.value } })
    );
    message.success('考试已删除');
    deleteModalOpen.value = false;
    void queryClient.invalidateQueries({ queryKey: ['exams'] });
  } catch (error) {
    message.error(error instanceof Error ? error.message : '删除考试失败，请稍后重试');
  } finally {
    deleting.value = false;
  }
}
</script>

<style scoped>
.mb-3 {
  margin-bottom: 0.75rem;
}
.mb-4 {
  margin-bottom: 1rem;
}
</style>
