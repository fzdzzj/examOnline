<template>
  <div class="leaderboard-container mt-4" data-test="leaderboard-panel">
    <Spin :spinning="!!isLoading">
      <!-- 404 语义 / 暂无本人成绩记录：空态，不渲染骨架 -->
      <Empty
        v-if="isNotFound"
        description="暂无本人成绩记录"
        data-test="leaderboard-empty"
        class="my-4"
      />

      <!-- 其他加载错误 -->
      <Alert
        v-else-if="otherErrorMessage"
        type="error"
        show-icon
        :message="otherErrorMessage"
        data-test="leaderboard-error"
        class="my-2"
      />

      <!-- 数据为空态 -->
      <Empty
        v-else-if="!activeData || (tableRows.length === 0 && !isLoading)"
        description="暂无榜单数据"
        data-test="leaderboard-empty"
        class="my-4"
      />

      <!-- 榜单数据渲染 -->
      <div v-else class="p-3 bg-white rounded border border-gray-200">
        <div class="flex items-center justify-between mb-3">
          <div class="font-semibold text-gray-800 flex items-center gap-2">
            <span>班级匿名榜单</span>
            <span class="text-xs text-gray-400 font-normal">（前 10 名及本人位置）</span>
          </div>
          <span class="text-xs text-gray-500">保留姓氏脱敏展示 · 不公开他人学号</span>
        </div>

        <Table
          :columns="columns"
          :data-source="tableRows"
          :pagination="false"
          size="small"
          bordered
          :row-class-name="getRowClassName"
          data-test="leaderboard-table"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'rank'">
              <span class="font-semibold text-gray-700">
                第 {{ (record as LeaderboardRowView).rank }} 名
              </span>
            </template>
            <template v-else-if="column.key === 'displayName'">
              <div class="flex items-center gap-1">
                <span
                  :class="
                    (record as LeaderboardRowView).isMe
                      ? 'text-blue-600 font-medium'
                      : 'text-gray-800'
                  "
                >
                  {{ (record as LeaderboardRowView).displayName }}
                </span>
                <Tag v-if="(record as LeaderboardRowView).isMe" color="blue" size="small">
                  {{ (record as LeaderboardRowView).isBottomMyRow ? '本人 (榜尾)' : '本人' }}
                </Tag>
              </div>
            </template>
            <template v-else-if="column.key === 'totalScore'">
              <span
                class="font-semibold"
                :class="(record as LeaderboardRowView).isMe ? 'text-blue-600' : 'text-gray-700'"
              >
                {{ (record as LeaderboardRowView).totalScore ?? '-' }}
              </span>
            </template>
          </template>
        </Table>
      </div>
    </Spin>
  </div>
</template>

<script setup lang="ts">
import { Alert, Empty, Spin, Table, Tag, type TableColumnsType } from 'ant-design-vue';
import { computed } from 'vue';
import { useQuery } from '@tanstack/vue-query';

import { client, unwrap } from '@/api/apiClient';
import { leaderboard } from '@/api/axios';
import type { ScoreLeaderboardResponse } from '@/api/axios';
import { ApiError } from '@/api/types';

export interface LeaderboardRowView {
  key: string;
  rank?: number;
  displayName?: string;
  totalScore?: number;
  isMe?: boolean;
  isBottomMyRow?: boolean;
}

const props = withDefaults(
  defineProps<{
    examId?: number;
    data?: ScoreLeaderboardResponse | null;
    loading?: boolean;
    error?: unknown;
  }>(),
  {
    examId: undefined,
    data: undefined,
    loading: undefined,
    error: undefined,
  }
);

const columns: TableColumnsType = [
  { title: '名次', dataIndex: 'rank', key: 'rank', width: 90, align: 'center' },
  { title: '姓名', dataIndex: 'displayName', key: 'displayName', width: 140 },
  { title: '总分', dataIndex: 'totalScore', key: 'totalScore', width: 100, align: 'right' },
];

const queryEnabled = computed(
  () => props.data === undefined && props.examId !== undefined && typeof leaderboard === 'function'
);

const {
  data: fetchedData,
  isFetching,
  error: queryError,
} = useQuery({
  queryKey: computed(() => ['exam-leaderboard', props.examId] as const),
  queryFn: async () => {
    if (typeof leaderboard !== 'function') return null;
    return (
      (await unwrap<ScoreLeaderboardResponse>(
        leaderboard({
          client,
          throwOnError: true,
          path: { examId: props.examId as number },
        })
      )) ?? null
    );
  },
  enabled: queryEnabled,
  retry: false,
});

const isLoading = computed(() => props.loading ?? (queryEnabled.value && isFetching.value));
const rawError = computed(() => props.error ?? queryError.value);

const isNotFound = computed(() => {
  const err = rawError.value;
  if (!err) return false;
  if (typeof err === 'string') {
    return err.includes('404') || err.includes('暂无本人成绩记录');
  }
  if (err instanceof ApiError) {
    return err.code === 404 || err.status === 404 || err.message.includes('暂无本人成绩记录');
  }
  if (err instanceof Error) {
    return err.message.includes('404') || err.message.includes('暂无本人成绩记录');
  }
  return false;
});

const otherErrorMessage = computed(() => {
  if (isNotFound.value) return null;
  const err = rawError.value;
  if (!err) return null;
  if (typeof err === 'string') return err;
  return err instanceof Error ? err.message : '加载榜单失败';
});

const activeData = computed<ScoreLeaderboardResponse | null>(() => {
  if (props.data !== undefined) return props.data;
  return fetchedData.value ?? null;
});

const tableRows = computed<LeaderboardRowView[]>(() => {
  if (!activeData.value) return [];
  const rows: LeaderboardRowView[] = (activeData.value.top ?? []).map((item, idx) => ({
    key: `top-${idx}-${item.rank}-${item.displayName}`,
    rank: item.rank,
    displayName: item.displayName,
    totalScore: item.totalScore,
    isMe: item.isMe,
    isBottomMyRow: false,
  }));

  if (activeData.value.myRow) {
    rows.push({
      key: `my-row-${activeData.value.myRow.rank}`,
      rank: activeData.value.myRow.rank,
      displayName: '本人',
      totalScore: activeData.value.myRow.totalScore,
      isMe: true,
      isBottomMyRow: true,
    });
  }

  return rows;
});

function getRowClassName(record: LeaderboardRowView) {
  if (record.isBottomMyRow) {
    return 'leaderboard-my-row leaderboard-me-row bg-blue-50/80 font-medium';
  }
  if (record.isMe) {
    return 'leaderboard-me-row bg-blue-50 font-medium';
  }
  return '';
}
</script>

<style scoped>
:deep(.leaderboard-me-row) {
  background-color: #eff6ff !important;
}
:deep(.leaderboard-my-row) {
  border-top: 2px dashed #bfdbfe;
}
</style>
