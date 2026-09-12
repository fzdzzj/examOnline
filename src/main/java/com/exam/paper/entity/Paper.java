package com.exam.paper.entity;

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
 * 试卷：手动组卷与随机抽题的载体。
 * total_score 为教师申报的试卷总分，与各题分值之和在"保存试卷/生成快照"时校验一致；
 * status 0=草稿（组卷可编辑） 1=已锁定（快照已生成，只读，为考试阶段供数）。
 */
@Data
@TableName("papers")
public class Paper {

    /** 草稿：可继续加题/排序/调分 */
    public static final int STATUS_DRAFT = 0;

    /** 已锁定：快照已生成，题目/分值/顺序不可再改 */
    public static final int STATUS_LOCKED = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String description;

    /** 教师申报总分（各题分值之和须与其一致） */
    private BigDecimal totalScore;

    /** 试卷题目数量（随组卷操作维护） */
    private Integer questionCount;

    /** 0=草稿 1=已锁定 */
    private Integer status;

    /** 当前生效快照 ID（生成快照后回填） */
    private Long snapshotId;

    /** 创建教师 ID（owner 校验依据） */
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;

    @TableLogic
    @TableField(select = false)
    private Integer isDeleted;
}
