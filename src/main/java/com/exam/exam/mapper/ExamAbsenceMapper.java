package com.exam.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.exam.entity.ExamAbsence;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 缺考 Mapper：常规读写走 MyBatis-Plus；缺考标记批量写走自定义 INSERT IGNORE（幂等）。
 */
@Mapper
public interface ExamAbsenceMapper extends BaseMapper<ExamAbsence> {

    /**
     * 批量写入缺考记录（幂等）：INSERT IGNORE 命中 uk_exam_student(exam_id, student_id)
     * 唯一索引时对该行静默跳过——同一场考试多次扫表（定时 + 启动补偿）不会重复写缺考。
     *
     * <p>注意：自定义 @Insert 不经过 MyBatis-Plus 字段填充，id/status/marked_time/created_time
     * 等须在调用方（AbsenceService）显式赋值。
     *
     * @return 实际影响行数（首次写入的行；IGNORE 跳过的重复行不计入）
     */
    @Insert("<script>INSERT IGNORE INTO exam_absence "
            + "(exam_id, student_id, status, marked_time, created_time) VALUES "
            + "<foreach collection='list' item='a' separator=','>"
            + "(#{a.examId}, #{a.studentId}, #{a.status}, #{a.markedTime}, #{a.createdTime})"
            + "</foreach></script>")
    int insertIgnoreBatch(@Param("list") List<ExamAbsence> absences);
}