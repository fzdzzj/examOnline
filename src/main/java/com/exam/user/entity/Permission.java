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
 * 权限点表，如 user:manage。
 */
@Data
@TableName("permissions")
public class Permission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    private String name;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    @TableLogic
    @TableField(select = false)
    private Integer isDeleted;
}
