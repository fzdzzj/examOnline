package com.exam.paper.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 试卷快照（spec「抽题锁定与试卷快照」需求）：
 * 抽题/组卷结果确定时生成的一次性序列化副本（题目内容、归一化答案、试卷内分值、题号顺序）。
 * 只写不改：之后题目被修改或软删除均不影响快照（副本隔离），历史组卷结果稳定；
 * 刷新读取始终命中同一份快照，不重新抽题。
 */
@Data
@TableName("paper_snapshots")
public class PaperSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long paperId;

    /**
     * 快照 JSON：
     * {paperId, title, totalScore, questionCount, generatedTime,
     *  questions: [{number, questionId, type, content, choices, correctAnswer, score}]}
     */
    private String paperJson;

    private Integer questionCount;

    private BigDecimal totalScore;

    /** 快照版本：预留字段，同一试卷不重复生成，当前固定 1 */
    private Integer version;

    /** 生成人（试卷创建教师） */
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
}
