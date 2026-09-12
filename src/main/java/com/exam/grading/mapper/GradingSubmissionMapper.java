package com.exam.grading.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.grading.entity.GradingSubmission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 答卷判分 Mapper（映射 exam_submissions 表，判分/成绩域专用）：
 * 常规读写走 MyBatis-Plus Wrapper，状态汇总 CAS 走自定义 SQL。
 */
@Mapper
public interface GradingSubmissionMapper extends BaseMapper<GradingSubmission> {

    /**
     * 成绩汇总 CAS（spec「成绩汇总」场景，成绩域收口动作）：
     * 已交卷(2)/已批改(3) → 已批改(3)，写入主观分/总分/部分批改标记，version 原子自增——
     * 并发汇总同一答卷时仅一个请求影响行数为 1，总分不被交叉覆盖；
     * 重算汇总（已批改→已批改）同样走本 CAS，允许重判后刷新总分。
     */
    @Update("UPDATE exam_submissions SET status = 3, subjective_score = #{subjectiveScore}, "
            + "total_score = #{totalScore}, partial_graded = #{partialGraded}, "
            + "version = version + 1, updated_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status IN (2, 3)")
    int casSummarize(@Param("id") Long id,
                     @Param("subjectiveScore") BigDecimal subjectiveScore,
                     @Param("totalScore") BigDecimal totalScore,
                     @Param("partialGraded") int partialGraded);
}
