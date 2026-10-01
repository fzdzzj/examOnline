<template>
  <div class="nav-panel" data-test="nav-panel">
    <Space wrap :size="8">
      <Button
        v-for="state in states"
        :key="state.index"
        :data-test="`nav-${state.index}`"
        :data-answered="state.answered ? 'yes' : 'no'"
        :data-current="state.current ? 'yes' : 'no'"
        :class="[
          'nav-cell',
          state.answered ? 'nav-answered' : 'nav-unanswered',
          state.current ? 'nav-current' : '',
        ]"
        size="small"
        @click="emit('select', state.index)"
      >
        {{ state.number }}
      </Button>
    </Space>
    <p class="legend text-xs text-gray-500">
      <span class="legend-cell nav-answered">已答</span>
      <span class="legend-cell nav-unanswered">未答</span>
      <span class="legend-cell nav-current">当前</span>
    </p>
  </div>
</template>

<script setup lang="ts">
/**
 * 逐题导航（阶段 22 第 1 片：极简版——只有已答/未答/当前三态）。
 *
 * `states` 由页面用纯函数 `navStatesOf(questions, answers, index)` 算出来后传入，
 * 本组件不参与"答没答"的判定，所以它可以被第 3 片的交卷确认（未答题数）直接复用。
 *
 * 注意：spec 的导航面板还有"标记题"一色，那依赖后端 `marked` 字段与草稿保存，
 * 属第 2 片范围（v3 明确**不做**标记备注的编辑能力，只可能显示后端已存的标记）。
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
