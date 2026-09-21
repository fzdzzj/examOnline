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

      <!-- 四个计数全部原样来自后端 overview：前端不做加总、不倒推缺考 -->
      <div class="stat-grid mb-4">
        <Card size="small">
          <p class="stat-label">已进入考试（有答卷）</p>
          <p class="stat-value">{{ overview.totalStudents ?? 0 }}</p>
        </Card>
        <Card size="small">
          <p class="stat-label">在线</p>
          <p class="stat-value">{{ overview.onlineCount ?? 0 }}</p>
        </Card>
        <Card size="small">
          <p class="stat-label">离线（心跳过期）</p>
          <p class="stat-value">{{ overview.offlineCount ?? 0 }}</p>
        </Card>
        <Card size="small">
          <p class="stat-label">异常（严重度≥中）</p>
          <p class="stat-value">{{ overview.abnormalCount ?? 0 }}</p>
        </Card>
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
        :data-source="students"
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
      <p v-if="!loading && students.length === 0" class="mb-0 mt-3 text-gray-500">
        后端返回本场考试还没有答卷行（totalStudents = 0）——尚无学生进入考试。
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
  Progress,
  Space,
  Table,
  Tag,
  type TableColumnsType,
} from 'ant-design-vue';
import dayjs from 'dayjs';
import { computed } from 'vue';

import type { MonitorOverviewResponse, MonitorStudentItem } from '@/api/axios';
import { MONITOR_POLLING_HINT } from '@/constants/monitor';
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

const emit = defineEmits<{ (e: 'view-log', student: MonitorStudentItem): void }>();

const students = computed<MonitorStudentItem[]>(() => props.overview?.students ?? []);
const totalQuestions = computed(() => props.overview?.totalQuestions ?? 0);

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
:deep(.abnormal-row) {
  background: #fff7e6;
}
</style>
