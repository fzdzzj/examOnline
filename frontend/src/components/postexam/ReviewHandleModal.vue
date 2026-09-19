<template>
  <Modal v-model:open="open" title="处理成绩复核申请" :confirm-loading="submitting" @ok="onSubmit">
    <ReviewTimeline :review="review" />

    <Divider orientation="left" plain>处理</Divider>

    <Form :label-col="{ span: 6 }" :wrapper-col="{ span: 16 }">
      <FormItem label="处理结论" required>
        <RadioGroup v-model:value="action">
          <Radio value="AGREE">同意（可调整总分）</Radio>
          <Radio value="REJECT">驳回（维持原成绩）</Radio>
        </RadioGroup>
      </FormItem>
      <FormItem v-if="action === 'AGREE'" label="调整后总分">
        <InputNumber
          :value="adjustedTotalScore ?? undefined"
          :min="0"
          :max="300"
          :step="0.5"
          @update:value="(v) => (adjustedTotalScore = typeof v === 'number' ? v : null)"
        />
        <span class="ml-2 hint">留空表示仅认可复核、不改分</span>
      </FormItem>
      <FormItem label="处理说明" required>
        <Textarea
          v-model:value="reason"
          :rows="3"
          placeholder="处理说明（后端写入 result 字段，学生对调整结果可见）"
        />
      </FormItem>
    </Form>
  </Modal>
</template>

<script setup lang="ts">
import {
  Divider,
  Form,
  FormItem,
  InputNumber,
  Modal,
  Radio,
  RadioGroup,
  Textarea,
  message,
} from 'ant-design-vue';
import { ref, watch } from 'vue';

import { handle, type ScoreReview } from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import ReviewTimeline from '@/components/postexam/ReviewTimeline.vue';

/**
 * 教师处理复核申请：POST /api/score-reviews/{reviewId}/handle
 * body = ReviewHandleRequest{ action: AGREE|REJECT, adjustedTotalScore?, reason }
 * 处理说明由后端落 score_review.result 字段，供双方追溯。
 */

const props = defineProps<{ review: ScoreReview | null }>();
const emit = defineEmits<{ handled: [] }>();

const open = defineModel<boolean>('open', { required: true });

const action = ref<'AGREE' | 'REJECT'>('AGREE');
const adjustedTotalScore = ref<number | null>(null);
const reason = ref('');
const submitting = ref(false);

watch(
  () => props.review,
  () => {
    action.value = 'AGREE';
    adjustedTotalScore.value = null;
    reason.value = '';
  }
);

async function onSubmit(): Promise<void> {
  if (props.review?.id === undefined) return;
  if (!reason.value.trim()) {
    message.warning('处理说明必填（后端会写入 result 供学生查看）');
    return;
  }
  submitting.value = true;
  try {
    await unwrap(
      handle({
        client,
        throwOnError: true,
        path: { reviewId: props.review.id },
        body: {
          action: action.value,
          adjustedTotalScore: adjustedTotalScore.value ?? undefined,
          reason: reason.value.trim(),
        },
      })
    );
    message.success('已处理该复核申请');
    open.value = false;
    emit('handled');
  } catch (error) {
    message.error(error instanceof Error ? error.message : '处理失败');
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.ml-2 {
  margin-left: 8px;
}
.hint {
  color: #999;
  font-size: 12px;
}
</style>
