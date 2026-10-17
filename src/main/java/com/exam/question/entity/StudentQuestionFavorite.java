package com.exam.question.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 学生题目个人收藏表（add-student-question-favorites）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("student_question_favorites")
public class StudentQuestionFavorite {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long studentId;

    private Long questionId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
}
