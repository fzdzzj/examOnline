package com.exam.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表。username 即登录账号：学生=学号、教师=工号。
 */
@Data
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录账号（唯一） */
    private String username;

    /** BCrypt 哈希 */
    private String password;

    /** 姓名 */
    private String name;

    /** 邮箱（找回密码） */
    private String email;

    /** 0=正常 1=禁用 */
    private Integer status;

    /** 1=下次登录需修改密码 */
    private Integer mustChangePassword;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;

    @TableLogic
    @TableField(select = false)
    private Integer isDeleted;
}
