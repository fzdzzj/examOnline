package com.exam.auth.security;

import java.util.Map;

/**
 * 角色层级常量：ADMIN(3) > TEACHER(2) > STUDENT(1)。
 * 与 roles 表的 level 列、RolePermissionInitializer 保持同步。
 */
public final class RoleHierarchy {

    public static final String ADMIN = "ADMIN";
    public static final String TEACHER = "TEACHER";
    public static final String STUDENT = "STUDENT";

    private static final Map<String, Integer> LEVELS = Map.of(
            ADMIN, 3,
            TEACHER, 2,
            STUDENT, 1
    );

    private RoleHierarchy() {
    }

    /** 取单个角色层级；未知角色返回 0（低于任何业务角色）。 */
    public static int levelOf(String roleCode) {
        return LEVELS.getOrDefault(roleCode, 0);
    }

    /** 取多个角色中的最高层级（用户实际生效层级）。 */
    public static int maxLevel(Iterable<String> roleCodes) {
        int max = 0;
        if (roleCodes != null) {
            for (String code : roleCodes) {
                max = Math.max(max, levelOf(code));
            }
        }
        return max;
    }
}
