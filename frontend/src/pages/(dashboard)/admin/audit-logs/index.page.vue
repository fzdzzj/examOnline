<template>
  <div>
    <Card title="安全审计日志" class="mb-4">
      <div class="mb-3 flex flex-wrap items-center gap-2">
        <!-- 动作筛选为文本输入而非下拉：契约里 action 是裸 string（无枚举），后端走精确匹配 -->
        <Input v-model:value="usernameFilter" placeholder="搜索用户名" allow-clear class="w-48" />
        <Input
          v-model:value="actionFilter"
          placeholder="按动作筛选（如 LOGIN / ACCOUNT_LOCKED）"
          allow-clear
          class="w-64"
        />
      </div>

      <!-- U-1 三态分离：查询失败显性呈现，表格隐藏——失败不得落「暂无数据」空态 -->
      <Alert
        v-if="queryErrorText"
        type="error"
        show-icon
        :message="queryErrorText"
        data-test="audit-logs-error"
        class="mb-3"
      />
      <Table
        v-else
        :columns="columns"
        :data-source="rows"
        :loading="isFetching"
        :pagination="tablePagination"
        :row-key="(row: AuditLogResponse) => row.id as number"
        size="middle"
        @change="onTableChange"
      >
        <template #emptyText>暂无数据</template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <!-- 只按返回值上色，文案一律用后端原串，前端不翻译不推算 -->
            <Tag :color="statusColor((record as AuditLogResponse).status)">
              {{ (record as AuditLogResponse).status }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'createdTime'">
            {{ formatTime((record as AuditLogResponse).createdTime) }}
          </template>
          <template v-else-if="column.key === 'actions'">
            <!-- 下线入口 fail-closed：仅携带 userId 的行出现（账户锁定等系统事件无对象可踢）；
                 能否踢由后端 user:manage 裁决，越权失败原文呈现 -->
            <Button
              v-if="isKickable(record as AuditLogResponse)"
              type="link"
              size="small"
              danger
              @click="openKickConfirm(record as AuditLogResponse)"
            >
              强制下线
            </Button>
          </template>
        </template>
      </Table>
    </Card>

    <!-- 强制下线确认弹窗（形态对齐考试删除 / 邀请码作废：warning Alert 明示会话立即失效） -->
    <Modal
      v-model:open="kickModalOpen"
      title="确认强制下线"
      :confirm-loading="kicking"
      ok-text="强制下线"
      @ok="confirmKick"
    >
      <!-- 正文走 #message 具名插槽：ant-design-vue 4 的 Alert 默认插槽会被静默丢弃 -->
      <Alert type="warning" show-icon>
        <template #message>
          <p class="font-semibold">警告：此操作将立即断开该用户的全部会话！</p>
          <ul class="list-disc pl-5">
            <li>
              用户
              <span class="font-semibold">{{ kickingUsername }}</span>
              的
              <span class="font-semibold text-red-600">所有会话立即失效，需重新登录</span>
              才能继续使用
            </li>
            <li>正在作答的答卷不受影响（按最后一次自动保存保留），但会话断开后需重新进入</li>
            <li>已断开的会话无法恢复，只能由该用户重新登录</li>
          </ul>
          <p class="mt-2 text-sm">请确认目标无误后再执行。</p>
        </template>
      </Alert>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import {
  Alert,
  Button,
  Card,
  Input,
  Modal,
  Table,
  Tag,
  message,
  type TableColumnsType,
} from 'ant-design-vue';
import dayjs from 'dayjs';
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';

import { auditLogs, kickUser, type AuditLogResponse } from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { queryClient } from '@/api/queryClient';

/**
 * 安全审计日志页（前端台账 F-4 第二阶段，落既有 /admin 角色分区）。
 *
 * 后端契约：GET /api/admin/audit-logs（page/size/username/action，返回页内数组）、
 * POST /api/admin/users/{userId}/kick（目标用户全端下线）。权限边界是后端类级
 * @RequireRole(ADMIN)（审计查询）+ 方法级 @RequirePermission('user:manage')（踢人），
 * 本页只是体验层入口。
 *
 * 口径：action/status 按后端返回字符串直渲染，前端不维护第二套语义；响应信封无 total，
 * 分页只做「满页即至少还有下一页」的下界推断（范式抄 teacher/papers 页）；筛选走服务端
 * 参数 + 300ms 防抖 + 变更回第 1 页，空值不携带；查询失败以 Alert 显性呈现后端 message，
 * 不伪装空态；下线失败 message 原文呈现，不本地编造、不拦截请求。
 */

/** 单页条数取后端默认值（上限 100 由后端 @Max 与 Math.min 双兜底，前端不自行放大） */
const DEFAULT_PAGE_SIZE = 20;

const columns: TableColumnsType = [
  { title: 'ID', key: 'id', dataIndex: 'id', width: 70 },
  { title: '用户名', key: 'username', dataIndex: 'username', width: 140 },
  { title: '动作', key: 'action', dataIndex: 'action', width: 150 },
  { title: '状态', key: 'status', width: 100 },
  { title: '链路 ID', key: 'traceId', dataIndex: 'traceId', width: 200, ellipsis: true },
  { title: 'IP 地址', key: 'ipAddress', dataIndex: 'ipAddress', width: 140 },
  { title: '时间', key: 'createdTime', width: 150 },
  { title: '详情', key: 'details', dataIndex: 'details', ellipsis: true },
  { title: '操作', key: 'actions', width: 110 },
];

function formatTime(value: string | undefined | null): string {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—';
}

/** 状态只据后端原串取色，未知取值回落 default，不做本地推断 */
function statusColor(status: string | undefined): string {
  switch (status) {
    case 'SUCCESS':
      return 'green';
    case 'FAILURE':
      return 'red';
    case 'WARNING':
      return 'orange';
    default:
      return 'default';
  }
}

/** 可下线判定：契约里 userId 是可选装载，系统事件行运行时为 null——typeof 一判两挡 */
function isKickable(row: AuditLogResponse): boolean {
  return typeof row.userId === 'number';
}

const pageNum = ref(1);
const pageSize = ref(DEFAULT_PAGE_SIZE);
const usernameFilter = ref('');
const actionFilter = ref('');
const debouncedUsername = ref('');
const debouncedAction = ref('');

let debounceTimer: ReturnType<typeof setTimeout> | null = null;
watch([usernameFilter, actionFilter], () => {
  if (debounceTimer !== null) clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => {
    debouncedUsername.value = usernameFilter.value.trim();
    debouncedAction.value = actionFilter.value.trim();
    pageNum.value = 1;
  }, 300);
});

onBeforeUnmount(() => {
  if (debounceTimer !== null) clearTimeout(debounceTimer);
});

const { data, error, isFetching, refetch } = useQuery({
  queryKey: computed(
    () =>
      [
        'audit-logs',
        pageNum.value,
        pageSize.value,
        debouncedUsername.value,
        debouncedAction.value,
      ] as const
  ),
  queryFn: () =>
    unwrap<AuditLogResponse[]>(
      auditLogs({
        client,
        throwOnError: true,
        query: {
          page: pageNum.value,
          size: pageSize.value,
          // 空筛选值不携带该参数（对齐「空描述不携带」既有口径）
          ...(debouncedUsername.value ? { username: debouncedUsername.value } : {}),
          ...(debouncedAction.value ? { action: debouncedAction.value } : {}),
        },
      })
    ),
});

const rows = computed<AuditLogResponse[]>(() => data.value ?? []);

// U-1 三态分离：查询失败显性呈现（Alert 承载后端 message），不落「暂无数据」空态
const queryErrorText = computed<string | null>(() => {
  const caught = error.value;
  if (!caught) return null;
  return caught instanceof Error ? caught.message : '审计日志加载失败';
});

const tablePagination = computed(() => ({
  current: pageNum.value,
  pageSize: pageSize.value,
  // 信封无 total（响应是页内数组），只能下界推断：满页即「至少还有下一页」，末页不预留。
  // 不照抄考试页的字面 `total: pageNum * pageSize`——按 antd 的
  // calculatePage = floor((total-1)/pageSize)+1，那写法在满页时算出 1 页，下一页根本点不到。
  total:
    (pageNum.value - 1) * pageSize.value +
    rows.value.length +
    (rows.value.length === pageSize.value ? 1 : 0),
  showSizeChanger: false,
}));

function onTableChange(pag: { current?: number; pageSize?: number }): void {
  if (typeof pag.current === 'number') pageNum.value = pag.current;
  if (typeof pag.pageSize === 'number') pageSize.value = pag.pageSize;
  void refetch();
}

// ===== 强制下线 =====
const kickModalOpen = ref(false);
const kickingUserId = ref<number | undefined>(undefined);
const kickingUsername = ref('');
const kicking = ref(false);

function openKickConfirm(row: AuditLogResponse): void {
  if (!isKickable(row)) return;
  kickingUserId.value = row.userId;
  kickingUsername.value = row.username ?? String(row.userId);
  kickModalOpen.value = true;
}

async function confirmKick(): Promise<void> {
  if (kickingUserId.value === undefined) return;
  kicking.value = true;
  try {
    await unwrap(kickUser({ client, throwOnError: true, path: { userId: kickingUserId.value } }));
    message.success('已强制下线，该用户需重新登录');
    kickModalOpen.value = false;
    void queryClient.invalidateQueries({ queryKey: ['audit-logs'] });
  } catch (error) {
    message.error(error instanceof Error ? error.message : '强制下线失败，请稍后重试');
  } finally {
    kicking.value = false;
  }
}
</script>

<style scoped>
.mb-3 {
  margin-bottom: 0.75rem;
}
.mb-4 {
  margin-bottom: 1rem;
}
</style>
