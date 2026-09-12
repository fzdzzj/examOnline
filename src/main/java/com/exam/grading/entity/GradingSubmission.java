package com.exam.grading.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 答卷判分读写模型（映射 exam_submissions 表，判分/成绩域专用视图）：
 *
 * <p>为什么另建实体而不扩展 submission 包的 ExamSubmission——答卷原始实体属于
 * 交卷域（add-exam-taking），其字段集以"交卷"为界；判分与成绩域需要读写
 * objective_score/total_score/grading_status 等本域字段。两个域各自演进时互不牵连，
 * 避免交卷域实体被判分字段撑胖（关注点分离），也保持对既有代码零改动。
 * 同表双实体在 MyBatis-Plus 下互不干扰：列名一致，各自映射各自关心的列。
 *
 * <p>状态语义与 ExamSubmission 常量保持一致（进行中/已交卷/已批改），判分只消费
 * 已交卷（status=2）之后的答卷。
 */
@Data
@TableName("exam_submissions")
public class GradingSubmission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long examId;

    private Long studentId;

    private LocalDateTime submitTime;

    /** 最终答案 JSON（questionId→答案）；NULL=尚未落库（判分前置条件） */
    private String answers;

    /** 答卷状态：见 ExamSubmission.STATUS_* 常量（1进行中 2已交卷 3已批改） */
    private Integer status;

    /** 客观题得分：判分引擎按考试快照自动判分写入 */
    private BigDecimal objectiveScore;

    /** 主观题得分：教师批改分数之和（批改保存/汇总时刷新） */
    private BigDecimal subjectiveScore;

    /** 总分 = 客观 + 主观（汇总时写入；未批简答按 0 分计入，§7.5） */
    private BigDecimal totalScore;

    /** 判分状态：0未判分 1判分成功 2判分失败（见 ObjectiveGradingService 常量） */
    private Integer gradingStatus;

    /** 判分失败原因（判分成功时置 NULL） */
    private String gradingError;

    /** 1=部分批改：存在未批简答（允许发布，未批按 0 分，§7.5） */
    private Integer partialGraded;

    /** 乐观锁版本号：状态 CAS 护栏（汇总 2→3 时原子自增） */
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;
}
