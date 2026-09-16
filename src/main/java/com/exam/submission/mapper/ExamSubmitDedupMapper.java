package com.exam.submission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.submission.entity.ExamSubmitDedup;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 交卷防重 Mapper：三重幂等的第一道持久化闸（先查后插 + 唯一索引兜底）。
 */
@Mapper
public interface ExamSubmitDedupMapper extends BaseMapper<ExamSubmitDedup> {

    /** 按 (考试, 学生) 查防重记录，存在即说明已有提交在途或完成。 */
    default ExamSubmitDedup selectByExamStudent(Long examId, Long studentId) {
        return selectOne(Wrappers.<ExamSubmitDedup>lambdaQuery()
                .eq(ExamSubmitDedup::getExamId, examId)
                .eq(ExamSubmitDedup::getStudentId, studentId));
    }

    /**
     * 按考试统计防重表行数（保留策略试算）。
     * WHERE 只带 exam_id，命中 uk_submit_dedup (exam_id, student_id) 最左前缀。
     */
    @Select("SELECT COUNT(*) FROM exam_submit_dedups WHERE exam_id = #{examId}")
    long countByExamId(@Param("examId") Long examId);

    /**
     * 按考试分批删除防重记录（保留策略）。
     * <p>WHERE 只带 exam_id（命中 uk_submit_dedup (exam_id, student_id) 最左前缀），
     * 故意不用 created_time：该列无索引，WHERE created_time &lt; ? 会全表扫描。
     */
    @Delete("DELETE FROM exam_submit_dedups WHERE exam_id = #{examId} LIMIT #{limit}")
    int deleteByExamIdBatch(@Param("examId") Long examId, @Param("limit") int limit);
}
