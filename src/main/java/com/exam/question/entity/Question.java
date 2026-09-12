package com.exam.question.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 题目：四类题型（单选/多选/判断/简答）的题库主体，软删除。
 * correct_answer 存归一化后的答案（见 {@link com.exam.question.support.AnswerNormalizer}）；
 * score 为题目默认分值，组卷时可在试卷内覆盖（paper_questions.score），两者互不影响。
 */
@Data
@TableName("questions")
public class Question {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 题型：1单选 2多选 3判断 4简答（见 {@link QuestionType}） */
    private Integer type;

    /** 题干 */
    private String content;

    /** 客观题选项 JSON 数组（如 ["选项A","选项B"]，按顺序对应 A/B/...）；判断/简答为 null */
    private String choices;

    /** 归一化正确答案：单选字母 / 多选升序字母列表 / 判断 T-F / 简答参考答案 */
    private String correctAnswer;

    /** 默认分值 */
    private BigDecimal score;

    /** 难度：1易 2中 3难 */
    private Integer difficulty;

    /** 答案解析 */
    private String analysis;

    /** 创建教师 ID（水平越权 owner 校验依据） */
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;

    /** 软删除标记：删除仅打标，历史试卷/快照不受影响，且不再参与后续组卷 */
    @TableLogic
    @TableField(select = false)
    private Integer isDeleted;
}
