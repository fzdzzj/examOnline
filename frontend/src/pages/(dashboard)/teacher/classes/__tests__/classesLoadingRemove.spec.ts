/**
 * 班级「移出学生」按钮 loading 动效（harden-review-flow-and-resource-limits L-03）：
 *
 * - 行为：移出请求在途（roster.state.pending === `remove:${userId}`）期间，
 *   该行「移出」按钮 SHALL 呈现加载态（ant-btn-loading）且保持禁用；
 * - 词法护栏：按钮必须绑定 `:loading="removing(record)"`——变异 m4 抽掉该绑定后
 *   本护栏即红（在途状态下按钮无 loading 动画）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { message, Popconfirm } from 'ant-design-vue';

import * as api from '@/api/axios';
import ClassesPage from '@/pages/(dashboard)/teacher/classes/index.page.vue';
import classesPageSource from '@/pages/(dashboard)/teacher/classes/index.page.vue?raw';

const invalidateQueries = vi.hoisted(() => vi.fn());

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: invalidateQueries },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'classes-remove-loading' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  create4: vi.fn(),
  delete3: vi.fn(),
  joinStudent: vi.fn(),
  listStudents: vi.fn(),
  page3: vi.fn(),
  removeStudent: vi.fn(),
  transfer: vi.fn(),
  update3: vi.fn(),
}));

vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref, watch } = await import('vue');
  return {
    useQuery: (options: { queryKey: unknown; queryFn: () => unknown; enabled?: unknown }) => {
      const data = ref<unknown>(undefined);
      const error = ref<unknown>(null);
      const run = () => {
        Promise.resolve()
          .then(options.queryFn)
          .then((value: unknown) => {
            data.value = value;
          })
          .catch((reason: unknown) => {
            error.value = reason;
          });
      };
      watch(
        () => [unref(options.queryKey), unref(options.enabled ?? true)] as const,
        ([, enabled]) => {
          if (enabled) run();
        },
        { immediate: true }
      );
      return { data, error, isFetching: ref(false), refetch: vi.fn() };
    },
  };
});

const TABLE_ROWS = [{ id: 3, name: '一班', courseId: null, createdBy: 9 }];
const STUDENTS = [{ userId: 9, username: 'stu9', name: '学生9' }];

beforeEach(() => {
  invalidateQueries.mockClear();
  vi.mocked(api.page3).mockReset();
  vi.mocked(api.listStudents).mockReset();
  vi.mocked(api.removeStudent).mockReset();
  vi.mocked(api.page3).mockResolvedValue({ list: TABLE_ROWS, total: 1 } as never);
  vi.mocked(api.listStudents).mockResolvedValue(STUDENTS as never);
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

/** 打开学生列表弹层并等待学生名单加载完成。 */
async function openStudentModal(wrapper: ReturnType<typeof mount>) {
  const viewButton = wrapper.findAll('button').find((b) => b.text().includes('查看学生'));
  expect(viewButton).toBeDefined();
  viewButton?.trigger('click');
  await flushPromises();
}

/** 找到包裹「移出」按钮的 Popconfirm（学生列表弹层内），触发其 confirm。 */
function confirmRemove(wrapper: ReturnType<typeof mount>): void {
  // 弹层内 Popconfirm 的 slot 是「移出」按钮（title 是 prop 不落 DOM 文本），
  // 主列表的删除 Popconfirm slot 是「删除」，据此区分
  const popconfirm = wrapper.findAllComponents(Popconfirm).find((p) => p.text().includes('移出'));
  expect(popconfirm).toBeDefined();
  popconfirm?.vm.$emit('confirm');
}

/** 学生弹层内容经 antd Modal 传送到 body，按钮须从 document.body 查询。 */
function removeButtonInBody(): HTMLButtonElement | undefined {
  return Array.from(document.body.querySelectorAll('button')).find(
    (b) => b.textContent?.trim() === '移出'
  );
}

describe('移出按钮 loading 动效（L-03）', () => {
  it('词法护栏：按钮绑定 :loading="removing(record)"（m4 变异敏感）', () => {
    expect(classesPageSource).toContain(':loading="removing(record)"');
    expect(classesPageSource).toContain(':disabled="removing(record)"');
  });

  it('移出请求在途：对应行按钮呈加载态（ant-btn-loading）且保持禁用', async () => {
    const wrapper = mount(ClassesPage);
    await flushPromises();
    await openStudentModal(wrapper);

    // 移出请求挂起（在途未决）：roster.pending 停在 remove:9
    vi.mocked(api.removeStudent).mockReturnValue(new Promise(() => {}) as never);
    confirmRemove(wrapper);
    await flushPromises();

    const removeButton = removeButtonInBody();
    expect(removeButton).toBeDefined();
    expect(removeButton?.classList.contains('ant-btn-loading')).toBe(true);
    expect(removeButton?.getAttribute('disabled')).not.toBeNull();
    expect(api.removeStudent).toHaveBeenCalledTimes(1);
  });

  it('请求落定后 loading 复位：按钮恢复可点（锁不是一次性闸门）', async () => {
    const wrapper = mount(ClassesPage);
    await flushPromises();
    await openStudentModal(wrapper);

    vi.mocked(api.removeStudent).mockResolvedValue(undefined as never);
    confirmRemove(wrapper);
    await flushPromises();

    const removeButton = removeButtonInBody();
    expect(removeButton).toBeDefined();
    expect(removeButton?.classList.contains('ant-btn-loading')).toBe(false);
    expect(removeButton?.getAttribute('disabled')).toBeNull();
    expect(message.success).toHaveBeenCalledWith('学生已移出本班');
  });
});
