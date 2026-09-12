package com.exam.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 当前登录用户信息（/api/auth/me）：角色与权限点取自 Token 声明（签发时从 RBAC 五表装载）。
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
}
