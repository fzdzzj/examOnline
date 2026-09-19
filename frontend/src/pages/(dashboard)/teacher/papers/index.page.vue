<template>
  <div>
    <div class="mb-3 flex items-center gap-2">
      <div class="flex-1 text-gray-500">
        教师仅可见自己的试卷；已锁定试卷（生成过快照）不可再编辑或删除。
      </div>
      <Button type="primary" @click="onCreate">新建试卷</Button>
    </div>

    <Table
      :columns="columns"
      :data-source="pagedRows"
      :loading="isFetching"
      :pagination="clientPagination"
      :row-key="(row: PaperResponse) => row.id as number"
      size="middle"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'title'">
          <a @click="openPaper(record)">{{ record.title }}</a>
        </template>
        <template v-else-if="column.key === 'status'">
          <Tag :color="record.status === PAPER_STATUS_LOCKED ? 'gold' : 'default'">
            {{ paperStatusLabel(record.status) }}
          </Tag>
        </template>
        <template v-else-if="column.key === 'createdTime'">
          {{ formatTime(record.createdTime) }}
        </template>
        <template v-else-if="column.key === 'actions'">
          <Button type="link" size="small" @click="openPaper(record)">
            {{ record.status === PAPER_STATUS_LOCKED ? '查看' : '组卷' }}
          </Button>
          <Popconfirm
            v-if="record.status !== PAPER_STATUS_LOCKED"
            title="删除试卷会同时移除全部题目关联，确认删除？"
            @confirm="onDelete(record)"
          >
            <Button type="link" size="small" danger>删除</Button>
          </Popconfirm>
        </template>
      </template>
    </Table>
  </div>
</template>

<script setup lang="ts">
import { Button, Popconfirm, Table, Tag, message, type TableColumnsType } from 'ant-design-vue';
import dayjs from 'dayjs';
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useQuery } from '@tanstack/vue-query';

import { delete1, page1, type PaperResponse } from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { queryClient } from '@/api/queryClient';
import { PAPER_STATUS_LOCKED, paperStatusLabel } from '@/utils/questionTypes';

const router = useRouter();

const columns: TableColumnsType = [
  { title: '标题', key: 'title', ellipsis: true },
  { title: '题数', key: 'questionCount', dataIndex: 'questionCount', width: 80 },
  { title: '总分', key: 'totalScore', dataIndex: 'totalScore', width: 90 },
  { title: '状态', key: 'status', width: 90 },
  { title: '创建时间', key: 'createdTime', width: 160 },
  { title: '操作', key: 'actions', width: 140 },
];

// 契约核实：GET /api/papers 返回裸 List<PaperResponse>（无 total），
// 后端单页 size 上限 100 —— 一次取 100 条后在本地分页展示，不伪造总数字段。
const FETCH_SIZE = 100;
const clientPage = ref(1);

const { data, isFetching } = useQuery({
  queryKey: ['papers', 1, FETCH_SIZE] as const,
  queryFn: () =>
    unwrap<PaperResponse[]>(
      page1({ client, throwOnError: true, query: { page: 1, size: FETCH_SIZE } })
    ),
});

const rows = computed(() => data.value ?? []);

const pagedRows = computed(() => {
  const start = (clientPage.value - 1) * FETCH_SIZE;
  return rows.value.slice(start, start + FETCH_SIZE);
});

const clientPagination = computed(() => ({
  current: clientPage.value,
  pageSize: FETCH_SIZE,
  total: rows.value.length,
  showSizeChanger: false,
  onChange: (page: number) => {
    clientPage.value = page;
  },
}));

function onCreate(): void {
  void router.push('/teacher/papers/create');
}

function openPaper(record: PaperResponse): void {
  if (record.id !== undefined) {
    void router.push(`/teacher/papers/${record.id}`);
  }
}

async function onDelete(record: PaperResponse): Promise<void> {
  if (record.id === undefined) return;
  try {
    await unwrap(delete1({ client, throwOnError: true, path: { id: record.id } }));
    message.success('试卷已删除');
    void queryClient.invalidateQueries({ queryKey: ['papers'] });
  } catch (error) {
    message.error(error instanceof Error ? error.message : '删除失败，请稍后重试');
  }
}

function formatTime(value?: string | null): string {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—';
}
</script>
