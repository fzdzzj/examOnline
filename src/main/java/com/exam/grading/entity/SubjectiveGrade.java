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
 * 主观题批改记录（spec「简答批改」需求，一卷一题一行，uk_subjective_grades 唯一）：
 *
 * <p>生命周期：判分引擎为每道简答建行（带关键词初判提示分）→ 教师批改回填终分/评语/
 * 批改人/时间 → 重判只更新提示分不动终分（§7.2：仅客观题全量重算）。
 *
 * <p>并发批改防覆盖：两教师同批一份卷时以 version 乐观锁 CAS（UPDATE ... WHERE version=?），
 * 仅一个成功，另一个收到 409 冲突后重载最新数据（spec「并发批改防覆盖」场景）。
 */
@Data
@TableName("subjective_grades")
public class SubjectiveGrade {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long submissionId;

    private Long examId;

    private Long studentId;

    /** 题目真实主键：个人快照题序可能不同，跨学生对齐靠 questionId 而非题号 */
    private Long questionId;

    /** 考试快照内题号（工作台展示用） */
    private Integer questionNumber;

    /** 学生答案快照（判分时从 answers JSON 摘出，工作台直接展示免再解析） */
    private String studentAnswer;

    /** 关键词初判提示分：仅供教师参考，不自动定分 */
    private BigDecimal suggestedScore;

    /** 初判依据（命中关键词 x/y），教师复核初判误伤 */
    private String suggestedDetail;

    /** 教师终分：NULL=未批（汇总按 0 分计入，§7.5） */
    private BigDecimal score;

    /** 评语留痕 */
    private String comment;

    /** 批改人 */
    private Long graderId;

    /** 批改时间 */
    private LocalDateTime gradedTime;

    /** 乐观锁版本号：并发批改 CAS 护栏（教师保存终分时 +1） */
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;
}
