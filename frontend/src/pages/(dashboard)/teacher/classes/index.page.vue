<template>
  <div>
    <Card title="班级管理" class="mb-4">
      <template #extra>
        <Button type="primary" @click="openClassModal()">+ 新建班级</Button>
      </template>

      <Table
        :columns="columns"
        :data-source="rows"
        :loading="isFetching"
        :pagination="tablePagination"
        :row-key="(row: ClassResponse) => row.id as number"
        size="middle"
        @change="onTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'actions'">
            <Space>
              <Button type="link" size="small" @click="viewClassStudents(record as ClassResponse)">
                查看学生
              </Button>
              <Button type="link" size="small" @click="openClassModal(record as ClassResponse)">
                编辑
              </Button>
              <Popconfirm
                title="删除班级为软删，确认删除？"
                @confirm="onDeleteClass(record as ClassResponse)"
              >
                <Button type="link" size="small" danger>删除</Button>
              </Popconfirm>
            </Space>
          </template>
        </template>
      </Table>
      <!-- 说明：按 ClassController 现有契约实现——分页 / 创建 / 更新 / 删除 / 学生列表，
           学生入班 / 转班 / 移出在本页不展开（属考务编排，后续阶段接入）。 -->
    </Card>

    <!-- 新建/编辑班级 Modal -->
    <Modal
      v-model:open="classModalOpen"
      :title="isEditing ? '编辑班级' : '新建班级'"
      :confirm-loading="saving"
      :ok-button-props="{ disabled: !draftName.trim() }"
      @ok="saveClass"
    >
      <Form layout="vertical">
        <FormItem label="班级名称" required>
          <Input
            v-model:value="draftName"
            :maxlength="64"
            placeholder="请输入班级名称"
            allow-clear
          />
        </FormItem>
        <FormItem label="课程 ID（可选）">
          <InputNumber
            v-model:value="draftCourseId"
            :min="1"
            placeholder="请输入课程 ID"
            style="width: 100%"
          />
        </FormItem>
      </Form>
    </Modal>

    <!-- 学生列表 Modal -->
    <Modal v-model:open="studentModalOpen" title="班级学生列表" width="640px" :footer="null">
      <div class="mb-3">
        <Input
          v-model:value="studentSearch"
          placeholder="搜索学生（姓名 / 用户名）"
          allow-clear
          style="max-width: 280px"
        />
      </div>

      <Table
        :columns="studentColumns"
        :data-source="filteredStudents"
        :loading="studentsFetching"
        :pagination="false"
        :row-key="(row: ClassStudentItem) => row.userId as number"
        size="small"
      />
    </Modal>
  </div>
</template>

<script setup lang="ts">
import {
  Button,
  Card,
  Form,
  FormItem,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Space,
  Table,
  message,
  type TableColumnsType,
} from 'ant-design-vue';
import { computed, ref } from 'vue';
import { useQuery } from '@tanstack/vue-query';

import {
  create4 as createClass,
  delete3 as deleteClass,
  listStudents,
  page3 as pageClasses,
  update3 as updateClass,
  type ClassPageResponse,
  type ClassResponse,
  type ClassStudentItem,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';

/**
 * 班级管理页面（阶段 21 考务，修复版）。
 * 后端契约：GET/POST /api/classes、PUT/DELETE /api/classes/{id}、
 * GET /api/classes/{id}/students；教师仅见自己归属的班级（后端裁决）。
 */

const columns: TableColumnsType = [
  { title: 'ID', key: 'id', width: 80 },
  { title: '班级名称', key: 'name' },
  { title: '课程 ID', key: 'courseId', width: 120 },
  { title: '创建教师 ID', key: 'createdBy', width: 130 },
  { title: '创建时间', key: 'createdTime', width: 180 },
  { title: '操作', key: 'actions', width: 220 },
];

const studentColumns: TableColumnsType = [
  { title: '用户 ID', key: 'userId', width: 100 },
  { title: '用户名', key: 'username', width: 140 },
  { title: '姓名', key: 'name', width: 140 },
  { title: '入班时间', key: 'joinedTime', width: 180 },
];

const pageNum = ref(1);
const pageSize = ref(10);

const { data, isFetching, refetch } = useQuery({
  queryKey: computed(() => ['classes', pageNum.value, pageSize.value] as const),
  queryFn: () =>
    unwrap<ClassPageResponse>(
      pageClasses({
        client,
        throwOnError: true,
        query: { page: pageNum.value, size: pageSize.value },
      })
    ),
});

const rows = computed<ClassResponse[]>(() => data.value?.list ?? []);

const tablePagination = computed(() => ({
  current: pageNum.value,
  pageSize: pageSize.value,
  // 后端未返回 total 时退化为「只有当页」的分页器，避免拍脑袋造总数
  total: data.value?.total ?? data.value?.list?.length ?? 0,
  showSizeChanger: false,
}));

function onTableChange(pag: { current?: number; pageSize?: number }): void {
  if (typeof pag.current === 'number') pageNum.value = pag.current;
  if (typeof pag.pageSize === 'number') pageSize.value = pag.pageSize;
  void refetch();
}

// ===== 新建 / 编辑 =====
const classModalOpen = ref(false);
const isEditing = ref(false);
const editingClassId = ref<number | undefined>(undefined);
const draftName = ref('');
const draftCourseId = ref<number | undefined>(undefined);
const saving = ref(false);

function openClassModal(clazz?: ClassResponse): void {
  if (clazz) {
    isEditing.value = true;
    editingClassId.value = clazz.id;
    draftName.value = clazz.name ?? '';
    draftCourseId.value = clazz.courseId;
  } else {
    isEditing.value = false;
    editingClassId.value = undefined;
    draftName.value = '';
    draftCourseId.value = undefined;
  }
  classModalOpen.value = true;
}

async function saveClass(): Promise<void> {
  const name = draftName.value.trim();
  if (!name) {
    message.warning('请输入班级名称');
    return;
  }
  saving.value = true;
  try {
    if (isEditing.value && editingClassId.value !== undefined) {
      await unwrap(
        updateClass({
          client,
          throwOnError: true,
          path: { id: editingClassId.value },
          body: {
            name,
            ...(draftCourseId.value !== undefined ? { courseId: draftCourseId.value } : {}),
          },
        })
      );
      message.success('班级已更新');
    } else {
      await unwrap(
        createClass({
          client,
          throwOnError: true,
          body: {
            name,
            ...(draftCourseId.value !== undefined ? { courseId: draftCourseId.value } : {}),
          },
        })
      );
      message.success('班级已创建');
    }
    classModalOpen.value = false;
    void refetch();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '保存班级失败，请稍后重试');
  } finally {
    saving.value = false;
  }
}

async function onDeleteClass(clazz: ClassResponse): Promise<void> {
  if (clazz.id === undefined) return;
  try {
    await unwrap(deleteClass({ client, throwOnError: true, path: { id: clazz.id } }));
    message.success('班级已删除');
    void refetch();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '删除班级失败，请稍后重试');
  }
}

// ===== 学生列表 =====
const studentModalOpen = ref(false);
const studentSearch = ref('');
const studentsExamKey = ref<number | undefined>(undefined);

const {
  data: studentsData,
  isFetching: studentsFetching,
  refetch: refetchStudents,
} = useQuery({
  queryKey: computed(() => ['classes', studentsExamKey.value, 'students'] as const),
  queryFn: () =>
    unwrap<ClassStudentItem[]>(
      listStudents({
        client,
        throwOnError: true,
        path: { id: studentsExamKey.value as number },
      })
    ),
  enabled: computed(() => studentModalOpen.value && studentsExamKey.value !== undefined),
});

const filteredStudents = computed<ClassStudentItem[]>(() => {
  const keyword = studentSearch.value.trim().toLowerCase();
  if (!keyword) return studentsData.value ?? [];
  return (studentsData.value ?? []).filter(
    (s) => s.name?.toLowerCase().includes(keyword) || s.username?.toLowerCase().includes(keyword)
  );
});

function viewClassStudents(clazz: ClassResponse): void {
  if (clazz.id === undefined) return;
  studentsExamKey.value = clazz.id;
  studentSearch.value = '';
  studentModalOpen.value = true;
  void refetchStudents();
}
</script>

<style scoped>
.mb-3 {
  margin-bottom: 0.75rem;
}
.mb-4 {
  margin-bottom: 1rem;
}
</style>
