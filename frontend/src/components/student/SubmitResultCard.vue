<template>
  <div class="result-card" data-test="submit-result">
    <Alert type="success" show-icon message="交卷成功" class="mb-2" />
    <Descriptions :column="1" size="small" bordered>
      <Descriptions.Item label="答卷编号" data-test="result-submission-id">
        {{ result.submissionId ?? '—' }}
      </Descriptions.Item>
      <Descriptions.Item label="交卷时间" data-test="result-submit-time">
        {{ result.submitTime ?? '—' }}
      </Descriptions.Item>
      <Descriptions.Item label="答卷状态" data-test="result-status">
        {{ statusLabel }}
      </Descriptions.Item>
      <Descriptions.Item label="成绩">
        <span data-test="result-score-pending">
          客观题与主观题得分待教师批改后公布，本页不预估、不猜分
        </span>
      </Descriptions.Item>
    </Descriptions>
    <p class="text-xs text-gray-500 mt-2">
      交卷结果以后端返回为准；答案已由系统异步落库（MQ 削峰），无需重复提交。
    </p>
  </div>
</template>

<script setup lang="ts">
/**
 * 交卷结果卡（阶段 22 第 3 片，tasks.json 任务 5 第 4 条）。
 *
 * 如实呈现后端 `SubmitResponse` 已返回的字段（submissionId / submitTime / status）。
 * **后端交卷接口不返回任何得分字段**（已核实 `SubmitResponse`），所以这里没有
 * 「客观题即时出分」——那是后端不支持，不是前端偷懒；也不在本页引入阶段 23 的
 * 批改 / 成绩 / 复核页（硬约定 13），得分入口待成绩页立项。
 */
import { Alert, Descriptions } from 'ant-design-vue';
import { computed } from 'vue';

import type { SubmitResponse } from '@/api/axios';

const props = defineProps<{ result: SubmitResponse }>();

/** 后端 `ExamSubmission.STATUS_*`：2 = 已交卷；其它值原样显示码值，不猜语义。 */
const statusLabel = computed(() =>
  props.result.status === 2 ? '已交卷' : `后端状态码 ${props.result.status ?? '—'}`
);
</script>

<style scoped>
.mb-2 {
  margin-bottom: 0.5rem;
}
.mt-2 {
  margin-top: 0.5rem;
}
.text-xs {
  font-size: 0.75rem;
}
.text-gray-500 {
  color: #6b7280;
}
</style>
