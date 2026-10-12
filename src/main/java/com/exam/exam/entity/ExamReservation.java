package com.exam.exam.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 考试预约记录（add-exam-reservation）：
 * 学生预约制考试（exams.reservation_required=1）时写入，是"预约名单"的唯一事实源。
 *
 * <p><b>名单即终局</b>：考试状态推进为"进行中"瞬间预约名单即锁定——开考后不可预约、不可取消，
 * 缺席/应考统计（AbsenceService / ExamAnalysisReportService）按考试结束时刻读到的有效预约行集计。
 *
 * <p><b>幂等</b>：uk_reservation_exam_student(exam_id, student_id) 唯一索引兜底，
 * 取消预约 = 物理删除，重新预约 = 重新插入，一人一场至多一条有效预约。
 *
 * <p><b>开考提醒行级幂等</b>：reminded_time 为投递标记，提醒扫描 UPDATE
 * ... WHERE reminded_time IS NULL 影响行数=1 才写消息；重复扫描/重启/多实例竞争不重发。
 */
@Data
@TableName("exam_reservations")
public class ExamReservation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属考试（须已发布且未开始才可约） */
    private Long examId;

    /** 预约学生（user id） */
    private Long studentId;

    /** 开考提醒投递时间；NULL=尚未投递（扫描候选） */
    private LocalDateTime remindedTime;

    private LocalDateTime createdTime;
}