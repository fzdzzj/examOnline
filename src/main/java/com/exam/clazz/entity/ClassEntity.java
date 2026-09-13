package com.exam.clazz.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 班级实体（表名 classes）。
 *
 * <p><b>为什么命名 ClassEntity 而非 Class</b>：{@code class} 是 Java 关键字，
 * 不能用作类名/包名/变量名，故实体命名 ClassEntity、包路径用 clazz（com.exam.clazz），
 * 仅数据库表名保留 classes（见迁移文件 2026-W10-add-class.sql）。
 *
 * <p>归属关系：teacher_id 是班级的归属教师（owner 校验依据，复用 OwnershipGuard 模式），
 * created_by 是创建人——两者在"管理员代建"场景可不同，常规创建时相同。
 * course_id 与 Exam.courseId 一样是悬空 ID：课程实体后续阶段提供，先存 ID。
 */
@Data
@TableName("classes")
public class ClassEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 班级名（同一教师内不强制唯一，Service 层不做查重，spec 未要求） */
    private String name;

    /** 课程 ID（课程实体后续阶段提供，先存 ID） */
    private Long courseId;

    /** 归属教师 ID（owner 校验依据） */
    private Long teacherId;

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
