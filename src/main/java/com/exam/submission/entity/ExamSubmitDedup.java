package com.exam.submission.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 交卷防重记录：同一考试同一学生仅一行（uk_submit_dedup 唯一约束）。
 *
 * <p>三重幂等的第一道持久化闸：交卷时先查后插，插入成功即占有"首次提交"身份；
 * 并发插入由唯一索引兜底（DuplicateKeyException 视为已有提交在途）。
 * 与 uk_exam_student（答卷唯一）、SETNX 分布式锁一起构成三重幂等。
 */
@Data
@TableName("exam_submit_dedups")
public class ExamSubmitDedup {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long examId;

    private Long studentId;

    /** 关联答卷 ID */
    private Long submissionId;

    /** 首次提交的来源：见 ExamSubmission.SUBMIT_TYPE_* 常量 */
    private Integer submitType;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
}
