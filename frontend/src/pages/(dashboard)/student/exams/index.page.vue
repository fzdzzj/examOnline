<template>
  <div>
    <Card title="我的考试">
      <template #extra>
        <Tag v-if="isFallbackActive" color="warning" data-test="offline-cache-badge">
          {{ OFFLINE_LIST_NOTICE.BADGE }}
        </Tag>
        <Button size="small" :loading="isFetching" @click="refetch()">刷新</Button>
      </template>

      <Alert
        v-if="isFallbackActive"
        type="warning"
        show-icon
        :message="OFFLINE_LIST_NOTICE.ALERT_MESSAGE"
        :description="OFFLINE_LIST_NOTICE.ALERT_DESCRIPTION"
        data-test="offline-list-banner"
        class="mb-3"
      />

      <StudentExamList :items="rows" :loading="isFetching" :error="errorText" @enter="onEnter" />
    </Card>
  </div>
</template>

<script setup lang="ts">
/**
 * 学生端考试列表（阶段 22 第 1 片 / 创新点 5 二期 · 阶段 4）。
 *
 * 后端契约：`GET /api/exam-taking/exams` → `ExamListItem[]`（`ExamTakingService.myExams`）。
 * 该接口已经把「待考 / 进行中 / 已完成」分好组并算好 `canEnter` 与 `remainingSeconds`，
 * 所以本页**不**做任何按时间窗的二次过滤或分组——分组口径只有一处（后端）。
 *
 * 离线降级与 SW 注册域（阶段 4）：
 * 1. 学生考试域（列表 + 作答页）挂载即注册 SW；离开学生考试域时 unregister；
 * 2. 联网成功获取列表时，自动将列表与当前登录用户 userId 持久化至本地缓存；
 * 3. 断网且请求失败时，若存在当前用户的缓存则降级渲染并显性标注「离线缓存」；
 *    若已换号则坚决不串用他人缓存，维持既有错误态。
 */
import { Alert, Button, Card, Tag } from 'ant-design-vue';
import { computed, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave, useRouter } from 'vue-router';
import { useQuery } from '@tanstack/vue-query';
import { useStore } from 'vuex';

import { myExams as myExamsContract, type ExamListItem } from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import StudentExamList from '@/components/student/StudentExamList.vue';
import { createStudentExamsQueryOptions } from '@/hooks/useStudentTaking';
import { ApiError } from '@/api/types';
import { createStudentExamListCacheStorage } from '@/utils/examListCacheStorage';
import { isStudentExamRoute, registerExamServiceWorker, unregisterExamServiceWorker } from '@/utils/swRegister';
import { OFFLINE_LIST_NOTICE } from '@/constants/studentTaking';

const router = useRouter();
const store = useStore();
const currentUserId = computed<number | undefined>(() => store.state.user?.id);

const listCacheStorage = createStudentExamListCacheStorage();
const cachedRows = ref<ExamListItem[]>([]);

onMounted(() => {
  void registerExamServiceWorker();
});

onBeforeRouteLeave((to) => {
  if (!isStudentExamRoute(to.path)) {
    void unregisterExamServiceWorker();
  }
});

const { data, isFetching, error, refetch } = useQuery(
  createStudentExamsQueryOptions(() =>
    unwrap<ExamListItem[]>(myExamsContract({ client, throwOnError: true }))
  )
);

async function syncLocalListCache(): Promise<void> {
  if (typeof currentUserId.value !== 'number') {
    cachedRows.value = [];
    return;
  }
  const cached = await listCacheStorage.load(currentUserId.value);
  if (cached) {
    cachedRows.value = cached.items;
  } else {
    cachedRows.value = [];
  }
}

watch(
  currentUserId,
  () => {
    void syncLocalListCache();
  },
  { immediate: true }
);

// 联网请求成功时自动更新缓存
watch(
  data,
  (val) => {
    if (val && typeof currentUserId.value === 'number') {
      void listCacheStorage.save(currentUserId.value, val);
    }
  },
  { immediate: true }
);

const isOffline = computed<boolean>(() => {
  return typeof navigator !== 'undefined' ? !navigator.onLine : false;
});

const isFallbackActive = computed<boolean>(() => {
  return Boolean(
    error.value &&
    isOffline.value &&
    cachedRows.value.length > 0
  );
});

watch(
  () => [Boolean(error.value), isOffline.value] as const,
  ([hasError, offline]) => {
    if (hasError && offline) {
      void syncLocalListCache();
    }
  }
);

const rows = computed<ExamListItem[]>(() => {
  if (data.value) return data.value;
  if (isFallbackActive.value) return cachedRows.value;
  return [];
});

/**
 * 失败就如实报错，不降级成空列表：
 * 「暂无考试」和「拉不到考试」是两件事，混起来会把后端故障伪装成学生没试可考。
 */
const errorText = computed<string | null>(() => {
  if (isFallbackActive.value) return null;
  const caught = error.value;
  if (!caught) return null;
  return caught instanceof ApiError || caught instanceof Error
    ? caught.message
    : '考试列表加载失败';
});

function onEnter(item: ExamListItem): void {
  if (item.examId === undefined) return;
  void router.push(`/student/exams/${item.examId}`);
}
</script>

<style scoped>
.mb-3 {
  margin-bottom: 0.75rem;
}
</style>
