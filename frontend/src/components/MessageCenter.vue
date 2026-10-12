<template>
  <Popover trigger="click" placement="bottomRight" :overlayClassName="'message-center-popover'">
    <template #content>
      <div class="message-center" data-test="message-center">
        <div class="mc-header">
          <span class="mc-title">消息中心</span>
          <Button size="small" type="link" @click="refresh">刷新</Button>
        </div>
        <!-- 查询失败显性化：失败不得伪装成「暂无消息」空态（对齐列表页 error Alert 范式） -->
        <Alert
          v-if="queryError"
          type="error"
          show-icon
          :message="queryError"
          data-test="mc-error"
        />
        <div v-if="loading" class="mc-empty">加载中…</div>
        <template v-else-if="!listError">
          <div v-if="messages.length === 0" class="mc-empty" data-test="mc-empty">暂无消息</div>
          <ul v-else class="mc-list">
            <li v-for="m in messages" :key="m.id" class="mc-item" data-test="mc-item">
              <div class="mc-item-title" data-test="mc-item-title">{{ m.title }}</div>
              <div class="mc-item-content">{{ m.content }}</div>
              <div class="mc-item-foot">
                <span class="mc-item-time">{{ (m as UserMessage).createdTime ?? '' }}</span>
                <Button
                  v-if="(m as UserMessage).readFlag === 0"
                  size="small"
                  type="link"
                  data-test="mark-read-button"
                  @click="openMarkRead(requiredId(m as UserMessage))"
                >
                  标为已读
                </Button>
                <Tag v-else color="default">已读</Tag>
              </div>
            </li>
          </ul>
        </template>
      </div>
    </template>
    <span class="bell-trigger" data-test="message-bell">
      🔔
      <Badge
        v-if="!unreadError && unread > 0"
        :count="unread"
        :overflow-count="99"
        data-test="unread-badge"
        class="bee-badge"
      />
    </span>
  </Popover>
</template>

<script setup lang="ts">
/**
 * 学生消息中心（add-exam-reservation）：导航壳消息铃铛（未读数）+ 消息列表/标已读。
 *
 * 数据一律以服务端为准，**不本地持久化**未读状态（spec-delta「消息状态不在前端本地持久化」）。
 * 未读角标 = `GET /api/messages/unread-count`，列表 = `GET /api/messages`，
 * 标已读 = `PUT /api/messages/{id}/read`（成功后失效 unread-count 查询联动减少）。
 *
 * 仅登录鉴权（后端不特判角色），教师天然可拥有空消息箱。
 */
import { Alert, Badge, Button, Popover, Tag, message } from 'ant-design-vue';
import { computed, onMounted } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';

import { client, unwrap } from '@/api/apiClient';
import { list1 as listMessages, markRead, unreadCount, type UserMessage } from '@/api/axios';

const queryClient = useQueryClient();

const unreadQuery = useQuery({
  queryKey: ['messages', 'unread-count'] as const,
  queryFn: () => unwrap<number>(unreadCount({ client, throwOnError: true })),
});

const listQuery = useQuery({
  queryKey: ['messages', 'list'] as const,
  queryFn: () =>
    unwrap<UserMessage[]>(listMessages({ client, throwOnError: true, query: { limit: 100 } })),
});

const unread = computed(() => unreadQuery.data.value ?? 0);
const messages = computed(() => listQuery.data.value ?? []);
const loading = computed(() => listQuery.isLoading.value);
// 查询失败显性化：error 必须被消费，否则失败会被 data=undefined 伪装成「暂无消息」
const unreadError = computed<string | null>(() => unwrapQueryError(unreadQuery.error.value));
const listError = computed<string | null>(() => unwrapQueryError(listQuery.error.value));
const queryError = computed<string | null>(() => listError.value ?? unreadError.value);

function unwrapQueryError(error: unknown): string | null {
  if (!error) return null;
  return error instanceof Error ? error.message : '消息加载失败';
}

onMounted(() => {
  void queryClient.invalidateQueries({ queryKey: ['messages', 'unread-count'] });
});

async function refresh(): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['messages', 'list'] }),
    queryClient.invalidateQueries({ queryKey: ['messages', 'unread-count'] }),
  ]);
}

async function openMarkRead(messageId: number): Promise<void> {
  try {
    await unwrap(markRead({ client, throwOnError: true, path: { id: messageId } }));
  } catch (error) {
    message.error(error instanceof Error ? error.message : '标记已读失败，请稍后重试');
    return;
  }
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['messages', 'list'] }),
    queryClient.invalidateQueries({ queryKey: ['messages', 'unread-count'] }),
  ]);
}

/** 列表行的真实消息 id 必然存在（来自后端）；契约类型为可选，此处收敛为非空 number。 */
function requiredId(m: UserMessage): number {
  return m.id as number;
}
</script>

<style scoped>
.message-center {
  width: 320px;
  max-height: 360px;
  overflow: auto;
}
.mc-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #f0f0f0;
  padding-bottom: 4px;
  margin-bottom: 8px;
}
.mc-title {
  font-weight: 600;
}
.mc-empty {
  padding: 16px 0;
  text-align: center;
  color: #999;
}
.mc-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.mc-item {
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;
}
.mc-item-title {
  font-weight: 600;
}
.mc-item-content {
  color: #555;
}
.mc-item-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 4px;
}
.mc-item-time {
  color: #999;
  font-size: 12px;
}
.bell-trigger {
  cursor: pointer;
  font-size: 16px;
  margin-right: 12px;
}
</style>
