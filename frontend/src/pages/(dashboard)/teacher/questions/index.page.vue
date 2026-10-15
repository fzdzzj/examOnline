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
      <Button
        :disabled="selectedIds.length === 0"
        data-test="batch-tags-button"
        @click="batchTagsOpen = true"
      >
        批量标签（{{ selectedIds.length }}）
      </Button>
      <Popconfirm
        title="确认删除选中的题目？"
        :disabled="selectedIds.length === 0"
        @confirm="onBatchDelete"
      >
        <Button
          danger
          :loading="batchDeleting"
          :disabled="batchDeleting || selectedIds.length === 0"
        >
          批量删除（{{ selectedIds.length }}）
        </Button>
      </Popconfirm>
      <Button :loading="importing" @click="onChooseFile">导入</Button>
      <Button :loading="exporting" @click="onExport">导出</Button>
      <Button :loading="templateLoading" @click="onDownloadTemplate">下载模板</Button>
      <Button @click="onCheckDuplicate">题目查重</Button>
      <Button type="primary" @click="onCreate">新建题目</Button>
      <input
        ref="fileInput"
        type="file"
        accept=".xlsx"
        class="hidden-file"
        @change="onFileChange"
      />
    </div>

    <!-- U-1 三态分离（harden-data-sanitization-and-ui-states M-03）：查询失败显性呈现，
         主数据表格隐藏——系统故障不得伪装成「题库为空」的业务空态 -->
    <Alert
      v-if="questionsErrorText"
      type="error"
      show-icon
      :message="questionsErrorText"
      data-test="questions-error"
      class="mb-3"
    />

    <Table
      v-else
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
          <Button type="link" size="small" data-test="copy-question" @click="onCopy(record)">
            复制
          </Button>
          <Popconfirm title="确认删除该题目？" @confirm="onDelete(record)">
            <Button type="link" size="small" danger>删除</Button>
          </Popconfirm>
        </template>
      </template>
    </Table>

    <!-- 软删除语义说明：列表数据完全来自后端分页接口；已删题目被后端逻辑删除过滤，
         不会出现在本列表（前端不自行发明过滤规则，也不做「已删假可用」展示）。 -->
    <QuestionForm
      v-model:open="formOpen"
      :question="editing"
      :copy-from="copySource"
      @saved="invalidateQuestions"
    />

    <!-- 批量打标/解绑弹窗（add-batch-tagging-and-score-balancing） -->
    <BatchTagModal
      :open="batchTagsOpen"
      :question-ids="selectedIds"
      :tags="tags ?? []"
      :submitting="batchTagsSubmitting"
      @close="batchTagsOpen = false"
      @submit="onBatchTags"
    />

    <QuestionImportModal
      :open="importResultOpen"
      :result="importResult"
      @close="importResultOpen = false"
    />

    <!-- 题目查重快捷入口（add-question-duplicate-detection）：粘贴题干主动排查重复 -->
    <Modal
      v-model:open="duplicateModalOpen"
      :title="'题目查重'"
      :mask-closable="false"
      :footer="null"
      width="640px"
    >
      <div class="flex items-start gap-2">
        <Textarea
          v-model:value="duplicateStem"
          :rows="3"
          :maxlength="5000"
          placeholder="粘贴一段题干文本进行查重（至少 2 个有效字符）"
          class="flex-1"
        />
        <Button
          type="primary"
          :loading="duplicateChecking"
          :disabled="(duplicateStem ?? '').trim().length < 2"
          @click="onCheckDuplicateSubmit"
        >
          查重
        </Button>
      </div>
      <Alert
        v-if="duplicateQuickHits.length > 0"
        type="warning"
        show-icon
        data-test="quick-duplicate-warning"
        class="mt-3"
        :message="`命中 ${duplicateQuickHits.length} 道相似题目（最高相似度 ${Math.round(duplicateQuickMax * 100)}%）`"
      />
      <div class="mt-3">
        <div
          v-for="item in duplicateQuickHits"
          :key="item.questionId"
          data-test="quick-duplicate-item"
          class="mb-1 rounded border border-amber-200 bg-amber-50 px-2 py-1"
        >
          <div class="flex items-center gap-2">
            <span class="text-xs">[{{ typeLabelOf(item.type) }}]</span>
            <span class="truncate flex-1 text-xs text-gray-700">{{ item.content }}</span>
            <Tag color="warning">{{ Math.round((item.similarity ?? 0) * 100) }}%</Tag>
          </div>
          <div class="text-xs text-gray-400">默认分：{{ item.score ?? '—' }}</div>
        </div>
        <div
          v-if="duplicateChecked && duplicateQuickHits.length === 0"
          data-test="quick-duplicate-empty"
          class="text-gray-400"
        >
          未检测到相似题目
        </div>
      </div>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import {
  Alert,
  Button,
  Input,
  Modal,
  Popconfirm,
  Select,
  Table,
  Tag,
  Textarea,
  message,
  type TableColumnsType,
} from 'ant-design-vue';
import dayjs from 'dayjs';
import { computed, reactive, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';

import QuestionForm from '@/components/question/QuestionForm.vue';
import QuestionImportModal from '@/components/question/QuestionImportModal.vue';
import BatchTagModal from '@/components/question/BatchTagModal.vue';
import {
  batchDelete,
  batchTags,
  checkDuplicate,
  delete_ as deleteQuestion,
  importQuestions,
  list as listTags,
  page,
  type QuestionDuplicateItemResponse,
  type QuestionImportResult,
  type QuestionResponse,
  type TagResponse,
} from '@/api/axios';
import { apiClient, client, unwrap } from '@/api/apiClient';
import { queryClient } from '@/api/queryClient';
import { browserSaveFile, downloadExport } from '@/utils/exportDownload';
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

const { data, isFetching, error } = useQuery({
  queryKey: computed(() => ['questions', query.value]),
  queryFn: () => unwrap(page({ client, throwOnError: true, query: query.value })),
});

// U-1 三态分离（harden M-03）：查询失败以 Alert 显性呈现后端 message，主表格隐藏
const questionsErrorText = computed<string | null>(() => {
  const caught = error?.value;
  if (!caught) return null;
  return caught instanceof Error ? caught.message : String(caught);
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

/** 批量删除在途状态（harden-data-sanitization-and-ui-states M-04）：
 *  绑定按钮 loading/disabled，兼作 onBatchDelete 入口防抖锁——在途期间拦截并发重复触发。 */
const batchDeleting = ref(false);

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
/** 复制另存源题（存递给 QuestionForm 的 copyFrom）；与 editing 互斥二选一 */
const copySource = ref<QuestionResponse | null>(null);

function onCreate(): void {
  editing.value = null;
  copySource.value = null;
  formOpen.value = true;
}

function onEdit(record: QuestionResponse): void {
  editing.value = record;
  copySource.value = null;
  formOpen.value = true;
}

/** 题目快速复制另存：以源题开「复制为新题」表单（深拷贝回填由 QuestionForm 完成）。 */
function onCopy(record: QuestionResponse): void {
  editing.value = null;
  copySource.value = record;
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
  // M-04 入口防抖锁：请求在途未决期间，所有并发/重复触发一律拦截（finally 复位）
  if (batchDeleting.value) return;
  const ids = [...selectedIds.value];
  if (ids.length === 0) return;
  batchDeleting.value = true;
  try {
    // 单次批量调用 + 逐题结果信封：部分成功不伪装成全部成功（失败项逐条 reason 透出）
    const result = await unwrap(batchDelete({ client, throwOnError: true, body: { ids } }));
    const okCount = result?.succeeded?.length ?? 0;
    const failedItems = result?.failed ?? [];
    if (failedItems.length === 0) {
      message.success(`已删除 ${okCount} 道题目`);
    } else {
      message.warning(`删除完成：成功 ${okCount} 题，失败 ${failedItems.length} 题`);
      for (const item of failedItems) {
        message.error(`题目 ${item.id}：${item.reason ?? '删除失败，请稍后重试'}`);
      }
    }
    selectedIds.value = [];
    invalidateQuestions();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '删除失败，请稍后重试');
  } finally {
    batchDeleting.value = false;
  }
}

// ==================== 批量打标/解绑（add-batch-tagging-and-score-balancing） ====================

const batchTagsOpen = ref(false);
const batchTagsSubmitting = ref(false);

async function onBatchTags(payload: {
  questionIds: number[];
  tagIds: number[];
  action: string;
}): Promise<void> {
  batchTagsSubmitting.value = true;
  try {
    const result = await unwrap(
      batchTags({
        client,
        throwOnError: true,
        body: {
          questionIds: payload.questionIds,
          tagIds: payload.tagIds,
          action: payload.action,
        },
      })
    );
    const okCount = result?.succeeded?.length ?? 0;
    const failedItems = result?.failed ?? [];
    if (failedItems.length === 0) {
      message.success(
        payload.action === 'BIND'
          ? `已为 ${okCount} 题打上所选标签`
          : `已从 ${okCount} 题移除所选标签`
      );
    } else {
      message.warning(`批量标签完成：成功 ${okCount} 题，失败 ${failedItems.length} 题`);
      for (const item of failedItems) {
        message.error(`题目 ${item.id}：${item.reason ?? '操作失败，请稍后重试'}`);
      }
    }
    batchTagsOpen.value = false;
    selectedIds.value = [];
    invalidateQuestions();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '批量标签失败，请稍后重试');
  } finally {
    batchTagsSubmitting.value = false;
  }
}

function formatTime(value?: string | null): string {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—';
}

// ==================== 批量导入 / 导出 / 下载模板（add-question-import-export） ====================

const fileInput = ref<HTMLInputElement | null>(null);
const importing = ref(false);
const exporting = ref(false);
const templateLoading = ref(false);
const importResultOpen = ref(false);
const importResult = ref<QuestionImportResult | null>(null);

function onChooseFile(): void {
  fileInput.value?.click();
}

async function onFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  // 无论成败都清空 input，保证连续选择同一文件也能再次触发 change
  input.value = '';
  if (!file) return;
  if (!/\.xlsx$/i.test(file.name)) {
    message.error('仅支持 .xlsx 格式的题库文件');
    return;
  }
  importing.value = true;
  try {
    // SDK importQuestions：multipart/form-data（formDataBodySerializer + Content-Type:null），对齐图片上传函数形态
    const result = await unwrap<QuestionImportResult>(
      importQuestions({ client, throwOnError: true, body: { file } })
    );
    importResult.value = result ?? { imported: 0, failed: 0, errors: [] };
    importResultOpen.value = true;
    if ((result?.imported ?? 0) > 0) {
      invalidateQuestions();
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '导入失败，请稍后重试');
  } finally {
    importing.value = false;
  }
}

/** 导出：后端 SXSSF 流式生成 xlsx，前端只触发下载（复用成绩导出同一工具）。 */
async function onExport(): Promise<void> {
  exporting.value = true;
  try {
    const fileName = await downloadExport(
      { http: apiClient, saveFile: browserSaveFile },
      '/api/questions/export',
      '题库导出.xlsx'
    );
    message.success(`已导出：${fileName}`);
  } catch (error) {
    message.error(error instanceof Error ? error.message : '导出失败，请稍后重试');
  } finally {
    exporting.value = false;
  }
}

/** 下载导入模板（复用成绩导出同一下载工具）。 */
async function onDownloadTemplate(): Promise<void> {
  templateLoading.value = true;
  try {
    const fileName = await downloadExport(
      { http: apiClient, saveFile: browserSaveFile },
      '/api/questions/import-template',
      '题库导入模板.xlsx'
    );
    message.success(`已下载：${fileName}`);
  } catch (error) {
    message.error(error instanceof Error ? error.message : '模板下载失败，请稍后重试');
  } finally {
    templateLoading.value = false;
  }
}

// ==================== 题目查重快捷入口（add-question-duplicate-detection） ====================

const duplicateModalOpen = ref(false);
const duplicateStem = ref('');
const duplicateQuickHits = ref<QuestionDuplicateItemResponse[]>([]);
const duplicateChecking = ref(false);
const duplicateChecked = ref(false);

const duplicateQuickMax = computed(() => {
  let max = 0;
  for (const item of duplicateQuickHits.value) {
    const sim = item.similarity ?? 0;
    if (sim > max) max = sim;
  }
  return max;
});

function onCheckDuplicate(): void {
  duplicateModalOpen.value = true;
  duplicateQuickHits.value = [];
  duplicateChecked.value = false;
  duplicateStem.value = '';
}

async function onCheckDuplicateSubmit(): Promise<void> {
  const stem = duplicateStem.value.trim();
  if (stem.length < 2) return;
  duplicateChecking.value = true;
  try {
    const hits = await unwrap<QuestionDuplicateItemResponse[]>(
      checkDuplicate({ client, throwOnError: true, query: { stem } })
    );
    duplicateQuickHits.value = hits ?? [];
    duplicateChecked.value = true;
  } catch (error) {
    message.error(error instanceof Error ? error.message : '查重失败，请稍后重试');
    duplicateChecked.value = true;
  } finally {
    duplicateChecking.value = false;
  }
}
</script>

<style scoped>
.hidden-file {
  display: none;
}
</style>
