/**
 * 用户批量导入导出管理界面前端行为用例（spec-delta frontend）。
 *
 * 覆盖：
 *   - 导入结果反馈：上传 xlsx → 调 importUsers → 弹层展示成功/失败计数及逐行错误原因；
 *   - 导出入口与过滤：scope 选择（学生/教师/全部）与班级过滤，触发 downloadExport；
 *   - 模板下载入口：点击下载模板，触发 downloadExport。
 *
 * 断言有效性纪律：无 exists() 守卫、无 || 双通兜底。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { message } from 'ant-design-vue';

const importUsers = vi.hoisted(() => vi.fn());
const downloadExport = vi.hoisted(() => vi.fn());

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'user-import-export' },
  apiClient: { get: vi.fn() },
  unwrap: async (value: unknown) => {
    const res = (await value) as { data?: { data?: unknown } };
    return res?.data?.data;
  },
}));

vi.mock('@/api/axios', () => ({
  importUsers,
  exportUsers: vi.fn(),
  importTemplate1: vi.fn(),
}));

vi.mock('@/utils/exportDownload', () => ({
  downloadExport,
  browserSaveFile: vi.fn(),
}));

import UserImportExportPage from '@/pages/(dashboard)/admin/users/index.page.vue';

function mountPage() {
  return mount(UserImportExportPage);
}

async function chooseFile(wrapper: ReturnType<typeof mountPage>, name: string, content: string) {
  const input = wrapper.find('input[type="file"]');
  const file = new File([content], name, {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true });
  await input.trigger('change');
}

async function clickButton(wrapper: ReturnType<typeof mountPage>, label: string) {
  const btn = wrapper.findAll('button').find((b) => b.text().replace(/\s/g, '').includes(label));
  expect(btn).toBeTruthy();
  await btn!.trigger('click');
}

function modalHtml(): string {
  return document.body.innerHTML;
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
  document.body.innerHTML = '';
});

afterEach(() => {
  document.body.innerHTML = '';
});

describe('用户批量导入导出前端界面', () => {
  it('导入结果反馈：展示成功/失败计数与逐行错误原因', async () => {
    vi.mocked(importUsers).mockResolvedValue({
      data: {
        code: 200,
        data: {
          imported: 5,
          failed: 2,
          errors: [
            { row: 3, reason: '学号已存在' },
            { row: 7, reason: '班级不存在: 高一(9)班' },
          ],
        },
      },
    } as never);

    const wrapper = mountPage();
    await chooseFile(wrapper, 'students.xlsx', 'dummy-xlsx-content');
    await flushPromises();

    expect(importUsers).toHaveBeenCalledTimes(1);
    const html = modalHtml();
    expect(html).toContain('成功导入 5 条');
    expect(html).toContain('失败 2 条');
    expect(html).toContain('学号已存在');
    expect(html).toContain('班级不存在: 高一(9)班');
  });

  it('非 xlsx 文件选择被拦截且不调用接口', async () => {
    const wrapper = mountPage();
    await chooseFile(wrapper, 'users.csv', 'csv,content');
    await flushPromises();

    expect(importUsers).not.toHaveBeenCalled();
    expect(message.error).toHaveBeenCalledWith('仅支持 .xlsx 格式的文件');
  });

  it('导入接口报错时透出错误提示', async () => {
    vi.mocked(importUsers).mockRejectedValueOnce(new Error('数据行数超过 1000 上限'));
    const wrapper = mountPage();
    await chooseFile(wrapper, 'overlimit.xlsx', 'dummy-content');
    await flushPromises();

    expect(importUsers).toHaveBeenCalledTimes(1);
    expect(message.error).toHaveBeenCalledWith('数据行数超过 1000 上限');
  });

  it('模板下载入口：点击下载模板触发 downloadExport', async () => {
    vi.mocked(downloadExport).mockResolvedValue('用户导入模板.xlsx');
    const wrapper = mountPage();

    await clickButton(wrapper, '下载导入模板');
    await flushPromises();

    expect(downloadExport).toHaveBeenCalledTimes(1);
    expect(downloadExport).toHaveBeenCalledWith(
      expect.anything(),
      '/api/admin/users/import-template',
      '用户导入模板.xlsx'
    );
    expect(message.success).toHaveBeenCalledWith('已下载：用户导入模板.xlsx');
  });

  it('导出入口与过滤：支持学生/教师/全部及班级过滤', async () => {
    vi.mocked(downloadExport).mockResolvedValue('用户导出.xlsx');
    const wrapper = mountPage();

    // 1) 默认 scope=student，无班级
    await clickButton(wrapper, '导出名单');
    await flushPromises();
    expect(downloadExport).toHaveBeenCalledWith(
      expect.anything(),
      '/api/admin/users/export?scope=student',
      '用户导出.xlsx'
    );

    // 2) scope=student 带 classId 过滤
    const inputNumber = wrapper.findComponent({ name: 'AInputNumber' });
    await inputNumber.vm.$emit('update:value', 42);
    await clickButton(wrapper, '导出名单');
    await flushPromises();
    expect(downloadExport).toHaveBeenCalledWith(
      expect.anything(),
      '/api/admin/users/export?scope=student&classId=42',
      '用户导出.xlsx'
    );

    // 3) 切换 scope 为 teacher
    const scopeSelect = wrapper.findComponent({ name: 'ASelect' });
    await scopeSelect.vm.$emit('update:value', 'teacher');
    await clickButton(wrapper, '导出名单');
    await flushPromises();
    expect(downloadExport).toHaveBeenCalledWith(
      expect.anything(),
      '/api/admin/users/export?scope=teacher',
      '用户导出.xlsx'
    );

    // 4) 切换 scope 为 all
    await scopeSelect.vm.$emit('update:value', 'all');
    await clickButton(wrapper, '导出名单');
    await flushPromises();
    expect(downloadExport).toHaveBeenCalledWith(
      expect.anything(),
      '/api/admin/users/export?scope=all',
      '用户导出.xlsx'
    );
  });
});
