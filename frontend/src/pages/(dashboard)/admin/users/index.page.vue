<template>
  <div class="user-import-export-container">
    <Card title="用户批量导入导出" class="mb-4">
      <Space direction="vertical" size="large" style="width: 100%">
        <!-- 批量导入与模板下载 -->
        <Card type="inner" title="学生批量导入">
          <Space>
            <Button type="primary" :loading="importing" @click="triggerUpload">批量导入学生</Button>
            <input
              ref="fileInput"
              type="file"
              accept=".xlsx"
              class="hidden-file"
              @change="onFileChange"
            />
            <Button :loading="templateLoading" @click="onDownloadTemplate">下载导入模板</Button>
          </Space>
          <div class="tip-text mt-2">
            注：仅支持 .xlsx 格式文件，单次上限 1000 行。导入学生角色固定为 STUDENT，密码 BCrypt
            加密，班级自动挂接已有班级。
          </div>
        </Card>

        <!-- 用户名单导出 -->
        <Card type="inner" title="用户名单导出">
          <Space wrap>
            <span>导出人群：</span>
            <Select v-model:value="exportScope" style="width: 130px">
              <SelectOption value="student">学生名单</SelectOption>
              <SelectOption value="teacher">教师名单</SelectOption>
              <SelectOption value="all">全部用户</SelectOption>
            </Select>

            <template v-if="exportScope === 'student'">
              <span>班级过滤：</span>
              <InputNumber
                v-model:value="exportClassId"
                placeholder="班级 ID (可选)"
                :min="1"
                data-test="export-class-id"
                style="width: 140px"
              />
            </template>

            <Button type="primary" :loading="exporting" @click="onExport">导出名单</Button>
          </Space>
          <div class="tip-text mt-2">
            注：导出采用流式生成，软删用户已自动过滤；学生名单含班级归属列。
          </div>
        </Card>
      </Space>
    </Card>

    <!-- 导入结果反馈弹层 -->
    <Modal v-model:open="importResultOpen" title="导入结果反馈" :footer="null" width="600px">
      <Alert
        :message="`成功导入 ${importResult.imported ?? 0} 条，失败 ${importResult.failed ?? 0} 条`"
        :type="(importResult.failed ?? 0) > 0 ? 'warning' : 'success'"
        show-icon
        class="mb-4"
      />
      <Table
        v-if="importResult.errors && importResult.errors.length > 0"
        :data-source="importResult.errors"
        :columns="errorColumns"
        row-key="row"
        :pagination="{ pageSize: 5 }"
        size="small"
      />
      <div class="text-right mt-4">
        <Button type="primary" @click="importResultOpen = false">确定</Button>
      </div>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import {
  Alert,
  Button,
  Card,
  InputNumber,
  Modal,
  Select,
  SelectOption,
  Space,
  Table,
  message,
} from 'ant-design-vue';
import type { TableColumnsType } from 'ant-design-vue';

import { apiClient, client, unwrap } from '@/api/apiClient';
import { importUsers, type UserImportResult } from '@/api/axios';
import { browserSaveFile, downloadExport } from '@/utils/exportDownload';

const fileInput = ref<HTMLInputElement | null>(null);
const importing = ref(false);
const templateLoading = ref(false);
const exporting = ref(false);

const exportScope = ref<'student' | 'teacher' | 'all'>('student');
const exportClassId = ref<number | undefined>(undefined);

const importResultOpen = ref(false);
const importResult = ref<UserImportResult>({
  imported: 0,
  failed: 0,
  errors: [],
});

const errorColumns: TableColumnsType = [
  { title: 'Excel行号', dataIndex: 'row', key: 'row', width: 100 },
  { title: '失败原因', dataIndex: 'reason', key: 'reason' },
];

function triggerUpload(): void {
  fileInput.value?.click();
}

async function onFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;

  if (!/\.xlsx$/i.test(file.name)) {
    message.error('仅支持 .xlsx 格式的文件');
    return;
  }

  importing.value = true;
  try {
    const result = await unwrap<UserImportResult>(
      importUsers({ client, throwOnError: true, body: { file } })
    );
    importResult.value = result ?? { imported: 0, failed: 0, errors: [] };
    importResultOpen.value = true;
  } catch (error) {
    message.error(error instanceof Error ? error.message : '导入失败，请稍后重试');
  } finally {
    importing.value = false;
  }
}

async function onDownloadTemplate(): Promise<void> {
  templateLoading.value = true;
  try {
    const fileName = await downloadExport(
      { http: apiClient, saveFile: browserSaveFile },
      '/api/admin/users/import-template',
      '用户导入模板.xlsx'
    );
    message.success(`已下载：${fileName}`);
  } catch (error) {
    message.error(error instanceof Error ? error.message : '模板下载失败，请稍后重试');
  } finally {
    templateLoading.value = false;
  }
}

async function onExport(): Promise<void> {
  exporting.value = true;
  try {
    let url = `/api/admin/users/export?scope=${exportScope.value}`;
    if (
      exportScope.value === 'student' &&
      exportClassId.value != null &&
      exportClassId.value !== 0
    ) {
      url += `&classId=${exportClassId.value}`;
    }
    const fileName = await downloadExport(
      { http: apiClient, saveFile: browserSaveFile },
      url,
      '用户导出.xlsx'
    );
    message.success(`已导出：${fileName}`);
  } catch (error) {
    message.error(error instanceof Error ? error.message : '导出失败，请稍后重试');
  } finally {
    exporting.value = false;
  }
}
</script>

<style scoped>
.hidden-file {
  display: none;
}
.tip-text {
  font-size: 13px;
  color: #8c8c8c;
}
.mt-2 {
  margin-top: 8px;
}
.mt-4 {
  margin-top: 16px;
}
.mb-4 {
  margin-bottom: 16px;
}
.text-right {
  text-align: right;
}
</style>
