<template>
  <div>
    <Card class="mb-4">
      <template #title>
        <Space wrap>
          <span>{{ detail?.title ?? (detailFetching ? '加载中…' : '考试详情') }}</span>
          <Tag v-if="detail" :color="statusConfig.color">{{ statusConfig.label }}</Tag>
          <Tag v-if="detail" :color="detail.published ? 'green' : 'default'">
            {{ detail.published ? '已发布（学生可见）' : '未发布' }}
          </Tag>
        </Space>
      </template>
      <template #extra>
        <Button @click="router.push('/teacher/exams')">返回考试列表</Button>
      </template>

      <Alert v-if="detailError" type="error" show-icon :message="detailError" class="mb-0" />
      <p v-else class="mb-0 text-gray-500">
        状态与可用操作一律按接口返回渲染（本页面只读，发布 / 强制结束在列表页执行）。
      </p>
    </Card>

    <Tabs v-model:activeKey="activeTab">
      <!-- ==================== 概览 ==================== -->
      <TabPane key="overview" tab="概览">
        <Descriptions bordered size="small" :column="2">
          <DescriptionsItem label="考试 ID">{{ detail?.id ?? '—' }}</DescriptionsItem>
          <DescriptionsItem label="绑定试卷">
            {{ detail?.paperTitle ?? `#${detail?.paperId ?? '—'}` }}
          </DescriptionsItem>
          <DescriptionsItem label="班级">
            {{
              className ?? (detail?.classId === undefined ? '未绑定班级' : `#${detail?.classId}`)
            }}
          </DescriptionsItem>
          <DescriptionsItem label="快照 ID">
            {{ detail?.snapshotId ?? '—（未发布则无快照）' }}
          </DescriptionsItem>
          <DescriptionsItem label="开始时间">{{ formatTime(detail?.startTime) }}</DescriptionsItem>
          <DescriptionsItem label="结束时间">{{ formatTime(detail?.endTime) }}</DescriptionsItem>
          <DescriptionsItem label="个人时长（分钟）">
            {{ detail?.durationMinutes ?? '—' }}
          </DescriptionsItem>
          <DescriptionsItem label="允许迟到（分钟）">
            {{ detail?.allowLateMinutes ?? '—' }}
          </DescriptionsItem>
          <DescriptionsItem label="强制结束标记">
            {{ detail?.forceEnd ? '已强制结束' : '—' }}
          </DescriptionsItem>
          <DescriptionsItem label="创建时间">
            {{ formatTime(detail?.createdTime) }}
          </DescriptionsItem>
        </Descriptions>

        <p class="mt-4 mb-2 text-sm font-semibold">防作弊配置（后端已存字段，原样展示）</p>
        <Table
          v-if="antiCheatFields.length"
          :columns="fieldColumns"
          :data-source="antiCheatFields"
          :pagination="false"
          size="small"
          row-key="key"
        />
        <p v-else class="mb-0 text-gray-500">后端未返回 antiCheatConfig（或该考试未配置）。</p>
      </TabPane>

      <!-- ==================== 试卷快照 ==================== -->
      <TabPane key="snapshot" tab="试卷快照">
        <ExamSnapshotPreview
          :snapshot="snapshot"
          :loading="snapshotFetching"
          :error="snapshotErrorMessage"
          :not-found="snapshotNotFound"
        />
      </TabPane>

      <!-- ==================== 考生名单与提交进度 ==================== -->
      <TabPane key="roster" tab="考生名单与进度">
        <Space class="mb-3" wrap>
          <Button size="small" :loading="rosterFetching" @click="refreshRoster">刷新名单</Button>
          <span class="text-xs text-gray-500">{{ MONITOR_POLLING_HINT }}的计数见「监考」页</span>
        </Space>

        <Alert v-if="rosterError" type="error" show-icon :message="rosterError" class="mb-3" />

        <Alert
          v-else-if="detail && !detail.classId"
          type="warning"
          show-icon
          class="mb-3"
          message="该考试未绑定班级，读不到班级名单"
          description="后端没有「按考试列考生」的端点（exam_candidates 未对外开放），因此这类考试的名单只能来自监考视图里已进入考试的学生。"
        />

        <!-- 名单 = 班级学生（应考）+ 后端 overview 给的进入/交卷状态（实考） -->
        <Table
          :columns="rosterColumns"
          :data-source="rosterRows"
          :loading="rosterFetching"
          :pagination="false"
          size="small"
          :row-key="(row: RosterRow) => row.userId"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'enteredTime'">
              {{ formatTime((record as RosterRow).joinedTime) }}
            </template>
            <template v-else-if="column.key === 'monitorStatus'">
              <Tag v-if="(record as RosterRow).monitorStatus" :color="monitorStatusColor">
                {{ (record as RosterRow).monitorStatus }}
              </Tag>
              <span v-else class="text-gray-400">未进入</span>
            </template>
            <template v-else-if="column.key === 'progress'">
              <span v-if="(record as RosterRow).progressPercent !== undefined">
                {{ (record as RosterRow).progressPercent }}%
              </span>
              <span v-else class="text-gray-400">—</span>
            </template>
          </template>
        </Table>
        <p v-if="!rosterFetching && rosterRows.length === 0" class="mb-0 text-gray-500">
          暂无名单数据（班级里没有学生）。
        </p>
      </TabPane>

      <!-- ==================== 监考 ==================== -->
      <TabPane key="monitor" tab="监考（准实时轮询）">
        <ExamMonitorPanel
          :exam-id-label="examId"
          :overview="overview"
          :loading="monitorFetching"
          :error="monitorError"
          @view-log="openStudentLog"
        />
        <Divider />
        <GrafanaEntry />
      </TabPane>

      <!-- ==================== 行为日志 ==================== -->
      <TabPane key="behavior" tab="行为日志">
        <Space class="mb-3" wrap align="end">
          <div>
            <p class="mb-1 text-xs text-gray-500">学生</p>
            <Select
              v-model:value="logStudentId"
              :options="logStudentOptions"
              placeholder="选择学生"
              style="width: 220px"
              show-search
              option-filter-prop="label"
            />
          </div>
          <div>
            <p class="mb-1 text-xs text-gray-500">查看方式</p>
            <RadioGroup
              v-model:value="logMode"
              :options="[
                { value: 'student', label: '单学生时间线' },
                { value: 'all', label: '全部日志（分页）' },
              ]"
              option-type="button"
              size="small"
            />
          </div>
          <div v-if="logMode === 'all'">
            <p class="mb-1 text-xs text-gray-500">事件类型（留空=全部）</p>
            <Input
              v-model:value="logEventType"
              placeholder="如 SWITCH_SCREEN"
              allow-clear
              style="width: 200px"
            />
          </div>
          <div v-if="logMode === 'all'">
            <p class="mb-1 text-xs text-gray-500">严重度</p>
            <Select
              v-model:value="logSeverity"
              :options="severityOptions"
              placeholder="全部"
              allow-clear
              style="width: 120px"
            />
          </div>
        </Space>

        <Alert
          v-if="logMode === 'student' && logStudentId === undefined"
          type="info"
          show-icon
          message="请选择学生后查看其行为轨迹（后端 timeline 端点的 studentId 为必填）"
        />

        <BehaviorTimeline
          v-else
          :logs="logRows"
          :stats="logStats"
          :total="logTotal"
          :loading="logFetching"
          :error="logError"
        />

        <div v-if="logMode === 'all'" class="mt-3 flex justify-end">
          <Pagination
            v-model:current="logPage"
            :page-size="logPageSize"
            :total="logTotal ?? 0"
            size="small"
            :show-size-changer="false"
          />
        </div>
      </TabPane>
    </Tabs>
  </div>
</template>

<script setup lang="ts">
import {
  Alert,
  Button,
  Card,
  Descriptions,
  DescriptionsItem,
  Divider,
  Input,
  Pagination,
  RadioGroup,
  Select,
  Space,
  Table,
  TabPane,
  Tabs,
  Tag,
  type TableColumnsType,
} from 'ant-design-vue';
import dayjs from 'dayjs';
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useQuery } from '@tanstack/vue-query';

import {
  detail2 as examDetailContract,
  getSnapshot1 as examSnapshotContract,
  listStudents,
  overview as monitorOverviewContract,
  page3 as pageClasses,
  page4 as behaviorPageContract,
  timeline as behaviorTimelineContract,
  type BehaviorLogItem,
  type BehaviorLogPageResponse,
  type BehaviorTimelineResponse,
  type ClassPageResponse,
  type ClassStudentItem,
  type ExamDetailResponse,
  type ExamSnapshotResponse,
  type MonitorOverviewResponse,
} from '@/api/axios';
import { ApiError } from '@/api/types';
import { BizCode } from '@/api/errorMap';
import { client, unwrap } from '@/api/apiClient';
import { getExamStatusConfig, type ExamStatus } from '@/constants/examStatus';
import { isMonitorConsumerTab, MONITOR_POLLING_HINT } from '@/constants/monitor';
import { SEVERITY_LEVEL } from '@/constants/severity';
import BehaviorTimeline from '@/components/exam/BehaviorTimeline.vue';
import ExamMonitorPanel from '@/components/exam/ExamMonitorPanel.vue';
import ExamSnapshotPreview from '@/components/exam/ExamSnapshotPreview.vue';
import GrafanaEntry from '@/components/observability/GrafanaEntry.vue';
import { createMonitorQueryOptions } from '@/hooks/useExamMonitor';
import { parseAntiCheatFields, type JsonField } from '@/utils/examSnapshot';

/**
 * 考试详情（阶段 21 缺口 2 + 3 + 4 + 5 的落位页面）。
 *
 * 后端契约：GET /api/exams/{id}（详情）、/snapshot（快照）、
 * /monitor/overview（监考总览，轮询）、/behavior-logs（分页）、/behavior-logs/timeline（单学生）；
 * 班级名单走 GET /api/classes/{id}/students。
 *
 * ⚠️ 本页面**不改任何状态**：发布 / 强制结束留在列表页（有二次确认），
 * 越权由后端 `exam:manage` + `requireOwnedExam` 裁决——这里能点到不代表前端认为合法。
 */

const route = useRoute('/(dashboard)/teacher/exams/[id]');
const router = useRouter();
const examId = computed(() => Number(route.params.id));

const activeTab = ref<'overview' | 'snapshot' | 'roster' | 'monitor' | 'behavior'>('overview');

// ==================== 详情 ====================

const {
  data: detail,
  isFetching: detailFetching,
  error: detailQueryError,
} = useQuery({
  queryKey: computed(() => ['exam', examId.value, 'detail'] as const),
  queryFn: () =>
    unwrap<ExamDetailResponse>(
      examDetailContract({ client, throwOnError: true, path: { id: examId.value } })
    ),
  enabled: computed(() => Number.isInteger(examId.value) && examId.value > 0),
  retry: 0,
});

const detailError = computed(() =>
  detailQueryError.value instanceof Error ? detailQueryError.value.message : null
);

const statusConfig = computed(() =>
  getExamStatusConfig((detail.value?.status ?? -1) as ExamStatus)
);

const antiCheatFields = computed<JsonField[]>(() =>
  parseAntiCheatFields(detail.value?.antiCheatConfig)
);

const fieldColumns: TableColumnsType = [
  { title: '配置项', key: 'key', dataIndex: 'key', width: 220 },
  { title: '值', key: 'value', dataIndex: 'value' },
];

function formatTime(value: string | undefined | null): string {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—';
}

// ==================== 试卷快照 ====================

const {
  data: snapshot,
  isFetching: snapshotFetching,
  error: snapshotError,
} = useQuery({
  queryKey: computed(() => ['exam', examId.value, 'snapshot'] as const),
  queryFn: () =>
    unwrap<ExamSnapshotResponse>(
      examSnapshotContract({ client, throwOnError: true, path: { id: examId.value } })
    ),
  enabled: computed(() => Number.isInteger(examId.value) && examId.value > 0),
  // 未发布时后端按 404 回「考试尚未发布或快照不存在」：这是空态，重试没有意义
  retry: 0,
});

/** 404 走空态；其余（403/500/网络）才是错误态——判据取后端业务码，不靠前端猜状态。 */
const snapshotNotFound = computed(() => isNotFound(snapshotError.value));
const snapshotErrorMessage = computed(() => {
  const error = snapshotError.value;
  if (!error) return null;
  if (isNotFound(error)) return null;
  return error instanceof Error ? error.message : '加载试卷快照失败';
});

function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && (error.code === BizCode.NOT_FOUND || error.status === 404);
}

// ==================== 监考总览（轮询） ====================

const monitorOptions = computed(() =>
  createMonitorQueryOptions(
    examId.value,
    (id) =>
      unwrap<MonitorOverviewResponse>(
        monitorOverviewContract({ client, throwOnError: true, path: { examId: id } })
      ),
    // 仅「监考」「考生名单与进度」页签消费 overview；其余页签暂停轮询
    { isConsumerTabActive: () => isMonitorConsumerTab(activeTab.value) }
  )
);

const {
  data: overview,
  isFetching: monitorFetching,
  error: monitorQueryError,
} = useQuery({
  queryKey: computed(() => monitorOptions.value.queryKey),
  queryFn: () => monitorOptions.value.queryFn(),
  refetchInterval: computed(() => monitorOptions.value.refetchInterval),
  enabled: computed(() => monitorOptions.value.enabled),
  refetchOnWindowFocus: false,
  retry: 0,
});

const monitorError = computed(() => {
  const error = monitorQueryError.value;
  if (!error) return null;
  if (isNotFound(error)) return '考试不存在或不属于当前教师（后端 404）';
  return error instanceof Error ? error.message : '监考数据获取失败';
});

// ==================== 考生名单 ====================

/** 班级名（展示用）与名单一起取，避免只有 ID 的人读不出信息。 */
const { data: classList } = useQuery({
  queryKey: ['classes', 'for-exam-detail'] as const,
  queryFn: () =>
    unwrap<ClassPageResponse>(
      pageClasses({ client, throwOnError: true, query: { page: 1, size: 100 } })
    ),
  enabled: computed(() => detail.value?.classId !== undefined),
});

const className = computed(
  () => classList.value?.list?.find((c) => c.id === detail.value?.classId)?.name
);

const {
  data: roster,
  isFetching: rosterFetching,
  error: rosterQueryError,
  refetch: refetchRoster,
} = useQuery({
  queryKey: computed(() => ['classes', detail.value?.classId ?? 0, 'students'] as const),
  queryFn: () =>
    unwrap<ClassStudentItem[]>(
      listStudents({ client, throwOnError: true, path: { id: detail.value?.classId as number } })
    ),
  enabled: computed(() => detail.value?.classId !== undefined),
  retry: 0,
});

const rosterError = computed(() =>
  rosterQueryError.value instanceof Error ? rosterQueryError.value.message : null
);

function refreshRoster(): void {
  void refetchRoster();
}

interface RosterRow {
  userId: number;
  username?: string;
  name?: string;
  joinedTime?: string;
  monitorStatus?: string;
  progressPercent?: number;
}

/**
 * 应考名单（班级学生）左连实考状态（后端 overview.students）。
 * 连接键是 studentId/userId；没匹配到就是「未进入」，不由前端推断缺考。
 */
const rosterRows = computed<RosterRow[]>(() => {
  const monitorById = new Map<number, { status?: string; progressPercent?: number }>();
  for (const student of overview.value?.students ?? []) {
    if (student.studentId !== undefined) {
      monitorById.set(student.studentId, {
        status: student.status,
        progressPercent: student.progressPercent,
      });
    }
  }
  const rows: RosterRow[] = (roster.value ?? [])
    .filter((s): s is ClassStudentItem & { userId: number } => s.userId !== undefined)
    .map((s) => ({
      userId: s.userId,
      username: s.username,
      name: s.name,
      joinedTime: s.joinedTime,
      monitorStatus: monitorById.get(s.userId)?.status,
      progressPercent: monitorById.get(s.userId)?.progressPercent,
    }));
  // 班级之外、但已进入考试的学生也要出现——否则名单会漏人，教师看不到实考者
  const known = new Set(rows.map((r) => r.userId));
  for (const student of overview.value?.students ?? []) {
    if (student.studentId !== undefined && !known.has(student.studentId)) {
      rows.push({
        userId: student.studentId,
        name: student.studentName,
        monitorStatus: student.status,
        progressPercent: student.progressPercent,
      });
    }
  }
  return rows;
});

const monitorStatusColor = 'blue';

const rosterColumns: TableColumnsType = [
  { title: '用户 ID', key: 'userId', dataIndex: 'userId', width: 90 },
  { title: '用户名', key: 'username', dataIndex: 'username', width: 140 },
  { title: '姓名', key: 'name', dataIndex: 'name', width: 140 },
  { title: '入班时间', key: 'enteredTime', width: 170 },
  { title: '考试状态（后端）', key: 'monitorStatus', width: 150 },
  { title: '答题进度', key: 'progress', width: 110 },
];

// ==================== 行为日志 ====================

type LogMode = 'student' | 'all';

const logMode = ref<LogMode>('student');
const logStudentId = ref<number | undefined>(undefined);
const logEventType = ref('');
const logSeverity = ref<number | undefined>(undefined);
const logPage = ref(1);
const logPageSize = ref(20);

const severityOptions = [
  { value: SEVERITY_LEVEL.LOW, label: '低' },
  { value: SEVERITY_LEVEL.MEDIUM, label: '中' },
  { value: SEVERITY_LEVEL.HIGH, label: '高' },
];

/** 候选学生：班级名单 ∪ 已进入考试的学生（都来自后端，不硬编码）。 */
const logStudentOptions = computed(() =>
  rosterRows.value.map((row) => ({
    value: row.userId,
    label: `${row.name || row.username || `用户 #${row.userId}`}（${row.userId}）`,
  }))
);

const timelineQuery = useQuery({
  queryKey: computed(
    () => ['exam', examId.value, 'behavior-timeline', logStudentId.value ?? 0] as const
  ),
  queryFn: () =>
    unwrap<BehaviorTimelineResponse>(
      behaviorTimelineContract({
        client,
        throwOnError: true,
        path: { examId: examId.value },
        query: { studentId: logStudentId.value as number },
      })
    ),
  enabled: computed(
    () =>
      logMode.value === 'student' &&
      logStudentId.value !== undefined &&
      activeTab.value === 'behavior'
  ),
  retry: 0,
});

const behaviorPageQuery = useQuery({
  queryKey: computed(
    () =>
      [
        'exam',
        examId.value,
        'behavior-page',
        logPage.value,
        logPageSize.value,
        logEventType.value,
        logSeverity.value ?? 'all',
      ] as const
  ),
  queryFn: () =>
    unwrap<BehaviorLogPageResponse>(
      behaviorPageContract({
        client,
        throwOnError: true,
        path: { examId: examId.value },
        query: {
          page: logPage.value,
          size: logPageSize.value,
          ...(logEventType.value.trim() ? { eventType: logEventType.value.trim() } : {}),
          ...(logSeverity.value !== undefined ? { severity: logSeverity.value } : {}),
        },
      })
    ),
  enabled: computed(() => logMode.value === 'all' && activeTab.value === 'behavior'),
  retry: 0,
});

const logRows = computed<BehaviorLogItem[] | null>(() =>
  logMode.value === 'student'
    ? (timelineQuery.data.value?.items ?? null)
    : (behaviorPageQuery.data.value?.items ?? null)
);

const logStats = computed(() => {
  if (logMode.value !== 'student') return null;
  const data = timelineQuery.data.value;
  if (!data) return null;
  return { low: data.lowCount, medium: data.mediumCount, high: data.highCount };
});

const logTotal = computed(() =>
  logMode.value === 'student'
    ? (timelineQuery.data.value?.total ?? 0)
    : (behaviorPageQuery.data.value?.total ?? 0)
);

const logFetching = computed(() =>
  logMode.value === 'student' ? timelineQuery.isFetching.value : behaviorPageQuery.isFetching.value
);

const logError = computed(() => {
  const error =
    logMode.value === 'student' ? timelineQuery.error.value : behaviorPageQuery.error.value;
  if (!error) return null;
  return error instanceof Error ? error.message : '行为日志获取失败';
});

/** 监考面板点「行为轨迹」→ 选中该生并跳到行为日志页（spec「查看异常详情」跳转）。 */
function openStudentLog(student: { studentId?: number }): void {
  if (student.studentId === undefined) return;
  logStudentId.value = student.studentId;
  logMode.value = 'student';
  activeTab.value = 'behavior';
}

// 换考试时清空与旧考试绑定的选择，避免把上一场的学生 id 发给新考试
watch(examId, () => {
  logStudentId.value = undefined;
  logPage.value = 1;
  activeTab.value = 'overview';
});

watch(logPage, () => {
  if (logMode.value !== 'all') logPage.value = 1;
});
</script>

<style scoped>
.mb-1 {
  margin-bottom: 0.25rem;
}
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
.mt-3 {
  margin-top: 0.75rem;
}
.mt-4 {
  margin-top: 1rem;
}
.flex {
  display: flex;
}
.justify-end {
  justify-content: flex-end;
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
</style>
