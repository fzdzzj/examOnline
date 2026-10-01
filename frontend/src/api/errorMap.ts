/**
 * 业务错误码 → 可读提示（手写薄封装，硬约定 3 允许）。
 *
 * 码值与后端 `com.exam.common.ResponseCode` 逐条对齐（契约里只暴露 `code: integer`，
 * 枚举本身不在 openapi.yaml 中）。**新增码必须先改后端**，前端不得自造。
 * 括号内注释为该码对应的 HTTP 状态（GlobalExceptionHandler 按 httpStatus 返回）。
 */
export const BizCode = {
  SUCCESS: 0,
  /** 400 */
  BAD_REQUEST: 400,
  /** 401 */
  UNAUTHORIZED: 401,
  /** 403 */
  FORBIDDEN: 403,
  /** 404 */
  NOT_FOUND: 404,
  /** 500 */
  INTERNAL_ERROR: 500,
  /** 400 通用「数据已存在」 */
  DATA_ALREADY_EXISTS: 1001,
  /** 400 注册：账号（学号/工号）已存在 */
  ACCOUNT_ALREADY_EXISTS: 1002,
  /** 400 注册：密码强度不足 */
  PASSWORD_TOO_WEAK: 1003,
  /** 400 注册：教师邀请码无效或已作废 */
  INVITE_CODE_INVALID: 1004,
  /** 401 登录：账号或密码错误（防账号枚举的统一提示） */
  ACCOUNT_OR_PASSWORD_ERROR: 1005,
  /** 403 登录：账号被禁用 */
  ACCOUNT_DISABLED: 1006,
  /** 423 登录：连续失败触发锁定 */
  ACCOUNT_LOCKED: 1007,
  /** 429 接口/登录限流 */
  TOO_MANY_REQUESTS: 1008,
  /** 401 令牌无效、过期、黑名单或 Refresh 复用检测触发 */
  TOKEN_INVALID: 1009,
  /** 400 找回密码：验证码无效或过期 */
  RESET_CODE_INVALID: 1010,
  /** 400 修改密码：原密码错误 */
  OLD_PASSWORD_ERROR: 1011,
  /** 409 考试状态机 CAS 冲突（阶段 20+ 使用） */
  STATE_CONFLICT: 1012,
} as const;

/**
 * 会话已被后端判定失效的码：Refresh 轮换/复用检测、令牌黑名单都会落到 1009。
 * 命中即「清本地 + 跳登录」，不做任何静默重试（硬约定 6：以后端结果为准）。
 */
export const SESSION_KILLED_CODES: readonly number[] = [
  BizCode.TOKEN_INVALID,
  BizCode.UNAUTHORIZED,
];

const MESSAGE_BY_CODE: Readonly<Record<number, string>> = {
  [BizCode.BAD_REQUEST]: '请求参数不合法',
  [BizCode.UNAUTHORIZED]: '登录状态已失效，请重新登录',
  [BizCode.FORBIDDEN]: '没有权限执行该操作',
  [BizCode.NOT_FOUND]: '请求的资源不存在',
  [BizCode.INTERNAL_ERROR]: '系统繁忙，请稍后重试',
  [BizCode.DATA_ALREADY_EXISTS]: '数据已存在，请勿重复提交',
  [BizCode.ACCOUNT_ALREADY_EXISTS]: '该账号已存在，请直接登录或换一个账号',
  [BizCode.PASSWORD_TOO_WEAK]: '密码强度不足，至少 8 位',
  [BizCode.INVITE_CODE_INVALID]: '邀请码无效或已作废，请向管理员重新获取',
  [BizCode.ACCOUNT_OR_PASSWORD_ERROR]: '账号或密码错误',
  [BizCode.ACCOUNT_DISABLED]: '账号已被禁用，请联系管理员',
  [BizCode.ACCOUNT_LOCKED]: '连续登录失败次数过多，账号已被临时锁定，请稍后再试',
  [BizCode.TOO_MANY_REQUESTS]: '操作过于频繁，请稍后再试（系统限流保护）',
  [BizCode.TOKEN_INVALID]: '登录已过期，请重新登录',
  [BizCode.RESET_CODE_INVALID]: '重置验证码错误或已过期，请重新获取',
  [BizCode.OLD_PASSWORD_ERROR]: '原密码不正确',
  [BizCode.STATE_CONFLICT]: '数据已被他人修改，请刷新后重试',
};

/**
 * 解析展示文案：本表给出固定语义，后端 message 带具体上下文（如字段校验详情）时拼在后面，
 * 未知码统一兜底，避免裸码值糊到用户脸上。
 */
export function resolveErrorMessage(code: number | undefined, serverMessage?: string): string {
  const known = code === undefined ? undefined : MESSAGE_BY_CODE[code];
  const fromServer = serverMessage?.trim();
  if (known) {
    return fromServer && fromServer !== known ? `${known}（${fromServer}）` : known;
  }
  if (fromServer) return fromServer;
  return code === undefined ? '请求失败' : `请求失败（错误码 ${code}）`;
}
