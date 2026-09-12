package com.exam.auth.security;

import lombok.Data;

import java.util.Set;

/**
 * 当前登录用户上下文（随 Access Token 声明签发，或由 Service 从 DB 装载）。
 * 经 AuthenticationInterceptor 解析后写入 SecurityUtil（ThreadLocal），
 * 供 AOP 切面与 Service 层取用。
 */
@Data
public class LoginUser {

    private Long id;

    /** 登录账号：学生=学号、教师=工号 */
    private String username;

    private String name;

    private String email;

    /** 角色码集合（如 [TEACHER]） */
    private Set<String> roles;

    /** 权限点集合（如 [exam:manage]） */
    private Set<String> permissions;

    /** 最高角色层级：3=ADMIN 2=TEACHER 1=STUDENT */
    private int roleLevel;

    /** 会话版本：改密码/踢人/Refresh 复用检测时递增，旧 Token 因版本不匹配立即失效 */
    private long sessionVersion;

    /** 当前 Token 的 jti（黑名单判定用） */
    private String jti;
}
