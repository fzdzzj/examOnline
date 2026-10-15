<template>
  <Modal
    :open="visible"
    title="本场考试要求全屏作答"
    :closable="false"
    :mask-closable="false"
    :keyboard="false"
    :footer="null"
    :get-container="false"
    width="420px"
  >
    <div v-if="visible" data-test="fullscreen-guard-overlay">
      <Alert type="warning" show-icon :message="ANTI_CHEAT_HINT.FULLSCREEN_REQUIRED" />
      <p class="note">
        退出全屏会被记录（
        <strong>只警告、不交卷</strong>
        ）；是否处置由教师依据行为日志判定。 浏览器不允许自动进入全屏，请点击下方按钮。
      </p>
      <div class="actions">
        <Button type="primary" data-test="enter-fullscreen" @click="emit('enter')">
          进入全屏作答
        </Button>
      </div>
    </div>
  </Modal>
</template>

<script setup lang="ts">
/**
 * 全屏作答引导弹层（add-anti-cheat-restrictions 阶段 3）。
 *
 * 它只做两件事：**说明要求** + **把「进入全屏」的点击上抛**。真正的全屏请求由页面经
 * `useBehaviorReport().enterFullScreen()` 发出——浏览器只允许在用户手势里进入全屏，
 * 组件自己碰 API 会拿不到手势上下文，也没法走同一条旁路静默降级。
 *
 * 边界：不含任何交卷/锁定入口（硬约定：防作弊只警告 + 记录，绝不自动交卷）；
 * 文案里的"只警告、不交卷"是刻意写明的——避免学生把守卫理解成"已被判作弊"。
 */
import { Alert, Button, Modal } from 'ant-design-vue';

import { ANTI_CHEAT_HINT } from '@/utils/antiCheat';

defineProps<{
  /** 是否需要引导（策略开启 + 作答中 + 当前不在全屏），由作答页裁决。 */
  visible: boolean;
}>();

const emit = defineEmits<{ enter: [] }>();
</script>

<style scoped>
.note {
  margin: 12px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: #6b7280;
}
.actions {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
