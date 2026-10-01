<template>
  <div>
    <Alert
      v-if="error"
      type="error"
      show-icon
      :message="error"
      data-test="list-error"
      class="mb-3"
    />

    <Table
      v-if="items.length || loading"
      :columns="columns"
      :data-source="items"
      :loading="loading"
      :pagination="false"
      :row-key="(row: ExamListItem) => row.examId as number"
      size="middle"
      data-test="exam-list"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'group'">
          <!-- 状态文案**只**由后端 group 算出来：不拿 startTime/endTime/当前时间二次推断 -->
          <Tag :color="groupColorOf((record as ExamListItem).group)" data-test="group-tag">
            {{ groupLabelOf((record as ExamListItem).group) }}
          </Tag>
        </template>

        <template v-else-if="column.key === 'window'">
          {{ formatTime((record as ExamListItem).startTime) }} ~
          {{ formatTime((record as ExamListItem).endTime) }}
        </template>

        <template v-else-if="column.key === 'remaining'">
          <span data-test="remaining">
            {{ formatCountdown((record as ExamListItem).remainingSeconds) }}
          </span>
        </template>

        <template v-else-if="column.key === 'actions'">
          <Button
            type="primary"
            size="small"
            data-test="enter-button"
            :disabled="!canEnterOf(record as ExamListItem)"
            @click="emit('enter', record as ExamListItem)"
          >
            {{ (record as ExamListItem).group === EXAM_GROUP.ONGOING ? '继续作答' : '进入考试' }}
          </Button>
        </template>
      </template>
    </Table>

    <!--
      空态与错误态分开：后端拉不到（error）时说「暂无可参加的考试」，
      学生会以为自己没被安排考试，而真相是系统坏了。
    -->
    <div v-else-if="!error" data-test="empty">暂无可参加的考试</div>
  </div>
</template>

<script setup lang="ts">
/**
 * 学生考试列表（阶段 22 第 1 片）。
 *
 * 数据契约：`GET /api/exam-taking/exams` → `ExamListItem[]`（`ExamTakingService.myExams`）。
 * 三态**只读后端 `group`**（UPCOMING 待考 / ONGOING 进行中 / FINISHED 已完成）：
 * 后端算分组时要同时看答卷状态、个人截止与考试状态机（`myExams` 里那 20 行 if/else），
 * 前端任何一处"我看时间也像进行中"的推断都会跟它打架，所以这里一处都不推。
 * 「进入考试」入口的可点性同理，只认后端 `canEnter`。
 */
import { Alert, Button, Table, Tag, type TableColumnsType } from 'ant-design-vue';
import dayjs from 'dayjs';

import type { ExamListItem } from '@/api/axios';
import { EXAM_GROUP } from '@/constants/studentTaking';
import { canEnterOf } from '@/hooks/useStudentTaking';
import { formatCountdown, groupLabelOf } from '@/utils/studentTaking';

withDefaults(
  defineProps<{
    items: ExamListItem[];
    loading?: boolean;
    /** 拉取失败时由页面把后端的 message 传进来；列表不伪装成「暂无考试」 */
    error?: string | null;
  }>(),
  { loading: false, error: null }
);

const emit = defineEmits<{ enter: [item: ExamListItem] }>();

const columns: TableColumnsType = [
  { title: '考试', key: 'title', dataIndex: 'title' },
  { title: '状态', key: 'group', width: 200 },
  { title: '考试时间窗', key: 'window', width: 300 },
  { title: '剩余时长', key: 'remaining', width: 120 },
  { title: '操作', key: 'actions', width: 130 },
];

function formatTime(value: string | undefined | null): string {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—';
}

/** 配色只是可读性，不参与判定；未知分组用 default 色，不借颜色暗示状态。 */
function groupColorOf(group: string | null | undefined): string {
  switch (group) {
    case EXAM_GROUP.UPCOMING:
      return 'blue';
    case EXAM_GROUP.ONGOING:
      return 'orange';
    case EXAM_GROUP.FINISHED:
      return 'green';
    default:
      return 'default';
  }
}
</script>

<style scoped>
.mb-3 {
  margin-bottom: 0.75rem;
}
</style>
