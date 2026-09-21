<template>
  <div :data-empty="isEmpty ? 'true' : 'false'">
    <Alert v-if="error" type="error" show-icon :message="error" class="mb-3" />

    <Spin v-else :spinning="!!loading">
      <Space v-if="stats" class="mb-3" wrap>
        <span class="text-sm text-gray-500">严重度分布（后端统计）：</span>
        <Tag color="default">低 {{ stats.low ?? 0 }}</Tag>
        <Tag color="orange">中 {{ stats.medium ?? 0 }}</Tag>
        <Tag color="red">高 {{ stats.high ?? 0 }}</Tag>
        <Tag color="blue">合计 {{ total ?? rows.length }}</Tag>
      </Space>

      <p v-if="isEmpty && !loading" class="mb-0 text-gray-500">
        该学生在本场考试暂无行为记录（后端返回 0 条）。
      </p>

      <Timeline v-else-if="rows.length">
        <TimelineItem v-for="log in rows" :key="log.id" :color="getSeverityDotColor(log.severity)">
          <div class="log-row" :data-severity="log.severity ?? 'unknown'">
            <Space :size="6" wrap>
              <span class="time-text">{{ formatTime(log.eventTime) }}</span>
              <b>{{ log.eventType ?? '未知事件' }}</b>
              <Tag :color="severityConfig(log).color">
                {{ severityConfig(log).label }}
              </Tag>
            </Space>
            <div v-if="describeEventData(log.eventData)" class="detail-text">
              {{ describeEventData(log.eventData) }}
            </div>
          </div>
        </TimelineItem>
      </Timeline>

      <p v-else-if="!loading" class="mb-0 text-gray-500">暂无行为日志。</p>
    </Spin>
  </div>
</template>

<script setup lang="ts">
import { Alert, Space, Spin, Tag, Timeline, TimelineItem } from 'ant-design-vue';
import dayjs from 'dayjs';
import { computed } from 'vue';

import type { BehaviorLogItem, JsonNode } from '@/api/axios';
import { getSeverityConfig, getSeverityDotColor, type SeverityConfig } from '@/constants/severity';

/**
 * 行为日志时间线（阶段 21 缺口 4）。
 *
 * 数据来自后端两个端点之一：
 * - `GET /api/exams/{examId}/behavior-logs/timeline?studentId=`（单学生完整轨迹 + 严重度分布）
 * - `GET /api/exams/{examId}/behavior-logs`（分页 + 按学生/事件类型/严重度筛选）
 *
 * ⚠️ 分级判定**不在前端**：`severity` 由各事件采集策略决定，
 * 后端还把语义名放在 `severityName`（低/中/高）。本组件优先显示 `severityName`，
 * `getSeverityConfig` 只负责上色与"后端没给名字"时的兜底；
 * 事件类型（`eventType`）也原样透出——后端注册表允许未注册类型走兜底策略，
 * 前端若自造一份枚举清单就会与它漂移。
 */

const props = defineProps<{
  logs?: BehaviorLogItem[] | null;
  /** 后端 timeline 响应给的分布；分页接口没有就不显示 */
  stats?: { low?: number; medium?: number; high?: number } | null;
  total?: number;
  loading?: boolean;
  error?: string | null;
}>();

const rows = computed<BehaviorLogItem[]>(() => props.logs ?? []);
const isEmpty = computed(() => !props.loading && rows.value.length === 0);

function severityConfig(log: BehaviorLogItem): SeverityConfig {
  const fallback = getSeverityConfig(log.severity);
  // 后端给了语义名就用后端的，颜色仍按 severity 值映射
  return { label: log.severityName ?? fallback.label, color: fallback.color };
}

function formatTime(value: string | undefined | null): string {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—';
}

/** eventData 是自由 JSON：对象展成 `k=v`，字符串直接给，其余 JSON 化。 */
function describeEventData(node: JsonNode | undefined): string {
  if (node === null || node === undefined) return '';
  if (typeof node === 'string') return node;
  if (typeof node === 'number' || typeof node === 'boolean') return String(node);
  if (Array.isArray(node)) return node.map((item) => describeEventData(item)).join(' , ');
  return Object.entries(node as Record<string, unknown>)
    .map(([key, value]) => `${key}=${describeEventData(value)}`)
    .join(' · ');
}
</script>

<style scoped>
.mb-3 {
  margin-bottom: 0.75rem;
}
.mb-0 {
  margin-bottom: 0;
}
.text-sm {
  font-size: 0.875rem;
}
.text-gray-500 {
  color: #6b7280;
}
.time-text {
  font-variant-numeric: tabular-nums;
  color: #6b7280;
}
.detail-text {
  font-size: 0.75rem;
  color: #6b7280;
  word-break: break-all;
}
</style>
