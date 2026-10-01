package com.exam.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.exam.entity.ExamCandidate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 补考名单 Mapper：常规读写走 MyBatis-Plus；名单写入走自定义 INSERT IGNORE（幂等）。
 */
@Mapper
public interface ExamCandidateMapper extends BaseMapper<ExamCandidate> {

    /**
     * 批量写入补考名单（幂等）：INSERT IGNORE 命中 uk_exam_student(exam_id, student_id)
     * 唯一索引时对该行静默跳过——重复组织同场补考不产生重复名单。
     *
     * <p>自定义 @Insert 不经过字段填充，created_time 须由调用方（MakeupService）显式赋值。
     *
     * @return 实际影响行数（首次写入的行；IGNORE 跳过的重复行不计入）
     */
    @Insert("<script>INSERT IGNORE INTO exam_candidates (exam_id, student_id, created_time) VALUES "
            + "<foreach collection='list' item='c' separator=','>"
            + "(#{c.examId}, #{c.studentId}, #{c.createdTime})"
            + "</foreach></script>")
    int insertIgnoreBatch(@Param("list") List<ExamCandidate> candidates);
}