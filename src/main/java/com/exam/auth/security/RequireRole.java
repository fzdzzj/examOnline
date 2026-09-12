package com.exam.auth.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色最低要求注解：标注接口需要的最低角色层级（ADMIN > TEACHER > STUDENT）。
 * 由 RoleValidationAspect 校验，当前用户最高角色层级低于要求时返回 403。
 *
 * <p>用法：{@code @RequireRole(RoleHierarchy.TEACHER)}（类级或方法级均可）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequireRole {

    /** 最低要求的角色码（如 "TEACHER"） */
    String value();
}
