package com.exam.question.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 标签：扁平结构四类（学科/难度/题型/自定义），题目可多选关联。
 * 教师共享的全局资源；软删除后同名标签可重建（因此不设 (name,type) 唯一键，查重在 Service 层）。
 */
@Data
@TableName("tags")
public class Tag {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标签名（同一类型内不重复，Service 层查重） */
    private String name;

    /** 标签类型：SUBJECT/DIFFICULTY/QUESTION_TYPE/CUSTOM（见 {@link TagType}） */
    private String type;

    /** 创建教师 ID */
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;

    @TableLogic
    @TableField(select = false)
    private Integer isDeleted;
}
