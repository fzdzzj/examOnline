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
      <!-- M-05 不可逆防呆：同意且填入调分时，显性 warning 警示「覆写最终得分且不可逆」 -->
      <Alert
        v-if="action === 'AGREE' && adjustedTotalScore !== null"
        type="warning"
        show-icon
        message="调整总分将直接覆写该场考试的学生最终得分且不可逆，请核对准确"
        data-test="review-adjust-warning"
        class="mb-3"
      />
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
  Alert,
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
 *
 * M-05 防呆：AGREE + 填入调分 → 后端将直接覆写答卷最终得分，不可撤销，
 * 提交前必须经 Modal.confirm 二次知情确认，杜绝手误直接覆写成绩。
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

/** M-05 二次知情确认：仅「同意且调分」路径需要，驳回/仅认可复核不改分直接提交。 */
async function onSubmit(): Promise<void> {
  if (props.review?.id === undefined) return;
  if (!reason.value.trim()) {
    message.warning('处理说明必填（后端会写入 result 供学生查看）');
    return;
  }
  const adjusting = action.value === 'AGREE' && adjustedTotalScore.value !== null;
  if (adjusting) {
    // ant-design-vue 4 的 Modal.confirm 返回 ModalFunc（onOk 回调式）：
    // 确认按钮触发 onOk → doSubmit；取消/关闭则不发起任何请求
    Modal.confirm({
      title: '确认调整总分？',
      content: '调整总分将直接覆写该场考试的学生最终得分且不可逆，请核对准确后确认。',
      okText: '确认调整',
      cancelText: '取消',
      onOk: () => doSubmit(),
    });
    return;
  }
  await doSubmit();
}

async function doSubmit(): Promise<void> {
  if (props.review?.id === undefined) return;
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
