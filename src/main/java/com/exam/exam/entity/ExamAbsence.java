package com.exam.exam.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 缺考记录（spec「缺考标记」，§8.10/§12.5）：
 * 考试状态机"进行中→已结束"迁移时，将「应考名单（班级当前学生）− 有答卷者」的差集写入。
 *
 * <p><b>为什么缺考在"考试结束"时标记而非"开考即标"</b>（决策记录 §8.10）：时间窗内存在允许迟到的
 * 窗口（{@code allow_late_minutes}），学生可能在开始后一段时间才进入考试——此刻点"开始"前他
 * 仍可能补交答卷，若开考即判缺考会在迟到窗口内误判；只有时间窗真正结束、整场考试不再可进入时，
 * 才能把"仍无答卷"的学生判为终局缺考。状态机"进行中→已结束"正是这个"时间窗彻底关闭"的权威时点。
 *
 * <p>幂等：uk_exam_student(exam_id, student_id) 唯一索引 + {@code INSERT IGNORE} 批量写入，
 * 同一场考试被多次扫表（定时 + 启动补偿重复触发）不会重复标记。
 */
@Data
@TableName("exam_absence")
public class ExamAbsence {

    /** 缺考已标记（预留扩展：后续如需"已安排补考"等状态可扩展，不破坏既有行） */
    public static final int STATUS_ABSENT = 0;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属考试 */
    private Long examId;

    /** 缺考学生（user id） */
    private Long studentId;

    /** 缺考状态：见 STATUS_* 常量（当前恒为 0=已标记缺考） */
    private Integer status;

    /** 缺考标记时间（服务端记录，考试结束时写入） */
    private LocalDateTime markedTime;

    private LocalDateTime createdTime;
}