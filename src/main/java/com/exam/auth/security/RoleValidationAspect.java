package com.exam.auth.security;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 角色/权限校验切面（RBAC 授权落地）：
 * <ul>
 *   <li>{@link RequireRole}：校验当前用户最高角色层级 >= 要求角色层级（ADMIN > TEACHER > STUDENT）；</li>
 *   <li>{@link RequirePermission}：校验当前用户拥有全部要求的权限点（AND 语义）。</li>
 * </ul>
 * 当前用户取自 {@link SecurityUtil}（由 AuthenticationInterceptor 写入）；未登录直接抛 401。
 * 不满足返回 403，业务方法不会被执行。
 */
@Aspect
@Component
@Order(10)
public class RoleValidationAspect {

    @Around("@annotation(com.exam.auth.security.RequireRole) || @within(com.exam.auth.security.RequireRole)")
    public Object checkRole(ProceedingJoinPoint pjp) throws Throwable {
        LoginUser user = SecurityUtil.getCurrentUser();
        if (user == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        RequireRole requireRole = findAnnotation(pjp, RequireRole.class);
        int requiredLevel = RoleHierarchy.levelOf(requireRole.value());
        if (user.getRoleLevel() < requiredLevel) {
            throw new BusinessException(ResponseCode.FORBIDDEN,
                    "需要 " + requireRole.value() + " 及以上角色才能执行该操作");
        }
        return pjp.proceed();
    }

    @Around("@annotation(com.exam.auth.security.RequirePermission) || @within(com.exam.auth.security.RequirePermission)")
    public Object checkPermission(ProceedingJoinPoint pjp) throws Throwable {
        LoginUser user = SecurityUtil.getCurrentUser();
        if (user == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        RequirePermission requirePermission = findAnnotation(pjp, RequirePermission.class);
        for (String code : requirePermission.value()) {
            if (!user.getPermissions().contains(code)) {
                throw new BusinessException(ResponseCode.FORBIDDEN, "缺少权限点: " + code);
            }
        }
        return pjp.proceed();
    }

    /** 方法级注解优先，其次类级注解。 */
    private <A extends java.lang.annotation.Annotation> A findAnnotation(ProceedingJoinPoint pjp, Class<A> type) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        A onMethod = method.getAnnotation(type);
        if (onMethod != null) {
            return onMethod;
        }
        return pjp.getTarget().getClass().getAnnotation(type);
    }
}
