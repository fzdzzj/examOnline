<template>
  <div class="grafana-entry" :data-state="state">
    <!-- data-state 供用例断言（unconfigured / unreachable / reachable），也便于样式区分 -->
    <Space class="mb-2" align="center">
      <span class="text-sm text-gray-500">观测面板（Grafana）只读入口</span>
      <Button size="small" :loading="checking" :disabled="!base" @click="check">
        重新检测连通性
      </Button>
    </Space>

    <!-- 已配置：给出可点的外部链接 + 连通性实况；未连通时明确说"打不开"，
         而不是渲染一个空图表冒充"面板无数据" -->
    <!-- ⚠️ ant-design-vue 4 的 Alert **只渲染 message / description**（见
         es/alert/index.js：`props.description ?? slots.description?.()`），
         默认插槽的内容会被静默丢弃。正文一律走 #description。 -->
    <Alert v-if="state === 'reachable'" type="success" show-icon>
      <template #message>
        面板可访问：
        <a :href="dashboardUrl" target="_blank" rel="noopener noreferrer">在新标签页打开 Grafana</a>
      </template>
      <template #description>
        地址 {{ base }}（只读跳转，本系统不重做面板图表，也不在这里展示任何指标数值）
      </template>
    </Alert>

    <Alert v-else-if="state === 'unreachable'" type="warning" show-icon>
      <template #message>
        面板当前
        <b>打不开</b>
        ：{{ base }} 无响应（Grafana 未启动，或端口未映射到本机）
      </template>
      <template #description>
        <p class="mb-1">
          这
          <b>不等于</b>
          「面板里查不到东西」——现在是
          <b>连接</b>
          没建立，一个指标都没问到。
        </p>
        <p class="mb-1">
          启动办法与宿主端口以编排文件为真源：
          <code>docker/observability/docker-compose.observability.yml</code>
          的
          <code>grafana</code>
          服务（该服务不随根
          <code>docker compose up</code>
          自动启动）。
        </p>
        <p class="mb-0">
          仍想手动试一次：
          <a :href="dashboardUrl" target="_blank" rel="noopener noreferrer">
            {{ dashboardUrl }}
          </a>
        </p>
      </template>
    </Alert>

    <Alert v-else type="warning" show-icon message="未配置观测面板地址，入口暂不可用">
      <template #description>
        <p class="mb-1">
          需要设置环境变量
          <code>VITE_GRAFANA_BASE_URL</code>
          （见
          <code>frontend/.env.example</code>
          ）。前端
          <b>刻意不写死</b>
          宿主端口：端口属环境事实，真源是
          <code>docker/observability/docker-compose.observability.yml</code>
          的
          <code>grafana</code>
          服务映射，抄进代码就是一份会过期的副本。
        </p>
        <p class="mb-0">未配置时本区域不展示任何图表或数值，避免被误读成「指标为空」。</p>
      </template>
    </Alert>
  </div>
</template>

<script setup lang="ts">
import { Alert, Button, Space } from 'ant-design-vue';
import { computed, onMounted, ref } from 'vue';

import {
  grafanaBaseUrl,
  grafanaDashboardUrl,
  probeGrafana,
  type GrafanaState,
} from '@/utils/grafana';

/**
 * 观测入口（阶段 21 缺口 5）：**只做只读跳转**，不内嵌 iframe、不伪造面板 URL、不画图表。
 *
 * @param baseUrl 显式指定面板基址（单测用）；不传则读 `VITE_GRAFANA_BASE_URL`
 * @param probe   显式指定连通性探测（单测用）；不传则用 `probeGrafana`
 */
const props = defineProps<{
  baseUrl?: string | null;
  probe?: (base: string) => Promise<boolean>;
}>();

const base = computed<string | null>(() => {
  // `baseUrl` 未传（undefined）才回落到环境变量；显式传空串表示"就是没配"，便于测试与嵌出降级态
  const raw = props.baseUrl === undefined ? grafanaBaseUrl() : (props.baseUrl ?? '').trim();
  return raw ? raw.replace(/\/+$/, '') : null;
});

const dashboardUrl = computed(() => (base.value ? grafanaDashboardUrl(base.value) : ''));

const state = ref<GrafanaState>('unconfigured');
const checking = ref(false);

async function check(): Promise<void> {
  const target = base.value;
  if (!target) {
    state.value = 'unconfigured';
    return;
  }
  checking.value = true;
  try {
    const probe = props.probe ?? ((url: string) => probeGrafana(url));
    state.value = (await probe(target)) ? 'reachable' : 'unreachable';
  } finally {
    checking.value = false;
  }
}

onMounted(() => {
  void check();
});

defineExpose({ check, state });
</script>

<style scoped>
.mb-2 {
  margin-bottom: 0.5rem;
}
.mb-1 {
  margin-bottom: 0.25rem;
}
.mb-0 {
  margin-bottom: 0;
}
.text-sm {
  font-size: 0.875rem;
}
.text-gray-500 {
  color: #6b7280;
}
code {
  background: #f5f5f5;
  padding: 0 2px;
  border-radius: 2px;
}
</style>
