import { describe, expect, it } from 'vitest';
import axios from 'axios';

import { upload } from '@/api/axios';
import { createClient } from '@/api/axios/client';

/**
 * 题干图片上传 SDK 序列化护栏（add-question-image 复核阻断项 §7 修复）：
 * 阻断项：springdoc 曾把上传端点导出为 application/json requestBody，生成的 upload() 强制
 * JSON Content-Type 且无 bodySerializer——浏览器端真实请求体为 {"file":{}}，后端恒 400。
 * 修复：契约导出为 multipart/form-data 后重生成 SDK，upload() 携带 formDataBodySerializer。
 * 本护栏用【真实生成 SDK】+ 捕获型 axios 适配器直接断言序列化产物为 FormData：
 * 若 upload() 退化为 JSON 序列化（移除 bodySerializer / 强制 application/json），本测试即红。
 */
describe('题干图片上传 SDK 序列化护栏（真实生成 SDK）', () => {
  it('upload() 以 FormData 发送 file part，不得强制 application/json', async () => {
    const captured: { data?: unknown; headers?: Record<string, unknown> } = {};
    const fake = axios.create();
    fake.defaults.adapter = async (config) => {
      captured.data = config.data;
      captured.headers = config.headers as Record<string, unknown>;
      return { data: {}, status: 200, statusText: 'OK', headers: {}, config };
    };
    const cl = createClient({ axios: fake, throwOnError: true });

    const file = new File(['fake png bytes'], 'photo.png', { type: 'image/png' });
    await upload({ client: cl, throwOnError: true, body: { file } });

    // 序列化产物必须是 FormData（multipart 口径），file part 原样在表单里
    expect(captured.data).toBeInstanceOf(FormData);
    expect((captured.data as FormData).get('file')).toBe(file);
    // 不得强制 application/json（Content-Type 交给浏览器按 FormData 自动带 boundary）
    const ct =
      captured.headers && 'Content-Type' in captured.headers
        ? String(captured.headers['Content-Type'])
        : '';
    expect(ct.toLowerCase()).not.toContain('application/json');
  });
});
