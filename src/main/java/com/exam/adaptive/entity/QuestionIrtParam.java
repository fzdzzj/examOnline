package com.exam.adaptive.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 题目 IRT 三参数标定结果（spec add-irt-adaptive-testing，3PL 模型）。
 *
 * <p>参数一律来自既有 CTT 口径的经验转换（{@code IrtParamService} 为唯一出处）：
 * {@code a} 由区分度 D 单调映射、{@code b} 由难度系数 P 经 logit 变换（中值修正防发散）、
 * {@code c} 按题型固定（单选 0.25 / 判断 0.50 / 其余 0）。标定不改写任何既有判分、
 * 掌握度与错题本口径，只供 CAT 题池消费。
 *
 * <p>样本不足（考试作答 + 练习作答 &lt; 4）的题目 {@code poolAvailable=false}，不进题池。
 */
@Data
@TableName("question_irt_params")
public class QuestionIrtParam {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 题目 ID（唯一键 uk_irt_question，标定 upsert 依据） */
    private Long questionId;

    /** a：区分度参数（由 CTT 区分度 D 映射，钳制在 [0.5, 2.5]） */
    private BigDecimal discrimination;

    /** b：难度参数（logit 尺度，b 越大越难；0 为中等难度，CAT 开局据此选题） */
    private BigDecimal difficulty;

    /** c：猜测系数（单选 0.25 / 判断 0.50 / 其余 0） */
    private BigDecimal guessing;

    /** 标定样本量（考试作答人数 + 练习作答次数） */
    private Integer sampleSize;

    /** 是否进 CAT 题池（样本不足为 false） */
    private Boolean poolAvailable;

    /** 最近一次产出该参数的考试 ID（按场次判新鲜度，TTL 内不重复标定） */
    private Long sourceExamId;

    /** 标定时间 */
    private LocalDateTime calibratedTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;
}
