<template>
  <div class="submit-panel" data-test="submit-panel">
    <Alert
      v-if="phase === 'failed' && error"
      type="error"
      show-icon
      :message="failureTitle"
      :description="error.message"
      data-test="submit-error"
      class="mb-2"
    >
      <template #action>
        <Button
          size="small"
          danger
          :disabled="submitting"
          data-test="submit-retry"
          @click="emit('retry')"
        >
          重试交卷
        </Button>
      </template>
    </Alert>

    <Space>
      <Button
        type="primary"
        :loading="submitting"
        :disabled="submitting || phase === 'submitted'"
        data-test="submit-button"
        @click="confirmOpen = true"
      >
        {{ submitting ? '正在交卷…' : '交卷' }}
      </Button>
      <span class="text-xs text-gray-500" data-test="submit-hint">
        {{
          submitting
            ? '已提交到服务器，等待结果，请勿关闭页面'
            : '交卷后不能再作答，确认前请检查导航面板的未答题'
        }}
      </span>
    </Space>

    <Modal
      v-model:open="confirmOpen"
      title="确认交卷"
      :confirm-loading="submitting"
      ok-text="确认交卷"
      cancel-text="再检查一下"
      :ok-button-props="{ danger: true, disabled: submitting }"
      data-test="submit-confirm"
      @ok="onConfirm"
    >
      <p data-test="confirm-unanswered">
        <template v-if="unansweredCount > 0">
          还有
          <b>{{ unansweredCount }}</b>
          道题未作答（共 {{ totalQuestions }} 题）。未答题按空卷计分。
        </template>
        <template v-else>全部 {{ totalQuestions }} 题均已作答。</template>
      </p>
      <p class="text-xs text-gray-500">
        交卷以服务器结果为准：成功后不能再修改；若网络失败答案会保留，可重试。
      </p>
    </Modal>
  </div>
</template>

<script setup lang="ts">
/**
 * 交卷面板（阶段 22 第 3 片）——纯展示 + 事件回抛，不发请求。
 *
 * 硬约定 4/5 落到 UI 的形态：
 * - 二次确认弹窗展示**未答题数**（页面用 `navStatesOf` 算，与导航面板同一份数字）；
 * - 确认后回调页面 `submitExam`；`submitting` 期间按钮 loading + 禁用（连点只发一次
 *   的 hook 层守卫在这里有可见对应物）；
 * - 失败 Alert 常驻 + 重试按钮：**不清空、不退出、答案还在页面上**，绝不把失败画成成功。
 * 交卷结果展示在 `SubmitResultCard`，与本组件互斥出现。
 */
import { Alert, Button, Modal, Space } from 'ant-design-vue';
import { computed, ref } from 'vue';

import type { SubmitError, SubmitPhase } from '@/hooks/useSubmitExam';

const props = defineProps<{
  unansweredCount: number;
  totalQuestions: number;
  submitting: boolean;
  phase: SubmitPhase;
  error: SubmitError | null;
}>();

const emit = defineEmits<{ submit: []; retry: [] }>();

const confirmOpen = ref(false);

const failureTitle = computed(() => {
  switch (props.error?.kind) {
    case 'conflict':
      return '服务器正忙于处理你的提交，请稍候重试';
    case 'rate_limited':
      return '交卷请求被限流，请稍候重试';
    case 'server':
      return '服务器异常：答案已在服务器暂存，可重试或稍后重进查看';
    case 'network':
      return '网络异常，交卷未送达';
    default:
      return '交卷失败';
  }
});

function onConfirm(): void {
  if (props.submitting) return;
  confirmOpen.value = false;
  emit('submit');
}
</script>

<style scoped>
.mb-2 {
  margin-bottom: 0.5rem;
}
.text-xs {
  font-size: 0.75rem;
}
.text-gray-500 {
  color: #6b7280;
}
</style>
