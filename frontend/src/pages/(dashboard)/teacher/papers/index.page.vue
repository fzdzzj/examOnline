<template>
  <div>
    <div class="mb-3 flex items-center gap-2">
      <div class="flex-1 text-gray-500">
        {{
          activeTab === 'template'
            ? '模板库：模板卷仅用于复制复用；点击「使用」即从模板克隆出可编辑的草稿卷，模板本身不变。'
            : '教师仅可见自己的试卷；已锁定试卷（生成过快照）不可再编辑或删除。'
        }}
      </div>
      <Button v-if="activeTab === 'paper'" type="primary" @click="onCreate">新建试卷</Button>
    </div>

    <Tabs v-model:activeKey="activeTab" class="mb-3">
      <TabPane key="paper" tab="试卷" />
      <TabPane key="template" tab="模板库" />
    </Tabs>

    <!-- U-1 三态分离（同缺考名单口径）：查询失败显性呈现，表格隐藏——失败不得落「暂无数据」空态 -->
    <Alert
      v-if="queryErrorText"
      type="error"
      show-icon
      :message="queryErrorText"
      data-test="papers-error"
      class="mb-3"
    />

    <Table
      v-else
      :columns="columns"
      :data-source="rows"
      :loading="isFetching"
      :pagination="tablePagination"
      :row-key="(row: PaperResponse) => row.id as number"
      size="middle"
      @change="onTableChange"
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
          <!-- 模板库视图：使用（从模板建卷）/ 编辑 / 删除 -->
          <template v-if="activeTab === 'template'">
            <Button
              type="link"
              size="small"
              data-test="template-use"
              @click="onUseTemplate(record)"
            >
              使用
            </Button>
            <Button type="link" size="small" data-test="template-edit" @click="openPaper(record)">
              编辑
            </Button>
            <Popconfirm
              title="删除模板会同时移除全部题目关联，确认删除？"
              @confirm="onDelete(record)"
            >
              <Button type="link" size="small" danger>删除</Button>
            </Popconfirm>
          </template>
          <!-- 普通试卷视图：组卷/查看、克隆、另存为模板、删除（既有行为零漂移） -->
          <template v-else>
            <Button type="link" size="small" @click="openPaper(record)">
              {{ record.status === PAPER_STATUS_LOCKED ? '查看' : '组卷' }}
            </Button>
            <Button type="link" size="small" data-test="paper-clone" @click="onClone(record)">
              克隆
            </Button>
            <Button
              type="link"
              size="small"
              data-test="paper-save-template"
              @click="onSaveAsTemplate(record)"
            >
              另存为模板
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
      </template>
    </Table>
  </div>
</template>

<script setup lang="ts">
import {
  Alert,
  Button,
  Popconfirm,
  TabPane,
  Table,
  Tabs,
  Tag,
  message,
  type TableColumnsType,
} from 'ant-design-vue';
import dayjs from 'dayjs';
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useQuery } from '@tanstack/vue-query';

import { clone, delete1, page1, saveAsTemplate, type PaperResponse } from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { queryClient } from '@/api/queryClient';
import { PAPER_STATUS_LOCKED, paperStatusLabel } from '@/utils/questionTypes';

const router = useRouter();

type ViewKey = 'paper' | 'template';
const activeTab = ref<ViewKey>('paper');

const columns: TableColumnsType = [
  { title: '标题', key: 'title', ellipsis: true },
  { title: '题数', key: 'questionCount', dataIndex: 'questionCount', width: 80 },
  { title: '总分', key: 'totalScore', dataIndex: 'totalScore', width: 90 },
  { title: '状态', key: 'status', width: 90 },
  { title: '创建时间', key: 'createdTime', width: 160 },
  { title: '操作', key: 'actions', width: 220 },
];

// 契约核实：GET /api/papers 返回裸 List<PaperResponse>（无 total），后端单页 size 上限 100。
// 列表按服务端分页取当页（U-2）：不再「一次取回 100 条后本地切片」——那会让第 101 份试卷
// 在列表里永远不存在。形态对齐 teacher/exams/index.page.vue（当前页进 queryKey、翻页真实请求）。
// 模板库视图（add-paper-template-clone）以 isTemplate=true 复用同一端点；默认视图不带该参数——
// 既有调用方（不传参）行为零变化（向后兼容护栏）。
const pageNum = ref(1);
const pageSize = ref(10);

const { data, error, isFetching, refetch } = useQuery({
  queryKey: computed(() => ['papers', activeTab.value, pageNum.value, pageSize.value] as const),
  queryFn: () =>
    unwrap<PaperResponse[]>(
      page1({
        client,
        throwOnError: true,
        query:
          activeTab.value === 'template'
            ? { page: pageNum.value, size: pageSize.value, isTemplate: true }
            : { page: pageNum.value, size: pageSize.value },
      })
    ),
});

const rows = computed<PaperResponse[]>(() => data.value ?? []);

// 切换视图回到第一页（模板库/试卷各自从首页看起）
watch(activeTab, () => {
  pageNum.value = 1;
});

// U-1 三态分离：查询失败显性呈现（Alert 承载后端 message），不落「暂无数据」空态
const queryErrorText = computed<string | null>(() => {
  const caught = error.value;
  if (!caught) return null;
  return caught instanceof Error ? caught.message : '试卷列表加载失败';
});

const tablePagination = computed(() => ({
  current: pageNum.value,
  pageSize: pageSize.value,
  // 信封无 total，只能做下界推断：满页即「至少还有下一页」，末页（未满页）不预留。
  // 不照抄考试页的字面 `total: pageNum * pageSize`——按 antd 的
  // calculatePage = floor((total-1)/pageSize)+1，那写法在满页时算出 1 页，下一页按钮根本点不到。
  total:
    (pageNum.value - 1) * pageSize.value +
    rows.value.length +
    (rows.value.length === pageSize.value ? 1 : 0),
  showSizeChanger: false,
}));

function onTableChange(pag: { current?: number; pageSize?: number }): void {
  if (typeof pag.current === 'number') pageNum.value = pag.current;
  if (typeof pag.pageSize === 'number') pageSize.value = pag.pageSize;
  void refetch();
}

function onCreate(): void {
  void router.push('/teacher/papers/create');
}

function openPaper(record: PaperResponse): void {
  if (record.id !== undefined) {
    void router.push(`/teacher/papers/${record.id}`);
  }
}

// 克隆（普通试卷）与「从模板建卷」（模板库使用）同名端点语义：复制为可编辑草稿，源卷/源模板零影响。
async function cloneToDraft(record: PaperResponse, successText: string): Promise<void> {
  if (record.id === undefined) return;
  try {
    const created = await unwrap<PaperResponse>(
      clone({ client, throwOnError: true, path: { id: record.id } })
    );
    message.success(successText);
    void queryClient.invalidateQueries({ queryKey: ['papers'] });
    if (created?.id !== undefined) void router.push(`/teacher/papers/${created.id}`);
  } catch (error) {
    message.error(error instanceof Error ? error.message : '操作失败，请稍后重试');
  }
}

function onClone(record: PaperResponse): void {
  void cloneToDraft(record, '已克隆试卷（副本为草稿态）');
}

function onUseTemplate(record: PaperResponse): void {
  void cloneToDraft(record, '已从模板创建新试卷');
}

async function onSaveAsTemplate(record: PaperResponse): Promise<void> {
  if (record.id === undefined) return;
  try {
    await unwrap<PaperResponse>(
      saveAsTemplate({ client, throwOnError: true, path: { id: record.id } })
    );
    message.success('已另存为模板，可在「模板库」查看');
    void queryClient.invalidateQueries({ queryKey: ['papers'] });
  } catch (error) {
    message.error(error instanceof Error ? error.message : '另存为模板失败，请稍后重试');
  }
}

async function onDelete(record: PaperResponse): Promise<void> {
  if (record.id === undefined) return;
  try {
    await unwrap(delete1({ client, throwOnError: true, path: { id: record.id } }));
    message.success(activeTab.value === 'template' ? '模板已删除' : '试卷已删除');
    void queryClient.invalidateQueries({ queryKey: ['papers'] });
  } catch (error) {
    message.error(error instanceof Error ? error.message : '删除失败，请稍后重试');
  }
}

function formatTime(value?: string | null): string {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—';
}
</script>
