package com.exam.auth;

import com.exam.auth.security.LoginUser;
import com.exam.auth.security.OwnershipGuard;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * OwnershipGuard（水平越权防护）单元测试：
 * 归属放行、非归属 403、ADMIN 越级放行、未登录 401。
 */
class OwnershipGuardTest {

    private LoginUser teacher(Long id) {
        LoginUser user = new LoginUser();
        user.setId(id);
        user.setUsername("T" + id);
        user.setRoles(Set.of("TEACHER"));
        user.setRoleLevel(2);
        return user;
    }

    private LoginUser admin() {
        LoginUser user = new LoginUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setRoles(Set.of("ADMIN"));
        user.setRoleLevel(3);
        return user;
    }

    @Test
    void ownerPasses() {
        LoginUser operator = teacher(10L);
        assertDoesNotThrow(() -> OwnershipGuard.assertOwner(10L, operator, "考试"));
    }

    @Test
    void nonOwnerRejectedWithForbidden() {
        LoginUser operator = teacher(10L);
        BusinessException e = assertThrows(BusinessException.class,
                () -> OwnershipGuard.assertOwner(20L, operator, "考试"));
        assertEquals(ResponseCode.FORBIDDEN.getCode(), e.getCode());
    }

    @Test
    void adminCanAccessAnyResource() {
        LoginUser operator = admin();
        assertDoesNotThrow(() -> OwnershipGuard.assertOwner(999L, operator, "考试"));
    }

    @Test
    void anonymousRejectedWithUnauthorized() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> OwnershipGuard.assertOwner(10L, null, "考试"));
        assertEquals(ResponseCode.TOKEN_INVALID.getCode(), e.getCode());
    }
}
