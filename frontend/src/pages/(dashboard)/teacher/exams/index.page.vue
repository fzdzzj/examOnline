<script setup lang="ts">
import { message, Modal, Table } from 'ant-design-vue';
import { computed, onMounted, ref, type Ref } from 'vue';
import type { ExamResponse } from '@/api';
import { examsApi } from '@/api';
import { EXAM_STATUS, getExamStatusConfig } from '@/constants/examStatus';

/**
 * 考试列表页面
 * 功能：考试分页、状态标签、可用操作按钮（由后端决定）
 *
 * 后端契约：
 * - GET /api/exams: 考试分页（教师仅见自己的考试）
 * - POST /api/exams: 创建考试
 * - GET /api/exams/{id}: 考试详情
 * - PUT /api/exams/{id}: 更新考试（仅未发布且未开始）
 * - DELETE /api/exams/{id}: 删除考试（仅未发布且未开始）
 * - POST /api/exams/{id}/publish: 发布考试（生成快照）
 * - POST /api/exams/{id}/force-end: 强制结束（触发缺考标记）
 * - GET /api/exams/{id}/snapshot: 读取试卷快照
 */

const examList = ref<ExamResponse[]>([]);
const loading = ref(false);
interface Pagination {
  current: number;
  pageSize: number;
  total: number;
}
const pagination = ref<Pagination>({
  current: 1,
  pageSize: 10,
  total: 0,
});

// 筛选条件
const statusFilter = ref<number | undefined>();
const searchTitle = ref('');

// 发布确认
const publishModalVisible = ref(false);
const publishingExamId = ref<number | undefined>();

// force-end 确认
const forceEndModalVisible = ref(false);
const forceEndingExamId = ref<number | undefined>();

// 加载考试列表
async function loadExams() {
  loading.value = true;
  try {
    const response = await examsApi.examsPageGet({
      page: pagination.value.current,
      size: pagination.value.pageSize,
    });

    if (response.code === 200 && response.data) {
      examList.value = response.data.list || [];
      pagination.value.total = response.data.total || 0;
    } else {
      message.error(response.message || '加载考试列表失败');
    }
  } catch (error) {
    console.error('加载考试列表失败:', error);
    message.error('加载考试列表失败');
  } finally {
    loading.value = false;
  }
}

// 过滤后的列表
const filteredExamList = computed(() => {
  let result = examList.value;

  // 按标题搜索
  if (searchTitle.value) {
    result = result.filter((e) => e.title?.includes(searchTitle.value));
  }

  // 按状态筛选
  if (statusFilter.value !== undefined) {
    result = result.filter((e) => e.status === statusFilter.value);
  }

  return result;
});

// 打开发布确认
function openPublishConfirm(exam: ExamResponse) {
  publishingExamId.value = exam.id;
  publishModalVisible.value = true;
}

// 确认发布
async function confirmPublish() {
  if (!publishingExamId.value) return;

  try {
    const response = await examsApi.examsIdPublishPost({ id: publishingExamId.value });
    if (response.code === 200) {
      message.success('发布考试成功，已生成试卷快照');
      publishModalVisible.value = false;
      loadExams();
    } else {
      message.error(response.message || '发布考试失败');
    }
  } catch (error) {
    console.error('发布考试失败:', error);
    message.error('发布考试失败');
  }
}

// 打开 force-end 确认
function openForceEndConfirm(exam: ExamResponse) {
  forceEndingExamId.value = exam.id;
  forceEndModalVisible.value = true;
}

// 确认 force-end
async function confirmForceEnd() {
  if (!forceEndingExamId.value) return;

  try {
    const response = await examsApi.examsIdForceEndPost({ id: forceEndingExamId.value });
    if (response.code === 200) {
      message.success('强制结束成功，已触发缺考标记');
      forceEndModalVisible.value = false;
      loadExams();
    } else {
      message.error(response.message || '强制结束失败');
    }
  } catch (error) {
    console.error('强制结束失败:', error);
    message.error('强制结束失败');
  }
}

// 查看考试详情
function viewExamDetail(_exam: ExamResponse) {
  // TODO: 跳转到详情页
}

onMounted(() => {
  loadExams();
});
</script>

<template>
  <div class="p-6">
    <Card title="考试管理" class="mb-4">
      <template #extra>
        <Button type="primary" @click="$router.push('/teacher/exams/create')">+ 新建考试</Button>
      </template>

      <!-- 筛选区 -->
      <div class="mb-4 flex gap-4">
        <Input
          v-model:value="searchTitle"
          placeholder="搜索考试标题"
          allow-clear
          style="width: 300px"
          @change="loadExams"
        />
        <Select
          v-model:value="statusFilter"
          placeholder="全部状态"
          allow-clear
          style="width: 150px"
          @change="loadExams"
        >
          <SelectOption :value="EXAM_STATUS.NOT_STARTED">未开始</SelectOption>
          <SelectOption :value="EXAM_STATUS.IN_PROGRESS">进行中</SelectOption>
          <SelectOption :value="EXAM_STATUS.ENDED">已结束</SelectOption>
          <SelectOption :value="EXAM_STATUS.GRADED">已批改</SelectOption>
          <SelectOption :value="EXAM_STATUS.PUBLISHED">已发布</SelectOption>
        </Select>
      </div>

      <Table
        :dataSource="filteredExamList"
        :loading="loading"
        :pagination="pagination"
        :scroll="{ x: 1200 }"
        @change="
          (page: any, _filters: any) => {
            if (page && typeof page === 'object') {
              pagination.current = page.current;
              pagination.pageSize = page.pageSize;
              loadExams();
            }
          }
        "
      >
        <Column title="ID" dataIndex="id" width="80" />
        <Column title="考试标题" dataIndex="title" width="250" />
        <Column title="试卷 ID" dataIndex="paperId" width="120" />
        <Column title="班级 ID" dataIndex="classId" width="120" />
        <Column title="状态" width="120">
          <template #bodyCell="{ record }">
            <Tag :color="getExamStatusConfig(record.status!).color">
              {{ getExamStatusConfig(record.status!).label }}
            </Tag>
          </template>
        </Column>
        <Column title="开始时间" dataIndex="startTime" width="180" />
        <Column title="结束时间" dataIndex="endTime" width="180" />
        <Column title="个人时长 (分钟)" dataIndex="durationMinutes" width="120" />
        <Column title="发布时间" dataIndex="createdTime" width="180" />
        <Column title="操作" fixed="right" width="300">
          <template #bodyCell="{ record }">
            <Space>
              <Button type="link" @click="viewExamDetail(record)">详情</Button>

              <!-- 发布按钮：仅未发布的考试可发布 -->
              <Button
                v-if="!record.published && record.status === EXAM_STATUS.NOT_STARTED"
                type="link"
                @click="openPublishConfirm(record)"
              >
                发布
              </Button>

              <!-- force-end 按钮：仅进行中的考试可结束 -->
              <Button
                v-if="record.status === EXAM_STATUS.IN_PROGRESS"
                type="link"
                danger
                @click="openForceEndConfirm(record)"
              >
                强制结束
              </Button>
            </Space>
          </template>
        </Column>
      </Table>
    </Card>

    <!-- 发布确认弹窗 -->
    <Modal
      v-model:visible="publishModalVisible"
      title="确认发布"
      :ok-disabled="!publishingExamId"
      @ok="confirmPublish"
    >
      <Alert type="info" show-icon>
        <p class="mb-2">确定要发布该考试吗？</p>
        <p class="text-gray-600">发布后将会：</p>
        <ul class="list-disc pl-5 text-gray-600">
          <li>生成试卷快照（唯一时机），学生侧可见</li>
          <li>绑定试卷被锁定（只读），禁止改题/删题</li>
          <li>考试状态仍为「未开始」，等待定时开考</li>
        </ul>
      </Alert>
    </Modal>

    <!-- force-end 确认弹窗 -->
    <Modal
      v-model:visible="forceEndModalVisible"
      title="确认强制结束"
      :ok-disabled="!forceEndingExamId"
      @ok="confirmForceEnd"
    >
      <Alert type="warning" show-icon>
        <p class="mb-2 font-semibold">警告：此操作将立即结束考试！</p>
        <p class="text-gray-700">强制结束会：</p>
        <ul class="list-disc pl-5 text-gray-700">
          <li>将考试状态从「进行中」改为「已结束」</li>
          <li>
            <strong class="text-red-600">触发缺考标记</strong>
            （占考名单自动填充）
          </li>
          <li>锁定当前答卷（按最后自动保存）</li>
        </ul>
        <p class="mt-3 text-sm text-gray-600">⚠️ 这是不可逆操作，请谨慎执行。</p>
      </Alert>
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

.flex {
  display: flex;
}

.gap-4 {
  gap: 1rem;
}

.text-gray-600 {
  color: #6b7280;
}

.text-gray-700 {
  color: #374151;
}

.font-semibold {
  font-weight: 600;
}

.text-red-600 {
  color: #dc2626;
}
</style>
