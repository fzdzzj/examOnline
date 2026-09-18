<template>
  <div>
    <Card title="新建标签" class="mb-4">
      <div class="flex flex-wrap items-center gap-2">
        <Input
          v-model:value="draftName"
          :maxlength="64"
          placeholder="标签名（最长 64 字）"
          class="w-64"
        />
        <Select
          v-model:value="draftType"
          :options="TAG_TYPE_OPTIONS"
          placeholder="标签类型"
          class="w-40"
        />
        <Button type="primary" :loading="creating" @click="onCreate">创建</Button>
      </div>
      <p class="mb-0 mt-2 text-gray-400">
        标签为扁平四类（学科 / 难度 / 题型 / 自定义）；同类型下重名会被后端拒绝。
      </p>
    </Card>

    <Card>
      <template #title>
        <div class="flex items-center gap-3">
          <span>标签列表</span>
          <Select
            v-model:value="typeFilter"
            :options="tagFilterOptions"
            class="w-36"
            size="small"
          />
        </div>
      </template>
      <Table
        :columns="columns"
        :data-source="rows"
        :loading="isFetching"
        :pagination="false"
        :row-key="(row: TagResponse) => row.id as number"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'type'">
            <Tag color="blue">{{ tagTypeLabelOf(record.type) }}</Tag>
          </template>
          <template v-else-if="column.key === 'actions'">
            <Popconfirm title="删除标签会同时清理题目关联，确认删除？" @confirm="onDelete(record)">
              <Button type="link" size="small" danger>删除</Button>
            </Popconfirm>
          </template>
        </template>
      </Table>
      <!-- 说明：TagController 仅提供 创建 / 列表 / 删除 三个端点（无更新），
           界面严格按后端现有能力实现，不扩字段、不扩表。 -->
    </Card>
  </div>
</template>

<script setup lang="ts">
import {
  Button,
  Card,
  Input,
  Popconfirm,
  Select,
  Table,
  Tag,
  message,
  type TableColumnsType,
} from 'ant-design-vue';
import { computed, ref } from 'vue';
import { useQuery } from '@tanstack/vue-query';

import {
  create as createTag,
  delete4 as deleteTag,
  list as listTags,
  type TagResponse,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { queryClient } from '@/api/queryClient';
import { TAG_TYPE_OPTIONS, tagTypeLabelOf } from '@/utils/questionTypes';

const columns: TableColumnsType = [
  { title: '标签名', key: 'name' },
  { title: '类型', key: 'type', width: 140 },
  { title: '操作', key: 'actions', width: 100 },
];

const typeFilter = ref<string>('');

const tagFilterOptions = [{ value: '', label: '全部类型' }, ...TAG_TYPE_OPTIONS];

const { data, isFetching } = useQuery({
  queryKey: computed(() => ['tags', typeFilter.value] as const),
  queryFn: () =>
    unwrap<TagResponse[]>(
      listTags({
        client,
        throwOnError: true,
        // 后端 type 为可选参数；空串表示全部，不下发
        ...(typeFilter.value ? { query: { type: typeFilter.value } } : {}),
      })
    ),
});

const rows = computed(() => data.value ?? []);

const draftName = ref('');
const draftType = ref<string | undefined>(undefined);
const creating = ref(false);

async function onCreate(): Promise<void> {
  const name = draftName.value.trim();
  if (!name) {
    message.warning('请输入标签名');
    return;
  }
  if (!draftType.value) {
    message.warning('请选择标签类型');
    return;
  }
  creating.value = true;
  try {
    await unwrap(createTag({ client, throwOnError: true, body: { name, type: draftType.value } }));
    message.success('标签已创建');
    draftName.value = '';
    draftType.value = undefined;
    void queryClient.invalidateQueries({ queryKey: ['tags'] });
  } catch (error) {
    message.error(error instanceof Error ? error.message : '创建失败，请稍后重试');
  } finally {
    creating.value = false;
  }
}

async function onDelete(record: TagResponse): Promise<void> {
  if (record.id === undefined) return;
  try {
    await unwrap(deleteTag({ client, throwOnError: true, path: { id: record.id } }));
    message.success('标签已删除');
    void queryClient.invalidateQueries({ queryKey: ['tags'] });
  } catch (error) {
    message.error(error instanceof Error ? error.message : '删除失败，请稍后重试');
  }
}
</script>
