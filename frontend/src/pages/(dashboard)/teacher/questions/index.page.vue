<template>
  <div>
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <Select
        v-model:value="filters.type"
        :options="QUESTION_TYPE_OPTIONS"
        allow-clear
        placeholder="题型"
        class="w-32"
      />
      <Select
        v-model:value="filters.difficulty"
        :options="DIFFICULTY_OPTIONS"
        allow-clear
        placeholder="难度"
        class="w-28"
      />
      <Select
        v-model:value="filters.tagId"
        :options="tagOptions"
        allow-clear
        placeholder="标签"
        class="w-40"
      />
      <Input
        v-model:value="keywordInput"
        allow-clear
        placeholder="题干关键词"
        class="w-56"
        @press-enter="onSearch"
      />
      <Button type="primary" @click="onSearch">查询</Button>
      <div class="flex-1" />
      <Popconfirm
        title="确认删除选中的题目？"
        :disabled="selectedIds.length === 0"
        @confirm="onBatchDelete"
      >
        <Button danger :disabled="selectedIds.length === 0">
          批量删除（{{ selectedIds.length }}）
        </Button>
      </Popconfirm>
      <Button type="primary" @click="onCreate">新建题目</Button>
    </div>

    <Table
      :columns="columns"
      :data-source="rows"
      :loading="isFetching"
      :pagination="pagination"
      :row-key="(row: QuestionResponse) => row.id as number"
      :row-selection="rowSelection"
      size="middle"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'content'">
          <span>{{ record.content }}</span>
        </template>
        <template v-else-if="column.key === 'type'">
          <Tag>{{ typeLabelOf(record.type) }}</Tag>
        </template>
        <template v-else-if="column.key === 'score'">{{ record.score ?? '—' }}</template>
        <template v-else-if="column.key === 'difficulty'">
          {{ difficultyLabelOf(record.difficulty) }}
        </template>
        <template v-else-if="column.key === 'tags'">
          <Tag v-for="tag in record.tags ?? []" :key="tag.id" color="blue">{{ tag.name }}</Tag>
          <span v-if="(record.tags ?? []).length === 0" class="text-gray-400">无</span>
        </template>
        <template v-else-if="column.key === 'updatedTime'">
          {{ formatTime(record.updatedTime) }}
        </template>
        <template v-else-if="column.key === 'actions'">
          <Button type="link" size="small" @click="onEdit(record)">编辑</Button>
          <Popconfirm title="确认删除该题目？" @confirm="onDelete(record)">
            <Button type="link" size="small" danger>删除</Button>
          </Popconfirm>
        </template>
      </template>
    </Table>

    <!-- 软删除语义说明：列表数据完全来自后端分页接口；已删题目被后端逻辑删除过滤，
         不会出现在本列表（前端不自行发明过滤规则，也不做「已删假可用」展示）。 -->
    <QuestionForm v-model:open="formOpen" :question="editing" @saved="invalidateQuestions" />
  </div>
</template>

<script setup lang="ts">
import {
  Button,
  Input,
  Popconfirm,
  Select,
  Table,
  Tag,
  message,
  type TableColumnsType,
} from 'ant-design-vue';
import dayjs from 'dayjs';
import { computed, reactive, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';

import QuestionForm from '@/components/question/QuestionForm.vue';
import {
  delete_ as deleteQuestion,
  list as listTags,
  page,
  type QuestionResponse,
  type TagResponse,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { queryClient } from '@/api/queryClient';
import { buildQuestionPageQuery } from '@/utils/questionFilters';
import {
  DIFFICULTY_OPTIONS,
  QUESTION_TYPE_OPTIONS,
  difficultyLabelOf,
  typeLabelOf,
} from '@/utils/questionTypes';

const columns: TableColumnsType = [
  { title: '题干', key: 'content', ellipsis: true },
  { title: '题型', key: 'type', width: 80 },
  { title: '默认分', key: 'score', width: 80 },
  { title: '难度', key: 'difficulty', width: 70 },
  { title: '标签', key: 'tags', width: 200 },
  { title: '更新时间', key: 'updatedTime', width: 160 },
  { title: '操作', key: 'actions', width: 120 },
];

const filters = reactive<{
  type: number | undefined;
  difficulty: number | undefined;
  tagId: number | undefined;
  keyword: string;
}>({ type: undefined, difficulty: undefined, tagId: undefined, keyword: '' });

const keywordInput = ref('');
const currentPage = ref(1);
const pageSize = ref(10);

const query = computed(() =>
  buildQuestionPageQuery({
    page: currentPage.value,
    size: pageSize.value,
    type: filters.type,
    difficulty: filters.difficulty,
    tagId: filters.tagId,
    keyword: filters.keyword,
  })
);

const { data, isFetching } = useQuery({
  queryKey: computed(() => ['questions', query.value]),
  queryFn: () => unwrap(page({ client, throwOnError: true, query: query.value })),
});

const rows = computed(() => data.value?.list ?? []);
const total = computed(() => data.value?.total ?? 0);

const pagination = computed(() => ({
  current: currentPage.value,
  pageSize: pageSize.value,
  total: total.value,
  showSizeChanger: true,
  onChange: (nextPage: number, nextSize: number) => {
    currentPage.value = nextPage;
    pageSize.value = nextSize;
  },
}));

watch([() => filters.type, () => filters.difficulty, () => filters.tagId], () => {
  currentPage.value = 1;
});

function onSearch(): void {
  filters.keyword = keywordInput.value;
  currentPage.value = 1;
}

const { data: tags } = useQuery({
  queryKey: ['tags'],
  queryFn: () => unwrap<TagResponse[]>(listTags({ client, throwOnError: true })),
});

const tagOptions = computed(() =>
  (tags.value ?? []).map((tag) => ({ value: tag.id as number, label: tag.name ?? String(tag.id) }))
);

const selectedIds = ref<number[]>([]);

const rowSelection = computed(() => ({
  selectedRowKeys: selectedIds.value,
  onChange: (keys: Array<number | string>) => {
    selectedIds.value = keys.map(Number);
  },
}));

// 换页/筛选后清空勾选，避免把不可见行悄悄删掉
watch(query, () => {
  selectedIds.value = [];
});

function invalidateQuestions(): void {
  void queryClient.invalidateQueries({ queryKey: ['questions'] });
}

const formOpen = ref(false);
const editing = ref<QuestionResponse | null>(null);

function onCreate(): void {
  editing.value = null;
  formOpen.value = true;
}

function onEdit(record: QuestionResponse): void {
  editing.value = record;
  formOpen.value = true;
}

async function onDelete(record: QuestionResponse): Promise<void> {
  if (record.id === undefined) return;
  try {
    await unwrap(deleteQuestion({ client, throwOnError: true, path: { id: record.id } }));
    message.success('题目已删除');
    invalidateQuestions();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '删除失败，请稍后重试');
  }
}

async function onBatchDelete(): Promise<void> {
  const ids = [...selectedIds.value];
  if (ids.length === 0) return;
  let okCount = 0;
  let failCount = 0;
  for (const id of ids) {
    try {
      await unwrap(deleteQuestion({ client, throwOnError: true, path: { id } }));
      okCount += 1;
    } catch {
      failCount += 1;
    }
  }
  if (failCount === 0) {
    message.success(`已删除 ${okCount} 道题目`);
  } else {
    message.warning(`删除完成：成功 ${okCount} 题，失败 ${failCount} 题`);
  }
  selectedIds.value = [];
  invalidateQuestions();
}

function formatTime(value?: string | null): string {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—';
}
</script>
