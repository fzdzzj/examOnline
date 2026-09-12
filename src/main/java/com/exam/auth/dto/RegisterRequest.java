package com.exam.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求。学生以学号注册（默认 STUDENT 角色）；
 * 教师以工号 + 有效邀请码注册（授予 TEACHER 角色）。
 */
@Data
public class RegisterRequest {

    /** 登录账号：学生=学号、教师=工号 */
    @NotBlank(message = "账号不能为空")
    @Size(max = 64, message = "账号长度不能超过 64")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "账号仅允许字母、数字、下划线与连字符")
    private String username;

    /** 密码（BCrypt 哈希存储，至少 8 位） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码至少 8 位、最多 64 位")
    private String password;

    /** 姓名 */
    @NotBlank(message = "姓名不能为空")
    @Size(max = 64, message = "姓名长度不能超过 64")
    private String name;

    /** 邮箱（可选，用于找回密码；填写时需为合法邮箱格式） */
    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱长度不能超过 128")
    private String email;

    /** 注册身份：STUDENT（学生自助） / TEACHER（教师，需邀请码） */
    @NotBlank(message = "注册身份不能为空")
    private String roleType;

    /** 教师注册邀请码（roleType=TEACHER 时必填） */
    @Size(max = 32, message = "邀请码长度不能超过 32")
    private String inviteCode;
}
