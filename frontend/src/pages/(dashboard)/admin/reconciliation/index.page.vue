<template>
  <div>
    <Card title="成绩链对账" class="mb-4">
      <div class="toolbar">
        <Input
          v-model:value="examIdInput"
          placeholder="考试 ID（可选，留空表示全量对账）"
          allow-clear
          style="width: 260px"
        />
        <Button type="primary" :loading="running" @click="triggerRun">触发对账</Button>
      </div>

      <!-- 三态分离：查询失败显性呈现后端 message 并隐藏表格，不落「暂无数据」空态 -->
      <Alert
        v-if="queryErrorText"
        type="error"
        show-icon
        :message="queryErrorText"
        data-test="reconciliation-error"
        class="mb-3"
      />
      <template v-else>
        <h3 class="section-title">运行历史</h3>
        <Table
          :columns="runColumns"
          :data-source="runsRows"
          :loading="runsFetching"
          :pagination="runsPagination"
          :row-key="(row: ReconciliationRunResponse) => row.id as number"
          size="middle"
          @change="onRunsTableChange"
        >
          <template #emptyText>暂无数据</template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'status'">
              <!-- 只按后端原串取色，文案直渲染后端原串，前端不翻译不推算 -->
              <Tag :color="statusColor((record as ReconciliationRunResponse).status)">
                {{ (record as ReconciliationRunResponse).status }}
              </Tag>
            </template>
            <template v-else-if="column.key === 'startedTime'">
              {{ formatTime((record as ReconciliationRunResponse).startedTime) }}
            </template>
            <template v-else-if="column.key === 'finishedTime'">
              {{ formatTime((record as ReconciliationRunResponse).finishedTime) }}
            </template>
          </template>
        </Table>

        <h3 class="section-title">差异票</h3>
        <div class="toolbar">
          <Input
            v-model:value="runIdFilter"
            placeholder="运行 ID"
            allow-clear
            style="width: 140px"
          />
          <Select
            v-model:value="diffTypeFilter"
            placeholder="差异类型"
            allow-clear
            style="width: 230px"
            :options="DIFF_TYPE_OPTIONS"
          />
          <Select
            v-model:value="statusFilter"
            placeholder="处置状态"
            allow-clear
            style="width: 180px"
            :options="DIFF_STATUS_OPTIONS"
          />
        </div>
        <Table
          :columns="diffColumns"
          :data-source="diffRows"
          :loading="diffsFetching"
          :pagination="diffsPagination"
          :row-key="(row: ReconciliationDiffResponse) => row.id as number"
          size="middle"
          @change="onDiffsTableChange"
        >
          <template #emptyText>暂无数据</template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'diffType'">
              <Tag :color="diffTypeColor((record as ReconciliationDiffResponse).diffType)">
                {{ (record as ReconciliationDiffResponse).diffType }}
              </Tag>
            </template>
            <template v-else-if="column.key === 'status'">
              <Tag :color="statusColor((record as ReconciliationDiffResponse).status)">
                {{ (record as ReconciliationDiffResponse).status }}
              </Tag>
            </template>
            <template v-else-if="column.key === 'createdTime'">
              {{ formatTime((record as ReconciliationDiffResponse).createdTime) }}
            </template>
            <template v-else-if="column.key === 'actions'">
              <!-- 人工关闭只对未处置票开放（fail-closed）：AUTO_FIXED / MANUAL_CLOSED 无入口 -->
              <Button
                v-if="isOpenDiff(record as ReconciliationDiffResponse)"
                type="link"
                size="small"
                @click="openCloseConfirm(record as ReconciliationDiffResponse)"
              >
                人工关闭
              </Button>
            </template>
          </template>
        </Table>
      </template>
    </Card>

    <!-- 人工关闭确认弹窗：明示关闭只代表处置完成，不改分、不重算 -->
    <Modal
      v-model:open="closeModalOpen"
      title="关闭差异票"
      :confirm-loading="closing"
      ok-text="确认关闭"
      @ok="confirmClose"
    >
      <!-- 正文走 #message 具名插槽：ant-design-vue 4 的 Alert 默认插槽会被静默丢弃 -->
      <Alert type="warning" show-icon>
        <template #message>
          <p class="font-semibold">关闭仅表示该差异票已人工处置完成，不会改动任何分数。</p>
          <ul class="list-disc pl-5">
            <li>关闭后状态置为 MANUAL_CLOSED，只记录处置人与处置时间</li>
            <li>不回写分数、不重算成绩链；后续对账仍会重新扫描该考试</li>
            <li>若差异真实存在，请先在成绩复核流程中处理后关闭</li>
          </ul>
        </template>
      </Alert>
      <Input v-model:value="closeReason" placeholder="处置说明（可选）" style="margin-top: 12px" />
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
  Table,
  Tag,
  message,
  type TableColumnsType,
} from 'ant-design-vue';
import dayjs from 'dayjs';
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';

import {
  closeReconciliationDiff,
  reconciliationDiffs,
  reconciliationRuns,
  runReconciliation,
  type ReconciliationDiffResponse,
  type ReconciliationRunResponse,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { queryClient } from '@/api/queryClient';

/**
 * 管理端「成绩链对账」页（落既有 /admin 角色分区）。
 *
 * 后端契约：POST /api/admin/reconciliation/run（触发，body 可选 examId）、
 * GET /api/admin/reconciliation/runs（page/size，返回页内数组）、
 * GET /api/admin/reconciliation/diffs（page/size/runId/diffType/status，返回页内数组）、
 * POST /api/admin/reconciliation/diffs/{id}/close（人工关闭，body 可选 reason）。
 * 权限边界在后端 @RequireRole(ADMIN) 一类注解，本页只是体验层入口。
 *
 * 口径：status / diffType 一律按后端原串直渲染与取色，前端不维护第二套语义；运行与差异
 * 响应信封都没有 total，分页只做「满页即至少还有下一页」的下界推断（范式抄 audit-logs 页）；
 * 筛选走服务端参数 + 300ms 防抖 + 变更回第 1 页，空值不携带该参数；查询失败以 Alert 显性
 * 呈现后端 message 并隐藏表格，不伪装空态；触发与关闭失败 message 原文呈现，不本地编造。
 */

/** 单页条数取后端默认值（上限由后端 @Max 与 Math.min 双兜底，前端不自行放大） */
const DEFAULT_PAGE_SIZE = 20;

const runColumns: TableColumnsType = [
  { title: '运行 ID', key: 'id', dataIndex: 'id', width: 90 },
  { title: '考试 ID', key: 'examId', dataIndex: 'examId', width: 90 },
  { title: '触发来源', key: 'triggerSource', dataIndex: 'triggerSource', width: 130 },
  { title: '状态', key: 'status', width: 110 },
  { title: '扫描', key: 'scannedCount', dataIndex: 'scannedCount', width: 80 },
  { title: '未判分', key: 'ungradedCount', dataIndex: 'ungradedCount', width: 90 },
  { title: '明细不一致', key: 'mismatchCount', dataIndex: 'mismatchCount', width: 110 },
  { title: '自动补齐', key: 'autoFixedCount', dataIndex: 'autoFixedCount', width: 100 },
  { title: '错误信息', key: 'errorMessage', dataIndex: 'errorMessage', ellipsis: true },
  { title: '开始时间', key: 'startedTime', width: 150 },
  { title: '结束时间', key: 'finishedTime', width: 150 },
];

const diffColumns: TableColumnsType = [
  { title: '运行 ID', key: 'runId', dataIndex: 'runId', width: 90 },
  { title: '考试 ID', key: 'examId', dataIndex: 'examId', width: 90 },
  { title: '答卷 ID', key: 'submissionId', dataIndex: 'submissionId', width: 100 },
  { title: '差异类型', key: 'diffType', width: 200 },
  { title: '状态', key: 'status', width: 130 },
  { title: '期望值', key: 'expectedValue', dataIndex: 'expectedValue', width: 90 },
  { title: '实际值', key: 'actualValue', dataIndex: 'actualValue', width: 90 },
  { title: '偏差', key: 'deviation', dataIndex: 'deviation', width: 90 },
  { title: '说明', key: 'detail', dataIndex: 'detail', ellipsis: true },
  { title: '创建时间', key: 'createdTime', width: 150 },
  { title: '操作', key: 'actions', width: 110 },
];

/** 差异类型选项：值即后端原串，前端不做中文映射，避免第二套语义 */
const DIFF_TYPE_OPTIONS = [
  { label: 'SUBMISSION_UNGRADED', value: 'SUBMISSION_UNGRADED' },
  { label: 'DETAIL_TOTAL_MISMATCH', value: 'DETAIL_TOTAL_MISMATCH' },
];

/** 处置状态选项：同上，直取后端原串 */
const DIFF_STATUS_OPTIONS = [
  { label: 'OPEN', value: 'OPEN' },
  { label: 'AUTO_FIXED', value: 'AUTO_FIXED' },
  { label: 'MANUAL_CLOSED', value: 'MANUAL_CLOSED' },
];

function formatTime(value: string | undefined | null): string {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—';
}

/** 运行状态与差异状态的取色统一按后端原串，未知取值回落 default */
function statusColor(status: string | undefined): string {
  switch (status) {
    case 'RUNNING':
      return 'blue';
    case 'SUCCESS':
      return 'green';
    case 'FAILED':
      return 'red';
    case 'OPEN':
      return 'orange';
    case 'AUTO_FIXED':
      return 'blue';
    case 'MANUAL_CLOSED':
      return 'default';
    default:
      return 'default';
  }
}

/** 差异类型取色同样只认后端原串 */
function diffTypeColor(diffType: string | undefined): string {
  switch (diffType) {
    case 'SUBMISSION_UNGRADED':
      return 'orange';
    case 'DETAIL_TOTAL_MISMATCH':
      return 'red';
    default:
      return 'default';
  }
}

/** 可人工关闭判定：状态为 OPEN 且带 id（id 是关闭接口的唯一路径参数） */
function isOpenDiff(row: ReconciliationDiffResponse): boolean {
  return typeof row.id === 'number' && row.status === 'OPEN';
}

// ===== 触发对账 =====
const examIdInput = ref('');
const running = ref(false);

async function triggerRun(): Promise<void> {
  const raw = examIdInput.value.trim();
  const parsed = raw ? Number(raw) : undefined;
  const examId =
    parsed !== undefined && Number.isInteger(parsed) && parsed > 0 ? parsed : undefined;
  if (raw && examId === undefined) {
    message.error('考试 ID 需为正整数，或留空做全量对账');
    return;
  }
  running.value = true;
  try {
    const run = await unwrap<ReconciliationRunResponse>(
      runReconciliation({
        client,
        throwOnError: true,
        // 空值不携带 examId：后端按「未指定考试」走全量对账
        body: examId !== undefined ? { examId } : {},
      })
    );
    message.success(
      `对账完成：未判分 ${run?.ungradedCount ?? 0} 条，明细不一致 ${run?.mismatchCount ?? 0} 条，` +
        `自动补齐 ${run?.autoFixedCount ?? 0} 条`
    );
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['reconciliation-runs'] }),
      queryClient.invalidateQueries({ queryKey: ['reconciliation-diffs'] }),
    ]);
  } catch (error) {
    message.error(error instanceof Error ? error.message : '触发对账失败，请稍后重试');
  } finally {
    running.value = false;
  }
}

// ===== 运行历史（page/size） =====
const runsPage = ref(1);
const runsPageSize = ref(DEFAULT_PAGE_SIZE);

const {
  data: runsData,
  error: runsError,
  isFetching: runsFetching,
  refetch: refetchRuns,
} = useQuery({
  queryKey: computed(() => ['reconciliation-runs', runsPage.value, runsPageSize.value] as const),
  queryFn: () =>
    unwrap<ReconciliationRunResponse[]>(
      reconciliationRuns({
        client,
        throwOnError: true,
        query: { page: runsPage.value, size: runsPageSize.value },
      })
    ),
});

const runsRows = computed<ReconciliationRunResponse[]>(() => runsData.value ?? []);

// ===== 差异票（page/size + runId/diffType/status 筛选） =====
const runIdFilter = ref('');
const diffTypeFilter = ref<string | undefined>(undefined);
const statusFilter = ref<string | undefined>(undefined);
const debouncedRunId = ref<number | undefined>(undefined);
const debouncedDiffType = ref<string | undefined>(undefined);
const debouncedStatus = ref<string | undefined>(undefined);
const diffPage = ref(1);
const diffPageSize = ref(DEFAULT_PAGE_SIZE);

let debounceTimer: ReturnType<typeof setTimeout> | null = null;
watch([runIdFilter, diffTypeFilter, statusFilter], () => {
  if (debounceTimer !== null) clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => {
    const rawRunId = runIdFilter.value.trim();
    const parsedRunId = rawRunId ? Number(rawRunId) : NaN;
    debouncedRunId.value =
      Number.isInteger(parsedRunId) && parsedRunId > 0 ? parsedRunId : undefined;
    debouncedDiffType.value = (diffTypeFilter.value ?? '').trim() || undefined;
    debouncedStatus.value = (statusFilter.value ?? '').trim() || undefined;
    // 筛选变更回第 1 页：换了筛选条件再停在第 N 页会展示错位结果
    diffPage.value = 1;
  }, 300);
});

onBeforeUnmount(() => {
  if (debounceTimer !== null) clearTimeout(debounceTimer);
});

const {
  data: diffsData,
  error: diffsError,
  isFetching: diffsFetching,
  refetch: refetchDiffs,
} = useQuery({
  queryKey: computed(
    () =>
      [
        'reconciliation-diffs',
        diffPage.value,
        diffPageSize.value,
        debouncedRunId.value,
        debouncedDiffType.value,
        debouncedStatus.value,
      ] as const
  ),
  queryFn: () =>
    unwrap<ReconciliationDiffResponse[]>(
      reconciliationDiffs({
        client,
        throwOnError: true,
        query: {
          page: diffPage.value,
          size: diffPageSize.value,
          // 空筛选值不携带该参数（对齐「空描述不携带」既有口径）
          ...(debouncedRunId.value !== undefined ? { runId: debouncedRunId.value } : {}),
          ...(debouncedDiffType.value ? { diffType: debouncedDiffType.value } : {}),
          ...(debouncedStatus.value ? { status: debouncedStatus.value } : {}),
        },
      })
    ),
});

const diffRows = computed<ReconciliationDiffResponse[]>(() => diffsData.value ?? []);

// 三态分离：任一查询失败都用 Alert 承载后端 message，两个表格一起隐藏
const queryErrorText = computed<string | null>(() => {
  const caught = runsError.value ?? diffsError.value;
  if (!caught) return null;
  return caught instanceof Error ? caught.message : '对账数据加载失败';
});

/**
 * 信封无 total（响应是页内数组），只能下界推断：满页即「至少还有下一页」，末页不预留。
 * 不写字面 `total: pageNum * pageSize`——按 antd 的 calculatePage 那写法在满页时只有 1 页。
 */
function lowerBoundTotal(page: number, size: number, length: number): number {
  return (page - 1) * size + length + (length === size ? 1 : 0);
}

const runsPagination = computed(() => ({
  current: runsPage.value,
  pageSize: runsPageSize.value,
  total: lowerBoundTotal(runsPage.value, runsPageSize.value, runsRows.value.length),
  showSizeChanger: false,
}));

const diffsPagination = computed(() => ({
  current: diffPage.value,
  pageSize: diffPageSize.value,
  total: lowerBoundTotal(diffPage.value, diffPageSize.value, diffRows.value.length),
  showSizeChanger: false,
}));

function onRunsTableChange(pag: { current?: number; pageSize?: number }): void {
  if (typeof pag.current === 'number') runsPage.value = pag.current;
  if (typeof pag.pageSize === 'number') runsPageSize.value = pag.pageSize;
  void refetchRuns();
}

function onDiffsTableChange(pag: { current?: number; pageSize?: number }): void {
  if (typeof pag.current === 'number') diffPage.value = pag.current;
  if (typeof pag.pageSize === 'number') diffPageSize.value = pag.pageSize;
  void refetchDiffs();
}

// ===== 人工关闭差异票 =====
const closeModalOpen = ref(false);
const closing = ref(false);
const closingDiffId = ref<number | undefined>(undefined);
const closeReason = ref('');

function openCloseConfirm(row: ReconciliationDiffResponse): void {
  if (!isOpenDiff(row)) return;
  closingDiffId.value = row.id;
  closeReason.value = '';
  closeModalOpen.value = true;
}

async function confirmClose(): Promise<void> {
  const id = closingDiffId.value;
  if (id === undefined) return;
  const reason = closeReason.value.trim();
  closing.value = true;
  try {
    await unwrap(
      closeReconciliationDiff({
        client,
        throwOnError: true,
        path: { id },
        body: reason ? { reason } : {},
      })
    );
    message.success('差异票已人工关闭（仅记录处置，未改动分数）');
    closeModalOpen.value = false;
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['reconciliation-runs'] }),
      queryClient.invalidateQueries({ queryKey: ['reconciliation-diffs'] }),
    ]);
  } catch (error) {
    message.error(error instanceof Error ? error.message : '关闭差异票失败，请稍后重试');
  } finally {
    closing.value = false;
  }
}
</script>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.section-title {
  margin: 16px 0 8px;
  font-size: 15px;
  font-weight: 600;
}
.mb-3 {
  margin-bottom: 0.75rem;
}
.mb-4 {
  margin-bottom: 1rem;
}
</style>
