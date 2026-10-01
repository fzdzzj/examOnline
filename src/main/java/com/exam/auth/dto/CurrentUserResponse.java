package com.exam.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 当前登录用户信息（/api/auth/me）：角色与权限点取自 Token 声明（签发时从 RBAC 五表装载），
 * 强制改密标记实时读库（见 {@link #mustChangePassword} 的取舍说明）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CurrentUserResponse {

    private Long id;
    private String username;
    private String name;
    private String email;
    /** 角色码集合（如 [TEACHER]） */
    private Set<String> roles;
    /** 权限点集合（如 [exam:manage]） */
    private Set<String> permissions;

    /**
     * 是否必须修改密码（初始密码强制改密标记），由 {@code /api/auth/me} 实时读库得出。
     *
     * <p><b>取舍：刻意不作为 JWT 声明携带。</b>该标记是可变状态（改密成功即解除），而 Access Token
     * 一旦签发内容即固定，若入 claim 会留下「密码已改、旧 token 仍声称必须改密」的窗口，
     * 只能靠 jti 黑名单 / sessionVersion 兜底，语义脆弱。放在本响应里由客户端实时查询才正确。
     */
    private Boolean mustChangePassword;
}
