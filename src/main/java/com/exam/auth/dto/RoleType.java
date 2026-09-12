package com.exam.auth.dto;

/**
 * 注册身份：学生自助注册（默认 STUDENT 角色）、教师注册（需有效邀请码，授予 TEACHER）。
 */
public enum RoleType {
    STUDENT,
    TEACHER;

    public static RoleType from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("注册身份不能为空");
        }
        return RoleType.valueOf(value.trim().toUpperCase());
    }
}
