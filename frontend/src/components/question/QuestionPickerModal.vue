<template>
  <Modal :open="open" title="从题库选题入卷" width="860px" :mask-closable="false" @cancel="onClose">
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <Select
        v-model:value="filterType"
        :options="QUESTION_TYPE_OPTIONS"
        allow-clear
        placeholder="题型"
        class="w-32"
      />
      <Select
        v-model:value="filterTagId"
        :options="tagOptions"
        allow-clear
        placeholder="标签"
        class="w-40"
      />
      <Input
        v-model:value="filterKeyword"
        allow-clear
        placeholder="题干关键词"
        class="w-52"
        @press-enter="resetToFirstPage"
      />
      <Button type="primary" @click="resetToFirstPage">查询</Button>
    </div>

    <Table
      :columns="columns"
      :data-source="rows"
      :loading="isFetching"
      :pagination="pagination"
      :row-key="(row: QuestionResponse) => row.id as number"
      :row-selection="rowSelection"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'content'">
          <span class="line-clamp-2">{{ record.content }}</span>
        </template>
        <template v-else-if="column.key === 'type'">
          <Tag>{{ typeLabelOf(record.type) }}</Tag>
        </template>
        <template v-else-if="column.key === 'score'">{{ record.score ?? '—' }}</template>
        <template v-else-if="column.key === 'difficulty'">
          {{ difficultyLabelOf(record.difficulty) }}
        </template>
      </template>
    </Table>

    <div class="mt-3 flex justify-end gap-2">
      <Button @click="onClose">取消</Button>
      <Button type="primary" :disabled="selectedRows.length === 0" @click="onConfirm">
        加入试卷（{{ selectedRows.length }}）
      </Button>
    </div>
  </Modal>
</template>

<script setup lang="ts">
/**
 * 选题器：分页 + 筛选 + 勾选，供手动组卷调用。
 * 只负责挑选题目；入卷（含分值覆盖）由组卷工作台在 picked 事件后调契约完成。
 */
import { Button, Input, Modal, Select, Table, Tag, type TableColumnsType } from 'ant-design-vue';
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';

import { list, page, type QuestionResponse, type TagResponse } from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { buildQuestionPageQuery } from '@/utils/questionFilters';
import { difficultyLabelOf, QUESTION_TYPE_OPTIONS, typeLabelOf } from '@/utils/questionTypes';

const props = defineProps<{
  open: boolean;
  /** 已在试卷中的题目 ID：这些行禁选（后端重复加题会报 1001） */
  excludeIds: number[];
}>();

const emit = defineEmits<{
  (event: 'picked', questions: QuestionResponse[]): void;
  (event: 'update:open', open: boolean): void;
}>();

const columns: TableColumnsType = [
  { title: '题干', key: 'content', ellipsis: true },
  { title: '题型', key: 'type', width: 80 },
  { title: '默认分', key: 'score', width: 80 },
  { title: '难度', key: 'difficulty', width: 70 },
];

const PAGE_SIZE = 20;

const filterType = ref<number | undefined>(undefined);
const filterTagId = ref<number | undefined>(undefined);
const filterKeyword = ref('');
const currentPage = ref(1);
const selectedRows = ref<QuestionResponse[]>([]);

const query = computed(() =>
  buildQuestionPageQuery({
    page: currentPage.value,
    size: PAGE_SIZE,
    type: filterType.value,
    tagId: filterTagId.value,
    keyword: filterKeyword.value,
  })
);

const { data, isFetching } = useQuery({
  queryKey: computed(() => ['questions', 'picker', query.value]),
  queryFn: () => unwrap(page({ client, throwOnError: true, query: query.value })),
});

const rows = computed(() => data.value?.list ?? []);
const total = computed(() => data.value?.total ?? 0);

const pagination = computed(() => ({
  current: currentPage.value,
  pageSize: PAGE_SIZE,
  total: total.value,
  showSizeChanger: false,
  onChange: (page: number) => {
    currentPage.value = page;
  },
}));

function resetToFirstPage(): void {
  currentPage.value = 1;
}

watch([filterType, filterTagId, filterKeyword], resetToFirstPage);

// 换页/换筛选后清空勾选，避免把不可见行悄悄带进试卷
watch(query, () => {
  selectedRows.value = [];
});

const { data: tags } = useQuery({
  queryKey: ['tags'],
  queryFn: () => unwrap<TagResponse[]>(list({ client, throwOnError: true })),
  enabled: computed(() => props.open),
});

const tagOptions = computed(() =>
  (tags.value ?? []).map((tag) => ({ value: tag.id as number, label: tag.name ?? String(tag.id) }))
);

const rowSelection = computed(() => ({
  selectedRowKeys: selectedRows.value.map((row) => row.id as number),
  onChange: (keys: Array<number | string>, rows: Array<unknown>) => {
    void keys;
    selectedRows.value = rows as QuestionResponse[];
  },
  getCheckboxProps: (record: QuestionResponse) => ({
    disabled: record.id !== undefined && props.excludeIds.includes(record.id),
  }),
}));

function onClose(): void {
  emit('update:open', false);
}

function onConfirm(): void {
  emit('picked', [...selectedRows.value]);
  selectedRows.value = [];
  emit('update:open', false);
}
</script>
