package com.exam.paper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 试卷-题目关联（spec「手动组卷」需求）：
 * number 为试卷内题号（1 起连续，支持调整顺序）；
 * score 为试卷内分值——覆盖题目默认分（spec「组卷与分值覆盖」场景），两者互不影响。
 * 纯关联行，物理删除。
 */
@Data
@TableName("paper_questions")
public class PaperQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long paperId;

    private Long questionId;

    /** 试卷内题号（1 起连续） */
    private Integer number;

    /** 试卷内分值（覆盖题目默认分） */
    private BigDecimal score;
}
