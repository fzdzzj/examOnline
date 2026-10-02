<template>
  <div>
    <Card title="缺考名单" class="mb-4">
      <template #extra>
        <span class="hint">缺考由后端考试结束时统一标记，前端只呈现结果，不参与判定。</span>
      </template>

      <div class="mb-3 flex flex-wrap items-center gap-3">
        <Select
          v-model:value="selectedExamId"
          :options="examOptions"
          placeholder="选择考试"
          class="w-96"
          show-search
          :filter-option="examFilterOption"
          :loading="examsFetching"
        />
        <Button @click="() => void refetch()">刷新名单</Button>
      </div>

      <Alert type="info" show-icon class="mb-3">
        <template #description>
          <ul class="info-list">
            <li>
              两条结束路径都由后端
              <code>AbsenceService.markAbsence</code>
              幂等标记：自然到点（状态机转移）与教师强制结束（force-end）均会触发。
            </li>
            <li>
              后端返回字段只有
              <code>studentId / studentName / markedTime</code>
              ，
              <b>没有标记来源字段</b>
              ，因此本页不区分「哪条路径产生的标记」——前端不编造来源。
            </li>
          </ul>
        </template>
      </Alert>

      <Table
        :columns="columns"
        :data-source="absenceList"
        :loading="isFetching"
        :pagination="{ pageSize: 10, showSizeChanger: false }"
        :row-key="(row: AbsenceItemResponse) => row.studentId as number"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'markedTime'">
            {{ formatTime((record as AbsenceItemResponse).markedTime) }}
          </template>
          <template v-else-if="column.key === 'actions'">
            <Button
              type="link"
              size="small"
              @click="gotoMakeup((record as AbsenceItemResponse).studentId as number)"
            >
              为该生创建补考
            </Button>
          </template>
        </template>
        <template #emptyText>该考试没有缺考记录（或考试尚未结束）</template>
      </Table>
    </Card>
  </div>
</template>

<script setup lang="ts">
import { Alert, Button, Card, Select, Table, type TableColumnsType } from 'ant-design-vue';
import dayjs from 'dayjs';
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useQuery } from '@tanstack/vue-query';

import {
  absences,
  page2 as pageExams,
  type AbsenceItemResponse,
  type ExamResponse,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { createTeacherExamsQueryOptions } from '@/hooks/useTeacherExams';

/**
 * 缺考名单（阶段 23）：GET /api/exams/{id}/absences。
 * 标记动作全在后端（自然到点 / force-end 两条路径统一走 markAbsence，INSERT IGNORE + 唯一索引幂等）。
 * 后端响应不含来源字段，故本页不做来源区分（接口事实如此，不编造）。
 */

const router = useRouter();

const columns: TableColumnsType = [
  { title: '学生 ID', key: 'studentId', dataIndex: 'studentId', width: 100 },
  { title: '姓名', key: 'studentName', dataIndex: 'studentName', width: 140 },
  { title: '标记时间', key: 'markedTime', width: 200 },
  { title: '操作', key: 'actions', width: 160 },
];

function formatTime(value: string | undefined): string {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—';
}

const { data: examsData, isFetching: examsFetching } = useQuery(
  createTeacherExamsQueryOptions((page, size) =>
    unwrap<ExamResponse[]>(pageExams({ client, throwOnError: true, query: { page, size } }))
  )
);
const exams = computed<ExamResponse[]>(() => examsData.value ?? []);
const examOptions = computed(() =>
  exams.value.map((e) => ({ value: e.id as number, label: `#${e.id} ${e.title}` }))
);
function examFilterOption(input: string, option?: unknown): boolean {
  const label = (option as { label?: unknown } | undefined)?.label;
  return String(label ?? '')
    .toLowerCase()
    .includes(input.toLowerCase());
}

const selectedExamId = ref<number | undefined>(undefined);

const { data, isFetching, refetch } = useQuery({
  queryKey: computed(() => ['absences', selectedExamId.value] as const),
  queryFn: () =>
    unwrap<AbsenceItemResponse[]>(
      absences({ client, throwOnError: true, path: { id: selectedExamId.value as number } })
    ),
  enabled: computed(() => selectedExamId.value !== undefined),
});

const absenceList = computed<AbsenceItemResponse[]>(() => data.value ?? []);

function gotoMakeup(studentId: number): void {
  void router.push({
    path: '/teacher/makeups',
    query: { examId: String(selectedExamId.value), studentIds: String(studentId) },
  });
}
</script>

<style scoped>
.mb-3 {
  margin-bottom: 0.75rem;
}
.mb-4 {
  margin-bottom: 1rem;
}
.hint {
  color: #999;
  font-size: 12px;
  font-weight: normal;
  max-width: 420px;
}
.info-list {
  margin: 0;
  padding-left: 18px;
}
</style>
