package com.exam.auth.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限点要求注解：标注接口需要的权限点（AND 语义，全部满足才放行）。
 * 权限点由 Token 声明携带（签发时从 RBAC 五表装载），由 RoleValidationAspect 校验。
 *
 * <p>用法：{@code @RequirePermission("invite:manage")}。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequirePermission {

    /** 要求的权限点集合，全部满足才放行 */
    String[] value();
}
