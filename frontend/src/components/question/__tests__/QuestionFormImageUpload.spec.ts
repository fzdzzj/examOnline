import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';

import QuestionForm from '@/components/question/QuestionForm.vue';

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  create1: vi.fn(),
  list: vi.fn().mockResolvedValue([]),
  update: vi.fn(),
  upload: vi.fn(),
}));

vi.mock('@tanstack/vue-query', () => ({
  useQuery: () => ({ data: ref([]) }),
}));

import { upload } from '@/api/axios';

type FormVm = {
  beforeUpload: (file: File) => boolean;
  customUpload: (options: { file?: Blob | File }) => Promise<void>;
  removeImage: () => void;
  draft: { imageUrl: string };
  uploadError: string | null;
};

function mountForm() {
  return mount(QuestionForm, {
    props: { open: true, question: null },
  });
}

function fileOf(name: string, size: number): File {
  return { name, size } as File;
}

describe('题目表单题干图片上传（add-question-image）', () => {
  it('本地预校验与后端口径一致：非白名单扩展名 / 超 5MB 直接拦截，不发无效上传', async () => {
    const wrapper = mountForm();
    const vm = wrapper.vm as unknown as FormVm;

    expect(vm.beforeUpload(fileOf('evil.txt', 100))).toBe(false);
    expect(vm.uploadError).toContain('仅支持 png/jpg/jpeg/gif/webp 格式');

    expect(vm.beforeUpload(fileOf('big.png', 5 * 1024 * 1024 + 1))).toBe(false);
    expect(vm.uploadError).toContain('图片大小不能超过 5MB');

    expect(vm.beforeUpload(fileOf('ok.png', 1024))).toBe(true);
    expect(vm.uploadError).toBeNull();
    expect(upload).not.toHaveBeenCalled();
  });

  it('上传成功回填 imageUrl 并渲染预览（选择→上传中→预览）；移除后置空', async () => {
    vi.mocked(upload).mockResolvedValueOnce({ url: '/images/abc-123.png' } as never);
    const wrapper = mountForm();
    const vm = wrapper.vm as unknown as FormVm;

    await vm.customUpload({ file: fileOf('ok.png', 1024) });
    expect(upload).toHaveBeenCalledWith({
      client: expect.anything(),
      throwOnError: true,
      body: { file: expect.anything() },
    });
    expect(vm.draft.imageUrl).toBe('/images/abc-123.png');

    // Modal 内容 teleport 到 body：预览元素须在 document.body 断言
    await flushPromises();
    const preview = document.body.querySelector('[data-test="image-preview"]');
    expect(preview).not.toBeNull();
    expect((preview as HTMLImageElement | null)?.getAttribute('src')).toBe('/images/abc-123.png');

    vm.removeImage();
    expect(vm.draft.imageUrl).toBe('');
    await flushPromises();
    expect(document.body.querySelector('[data-test="image-preview"]')).toBeNull();
  });

  it('上传失败：错误文案透出（后端拒绝文案原样展示），不污染已回填值', async () => {
    vi.mocked(upload).mockRejectedValueOnce(new Error('图片内容与扩展名不符'));
    const wrapper = mountForm();
    const vm = wrapper.vm as unknown as FormVm;

    await vm.customUpload({ file: fileOf('fake.png', 1024) });
    expect(vm.uploadError).toBe('图片内容与扩展名不符');
    expect(vm.draft.imageUrl).toBe('');
    await flushPromises();
    const errorBox = document.body.querySelector('[data-test="image-upload-error"]');
    expect(errorBox).not.toBeNull();
    expect(errorBox?.textContent).toContain('图片内容与扩展名不符');
  });
});
