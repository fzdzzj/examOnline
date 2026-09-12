package com.exam.submission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.submission.entity.ExamSubmitDedup;
import org.apache.ibatis.annotations.Mapper;

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
}
