package com.exam.score.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 错题重练作答明细（spec add-wrong-question-practice）：
 * 每次学生重练提交记录一条；「连对次数」「已消灭」由该 (student_id, question_id) 的明细推导。
 */
@Data
@TableName("practice_attempt")
public class PracticeAttempt {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 学生用户 ID */
    private Long studentId;

    /** 题目 ID */
    private Long questionId;

    /** 是否答对（得满分=true） */
    private Boolean isCorrect;

    /** 作答时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
}
