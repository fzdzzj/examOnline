<template>
  <div>
    <Alert v-if="error" type="error" show-icon :message="error" class="mb-3" />

    <template v-else-if="overview">
      <!-- 新鲜度声明：轮询间隔与措辞都取自 constants/monitor.ts，
           不写「实时」——本系统 v3 无 WebSocket 推送 -->
      <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
        <Space>
          <span class="font-semibold">{{ overview.examTitle ?? `考试 #${examIdLabel}` }}</span>
          <Tag :color="statusConfig.color">{{ statusConfig.label }}</Tag>
        </Space>
        <Space>
          <Tag color="blue">{{ MONITOR_POLLING_HINT }}</Tag>
          <span class="text-xs text-gray-500">题目总数（进度分母）{{ totalQuestions }}</span>
        </Space>
      </div>

      <!-- 工具栏：本地搜索 + 立即刷新 + 下次轮询倒计时（间隔取自 constants/monitor.ts，页面不写字面量） -->
      <div class="mb-3 flex flex-wrap items-center gap-2">
        <Input
          v-model:value="searchText"
          allow-clear
          placeholder="按姓名 / 学生 ID 搜索"
          class="w-56"
          :data-test="'monitor-search'"
        />
        <Button
          size="small"
          :loading="refreshing"
          :disabled="!!loading"
          data-test="refresh-now"
          @click="onRefreshNow"
        >
          立即刷新
        </Button>
        <span class="text-xs text-gray-500" data-test="poll-countdown">
          下次刷新 {{ countdownSeconds }}s
        </span>
      </div>

      <!-- 四个计数全部原样来自后端 overview：前端不做加总、不倒推缺考 -->
      <!-- 在线/离线/异常三卡可点击联动学生表过滤（纯展示层行为，计数与进度仍原样来自 overview） -->
      <div class="stat-grid mb-4">
        <Card size="small">
          <p class="stat-label">已进入考试（有答卷）</p>
          <p class="stat-value">{{ overview.totalStudents ?? 0 }}</p>
        </Card>
        <Card
          size="small"
          class="stat-card-clickable"
          :class="filterStatus === 'ONLINE' ? 'stat-card-active' : ''"
          :data-test="'stat-card-online'"
          @click="toggleFilter('ONLINE')"
        >
          <p class="stat-label">在线</p>
          <p class="stat-value">{{ overview.onlineCount ?? 0 }}</p>
        </Card>
        <Card
          size="small"
          class="stat-card-clickable"
          :class="filterStatus === 'OFFLINE' ? 'stat-card-active' : ''"
          :data-test="'stat-card-offline'"
          @click="toggleFilter('OFFLINE')"
        >
          <p class="stat-label">离线（心跳过期）</p>
          <p class="stat-value">{{ overview.offlineCount ?? 0 }}</p>
        </Card>
        <Card
          size="small"
          class="stat-card-clickable"
          :class="filterAbnormal ? 'stat-card-active' : ''"
          :data-test="'stat-card-abnormal'"
          @click="toggleAbnormalFilter"
        >
          <p class="stat-label">异常（严重度≥中）</p>
          <p class="stat-value">{{ overview.abnormalCount ?? 0 }}</p>
        </Card>
      </div>

      <!-- 生效的过滤 Tag（可点击取消）：搜索关键词只显示在搜索框，这里只列卡片过滤态 -->
      <div v-if="hasActiveFilters" class="mb-2 flex flex-wrap items-center gap-2">
        <span class="text-xs text-gray-500">过滤：</span>
        <Tag
          v-if="filterStatus"
          closable
          color="blue"
          :data-test="'filter-tag-status'"
          @close="clearStatusFilter"
        >
          {{ statusLabelOf(filterStatus) }}
        </Tag>
        <Tag
          v-if="filterAbnormal"
          closable
          color="orange"
          :data-test="'filter-tag-abnormal'"
          @close="clearAbnormalFilter"
        >
          异常
        </Tag>
        <Button type="link" size="small" class="px-0" @click="clearAllFilters">清除全部</Button>
      </div>

      <p class="mb-1 text-sm">
        提交进度：已交卷 {{ overview.submittedCount ?? 0 }} / 已进入
        {{ overview.totalStudents ?? 0 }}
      </p>
      <Progress
        :percent="progressPercent"
        :status="progressPercent >= 100 ? 'success' : 'active'"
        class="mb-4"
      />

      <Table
        :columns="columns"
        :data-source="filteredStudents"
        :loading="!!loading"
        :pagination="false"
        :row-key="(row: MonitorStudentItem) => row.studentId as number"
        size="small"
        :row-class-name="(row: MonitorStudentItem) => (row.abnormal ? 'abnormal-row' : '')"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <Tag :color="statusColorOf((record as MonitorStudentItem).status)">
              {{ statusLabelOf((record as MonitorStudentItem).status) }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'progress'">
            <Progress
              :percent="(record as MonitorStudentItem).progressPercent ?? 0"
              size="small"
              :show-info="true"
            />
            <span class="text-xs text-gray-500">
              已答 {{ record.answeredCount ?? 0 }} / {{ totalQuestions }}
            </span>
          </template>
          <template v-else-if="column.key === 'abnormal'">
            <Space v-if="(record as MonitorStudentItem).abnormal">
              <Tag :color="severityColorOf((record as MonitorStudentItem).maxSeverity)">
                最高 {{ severityLabelOf((record as MonitorStudentItem).maxSeverity) }}
              </Tag>
              <span class="text-xs">异常 {{ record.abnormalEventCount ?? 0 }} 次</span>
              <span class="text-xs text-gray-500">
                最近 {{ formatTime((record as MonitorStudentItem).lastAbnormalTime) }}
              </span>
            </Space>
            <span v-else class="text-gray-400">—</span>
          </template>
          <template v-else-if="column.key === 'actions'">
            <Button
              type="link"
              size="small"
              :disabled="(record as MonitorStudentItem).studentId === undefined"
              @click="onViewLog(record as MonitorStudentItem)"
            >
              行为轨迹
            </Button>
          </template>
        </template>
      </Table>

      <!-- 有 overview 但一个学生都没进来：这是后端给的零，不是取不到数据 -->
      <p v-if="!loading && filteredStudents.length === 0" class="mb-0 mt-3 text-gray-500">
        {{
          students.length === 0
            ? '后端返回本场考试还没有答卷行（totalStudents = 0）——尚无学生进入考试。'
            : '当前过滤条件下没有匹配的学生行。'
        }}
      </p>
    </template>

    <!-- 既无数据也无错误：还没拿到第一次响应 -->
    <p v-else class="mb-0 text-gray-500">{{ loading ? '加载中…' : '暂未取到监考数据。' }}</p>
  </div>
</template>

<script setup lang="ts">
import {
  Alert,
  Button,
  Card,
  Input,
  Progress,
  Space,
  Table,
  Tag,
  type TableColumnsType,
} from 'ant-design-vue';
import dayjs from 'dayjs';
import { computed, onScopeDispose, ref, watch } from 'vue';

import type { MonitorOverviewResponse, MonitorStudentItem } from '@/api/axios';
import { MONITOR_POLLING_HINT, MONITOR_POLLING_INTERVAL_MS } from '@/constants/monitor';
import { getExamStatusConfig, type ExamStatus } from '@/constants/examStatus';
import { getSeverityConfig } from '@/constants/severity';
import { submittedRatioOf } from '@/hooks/useExamMonitor';

/**
 * 监考视图（阶段 21 缺口 3）。
 *
 * ⚠️ 这里**没有任何前端推算**：人数、逐学生进度、异常判定（`MonitorService` 按
 * severity≥2 聚合）全部按 `GET /api/exams/{examId}/monitor/overview` 的返回渲染。
 * 越权（看别人的考试）由后端 `requireOwnedExam` 拒绝，本组件不做"看得到才请求"的假设。
 */

const props = defineProps<{
  examIdLabel?: number | string;
  overview?: MonitorOverviewResponse | null;
  loading?: boolean;
  error?: string | null;
}>();

const emit = defineEmits<{
  (e: 'view-log', student: MonitorStudentItem): void;
  (e: 'refresh'): void;
}>();

const students = computed<MonitorStudentItem[]>(() => props.overview?.students ?? []);
const totalQuestions = computed(() => props.overview?.totalQuestions ?? 0);

// ==== 本地过滤状态（纯展示层：计数与进度仍原样来自后端 overview，前端不推算） ====
const searchText = ref('');
/** 当前生效的卡片过滤：'ONLINE' | 'OFFLINE' | null（单选，再次点击取消）。 */
const filterStatus = ref<'ONLINE' | 'OFFLINE' | null>(null);
/** 异常行过滤开关（点击「异常」卡切换，异常按行 abnormal 字段判定）。 */
const filterAbnormal = ref(false);

/** 过滤后学生行：姓名/ID 本地搜索 + 卡片过滤。不发起任何后端请求。 */
const filteredStudents = computed<MonitorStudentItem[]>(() => {
  const keyword = searchText.value.trim().toLowerCase();
  return students.value.filter((student) => {
    if (filterStatus.value && student.status !== filterStatus.value) return false;
    if (filterAbnormal.value && !student.abnormal) return false;
    if (keyword) {
      const name = (student.studentName ?? '').toLowerCase();
      const id = student.studentId !== undefined ? String(student.studentId) : '';
      if (!name.includes(keyword) && !id.includes(keyword)) return false;
    }
    return true;
  });
});

const hasActiveFilters = computed(() => filterStatus.value !== null || filterAbnormal.value);

function toggleFilter(status: 'ONLINE' | 'OFFLINE'): void {
  filterStatus.value = filterStatus.value === status ? null : status;
}

function toggleAbnormalFilter(): void {
  filterAbnormal.value = !filterAbnormal.value;
}

function clearStatusFilter(): void {
  filterStatus.value = null;
}

function clearAbnormalFilter(): void {
  filterAbnormal.value = false;
}

function clearAllFilters(): void {
  filterStatus.value = null;
  filterAbnormal.value = false;
}

// ==== 手动立即刷新 + 下次轮询倒计时（间隔取自 constants/monitor.ts，页面不写字面量） ====
const refreshing = ref(false);
/** 倒计时（秒），每次拉到新 overview（轮询或手动刷新后）重置为完整间隔。 */
const countdownSeconds = ref(MONITOR_POLLING_INTERVAL_MS / 1000);

let countdownTimer: ReturnType<typeof setInterval> | undefined;
function startCountdown(): void {
  countdownSeconds.value = MONITOR_POLLING_INTERVAL_MS / 1000;
  if (countdownTimer) clearInterval(countdownTimer);
  countdownTimer = setInterval(() => {
    countdownSeconds.value = Math.max(0, countdownSeconds.value - 1);
  }, 1000);
}

onScopeDispose(() => {
  if (countdownTimer) clearInterval(countdownTimer);
});

/** overview 每次更新（轮询 / 页面恢复可见立即拉取）都让倒计时重新从完整间隔起算。 */
watch(
  () => props.overview,
  () => {
    if (props.overview) {
      startCountdown();
      refreshing.value = false;
    }
  },
  { immediate: true }
);

function onRefreshNow(): void {
  // loading=false 时允许手动刷新；把在途态反馈给页面（由 useQuery.refetch 接管）
  refreshing.value = true;
  emit('refresh');
}

/** 0~100 的整数百分比；后端一个数都没给（分母 0）时显示 0 并在文案里说明原因。 */
const progressPercent = computed(() => {
  const ratio = submittedRatioOf(props.overview);
  return ratio === null ? 0 : Math.round(ratio * 100);
});

const statusConfig = computed(() =>
  getExamStatusConfig((props.overview?.examStatus ?? -1) as ExamStatus)
);

const columns: TableColumnsType = [
  { title: '学生 ID', key: 'studentId', dataIndex: 'studentId', width: 90 },
  { title: '姓名', key: 'studentName', dataIndex: 'studentName', width: 140 },
  { title: '状态', key: 'status', width: 100 },
  { title: '答题进度', key: 'progress', width: 240 },
  { title: '异常', key: 'abnormal', width: 300 },
  { title: '操作', key: 'actions', width: 110 },
];

/** 状态字符串来自后端 `MonitorStudentItem.STATUS_*`。 */
const STATUS_LABELS: Record<string, string> = {
  ONLINE: '在线',
  OFFLINE: '离线',
  SUBMITTED: '已交卷',
};

const STATUS_COLORS: Record<string, string> = {
  ONLINE: 'processing',
  OFFLINE: 'default',
  SUBMITTED: 'success',
};

function statusLabelOf(status?: string): string {
  return (status && STATUS_LABELS[status]) || status || '—';
}

function statusColorOf(status?: string): string {
  return (status && STATUS_COLORS[status]) || 'default';
}

/** 严重度只用来决定颜色与兜底文案；后端另给了 `severityName` 时以它为准（见行为时间线）。 */
function severityColorOf(severity?: number): string {
  return getSeverityConfig(severity).color;
}

function severityLabelOf(severity?: number): string {
  return getSeverityConfig(severity).label;
}

function formatTime(value: string | undefined | null): string {
  return value ? dayjs(value).format('HH:mm:ss') : '—';
}

function onViewLog(student: MonitorStudentItem): void {
  emit('view-log', student);
}
</script>

<style scoped>
.mb-1 {
  margin-bottom: 0.25rem;
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
.mt-3 {
  margin-top: 0.75rem;
}
.flex {
  display: flex;
}
.flex-wrap {
  flex-wrap: wrap;
}
.items-center {
  align-items: center;
}
.justify-between {
  justify-content: space-between;
}
.gap-2 {
  gap: 0.5rem;
}
.text-sm {
  font-size: 0.875rem;
}
.text-xs {
  font-size: 0.75rem;
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
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0.75rem;
}
.stat-label {
  margin: 0;
  font-size: 0.75rem;
  color: #6b7280;
}
.stat-value {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
}
.stat-card-clickable {
  cursor: pointer;
  transition: box-shadow 0.2s;
}
.stat-card-active {
  box-shadow: 0 0 0 2px #4096ff;
}
:deep(.abnormal-row) {
  background: #fff7e6;
}
</style>
