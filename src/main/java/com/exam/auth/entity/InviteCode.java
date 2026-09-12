package com.exam.auth.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 教师邀请码：管理员生成，教师在注册时提供有效邀请码才授予 TEACHER 角色。
 * status：0=有效 1=已作废；used_count 统计被用于注册的次数（管理员可见）。
 */
@Data
@TableName("invite_codes")
public class InviteCode {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 邀请码（唯一，随机生成） */
    private String code;

    /** 备注（管理员填写） */
    private String note;

    /** 0=有效 1=已作废 */
    private Integer status;

    /** 已被用于注册的次数 */
    private Integer usedCount;

    /** 创建人用户 ID */
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;

    @TableLogic
    @TableField(select = false)
    private Integer isDeleted;
}
