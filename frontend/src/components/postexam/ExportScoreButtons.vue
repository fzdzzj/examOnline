<template>
  <div>
    <div class="export-actions">
      <Button
        v-for="item in EXPORT_ITEMS"
        :key="item.kind"
        :loading="exportingKind === item.kind"
        :disabled="!enabled || exportingKind !== null"
        @click="onExport(item.kind)"
      >
        {{ item.label }}
      </Button>
    </div>

    <!-- 导出失败：明示原因 + 原地重试（后端导出失败不影响重试，前端不做本地兜底拼表） -->
    <Alert
      v-if="errorMessage"
      type="error"
      show-icon
      class="export-error"
      :message="`导出失败：${errorMessage}`"
      closable
      @close="errorMessage = null"
    >
      <template #description>
        <Button size="small" danger @click="retry">重试上一次导出</Button>
      </template>
    </Alert>

    <div v-if="exportingKind" class="export-progress">
      正在由后端生成文件（SXSSF 流式写），完成后自动下载……
    </div>
  </div>
</template>

<script setup lang="ts">
import { Alert, Button, message } from 'ant-design-vue';
import { ref } from 'vue';

import { apiClient } from '@/api/apiClient';
import { browserSaveFile, downloadExport } from '@/utils/exportDownload';

/**
 * 成绩导出按钮组（硬约定 4）：前端只触发后端导出 + blob 保存 + 进度/失败提示，
 * 绝不取全量数据在前端拼表（后端 SXSSFWorkbook 窗口 100 行流式写是防 OOM 的关键设计）。
 * 端点均为 GET /api/exams/{examId}/scores/export/*（@RequirePermission("exam:manage")）。
 */

export type ExportKind = 'class-sheet' | 'detail' | 'question-stats';

const props = defineProps<{
  examId: number;
  examTitle?: string;
  /** 后端状态 ≥ GRADED 才开放导出（由父级按 scoreActions 传入） */
  enabled: boolean;
}>();

const EXPORT_ITEMS: ReadonlyArray<{ kind: ExportKind; label: string; path: string }> = [
  { kind: 'class-sheet', label: '全班成绩单', path: 'class-sheet' },
  { kind: 'detail', label: '逐题得分明细', path: 'detail' },
  { kind: 'question-stats', label: '题目统计', path: 'question-stats' },
];

const exportingKind = ref<ExportKind | null>(null);
const errorMessage = ref<string | null>(null);
let lastKind: ExportKind | null = null;

function urlOf(kind: ExportKind): string {
  const item = EXPORT_ITEMS.find((i) => i.kind === kind);
  return `/api/exams/${props.examId}/scores/export/${item?.path ?? 'class-sheet'}`;
}

function fallbackNameOf(kind: ExportKind): string {
  const title = props.examTitle ? `${props.examTitle}-` : `考试${props.examId}-`;
  const suffix: Record<ExportKind, string> = {
    'class-sheet': '全班成绩单.xlsx',
    detail: '逐题得分明细.xlsx',
    'question-stats': '题目统计.xlsx',
  };
  return `${title}${suffix[kind]}`;
}

async function runExport(kind: ExportKind): Promise<void> {
  lastKind = kind;
  exportingKind.value = kind;
  errorMessage.value = null;
  try {
    const fileName = await downloadExport(
      { http: apiClient, saveFile: browserSaveFile },
      urlOf(kind),
      fallbackNameOf(kind)
    );
    message.success(`已下载：${fileName}`);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '导出失败，请稍后重试';
  } finally {
    exportingKind.value = null;
  }
}

function onExport(kind: ExportKind): void {
  void runExport(kind);
}

function retry(): void {
  if (lastKind) void runExport(lastKind);
}
</script>

<style scoped>
.export-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.export-error {
  margin-top: 8px;
}
.export-progress {
  margin-top: 8px;
  color: #666;
  font-size: 13px;
}
</style>
