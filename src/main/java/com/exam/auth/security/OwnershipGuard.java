package com.exam.auth.security;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;

import java.util.Objects;

/**
 * 水平越权防护工具（owner 校验断言）：
 * 教师类写操作 Service 在执行业务前调用，校验资源归属者是否为当前操作者；
 * ADMIN 拥有全部资源（层级 3 放行），其余用户非归属即 403。
 *
 * <p>用法（后续考试/试卷模块接入）：
 * <pre>{@code
 * OwnershipGuard.assertOwner(exam.getTeacherId(), SecurityUtil.getCurrentUser(), "考试");
 * }</pre>
 */
public final class OwnershipGuard {

    private OwnershipGuard() {
    }

    /**
     * 校验操作者对资源的归属权。
     *
     * @param resourceOwnerId 资源归属用户 ID（如 exam.teacherId）
     * @param operator        当前操作者（SecurityUtil 获取）
     * @param resourceName    资源名称（用于错误提示，如 "考试"）
     * @throws BusinessException 未登录(401) 或非归属且非 ADMIN(403)
     */
    public static void assertOwner(Long resourceOwnerId, LoginUser operator, String resourceName) {
        if (operator == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        // ADMIN 拥有全部资源（垂直授权放行）
        if (operator.getRoleLevel() >= RoleHierarchy.levelOf(RoleHierarchy.ADMIN)) {
            return;
        }
        if (!Objects.equals(resourceOwnerId, operator.getId())) {
            throw new BusinessException(ResponseCode.FORBIDDEN,
                    "无权操作该" + resourceName + "（资源不属于当前用户）");
        }
    }
}
