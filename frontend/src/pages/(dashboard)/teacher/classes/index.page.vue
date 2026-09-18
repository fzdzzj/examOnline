<script setup lang="ts">
import { message, Modal, Table } from 'ant-design-vue';
import { onMounted, ref } from 'vue';
import type { ClassEntity } from '@/api';
import { classesApi } from '@/api';

/**
 * 班级管理页面
 * 功能：班级 CRUD、学生入班/转班、班级学生列表
 *
 * 后端契约：
 * - GET /api/classes: 班级分页（教师仅见自己归属的班级）
 * - POST /api/classes: 创建班级
 * - PUT /api/classes/{id}: 更新班级
 * - DELETE /api/classes/{id}: 删除班级（软删）
 * - GET /api/classes/{id}/students: 班级学生列表
 * - POST /api/classes/{id}/students: 学生入班
 * - PUT /api/classes/{id}/students/{userId}/transfer: 学生转班
 */

const classList = ref<ClassEntity[]>([]);
const loading = ref(false);
const pagination = ref({
  current: 1,
  pageSize: 10,
  total: 0,
});

// 新建/编辑班级表单
const classForm = ref({ name: '', courseId: undefined as number | undefined });
const isModalVisible = ref(false);
const isEditing = ref(false);
const editingClassId = ref<number | undefined>();

// 学生管理
const studentModalVisible = ref(false);
const selectedClassId = ref<number | undefined>();
interface StudentItem {
  studentId: number;
  studentName: string;
  studentNo?: string;
  joinedTime?: string;
}
const studentList = ref<StudentItem[]>([]);
const studentSearch = ref('');

// 加载班级列表
async function loadClasses() {
  loading.value = true;
  try {
    const response = await classesApi.classesPageGet({
      page: pagination.value.current,
      size: pagination.value.pageSize,
    });

    if (response.code === 200 && response.data) {
      classList.value = response.data.list || [];
      pagination.value.total = response.data.total || 0;
    } else {
      message.error(response.message || '加载班级列表失败');
    }
  } catch (error) {
    console.error('加载班级列表失败:', error);
    message.error('加载班级列表失败');
  } finally {
    loading.value = false;
  }
}

// 打开新建/编辑 modal
function openClassModal(classItem?: ClassEntity) {
  if (classItem) {
    isEditing.value = true;
    editingClassId.value = classItem.id;
    classForm.value = { name: classItem.name, courseId: classItem.courseId };
  } else {
    isEditing.value = false;
    editingClassId.value = undefined;
    classForm.value = { name: '', courseId: undefined };
  }
  isModalVisible.value = true;
}

// 保存班级
async function saveClass() {
  try {
    if (isEditing.value && editingClassId.value) {
      await classesApi.classesIdPut({
        id: editingClassId.value,
        ClassUpdateRequest: { name: classForm.value.name, courseId: classForm.value.courseId },
      });
      message.success('更新班级成功');
    } else {
      await classesApi.classesPost({
        ClassCreateRequest: { name: classForm.value.name, courseId: classForm.value.courseId },
      });
      message.success('创建班级成功');
    }
    isModalVisible.value = false;
    loadClasses();
  } catch (error) {
    console.error('保存班级失败:', error);
    message.error(isEditing.value ? '更新班级失败' : '创建班级失败');
  }
}

// 删除班级
async function deleteClass(classItem: ClassEntity) {
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除班级「${classItem.name}」吗？此操作为软删。`,
    okText: '删除',
    cancelText: '取消',
    onOk: async () => {
      try {
        await classesApi.classesIdDelete({ id: classItem.id! });
        message.success('删除班级成功');
        loadClasses();
      } catch (error) {
        console.error('删除班级失败:', error);
        message.error('删除班级失败');
      }
    },
  });
}

// 查看班级学生
async function viewClassStudents(classItem: ClassEntity) {
  selectedClassId.value = classItem.id;
  studentModalVisible.value = true;

  try {
    const response = await classesApi.classesIdStudentsGet({ id: classItem.id! });
    if (response.code === 200 && response.data) {
      studentList.value = response.data;
    } else {
      studentList.value = [];
    }
  } catch (error) {
    console.error('加载学生列表失败:', error);
    studentList.value = [];
  }
}

// 搜索学生
const filteredStudentList = computed(() => {
  if (!studentSearch.value) return studentList.value;
  const keyword = studentSearch.value.toLowerCase();
  return studentList.value.filter(
    (s) =>
      s.studentName?.toLowerCase().includes(keyword) || s.studentId?.toString().includes(keyword)
  );
});

onMounted(() => {
  loadClasses();
});
</script>

<template>
  <div class="p-6">
    <Card title="班级管理" class="mb-4">
      <template #extra>
        <Button @click="openClassModal">+ 新建班级</Button>
      </template>

      <Table
        :dataSource="classList"
        :loading="loading"
        :pagination="pagination"
        :scroll="{ x: 800 }"
        @change="
          (page, filters) => {
            if (page && typeof page === 'object') {
              pagination.value.current = page.current;
              pagination.value.pageSize = page.pageSize;
              loadClasses();
            }
          }
        "
      >
        <Column title="ID" dataIndex="id" width="80" />
        <Column title="班级名称" dataIndex="name" width="200" />
        <Column title="课程 ID" dataIndex="courseId" width="120" />
        <Column title="创建教师 ID" dataIndex="createdBy" width="120" />
        <Column title="创建时间" dataIndex="createdTime" width="180" />
        <Column title="操作" fixed="right" width="250">
          <template #bodyCell="{ record }">
            <Space>
              <Button type="link" @click="viewClassStudents(record)">查看学生</Button>
              <Button type="link" @click="openClassModal(record)">编辑</Button>
              <Popconfirm
                title="确认删除"
                description="确定要删除该班级吗？"
                okText="确定"
                cancelText="取消"
                @confirm="deleteClass(record)"
              >
                <Button type="link" danger>删除</Button>
              </Popconfirm>
            </Space>
          </template>
        </Column>
      </Table>
    </Card>

    <!-- 新建/编辑班级 Modal -->
    <Modal
      v-model:visible="isModalVisible"
      :title="isEditing ? '编辑班级' : '新建班级'"
      :ok-disabled="!classForm.name"
      @ok="saveClass"
    >
      <Form layout="vertical">
        <FormItem label="班级名称" required>
          <Input v-model:value="classForm.name" placeholder="请输入班级名称" allow-clear />
        </FormItem>
        <FormItem label="课程 ID（可选）">
          <InputNumber
            v-model:value="classForm.courseId"
            placeholder="请输入课程 ID"
            style="width: 100%"
            min="1"
          />
        </FormItem>
      </Form>
    </Modal>

    <!-- 学生列表 Modal -->
    <Modal v-model:visible="studentModalVisible" title="班级学生列表" width="80%" footer>
      <template #footer>
        <Button @click="studentModalVisible = false">关闭</Button>
      </template>

      <div class="mb-4">
        <Input
          v-model:value="studentSearch"
          placeholder="搜索学生（姓名/学号）"
          allow-clear
          style="max-width: 300px"
        />
      </div>

      <Table :dataSource="filteredStudentList" :loading="false" scroll="{ x: 600 }">
        <Column title="学生 ID" dataIndex="studentId" width="120" />
        <Column title="学生姓名" dataIndex="studentName" width="150" />
        <Column title="学号" dataIndex="studentNo" width="150" />
        <Column title="入班时间" dataIndex="joinedTime" width="180" />
      </Table>
    </Modal>
  </div>
</template>

<style scoped>
.p-6 {
  padding: 1.5rem;
}

.mb-4 {
  margin-bottom: 1rem;
}

.max-w-xl {
  max-width: 36rem;
}
</style>
