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
 * 角色表。level 为层级：3=ADMIN 2=TEACHER 1=STUDENT。
 */
@Data
@TableName("roles")
public class Role {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    private String name;

    private Integer level;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    @TableLogic
    @TableField(select = false)
    private Integer isDeleted;
}
