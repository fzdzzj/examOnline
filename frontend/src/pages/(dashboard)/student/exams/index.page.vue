<template>
  <div>
    <Card title="我的考试">
      <template #extra>
        <Button size="small" :loading="isFetching" @click="refetch()">刷新</Button>
      </template>

      <StudentExamList :items="rows" :loading="isFetching" :error="errorText" @enter="onEnter" />
    </Card>
  </div>
</template>

<script setup lang="ts">
/**
 * 学生端考试列表（阶段 22 第 1 片）。
 *
 * 后端契约：`GET /api/exam-taking/exams` → `ExamListItem[]`（`ExamTakingService.myExams`）。
 * 该接口已经把「待考 / 进行中 / 已完成」分好组并算好 `canEnter` 与 `remainingSeconds`，
 * 所以本页**不**做任何按时间窗的二次过滤或分组——分组口径只有一处（后端）。
 *
 * 「进入考试」入口只负责跳进作答页；**拉个人快照由作答页挂载时发
 * `POST /api/exam-taking/exams/{examId}/enter` 完成**（后端幂等，重复进入返回同一快照），
 * 这样刷新、断线重连、从列表再点进来三条路径拿到的是同一份题集。
 * 本页也不本地记「我已经进过」——那属前端替后端判定会话状态（硬约定 1/3）。
 */
import { Button, Card } from 'ant-design-vue';
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { useQuery } from '@tanstack/vue-query';

import { myExams as myExamsContract, type ExamListItem } from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import StudentExamList from '@/components/student/StudentExamList.vue';
import { createStudentExamsQueryOptions } from '@/hooks/useStudentTaking';
import { ApiError } from '@/api/types';

const router = useRouter();

const { data, isFetching, error, refetch } = useQuery(
  createStudentExamsQueryOptions(() =>
    unwrap<ExamListItem[]>(myExamsContract({ client, throwOnError: true }))
  )
);

const rows = computed<ExamListItem[]>(() => data.value ?? []);

/**
 * 失败就如实报错，不降级成空列表：
 * 「暂无考试」和「拉不到考试」是两件事，混起来会把后端故障伪装成学生没试可考。
 */
const errorText = computed<string | null>(() => {
  const caught = error.value;
  if (!caught) return null;
  return caught instanceof ApiError || caught instanceof Error
    ? caught.message
    : '考试列表加载失败';
});

function onEnter(item: ExamListItem): void {
  if (item.examId === undefined) return;
  void router.push(`/student/exams/${item.examId}`);
}
</script>
