/**
 * 成绩导出下载（后端 SXSSF 流式生成的文件，前端只触发 + 保存）。
 *
 * ⚠️ 硬约定 4：导出**不在前端拼装**——后端 ExcelSheetWriter 用 SXSSFWorkbook
 * （窗口 100 行）流式写防 OOM，前端若取全量数据自拼表等于抹掉这个设计。
 * 这里用已挂拦截器的 axios 实例直接请求导出端点：
 * - `responseType: 'blob'`（apiClient 拦截器对 blob 响应原样放行，不解信封）；
 * - 文件名从 `Content-Disposition: attachment; filename*=UTF-8''…` 解析（RFC 5987）；
 * - 失败时抛 ApiError（拦截器已把非 2xx 转好），由页面显示原因并提供重试。
 *
 * 依赖注入：http 由调用方传入（生产 = apiClient，单测 = mock），
 * saveFile 可替换（生产 = 浏览器 a[download]，单测 = mock），便于纯逻辑测试。
 */

/** RFC 5987 编码文件名的解析；后端 attachment() 固定用 filename*=UTF-8''。 */
export function parseContentDispositionFileName(
  header: string | undefined,
  fallback: string
): string {
  if (!header) return fallback;
  const star = /filename\*=(?:UTF-8'')?([^;]+)/i.exec(header);
  if (star?.[1]) {
    try {
      return decodeURIComponent(star[1].trim().replace(/^"|"$/g, ''));
    } catch {
      return star[1].trim();
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(header);
  return plain?.[1]?.trim() || fallback;
}

/** 触发浏览器保存（生产实现；jsdom 无完整下载语义，单测替换掉）。 */
export function browserSaveFile(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

export interface ExportDownloadDeps {
  /** 已挂认证/续期拦截器的 axios 实例（apiClient） */
  http: {
    get: (
      url: string,
      config: { responseType: 'blob' }
    ) => Promise<{
      data: Blob;
      headers: Record<string, unknown>;
    }>;
  };
  saveFile?: (blob: Blob, fileName: string) => void;
}

/**
 * 下载一份导出文件。
 * @param url 导出端点（如 `/api/exams/1/scores/export/class-sheet`）
 * @param fallbackName 后端未给文件名时的兜底名（如 `考试1-全班成绩单.xlsx`）
 * @returns 实际保存的文件名（供页面提示「已导出 X」）
 * @throws 失败时原样抛出（ApiError / 网络错误），页面负责展示原因与重试入口
 */
export async function downloadExport(
  deps: ExportDownloadDeps,
  url: string,
  fallbackName: string
): Promise<string> {
  const response = await deps.http.get(url, { responseType: 'blob' });
  const fileName = parseContentDispositionFileName(
    response.headers?.['content-disposition'] as string | undefined,
    fallbackName
  );
  const save = deps.saveFile ?? browserSaveFile;
  save(response.data, fileName);
  return fileName;
}
