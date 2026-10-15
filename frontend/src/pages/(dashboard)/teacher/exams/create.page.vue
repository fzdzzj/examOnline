<template>
  <div class="max-w-3xl">
    <Card :title="isEditMode ? '编辑考试' : '新建考试'" class="mb-4">
      <template #extra>
        <Button @click="router.back()">返回</Button>
      </template>

      <!-- U-7 三态分离：编辑模式回填失败显性呈现（Alert 承载后端 message），
           且回填成功前禁用「保存修改」，防默认值误写真实考试 -->
      <Alert
        v-if="backfillErrorText"
        type="error"
        show-icon
        :message="backfillErrorText"
        data-test="exam-backfill-error"
        class="mb-4"
      />

      <Form layout="vertical">
        <FormItem label="考试标题" required>
          <Input
            v-model:value="form.title"
            :maxlength="128"
            show-count
            placeholder="请输入考试标题"
            allow-clear
          />
        </FormItem>

        <FormItem label="考试描述">
          <Input.TextArea
            v-model:value="form.description"
            :rows="3"
            :maxlength="512"
            show-count
            placeholder="请输入考试描述（可选）"
          />
        </FormItem>

        <FormItem label="绑定试卷" required>
          <Select
            v-model:value="form.paperId"
            :options="paperOptions"
            :loading="papersFetching"
            placeholder="请选择试卷（发布考试时将生成快照并锁定）"
            allow-clear
          />
        </FormItem>

        <FormItem label="班级（可选）">
          <Select
            v-model:value="form.classId"
            :options="classOptions"
            :loading="classesFetching"
            placeholder="请选择参考班级（可选）"
            allow-clear
          />
        </FormItem>

        <div class="grid grid-cols-2 gap-4">
          <FormItem label="开始时间" required>
            <DatePicker
              v-model:value="form.startTime"
              show-time
              value-format="YYYY-MM-DDTHH:mm:ss"
              format="YYYY-MM-DD HH:mm:ss"
              placeholder="选择开始时间"
              style="width: 100%"
            />
          </FormItem>
          <FormItem label="结束时间" required>
            <DatePicker
              v-model:value="form.endTime"
              show-time
              value-format="YYYY-MM-DDTHH:mm:ss"
              format="YYYY-MM-DD HH:mm:ss"
              placeholder="选择结束时间"
              style="width: 100%"
            />
          </FormItem>
        </div>

        <div class="grid grid-cols-2 gap-4">
          <FormItem label="个人答题时长（分钟）" required>
            <InputNumber v-model:value="form.durationMinutes" :min="1" style="width: 100%" />
          </FormItem>
          <FormItem label="允许迟到分钟数">
            <InputNumber v-model:value="form.allowLateMinutes" :min="0" style="width: 100%" />
          </FormItem>
        </div>

        <!-- add-exam-reservation：教师建考「需预约」开关（默认关闭，编辑表单回填） -->
        <FormItem label="需预约">
          <Space>
            <Switch
              v-model:checked="form.reservationRequired"
              data-test="reservation-required-switch"
            />
            <span class="text-sm text-gray-500">开启后学生须先预约方可进入（预约即应考）</span>
          </Space>
        </FormItem>

        <Divider>防作弊配置</Divider>

        <FormItem label="启用切屏检测">
          <Space>
            <Switch v-model:checked="enableSwitchScreen" data-test="switch-screen-switch" />
            <span class="text-sm text-gray-500">检测学生切换浏览器标签或窗口</span>
          </Space>
        </FormItem>

        <FormItem label="禁用复制粘贴">
          <Space>
            <Switch v-model:checked="enableForbidCopy" data-test="forbid-copy-switch" />
            <span class="text-sm text-gray-500">
              拦截复制/剪切/粘贴/右键并旁路上报（只警告，不交卷）
            </span>
          </Space>
        </FormItem>

        <FormItem label="要求全屏作答">
          <Space>
            <Switch v-model:checked="enableFullScreen" data-test="full-screen-switch" />
            <span class="text-sm text-gray-500">
              进入作答时引导全屏，退出全屏即记录（只警告，不交卷）
            </span>
          </Space>
        </FormItem>

        <!--
          切屏严重度阈值是**服务端全局属性**（exam.anticheat.switch-screen.medium/high-threshold），
          后端 SwitchScreenEventCollector 只读该配置、不读 exams.anti_cheat_config，
          因此这里如实说明口径而**不**写一个后端不认的键（写了就是死配置，会误导教师以为按场生效）。
        -->
        <p class="text-sm text-gray-500 mb-4" data-test="switch-threshold-note">
          切屏严重度升档阈值由服务端统一判定（升「中」3 次、升「高」5 次，服务端配置项
          exam.anticheat.switch-screen.*-threshold），不随单场考试覆盖；此处只决定是否采集切屏。
        </p>

        <FormItem label="IP 白名单（可选）">
          <Input.TextArea
            v-model:value="ipWhitelistText"
            :rows="3"
            data-test="ip-whitelist-input"
            placeholder="每行一条：192.168.1.100 / 192.168.1.* / 10.0.0.0/8；留空表示不限制 IP"
          />
          <p class="text-sm text-gray-500">
            仅名单内的 IP 可进入本场考试（服务端按单 IP / 整段通配符 / CIDR 匹配）；留空 =
            全网放行。
          </p>
        </FormItem>

        <!--
          白名单格式预检（只提示、不阻断提交）：条目写错等于把整场考生挡在 403 门外，
          所以保存前显性列出；但权威匹配器是服务端 IpMatcher，前端的判定是超集容忍的，
          不能让前端预检对合法配置拥有一票否决权。
        -->
        <Alert
          v-if="ipWhitelistIssues.length"
          type="warning"
          show-icon
          data-test="ip-whitelist-issues"
          class="mb-4"
          message="以下 IP 白名单条目按服务端匹配规则无法解析，保存后这些条目不会放行任何请求"
          :description="`请核对（支持单 IP、整段通配符如 192.168.1.*、CIDR 如 10.0.0.0/8）：${ipWhitelistIssues.join('、')}`"
        />

        <div class="mt-4 flex justify-end gap-3">
          <Button @click="router.back()">取消</Button>
          <Button
            type="primary"
            :loading="submitting"
            :disabled="isEditMode && !backfilled"
            @click="handleSubmit"
          >
            {{ isEditMode ? '保存修改' : '创建考试' }}
          </Button>
        </div>
      </Form>
    </Card>
  </div>
</template>

<script setup lang="ts">
import {
  Alert,
  Button,
  Card,
  DatePicker,
  Divider,
  Form,
  FormItem,
  Input,
  InputNumber,
  Select,
  Space,
  Switch,
  message,
} from 'ant-design-vue';
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useQuery } from '@tanstack/vue-query';

import {
  create3 as createExam,
  page1 as pagePapers,
  page3 as pageClasses,
  detail2 as examDetailContract,
  update2 as updateExamContract,
  type ClassPageResponse,
  type ClassResponse,
  type ExamDetailResponse,
  type PaperResponse,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { queryClient } from '@/api/queryClient';
import { fetchAllPages } from '@/hooks/fetchAllPages';
import {
  formatIpWhitelistText,
  invalidIpWhitelistEntries,
  parseIpWhitelistText,
  readAntiCheatPolicy,
} from '@/utils/antiCheat';

/**
 * 考试创建/编辑页面（阶段 21 考务，修复版）。
 * 后端契约：POST /api/exams（ExamCreateRequest）；编辑模式经 PUT /api/exams/{id}
 * （ExamUpdateRequest，后端 assertEditable 裁决「未发布且未开始」）；试卷 / 班级下拉取自分页接口。
 * 编辑模式由 route query 的 examId 触发（/teacher/exams/create?examId=9）：详情端点回填初始值，
 * 提交改调 update2，成功后失效 exams 查询并跳回列表（对齐发布/强制结束做法）。
 * 时间用字符串（value-format）直接对齐后端 LocalDateTime 反序列化，避免时区换算；
 * 但 value-format 必须带字面量 T（YYYY-MM-DDTHH:mm:ss）——空格分隔会被后端判
 * 400「请求体格式错误」（实测；`spring.jackson.date-format` 只作用于 java.util.Date）。
 * 展示用的 format 保持人读的 YYYY-MM-DD HH:mm:ss。
 */

const router = useRouter();
const route = useRoute();

/** 编辑模式：route query 带 examId（如 ?examId=9）即视为编辑既有考试 */
const editExamId = computed<number | undefined>(() => {
  const raw = route.query.examId;
  const value = Array.isArray(raw) ? raw[0] : raw;
  if (typeof value !== 'string' || value === '') return undefined;
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : undefined;
});
const isEditMode = computed(() => editExamId.value !== undefined);

const form = ref({
  title: '',
  description: '',
  paperId: undefined as number | undefined,
  classId: undefined as number | undefined,
  startTime: undefined as string | undefined,
  endTime: undefined as string | undefined,
  durationMinutes: 60,
  allowLateMinutes: 0,
  reservationRequired: false,
});

const enableSwitchScreen = ref(true);
const enableForbidCopy = ref(false);
const enableFullScreen = ref(false);
/** IP 白名单多行文本（每行一条）；空文本 = 提交空数组 = 不限制 IP。 */
const ipWhitelistText = ref('');

/** 白名单格式预检结果：只列"确定解析不出来"的条目（零误报优先），仅提示不阻断提交。 */
const ipWhitelistIssues = computed(() =>
  invalidIpWhitelistEntries(parseIpWhitelistText(ipWhitelistText.value))
);

const { data: editDetail, error: editBackfillError } = useQuery({
  queryKey: computed(() => ['exam-edit-detail', editExamId.value] as const),
  queryFn: () =>
    unwrap<ExamDetailResponse>(
      examDetailContract({
        client,
        throwOnError: true,
        path: { id: editExamId.value as number },
      })
    ),
  enabled: isEditMode,
});

// U-7 三态分离：编辑模式回填失败显性呈现（Alert 承载后端 message）——失败时表单不落默认值
// 误写真实考试（保存按钮随下方 isEditMode && !backfilled 禁用 + handleSubmit 拦截双保险）
const backfillErrorText = computed<string | null>(() => {
  if (!isEditMode.value) return null;
  const caught = editBackfillError.value;
  if (!caught) return null;
  return caught instanceof Error ? caught.message : '考试信息加载失败';
});

/**
 * antiCheatConfig 契约类型是 JsonNode（unknown）：按建考表单写入的键防御性读取。
 * 两个布尔键走 `readAntiCheatPolicy`（只认严格 true，与作答端守卫同一口径）；
 * `switchScreen` 保留既有语义（缺省视为开启）；`ipWhitelist` 只收字符串条目。
 */
function readAntiCheatConfig(raw: unknown): {
  switchScreen: boolean;
  forbidCopy: boolean;
  fullScreen: boolean;
  ipWhitelistText: string;
} {
  const record = (typeof raw === 'object' && raw !== null ? raw : {}) as Record<string, unknown>;
  const policy = readAntiCheatPolicy(raw);
  const entries = Array.isArray(record.ipWhitelist)
    ? record.ipWhitelist
        .filter((entry): entry is string => typeof entry === 'string')
        .map((entry) => entry.trim())
        .filter((entry) => entry !== '')
    : [];
  return {
    switchScreen: record.switchScreen !== false,
    forbidCopy: policy.forbidCopy,
    fullScreen: policy.fullScreen,
    ipWhitelistText: formatIpWhitelistText(entries),
  };
}

// 详情返回后回填一次；编辑中重新拉取不覆盖用户已改的表单
const backfilled = ref(false);
watch(
  editDetail,
  (detail) => {
    if (isEditMode.value && detail && !backfilled.value) {
      backfilled.value = true;
      form.value.title = detail.title ?? '';
      form.value.description = detail.description ?? '';
      form.value.paperId = detail.paperId;
      form.value.classId = detail.classId;
      form.value.startTime = detail.startTime;
      form.value.endTime = detail.endTime;
      form.value.durationMinutes = detail.durationMinutes ?? 60;
      form.value.allowLateMinutes = detail.allowLateMinutes ?? 0;
      form.value.reservationRequired = detail.reservationRequired === 1;
      const antiCheat = readAntiCheatConfig(detail.antiCheatConfig);
      enableSwitchScreen.value = antiCheat.switchScreen;
      enableForbidCopy.value = antiCheat.forbidCopy;
      enableFullScreen.value = antiCheat.fullScreen;
      ipWhitelistText.value = antiCheat.ipWhitelistText;
    }
  },
  { immediate: true }
);

// 候选不截断（U-2）：两个端点都是「只有 page/size、信封缺 total」的分页形状，
// 满页即续拉，语义与教师考试下拉同源（共用 fetchAllPages）；单页失败保留已累积部分。
const { data: papersData, isFetching: papersFetching } = useQuery({
  queryKey: ['papers', 'for-exam-create'] as const,
  queryFn: () =>
    fetchAllPages<PaperResponse>((page, size) =>
      unwrap<PaperResponse[]>(pagePapers({ client, throwOnError: true, query: { page, size } }))
    ),
});

const paperOptions = computed(() =>
  (papersData.value ?? []).map((p) => ({
    value: p.id as number,
    label: p.title ?? `试卷 #${p.id}`,
  }))
);

const { data: classesData, isFetching: classesFetching } = useQuery({
  queryKey: ['classes', 'for-exam-create'] as const,
  queryFn: () =>
    fetchAllPages<ClassResponse>(async (page, size) => {
      const envelope = await unwrap<ClassPageResponse>(
        pageClasses({ client, throwOnError: true, query: { page, size } })
      );
      return envelope?.list ?? [];
    }),
});

const classOptions = computed(() =>
  (classesData.value ?? []).map((c) => ({
    value: c.id as number,
    label: c.name ?? `班级 #${c.id}`,
  }))
);

const submitting = ref(false);

function validate(): boolean {
  if (!form.value.title.trim()) {
    message.warning('请输入考试标题');
    return false;
  }
  if (form.value.paperId === undefined) {
    message.warning('请选择绑定试卷');
    return false;
  }
  if (!form.value.startTime || !form.value.endTime) {
    message.warning('请选择开始与结束时间');
    return false;
  }
  if (form.value.endTime <= form.value.startTime) {
    message.warning('结束时间必须晚于开始时间');
    return false;
  }
  if (!form.value.durationMinutes || form.value.durationMinutes <= 0) {
    message.warning('个人答题时长必须为正整数');
    return false;
  }
  return true;
}

async function handleSubmit(): Promise<void> {
  // U-7 提交拦截（与保存按钮 `:disabled` 双保险）：编辑模式且回填尚未成功（失败/进行中）时拒绝提交，
  // 防默认值误写真实考试；保存按钮禁用已对常规点击生效，此拦截兜底程序化/键盘触发的提交
  if (isEditMode.value && !backfilled.value) {
    message.warning('考试信息加载失败，不能保存修改，请重试');
    return;
  }
  if (!validate()) return;
  submitting.value = true;
  try {
    const description = form.value.description.trim();
    const body = {
      title: form.value.title.trim(),
      // 编辑模式始终携带 description（空串 = 清空描述）；创建模式保留既有「空描述不携带」口径
      ...(isEditMode.value || description ? { description } : {}),
      paperId: form.value.paperId as number,
      ...(form.value.classId !== undefined ? { classId: form.value.classId } : {}),
      startTime: form.value.startTime as string,
      endTime: form.value.endTime as string,
      durationMinutes: form.value.durationMinutes,
      allowLateMinutes: form.value.allowLateMinutes ?? 0,
      reservationRequired: form.value.reservationRequired,
      antiCheatConfig: {
        switchScreen: enableSwitchScreen.value,
        forbidCopy: enableForbidCopy.value,
        fullScreen: enableFullScreen.value,
        // 空清单 = 不限 IP（服务端 AntiCheatConfigs 对空数组即"未配置白名单"）
        ipWhitelist: parseIpWhitelistText(ipWhitelistText.value),
      },
    };
    if (isEditMode.value && editExamId.value !== undefined) {
      await unwrap(
        updateExamContract({ client, throwOnError: true, path: { id: editExamId.value }, body })
      );
      message.success('考试已保存');
      void queryClient.invalidateQueries({ queryKey: ['exams'] });
    } else {
      await unwrap(createExam({ client, throwOnError: true, body }));
      message.success('考试已创建（未发布状态）');
    }
    router.push('/teacher/exams');
  } catch (error) {
    message.error(
      error instanceof Error
        ? error.message
        : isEditMode.value
          ? '保存考试失败，请稍后重试'
          : '创建考试失败，请稍后重试'
    );
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.max-w-3xl {
  max-width: 48rem;
}
.mb-4 {
  margin-bottom: 1rem;
}
.grid {
  display: grid;
}
.grid-cols-2 {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.gap-4 {
  gap: 1rem;
}
.mt-4 {
  margin-top: 1rem;
}
.flex {
  display: flex;
}
.justify-end {
  justify-content: flex-end;
}
.gap-3 {
  gap: 0.75rem;
}
.text-sm {
  font-size: 0.875rem;
}
.text-gray-500 {
  color: #6b7280;
}
</style>
