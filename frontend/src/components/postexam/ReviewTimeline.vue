<template>
  <div>
    <Steps
      v-if="review"
      :current="currentStep"
      :status="getReviewStatusConfig(review.status ?? -1).ongoing ? 'process' : 'finish'"
      size="small"
      direction="horizontal"
    >
      <Step title="已提交申请" :description="review.applyTime ?? ''" />
      <Step title="教师处理中" description="后端受理后进入处理中" />
      <Step :title="resultTitle" :description="review.handleTime ?? ''" />
    </Steps>

    <Descriptions v-if="review" bordered :column="1" size="small" class="mt-3">
      <DescriptionsItem label="当前状态">
        <Tag :color="getReviewStatusConfig(review.status ?? -1).color">
          {{ getReviewStatusConfig(review.status ?? -1).label }}
        </Tag>
      </DescriptionsItem>
      <DescriptionsItem label="申请理由">{{ review.reason || '（未填写）' }}</DescriptionsItem>
      <DescriptionsItem v-if="review.result" label="处理说明">{{ review.result }}</DescriptionsItem>
      <DescriptionsItem v-if="review.handlerId !== undefined" label="处理人">
        {{ review.handlerId }}
      </DescriptionsItem>
    </Descriptions>
  </div>
</template>

<script setup lang="ts">
import { Step, Steps, Tag, Descriptions, DescriptionsItem } from 'ant-design-vue';
import { computed } from 'vue';

import type { ScoreReview } from '@/api/axios';
import { REVIEW_STATUS, getReviewStatusConfig } from '@/constants/postExam';

/**
 * 复核流程时间线：状态直接取后端 score_review.status（ScoreReview.STATUS_*），
 * 结果与调整说明取后端 result 字段（ReviewHandleRequest.reason 由后端落 result）。
 */

const props = defineProps<{ review: ScoreReview | null }>();

const currentStep = computed(() => {
  const status = props.review?.status ?? -1;
  if (status === REVIEW_STATUS.PENDING) return 0;
  if (status === REVIEW_STATUS.PROCESSING) return 1;
  return 2;
});

const resultTitle = computed(() => {
  const status = props.review?.status ?? -1;
  if (status === REVIEW_STATUS.AGREED) return '已同意（成绩已调整）';
  if (status === REVIEW_STATUS.REJECTED) return '已驳回（维持原成绩）';
  return '处理完成';
});
</script>

<style scoped>
.mt-3 {
  margin-top: 0.75rem;
}
</style>
