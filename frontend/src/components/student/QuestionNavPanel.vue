<template>
  <div class="nav-panel" data-test="nav-panel">
    <Space wrap :size="8">
      <Button
        v-for="state in states"
        :key="state.index"
        :data-test="`nav-${state.index}`"
        :data-answered="state.answered ? 'yes' : 'no'"
        :data-current="state.current ? 'yes' : 'no'"
        :data-marked="state.marked ? 'yes' : 'no'"
        :class="[
          'nav-cell',
          state.answered ? 'nav-answered' : 'nav-unanswered',
          state.current ? 'nav-current' : '',
          state.marked ? 'nav-marked' : '',
        ]"
        size="small"
        @click="emit('select', state.index)"
      >
        <span class="mark-badge" v-if="state.marked" data-test="mark-badge">🚩</span>
        {{ state.number }}
      </Button>
    </Space>
    <p class="legend text-xs text-gray-500">
      <span class="legend-cell nav-answered">已答</span>
      <span class="legend-cell nav-unanswered">未答</span>
      <span class="legend-cell nav-current">当前</span>
      <span class="legend-cell nav-marked">已标记</span>
    </p>
  </div>
</template>

<script setup lang="ts">
/**
 * 逐题导航：已答/未答/当前三态 + 疑难标记（Mark for Review 🚩）四态叠加。
 *
 * `states` 由页面用纯函数 `navStatesOf(questions, answers, index, marked)` 算出来后传入，
 * 本组件不参与"答没答 / 标没标"的判定，所以它可以被第 3 片的交卷确认（未答题数）直接复用。
 */
import { Button, Space } from 'ant-design-vue';

import type { NavItemState } from '@/utils/studentTaking';

defineProps<{ states: NavItemState[] }>();

const emit = defineEmits<{ select: [index: number] }>();
</script>

<style scoped>
.nav-cell {
  min-width: 34px;
}
.nav-answered {
  background-color: #f6ffed;
  border-color: #b7eb8f;
}
.nav-unanswered {
  background-color: #fff;
}
.nav-current {
  font-weight: 700;
  box-shadow: 0 0 0 2px #91caff;
}
/* 已标记题：橙色描边 + 右上角 🚩 角标，与已答/未答底色叠加不冲突 */
.nav-marked {
  border-color: #faad14;
}
.nav-marked.nav-answered {
  background-color: #fffbe6;
}
.mark-badge {
  position: relative;
  font-size: 11px;
  margin-right: 2px;
}
.legend {
  margin-top: 8px;
}
.legend-cell {
  display: inline-block;
  padding: 0 6px;
  margin-right: 6px;
  border: 1px solid #d9d9d9;
}
</style>
