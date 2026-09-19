/**
 * 成绩导出：前端只触发后端导出 + 保存，失败显式原因且可重试（硬约定 4）。
 * 不做「取全量数据在前端拼表」，因此这里不涉及任何 Excel 组装逻辑。
 */
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/types';
import { downloadExport, parseContentDispositionFileName } from '../exportDownload';

describe('导出文件名解析（RFC 5987）', () => {
  it("优先取 filename*=UTF-8'' 编码名", () => {
    const header =
      "attachment; filename*=UTF-8''%E5%85%A8%E7%8F%AD%E6%88%90%E7%BB%A9%E5%8D%95.xlsx";
    expect(parseContentDispositionFileName(header, 'fallback.xlsx')).toBe('全班成绩单.xlsx');
  });

  it('退化为普通 filename', () => {
    expect(parseContentDispositionFileName('attachment; filename="sheet.xlsx"', 'f.xlsx')).toBe(
      'sheet.xlsx'
    );
  });

  it('没有头或解析不出时用兜底名', () => {
    expect(parseContentDispositionFileName(undefined, 'fallback.xlsx')).toBe('fallback.xlsx');
    expect(parseContentDispositionFileName('attachment', 'fallback.xlsx')).toBe('fallback.xlsx');
  });
});

describe('导出下载与失败重试', () => {
  function httpWith(impl: () => Promise<{ data: Blob; headers: Record<string, unknown> }>): {
    http: Parameters<typeof downloadExport>[0]['http'];
    calls: string[];
  } {
    const calls: string[] = [];
    const http = {
      get: (url: string) => {
        calls.push(url);
        return impl();
      },
    };
    return { http, calls };
  }

  it('成功：按后端文件名保存并返回文件名', async () => {
    const { http } = httpWith(() =>
      Promise.resolve({
        data: new Blob(['x']),
        headers: { 'content-disposition': "attachment; filename*=UTF-8''score.xlsx" },
      })
    );
    const saveFile = vi.fn();

    const name = await downloadExport(
      { http: http as Parameters<typeof downloadExport>[0]['http'], saveFile },
      '/api/exams/1/scores/export/class-sheet',
      'fallback.xlsx'
    );

    expect(name).toBe('score.xlsx');
    expect(saveFile).toHaveBeenCalledOnce();
    expect(saveFile.mock.calls[0]?.[1]).toBe('score.xlsx');
  });

  it('失败：抛出后端原因（ApiError 文案），不静默、不本地兜底', async () => {
    const { http } = httpWith(() => Promise.reject(new ApiError(403, '没有权限执行该操作')));
    const saveFile = vi.fn();

    await expect(
      downloadExport(
        { http: http as Parameters<typeof downloadExport>[0]['http'], saveFile },
        '/api/exams/1/scores/export/class-sheet',
        'fallback.xlsx'
      )
    ).rejects.toThrow('没有权限执行该操作');
    expect(saveFile).not.toHaveBeenCalled();
  });

  it('可重试：同一个 kind 再点一次就是重新发同一个请求（前端保留 lastKind）', async () => {
    let attempt = 0;
    const { http, calls } = httpWith(() => {
      attempt += 1;
      if (attempt === 1) {
        return Promise.reject(new ApiError(500, '系统繁忙，请稍后重试'));
      }
      return Promise.resolve({ data: new Blob(['x']), headers: {} });
    });
    const deps = { http: http as Parameters<typeof downloadExport>[0]['http'], saveFile: vi.fn() };
    const url = '/api/exams/1/scores/export/detail';

    await expect(downloadExport(deps, url, 'fallback.xlsx')).rejects.toThrow('系统繁忙');
    const name = await downloadExport(deps, url, '逐题得分明细.xlsx');

    expect(name).toBe('逐题得分明细.xlsx');
    expect(calls).toEqual([url, url]);
  });
});
