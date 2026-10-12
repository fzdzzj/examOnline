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
          <Space>
            <Button
              v-if="reservationActionOf(record as ExamListItem).showReserve"
              size="small"
              data-test="reserve-button"
              :disabled="reservationActionsDisabled"
              @click="emit('reserve', record as ExamListItem)"
            >
              预约
            </Button>
            <Popconfirm
              v-else-if="reservationActionOf(record as ExamListItem).showCancel"
              title="取消后需重新预约，确认取消？"
              @confirm="emit('cancel', record as ExamListItem)"
            >
              <Button
                size="small"
                danger
                data-test="cancel-reservation-button"
                :disabled="reservationActionsDisabled"
              >
                取消预约
              </Button>
            </Popconfirm>
            <Tag
              v-else-if="reservationActionOf(record as ExamListItem).showReservedTag"
              color="green"
              data-test="reserved-tag"
            >
              已预约
            </Tag>
            <Button
              type="primary"
              size="small"
              data-test="enter-button"
              :disabled="!canEnterOf(record as ExamListItem)"
              @click="emit('enter', record as ExamListItem)"
            >
              {{ (record as ExamListItem).group === EXAM_GROUP.ONGOING ? '继续作答' : '进入考试' }}
            </Button>
          </Space>
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
import {
  Alert,
  Button,
  Popconfirm,
  Space,
  Table,
  Tag,
  type TableColumnsType,
} from 'ant-design-vue';
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
    /**
     * 离线门控（U-8）：离线（含离线降级态）时禁用「预约」/「取消预约」入口。
     * 只作用于预约类入口——「进入考试/继续作答」仍只认后端 canEnter，不因离线禁用。
     */
    reservationActionsDisabled?: boolean;
  }>(),
  { loading: false, error: null, reservationActionsDisabled: false }
);

const emit = defineEmits<{
  enter: [item: ExamListItem];
  /** 预约制考试开考前：学生点「预约」 */
  reserve: [item: ExamListItem];
  /** 预约制考试开考前：学生点「取消预约」 */
  cancel: [item: ExamListItem];
}>();

/**
 * 预约入口三态（add-exam-reservation）：只对「需预约」考试呈现，且仅开考前（group=UPCOMING）。
 * - 未预约 → 预约按钮；
 * - 已预约且开考后 → 「已预约」只读 Tag（锁定，不可取消）；
 * - 已预约且未开始 → 取消预约按钮；
 * - 开考后未预约 → 不呈现预约/取消（名单已锁定）。
 * 三态判定只用后端下发的 reservationRequired / reserved 与 group，不做本地时间推断。
 */
interface ReservationAction {
  showReserve: boolean;
  showCancel: boolean;
  showReservedTag: boolean;
}

function reservationActionOf(item: ExamListItem): ReservationAction {
  if (item.reservationRequired !== true) {
    return { showReserve: false, showCancel: false, showReservedTag: false };
  }
  const upcoming = item.group === EXAM_GROUP.UPCOMING;
  const reserved = item.reserved === true;
  return {
    showReserve: upcoming && !reserved,
    showCancel: upcoming && reserved,
    showReservedTag: !upcoming && reserved,
  };
}

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
