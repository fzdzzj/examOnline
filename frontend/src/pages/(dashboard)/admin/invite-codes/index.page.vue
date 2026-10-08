<template>
  <div>
    <Card title="邀请码管理" class="mb-4">
      <template #extra>
        <Button type="primary" @click="openCreateModal">+ 生成邀请码</Button>
      </template>

      <!-- U-1 三态分离：查询失败显性呈现，表格隐藏——失败不得落「暂无数据」空态 -->
      <Alert
        v-if="queryErrorText"
        type="error"
        show-icon
        :message="queryErrorText"
        data-test="invite-codes-error"
        class="mb-3"
      />
      <Table
        v-else
        :columns="columns"
        :data-source="rows"
        :loading="isFetching"
        :pagination="false"
        :row-key="(row: InviteCodeResponse) => row.id as number"
        size="middle"
      >
        <template #emptyText>暂无数据</template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <Tag :color="isActive(record as InviteCodeResponse) ? 'green' : 'default'">
              {{ isActive(record as InviteCodeResponse) ? '有效' : '已作废' }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'createdTime'">
            {{ formatTime((record as InviteCodeResponse).createdTime) }}
          </template>
          <template v-else-if="column.key === 'actions'">
            <!-- 作废入口 fail-closed：仅有效（status=0）行出现；最终裁决在后端 -->
            <Button
              v-if="isActive(record as InviteCodeResponse)"
              type="link"
              size="small"
              danger
              @click="openInvalidateConfirm(record as InviteCodeResponse)"
            >
              作废
            </Button>
          </template>
        </template>
      </Table>
    </Card>

    <!-- 生成邀请码弹窗：成功后在同一弹层内展示新码（仅此一次可见可复制） -->
    <Modal
      v-model:open="createModalOpen"
      title="生成邀请码"
      :confirm-loading="creating"
      @ok="confirmCreate"
    >
      <template v-if="!created">
        <div class="mb-3">
          <div class="mb-1 text-gray-600">备注（可选）</div>
          <Input v-model:value="noteInput" placeholder="例如：给 2026 秋新教师" allow-clear />
        </div>
        <p class="text-sm text-gray-500">邀请码只在生成成功时展示一次，请生成后立即复制。</p>
      </template>
      <!-- 正文走 #message 具名插槽：ant-design-vue 4 的 Alert 默认插槽会被静默丢弃 -->
      <template v-else>
        <Alert type="success" show-icon>
          <template #message>
            <span>邀请码生成成功，仅此一次展示，请立即复制：</span>
          </template>
        </Alert>
        <div class="mt-2 text-lg">
          <TypographyText copyable>{{ created.code }}</TypographyText>
        </div>
      </template>
    </Modal>

    <!-- 作废确认弹窗（形态对齐「考试删除」：warning Alert 明示不可逆） -->
    <Modal
      v-model:open="invalidateModalOpen"
      title="确认作废邀请码"
      :confirm-loading="invalidating"
      ok-text="作废"
      @ok="confirmInvalidate"
    >
      <!-- 同上：正文走 #message 具名插槽，否则默认插槽被静默丢弃 -->
      <Alert type="warning" show-icon>
        <template #message>
          <p class="font-semibold">警告：此操作将作废该邀请码！</p>
          <ul class="list-disc pl-5">
            <li>作废后该邀请码不可再用于注册（已注册用户不受影响）</li>
            <li>
              <span class="font-semibold text-red-600">作废后不可恢复</span>
              （如需新的邀请码请重新生成）
            </li>
          </ul>
          <p class="mt-2 text-sm">这是不可逆操作，请谨慎执行。</p>
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
  Typography,
  message,
  type TableColumnsType,
} from 'ant-design-vue';
import dayjs from 'dayjs';
import { computed, ref } from 'vue';
import { useQuery } from '@tanstack/vue-query';

import {
  createInviteCode,
  invalidateInviteCode,
  listInviteCodes,
  type InviteCodeResponse,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { queryClient } from '@/api/queryClient';

/**
 * 邀请码管理页（前端台账 F-4 最小闭环，落既有 /admin 角色分区）。
 *
 * 后端契约：GET /api/admin/invite-codes（列表）、POST /api/admin/invite-codes（生成）、
 * POST /api/admin/invite-codes/{id}/invalidate（作废）。权限边界是后端类级
 * @RequireRole(ADMIN) + 方法级 @RequirePermission('invite:manage')，本页只是体验层入口。
 * 审计日志与强制下线在 /admin/audit-logs 页（F-4 第二阶段）。
 *
 * 口径：状态按后端返回的 status 渲染（0=有效、1=已作废），作废入口 fail-closed
 * 仅有效行出现；查询失败以 Alert 显性呈现后端 message（U-1 三态分离）不伪装空态；
 * 生成 / 作废失败 message 原文呈现，不本地编造、不拦截请求。
 */

const TypographyText = Typography.Text;

/** 后端契约：InviteCode.status —— 0=有效、1=已作废 */
const STATUS_VALID = 0;

const columns: TableColumnsType = [
  { title: 'ID', key: 'id', dataIndex: 'id', width: 70 },
  { title: '邀请码', key: 'code', dataIndex: 'code' },
  { title: '备注', key: 'note', dataIndex: 'note', ellipsis: true },
  { title: '状态', key: 'status', width: 100 },
  { title: '已使用次数', key: 'usedCount', dataIndex: 'usedCount', width: 110 },
  { title: '创建时间', key: 'createdTime', width: 170 },
  { title: '操作', key: 'actions', width: 100 },
];

function formatTime(value: string | undefined | null): string {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—';
}

function isActive(row: InviteCodeResponse): boolean {
  return row.status === STATUS_VALID;
}

const { data, error, isFetching } = useQuery({
  queryKey: ['invite-codes'],
  queryFn: () => unwrap<InviteCodeResponse[]>(listInviteCodes({ client, throwOnError: true })),
});

const rows = computed<InviteCodeResponse[]>(() => data.value ?? []);

// U-1 三态分离：查询失败显性呈现（Alert 承载后端 message），不落「暂无数据」空态
const queryErrorText = computed<string | null>(() => {
  const caught = error.value;
  if (!caught) return null;
  return caught instanceof Error ? caught.message : '邀请码列表加载失败';
});

// ===== 生成邀请码 =====
const createModalOpen = ref(false);
const creating = ref(false);
const noteInput = ref('');
const created = ref<InviteCodeResponse | null>(null);

function openCreateModal(): void {
  noteInput.value = '';
  created.value = null;
  createModalOpen.value = true;
}

async function confirmCreate(): Promise<void> {
  if (created.value) {
    // 新码已展示：本次点击只关闭弹层
    createModalOpen.value = false;
    return;
  }
  creating.value = true;
  try {
    const note = noteInput.value.trim();
    // 空备注不携带（对齐创建考试的「空描述不携带」口径）
    const result = await unwrap(
      createInviteCode({ client, throwOnError: true, body: note ? { note } : {} })
    );
    created.value = result ?? null;
    message.success('邀请码已生成');
    void queryClient.invalidateQueries({ queryKey: ['invite-codes'] });
  } catch (error) {
    message.error(error instanceof Error ? error.message : '生成邀请码失败，请稍后重试');
  } finally {
    creating.value = false;
  }
}

// ===== 作废邀请码 =====
const invalidateModalOpen = ref(false);
const invalidatingId = ref<number | undefined>(undefined);
const invalidating = ref(false);

function openInvalidateConfirm(row: InviteCodeResponse): void {
  invalidatingId.value = row.id;
  invalidateModalOpen.value = true;
}

async function confirmInvalidate(): Promise<void> {
  if (invalidatingId.value === undefined) return;
  invalidating.value = true;
  try {
    await unwrap(
      invalidateInviteCode({ client, throwOnError: true, path: { id: invalidatingId.value } })
    );
    message.success('邀请码已作废');
    invalidateModalOpen.value = false;
    void queryClient.invalidateQueries({ queryKey: ['invite-codes'] });
  } catch (error) {
    message.error(error instanceof Error ? error.message : '作废邀请码失败，请稍后重试');
  } finally {
    invalidating.value = false;
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
