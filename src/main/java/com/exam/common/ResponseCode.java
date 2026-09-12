package com.exam.common;

import lombok.Getter;

/**
 * 统一业务响应码：{code 业务码, message 提示, httpStatus HTTP 状态}。
 * 业务码用于前端逻辑分支，httpStatus 用于 HTTP 语义。
 */
@Getter
public enum ResponseCode {

    SUCCESS(0, "ok", 200),
    BAD_REQUEST(400, "请求参数错误", 400),
    UNAUTHORIZED(401, "未登录或登录已过期", 401),
    FORBIDDEN(403, "无权限执行该操作", 403),
    NOT_FOUND(404, "资源不存在", 404),
    INTERNAL_ERROR(500, "系统内部错误", 500),

    DATA_ALREADY_EXISTS(1001, "数据已存在", 400),

    // ---- 认证与鉴权（add-authentication, W1-W2）----
    /** 注册：账号（学号/工号）已存在 */
    ACCOUNT_ALREADY_EXISTS(1002, "账号已存在", 400),
    /** 注册：密码强度不足（至少 8 位） */
    PASSWORD_TOO_WEAK(1003, "密码至少 8 位", 400),
    /** 注册：教师邀请码无效或已作废 */
    INVITE_CODE_INVALID(1004, "邀请码无效", 400),
    /** 登录：统一提示防账号枚举（账号不存在/密码错误均返回该码） */
    ACCOUNT_OR_PASSWORD_ERROR(1005, "账号或密码错误", 401),
    /** 登录：账号被禁用 */
    ACCOUNT_DISABLED(1006, "账号已被禁用", 403),
    /** 登录：连续失败触发锁定 */
    ACCOUNT_LOCKED(1007, "账号已锁定，请稍后再试", 423),
    /** 登录/接口限流 */
    TOO_MANY_REQUESTS(1008, "请求过于频繁，请稍后重试", 429),
    /** 令牌无效、过期或在黑名单中 */
    TOKEN_INVALID(1009, "登录已过期，请重新登录", 401),
    /** 找回密码：验证码错误或已过期 */
    RESET_CODE_INVALID(1010, "验证码无效或已过期", 400),
    /** 修改密码：原密码错误 */
    OLD_PASSWORD_ERROR(1011, "原密码错误", 400),

    // ---- 考试管理（add-exam-management, W3-W4）----
    /** 状态机乐观锁 CAS 影响 0 行：考试状态已被并发请求迁移，调用方应重读后重试 */
    STATE_CONFLICT(1012, "考试状态已变化，请刷新后重试", 409);

    private final int code;
    private final String message;
    private final int httpStatus;

    ResponseCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
