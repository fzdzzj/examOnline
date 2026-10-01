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
          <template v-if="column.key === 'createdTime'">
            {{ formatTime((record as ClassResponse).createdTime) }}
          </template>
          <template v-else-if="column.key === 'actions'">
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
      <!-- 说明：按 ClassController 现有契约实现——分页 / 创建 / 更新 / 删除 / 学生列表 /
           入班 / 转班 / 移出。归属与存在性均由后端裁决，前端只渲染返回结果。 -->
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
    <Modal v-model:open="studentModalOpen" title="班级学生列表" width="760px" :footer="null">
      <Alert
        v-if="roster.state.error"
        type="error"
        show-icon
        closable
        :message="roster.state.error"
        class="mb-3"
        @close="roster.clearError()"
      />

      <!-- 入班：契约只吃 userId（POST /api/classes/{id}/students 的 JoinStudentRequest）。
           ⚠️ 后端没有「教师可检索学生」的只读端点（/api/users 不存在，
           AdminController 是 @RequireRole(ADMIN) 且不含用户列表），故此处按 ID 录入，
           不伪造下拉候选。 -->
      <div class="mb-3 flex flex-wrap items-end gap-2">
        <div>
          <p class="mb-1 text-xs text-gray-500">学生用户 ID</p>
          <InputNumber
            v-model:value="joinUserId"
            :min="1"
            placeholder="如 12"
            style="width: 160px"
          />
        </div>
        <Button
          type="primary"
          :loading="roster.state.pending === 'join'"
          :disabled="joinUserId === undefined"
          @click="onJoinStudent"
        >
          加入本班
        </Button>
        <Input
          v-model:value="studentSearch"
          placeholder="搜索本班学生（姓名 / 用户名）"
          allow-clear
          style="width: 240px"
        />
      </div>

      <Table
        :columns="studentColumns"
        :data-source="filteredStudents"
        :loading="studentsFetching"
        :pagination="false"
        :row-key="(row: ClassStudentItem) => row.userId as number"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'joinedTime'">
            {{ formatTime((record as ClassStudentItem).joinedTime) }}
          </template>
          <template v-else-if="column.key === 'actions'">
            <Space>
              <Button type="link" size="small" @click="openTransfer(record as ClassStudentItem)">
                转班
              </Button>
              <Popconfirm
                title="将该学生移出本班？（仅解除班级归属，成绩随人不迁移）"
                :ok-button-props="{ danger: true }"
                @confirm="onRemoveStudent(record as ClassStudentItem)"
              >
                <Button type="link" size="small" danger :disabled="removing(record)">移出</Button>
              </Popconfirm>
            </Space>
          </template>
        </template>
      </Table>
      <p v-if="!studentsFetching && filteredStudents.length === 0" class="mb-0 mt-2 text-gray-500">
        本班暂无学生（或搜索无结果）。
      </p>
    </Modal>

    <!-- 转班 Modal：目标班级只能从「本班教师可见的班级」里选（后端 page 接口按归属过滤），
         越权指向别人的班级由后端 getOwnedClass / selectById 裁决，前端不做安全假设。 -->
    <Modal
      v-model:open="transferModalOpen"
      title="学生转班"
      :confirm-loading="transferring"
      :ok-button-props="{ disabled: transferTargetId === undefined }"
      ok-text="确认转班"
      @ok="onConfirmTransfer"
    >
      <p class="mb-3 text-gray-700">
        学生
        <b>{{ transferStudentName }}</b>
        （ID {{ transferUserId ?? '—' }}）将由 「{{ currentClassName }}」转入下方所选班级。
      </p>
      <!-- ⚠️ ant-design-vue 4 的 Alert 只渲染 message/description，正文必须走 description
           （默认插槽会被静默丢弃——本仓多份旧页面的 Alert 正文就是这么消失的） -->
      <Alert
        type="info"
        show-icon
        class="mb-3"
        description="后端仅更新 user_class.class_id（成绩随人，历史成绩不迁移），入班时间记为转班时刻。"
      />
      <Select
        v-model:value="transferTargetId"
        :options="transferTargetOptions"
        :loading="transferTargetsFetching"
        placeholder="选择目标班级"
        style="width: 100%"
        show-search
        option-filter-prop="label"
      />
    </Modal>
  </div>
</template>

<script setup lang="ts">
import {
  Alert,
  Button,
  Card,
  Form,
  FormItem,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  message,
  type TableColumnsType,
} from 'ant-design-vue';
import dayjs from 'dayjs';
import { computed, ref } from 'vue';
import { useQuery } from '@tanstack/vue-query';

import {
  create4 as createClass,
  delete3 as deleteClass,
  joinStudent as joinStudentContract,
  listStudents,
  page3 as pageClasses,
  removeStudent as removeStudentContract,
  transfer as transferContract,
  update3 as updateClass,
  type ClassPageResponse,
  type ClassResponse,
  type ClassStudentItem,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { queryClient } from '@/api/queryClient';
import { createClassRoster } from '@/hooks/useClassRoster';

/**
 * 班级管理页面（阶段 21 考务）。
 * 后端契约：GET/POST /api/classes、PUT/DELETE /api/classes/{id}、
 * GET /api/classes/{id}/students、POST /api/classes/{id}/students（入班）、
 * PUT /api/classes/{id}/students/{userId}/transfer（转班）、
 * DELETE /api/classes/{id}/students/{userId}（移出）。
 * 教师仅见/仅改自己归属的班级——越权由 ClassService.getOwnedClass 裁决（隐藏按钮不是安全边界）。
 */

const columns: TableColumnsType = [
  { title: 'ID', key: 'id', dataIndex: 'id', width: 80 },
  { title: '班级名称', key: 'name', dataIndex: 'name' },
  { title: '课程 ID', key: 'courseId', dataIndex: 'courseId', width: 120 },
  { title: '创建教师 ID', key: 'createdBy', dataIndex: 'createdBy', width: 130 },
  { title: '创建时间', key: 'createdTime', width: 180 },
  { title: '操作', key: 'actions', width: 220 },
];

const studentColumns: TableColumnsType = [
  { title: '用户 ID', key: 'userId', dataIndex: 'userId', width: 100 },
  { title: '用户名', key: 'username', dataIndex: 'username', width: 140 },
  { title: '姓名', key: 'name', dataIndex: 'name', width: 140 },
  { title: '入班时间', key: 'joinedTime', width: 170 },
  { title: '操作', key: 'actions', width: 140 },
];

function formatTime(value: string | undefined | null): string {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—';
}

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
/** 入班录入框：契约入参只有 userId（无学生检索端点，故不伪造下拉候选）。 */
const joinUserId = ref<number | undefined>(undefined);

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
  joinUserId.value = undefined;
  roster.clearError();
  studentModalOpen.value = true;
  void refetchStudents();
}

// ===== 入班 / 转班 / 移出 =====

/** 当前打开学生列表的班级 id（三个动作的路径班级）。 */
const activeClassId = computed(() => studentsExamKey.value);

function invalidateRoster(classIds: number[]): void {
  for (const id of classIds) {
    void queryClient.invalidateQueries({ queryKey: ['classes', id, 'students'] });
  }
}

// 生产注入：gen:api 生成客户端 + apiClient 拦截器；单测走 hooks/__tests__/useClassRoster.spec.ts
const roster = createClassRoster({
  join: (classId, userId) =>
    unwrap(
      joinStudentContract({
        client,
        throwOnError: true,
        path: { id: classId },
        body: { userId },
      })
    ),
  transfer: (classId, userId, targetClassId) =>
    unwrap(
      transferContract({
        client,
        throwOnError: true,
        path: { id: classId, userId },
        body: { targetClassId },
      })
    ),
  remove: (classId, userId) =>
    unwrap(
      removeStudentContract({
        client,
        throwOnError: true,
        path: { id: classId, userId },
      })
    ),
  onChanged: invalidateRoster,
});

async function onJoinStudent(): Promise<void> {
  const classId = activeClassId.value;
  const userId = joinUserId.value;
  if (classId === undefined || userId === undefined) return;
  const ok = await roster.joinStudent(classId, userId);
  if (ok) {
    joinUserId.value = undefined;
    message.success('学生已加入本班');
  }
}

function removing(student: ClassStudentItem): boolean {
  return roster.state.pending === `remove:${student.userId}`;
}

async function onRemoveStudent(student: ClassStudentItem): Promise<void> {
  const classId = activeClassId.value;
  if (classId === undefined || student.userId === undefined) return;
  if (await roster.removeStudent(classId, student.userId)) {
    message.success('学生已移出本班');
  }
}

const transferModalOpen = ref(false);
const transferUserId = ref<number | undefined>(undefined);
const transferStudentName = ref('');
const transferTargetId = ref<number | undefined>(undefined);
const transferring = computed(() => roster.state.pending === `transfer:${transferUserId.value}`);

/** 目标班级候选：走同一张分页接口（后端按归属过滤），不另造端点。 */
const { data: transferTargets, isFetching: transferTargetsFetching } = useQuery({
  queryKey: ['classes', 'for-transfer'] as const,
  queryFn: () =>
    unwrap<ClassPageResponse>(
      pageClasses({ client, throwOnError: true, query: { page: 1, size: 100 } })
    ),
  enabled: computed(() => transferModalOpen.value),
});

const transferTargetOptions = computed(() =>
  (transferTargets.value?.list ?? [])
    .filter((c) => c.id !== undefined && c.id !== activeClassId.value)
    .map((c) => ({ value: c.id as number, label: c.name ?? `班级 #${c.id}` }))
);

function openTransfer(student: ClassStudentItem): void {
  if (student.userId === undefined || activeClassId.value === undefined) return;
  transferUserId.value = student.userId;
  transferStudentName.value = student.name || student.username || `用户 #${student.userId}`;
  transferTargetId.value = undefined;
  transferModalOpen.value = true;
}

const currentClassName = computed(
  () => rows.value.find((c) => c.id === activeClassId.value)?.name ?? '当前班级'
);

async function onConfirmTransfer(): Promise<void> {
  const classId = activeClassId.value;
  const userId = transferUserId.value;
  const targetClassId = transferTargetId.value;
  if (classId === undefined || userId === undefined || targetClassId === undefined) return;
  if (await roster.transferStudent(classId, userId, targetClassId)) {
    transferModalOpen.value = false;
    message.success('学生已转班，两个班级的名单均已刷新');
  }
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
