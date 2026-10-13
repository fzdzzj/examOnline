<template>
  <Modal :open="open" :title="`导入结果`" :footer="null" width="620" @cancel="emit('close')">
    <div v-if="result">
      <p class="summary">
        成功
        <b class="success">{{ result.imported ?? 0 }}</b>
        题， 失败
        <b :class="(result.failed ?? 0) ? 'danger' : 'success'">{{ result.failed ?? 0 }}</b>
        题
      </p>
      <Alert
        v-if="(result.failed ?? 0) > 0"
        type="warning"
        show-icon
        message="以下行未导入，请修正后重新上传"
        class="mb-3"
      />
      <Table
        v-if="(result.errors ?? []).length"
        :columns="columns"
        :data-source="result.errors"
        :pagination="false"
        row-key="row"
        size="small"
        :scroll="{ y: 320 }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'row'">
            <span>第 {{ record.row }} 行</span>
          </template>
          <template v-else-if="column.key === 'reason'">
            <span class="reason">{{ record.reason }}</span>
          </template>
        </template>
      </Table>
      <Alert v-else type="success" show-icon message="全部行导入成功" class="mt-2" />
    </div>
  </Modal>
</template>

<script setup lang="ts">
import { Alert, Modal, Table, type TableColumnsType } from 'ant-design-vue';

/**
 * 题库批量导入结果弹层（add-question-import-export）。
 * 展示成功数/失败数 + 行号+原因表，与后端响应 {imported, failed, errors:[{row,reason}]} 逐字段一致。
 * 字段可选以匹配 SDK 生成类型（字段可省略），展示时按缺省 0 / 空表兜底。
 */
defineProps<{
  open: boolean;
  result?: {
    imported?: number;
    failed?: number;
    errors?: Array<{ row?: number; reason?: string }>;
  } | null;
}>();

const emit = defineEmits<{ close: [] }>();

const columns: TableColumnsType = [
  { title: '行号', key: 'row', width: 90 },
  { title: '失败原因', key: 'reason' },
];
</script>

<style scoped>
.summary {
  margin-bottom: 12px;
}
.success {
  color: #52c41a;
}
.danger {
  color: #ff4d4f;
}
.reason {
  white-space: pre-wrap;
}
.mb-3 {
  margin-bottom: 12px;
}
.mt-2 {
  margin-top: 12px;
}
</style>
