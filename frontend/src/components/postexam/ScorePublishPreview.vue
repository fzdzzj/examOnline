<template>
  <div>
    <div class="preview-summary">
      <span>已汇总 {{ preview.summarizedCount ?? 0 }} 人</span>
      <span v-if="(preview.partialGradedCount ?? 0) > 0" class="warn">
        其中 {{ preview.partialGradedCount }} 人仅部分批改（客观题已判、主观题未批完）
      </span>
      <span v-else class="ok">全部批改完成</span>
    </div>

    <Table
      :columns="columns"
      :data-source="items"
      :pagination="{ pageSize: 10, showSizeChanger: false }"
      :row-key="(row: ScoreItem) => row.studentId as number"
      size="small"
      bordered
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'total'">
          <span class="total">{{ (record as ScoreItem).totalScore ?? '-' }}</span>
          <Tag v-if="(record as ScoreItem).partialGraded === 1" color="warning">部分批改</Tag>
        </template>
        <template v-else-if="column.key === 'personal'">
          <!-- 个人成绩单：同样走后端导出（xlsx / pdf 两种格式由后端 format 参数决定） -->
          <Space>
            <Button
              size="small"
              type="link"
              :loading="personalLoadingId === (record as ScoreItem).studentId"
              @click="onExportPersonal(record as ScoreItem, 'xlsx')"
            >
              Excel
            </Button>
            <Button
              size="small"
              type="link"
              :loading="personalLoadingId === (record as ScoreItem).studentId"
              @click="onExportPersonal(record as ScoreItem, 'pdf')"
            >
              PDF
            </Button>
          </Space>
        </template>
      </template>
    </Table>

    <Alert
      v-if="errorMessage"
      type="error"
      show-icon
      class="mt-2"
      :message="`个人成绩单导出失败：${errorMessage}`"
      closable
      @close="errorMessage = null"
    />
  </div>
</template>

<script setup lang="ts">
import { Alert, Button, Space, Table, Tag, message, type TableColumnsType } from 'ant-design-vue';
import { computed, ref } from 'vue';

import { apiClient } from '@/api/apiClient';
import type { ScoreItem, ScorePreviewResponse } from '@/api/axios';
import { browserSaveFile, downloadExport } from '@/utils/exportDownload';

/**
 * 发布前预览（GET /api/exams/{examId}/scores/publish-preview）。
 * 展示后端已汇总的成绩明细与排名；「部分批改」标记来自后端 partialGraded 字段，
 * 前端不自行推算批改完成度。
 */

const props = defineProps<{
  examId: number;
  examTitle?: string;
  preview: ScorePreviewResponse;
}>();

const columns: TableColumnsType = [
  { title: '排名', key: 'rank', dataIndex: 'rank', width: 70 },
  { title: '学生', key: 'studentName', dataIndex: 'studentName', width: 120 },
  { title: '客观题', key: 'objectiveScore', dataIndex: 'objectiveScore', width: 90 },
  { title: '主观题', key: 'subjectiveScore', dataIndex: 'subjectiveScore', width: 90 },
  { title: '总分', key: 'total', width: 130 },
  { title: '个人成绩单', key: 'personal', width: 140 },
];

const items = computed<ScoreItem[]>(() => props.preview.items ?? []);

const personalLoadingId = ref<number | null>(null);
const errorMessage = ref<string | null>(null);

async function onExportPersonal(row: ScoreItem, format: 'xlsx' | 'pdf'): Promise<void> {
  if (row.studentId === undefined) return;
  const studentId = row.studentId;
  personalLoadingId.value = studentId;
  errorMessage.value = null;
  try {
    const fileName = await downloadExport(
      { http: apiClient, saveFile: browserSaveFile },
      `/api/exams/${props.examId}/scores/export/personal/${studentId}?format=${format}`,
      `${props.examTitle ?? `考试${props.examId}`}-${row.studentName ?? studentId}-个人成绩单.${
        format === 'pdf' ? 'pdf' : 'xlsx'
      }`
    );
    message.success(`已下载：${fileName}`);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '导出失败，请稍后重试';
  } finally {
    personalLoadingId.value = null;
  }
}
</script>

<style scoped>
.preview-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 8px;
  font-size: 13px;
  color: #555;
}
.warn {
  color: #d46b08;
}
.ok {
  color: #389e0d;
}
.total {
  font-weight: 600;
  margin-right: 6px;
}
.mt-2 {
  margin-top: 8px;
}
</style>
